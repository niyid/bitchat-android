package com.bitchat.android.monero.wallet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.m2049r.xmrwallet.model.Wallet;
import com.m2049r.xmrwallet.model.WalletListener;
import com.m2049r.xmrwallet.model.WalletManager;
import com.m2049r.xmrwallet.model.PendingTransaction;
import com.m2049r.xmrwallet.util.Helper;
import android.util.Base64;

/**
 * Enhanced Monero wallet manager for BitChat using Monerujo library
 * Handles wallet creation, transactions, balance management, and Bluetooth/Chat blob workflows
 * Configuration is loaded from wallet.properties file      
 */
public class WalletSuite {
    private static final String TAG = "com.bitchat.WalletSuite";
    private static final String PROPERTIES_FILE = "wallet.properties";
    
    // Default values (fallbacks)
    private static final String DEFAULT_WALLET_NAME = "bitchat_wallet";
    private static final String DEFAULT_WALLET_PASSWORD = "bitchat_secure_pass";
    private static final String DEFAULT_WALLET_LANGUAGE = "English";
    private static final String DEFAULT_DAEMON_ADDRESS = "http://172.20.10.5";
    private static final int DEFAULT_DAEMON_PORT = 38081;
    private static final String DEFAULT_DAEMON_USERNAME = "rpc_user";
    private static final String DEFAULT_DAEMON_PASSWORD = "rpc_password";
    private static final int DEFAULT_NETWORK_TYPE = 2; // stagenet

    private static volatile boolean nativeOk = false;
    private static volatile boolean nativeChecked = false;
    private static volatile WalletSuite instance;

    // Configuration properties
    private String walletName;
    private String walletPassword;
    private String walletLanguage;
    private String daemonAddress;
    private int daemonPort;
    private String daemonUsername;
    private String daemonPassword;
    private int networkType;

    private WalletSuite() {}

    public static boolean nativeAvailable() {
        if (!nativeChecked) {
            synchronized (WalletSuite.class) {
                if (!nativeChecked) {
                    try {
                        Log.i(TAG, "Attempting to load native library monerujo");
                        System.loadLibrary("monerujo");
                        nativeOk = true;
                        Log.i(TAG, "Native library monerujo loaded successfully");
                    } catch (UnsatisfiedLinkError e) {
                        Log.w(TAG, "Failed to load native library monerujo", e);
                        nativeOk = false;
                    } catch (Exception e) {
                        Log.e(TAG, "Unexpected error loading native library", e);
                        nativeOk = false;
                    }
                    nativeChecked = true;
                }
            }
        }
        return nativeOk;
    }

    private Wallet wallet;
    private Context context;
    private ExecutorService executorService;
    private Handler mainHandler;
    private boolean isInitialized = false;
    private boolean isSyncing = false;

    private WalletStatusListener statusListener;
    private TransactionListener transactionListener;

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

    private WalletSuite(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
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

    private void loadConfiguration() {
        Properties props = new Properties();
        
        // Try loading from external storage first (for easy updates)
        File externalConfig = new File(context.getExternalFilesDir(null), PROPERTIES_FILE);
        
        // Try loading from internal assets (bundled with app)
        File internalConfig = new File(context.getFilesDir(), PROPERTIES_FILE);
        
        boolean configLoaded = false;
        
        // Priority 1: External storage (user-modifiable)
        if (externalConfig.exists()) {
            try (FileInputStream fis = new FileInputStream(externalConfig)) {
                props.load(fis);
                configLoaded = true;
                Log.i(TAG, "Loaded configuration from external storage: " + externalConfig.getAbsolutePath());
            } catch (IOException e) {
                Log.w(TAG, "Failed to load external config file", e);
            }
        }
        
        // Priority 2: Internal storage
        if (!configLoaded && internalConfig.exists()) {
            try (FileInputStream fis = new FileInputStream(internalConfig)) {
                props.load(fis);
                configLoaded = true;
                Log.i(TAG, "Loaded configuration from internal storage: " + internalConfig.getAbsolutePath());
            } catch (IOException e) {
                Log.w(TAG, "Failed to load internal config file", e);
            }
        }
        
        // Priority 3: Assets folder (bundled with APK)
        if (!configLoaded) {
            try (InputStream is = context.getAssets().open(PROPERTIES_FILE)) {
                props.load(is);
                configLoaded = true;
                Log.i(TAG, "Loaded configuration from assets");
            } catch (IOException e) {
                Log.w(TAG, "No config file found in assets, using defaults", e);
            }
        }
        
        // Load properties with fallback to defaults
        walletName = props.getProperty("wallet.name", DEFAULT_WALLET_NAME);
        walletPassword = props.getProperty("wallet.password", DEFAULT_WALLET_PASSWORD);
        walletLanguage = props.getProperty("wallet.language", DEFAULT_WALLET_LANGUAGE);
        daemonAddress = props.getProperty("daemon.address", DEFAULT_DAEMON_ADDRESS);
        daemonPort = Integer.parseInt(props.getProperty("daemon.port", String.valueOf(DEFAULT_DAEMON_PORT)));
        daemonUsername = props.getProperty("daemon.username", DEFAULT_DAEMON_USERNAME);
        daemonPassword = props.getProperty("daemon.password", DEFAULT_DAEMON_PASSWORD);
        networkType = Integer.parseInt(props.getProperty("network.type", String.valueOf(DEFAULT_NETWORK_TYPE)));
        
        Log.d(TAG, "Configuration loaded - Network: " + getNetworkName() + ", Daemon: " + daemonAddress + ":" + daemonPort);
    }
    
    private String getNetworkName() {
        return (networkType == 1) ? "testnet" : (networkType == 2) ? "stagenet" : "mainnet";
    }

    public void initializeWallet() {
        executorService.execute(() -> {
            try {
                File dir = context.getDir("wallets", Context.MODE_PRIVATE);
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "Failed to create wallets directory: " + dir.getAbsolutePath());
                    notifyWalletInitialized(false, "Failed to create wallets directory");
                    return;
                }

                if (!dir.isDirectory() || !dir.canWrite()) {
                    Log.e(TAG, "Wallet directory not writable: " + dir.getAbsolutePath());
                    notifyWalletInitialized(false, "Wallet directory not writable");
                    return;
                }

                String walletPath = new File(dir, walletName).getAbsolutePath();
                Log.d(TAG, "Wallet path: " + walletPath);

                File keysFile = new File(walletPath + ".keys");
                File cacheFile = new File(walletPath);
                File addrFile = new File(walletPath + ".address.txt");

                WalletManager mgr = WalletManager.getInstance();

                if (keysFile.exists() && cacheFile.exists()) {
                    Log.d(TAG, "Opening existing wallet...");
                    wallet = mgr.openWallet(walletPath, walletPassword, 1);
                } else if (keysFile.exists() || cacheFile.exists() || addrFile.exists()) {
                    backupFile(keysFile);
                    backupFile(cacheFile);
                    backupFile(addrFile);

                    Log.d(TAG, "Recreating wallet (networkType=" + networkType + ")");
                    wallet = mgr.createWallet(walletPath, walletPassword, walletLanguage, networkType);
                } else {
                    Log.d(TAG, "Creating new wallet (networkType=" + networkType + ")");
                    wallet = mgr.createWallet(walletPath, walletPassword, walletLanguage, networkType);
                }

                if (wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal()) {
                    setupWallet();
                    isInitialized = true;
                    String networkName = (networkType == 1) ? "testnet"
                                       : (networkType == 2) ? "stagenet"
                                       : "mainnet";
                    Log.i(TAG, "Wallet initialized successfully (" + networkName + ")");
                    notifyWalletInitialized(true, "Wallet initialized successfully (" + networkName + ")");
                    startSync();
                } else {
                    String error = (wallet != null) ? wallet.getErrorString() : "Unknown JNI error";
                    Log.e(TAG, "Wallet initialization failed: " + error);
                    notifyWalletInitialized(false, "Failed to initialize wallet: " + error);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error initializing wallet", e);
                notifyWalletInitialized(false, "Error: " + e.getMessage());
            }
        });
    }

    private void backupFile(File file) {
        if (file != null && file.exists()) {
            String backupName = file.getAbsolutePath() + ".bak_" + System.currentTimeMillis();
            boolean renamed = file.renameTo(new File(backupName));
            if (renamed) {
                Log.w(TAG, "Backed up " + file.getName() + " → " + backupName);
            } else {
                Log.e(TAG, "Failed to back up " + file.getName());
            }
        }
    }

    public void initializeWalletFromSeed(String seedPhrase, long restoreHeight, int nettype) {
        // Use provided nettype or fall back to configured value
        int networkType = (nettype > 0) ? nettype : this.networkType;
        
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();

                WalletManager mgr = WalletManager.getInstance();
                wallet = mgr.recoveryWallet(walletPath, walletPassword, seedPhrase, networkType, restoreHeight);

                if (wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal()) {
                    setupWallet();
                    isInitialized = true;
                    notifyWalletInitialized(true, "Wallet restored from seed successfully");
                    startSync();
                } else {
                    String error = wallet != null ? wallet.getErrorString() : "Unknown error";
                    Log.e(TAG, "Wallet restore failed: " + error);
                    notifyWalletInitialized(false, "Failed to restore wallet: " + error);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error restoring wallet from seed", e);
                notifyWalletInitialized(false, "Error: " + e.getMessage());
            }
        });
    }

    private String getWalletPath() {
        File walletDir = new File(context.getFilesDir(), "monero");
        if (!walletDir.exists()) {
            walletDir.mkdirs();
        }
        return new File(walletDir, walletName).getAbsolutePath();
    }

    public void getAddress(AddressCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }

        executorService.execute(() -> {
            try {
                String address = wallet.getAddress();
                mainHandler.post(() -> callback.onSuccess(address));
            } catch (Exception e) {
                Log.e(TAG, "Error getting address", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface AddressCallback {
        void onSuccess(String address);
        void onError(String error);
    }

    public void getBalance(BalanceCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }

        executorService.execute(() -> {
            try {
                long balance = wallet.getBalance();
                long unlockedBalance = wallet.getUnlockedBalance();
                mainHandler.post(() -> callback.onSuccess(balance, unlockedBalance));
            } catch (Exception e) {
                Log.e(TAG, "Error getting balance", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface BalanceCallback {
        void onSuccess(long balance, long unlockedBalance);
        void onError(String error);
    }

    private void updateBalance() {
        if (!isInitialized || wallet == null) return;

        executorService.execute(() -> {
            try {
                long balance = wallet.getBalance();
                long unlockedBalance = wallet.getUnlockedBalance();
                if (statusListener != null) {
                    mainHandler.post(() -> statusListener.onBalanceUpdated(balance, unlockedBalance));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating balance", e);
            }
        });
    }

    public void sendMonero(String toAddress, String amount, SendCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }

        executorService.execute(() -> {
            try {
                long atomicAmount = Helper.getAmountFromString(amount);

                PendingTransaction pendingTx = wallet.createTransaction(
                        toAddress,
                        "",
                        atomicAmount,
                        0,
                        PendingTransaction.Priority.Priority_Default.ordinal()
                );

                if (pendingTx.getStatus() != PendingTransaction.Status.Status_Ok.ordinal()) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "Transaction creation failed: " + error);
                    mainHandler.post(() -> callback.onError("Transaction failed: " + error));
                    return;
                }

                long fee = pendingTx.getFee();
                String txId = pendingTx.getFirstTxId();

                Log.d(TAG, "Transaction created: " + txId + ", Fee: " + Helper.getDisplayAmount(fee) + " XMR");

                boolean committed = pendingTx.commit("", true);

                if (committed) {
                    if (transactionListener != null) {
                        mainHandler.post(() -> transactionListener.onTransactionCreated(txId, atomicAmount));
                    }
                    mainHandler.post(() -> callback.onSuccess(txId, atomicAmount, fee));
                } else {
                    String error = "Failed to commit transaction";
                    Log.e(TAG, error);
                    mainHandler.post(() -> callback.onError(error));
                }

            } catch (Exception e) {
                Log.e(TAG, "Error sending Monero", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
                if (transactionListener != null) {
                    mainHandler.post(() -> transactionListener.onTransactionFailed("", e.getMessage()));
                }
            }
        });
    }

    public interface SendCallback {
        void onSuccess(String txId, long amount, long fee);
        void onError(String error);
    }

    public void syncWallet(SyncCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }

        executorService.execute(() -> {
            try {
                WalletListener syncListener = new WalletListener() {
                    @Override
                    public void moneySent(String txId, long amount) {}

                    @Override
                    public void moneyReceived(String txId, long amount) {
                        if (transactionListener != null) {
                            mainHandler.post(() -> transactionListener.onOutputReceived(amount, txId, true));
                        }
                        updateBalance();
                    }

                    @Override
                    public void unconfirmedMoneyReceived(String txId, long amount) {
                        if (transactionListener != null) {
                            mainHandler.post(() -> transactionListener.onOutputReceived(amount, txId, false));
                        }
                    }

                    @Override
                    public void newBlock(long height) {
                        if (statusListener != null) {
                            long daemonHeight = wallet.getDaemonBlockChainHeight();
                            double progress = daemonHeight > 0 ? (double) height / daemonHeight * 100.0 : 0.0;
                            mainHandler.post(() -> statusListener.onSyncProgress(height, 0, daemonHeight, progress));
                        }
                    }

                    @Override
                    public void updated() {
                        updateBalance();
                    }

                    @Override
                    public void refreshed() {
                        updateBalance();
                    }
                };

                wallet.setListener(syncListener);

                boolean synced = wallet.refresh();

                if (synced) {
                    mainHandler.post(() -> callback.onSuccess("Sync completed"));
                } else {
                    String error = wallet.getErrorString();
                    Log.e(TAG, "Sync failed: " + error);
                    mainHandler.post(() -> callback.onError("Sync failed: " + error));
                }

            } catch (Exception e) {
                Log.e(TAG, "Error syncing wallet", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface SyncCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    private void startSync() {
        if (wallet == null || isSyncing) return;

        executorService.execute(() -> {
            try {
                isSyncing = true;
                Log.d(TAG, "Synchronization initiated...");
                syncWallet(new SyncCallback() {
                    @Override
                    public void onSuccess(String message) {
                        Log.d(TAG, "Initial sync completed: " + message);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Initial sync failed: " + error);
                        isSyncing = false;
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error starting sync", e);
                isSyncing = false;
            }
        });
    }

    public void stopSync() {
        if (wallet != null) {
            isSyncing = false;
            Log.d(TAG, "Sync stopped");
        }
    }
    
    private void setupWallet() {
        if (wallet == null) return;

        WalletManager mgr = WalletManager.getInstance();

        // Construct daemon URL with optional username/password
        String url;
        if (daemonUsername != null && !daemonUsername.isEmpty() &&
            daemonPassword != null && !daemonPassword.isEmpty()) {
            url = String.format("http://%s:%s@%s:%d",
                    daemonUsername, daemonPassword, daemonAddress, daemonPort);
        } else {
            url = String.format("http://%s:%d", daemonAddress, daemonPort);
        }

        boolean connected = mgr.setDaemonAddress(url, daemonPort);
        if (!connected) {
            Log.w(TAG, "Failed to connect to daemon at " + url);
        } else {
            Log.i(TAG, "Connected to daemon at " + url);
        }

        Log.d(TAG, "Wallet setup completed");
    }
    
    public boolean isSyncing() {
        return isSyncing && wallet != null;
    }

    public void saveWallet() {
        if (wallet != null) {
            executorService.execute(() -> {
                try {
                    boolean saved = wallet.store("");
                    if (saved) {
                        Log.d(TAG, "Wallet saved");
                    } else {
                        Log.e(TAG, "Failed to save wallet: " + wallet.getErrorString());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error saving wallet", e);
                }
            });
        }
    }

    public void close() {
        executorService.execute(() -> {
            try {
                if (wallet != null) {
                    isSyncing = false;
                    wallet.store("");
                    wallet.close();
                    wallet = null;
                }
                isInitialized = false;
                Log.d(TAG, "Wallet closed and saved");
            } catch (Exception e) {
                Log.e(TAG, "Error closing wallet", e);
            }
        });

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void setWalletStatusListener(WalletStatusListener listener) {
        this.statusListener = listener;
    }

    public void setTransactionListener(TransactionListener listener) {
        this.transactionListener = listener;
    }

    private void notifyWalletInitialized(boolean success, String message) {
        if (statusListener != null) {
            mainHandler.post(() -> statusListener.onWalletInitialized(success, message));
        }
    }

    public boolean isReady() {
        return isInitialized && wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal();
    }

    public void getSeedPhrase(SeedCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }

        executorService.execute(() -> {
            try {
                String seed = wallet.getSeed("");
                mainHandler.post(() -> callback.onSuccess(seed));
            } catch (Exception e) {
                Log.e(TAG, "Error getting seed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface SeedCallback {
        void onSuccess(String seedPhrase);
        void onError(String error);
    }

    public void getSyncInfo(SyncInfoCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }

        executorService.execute(() -> {
            try {
                long height = wallet.getBlockChainHeight();
                long daemonHeight = wallet.getDaemonBlockChainHeight();
                boolean isSynced = height >= daemonHeight;

                mainHandler.post(() -> callback.onSuccess(height, daemonHeight, isSynced));
            } catch (Exception e) {
                Log.e(TAG, "Error getting sync info", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface SyncInfoCallback {
        void onSuccess(long walletHeight, long daemonHeight, boolean isSynced);
        void onError(String error);
    }

    public static String convertAtomicToXmr(long atomic) {
        return Helper.getDisplayAmount(atomic);
    }

    public static long convertXmrToAtomic(String xmr) {
        return Helper.getAmountFromString(xmr);
    }

    public void getTransactionHistory(TransactionHistoryCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }

        executorService.execute(() -> {
            try {
                wallet.refreshHistory();
                mainHandler.post(() -> callback.onSuccess("Transaction history updated"));
            } catch (Exception e) {
                Log.e(TAG, "Error getting transaction history", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface TransactionHistoryCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    // ==================== CHAT / BLUETOOTH TRANSACTION BLOB SUPPORT ====================

    public void createTxBlob(String toAddress, String amount, TxBlobCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }

        executorService.execute(() -> {
            try {
                long atomicAmount = Helper.getAmountFromString(amount);

                Log.d(TAG, "Preparing to create transaction blob");

                PendingTransaction pendingTx = wallet.createTransaction(
                        toAddress,
                        "",
                        atomicAmount,
                        0,
                        PendingTransaction.Priority.Priority_Default.ordinal()
                );
 
                Log.d(TAG, "Transaction created; now to extract the blob");

                if (pendingTx.getStatus() != PendingTransaction.Status.Status_Ok.ordinal()) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "Failed to create tx blob: " + error);
                    mainHandler.post(() -> callback.onError(error));
                    return;
                }

                byte[] rawBlob = pendingTx.getSerializedTransaction();
                String base64Blob = Base64.encodeToString(rawBlob, Base64.NO_WRAP);
                String txId = pendingTx.getFirstTxId();

                Log.d(TAG, "Created TX blob: " + txId);

                mainHandler.post(() -> callback.onSuccess(txId, base64Blob));

            } catch (Exception e) {
                Log.e(TAG, "Error creating tx blob", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface TxBlobCallback {
        void onSuccess(String txId, String base64Blob);
        void onError(String error);
    }

    public void submitTxBlob(byte[] blob, TxBlobCallback callback) {
        if (!isInitialized || wallet == null) {
            Log.e(TAG, "Wallet not initialized for submitTxBlob");
            if (callback != null) {
                callback.onError("Wallet not initialized");
            }
            return;
        }

        executorService.execute(() -> {
            try {
                // Convert raw bytes to hex string for JNI
                String txHex = bytesToHex(blob);

                Log.d(TAG, "Submitting TX blob of length " + blob.length);

                // JNI call expects hex string
                String txId = wallet.submitTransaction(txHex);

                if (callback != null) {
                    // Still return base64 blob for consistency/logging
                    String base64Blob = Base64.encodeToString(blob, Base64.NO_WRAP);
                    mainHandler.post(() -> callback.onSuccess(txId, base64Blob));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error submitting tx blob", e);
                if (callback != null) {
                    final String errorMsg = e.getMessage(); // make it effectively final
                    mainHandler.post(() -> callback.onError(errorMsg));
                }
            }
        });
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // ==================== CONFIGURATION UTILITIES ====================

    /**
     * Copy the default configuration from assets to external storage
     * This allows users to modify the configuration file
     */
    public static void copyDefaultConfigToExternalStorage(Context context) {
        try {
            File externalConfig = new File(context.getExternalFilesDir(null), PROPERTIES_FILE);
            if (externalConfig.exists()) {
                Log.i(TAG, "Configuration file already exists in external storage");
                return;
            }

            InputStream inputStream = context.getAssets().open(PROPERTIES_FILE);
            java.io.FileOutputStream outputStream = new java.io.FileOutputStream(externalConfig);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }

            outputStream.close();
            inputStream.close();

            Log.i(TAG, "Default configuration copied to: " + externalConfig.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy default configuration", e);
        }
    }

    /**
     * Get the current configuration file path being used
     */
    public String getCurrentConfigPath() {
        File externalConfig = new File(context.getExternalFilesDir(null), PROPERTIES_FILE);
        if (externalConfig.exists()) {
            return externalConfig.getAbsolutePath();
        }
        
        File internalConfig = new File(context.getFilesDir(), PROPERTIES_FILE);
        if (internalConfig.exists()) {
            return internalConfig.getAbsolutePath();
        }
        
        return "assets/" + PROPERTIES_FILE;
    }

    /**
     * Reload configuration from file
     * Useful after user modifies the configuration file
     */
    public void reloadConfiguration() {
        loadConfiguration();
        Log.i(TAG, "Configuration reloaded from: " + getCurrentConfigPath());
    }
}
