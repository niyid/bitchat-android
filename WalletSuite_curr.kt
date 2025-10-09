package com.bitchat.android.monero.wallet;

import android.content.Context;
import android.os.Environment;
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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

import java.net.HttpURLConnection;
import java.net.URL;

public class WalletSuite {
    private static final String TAG = "WalletSuite";
    private static final String PROPERTIES_FILE = "wallet.properties";

    private static volatile boolean nativeOk = false;
    private static volatile boolean nativeChecked = false;
    private static volatile WalletSuite instance;
    
    // Periodic sync settings
    private static final long PERIODIC_SYNC_INTERVAL_MS = 300000; // 5 minutes
    private static final int SINGLE_SYNC_TIMEOUT_MS = 120000; // 2 minutes per sync
    
    private volatile long lastKnownDaemonHeight = -1;
    private volatile boolean syncCompleted = false;
    private volatile boolean walletPersisted = false;
    private volatile WalletListener currentListener;
    private volatile String currentWalletPath;
    private final Object syncLock = new Object();
    private final Object persistLock = new Object();
    
    // Periodic sync scheduler
    private ScheduledExecutorService periodicSyncScheduler;
    private ScheduledFuture<?> periodicSyncTask;

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
    private volatile DaemonConfigCallback daemonConfigCallback;
    
    private long balance = 0L;
    private long unlocked = 0L;
    
    public enum InitStatus {
        OK,
        READ_ONLY,
        KEYS_MISSING,
        NET_MISMATCH,
        ERROR
    }
    
    public interface WalletStatusListener {
        void onWalletInitialized(boolean success, String message);
        void onBalanceUpdated(long balance, long unlocked);
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
        void onSuccess(long balance, long unlocked);
        void onError(String error);
    }

    public interface SyncCallback {
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

    public void setDaemonConfigCallback(DaemonConfigCallback callback) {
        this.daemonConfigCallback = callback;
    }

    private WalletSuite(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
        this.periodicSyncScheduler = Executors.newSingleThreadScheduledExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.walletManager = WalletManager.getInstance();
        loadConfiguration();
        registerShutdownHandler();
    }
    
    public long getBalanceValue() {
        return balance;
    }

    public long getUnlockedBalanceValue() {
        return unlocked;
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
        stopPeriodicSync();
        stopSync();
        closeWalletSync();

        periodicSyncScheduler.shutdown();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                Log.w(TAG, "Executor service did not terminate in time, forcing shutdown");
                executorService.shutdownNow();
            }
            if (!periodicSyncScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                periodicSyncScheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executorService.shutdownNow();
            periodicSyncScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Log.d(TAG, "Shutdown sequence completed");
    }

    private void startPeriodicSync() {
        synchronized (syncLock) {
            if (periodicSyncTask != null && !periodicSyncTask.isDone()) {
                Log.d(TAG, "Periodic sync already running");
                return;
            }

            Log.i(TAG, "=== STARTING PERIODIC SYNC SCHEDULER ===");
            Log.d(TAG, "Sync interval: " + (PERIODIC_SYNC_INTERVAL_MS / 1000) + " seconds (" +
                    (PERIODIC_SYNC_INTERVAL_MS / 60000) + " minutes)");

            periodicSyncTask = periodicSyncScheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (isInitialized && !isSyncing) {
                        Log.i(TAG, "⏰ Periodic sync triggered");
                        performSingleSync();

                        // Check sync completion after each single sync
                        long walletHeight = wallet.getBlockChainHeight();
                        long daemonHeight = walletManager.getBlockchainHeight();
                        double percentDone = (daemonHeight > 0)
                                ? (walletHeight * 100.0) / daemonHeight
                                : 0.0;

                        Log.d(TAG, String.format(Locale.US,
                                "Sync check after performSingleSync: walletHeight=%d, daemonHeight=%d, percent=%.2f%%",
                                walletHeight, daemonHeight, percentDone));

                        if (percentDone >= 99.9) {
                            balance = wallet.getBalance();
                            unlocked = wallet.getUnlockedBalance();

                            Log.i(TAG, "Wallet appears fully synced - fetching balances");
                            Log.d(TAG, "Balance: " + balance + " (atomic), Unlocked: " + unlocked + " (atomic)");
                            Log.d(TAG, "Balance XMR: " + convertAtomicToXmr(balance) +
                                       " | Unlocked XMR: " + convertAtomicToXmr(unlocked));

                            if (statusListener != null) {
                                statusListener.onBalanceUpdated(balance, unlocked);
                            }

                        }
                    } else {
                        Log.d(TAG, "⏰ Skipping periodic sync - wallet not ready (initialized=" +
                                isInitialized + ", syncing=" + isSyncing + ")");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in periodic sync task", e);
                }
            }, PERIODIC_SYNC_INTERVAL_MS, PERIODIC_SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);

            Log.i(TAG, "✓ Periodic sync scheduler started");
        }
    }

    
    private void stopPeriodicSync() {
        synchronized (syncLock) {
            if (periodicSyncTask != null) {
                periodicSyncTask.cancel(false);
                periodicSyncTask = null;
                Log.i(TAG, "✓ Periodic sync scheduler stopped");
            }
        }
    }

    private void performSingleSync() {
        synchronized (syncLock) {
            if (isSyncing) {
                Log.d(TAG, "Sync already in progress, skipping");
                return;
            }
            
            Log.d(TAG, "=== STARTING SINGLE SYNC OPERATION ===");
            isSyncing = true;
            syncCompleted = false;
        }

        executorService.execute(() -> {
            Log.d(TAG, "Validating daemon connection...");
            if (!validateDaemonConnection()) {
                Log.e(TAG, "Daemon validation failed");
                mainHandler.post(() -> handleSyncError("Daemon connection validation failed"));
                return;
            }
            
            Log.d(TAG, "Daemon validation successful, proceeding to sync");
            mainHandler.post(this::executeSingleSync);
        });
    }

    private void executeSingleSync() {
        try {
            Log.d(TAG, "=== SETTING UP SYNC LISTENER ===");
            
            if (currentListener != null && wallet != null) {
                Log.d(TAG, "Removing previous listener");
                wallet.setListener(null);
            }

            currentListener = new WalletListener() {
                @Override
                public void moneySent(String txId, long amount) {
                    Log.d(TAG, "[LISTENER] moneySent: txId=" + txId + " amount=" + amount);
                }

                @Override
                public void moneyReceived(String txId, long amount) {
                    Log.d(TAG, "[LISTENER] moneyReceived: txId=" + txId + " amount=" + amount);
                    if (transactionListener != null) {
                        mainHandler.post(() -> transactionListener.onOutputReceived(amount, txId, false));
                    }
                }

                @Override
                public void unconfirmedMoneyReceived(String txId, long amount) {
                    Log.d(TAG, "[LISTENER] unconfirmedMoneyReceived: txId=" + txId + " amount=" + amount);
                }

                @Override
                public void newBlock(long height) {
                    Log.d(TAG, "[LISTENER] newBlock: height=" + height);
                }

                @Override
                public void updated() {
                    Log.d(TAG, "[LISTENER] Wallet updated callback");
                }

                @Override
                public void refreshed() {
                    Log.d(TAG, "[LISTENER] Wallet refreshed callback - sync complete");
                    completeSingleSync();
                }
            };

            wallet.setListener(currentListener);
            Log.d(TAG, "✓ Sync listener attached");

            // Schedule timeout for this sync
            Log.d(TAG, "Scheduling sync timeout (" + SINGLE_SYNC_TIMEOUT_MS + "ms)");
            mainHandler.postDelayed(() -> {
                if (isSyncing) {
                    Log.w(TAG, "⚠ Single sync timeout reached");
                    completeSingleSync();
                }
            }, SINGLE_SYNC_TIMEOUT_MS);

            executorService.execute(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    Log.d(TAG, "=== PERFORMING SYNCHRONOUS REFRESH ===");
                    
                    long heightBefore = wallet.getBlockChainHeight();
                    Log.d(TAG, "Wallet height before refresh: " + heightBefore);
                    
                    wallet.refresh();
                    
                    long duration = System.currentTimeMillis() - startTime;
                    long heightAfter = wallet.getBlockChainHeight();
                    Log.d(TAG, "Synchronous refresh completed in " + duration + "ms");
                    Log.d(TAG, "Wallet height after refresh: " + heightAfter + " (+" + (heightAfter - heightBefore) + " blocks)");
                    
                    mainHandler.post(() -> completeSingleSync());
                    
                } catch (Exception e) {
                    Log.e(TAG, "✗ Sync failed with exception", e);
                    mainHandler.post(() -> completeSingleSync());
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "✗ Exception during sync setup", e);
            completeSingleSync();
        }
    }

    private void completeSingleSync() {
        synchronized (syncLock) {
            if (!isSyncing) {
                Log.d(TAG, "Sync already completed, skipping");
                return;
            }
            
            Log.d(TAG, "=== COMPLETING SINGLE SYNC ===");
            isSyncing = false;
            syncCompleted = true;
        }

        long walletHeight = 0;
        long daemonHeight = 0;

        try {
            balance = wallet.getBalance();
            unlocked = wallet.getUnlockedBalance();
            walletHeight = wallet.getBlockChainHeight();
            daemonHeight = getDaemonHeightViaHttp();
            
            double balanceXmr = balance / 1e12;
            double unlockedXmr = unlocked / 1e12;
            
            Log.d(TAG, "=== SYNC RESULTS ===");
            Log.d(TAG, "Wallet height: " + walletHeight);
            Log.d(TAG, "Daemon height: " + daemonHeight);
            Log.d(TAG, "Balance: " + balance + " atomic units (" + String.format("%.12f", balanceXmr) + " XMR)");
            Log.d(TAG, "Unlocked: " + unlocked + " atomic units (" + String.format("%.12f", unlockedXmr) + " XMR)");
            
            boolean synced = (daemonHeight > 0 && walletHeight >= daemonHeight);
            Log.d(TAG, "Fully synced: " + synced);
            
            if (statusListener != null) {
                double percent = daemonHeight > 0 ? (100.0 * walletHeight / daemonHeight) : 100.0;
                Log.d(TAG, "Notifying status listener: " + String.format("%.2f", percent) + "% complete");
                
                statusListener.onSyncProgress(walletHeight, walletHeight, daemonHeight, percent);
                statusListener.onBalanceUpdated(balance, unlocked);
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error retrieving sync results", e);
        }

        // Persist wallet state
        Log.d(TAG, "Persisting wallet state...");
        persistWalletSafely();
        
        Log.d(TAG, "=== SYNC OPERATION COMPLETE ===");
    }
    
    public void importOutputs(File outputsFile) {
        if (outputsFile.exists()) {
            int success = wallet.importOutputs(outputsFile.getAbsolutePath());
            if (success > -1) {
                Log.i(TAG, "✅ Outputs imported successfully from " + outputsFile);
                wallet.rescanBlockchainAsync();
            } else {
                Log.w(TAG, "⚠️ Failed to import outputs from " + outputsFile);
            }
        }    
    }
 
    private void persistWalletSafely() {
        executorService.execute(() -> {
            synchronized (persistLock) {
                if (wallet == null || !isInitialized) {
                    Log.d(TAG, "Cannot persist: wallet=" + (wallet != null) + ", initialized=" + isInitialized);
                    return;
                }

                try {
                    String walletPath = wallet.getPath();
                    Log.d(TAG, "Persisting wallet to: " + walletPath);
                    
                    if (walletPath != null && !walletPath.isEmpty()) {
                        boolean stored = wallet.store(walletPath);
                        if (stored) {
                            walletPersisted = true;
                            Log.i(TAG, "✓ Wallet persisted successfully");
                        } else {
                            String error = wallet.getErrorString();
                            Log.w(TAG, "✗ Wallet store returned false: " + (error != null ? error : "unknown error"));
                        }
                    } else {
                        Log.w(TAG, "✗ Cannot persist: wallet path is null or empty");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "✗ Exception during wallet persistence", e);
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

            Log.d(TAG, "Checking daemon connection...");
            long daemonHeight = getDaemonHeightViaHttp();
            
            if (daemonHeight <= 0) {
                Log.w(TAG, "Daemon validation failed: invalid height (" + daemonHeight + ")");
                return false;
            }

            Log.i(TAG, "✓ Daemon connection validated: height=" + daemonHeight);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "✗ Daemon validation failed with exception", e);
            return false;
        }
    }

    private void stopSync() {
        synchronized (syncLock) {
            isSyncing = false;
            syncCompleted = false;
        }

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

        if (statusListener != null) {
            mainHandler.post(() -> statusListener.onWalletInitialized(false, error));
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
                Log.d(TAG, "=== WALLET INITIALIZATION STARTED ===");
                String walletName = walletManager.getWalletName();
                Log.d(TAG, "Wallet name: " + walletName);

                // Step 1: Check for wallet backup on SD card
                File sdcardDir = new File(Environment.getExternalStorageDirectory(),
                        "Android/data/com.bitchat.droid/files");
                Log.d(TAG, "Checking SD card directory: " + sdcardDir.getAbsolutePath());
                Log.d(TAG, "SD card directory exists: " + sdcardDir.exists());
                
                File backupFile = new File(sdcardDir, walletName);
                File backupKeysFile = new File(sdcardDir, walletName + ".keys");
                File backupAddressFile = new File(sdcardDir, walletName + ".address.txt");
                Log.d(TAG, "Backup base file: " + backupFile.getAbsolutePath());

                // Step 2: Determine target directory
                File dir = context.getDir("wallets", Context.MODE_PRIVATE);
                Log.d(TAG, "Wallets directory: " + dir.getAbsolutePath());
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "CRITICAL: Cannot create wallets directory");
                    notifyWalletInitialized(false, "Cannot create wallets dir");
                    future.complete(false);
                    return;
                }

                String walletPath = new File(dir, walletName).getAbsolutePath();
                currentWalletPath = walletPath;

                // Step 3: Copy wallet and rename old backups
                if (backupFile.exists()) {
                    Log.i(TAG, "=== RESTORING WALLET FROM SD CARD ===");
                    try {
                        File destWalletFile = new File(walletPath);
                        Files.copy(backupFile.toPath(), destWalletFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        Log.i(TAG, "✓ Main wallet file copied");
                        File bakWalletFile = new File(sdcardDir, walletName + ".bak");
                        Files.move(backupFile.toPath(), bakWalletFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        Log.i(TAG, "✓ Original wallet file renamed to .bak");
                    } catch (Exception ex) {
                        Log.e(TAG, "✗ Wallet copy/rename failed", ex);
                    }

                    if (backupKeysFile.exists()) {
                        try {
                            File destKeysFile = new File(walletPath + ".keys");
                            Files.copy(backupKeysFile.toPath(), destKeysFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            File bakKeysFile = new File(sdcardDir, walletName + ".keys.bak");
                            Files.move(backupKeysFile.toPath(), bakKeysFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            Log.i(TAG, "✓ Keys file copied and renamed to .bak");
                        } catch (Exception ex) {
                            Log.e(TAG, "✗ Keys copy/rename failed", ex);
                        }
                    }

                    if (backupAddressFile.exists()) {
                        try {
                            File destAddressFile = new File(walletPath + ".address.txt");
                            Files.copy(backupAddressFile.toPath(), destAddressFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            File bakAddrFile = new File(sdcardDir, walletName + ".address.txt.bak");
                            Files.move(backupAddressFile.toPath(), bakAddrFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            Log.i(TAG, "✓ Address file copied and renamed to .bak");
                        } catch (Exception ex) {
                            Log.e(TAG, "✗ Address copy/rename failed", ex);
                        }
                    }

                    Log.i(TAG, "=== WALLET RESTORATION COMPLETE ===");
                } else {
                    Log.d(TAG, "No backup found on SD card; continuing with existing or new wallet");
                }

                // Step 4: Open or create wallet
                File keysFile = new File(walletPath + ".keys");
                Log.d(TAG, "Keys file exists: " + keysFile.exists());

                if (keysFile.exists()) {
                    Log.i(TAG, "=== OPENING EXISTING WALLET ===");
                    wallet = walletManager.openWallet(walletPath);
                } else {
                    Log.i(TAG, "=== CREATING NEW WALLET ===");
                    wallet = walletManager.createWallet(walletPath);
                }

                if (wallet == null) {
                    Log.e(TAG, "CRITICAL: Wallet is null after open/create attempt");
                    notifyWalletInitialized(false, "JNI returned null wallet");
                    future.complete(false);
                    return;
                }

                // Step 5: Initialize daemon connection
                Log.d(TAG, "=== INITIALIZING DAEMON CONNECTION ===");
                try {
                    Node node = walletManager.createNodeFromConfig();
                    Log.d(TAG, "Node config: " + node.displayProperties());

                    long handle = wallet.initJ(
                            node.getAddress(), 0,
                            node.getUsername(), node.getPassword(),
                            node.isSsl(), false, ""
                    );
                    Log.d(TAG, "initJ handle: " + handle);
                    if (handle == 0) {
                        Log.w(TAG, "initJ returned 0, applying fallback daemon setup");
                        boolean daemonSet = walletManager.setDaemonAddress(node.getAddress());
                        Log.d(TAG, "Fallback daemon setup result: " + daemonSet);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception during daemon init", e);
                    boolean daemonSet = walletManager.setDaemonAddress(walletManager.getDaemonAddress());
                    Log.d(TAG, "Fallback daemon setup result: " + daemonSet);
                }

                // Step 6: Status check
                int status = wallet.getStatus();
                String statusName = (status < Wallet.Status.values().length)
                        ? Wallet.Status.values()[status].name() : "UNKNOWN";
                Log.d(TAG, "Wallet status: " + statusName + " (" + status + ")");
                if (status != Wallet.Status.Status_Ok.ordinal()) {
                    notifyWalletInitialized(false, "Init failed: " + statusName);
                    future.complete(false);
                    return;
                }

                // Step 7: Check read-only flag
                boolean isReadOnly = false;
                try {
                    isReadOnly = wallet.isReadOnly();
                } catch (Throwable t) {
                    Log.w(TAG, "Cannot determine read-only state", t);
                }
                if (isReadOnly) {
                    Log.w(TAG, "⚠️ Wallet opened in read-only mode");
                    notifyWalletInitialized(true, "Read-only wallet");
                } else {
                    notifyWalletInitialized(true, "Wallet initialized OK");
                }

                // Step 8: Metadata
                try {
                    walletAddress = wallet.getAddress();
                    Log.i(TAG, "Wallet address: " + walletAddress);
                    Log.d(TAG, "Height=" + wallet.getBlockChainHeight() + ", Restore=" + wallet.getRestoreHeight());
                } catch (Exception e) {
                    Log.w(TAG, "Metadata fetch failed", e);
                }
                
                //

                // Step 9: Sync
                performSingleSync();
                startPeriodicSync();

                isInitialized = true;
                future.complete(true);
                Log.i(TAG, "✓ WALLET INITIALIZATION COMPLETE");

            } catch (Exception e) {
                Log.e(TAG, "✗ Exception during wallet init", e);
                notifyWalletInitialized(false, "Error: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    public void initializeWalletFromSeed(String seed, long restoreHeight, int requestedNetType) {
        executorService.execute(() -> {
            try {
                Log.i(TAG, "=== RESTORING WALLET FROM SEED ===");
                Log.d(TAG, "Restore height: " + restoreHeight);
                
                String walletPath = getWalletPath();
                currentWalletPath = walletPath;
                Log.d(TAG, "Wallet path: " + walletPath);
                
                wallet = walletManager.recoveryWallet(walletPath, seed, restoreHeight);
                Log.d(TAG, "Recovery wallet returned: " + (wallet != null));
                
                if (wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal()) {
                    Log.i(TAG, "✓ Wallet restored successfully");
                    
                    setupWallet();
                    isInitialized = true;
                    notifyWalletInitialized(true, "Wallet restored");

                    //
                    performSingleSync();
                    startPeriodicSync();
                } else {
                    String error = (wallet != null) ? wallet.getErrorString() : "JNI error";
                    Log.e(TAG, "✗ Wallet restoration failed: " + error);
                    notifyWalletInitialized(false, "Restore failed: " + error);
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ Exception during wallet restoration", e);
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

    public void triggerImmediateSync() {
        Log.d(TAG, "Manual sync triggered");
        performSingleSync();
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
                Log.i(TAG, "=== SEND TRANSACTION ===");
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
                    Log.e(TAG, "✗ Failed to create transaction: " + error);
                    mainHandler.post(() -> callback.onError("Transaction creation failed: " + error));
                    return;
                }
                
                boolean committed = pendingTx.commit("", true);
                if (!committed) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "✗ Failed to commit transaction: " + error);
                    mainHandler.post(() -> callback.onError("Transaction broadcast failed: " + error));
                    return;
                }
                
                String txId = pendingTx.getFirstTxId();
                long actualAmount = pendingTx.getAmount();
                long fee = pendingTx.getFee();
                
                Log.i(TAG, "✓ Transaction broadcast successfully!");
                Log.d(TAG, "TxID: " + txId);
                Log.d(TAG, "Amount: " + actualAmount + " atomic units");
                Log.d(TAG, "Fee: " + fee + " atomic units");
                
                try {
                    wallet.store(currentWalletPath);
                    Log.i(TAG, "✓ Wallet stored after transaction");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to store wallet after transaction", e);
                }
                
                mainHandler.post(() -> triggerImmediateSync());
                mainHandler.post(() -> calculateFinalBalancesAfterSync());
                mainHandler.post(() -> callback.onSuccess(txId, actualAmount));
                
            } catch (Exception e) {
                Log.e(TAG, "✗ Exception in sendTransaction", e);
                mainHandler.post(() -> callback.onError("Transaction failed: " + e.getMessage()));
            }
        });
    }

    private void searchForSpecificTransaction() {
        final String TARGET_TX_ID = "65860b5309fdf18cabce905d8869e35763dc44ff96a2a1cd05359790d2538550";
        
        if (!isInitialized || wallet == null) {
            Log.d(TAG, "Cannot search for transaction: wallet not ready");
            return;
        }
        
        executorService.execute(() -> {
            try {
                Log.i(TAG, "=== AUTO-SEARCH FOR SPECIFIC TRANSACTION ===");
                
                // Get current wallet state
                long currentHeight = wallet.getBlockChainHeight();
                long restoreHeight = wallet.getRestoreHeight();
                long walletBalance = wallet.getBalance();
                long unlockedBalance = wallet.getUnlockedBalance();
                
                Log.d(TAG, "Wallet height: " + currentHeight);
                Log.d(TAG, "Restore height: " + restoreHeight);
                Log.d(TAG, "Balance: " + walletBalance);
                Log.d(TAG, "Unlocked: " + unlockedBalance);

                TransactionHistory history = wallet.getHistory();
                if (history == null) {
                    Log.w(TAG, "✗ Transaction history is null");
                    return;
                }

                history.refresh();
                List<TransactionInfo> allTxs = history.getAll();
                Log.d(TAG, "Total transactions: " + (allTxs != null ? allTxs.size() : 0));

                TransactionInfo txInfo = null;
                if (allTxs != null) {
                    for (TransactionInfo info : allTxs) {
                        if (info.hash.equals(TARGET_TX_ID)) {
                            txInfo = info;
                            break;
                        }
                    }
                }

                if (txInfo != null) {
                    Log.i(TAG, "✓ TARGET TRANSACTION FOUND!");
                    Log.d(TAG, "TxID: " + TARGET_TX_ID);
                    Log.d(TAG, "Amount: " + txInfo.amount + " atomic units (" + (txInfo.amount / 1e12) + " XMR)");
                    Log.d(TAG, "Confirmations: " + txInfo.confirmations);
                    Log.d(TAG, "Block height: " + txInfo.blockheight);
                    Log.d(TAG, "Direction: " + (txInfo.direction == TransactionInfo.Direction.Direction_In ? "INCOMING" : "OUTGOING"));
                    Log.d(TAG, "Is failed: " + txInfo.isFailed);
                    Log.d(TAG, "Is pending: " + txInfo.isPending);
                    Log.d(TAG, "Timestamp: " + txInfo.timestamp);
                    
                    // CRITICAL CHECKS
                    if (txInfo.blockheight > currentHeight) {
                        Log.e(TAG, "⚠️ PROBLEM: Transaction block (" + txInfo.blockheight + ") is ahead of wallet height (" + currentHeight + ")");
                        Log.e(TAG, "⚠️ Wallet needs to sync further!");
                    }
                    
                    if (txInfo.blockheight < restoreHeight) {
                        Log.e(TAG, "⚠️ PROBLEM: Transaction block (" + txInfo.blockheight + ") is before restore height (" + restoreHeight + ")");
                        Log.e(TAG, "⚠️ Need to rescan from block " + txInfo.blockheight + " or earlier!");
                    }
                    
                    if (txInfo.direction == TransactionInfo.Direction.Direction_In && walletBalance == 0) {
                        Log.e(TAG, "⚠️ PROBLEM: Incoming transaction found but balance is 0!");
                        Log.e(TAG, "⚠️ This suggests outputs were not properly imported or were already spent");
                    }
                    
                } else {
                    Log.d(TAG, "Target transaction not found in wallet history");
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ Error during auto-search for transaction", e);
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
                Log.i(TAG, "=== SEARCH AND IMPORT TRANSACTION ===");
                Log.d(TAG, "TxID: " + txId);

                wallet.refresh();
                Log.d(TAG, "Wallet refresh complete");

                TransactionHistory history = wallet.getHistory();
                if (history == null) {
                    Log.w(TAG, "✗ Transaction history is null");
                    mainHandler.post(() -> callback.onError("Transaction history unavailable"));
                    return;
                }

                history.refresh();
                List<TransactionInfo> allTxs = history.getAll();
                Log.d(TAG, "Total transactions: " + (allTxs != null ? allTxs.size() : 0));

                TransactionInfo txInfo = null;
                if (allTxs != null) {
                    for (TransactionInfo info : allTxs) {
                        if (info.hash.equals(txId)) {
                            txInfo = info;
                            break;
                        }
                    }
                }

                if (txInfo != null) {
                    Log.i(TAG, "✓ Transaction found!");
                    long amount = txInfo.amount;
                    long confirmations = txInfo.confirmations;
                    long blockHeight = txInfo.blockheight;

                    if (statusListener != null) {
                        long bal = wallet.getBalance();
                        long ubal = wallet.getUnlockedBalance();
                        mainHandler.post(() -> statusListener.onBalanceUpdated(bal, ubal));
                    }

                    wallet.store(currentWalletPath);
                    mainHandler.post(() -> callback.onTransactionFound(txId, amount, confirmations, blockHeight));
                } else {
                    Log.w(TAG, "✗ Transaction not found");
                    mainHandler.post(() -> callback.onTransactionNotFound(txId));
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ Error searching for transaction", e);
                mainHandler.post(() -> callback.onError("Search failed: " + e.getMessage()));
            }
        });
    }
    
    public void searchForMissingTransaction(String txId, TransactionSearchCallback callback) {
        searchAndImportTransaction(txId, callback);
    }
    
    public void importSignedTransactionBlob(String signedTxBlob, TransactionImportCallback cb) {
        if (!isInitialized || wallet == null) {
            cb.onError("Wallet not ready");
            return;
        }
        executorService.execute(() -> {
            try {
                Log.i(TAG, "=== IMPORT SIGNED TRANSACTION ===");
                byte[] blob = Base64.decode(signedTxBlob, Base64.NO_WRAP);
                String hex = bytesToHex(blob);

                String txId = wallet.submitTransaction(hex);
                if (txId == null || txId.isEmpty()) {
                    String error = wallet.getErrorString();
                    mainHandler.post(() -> cb.onError("Import failed: " + (error != null ? error : "Unknown error")));
                    return;
                }

                Log.i(TAG, "✓ Transaction imported: " + txId);
                mainHandler.post(() -> triggerImmediateSync());
                mainHandler.post(() -> cb.onSuccess(txId));
            } catch (Exception e) {
                Log.e(TAG, "✗ Exception in importSignedTransactionBlob", e);
                mainHandler.post(() -> cb.onError("Import failed: " + e.getMessage()));
            }
        });
    }

    public void performManualRescan(long fromHeight, SyncCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not ready");
            return;
        }

        if (isSyncing) {
            callback.onError("Sync already in progress");
            return;
        }

        executorService.execute(() -> {
            try {
                Log.i(TAG, "=== MANUAL RESCAN REQUESTED ===");
                Log.d(TAG, "Rescanning from height: " + fromHeight);

                wallet.setRestoreHeight(fromHeight);
                wallet.rescanBlockchainAsync(new Wallet.RescanCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(TAG, "✓ Manual rescan completed");
                        try {
                            wallet.store(currentWalletPath);
                            if (statusListener != null) {
                                long bal = wallet.getBalance();
                                long ubal = wallet.getUnlockedBalance();
                                mainHandler.post(() -> statusListener.onBalanceUpdated(bal, ubal));
                            }
                            mainHandler.post(() -> callback.onSuccess("Rescan completed successfully"));
                        } catch (Exception e) {
                            mainHandler.post(() -> callback.onError("Rescan completed but error updating: " + e.getMessage()));
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "✗ Manual rescan failed: " + error);
                        mainHandler.post(() -> callback.onError("Rescan failed: " + error));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "✗ Exception during manual rescan", e);
                mainHandler.post(() -> callback.onError("Rescan failed: " + e.getMessage()));
            }
        });
    }
}
