package com.bitchat.android.monero.wallet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.math.BigInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import monero.wallet.MoneroWalletFull;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.MoneroTxConfig;
import monero.daemon.model.MoneroNetworkType;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroWalletListener;
import monero.wallet.model.MoneroOutputWallet;

/**
 * Enhanced Monero wallet manager for BitChat
 * Handles wallet creation, transactions, and balance management with proper error handling
 */
public class MoneroWalletManager {
    
    private static final String TAG = "MoneroWalletManager";
    private static final String WALLET_NAME = "bitchat_wallet";
    private static final String WALLET_PASSWORD = "bitchat_secure_pass"; // In production, use proper key derivation
    private static final long SYNC_PERIOD_MS = 5000L;
    
    private static MoneroWalletManager instance;
    private MoneroWalletFull wallet;
    private Context context;
    private ExecutorService executorService;
    private Handler mainHandler;
    private boolean isInitialized = false;
    private boolean isSyncing = false;
    
    // Configuration
    private String daemonUri = "http://localhost:18081"; // Default mainnet daemon
    private String daemonUsername = null;
    private String daemonPassword = null;
    
    // Listeners
    private WalletStatusListener statusListener;
    private TransactionListener transactionListener;
    
    public interface WalletStatusListener {
        void onWalletInitialized(boolean success, String message);
        void onBalanceUpdated(BigInteger balance, BigInteger unlockedBalance);
        void onSyncProgress(long height, long startHeight, long endHeight, double percentDone);
    }
    
    public interface TransactionListener {
        void onTransactionCreated(String txId, BigInteger amount);
        void onTransactionConfirmed(String txId);
        void onTransactionFailed(String txId, String error);
        void onOutputReceived(BigInteger amount, String txHash, boolean isConfirmed);
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
     * Set daemon configuration
     */
    public void setDaemonConfig(String uri, String username, String password) {
        this.daemonUri = uri;
        this.daemonUsername = username;
        this.daemonPassword = password;
    }
    
    /**
     * Initialize the wallet (create or open existing)
     */
    public void initializeWallet() {
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();
                File walletFile = new File(walletPath + ".keys"); // Monero wallets have .keys extension
                
                if (walletFile.exists()) {
                    // Open existing wallet
                    openExistingWallet(walletPath);
                } else {
                    // Create new wallet
                    createNewWallet(walletPath);
                }
                
                if (wallet != null) {
                    setupWalletListeners();
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
    
    /**
     * Initialize wallet from seed phrase
     */
    public void initializeWalletFromSeed(String seedPhrase, Long restoreHeight) {
        executorService.execute(() -> {
            try {
                String walletPath = getWalletPath();
                createWalletFromSeed(walletPath, seedPhrase, restoreHeight);
                
                if (wallet != null) {
                    setupWalletListeners();
                    isInitialized = true;
                    startBackgroundSync();
                    notifyWalletInitialized(true, "Wallet restored from seed successfully");
                } else {
                    notifyWalletInitialized(false, "Failed to restore wallet from seed");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error restoring wallet from seed", e);
                notifyWalletInitialized(false, "Error: " + e.getMessage());
            }
        });
    }
    
    private void createNewWallet(String walletPath) throws Exception {
        Log.d(TAG, "Creating new wallet at: " + walletPath);
        
        MoneroWalletConfig config = new MoneroWalletConfig()
            .setPath(walletPath)
            .setPassword(WALLET_PASSWORD)
            .setNetworkType(MoneroNetworkType.MAINNET)
            .setServerUri(daemonUri)
            .setRestoreHeight(0L);
        
        if (daemonUsername != null) {
            config.setServerUsername(daemonUsername);
        }
        if (daemonPassword != null) {
            config.setServerPassword(daemonPassword);
        }
        
        wallet = MoneroWalletFull.createWallet(config);
        Log.d(TAG, "New wallet created successfully");
    }
    
    private void createWalletFromSeed(String walletPath, String seedPhrase, Long restoreHeight) throws Exception {
        Log.d(TAG, "Creating wallet from seed at: " + walletPath);
        
        MoneroWalletConfig config = new MoneroWalletConfig()
            .setPath(walletPath)
            .setPassword(WALLET_PASSWORD)
            .setNetworkType(MoneroNetworkType.MAINNET)
            .setServerUri(daemonUri)
            .setSeed(seedPhrase)
            .setRestoreHeight(restoreHeight != null ? restoreHeight : 0L);
        
        if (daemonUsername != null) {
            config.setServerUsername(daemonUsername);
        }
        if (daemonPassword != null) {
            config.setServerPassword(daemonPassword);
        }
        
        wallet = MoneroWalletFull.createWallet(config);
        Log.d(TAG, "Wallet created from seed successfully");
    }
    
    private void openExistingWallet(String walletPath) throws Exception {
        Log.d(TAG, "Opening existing wallet at: " + walletPath);
        
        MoneroWalletConfig config = new MoneroWalletConfig()
            .setPath(walletPath)
            .setPassword(WALLET_PASSWORD)
            .setNetworkType(MoneroNetworkType.MAINNET)
            .setServerUri(daemonUri);
        
        if (daemonUsername != null) {
            config.setServerUsername(daemonUsername);
        }
        if (daemonPassword != null) {
            config.setServerPassword(daemonPassword);
        }
        
        wallet = MoneroWalletFull.openWallet(config);
        Log.d(TAG, "Existing wallet opened successfully");
    }
    
    private void setupWalletListeners() {
        if (wallet == null) return;
        
        wallet.addListener(new MoneroWalletListener() {
            @Override
            public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) {
                if (statusListener != null) {
                    mainHandler.post(() -> statusListener.onSyncProgress(height, startHeight, endHeight, percentDone));
                }
            }
            
            @Override
            public void onOutputReceived(MoneroOutputWallet output) {
                BigInteger amount = output.getAmount();
                String txHash = output.getTx().getHash();
                Boolean isConfirmed = output.getTx().isConfirmed();
                
                if (statusListener != null) {
                    mainHandler.post(() -> transactionListener.onOutputReceived(amount, txHash, isConfirmed));
                }
                
                // Update balance when output is received
                updateBalance();
            }
            
            @Override
            public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
                if (statusListener != null) {
                    mainHandler.post(() -> statusListener.onBalanceUpdated(newBalance, newUnlockedBalance));
                }
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
    
    /**
     * Get the wallet's primary address
     */
    public void getAddress(AddressCallback callback) {
        if (!isInitialized || wallet == null) {
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
        if (!isInitialized || wallet == null) {
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
     * Update balance and notify listeners
     */
    private void updateBalance() {
        if (!isInitialized || wallet == null) return;
        
        executorService.execute(() -> {
            try {
                BigInteger balance = wallet.getBalance();
                BigInteger unlockedBalance = wallet.getUnlockedBalance();
                if (statusListener != null) {
                    mainHandler.post(() -> statusListener.onBalanceUpdated(balance, unlockedBalance));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating balance", e);
            }
        });
    }
    
    /**
     * Send Monero to an address using proper transaction configuration
     */
    public void sendMonero(String toAddress, String amount, SendCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }
        
        executorService.execute(() -> {
            try {
                // Convert amount from XMR to atomic units
                BigInteger atomicAmount = convertXmrToAtomic(amount);
                
                // Create transaction configuration
                MoneroTxConfig txConfig = new MoneroTxConfig()
                    .setAccountIndex(0)
                    .setAddress(toAddress)
                    .setAmount(atomicAmount.toString())
                    .setRelay(false); // Create first, then relay after confirmation
                
                // Create transaction
                MoneroTxWallet tx = wallet.createTx(txConfig);
                String txId = tx.getHash();
                BigInteger fee = tx.getFee();
                
                Log.d(TAG, "Transaction created: " + txId + ", Fee: " + convertAtomicToXmr(fee) + " XMR");
                
                // Relay the transaction
                wallet.relayTx(tx);
                
                if (transactionListener != null) {
                    mainHandler.post(() -> transactionListener.onTransactionCreated(txId, atomicAmount));
                }
                
                mainHandler.post(() -> callback.onSuccess(txId, atomicAmount, fee));
                
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
        void onSuccess(String txId, BigInteger amount, BigInteger fee);
        void onError(String error);
    }
    
    /**
     * Convert XMR amount to atomic units (piconeros)
     * 1 XMR = 10^12 piconeros
     */
    private BigInteger convertXmrToAtomic(String xmrAmount) {
        try {
            double xmr = Double.parseDouble(xmrAmount);
            // Use BigInteger arithmetic for precision
            BigInteger multiplier = BigInteger.valueOf(1000000000000L); // 10^12
            BigInteger xmrBig = BigInteger.valueOf((long)(xmr * 1000000000000L));
            return xmrBig;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid amount format: " + xmrAmount);
        }
    }
    
    /**
     * Convert atomic units to XMR with better precision
     */
    public static String convertAtomicToXmr(BigInteger atomic) {
        if (atomic == null) return "0.000000";
        double xmr = atomic.doubleValue() / 1000000000000.0; // 10^12
        return String.format("%.6f", xmr);
    }
    
    /**
     * Perform manual sync
     */
    public void syncWallet(SyncCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }
        
        executorService.execute(() -> {
            try {
                // Sync with progress monitoring
                wallet.sync(new MoneroWalletListener() {
                    @Override
                    public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) {
                        if (statusListener != null) {
                            mainHandler.post(() -> statusListener.onSyncProgress(height, startHeight, endHeight, percentDone));
                        }
                    }
                });
                
                mainHandler.post(() -> callback.onSuccess("Sync completed"));
                updateBalance();
                
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
    
    /**
     * Start background sync
     */
    private void startBackgroundSync() {
        if (wallet == null || isSyncing) return;
        
        executorService.execute(() -> {
            try {
                wallet.startSyncing(SYNC_PERIOD_MS);
                isSyncing = true;
                Log.d(TAG, "Background sync started with period: " + SYNC_PERIOD_MS + "ms");
            } catch (Exception e) {
                Log.e(TAG, "Error starting sync", e);
            }
        });
    }
    
    /**
     * Stop background sync
     */
    public void stopSync() {
        if (wallet != null && isSyncing) {
            try {
                wallet.stopSyncing();
                isSyncing = false;
                Log.d(TAG, "Background sync stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping sync", e);
            }
        }
    }
    
    /**
     * Check if wallet is currently syncing
     */
    public boolean isSyncing() {
        return isSyncing && wallet != null;
    }
    
    /**
     * Save wallet
     */
    public void saveWallet() {
        if (wallet != null) {
            executorService.execute(() -> {
                try {
                    wallet.save();
                    Log.d(TAG, "Wallet saved");
                } catch (Exception e) {
                    Log.e(TAG, "Error saving wallet", e);
                }
            });
        }
    }
    
    /**
     * Close wallet and cleanup
     */
    public void close() {
        executorService.execute(() -> {
            try {
                if (wallet != null) {
                    if (isSyncing) {
                        wallet.stopSyncing();
                        isSyncing = false;
                    }
                    wallet.close(true); // Save before closing
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
        if (!isInitialized || wallet == null) {
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
    
    /**
     * Get sync height information
     */
    public void getSyncInfo(SyncInfoCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not initialized");
            return;
        }
        
        executorService.execute(() -> {
            try {
                long height = wallet.getHeight();
                long daemonHeight = wallet.getDaemonHeight();
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
}
