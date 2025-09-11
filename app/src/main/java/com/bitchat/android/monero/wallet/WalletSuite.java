package com.bitchat.android.monero.wallet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.m2049r.xmrwallet.model.Wallet;
import com.m2049r.xmrwallet.model.WalletManager;
import com.m2049r.xmrwallet.model.PendingTransaction;
import com.m2049r.xmrwallet.util.Helper;

/**
 * Enhanced Monero wallet manager for BitChat using Monerujo library
 * Handles wallet creation, transactions, balance management, and Bluetooth/Chat blob workflows
 */
public class WalletSuite {
    private static final String TAG = "com.bitchat.android.monero.wallet.WalletSuite";
    private static final String WALLET_NAME = "bitchat_wallet";
    private static final String WALLET_PASSWORD = "bitchat_secure_pass"; // In production, use secure storage / key derivation
    private static final String WALLET_LANGUAGE = "English";

    private static volatile boolean nativeOk = false;
    private static volatile boolean nativeChecked = false;
    private static volatile WalletSuite instance;

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

    private String daemonAddress = "node.xmr.to";
    private int daemonPort = 18081;
    private String daemonUsername = "";
    private String daemonPassword = "";
    private boolean isTestnet = false;

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
    }

    public static synchronized WalletSuite getInstance(Context context) {
        if (instance == null) {
            instance = new WalletSuite(context);
        }
        return instance;
    }

    public void setDaemonConfig(String address, int port, String username, String password, boolean testnet) {
        this.daemonAddress = address;
        this.daemonPort = port;
        this.daemonUsername = username;
        this.daemonPassword = password;
        this.isTestnet = testnet;
    }

    public void initializeWallet(int networkType) {
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

                String walletPath = new File(dir, WALLET_NAME).getAbsolutePath();
                Log.d(TAG, "Wallet path: " + walletPath);

                File keysFile = new File(walletPath + ".keys");
                File cacheFile = new File(walletPath);
                File addrFile = new File(walletPath + ".address.txt");

                WalletManager mgr = WalletManager.getInstance();

                if (keysFile.exists() && cacheFile.exists()) {
                    Log.d(TAG, "Opening existing wallet...");
                    wallet = mgr.openWalletJ(walletPath, WALLET_PASSWORD);
                } else if (keysFile.exists() || cacheFile.exists() || addrFile.exists()) {
                    backupFile(keysFile);
                    backupFile(cacheFile);
                    backupFile(addrFile);

                    Log.d(TAG, "Recreating wallet (networkType=" + networkType + ")");
                    wallet = mgr.createWalletJ(walletPath, WALLET_PASSWORD, WALLET_LANGUAGE, networkType);
                } else {
                    Log.d(TAG, "Creating new wallet (networkType=" + networkType + ")");
                    wallet = mgr.createWalletJ(walletPath, WALLET_PASSWORD, WALLET_LANGUAGE, networkType);
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

    public void initializeWalletFromSeed(String seedPhrase, long restoreHeight) {
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();

                WalletManager mgr = WalletManager.getInstance();
                wallet = mgr.recoveryWalletJ(walletPath, WALLET_PASSWORD, seedPhrase, "", restoreHeight);

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
        return new File(walletDir, WALLET_NAME).getAbsolutePath();
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
                Wallet.Listener syncListener = new Wallet.Listener() {
                    @Override
                    public void moneySpent(String txId, long amount) {}

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

        boolean connected = wallet.setDaemonAddress(daemonAddress, daemonPort);
        if (!connected) {
            Log.w(TAG, "Failed to connect to daemon");
        }

        if (!daemonUsername.isEmpty()) {
            wallet.setDaemonLogin(daemonUsername, daemonPassword);
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

                PendingTransaction pendingTx = wallet.createTransaction(
                        toAddress,
                        "",
                        atomicAmount,
                        0,
                        PendingTransaction.Priority.Priority_Default.ordinal()
                );

                if (pendingTx.getStatus() != PendingTransaction.Status.Status_Ok.ordinal()) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "Failed to create tx blob: " + error);
                    mainHandler.post(() -> callback.onError(error));
                    return;
                }

                byte[] rawBlob = pendingTx.getSerializedTransaction();
                String base64Blob = android.util.Base64.encodeToString(rawBlob, android.util.Base64.NO_WRAP);
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

    public String submitTxBlob(byte[] blob) {
        if (!isInitialized || wallet == null) {
            Log.e(TAG, "Wallet not initialized for submitTxBlob");
            return null;
        }
        try {
            // Convert byte[] to String (JNI expects serialized tx)
            String txData = new String(blob);
            return wallet.submitTransaction(txData);
        } catch (Exception e) {
            Log.e(TAG, "Error submitting tx blob", e);
            return null;
        }
    }
}

