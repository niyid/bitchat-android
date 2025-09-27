package com.bitchat.android.monero.wallet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.m2049r.xmrwallet.data.Node;
import com.m2049r.xmrwallet.model.PendingTransaction;
import com.m2049r.xmrwallet.model.Wallet;
import com.m2049r.xmrwallet.model.WalletListener;
import com.m2049r.xmrwallet.model.WalletManager;
import com.m2049r.xmrwallet.util.Helper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.*;
import java.net.Socket;
import java.net.InetSocketAddress;

/**
 * WalletSuite integrates Monerujo JNI bindings into BitChat.
 * It handles wallet creation, restoration, syncing, daemon config, and transactions.
 * Wallet configuration and JNI bindings are delegated to WalletManager.
 */
public class WalletSuite {
    private static final String TAG = "WalletSuite";
    private static final String PROPERTIES_FILE = "wallet.properties";

    private static volatile boolean nativeOk = false;
    private static volatile boolean nativeChecked = false;
    private static volatile WalletSuite instance;
    private static final int SYNC_TIMEOUT_MS = 500000; // 2 minutes
    private static final int STUCK_THRESHOLD = 100; // 100 progress updates without change
    private static final int MAX_REFRESH_RETRIES = 3;
    private static final int BASE_TIMEOUT_MS = 120000; // min 2m
    private static final int MAX_TIMEOUT_MS  = 600000; // cap at 10m

    // Sync state management
    private volatile long syncStartTime;
    private volatile long lastSyncHeight = -1;
    private volatile int stuckCounter = 0;
    private volatile int refreshRetryCount = 0;
    private volatile boolean syncCompleted = false;
    private volatile boolean walletPersisted = false;
    private volatile WalletListener currentListener;
    private volatile long lastActivityTime = 0;
    private volatile String currentWalletPath; // Track wallet path for persistence
    private final Object syncLock = new Object();
    private final Object persistLock = new Object();

    // Runtime fields
    private volatile Wallet wallet;
    private final WalletManager walletManager;
    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private volatile boolean isInitialized = false;
    private volatile boolean isSyncing = false;
    private volatile String walletAddress;

    // listeners
    private volatile WalletStatusListener statusListener;
    private volatile TransactionListener transactionListener;

    // Interfaces
    public interface WalletStatusListener {
        void onWalletInitialized(boolean success, String message);
        void onBalanceUpdated(long balance, long unlockedBalance);
        void onSyncProgress(long height, long startHeight, long endHeight, double percentDone);
    }

    public interface TransactionListener {
        void onTransactionCreated(String txId, long amount);
        void onTransactionConfirmed(String txId);
        void onTransactionFailed(String txId, String error);
        void onOutputReceived(long amount, String txHash, boolean isConfirmed);
    }

    public interface AddressCallback {
        void onSuccess(String address);
        void onError(String error);
    }

    public interface BalanceCallback {
        void onSuccess(long balance, long unlockedBalance);
        void onError(String error);
    }

    public interface SendCallback {
        void onSuccess(String txId, long amount, long fee);
        void onError(String error);
    }

    public interface SyncCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface SeedCallback {
        void onSuccess(String seedPhrase);
        void onError(String error);
    }

    public interface SyncInfoCallback {
        void onSuccess(long walletHeight, long daemonHeight, boolean isSynced);
        void onError(String error);
    }

    public interface TransactionHistoryCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface TxBlobCallback {
        void onSuccess(String txId, String base64Blob);
        void onError(String error);
    }

    public static class SyncStatus {
        public final boolean syncing;
        public final long walletHeight;
        public final long daemonHeight;
        public final double percentDone;
        
        public SyncStatus(boolean syncing, long walletHeight, long daemonHeight, double percentDone) {
            this.syncing = syncing;
            this.walletHeight = walletHeight;
            this.daemonHeight = daemonHeight;
            this.percentDone = percentDone;
        }
    }    
    
    public interface DaemonConfigCallback {
        void onConfigNeeded();
        void onConfigError(String error);
    }

    private volatile DaemonConfigCallback daemonConfigCallback;

    public void setDaemonConfigCallback(DaemonConfigCallback callback) {
        this.daemonConfigCallback = callback;
    }    

    private WalletSuite(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.walletManager = WalletManager.getInstance();
        loadConfiguration();
    }

    public static synchronized WalletSuite getInstance(Context context) {
        if (instance == null) {
            instance = new WalletSuite(context);
            if (!nativeAvailable()) {
                Log.e(TAG, "Failed to load native library monerujo");
            }
        }
        return instance;
    }

    public static synchronized void resetInstance(Context context) {
        if (instance != null) {
            try {
                instance.close();
            } catch (Exception ignored) {}
            instance = null;
        }
        instance = new WalletSuite(context);
    }
    
    public String getCachedAddress() {
        return walletAddress;
    }
    
    private int getDynamicSyncTimeout(int blocksPerSecond) {
        if (blocksPerSecond <= 1) {
            return MAX_TIMEOUT_MS; // very slow sync, allow more slack
        } else if (blocksPerSecond < 20) {
            return 300000; // 5 min
        } else {
            return BASE_TIMEOUT_MS; // normal fast sync
        }
    }
    
    public void close() {
        stopSync();
        
        synchronized (persistLock) {
            try {
                if (wallet != null && isInitialized) {
                    wallet.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error closing wallet", e);
            } finally {
                wallet = null;
                isInitialized = false;
                isSyncing = false;
                syncCompleted = false;
                currentWalletPath = null; // Clear the tracked path
            }
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void startSyncWhenReady() {
        Log.d(TAG, "Checking if wallet is ready for sync...");
        
        executorService.execute(() -> {
            int maxRetries = 20; // 10 seconds max wait
            int retries = 0;
            
            while (retries < maxRetries) {
                if (isWalletReadyForSync()) {
                    Log.d(TAG, "Wallet is ready for sync after " + retries + " checks");
                    mainHandler.post(this::startSync);
                    return;
                } else {
                    Log.d(TAG, "Wallet not ready for sync, retrying... (" + retries + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Interrupted while waiting for wallet readiness");
                        return;
                    }
                    retries++;
                }
            }
            
            Log.e(TAG, "Wallet failed to become ready for sync after " + maxRetries + " attempts");
        });
    }
    
    private boolean isWalletReadyForSync() {
        if (wallet == null) {
            Log.d(TAG, "Wallet readiness check failed: wallet is null");
            return false;
        }
        
        if (!isInitialized) {
            Log.d(TAG, "Wallet readiness check failed: not initialized");
            return false;
        }
        
        if (isSyncing) {
            Log.d(TAG, "Wallet readiness check failed: already syncing");
            return false;
        }
        
        try {
            int status = wallet.getStatus();
            if (status != Wallet.Status.Status_Ok.ordinal()) {
                Log.d(TAG, "Wallet readiness check failed: status=" + status);
                return false;
            }
            
            String address = wallet.getAddress();
            if (address == null || address.isEmpty()) {
                Log.d(TAG, "Wallet readiness check failed: invalid address");
                return false;
            }
            
            // CRITICAL FIX: Check if daemon is actually responding to RPC calls
            long daemonHeight = wallet.getDaemonBlockChainHeight();
            if (daemonHeight <= 0) {
                Log.d(TAG, "Wallet readiness check failed: daemon not responding (height=" + daemonHeight + ")");
                return false;
            }
            
            Log.d(TAG, "Wallet readiness check: current height=" + wallet.getBlockChainHeight() + ", daemon height=" + daemonHeight);
            Log.d(TAG, "Wallet readiness check passed");
            return true;
            
        } catch (Exception e) {
            Log.d(TAG, "Wallet readiness check failed with exception: " + e.getMessage());
            return false;
        }
    }

    private void startSync() {
        synchronized (syncLock) {
            Log.d(TAG, "Starting wallet sync...");
            isSyncing = true;
            syncCompleted = false; // Reset completion flag
            walletPersisted = false; // Reset persistence flag
            syncStartTime = System.currentTimeMillis();
            lastSyncHeight = -1;
            stuckCounter = 0;
            refreshRetryCount = 0;
            lastActivityTime = System.currentTimeMillis();
        }

        try {
            // Clean up any existing listener
            if (currentListener != null && wallet != null) {
                wallet.setListener(null);
            }

            currentListener = new WalletListener() {
                @Override
                public void moneySent(String txId, long amount) {
                    if (transactionListener != null) {
                        mainHandler.post(() ->
                                transactionListener.onTransactionCreated(txId, amount));
                    }
                }

                @Override
                public void moneyReceived(String txId, long amount) {
                    Log.d(TAG, "Money received: " + txId + " amount: " + amount);
                    if (transactionListener != null) {
                        mainHandler.post(() ->
                                transactionListener.onOutputReceived(amount, txId, true));
                    }
                }

                @Override
                public void unconfirmedMoneyReceived(String txId, long amount) {
                    Log.d(TAG, "Unconfirmed money received: " + txId + " amount: " + amount);
                    if (transactionListener != null) {
                        mainHandler.post(() ->
                                transactionListener.onOutputReceived(amount, txId, false));
                    }
                }

                @Override
                public void refreshed() {
                    Log.d(TAG, "Wallet refreshed");
                    updateSyncActivity();

                    if (statusListener != null) {
                        try {
                            long walletHeight = wallet.getBlockChainHeight();
                            long daemonHeight = wallet.getDaemonBlockChainHeight();
                            double percent = (daemonHeight > 0)
                                    ? (100.0 * walletHeight / daemonHeight)
                                    : 0.0;

                            Log.d(TAG, "Refresh progress: " + walletHeight + "/" + daemonHeight +
                                    " (" + String.format("%.1f", percent) + "%)");

                            mainHandler.post(() ->
                                    statusListener.onSyncProgress(walletHeight, walletHeight, daemonHeight, percent));

                            // Check for sync completion
                            if (daemonHeight > 0 && walletHeight >= daemonHeight) {
                                checkAndCompleteSyncIfReady();
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "Error in refreshed callback", e);
                        }
                    }
                }

                @Override
                public void newBlock(long height) {
                    Log.d(TAG, "New block received at height: " + height);
                    updateSyncActivity();

                    if (statusListener != null) {
                        try {
                            long walletHeight = wallet.getBlockChainHeight();
                            long daemonHeight = wallet.getDaemonBlockChainHeight();
                            double percent = (daemonHeight > 0)
                                    ? (100.0 * walletHeight / daemonHeight)
                                    : 0.0;

                            Log.d(TAG, "Sync progress: " + walletHeight + "/" + daemonHeight +
                                    " (" + String.format("%.1f", percent) + "%)");

                            mainHandler.post(() ->
                                    statusListener.onSyncProgress(walletHeight, 0, daemonHeight, percent));

                            // Check for sync completion
                            if (daemonHeight > 0 && walletHeight >= daemonHeight) {
                                checkAndCompleteSyncIfReady();
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "Error in newBlock callback", e);
                        }
                    }
                }

                @Override
                public void updated() {
                    Log.d(TAG, "Wallet updated callback triggered");
                    if (statusListener != null) {
                        try {
                            long balance = wallet.getBalance();
                            long unlocked = wallet.getUnlockedBalance();
                            Log.d(TAG, "Balance updated: " + balance + " (unlocked: " + unlocked + ")");
                            mainHandler.post(() ->
                                    statusListener.onBalanceUpdated(balance, unlocked));
                        } catch (Exception e) {
                            Log.e(TAG, "Error in updated callback", e);
                        }
                    }
                }
            };

            wallet.setListener(currentListener);

            // Enhanced daemon validation
            if (!validateDaemonConnection()) {
                handleSyncError("Daemon connection validation failed");
                return;
            }

            // Log detailed status
            logDetailedWalletStatus();

            // CRITICAL FIX: Use synchronous refresh first to establish proper connection
            executorService.execute(() -> {
                try {
                    Log.d(TAG, "Starting initial synchronous refresh...");
                    wallet.refresh();
                    Log.d(TAG, "Synchronous refresh completed, starting async refresh...");
                    
                    mainHandler.post(() -> {
                        if (isSyncing && wallet != null) {
                            try {
                                wallet.refreshAsync();
                                Log.d(TAG, "Async refresh started successfully");
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to start async refresh", e);
                                handleSyncError("Async refresh failed: " + e.getMessage());
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Synchronous refresh failed", e);
                    mainHandler.post(() -> handleSyncError("Initial sync failed: " + e.getMessage()));
                }
            });
            
            // Start monitoring
            Log.d(TAG, "Starting sync monitoring...");
            scheduleProgressUpdates();
            scheduleSyncTimeout();

        } catch (Exception e) {
            Log.e(TAG, "Exception during sync setup", e);
            handleSyncError("Sync setup failed: " + e.getMessage());
        }
    }

    private void updateSyncActivity() {
        synchronized (syncLock) {
            stuckCounter = 0;
            lastActivityTime = System.currentTimeMillis();
        }
    }

    private void checkAndCompleteSyncIfReady() {
        synchronized (syncLock) {
            if (syncCompleted || !isSyncing) {
                Log.d(TAG, "Sync already completed or not syncing, skipping completion");
                return;
            }
            
            try {
                long walletHeight = wallet.getBlockChainHeight();
                long daemonHeight = wallet.getDaemonBlockChainHeight();
                
                if (daemonHeight > 0 && walletHeight >= daemonHeight) {
                    syncCompleted = true;
                    completeSyncSuccess();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking sync completion status", e);
            }
        }
    }
    
    private void completeSyncSuccess() {
        Log.d(TAG, "Sync completed successfully");
        
        synchronized (syncLock) {
            isSyncing = false;
        }

        if (statusListener != null) {
            try {
                long walletHeight = wallet.getBlockChainHeight();
                long daemonHeight = wallet.getDaemonBlockChainHeight();
                statusListener.onSyncProgress(walletHeight, walletHeight, daemonHeight, 100.0);
            } catch (Exception e) {
                Log.e(TAG, "Error in success callback", e);
            }
        }

        // Persist wallet data safely
        persistWalletSafely();
    }

    private void persistWalletSafely() {
        executorService.execute(() -> {
            synchronized (persistLock) {
                if (walletPersisted) {
                    Log.d(TAG, "Wallet already persisted, skipping");
                    return;
                }
                
                if (wallet == null) {
                    Log.w(TAG, "Cannot persist wallet - wallet is null");
                    return;
                }
                
                if (!isInitialized) {
                    Log.w(TAG, "Cannot persist wallet - not initialized");
                    return;
                }
                
                try {
                    // Additional safety checks before persisting
                    int status = wallet.getStatus();
                    if (status != Wallet.Status.Status_Ok.ordinal()) {
                        Log.w(TAG, "Cannot persist wallet - invalid status: " + status);
                        return;
                    }
                    
                    // Verify wallet is still functional
                    String address = wallet.getAddress();
                    if (address == null || address.isEmpty()) {
                        Log.w(TAG, "Cannot persist wallet - invalid address");
                        return;
                    }
                    
                    // Verify wallet has a valid path
                    String walletPath = wallet.getPath();
                    if (walletPath == null || walletPath.isEmpty()) {
                        Log.w(TAG, "Cannot persist wallet - no valid internal path");
                        return;
                    }
                    
                    Log.d(TAG, "Persisting wallet with status=" + status + 
                          ", address=" + address.substring(0, Math.min(10, address.length())) + "..." +
                          ", path=" + walletPath);
                    
                    // Store using explicit path (calling the native method directly)
                    boolean stored = wallet.store(walletPath);
                    if (stored) {
                        walletPersisted = true;
                        Log.d(TAG, "Wallet data persisted successfully to: " + walletPath);
                    } else {
                        String error = wallet.getErrorString();
                        Log.e(TAG, "Wallet store(path) returned false. Path: " + walletPath + 
                              ", Error: " + (error != null ? error : "unknown"));
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Exception during wallet persistence: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                    
                    // Try to get more info about the wallet state
                    try {
                        if (wallet != null) {
                            String walletPath = wallet.getPath();
                            Log.e(TAG, "Wallet state during error - Status: " + wallet.getStatus() + 
                                      ", Error: " + wallet.getErrorString() + 
                                      ", Path: " + walletPath);
                        }
                    } catch (Exception statusEx) {
                        Log.e(TAG, "Could not get wallet status during error handling", statusEx);
                    }
                }
            }
        });
    }

    private boolean validateDaemonConnection() {
        try {
            if (wallet == null) {
                Log.w(TAG, "Daemon validation failed: wallet is null");
                return false;
            }
            
            // Test both basic connection and RPC responsiveness
            long daemonHeight = wallet.getDaemonBlockChainHeight();
            if (daemonHeight <= 0) {
                Log.w(TAG, "Daemon height is invalid: " + daemonHeight);
                return false;
            }
            
            // Additional RPC call test
            String daemonAddress = walletManager.getDaemonAddress();
            if (daemonAddress == null || daemonAddress.isEmpty()) {
                Log.w(TAG, "Daemon address is not set");
                return false;
            }
            
            Log.d(TAG, "Daemon connection validated: height=" + daemonHeight + ", address=" + daemonAddress);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Daemon validation failed with exception", e);
            return false;
        }
    }

    private boolean shouldContinueSync() {
        synchronized (syncLock) {
            long now = System.currentTimeMillis();
            return (now - lastActivityTime) <= SYNC_TIMEOUT_MS;
        }
    }

    private void scheduleNextRefresh() {
        synchronized (syncLock) {
            refreshRetryCount++;
        }
        
        mainHandler.postDelayed(() -> {
            if (wallet != null && isSyncing) {
                Log.d(TAG, "Continuing refresh (retry " + refreshRetryCount + ")");
                try {
                    wallet.refreshAsync();
                } catch (Exception e) {
                    Log.e(TAG, "Error scheduling next refresh", e);
                    handleSyncError("Refresh error: " + e.getMessage());
                }
            }
        }, 2000);
    }
    
    private final Runnable syncTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (isSyncing) {
                Log.w(TAG, "Sync timeout reached, restarting");
                restartSync();
            }
        }
    };
    
    private void scheduleSyncTimeout() {
        synchronized (syncLock) {
            lastActivityTime = System.currentTimeMillis();
        }

        mainHandler.removeCallbacks(syncTimeoutRunnable);
        mainHandler.postDelayed(syncTimeoutRunnable, SYNC_TIMEOUT_MS);
    }

    private void scheduleProgressUpdates() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isSyncing || wallet == null) return;
                
                try {
                    long walletHeight = wallet.getBlockChainHeight();
                    long daemonHeight = wallet.getDaemonBlockChainHeight();
                    
                    synchronized (syncLock) {
                        if (walletHeight == lastSyncHeight) {
                            stuckCounter++;
                            if (stuckCounter >= STUCK_THRESHOLD) {
                                Log.w(TAG, "Sync appears stuck at height " + walletHeight + 
                                      " for " + stuckCounter + " updates, restarting");
                                restartSync();
                                return;
                            }
                        } else {
                            stuckCounter = 0;
                            lastSyncHeight = walletHeight;
                        }
                    }
                    
                    double percent = (daemonHeight > 0) 
                        ? (100.0 * walletHeight / daemonHeight) 
                        : 0.0;
                    
                    Log.d(TAG, "Progress update: " + walletHeight + "/" + 
                          daemonHeight + " (" + String.format("%.1f", percent) + "%) " +
                          "stuck=" + stuckCounter);
                    
                    if (statusListener != null) {
                        statusListener.onSyncProgress(walletHeight, walletHeight, 
                                                    daemonHeight, percent);
                    }
                    
                    mainHandler.postDelayed(this, 2000);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error in progress update", e);
                    handleSyncError("Progress update failed: " + e.getMessage());
                }
            }
        }, 2000);
    }

    private void restartSync() {
        Log.d(TAG, "Restarting sync...");
        stopSync();
        
        executorService.execute(() -> {
            boolean success = setDaemonFromConfigAndApply();
            mainHandler.post(() -> {
                if (success) {
                    Log.d(TAG, "Daemon reconnected successfully, restarting sync");
                    mainHandler.postDelayed(() -> startSyncWhenReady(), 1000);
                } else {
                    Log.e(TAG, "Failed to reconnect to daemon during restart");
                    handleSyncError("Failed to reconnect to daemon");
                }
            });
        });
    }

    private void stopSync() {
        synchronized (syncLock) {
            isSyncing = false;
            syncCompleted = false;
            walletPersisted = false;
        }

        mainHandler.removeCallbacks(syncTimeoutRunnable);

        if (wallet != null && currentListener != null) {
            try {
                wallet.setListener(null);
            } catch (Exception e) {
                Log.w(TAG, "Error removing wallet listener", e);
            }
        }

        currentListener = null;
    }

    private void handleSyncError(String error) {
        Log.e(TAG, "Sync error: " + error);
        stopSync();

        mainHandler.removeCallbacks(syncTimeoutRunnable);

        if (statusListener != null) {
            mainHandler.post(() -> statusListener.onWalletInitialized(false, error));
        }

        if (daemonConfigCallback != null) {
            mainHandler.post(() -> daemonConfigCallback.onConfigError(error));
        }
    }

    private void logDetailedWalletStatus() {
        Log.d(TAG, "=== Detailed Wallet Status ===");
        if (wallet != null) {
            try {
                int status = wallet.getStatus();
                Log.d(TAG, "Status: " + status + " (" + Wallet.Status.values()[status] + ")");
                
                long currentHeight = wallet.getBlockChainHeight();
                Log.d(TAG, "Wallet height: " + currentHeight);
                
                long daemonHeight = wallet.getDaemonBlockChainHeight();
                Log.d(TAG, "Daemon height: " + daemonHeight);
                
                String daemonAddress = walletManager.getDaemonAddress();
                Log.d(TAG, "Daemon address: " + daemonAddress);
                
                Log.d(TAG, "Network type: " + walletManager.getNetworkType());
                
            } catch (Exception e) {
                Log.w(TAG, "Error logging detailed wallet status", e);
            }
        }
        Log.d(TAG, "=============================");
    }
    
    public boolean isSyncing() {
        return isSyncing;
    }

    public SyncStatus getSyncStatus() {
        if (!isInitialized || wallet == null) {
            return new SyncStatus(false, 0, 0, 0.0);
        }
        try {
            long walletHeight = wallet.getBlockChainHeight();
            long daemonHeight = wallet.getDaemonBlockChainHeight();
            double percent = daemonHeight > 0 ? (100.0 * walletHeight / daemonHeight) : 0.0;
            return new SyncStatus(isSyncing, walletHeight, daemonHeight, percent);
        } catch (Exception e) {
            return new SyncStatus(false, 0, 0, 0.0);
        }
    }

    private static boolean nativeAvailable() {
        if (!nativeChecked) {
            synchronized (WalletSuite.class) {
                if (!nativeChecked) {
                    try {
                        System.loadLibrary("monerujo");
                        nativeOk = true;
                    } catch (Throwable e) {
                        nativeOk = false;
                        Log.e(TAG, "Failed to load native library monerujo", e);
                    }
                    nativeChecked = true;
                }
            }
        }
        return nativeOk;
    }

    private void loadConfiguration() {
        Properties props = new Properties();
        boolean loaded = false;
        File external = new File(context.getExternalFilesDir(null), PROPERTIES_FILE);
        File internal = new File(context.getFilesDir(), PROPERTIES_FILE);

        if (external.exists()) {
            try (FileInputStream fis = new FileInputStream(external)) {
                props.load(fis);
                loaded = true;
                Log.i(TAG, "Loaded config from external");
            } catch (IOException e) {
                Log.w(TAG, "Failed to load external config", e);
            }
        }

        if (!loaded && internal.exists()) {
            try (FileInputStream fis = new FileInputStream(internal)) {
                props.load(fis);
                loaded = true;
                Log.i(TAG, "Loaded config from internal");
            } catch (IOException e) {
                Log.w(TAG, "Failed to load internal config", e);
            }
        }

        if (!loaded) {
            try (InputStream is = context.getAssets().open(PROPERTIES_FILE)) {
                props.load(is);
                loaded = true;
                Log.i(TAG, "Loaded config from assets");
            } catch (IOException e) {
                Log.w(TAG, "No config found in assets; using defaults");
            }
        }

        walletManager.applyConfiguration(props);
    }

    public boolean setDaemonFromConfigAndApply() {
        try {
            Node node = walletManager.createNodeFromConfig();
            walletManager.setDaemon(node);
            Log.i(TAG, "Daemon set to: " + node.displayProperties());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set daemon", e);
            return false;
        }
    }

    private void setupWallet() {
        if (wallet == null) return;
        
        boolean daemonSet = setDaemonFromConfigAndApply();
        if (!daemonSet) {
            Log.e(TAG, "Failed to establish daemon connection during setup");
        }
    }
    
    public void testDaemonConnection(WalletManager walletManager) {
        new Thread(() -> {
            try {
                Log.d("NetworkTest", "Testing with Daemon Address: " + walletManager.getDaemonAddress() + " Port: " + walletManager.getDaemonPort());
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(walletManager.getDaemonAddress(), walletManager.getDaemonPort()), 10000);
                Log.d("NetworkTest", "Successfully connected to daemon");
                socket.close();
            } catch (Exception e) {
                Log.e("NetworkTest", "Cannot connect to daemon: " + e.getMessage());
            }
        }).start();
    }

    public Future<Boolean> initializeWallet() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        executorService.execute(() -> {
            try {
                File dir = context.getDir("wallets", Context.MODE_PRIVATE);
                if (!dir.exists() && !dir.mkdirs()) {
                    notifyWalletInitialized(false, "Cannot create wallets dir");
                    future.complete(false);
                    return;
                }

                String walletPath = new File(dir, walletManager.getWalletName()).getAbsolutePath();
                currentWalletPath = walletPath; // Store path for later use
                File keysFile = new File(walletPath + ".keys");

                if (keysFile.exists()) {
                    Log.d(TAG, "Opening existing wallet at " + walletPath);
                    wallet = walletManager.openWallet(walletPath);
                } else {
                    Log.d(TAG, "Creating new wallet at " + walletPath);
                    wallet = walletManager.createWallet(walletPath);
                }

                if (wallet == null) {
                    notifyWalletInitialized(false, "JNI returned null wallet");
                    future.complete(false);
                    return;
                }

                try {
                    Node node = walletManager.createNodeFromConfig();
                    long handle = wallet.initJ(
                            node.getAddress(),
                            0,
                            node.getUsername(),
                            node.getPassword(),
                            node.isSsl(),
                            false,
                            ""
                    );
                    Log.d(TAG, "Wallet initJ returned handle=" + handle);

                    if (handle == 0) {
                        Log.e(TAG, "initJ failed, falling back to setDaemonFromConfigAndApply()");
                        setDaemonFromConfigAndApply();
                    } else {
                        node = walletManager.createNodeFromConfig();
                        walletManager.setNetworkType(node.getNetworkType());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "initJ threw exception, falling back to setDaemonFromConfigAndApply()", e);
                    setDaemonFromConfigAndApply();
                }

                int status = wallet.getStatus();
                String errorStr = wallet.getErrorString();
                if (errorStr == null) errorStr = "<empty>";

                Log.d(TAG, "Wallet status: " + status + " (" + Wallet.Status.values()[status] + ")");
                Log.d(TAG, "Wallet error string: " + errorStr);

                try {
                    testDaemonConnection(walletManager);
                    walletAddress = wallet.getAddress();
                    Log.d(TAG, "Wallet address: " + wallet.getAddress());
                    Log.d(TAG, "Wallet seed language: " + wallet.getSeedLanguage());
                    Log.d(TAG, "Wallet restore height: " + wallet.getRestoreHeight());
                    Log.d(TAG, "Wallet blockchain height: " + wallet.getBlockChainHeight());
                    Log.d(TAG, "Daemon blockchain height: " + wallet.getDaemonBlockChainHeight());
                    Log.d(TAG, "Daemon: " + walletManager.getDaemonAddress() + ":" + walletManager.getDaemonPort());
                    Log.d(TAG, "Network type: " + walletManager.getNetworkType());
                } catch (Exception e) {
                    Log.w(TAG, "Unable to fetch wallet metadata: " + e.getMessage());
                }

                if (status == Wallet.Status.Status_Ok.ordinal()) {
                    setupWallet();
                    
                    boolean daemonConnected = testDaemonConnectionSync();
                    if (!daemonConnected) {
                        Log.w(TAG, "Daemon connection test failed");
                        if (daemonConfigCallback != null) {
                            mainHandler.post(() -> daemonConfigCallback.onConfigNeeded());
                            future.complete(false);
                            return;
                        }
                    }
                    
                    isInitialized = true;
                    notifyWalletInitialized(true, "Wallet initialized");

                    startSyncWhenReady();
                    future.complete(true);
                    return;
                }

                notifyWalletInitialized(false,
                        "Init failed: status=" + status + " err=" + errorStr);

                if (status == Wallet.Status.Status_Critical.ordinal()) {
                    Log.w(TAG, "Wallet status is CRITICAL. Deleting wallet files for path " + walletPath);

                    safeDelete(new File(walletPath));
                    safeDelete(new File(walletPath + ".keys"));
                    safeDelete(new File(walletPath + ".address.txt"));

                    wallet = walletManager.createWallet(walletPath);
                    if (wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal()) {
                        setupWallet();
                        isInitialized = true;
                        notifyWalletInitialized(true, "Wallet recreated successfully");
                        startSyncWhenReady();
                        future.complete(true);
                        return;
                    } else {
                        Log.e(TAG, "Wallet recreation also failed");
                    }
                }

                future.complete(false);

            } catch (Exception e) {
                Log.e(TAG, "Exception during wallet init", e);
                notifyWalletInitialized(false, "Error: " + e.getMessage());
                
                if (e.getMessage() != null && 
                    (e.getMessage().contains("daemon") || 
                     e.getMessage().contains("connection") ||
                     e.getMessage().contains("network")) && 
                    daemonConfigCallback != null) {
                    mainHandler.post(() -> daemonConfigCallback.onConfigNeeded());
                }
                
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private boolean testDaemonConnectionSync() {
        try {
            if (wallet != null) {
                long daemonHeight = wallet.getDaemonBlockChainHeight();
                boolean connected = daemonHeight > 0;
                Log.d(TAG, "Daemon connection test: " + (connected ? "SUCCESS" : "FAILED") + " (height=" + daemonHeight + ")");
                return connected;
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Daemon connection test failed: " + e.getMessage());
            return false;
        }
    }

    private void safeDelete(File f) {
        try {
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "Could not delete " + f.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "Error deleting " + f.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    public void initializeWalletFromSeed(String seed, long restoreHeight, int requestedNetType) {
        int netType = (requestedNetType >= 0) ? requestedNetType : walletManager.getNetworkType().ordinal();
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();
                currentWalletPath = walletPath; // Store path for later use
                wallet = walletManager.recoveryWallet(walletPath, seed, restoreHeight);
                if (wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal()) {
                    setupWallet();
                    isInitialized = true;
                    notifyWalletInitialized(true, "Wallet restored");
                    startSyncWhenReady();
                } else {
                    String error = (wallet != null) ? wallet.getErrorString() : "JNI error";
                    notifyWalletInitialized(false, "Restore failed: " + error);
                }
            } catch (Exception e) {
                notifyWalletInitialized(false, "Error: " + e.getMessage());
            }
        });
    }

    private String getWalletPath() {
        File walletDir = new File(context.getFilesDir(), "monero");
        if (!walletDir.exists()) walletDir.mkdirs();
        return new File(walletDir, walletManager.getWalletName()).getAbsolutePath();
    }

    public void getAddress(AddressCallback cb) {
        if (!isInitialized || wallet == null) { 
            cb.onError("Wallet not ready"); 
            return; 
        }
        executorService.execute(() -> {
            try { 
                String a = wallet.getAddress(); 
                mainHandler.post(() -> cb.onSuccess(a)); 
            }
            catch (Exception e) { 
                mainHandler.post(() -> cb.onError(e.getMessage())); 
            }
        });
    }

    public void getBalance(BalanceCallback cb) {
        if (!isInitialized || wallet == null) { 
            cb.onError("Wallet not ready"); 
            return; 
        }
        executorService.execute(() -> {
            try {
                long bal = wallet.getBalance();
                long ubal = wallet.getUnlockedBalance();
                
                mainHandler.post(() -> cb.onSuccess(bal, ubal));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public static String convertAtomicToXmr(long atomicAmount) {
        double xmr = atomicAmount / 1e12d;
        return String.format("%.12f", xmr).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public void createTxBlob(String to, String amount, TxBlobCallback cb) {
        if (!isInitialized || wallet == null) { 
            cb.onError("Wallet not ready"); 
            return; 
        }
        executorService.execute(() -> {
            try {
                long atomic = Helper.getAmountFromString(amount);
                PendingTransaction tx = wallet.createTransaction(to, "", atomic, 0, PendingTransaction.Priority.Priority_Default.ordinal());
                if (tx.getStatus() != PendingTransaction.Status.Status_Ok.ordinal()) {
                    mainHandler.post(() -> cb.onError(tx.getErrorString()));
                    return;
                }
                byte[] raw = tx.getSerializedTransaction();
                String b64 = Base64.encodeToString(raw, Base64.NO_WRAP);
                mainHandler.post(() -> cb.onSuccess(tx.getFirstTxId(), b64));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void submitTxBlob(byte[] blob, TxBlobCallback cb) {
        if (!isInitialized || wallet == null) { 
            cb.onError("Wallet not ready"); 
            return; 
        }
        executorService.execute(() -> {
            try {
                String hex = bytesToHex(blob);
                String txId = wallet.submitTransaction(hex);
                String b64 = Base64.encodeToString(blob, Base64.NO_WRAP);
                mainHandler.post(() -> cb.onSuccess(txId, b64));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public void reloadConfiguration() {
        loadConfiguration();
        Log.i(TAG, "Config reloaded from: " + getCurrentConfigPath());
        if (wallet != null) setDaemonFromConfigAndApply();
    }

    public static void copyDefaultConfigToExternalStorage(Context ctx) {
        try {
            File dest = new File(ctx.getExternalFilesDir(null), PROPERTIES_FILE);
            if (dest.exists()) { 
                Log.i(TAG, "Config already exists: " + dest.getAbsolutePath()); 
                return; 
            }
            try (InputStream is = ctx.getAssets().open(PROPERTIES_FILE);
                 FileOutputStream os = new FileOutputStream(dest)) {
                byte[] buf = new byte[1024];
                int r;
                while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            }
            Log.i(TAG, "Copied default config to " + dest.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy default config", e);
        }
    }

    public String getCurrentConfigPath() {
        File external = new File(context.getExternalFilesDir(null), PROPERTIES_FILE);
        if (external.exists()) return external.getAbsolutePath();
        File internal = new File(context.getFilesDir(), PROPERTIES_FILE);
        if (internal.exists()) return internal.getAbsolutePath();
        return "assets/" + PROPERTIES_FILE;
    }

    public void setWalletStatusListener(WalletStatusListener l) { 
        this.statusListener = l; 
    }
    
    public void setTransactionListener(TransactionListener l) { 
        this.transactionListener = l; 
    }

    private void notifyWalletInitialized(boolean ok, String msg) {
        if (statusListener != null) 
            mainHandler.post(() -> statusListener.onWalletInitialized(ok, msg));
    }

    public boolean isReady() {
        return isInitialized && wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal();
    }
}
