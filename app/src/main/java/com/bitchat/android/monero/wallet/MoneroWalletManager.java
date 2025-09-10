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
public class MoneroWalletManager {

    private static final String TAG = "MoneroWalletManager";
    private static final String WALLET_NAME = "bitchat_wallet";
    private static final String WALLET_PASSWORD = "bitchat_secure_pass"; // In production, use secure storage / key derivation
    private static final String WALLET_LANGUAGE = "English";

    // Load native Monerujo JNI library
    static {
        try {
            System.loadLibrary("monerujo");
            Log.d(TAG, "Monerujo native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load monerujo native library", e);
            throw new RuntimeException("Cannot load monerujo library", e);
        }
    }

    private static MoneroWalletManager instance;
    private Wallet wallet;
    private Context context;
    private ExecutorService executorService;
    private Handler mainHandler;
    private boolean isInitialized = false;
    private boolean isSyncing = false;

    // Configuration
    private String daemonAddress = "node.xmr.to";
    private int daemonPort = 18081;
    private String daemonUsername = "";
    private String daemonPassword = "";
    private boolean isTestnet = false;

    // Listeners
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

    private MoneroWalletManager(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized MoneroWalletManager getInstance(Context context) {
        if (instance == null) {
            instance = new MoneroWalletManager(context);
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

    /**
     * Initialize the Monero wallet.
     *
     * @param networkType 0 = mainnet, 1 = testnet, 2 = stagenet
     */
    public void initializeWallet(int networkType) {
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();
                File walletFile = new File(walletPath + ".keys");

                WalletManager mgr = WalletManager.getInstance();

                if (walletFile.exists()) {
                    wallet = mgr.openWallet(walletPath, WALLET_PASSWORD);
                    Log.d(TAG, "Existing wallet opened");
                } else {
                    wallet = mgr.createWallet(walletPath, WALLET_PASSWORD, WALLET_LANGUAGE, networkType);
                    String networkName = networkType == 1 ? "testnet" :
                                         networkType == 2 ? "stagenet" : "mainnet";
                    Log.d(TAG, "New wallet created (" + networkName + ")");
                }

                if (wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal()) {
                    setupWallet();
                    isInitialized = true;
                    String networkName = networkType == 1 ? "testnet" :
                                         networkType == 2 ? "stagenet" : "mainnet";
                    notifyWalletInitialized(true, "Wallet initialized successfully (" + networkName + ")");
                    startSync();
                } else {
                    String error = wallet != null ? wallet.getErrorString() : "Unknown error";
                    Log.e(TAG, "Wallet initialization failed: " + error);
                    notifyWalletInitialized(false, "Failed to initialize wallet: " + error);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error initializing wallet", e);
                notifyWalletInitialized(false, "Error: " + e.getMessage());
            }
        });
    }

    public void initializeWalletFromSeed(String seedPhrase, long restoreHeight) {
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();

                WalletManager mgr = WalletManager.getInstance();
                wallet = mgr.recoveryWallet(walletPath, WALLET_PASSWORD, seedPhrase, "", restoreHeight);

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

