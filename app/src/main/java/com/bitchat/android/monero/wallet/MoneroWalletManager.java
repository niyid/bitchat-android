package com.bitchat.android.monero.wallet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.math.BigInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroWalletConfig;
import monero.common.MoneroNetworkType;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroDestination;

/**
 * Manages Monero wallet operations for BitChat
 * Handles wallet creation, transactions, and balance management
 */
public class MoneroWalletManager {
    
    private static final String TAG = "MoneroWalletManager";
    private static final String WALLET_NAME = "bitchat_wallet";
    private static final String WALLET_PASSWORD = "bitchat_secure_pass"; // In production, use proper key derivation
    
    private static MoneroWalletManager instance;
    private MoneroWallet wallet;
    private Context context;
    private ExecutorService executorService;
    private Handler mainHandler;
    private boolean isInitialized = false;
    
    // Listeners
    private WalletStatusListener statusListener;
    private TransactionListener transactionListener;
    
    public interface WalletStatusListener {
        void onWalletInitialized(boolean success, String message);
        void onBalanceUpdated(BigInteger balance, BigInteger unlockedBalance);
        void onSyncProgress(long height, long targetHeight);
    }
    
    public interface TransactionListener {
        void onTransactionCreated(String txId, BigInteger amount);
        void onTransactionConfirmed(String txId);
        void onTransactionFailed(String txId, String error);
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
    
    /**
     * Initialize the wallet (create or open existing)
     */
    public void initializeWallet() {
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();
                File walletFile = new File(walletPath);
                
                if (walletFile.exists()) {
                    // Open existing wallet
                    openExistingWallet(walletPath);
                } else {
                    // Create new wallet
                    createNewWallet(walletPath);
                }
                
                if (wallet != null) {
                    isInitialized = true;
                    // Start background sync
                    startBackgroundSync();
                    notifyWalletInitialized(true, "Wallet initialized successfully");
                } else {
                    notifyWalletInitialized(false, "Failed to initialize wallet");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error initializing wallet", e);
                notifyWalletInitialized(false, "Error: " + e.getMessage());
            }
        });
    }
    
    private void createNewWallet(String walletPath) {
        try {
            Log.d(TAG, "Creating new wallet at: " + walletPath);
            
            MoneroWalletConfig config = new MoneroWalletConfig()
                .setPath(walletPath)
                .setPassword(WALLET_PASSWORD)
                .setNetworkType(MoneroNetworkType.MAINNET)
                .setRestoreHeight(0L);
            
            wallet = MoneroWallet.createWallet(config);
            Log.d(TAG, "New wallet created successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating new wallet", e);
            throw e;
        }
    }
    
    private void openExistingWallet(String walletPath) {
        try {
            Log.d(TAG, "Opening existing wallet at: " + walletPath);
            
            MoneroWalletConfig config = new MoneroWalletConfig()
                .setPath(walletPath)
                .setPassword(WALLET_PASSWORD)
                .setNetworkType(MoneroNetworkType.MAINNET);
            
            wallet = MoneroWallet.openWallet(config);
            Log.d(TAG, "Existing wallet opened successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error opening existing wallet", e);
            throw e;
        }
    }
    
    private String getWalletPath() {
        File walletDir = new File(context.getFilesDir(), "monero");
        if (!walletDir.exists()) {
            walletDir.mkdirs();
        }
        return new File(walletDir, WALLET_NAME).getAbsolutePath();
    }
    
    /**
     * Get the wallet's primary address
     */
    public void getAddress(AddressCallback callback) {
        if (!isInitialized) {
            callback.onError("Wallet not initialized");
            return;
        }
        
        executorService.execute(() -> {
            try {
                String address = wallet.getPrimaryAddress();
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
     * Get wallet balance
     */
    public void getBalance(BalanceCallback callback) {
        if (!isInitialized) {
            callback.onError("Wallet not initialized");
            return;
        }
        
        executorService.execute(() -> {
            try {
                BigInteger balance = wallet.getBalance();
                BigInteger unlockedBalance = wallet.getUnlockedBalance();
                mainHandler.post(() -> callback.onSuccess(balance, unlockedBalance));
            } catch (Exception e) {
                Log.e(TAG, "Error getting balance", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    public interface BalanceCallback {
        void onSuccess(BigInteger balance, BigInteger unlockedBalance);
        void onError(String error);
    }
    
    /**
     * Send Monero to an address
     */
    public void sendMonero(String toAddress, String amount, SendCallback callback) {
        if (!isInitialized) {
            callback.onError("Wallet not initialized");
            return;
        }
        
        executorService.execute(() -> {
            try {
                // Convert amount from XMR to atomic units
                BigInteger atomicAmount = convertXmrToAtomic(amount);
                
                // Create destination
                MoneroDestination destination = new MoneroDestination(toAddress, atomicAmount);
                
                // Create and relay transaction
                MoneroTxWallet tx = wallet.createTx(destination);
                String txId = tx.getHash();
                
                Log.d(TAG, "Transaction created: " + txId);
                
                if (transactionListener != null) {
                    mainHandler.post(() -> transactionListener.onTransactionCreated(txId, atomicAmount));
                }
                
                mainHandler.post(() -> callback.onSuccess(txId, atomicAmount));
                
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
        void onSuccess(String txId, BigInteger amount);
        void onError(String error);
    }
    
    /**
     * Convert XMR amount to atomic units (piconeros)
     * 1 XMR = 10^12 piconeros
     */
    private BigInteger convertXmrToAtomic(String xmrAmount) {
        try {
            double xmr = Double.parseDouble(xmrAmount);
            double piconeros = xmr * Math.pow(10, 12);
            return BigInteger.valueOf((long) piconeros);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid amount format: " + xmrAmount);
        }
    }
    
    /**
     * Convert atomic units to XMR
     */
    public static String convertAtomicToXmr(BigInteger atomic) {
        double xmr = atomic.doubleValue() / Math.pow(10, 12);
        return String.format("%.6f", xmr);
    }
    
    /**
     * Start background sync
     */
    private void startBackgroundSync() {
        if (wallet == null) return;
        
        executorService.execute(() -> {
            try {
                wallet.startSyncing(5000); // Sync every 5 seconds
                Log.d(TAG, "Background sync started");
            } catch (Exception e) {
                Log.e(TAG, "Error starting sync", e);
            }
        });
    }
    
    /**
     * Stop background sync
     */
    public void stopSync() {
        if (wallet != null) {
            try {
                wallet.stopSyncing();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping sync", e);
            }
        }
    }
    
    /**
     * Close wallet and cleanup
     */
    public void close() {
        executorService.execute(() -> {
            try {
                if (wallet != null) {
                    wallet.close();
                    wallet = null;
                }
                isInitialized = false;
                Log.d(TAG, "Wallet closed");
            } catch (Exception e) {
                Log.e(TAG, "Error closing wallet", e);
            }
        });
        
        if (executorService != null) {
            executorService.shutdown();
        }
    }
    
    // Listener setters
    public void setWalletStatusListener(WalletStatusListener listener) {
        this.statusListener = listener;
    }
    
    public void setTransactionListener(TransactionListener listener) {
        this.transactionListener = listener;
    }
    
    // Notification helpers
    private void notifyWalletInitialized(boolean success, String message) {
        if (statusListener != null) {
            mainHandler.post(() -> statusListener.onWalletInitialized(success, message));
        }
    }
    
    /**
     * Check if wallet is initialized and ready
     */
    public boolean isReady() {
        return isInitialized && wallet != null;
    }
    
    /**
     * Get wallet seed phrase for backup
     */
    public void getSeedPhrase(SeedCallback callback) {
        if (!isInitialized) {
            callback.onError("Wallet not initialized");
            return;
        }
        
        executorService.execute(() -> {
            try {
                String seed = wallet.getSeed();
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
}
