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
import com.m2049r.xmrwallet.model.TransactionHistory;
import com.m2049r.xmrwallet.model.TransactionInfo;
import com.m2049r.xmrwallet.util.Helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import java.net.HttpURLConnection;
import java.net.URL;

public class WalletSuite {
    private static final String TAG = "WalletSuite";
    private static final String PROPERTIES_FILE = "wallet.properties";

    private static volatile boolean nativeOk = false;
    private static volatile boolean nativeChecked = false;
    private static volatile WalletSuite instance;
    private static final int SYNC_TIMEOUT_MS = 500000;
    private static final int STUCK_THRESHOLD = 100;
    private static final int MAX_REFRESH_RETRIES = 3;
    private static final int BASE_TIMEOUT_MS = 120000;
    private static final int MAX_TIMEOUT_MS = 600000;

    private volatile long syncStartTime;
    private volatile long lastSyncHeight = -1;
    private volatile long lastKnownDaemonHeight = -1;
    private volatile boolean rescanAttempted = false;
    private volatile int stuckCounter = 0;
    private volatile int refreshRetryCount = 0;
    private volatile boolean syncCompleted = false;
    private volatile boolean walletPersisted = false;
    private volatile WalletListener currentListener;
    private volatile long lastActivityTime = 0;
    private volatile String currentWalletPath;
    private final Object syncLock = new Object();
    private final Object persistLock = new Object();

    private volatile Wallet wallet;
    private final WalletManager walletManager;
    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private volatile boolean isInitialized = false;
    private volatile boolean isSyncing = false;
    private volatile String walletAddress;

    private volatile WalletStatusListener statusListener;
    private volatile TransactionListener transactionListener;

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
    
    public interface TransactionImportCallback {
        void onSuccess(String txId);
        void onError(String error);
    }
    
    public interface TransactionCallback {
        void onSuccess(String txId, long amount);
        void onError(String error);
    }

    public interface TransactionSearchCallback {
        void onTransactionFound(String txId, long amount, long confirmations, long blockHeight);
        void onTransactionNotFound(String txId);
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
        registerShutdownHandler();
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
            } catch (Exception ignored) {
            }
            instance = null;
        }
        instance = new WalletSuite(context);
    }

    public String getCachedAddress() {
        return walletAddress;
    }

    private void registerShutdownHandler() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.d(TAG, "Shutdown hook triggered - closing wallet");
                closeWalletSync();
            }));
        } catch (Exception e) {
            Log.w(TAG, "Could not register shutdown hook", e);
        }
    }

    private void closeWalletSync() {
        synchronized (persistLock) {
            try {
                if (wallet != null && isInitialized) {
                    Log.d(TAG, "Performing synchronous wallet close");
                    isSyncing = false;
                    syncCompleted = false;
                    
                    if (currentListener != null) {
                        try {
                            wallet.setListener(null);
                        } catch (Exception e) {
                            Log.w(TAG, "Error removing listener during sync close", e);
                        }
                        currentListener = null;
                    }
                    
                    if (!walletPersisted && currentWalletPath != null) {
                        try {
                            Log.d(TAG, "Persisting wallet before close");
                            wallet.store(currentWalletPath);
                            walletPersisted = true;
                        } catch (Exception e) {
                            Log.e(TAG, "Error persisting wallet during shutdown", e);
                        }
                    }
                    
                    wallet.close();
                    Log.d(TAG, "Wallet closed successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during synchronous wallet closure", e);
            } finally {
                wallet = null;
                isInitialized = false;
                walletPersisted = false;
                currentWalletPath = null;
            }
        }
    }

    private int getDynamicSyncTimeout(int blocksPerSecond) {
        if (blocksPerSecond <= 1) {
            return MAX_TIMEOUT_MS;
        } else if (blocksPerSecond < 20) {
            return 300000;
        } else {
            return BASE_TIMEOUT_MS;
        }
    }
    
    private long getDaemonHeightViaHttp() {
        HttpURLConnection conn = null;
        try {
            String daemonAddress = walletManager.getDaemonAddress();
            int daemonPort = walletManager.getDaemonPort();

            if (daemonAddress == null || daemonAddress.isEmpty()) {
                Log.w(TAG, "Daemon address not configured");
                return lastKnownDaemonHeight > 0 ? lastKnownDaemonHeight : -1;
            }

            String daemonUrl = "http://" + daemonAddress + ":" + daemonPort + "/get_height";
            URL url = new URL(daemonUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line.trim());
                    }
                }

                String jsonResponse = response.toString();
                int heightIndex = jsonResponse.indexOf("\"height\":");
                if (heightIndex != -1) {
                    int startIndex = heightIndex + 9;
                    int endIndex = jsonResponse.indexOf(",", startIndex);
                    if (endIndex == -1) {
                        endIndex = jsonResponse.indexOf("}", startIndex);
                    }
                    if (endIndex != -1) {
                        long height = Long.parseLong(jsonResponse.substring(startIndex, endIndex).trim());
                        lastKnownDaemonHeight = height;
                        Log.d(TAG, "Daemon height from HTTP: " + height);
                        return height;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting daemon height: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (lastKnownDaemonHeight > 0) {
            Log.d(TAG, "Using cached daemon height: " + lastKnownDaemonHeight);
            return lastKnownDaemonHeight;
        }

        return -1;
    }
    
    public void close() {
        Log.d(TAG, "Close called - beginning shutdown sequence");
        stopSync();
        closeWalletSync();

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                Log.w(TAG, "Executor service did not terminate in time, forcing shutdown");
                executorService.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Log.d(TAG, "Shutdown sequence completed");
    }

    private void startSyncWhenReady() {
        Log.d(TAG, "Checking if wallet is ready for sync...");

        executorService.execute(() -> {
            int maxRetries = 20;
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

            long daemonHeight = getDaemonHeightViaHttp();
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
            syncCompleted = false;
            walletPersisted = false;
            syncStartTime = System.currentTimeMillis();
            lastSyncHeight = -1;
            stuckCounter = 0;
            refreshRetryCount = 0;
            lastActivityTime = System.currentTimeMillis();
        }

        executorService.execute(() -> {
            if (!validateDaemonConnection()) {
                mainHandler.post(() -> handleSyncError("Daemon connection validation failed"));
                return;
            }
            
            mainHandler.post(this::setupSyncListenerAndStart);
        });
    }

    private void setupSyncListenerAndStart() {
        try {
            if (currentListener != null && wallet != null) {
                wallet.setListener(null);
            }

            currentListener = new WalletListener() {
                @Override
                public void moneySent(String txId, long amount) {
                    Log.d(TAG, "moneySent: txId=" + txId + " amount=" + amount);
                    updateSyncActivity();
                }

                @Override
                public void moneyReceived(String txId, long amount) {
                    Log.d(TAG, "moneyReceived: txId=" + txId + " amount=" + amount);
                    updateSyncActivity();
                    if (transactionListener != null) {
                        mainHandler.post(() -> transactionListener.onOutputReceived(amount, txId, false));
                    }
                }

                @Override
                public void unconfirmedMoneyReceived(String txId, long amount) {
                    Log.d(TAG, "unconfirmedMoneyReceived: txId=" + txId + " amount=" + amount);
                    updateSyncActivity();
                }

                @Override
                public void newBlock(long height) {
                    Log.d(TAG, "newBlock: height=" + height);
                    updateSyncActivity();
                    checkAndCompleteSyncIfReady();
                }

                @Override
                public void updated() {
                    Log.d(TAG, "Wallet updated callback");
                    updateSyncActivity();
                    checkAndCompleteSyncIfReady();
                }

                @Override
                public void refreshed() {
                    Log.d(TAG, "Wallet refreshed callback");
                    updateSyncActivity();
                    checkAndCompleteSyncIfReady();

                    if (!shouldContinueSync()) {
                        Log.w(TAG, "Sync timeout detected in refreshed()");
                        mainHandler.post(() -> restartSync());
                        return;
                    }

                    if (!syncCompleted && refreshRetryCount < MAX_REFRESH_RETRIES) {
                        scheduleNextRefresh();
                    }
                }
            };

            wallet.setListener(currentListener);
            logDetailedWalletStatus();

            executorService.execute(() -> {
                try {
                    Log.d(TAG, "=== STARTING SYNC SEQUENCE ===");
                    
                    Log.d(TAG, "Step 1: Starting initial synchronous refresh...");
                    long refreshStartTime = System.currentTimeMillis();
                    wallet.refresh();
                    long refreshDuration = System.currentTimeMillis() - refreshStartTime;
                    
                    Log.d(TAG, "Synchronous refresh completed in " + refreshDuration + "ms");
                    
                    Log.d(TAG, "Step 2: Waiting 500ms before starting async refresh...");
                    Thread.sleep(500);
                    
                    Log.d(TAG, "Step 3: Starting async refresh on main thread...");
                    mainHandler.post(() -> {
                        synchronized (syncLock) {
                            if (!isSyncing) {
                                Log.d(TAG, "Sync already completed during initial refresh, skipping async refresh");
                                return;
                            }
                            if (wallet == null) {
                                Log.w(TAG, "Wallet is null, cannot start async refresh");
                                return;
                            }
                        }
                        
                        try {
                            Log.d(TAG, "Calling wallet.refreshAsync()...");
                            wallet.refreshAsync();
                            Log.d(TAG, "Async refresh started successfully");
                            Log.d(TAG, "=== SYNC SEQUENCE COMPLETED ===");
                            
                            scheduleProgressUpdates();
                            scheduleSyncTimeout();
                            
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to start async refresh", e);
                            handleSyncError("Async refresh failed: " + e.getMessage());
                        }
                    });
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "Sync interrupted during setup", e);
                    mainHandler.post(() -> handleSyncError("Sync interrupted: " + e.getMessage()));
                } catch (Exception e) {
                    Log.e(TAG, "Synchronous refresh failed", e);
                    mainHandler.post(() -> handleSyncError("Initial sync failed: " + e.getMessage()));
                }
            });

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
                return;
            }

            try {
                long walletHeight = wallet.getBlockChainHeight();
                long daemonHeight = getDaemonHeightViaHttp();

                if (daemonHeight > 0 && walletHeight >= daemonHeight) {
                    syncCompleted = true;
                    Log.d(TAG, "Sync target reached: wallet=" + walletHeight + " daemon=" + daemonHeight);
                    completeSyncSuccess();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking sync completion status", e);
            }
        }
    }

    private void completeSyncSuccess() {
        Log.d(TAG, "Sync completed successfully");

        long balance = 0;
        long unlockedBalance = 0;

        try {
            balance = wallet.getBalance();
            unlockedBalance = wallet.getUnlockedBalance();
            Log.d(TAG, "Current Balance: " + balance + " atomic units");
            Log.d(TAG, "Unlocked Balance: " + unlockedBalance + " atomic units");
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving balance for logging", e);
        }

        synchronized (syncLock) {
            isSyncing = false;
        }

        if (statusListener != null) {
            try {
                long walletHeight = wallet.getBlockChainHeight();
                long daemonHeight = getDaemonHeightViaHttp();
                statusListener.onSyncProgress(walletHeight, walletHeight, daemonHeight, 100.0);
                statusListener.onBalanceUpdated(balance, unlockedBalance);
                Log.d(TAG, "onBalanceUpdated() called with balance=" + balance
                        + " unlocked=" + unlockedBalance);
            } catch (Exception e) {
                Log.e(TAG, "Error in success callback", e);
            }
        }

        // ALWAYS persist the successful sync state first
        persistWalletSafely();
        Log.d(TAG, "Wallet state persisted after successful sync");

        // Only then, if balance is 0, attempt rescan
        if (balance == 0 && unlockedBalance == 0 && !rescanAttempted) {
            rescanAttempted = true;
            Log.w(TAG, "Balance is 0 after sync, scheduling rescan...");

            // Create atomic flag to track rescan state
            final AtomicBoolean rescanInProgress = new AtomicBoolean(true);
            
            // Start monitoring thread
            executorService.execute(() -> {
                Log.d(TAG, "Rescan monitor thread started");
                long lastHeight = 0;
                int noChangeCount = 0;
                
                while (rescanInProgress.get()) {
                    try {
                        Thread.sleep(5000); // Check every 5 seconds
                        
                        if (wallet != null) {
                            long currentHeight = wallet.getBlockChainHeight();
                            long currentBalance = wallet.getBalance();
                            
                            if (currentHeight != lastHeight) {
                                Log.d(TAG, "RESCAN PROGRESS: height=" + currentHeight 
                                        + ", balance=" + currentBalance);
                                lastHeight = currentHeight;
                                noChangeCount = 0;
                            } else {
                                noChangeCount++;
                                if (noChangeCount > 6) { // 30 seconds with no change
                                    Log.w(TAG, "RESCAN: No height change for 30 seconds - might be stuck");
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Rescan monitor interrupted");
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in rescan monitor", e);
                    }
                }
                
                Log.d(TAG, "Rescan monitor thread stopped");
            });

            // Start actual rescan
            executorService.execute(() -> {
                Log.d(TAG, "Rescan runnable STARTED on thread: " + Thread.currentThread().getName());
                
                try {
                    // Verify wallet is still valid before rescanning
                    if (wallet == null || !isInitialized) {
                        Log.w(TAG, "Cannot rescan: wallet is null or not initialized");
                        rescanInProgress.set(false);
                        return;
                    }
                    
                    int status = wallet.getStatus();
                    Log.d(TAG, "Wallet status: " + status);
                    
                    if (status != Wallet.Status.Status_Ok.ordinal()) {
                        Log.w(TAG, "Cannot rescan: wallet status is not OK: " + status);
                        rescanInProgress.set(false);
                        return;
                    }
                    
                    long currentRestoreHeight = wallet.getRestoreHeight();
                    long safeStartHeight = Math.max(currentRestoreHeight - 1000, 0);
                    
                    Log.d(TAG, "Setting restore height from " + currentRestoreHeight 
                            + " to " + safeStartHeight);
                    wallet.setRestoreHeight(safeStartHeight);
                    
                    Log.d(TAG, "Starting rescanBlockchainAsync...");
                    long rescanStartTime = System.currentTimeMillis();
                    
                    wallet.rescanBlockchainAsync(new Wallet.RescanCallback() {
                        @Override
                        public void onSuccess() {
                            rescanInProgress.set(false);
                            long rescanDuration = System.currentTimeMillis() - rescanStartTime;
                            
                            Log.d(TAG, "=== RESCAN COMPLETED SUCCESSFULLY in " 
                                    + (rescanDuration / 1000) + " seconds ===");
                            
                            try {
                                if (wallet == null) {
                                    Log.w(TAG, "Rescan complete but wallet is null");
                                    return;
                                }
                                
                                long b = wallet.getBalance();
                                long ub = wallet.getUnlockedBalance();
                                long height = wallet.getBlockChainHeight();
                                
                                Log.d(TAG, "Rescan results: height=" + height 
                                        + ", balance=" + b + ", unlocked=" + ub);
                                
                                // Persist again after successful rescan
                                persistWalletSafely();
                                Log.d(TAG, "Wallet state persisted after rescan");
                                
                                mainHandler.post(() -> {
                                    if (statusListener != null) {
                                        statusListener.onBalanceUpdated(b, ub);
                                        Log.d(TAG, "onBalanceUpdated() called after rescan");
                                    }
                                });
                            } catch (Exception e) {
                                Log.e(TAG, "Error in rescan success callback", e);
                            }
                        }

                        @Override
                        public void onError(String error) {
                            rescanInProgress.set(false);
                            Log.e(TAG, "=== RESCAN FAILED: " + error + " ===");
                        }
                    });
                    
                    Log.d(TAG, "rescanBlockchainAsync() called, waiting for callback...");
                    
                } catch (Exception e) {
                    rescanInProgress.set(false);
                    Log.e(TAG, "Exception during rescan setup", e);
                }
            });
        }
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
                    int status = wallet.getStatus();
                    if (status != Wallet.Status.Status_Ok.ordinal()) {
                        Log.w(TAG, "Cannot persist wallet - invalid status: " + status);
                        return;
                    }

                    String address = wallet.getAddress();
                    if (address == null || address.isEmpty()) {
                        Log.w(TAG, "Cannot persist wallet - invalid address");
                        return;
                    }

                    String walletPath = wallet.getPath();
                    if (walletPath == null || walletPath.isEmpty()) {
                        Log.w(TAG, "Cannot persist wallet - no valid internal path");
                        return;
                    }

                    Log.d(TAG, "Persisting wallet with status=" + status +
                            ", address=" + address.substring(0, Math.min(10, address.length())) + "..." +
                            ", path=" + walletPath);

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

            long daemonHeight = getDaemonHeightViaHttp();
            if (daemonHeight <= 0) {
                Log.w(TAG, "Daemon height is invalid: " + daemonHeight);
                return false;
            }

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
                    long daemonHeight = getDaemonHeightViaHttp();

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

                long daemonHeight = getDaemonHeightViaHttp();
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
            long daemonHeight = getDaemonHeightViaHttp();
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
                currentWalletPath = walletPath;
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
                    long daemonHeight = getDaemonHeightViaHttp();
                    if (daemonHeight > 0) {
                        Log.d(TAG, "Daemon connection test successful (height: " + daemonHeight + ")");
                    } else {
                        Log.w(TAG, "Daemon connection test failed");
                    }

                    walletAddress = wallet.getAddress();
                    Log.d(TAG, "Wallet address: " + wallet.getAddress());
                    Log.d(TAG, "Wallet seed language: " + wallet.getSeedLanguage());
                    Log.d(TAG, "Wallet restore height: " + wallet.getRestoreHeight());
                    Log.d(TAG, "Wallet blockchain height: " + wallet.getBlockChainHeight());
                    Log.d(TAG, "Daemon blockchain height: " + getDaemonHeightViaHttp());
                    Log.d(TAG, "Daemon: " + walletManager.getDaemonAddress() + ":" + walletManager.getDaemonPort());
                    Log.d(TAG, "Network type: " + walletManager.getNetworkType());
                } catch (Exception e) {
                    Log.w(TAG, "Unable to fetch wallet metadata: " + e.getMessage());
                }

                if (status == Wallet.Status.Status_Ok.ordinal()) {
                    setupWallet();

                    long daemonHeight = getDaemonHeightViaHttp();
                    boolean daemonConnected = daemonHeight > 0;

                    if (!daemonConnected) {
                        Log.w(TAG, "Daemon connection test failed (height: " + daemonHeight + ")");
                        if (daemonConfigCallback != null) {
                            mainHandler.post(() -> daemonConfigCallback.onConfigNeeded());
                            future.complete(false);
                            return;
                        }
                    } else {
                        Log.d(TAG, "Daemon connection test successful (height: " + daemonHeight + ")");
                    }

                    isInitialized = true;
                    notifyWalletInitialized(true, "Wallet initialized");

                    long initBal = wallet.getBalance();
                    long initUb = wallet.getUnlockedBalance();
                    Log.d(TAG, "Initial wallet state: balance=" + initBal + " atomic units, unlocked=" + initUb + " atomic units");
                    if (initBal == 0 && initUb == 0) {
                        Log.d(TAG, "Initial balances are zero - rescan will be triggered after sync if balances remain zero");
                    }

                    startSyncWhenReady();
                    future.complete(true);
                    return;
                }

                notifyWalletInitialized(false,
                        "Init failed: status=" + status + " err=" + errorStr);

                if (status == Wallet.Status.Status_Critical.ordinal()) {
                    Log.w(TAG, "Wallet status is CRITICAL. Backing up wallet files for path " + walletPath);

                    safeMove(new File(walletPath));
                    safeMove(new File(walletPath + ".keys"));
                    safeMove(new File(walletPath + ".address.txt"));

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

    public void initializeWalletFromSeed(String seed, long restoreHeight, int requestedNetType) {
        int netType = (requestedNetType >= 0) ? requestedNetType : walletManager.getNetworkType().ordinal();
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();
                currentWalletPath = walletPath;
                wallet = walletManager.recoveryWallet(walletPath, seed, restoreHeight);
                if (wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal()) {
                    setupWallet();
                    isInitialized = true;
                    notifyWalletInitialized(true, "Wallet restored");

                    long initBal = wallet.getBalance();
                    long initUb = wallet.getUnlockedBalance();
                    Log.d(TAG, "Initial restored wallet state: balance=" + initBal + " atomic units, unlocked=" + initUb + " atomic units");
                    if (initBal == 0 && initUb == 0) {
                        Log.d(TAG, "Initial balances are zero after restore - rescan will be triggered after sync if balances remain zero");
                    }

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

    private void safeMove(File f) {
        try {
            if (f.exists()) {
                File dest = new File(f.getAbsolutePath() + ".bak");
                if (!f.renameTo(dest)) {
                    Log.w(TAG, "Could not move " + f.getAbsolutePath() + " → " + dest.getAbsolutePath());
                }
            } else {
                Log.w(TAG, "File does not exist: " + f.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "Error moving " + f.getAbsolutePath() + ": " + e.getMessage());
        }
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
            } catch (Exception e) {
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

    public void sendTransaction(String destinationAddress, double amountXmr, TransactionCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not ready");
            return;
        }
        
        executorService.execute(() -> {
            try {
                Log.d(TAG, "=== SEND TRANSACTION (TXID-BASED) ===");
                Log.d(TAG, "Destination: " + destinationAddress);
                Log.d(TAG, "Amount: " + amountXmr + " XMR");
                
                long amountAtomic = (long) (amountXmr * 1e12);
                Log.d(TAG, "Amount in atomic units: " + amountAtomic);
                
                PendingTransaction pendingTx = wallet.createTransaction(
                    destinationAddress,
                    "",
                    amountAtomic,
                    0,
                    PendingTransaction.Priority.Priority_Default.ordinal()
                );
                
                if (pendingTx.getStatus() != PendingTransaction.Status.Status_Ok.ordinal()) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "Failed to create transaction: " + error);
                    mainHandler.post(() -> callback.onError("Transaction creation failed: " + error));
                    return;
                }
                
                boolean committed = pendingTx.commit("", true);
                if (!committed) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "Failed to commit transaction: " + error);
                    mainHandler.post(() -> callback.onError("Transaction broadcast failed: " + error));
                    return;
                }
                
                String txId = pendingTx.getFirstTxId();
                long actualAmount = pendingTx.getAmount();
                long fee = pendingTx.getFee();
                
                Log.d(TAG, "Transaction broadcast successfully!");
                Log.d(TAG, "TxID: " + txId);
                Log.d(TAG, "Amount: " + actualAmount + " atomic units");
                Log.d(TAG, "Fee: " + fee + " atomic units");
                
                try {
                    wallet.store(currentWalletPath);
                    Log.d(TAG, "Wallet stored after transaction");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to store wallet after transaction", e);
                }
                
                try {
                    wallet.refresh();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to refresh wallet after transaction", e);
                }
                
                mainHandler.post(() -> callback.onSuccess(txId, actualAmount));
                
                if (statusListener != null) {
                    try {
                        long balance = wallet.getBalance();
                        long unlockedBalance = wallet.getUnlockedBalance();
                        mainHandler.post(() -> statusListener.onBalanceUpdated(balance, unlockedBalance));
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to update balance after transaction", e);
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Exception in sendTransaction", e);
                mainHandler.post(() -> callback.onError("Transaction failed: " + e.getMessage()));
            }
        });
    }

    public void searchAndImportTransaction(String txId, TransactionSearchCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not ready");
            return;
        }

        executorService.execute(() -> {
            try {
                Log.d(TAG, "=== SEARCH AND IMPORT TRANSACTION ===");
                Log.d(TAG, "TxID: " + txId);

                Log.d(TAG, "Refreshing wallet to search for transaction...");
                wallet.refresh();

                TransactionHistory history = wallet.getHistory();
                if (history == null) {
                    Log.w(TAG, "Transaction history is null");
                    mainHandler.post(() -> callback.onError("Transaction history unavailable"));
                    return;
                }

                history.refresh();

                TransactionInfo txInfo = null;
                for (TransactionInfo info : history.getAll()) {
                    if (info.hash.equals(txId)) {
                        txInfo = info;
                        break;
                    }
                }

                if (txInfo != null) {
                    Log.d(TAG, "Transaction found in wallet history!");

                    try {
                        long amount = txInfo.amount;
                        long confirmations = txInfo.confirmations;
                        long blockHeight = txInfo.blockheight;

                        Log.d(TAG, "Transaction details - " +
                                "Amount: " + amount +
                                ", Confirmations: " + confirmations +
                                ", BlockHeight: " + blockHeight +
                                ", Incoming: " + txInfo.direction);

                        if (statusListener != null) {
                            try {
                                long bal = wallet.getBalance();
                                long ubal = wallet.getUnlockedBalance();
                                mainHandler.post(() -> statusListener.onBalanceUpdated(bal, ubal));
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to update balance", e);
                            }
                        }

                        try {
                            wallet.store(currentWalletPath);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to store wallet", e);
                        }

                        long finalAmount = amount;
                        long finalConfirmations = confirmations;
                        long finalBlockHeight = blockHeight;
                        mainHandler.post(() ->
                                callback.onTransactionFound(txId, finalAmount, finalConfirmations, finalBlockHeight)
                        );

                    } catch (Exception e) {
                        Log.w(TAG, "Error getting transaction details", e);
                        mainHandler.post(() -> callback.onError("Failed to parse transaction details"));
                    }

                } else {
                    Log.d(TAG, "Transaction " + txId + " not found in history");
                    mainHandler.post(() -> callback.onTransactionNotFound(txId));
                }

            } catch (Exception e) {
                Log.e(TAG, "Error searching for transaction", e);
                mainHandler.post(() -> callback.onError("Search failed: " + e.getMessage()));
            }
        });
    }
    
    private long getEstimatedCreationHeight() {
        try {
            long restoreHeight = wallet.getRestoreHeight();
            if (restoreHeight > 0) {
                return restoreHeight;
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not get restore height, trying transaction history");
        }
        
        try {
            List<TransactionInfo> transactions = wallet.getHistory().getAll();
            if (transactions != null && !transactions.isEmpty()) {
                long earliestHeight = Long.MAX_VALUE;
                for (TransactionInfo tx : transactions) {
                    long blockHeight = tx.blockheight;
                    if (blockHeight > 0 && blockHeight < earliestHeight) {
                        earliestHeight = blockHeight;
                    }
                }
                if (earliestHeight != Long.MAX_VALUE) {
                    return earliestHeight;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting transaction history", e);
        }
        
        return 0;
    }

    public void searchForMissingTransaction(String txId, TransactionSearchCallback callback) {
        Log.d(TAG, "=== MANUAL TRANSACTION SEARCH ===");
        Log.d(TAG, "Searching for TxID: " + txId);

        executorService.execute(() -> {
            try {
                searchAndImportTransaction(txId, new TransactionSearchCallback() {
                    @Override
                    public void onTransactionFound(String txId, long amount, long confirmations, long blockHeight) {
                        callback.onTransactionFound(txId, amount, confirmations, blockHeight);
                    }

                    @Override
                    public void onTransactionNotFound(String txId) {
                        Log.d(TAG, "Transaction not found, attempting rescans...");

                        try {
                            long currentHeight = wallet.getBlockChainHeight();
                            long creationHeight = getEstimatedCreationHeight();

                            long[] rescanHeights = new long[] {
                                Math.max(currentHeight - 500, 0),
                                Math.max(currentHeight - 5000, 0),
                                creationHeight
                            };

                            attemptRescanWithFallback(txId, callback, rescanHeights, 0);

                        } catch (Exception e) {
                            Log.e(TAG, "Error during rescan setup", e);
                            mainHandler.post(() -> callback.onTransactionNotFound(txId));
                        }
                    }

                    @Override
                    public void onError(String error) {
                        callback.onError(error);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error in manual search", e);
                mainHandler.post(() -> callback.onError("Manual search failed: " + e.getMessage()));
            }
        });
    }

    private void attemptRescanWithFallback(String txId, TransactionSearchCallback callback, long[] rescanHeights, int index) {
        if (index >= rescanHeights.length) {
            Log.d(TAG, "All rescans attempted, transaction not found.");
            mainHandler.post(() -> callback.onTransactionNotFound(txId));
            return;
        }

        long rescanHeight = rescanHeights[index];
        Log.d(TAG, "Rescanning from height: " + rescanHeight);

        wallet.setRestoreHeight(rescanHeight);
        wallet.rescanBlockchainAsync(new Wallet.RescanCallback() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Rescan complete, searching again...");
                searchAndImportTransaction(txId, new TransactionSearchCallback() {
                    @Override
                    public void onTransactionFound(String txId, long amount, long confirmations, long blockHeight) {
                        callback.onTransactionFound(txId, amount, confirmations, blockHeight);
                    }

                    @Override
                    public void onTransactionNotFound(String txId) {
                        attemptRescanWithFallback(txId, callback, rescanHeights, index + 1);
                    }

                    @Override
                    public void onError(String error) {
                        callback.onError(error);
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Rescan failed from height " + rescanHeight + ": " + error);
                attemptRescanWithFallback(txId, callback, rescanHeights, index + 1);
            }
        });
    }

    private long convertXmrToAtomic(double xmr) {
        return (long) (xmr * 1e12);
    }
    
    public void importSignedTransactionBlob(String signedTxBlob, TransactionImportCallback cb) {
        if (!isInitialized || wallet == null) {
            cb.onError("Wallet not ready");
            return;
        }
        executorService.execute(() -> {
            try {
                byte[] blob = Base64.decode(signedTxBlob, Base64.NO_WRAP);
                String hex = bytesToHex(blob);

                String txId = wallet.submitTransaction(hex);
                if (txId == null || txId.isEmpty()) {
                    String error = wallet.getErrorString();
                    mainHandler.post(() -> cb.onError("Import failed: " + (error != null ? error : "Unknown error")));
                    return;
                }

                try {
                    wallet.refresh();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to refresh wallet after import", e);
                }

                mainHandler.post(() -> cb.onSuccess(txId));

            } catch (Exception e) {
                Log.e(TAG, "Exception in importSignedTransactionBlob", e);
                mainHandler.post(() -> cb.onError("Import failed: " + e.getMessage()));
            }
        });
    }
}
