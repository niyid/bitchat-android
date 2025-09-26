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
import android.util.Log;


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
    private static final int SYNC_TIMEOUT_MS = 120000; // 2 minutes
    private static final int STUCK_THRESHOLD = 30; // 30 progress updates without change
    private static final int MAX_REFRESH_RETRIES = 3;

    private long syncStartTime;
    private long lastSyncHeight = -1;
    private int stuckCounter = 0;
    private int refreshRetryCount = 0;
    private WalletListener currentListener;
    
    // Runtime fields
    private Wallet wallet;
    private final WalletManager walletManager;
    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private boolean isInitialized = false;
    private boolean isSyncing = false;
    private String walletAddress;

    // listeners
    private WalletStatusListener statusListener;
    private TransactionListener transactionListener;

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

    private DaemonConfigCallback daemonConfigCallback;

    public void setDaemonConfigCallback(DaemonConfigCallback callback) {
        this.daemonConfigCallback = callback;
    }    

    // Constructor
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

    public void close() {
        stopSync();
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
        // Basic state checks - these should never throw exceptions
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
        
        // Test wallet JNI methods that are safe to call after initialization
        try {
            // Check wallet status first - this should be safe after successful initialization
            int status = wallet.getStatus();
            if (status != Wallet.Status.Status_Ok.ordinal()) {
                Log.d(TAG, "Wallet readiness check failed: status=" + status);
                return false;
            }
            
            // Test basic wallet operations to ensure JNI layer is responsive
            String address = wallet.getAddress();
            if (address == null || address.isEmpty()) {
                Log.d(TAG, "Wallet readiness check failed: invalid address");
                return false;
            }
            
            // Check if wallet can report its current blockchain height
            // This is a good indicator that the wallet object is fully functional
            long height = wallet.getBlockChainHeight();
            Log.d(TAG, "Wallet readiness check: current height=" + height);
            
            // If we reach here, the wallet JNI layer is responding correctly
            Log.d(TAG, "Wallet readiness check passed");
            return true;
            
        } catch (Exception e) {
            // Any exception here means the wallet JNI isn't ready yet
            Log.d(TAG, "Wallet readiness check failed with exception: " + e.getMessage());
            return false;
        }
    }

    private void startSync() {
        Log.d(TAG, "Starting wallet sync...");
        isSyncing = true;
        syncStartTime = System.currentTimeMillis();
        lastSyncHeight = -1;
        stuckCounter = 0;
        refreshRetryCount = 0;

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
                public void newBlock(long height) {
                    Log.d(TAG, "New block received at height: " + height);
                    resetStuckCounter(); // Progress detected
                    
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

                @Override
                public void refreshed() {
                    Log.d(TAG, "Wallet refreshed callback - checking sync status");

                    if (wallet == null || !isSyncing) {
                        Log.d(TAG, "Sync stopped or wallet null, ignoring refresh callback");
                        return;
                    }

                    try {
                        long walletHeight = wallet.getBlockChainHeight();
                        long daemonHeight = wallet.getDaemonBlockChainHeight();

                        boolean isSynced = (walletHeight >= daemonHeight && daemonHeight > 0);

                        Log.d(TAG, "Refresh complete - Wallet height: " + walletHeight +
                                ", Daemon height: " + daemonHeight + ", Synced: " + isSynced);

                        if (isSynced) {
                            Log.d(TAG, "Wallet is synchronized");
                            completeSyncSuccess();
                        } else {
                            // Check if we should continue or restart
                            if (shouldContinueSync()) {
                                Log.d(TAG, "Continuing sync...");
                                scheduleNextRefresh();
                            } else {
                                Log.w(TAG, "Sync conditions not met, restarting");
                                restartSync();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in refreshed callback", e);
                        handleSyncError("Refresh error: " + e.getMessage());
                    }
                }
            };

            wallet.setListener(currentListener);

            // Validate daemon connection before starting
            if (!validateDaemonConnection()) {
                handleSyncError("Daemon connection validation failed");
                return;
            }

            // Log initial status
            logWalletStatus();

            // Start the refresh process
            Log.d(TAG, "Starting refreshAsync()...");
            wallet.refreshAsync();
            
            // Start monitoring
            Log.d(TAG, "Starting sync monitoring...");
            scheduleProgressUpdates();
            scheduleSyncTimeout();

        } catch (Exception e) {
            Log.e(TAG, "Exception during sync setup", e);
            handleSyncError("Sync setup failed: " + e.getMessage());
        }
    }

    private boolean validateDaemonConnection() {
        try {
            if (wallet == null) return false;
            
            long daemonHeight = wallet.getDaemonBlockChainHeight();
            if (daemonHeight <= 0) {
                Log.w(TAG, "Daemon height is " + daemonHeight + ", connection may be invalid");
                return false;
            }
            
            Log.d(TAG, "Daemon connection validated, height: " + daemonHeight);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Daemon validation failed", e);
            return false;
        }
    }

    private boolean shouldContinueSync() {
        // Check timeout
        if (System.currentTimeMillis() - syncStartTime > SYNC_TIMEOUT_MS) {
            Log.w(TAG, "Sync timeout exceeded");
            return false;
        }
        
        // Check retry count
        if (refreshRetryCount >= MAX_REFRESH_RETRIES) {
            Log.w(TAG, "Max refresh retries exceeded");
            return false;
        }
        
        // Check if daemon is still responsive
        if (!validateDaemonConnection()) {
            Log.w(TAG, "Daemon no longer responsive");
            return false;
        }
        
        return true;
    }

    private void scheduleNextRefresh() {
        refreshRetryCount++;
        mainHandler.postDelayed(() -> {
            if (wallet != null && isSyncing) {
                Log.d(TAG, "Continuing refresh (retry " + refreshRetryCount + ")");
                Log.d(TAG, "Sync status: " + getSyncStatus() + ")");
                wallet.refreshAsync();
            }
        }, 2000);
    }

    private void resetStuckCounter() {
        stuckCounter = 0;
    }

    private void scheduleSyncTimeout() {
        mainHandler.postDelayed(() -> {
            if (isSyncing) {
                Log.w(TAG, "Sync timeout reached, restarting");
                restartSync();
            }
        }, SYNC_TIMEOUT_MS);
    }

    private void scheduleProgressUpdates() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isSyncing || wallet == null) return;
                
                try {
                    long walletHeight = wallet.getBlockChainHeight();
                    long daemonHeight = wallet.getDaemonBlockChainHeight();
                    
                    // Check for progress
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
                    
                    // Schedule next update
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
        
        // Brief delay before restart
        mainHandler.postDelayed(() -> {
            if (setDaemonFromConfigAndApply()) {
                startSyncWhenReady();
            } else {
                handleSyncError("Failed to reconnect to daemon");
            }
        }, 3000);
    }

    private void stopSync() {
        isSyncing = false;
        
        if (wallet != null && currentListener != null) {
            try {
                wallet.setListener(null);
            } catch (Exception e) {
                Log.w(TAG, "Error removing wallet listener", e);
            }
        }
        
        currentListener = null;
    }

    private void completeSyncSuccess() {
        Log.d(TAG, "Sync completed successfully");
        isSyncing = false;
        
        if (statusListener != null) {
            try {
                long walletHeight = wallet.getBlockChainHeight();
                long daemonHeight = wallet.getDaemonBlockChainHeight();
                statusListener.onSyncProgress(walletHeight, walletHeight, daemonHeight, 100.0);
            } catch (Exception e) {
                Log.e(TAG, "Error in success callback", e);
            }
        }
    }

    private void handleSyncError(String error) {
        Log.e(TAG, "Sync error: " + error);
        stopSync();
        
        if (statusListener != null) {
            mainHandler.post(() -> statusListener.onWalletInitialized(false, error));
        }
        
        // Optionally trigger daemon config callback for user intervention
        if (daemonConfigCallback != null) {
            mainHandler.post(() -> daemonConfigCallback.onConfigError(error));
        }
    }

    private void logWalletStatus() {
        Log.d(TAG, "Wallet status before sync:");
        if (wallet != null) {
            try {
                int status = wallet.getStatus();
                Log.d(TAG, "  - Status: " + status);
                
                long currentHeight = wallet.getBlockChainHeight();
                Log.d(TAG, "  - Current height: " + currentHeight);
                
                long daemonHeight = wallet.getDaemonBlockChainHeight();
                Log.d(TAG, "  - Daemon height: " + daemonHeight);
                
            } catch (Exception e) {
                Log.w(TAG, "Error logging wallet status", e);
            }
        }
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

    // Configuration loader
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

    // Apply Node to WalletManager
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
                Log.d("NetworkTest", "✅ Successfully connected to daemon");
                socket.close();
            } catch (Exception e) {
                Log.e("NetworkTest", "❌ Cannot connect to daemon: " + e.getMessage());
            }
        }).start();
    }    

    // Wallet lifecycle
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

                // --- NEW: Create node and initJ with its parameters ---
                try {
                    Node node = walletManager.createNodeFromConfig();
                    long handle = wallet.initJ(
                            node.getAddress(),    // daemonAddress
                            0,                    // upperTransactionLimit (0 = unlimited)
                            node.getUsername(),   // daemonUsername
                            node.getPassword(),   // daemonPassword
                            node.isSsl(),         // ssl
                            false,                // lightWallet
                            ""                    // proxy
                    );
                    Log.d(TAG, "Wallet initJ returned handle=" + handle);

                    if (handle == 0) {
                        Log.e(TAG, "initJ failed, falling back to setDaemonFromConfigAndApply()");
                        setDaemonFromConfigAndApply();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "initJ threw exception, falling back to setDaemonFromConfigAndApply()", e);
                    setDaemonFromConfigAndApply();
                }
                // --- END NEW ---

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
                    
                    // Test daemon connection
                    boolean daemonConnected = testDaemonConnectionSync();
                    if (!daemonConnected && daemonConfigCallback != null) {
                        // Daemon connection failed, request config from UI
                        mainHandler.post(() -> daemonConfigCallback.onConfigNeeded());
                        future.complete(false);
                        return;
                    }
                    
                    isInitialized = true;
                    notifyWalletInitialized(true, "Wallet initialized");

                    // FIXED: Use callback-based readiness check instead of arbitrary delay
                    startSyncWhenReady();

                    future.complete(true);
                    return;
                }

                // Handle non-OK status
                notifyWalletInitialized(false,
                        "Init failed: status=" + status + " err=" + errorStr);

                // If CRITICAL, nuke the wallet and try again
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

                        // FIXED: Use callback-based readiness check for recreated wallet
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
                
                // If it's a daemon connection error, offer config dialog
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

    // Add synchronous daemon connection test method:
    private boolean testDaemonConnectionSync() {
        try {
            if (wallet != null) {
                long daemonHeight = wallet.getDaemonBlockChainHeight();
                return daemonHeight > 0;
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Daemon connection test failed: " + e.getMessage());
            return false;
        }
    }

    // Helper: safe file deletion
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
                wallet = walletManager.recoveryWallet(walletPath, seed, restoreHeight);
                if (wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal()) {
                    setupWallet();
                    isInitialized = true;
                    notifyWalletInitialized(true, "Wallet restored");
                    
                    // FIXED: Use callback-based readiness check for seed restoration
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

    // === Public wallet ops ===

    public void getAddress(AddressCallback cb) {
        if (!isInitialized || wallet == null) { cb.onError("Wallet not ready"); return; }
        executorService.execute(() -> {
            try { String a = wallet.getAddress(); mainHandler.post(() -> cb.onSuccess(a)); }
            catch (Exception e) { mainHandler.post(() -> cb.onError(e.getMessage())); }
        });
    }

    public void getBalance(BalanceCallback cb) {
        if (!isInitialized || wallet == null) { cb.onError("Wallet not ready"); return; }
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
        if (!isInitialized || wallet == null) { cb.onError("Wallet not ready"); return; }
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
        if (!isInitialized || wallet == null) { cb.onError("Wallet not ready"); return; }
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

    // Config utils
    public void reloadConfiguration() {
        loadConfiguration();
        Log.i(TAG, "Config reloaded from: " + getCurrentConfigPath());
        if (wallet != null) setDaemonFromConfigAndApply();
    }

    public static void copyDefaultConfigToExternalStorage(Context ctx) {
        try {
            File dest = new File(ctx.getExternalFilesDir(null), PROPERTIES_FILE);
            if (dest.exists()) { Log.i(TAG, "Config already exists: " + dest.getAbsolutePath()); return; }
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

    // Listeners
    public void setWalletStatusListener(WalletStatusListener l) { this.statusListener = l; }
    public void setTransactionListener(TransactionListener l) { this.transactionListener = l; }

    private void notifyWalletInitialized(boolean ok, String msg) {
        if (statusListener != null) mainHandler.post(() -> statusListener.onWalletInitialized(ok, msg));
    }

    public boolean isReady() {
        return isInitialized && wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal();
    }
}
