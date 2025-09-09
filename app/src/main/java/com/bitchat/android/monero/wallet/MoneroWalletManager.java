package com.bitchat.android.monero.wallet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JNI-based Monero Wallet Manager for BitChat.
 * Uses libmonerujo.so directly (JNI backend).
 * Handles wallet lifecycle, balance, transactions, and sync.
 */
public class MoneroWalletManager {

    private static final String TAG = "MoneroWalletManager";
    private static final String WALLET_NAME = "bitchat_wallet";
    private static final String WALLET_PASSWORD = "bitchat_secure_pass"; // replace with secure storage
    private static final String WALLET_LANGUAGE = "English";

    static {
        try {
            System.loadLibrary("monerujo"); // loads libmonerujo.so
            Log.d(TAG, "Monero native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load Monero native library", e);
            throw new RuntimeException("Cannot load libmonerujo.so", e);
        }
    }

    private static MoneroWalletManager instance;
    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    private long nativeHandle = 0; // pointer to the native wallet
    private boolean isInitialized = false;

    private WalletStatusListener statusListener;
    private TransactionListener transactionListener;

    // === JNI native methods ===
    private native long jniCreateWallet(String path, String password, String language, boolean testnet);
    private native long jniOpenWallet(String path, String password);
    private native String jniGetAddress(long handle);
    private native long jniGetBalance(long handle);
    private native long jniGetUnlockedBalance(long handle);
    private native boolean jniStore(long handle);
    private native void jniClose(long handle);
    private native String jniGetSeed(long handle);
    private native String jniSendTransaction(long handle, String address, long amount);
    private native boolean jniSync(long handle);
    private native long jniGetHeight(long handle);
    private native long jniGetDaemonHeight(long handle);

    // === Listeners ===
    public interface WalletStatusListener {
        void onWalletInitialized(boolean success, String message);
        void onBalanceUpdated(long balance, long unlockedBalance);
        void onSyncProgress(long height, long daemonHeight, double percentDone);
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

    private String getWalletPath() {
        File walletDir = new File(context.getFilesDir(), "monero");
        if (!walletDir.exists()) {
            walletDir.mkdirs();
        }
        return new File(walletDir, WALLET_NAME).getAbsolutePath();
    }

    /**
     * Create or open wallet.
     */
    public void initializeWallet(boolean testnet) {
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();
                File keysFile = new File(walletPath + ".keys");

                if (keysFile.exists()) {
                    nativeHandle = jniOpenWallet(walletPath, WALLET_PASSWORD);
                    Log.d(TAG, "Opened existing wallet");
                } else {
                    nativeHandle = jniCreateWallet(walletPath, WALLET_PASSWORD, WALLET_LANGUAGE, testnet);
                    Log.d(TAG, "Created new wallet");
                }

                isInitialized = nativeHandle != 0;
                notifyWalletInitialized(isInitialized, isInitialized ? "Wallet ready" : "Failed to initialize wallet");

                if (isInitialized) {
                    syncWallet();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error initializing wallet", e);
                notifyWalletInitialized(false, e.getMessage());
            }
        });
    }

    /**
     * Get wallet address.
     */
    public void getAddress(AddressCallback callback) {
        if (!isInitialized) {
            callback.onError("Wallet not initialized");
            return;
        }
        executorService.execute(() -> {
            try {
                String address = jniGetAddress(nativeHandle);
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

    /**
     * Get balance.
     */
    public void getBalance(BalanceCallback callback) {
        if (!isInitialized) {
            callback.onError("Wallet not initialized");
            return;
        }
        executorService.execute(() -> {
            try {
                long balance = jniGetBalance(nativeHandle);
                long unlocked = jniGetUnlockedBalance(nativeHandle);
                mainHandler.post(() -> callback.onSuccess(balance, unlocked));
                if (statusListener != null) {
                    mainHandler.post(() -> statusListener.onBalanceUpdated(balance, unlocked));
                }
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

    /**
     * Send Monero.
     */
    public void sendMonero(String toAddress, long amount, SendCallback callback) {
        if (!isInitialized) {
            callback.onError("Wallet not initialized");
            return;
        }
        executorService.execute(() -> {
            try {
                String txId = jniSendTransaction(nativeHandle, toAddress, amount);
                if (txId != null && !txId.isEmpty()) {
                    if (transactionListener != null) {
                        mainHandler.post(() -> transactionListener.onTransactionCreated(txId, amount));
                    }
                    mainHandler.post(() -> callback.onSuccess(txId, amount));
                } else {
                    String error = "Transaction failed";
                    if (transactionListener != null) {
                        mainHandler.post(() -> transactionListener.onTransactionFailed(txId, error));
                    }
                    mainHandler.post(() -> callback.onError(error));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending Monero", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface SendCallback {
        void onSuccess(String txId, long atomicAmount);
        void onError(String error);
    }

    /**
     * Sync wallet.
     */
    public void syncWallet() {
        if (!isInitialized) return;
        executorService.execute(() -> {
            try {
                boolean ok = jniSync(nativeHandle);
                long height = jniGetHeight(nativeHandle);
                long daemonHeight = jniGetDaemonHeight(nativeHandle);
                double progress = daemonHeight > 0 ? (double) height / daemonHeight * 100.0 : 0.0;
                if (statusListener != null) {
                    mainHandler.post(() -> statusListener.onSyncProgress(height, daemonHeight, progress));
                }
                Log.d(TAG, "Sync finished: " + ok);
            } catch (Exception e) {
                Log.e(TAG, "Error syncing wallet", e);
            }
        });
    }

    /**
     * Get seed phrase.
     */
    public void getSeed(SeedCallback callback) {
        if (!isInitialized) {
            callback.onError("Wallet not initialized");
            return;
        }
        executorService.execute(() -> {
            try {
                String seed = jniGetSeed(nativeHandle);
                mainHandler.post(() -> callback.onSuccess(seed));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface SeedCallback {
        void onSuccess(String seedPhrase);
        void onError(String error);
    }

    /**
     * Save wallet.
     */
    public void saveWallet() {
        if (!isInitialized) return;
        executorService.execute(() -> {
            try {
                boolean saved = jniStore(nativeHandle);
                Log.d(TAG, saved ? "Wallet saved" : "Failed to save wallet");
            } catch (Exception e) {
                Log.e(TAG, "Error saving wallet", e);
            }
        });
    }

    /**
     * Close wallet.
     */
    public void close() {
        executorService.execute(() -> {
            try {
                if (nativeHandle != 0) {
                    jniStore(nativeHandle);
                    jniClose(nativeHandle);
                    nativeHandle = 0;
                }
                isInitialized = false;
                Log.d(TAG, "Wallet closed");
            } catch (Exception e) {
                Log.e(TAG, "Error closing wallet", e);
            }
        });
        shutdownExecutor();
    }

    private void shutdownExecutor() {
        if (!executorService.isShutdown()) {
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
    
    /**
     * Convert atomic units to XMR with better precision
     */
    public static String convertAtomicToXmr(Long atomic) {
        if (atomic == null) return "0.000000";
        double xmr = atomic.doubleValue() / 1000000000000.0; // 10^12
        return String.format("%.6f", xmr);
    }    

    // === Listener setters ===
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
}

