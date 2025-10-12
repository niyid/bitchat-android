package com.bitchat.android.monero.wallet;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.m2049r.xmrwallet.data.Node;
import com.m2049r.xmrwallet.data.TxData;
import com.m2049r.xmrwallet.model.PendingTransaction;
import com.m2049r.xmrwallet.model.Wallet;
import com.m2049r.xmrwallet.model.WalletListener;
import com.m2049r.xmrwallet.model.WalletManager;
import com.m2049r.xmrwallet.model.TransactionHistory;
import com.m2049r.xmrwallet.model.TransactionInfo;
import com.m2049r.xmrwallet.util.Helper;
import com.m2049r.xmrwallet.model.NetworkType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import java.net.HttpURLConnection;
import java.net.URL;

public class WalletSuite {
    private static final String TAG = "WalletSuite";
    private static final String PROPERTIES_FILE = "wallet.properties";

    private static volatile boolean nativeOk = false;
    private static volatile boolean nativeChecked = false;
    private static volatile WalletSuite instance;
    
    // Sync configuration - adapted for slow networks
    private static final long SYNC_TIMEOUT_MS = 7200000; // 120 minutes (2 hours for full rescan)
    private static final long PERIODIC_SYNC_INTERVAL_MS = 600000; // 10 minutes between sync attempts
    private static final long RESCAN_PROGRESS_CHECK_INTERVAL_MS = 30000; // 30 seconds
    private static final long RESCAN_COOLDOWN_MS = 600000; // 10 minutes
    
    // Wallet state machine
    private enum WalletState {
        IDLE,           // Not syncing, ready for operations
        SYNCING,        // Normal sync in progress
        RESCANNING,     // Full rescan in progress - BLOCKS ALL OTHER OPERATIONS
        CLOSING         // Shutdown in progress
    }
    
    private final AtomicReference<WalletState> currentState = new AtomicReference<>(WalletState.IDLE);
    private final AtomicLong lastSyncStartTime = new AtomicLong(0);
    private final AtomicLong lastRescanTime = new AtomicLong(0);
    private final AtomicLong cachedDaemonHeight = new AtomicLong(-1);
    
    private volatile Wallet wallet;
    private final WalletManager walletManager;
    private final Context context;
    private final ExecutorService syncExecutor;
    private final ExecutorService executorService;
    private final ScheduledExecutorService periodicSyncScheduler;
    private final Handler mainHandler;
    private volatile boolean isInitialized = false;
    
    private volatile String walletAddress;
    private volatile String currentWalletPath;
    private final AtomicLong balance = new AtomicLong(0L);
    private final AtomicLong unlockedBalance = new AtomicLong(0L);
    
    private volatile WalletStatusListener statusListener;
    private volatile TransactionListener transactionListener;
    private volatile DaemonConfigCallback daemonConfigCallback;
    
    private ScheduledFuture<?> periodicSyncTask;
    private ScheduledFuture<?> currentSyncTimeout;
    
    private RescanCallback rescanCallback = null;
    
    private final Object walletLock = new Object();
    
    private volatile RescanBalanceCallback rescanBalanceCallback;
    
    private volatile boolean transactionInProgress = false;
    private final Object transactionLock = new Object();
    private long transactionStartTime = 0;
    
    
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
    
    public interface TransactionHistoryCallback {
        void onSuccess(List<TransactionInfo> transactions);
        void onError(String error);
    }    
    
    public interface TransactionCallback {
        void onSuccess(String txId, long amount);
        void onError(String error);
    }
    
    public interface RescanCallback {
        void onComplete(long newBalance, long newUnlockedBalance);
        void onError(String error);
    }

    public interface TransactionSearchCallback {
        void onTransactionFound(String txId, long amount, long confirmations, long blockHeight);
        void onTransactionNotFound(String txId);
        void onError(String error);
    }
    
    public interface RescanBalanceCallback {
        void onBalanceUpdated(long balance, long unlockedBalance);
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
    
    private WalletSuite(Context context) {
        this.context = context.getApplicationContext();
        this.syncExecutor = Executors.newSingleThreadExecutor();
        this.executorService = Executors.newSingleThreadExecutor();
        this.periodicSyncScheduler = Executors.newSingleThreadScheduledExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.walletManager = WalletManager.getInstance();
        loadConfiguration();
        registerShutdownHandler();
    }
    
    public void setRescanBalanceCallback(RescanBalanceCallback callback) {
        this.rescanBalanceCallback = callback;
    }    
    
    public long getBalanceValue() {
        return balance.get();
    }

    public long getUnlockedBalanceValue() {
        return unlockedBalance.get();
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
                Log.d(TAG, "Shutdown hook triggered");
                closeWalletSync();
            }));
        } catch (Exception e) {
            Log.w(TAG, "Could not register shutdown hook", e);
        }
    }

    private void closeWalletSync() {
        currentState.set(WalletState.CLOSING);
        
        try {
            if (wallet != null && isInitialized) {
                Log.d(TAG, "Closing wallet synchronously");
                
                wallet.setListener(null);
                
                if (currentWalletPath != null) {
                    try {
                        wallet.store(currentWalletPath);
                        Log.d(TAG, "Wallet persisted before close");
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to persist wallet during shutdown", e);
                    }
                }
                
                wallet.close();
                Log.d(TAG, "Wallet closed successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during wallet closure", e);
        } finally {
            wallet = null;
            isInitialized = false;
            currentWalletPath = null;
        }
    }
    
    public void close() {
        Log.i(TAG, "=== SHUTDOWN INITIATED ===");
        
        stopPeriodicSync();
        
        if (currentSyncTimeout != null) {
            currentSyncTimeout.cancel(false);
        }
        
        closeWalletSync();

        periodicSyncScheduler.shutdown();
        syncExecutor.shutdown();
        
        try {
            if (!syncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "Sync executor did not terminate, forcing shutdown");
                syncExecutor.shutdownNow();
            }
            if (!periodicSyncScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                periodicSyncScheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            syncExecutor.shutdownNow();
            periodicSyncScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        Log.i(TAG, "=== SHUTDOWN COMPLETE ===");
    }

    /**
     * Starts the periodic sync scheduler
     * Interval is LONGER than max sync time to prevent overlapping attempts
     */
    private void startPeriodicSync() {
        if (periodicSyncTask != null && !periodicSyncTask.isDone()) {
            Log.d(TAG, "Periodic sync already scheduled");
            return;
        }

        Log.i(TAG, "=== STARTING PERIODIC SYNC (interval: " + (PERIODIC_SYNC_INTERVAL_MS / 60000) + " minutes) ===");
        Log.i(TAG, "Note: Max sync time is " + (SYNC_TIMEOUT_MS / 60000) + " minutes");

        periodicSyncTask = periodicSyncScheduler.scheduleWithFixedDelay(() -> {
            if (!isInitialized) {
                Log.d(TAG, "⏭ Skipping periodic sync - wallet not initialized");
                return;
            }
            
            WalletState state = currentState.get();
            if (state != WalletState.IDLE) {
                Log.d(TAG, "⏭ Skipping periodic sync - state: " + state);
                return;
            }
            
            // Check if previous sync is still running (shouldn't happen with proper intervals)
            long timeSinceLastSync = System.currentTimeMillis() - lastSyncStartTime.get();
            if (timeSinceLastSync < SYNC_TIMEOUT_MS && lastSyncStartTime.get() > 0) {
                Log.w(TAG, "⚠️ Previous sync may still be running (" + (timeSinceLastSync / 1000) + "s ago)");
                return;
            }
            
            Log.i(TAG, "⏰ Periodic sync triggered");
            performSync();
            
        }, PERIODIC_SYNC_INTERVAL_MS, PERIODIC_SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);

        Log.i(TAG, "✓ Periodic sync scheduler started");
    }

    private void stopPeriodicSync() {
        if (periodicSyncTask != null) {
            periodicSyncTask.cancel(false);
            periodicSyncTask = null;
            Log.i(TAG, "✓ Periodic sync scheduler stopped");
        }
    }

    /**
     * Main sync entry point - respects state machine
     * CRITICAL: Only ONE sync can run at a time
     * Sync timeout exists to detect hung operations (network failure, daemon crash, etc.)
     */
    private void performSync() {
        // State check with atomic compare-and-set
        if (!currentState.compareAndSet(WalletState.IDLE, WalletState.SYNCING)) {
            WalletState state = currentState.get();
            Log.w(TAG, "Cannot start sync - current state: " + state);
            return;
        }
        
        lastSyncStartTime.set(System.currentTimeMillis());
        
        Log.i(TAG, "=== SYNC STARTED ===");
        
        // Schedule timeout as safety net for hung operations
        // This is NOT the same as periodic sync - it's a watchdog timer
        mainHandler.post(() -> {
            if (currentSyncTimeout != null) {
                currentSyncTimeout.cancel(false);
            }
            currentSyncTimeout = periodicSyncScheduler.schedule(() -> {
                if (currentState.get() == WalletState.SYNCING) {
                    Log.e(TAG, "🚨 SYNC TIMEOUT - operation hung for " + (SYNC_TIMEOUT_MS / 60000) + " minutes");
                    Log.e(TAG, "This indicates a network or daemon issue, not normal slow sync");
                    completeSyncOperation(false);
                }
            }, SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        });
        
        // Execute sync on dedicated thread
        syncExecutor.execute(this::executeSyncOperation);
    }
    
    public void getTransactionHistory(TransactionHistoryCallback callback) {
        if (callback == null) {
            Log.w(TAG, "getTransactionHistory called with null callback");
            return;
        }
        
        // Check if wallet is available
        if (wallet == null) {
            String error = "Wallet not initialized";
            Log.e(TAG, "getTransactionHistory failed: " + error);
            mainHandler.post(() -> callback.onError(error));
            return;
        }
        
        // Execute on background thread
        syncExecutor.execute(() -> {
            try {
                TransactionHistory history = wallet.getHistory();
                if (history == null) {
                    mainHandler.post(() -> callback.onError("Transaction history unavailable"));
                    return;
                }
                
                int count = history.getCount();
                Log.d(TAG, "Fetching transaction history: " + count + " transactions");
                
                List<TransactionInfo> transactions = new ArrayList<>();
                
                for (int i = 0; i < count; i++) {
                    try {
                        TransactionInfo txInfo = history.getAll().get(i);
                        if (txInfo != null) {
                            // Extract transaction details
                            String txId = txInfo.hash;
                            long amount = txInfo.amount;
                            long timestamp = txInfo.timestamp;
                            boolean isConfirmed = txInfo.isConfirmed();
                            int confirmations = (int) txInfo.confirmations;
                            
                            transactions.add(new TransactionInfo(
                                txId,
                                amount,
                                timestamp,
                                confirmations
                            ));
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error reading transaction " + i + ": " + e.getMessage());
                        // Continue with other transactions
                    }
                }
                
                Log.d(TAG, "Successfully fetched " + transactions.size() + " transactions");
                
                // Post results to main thread
                final List<TransactionInfo> finalTransactions = transactions;
                mainHandler.post(() -> callback.onSuccess(finalTransactions));
                
            } catch (Exception e) {
                Log.e(TAG, "Error getting transaction history", e);
                final String error = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError("Failed to get transaction history: " + error));
            }
        });
    }    

    /**
     * Executes the actual sync operation
     * Runs on syncExecutor thread
     */
    private void executeSyncOperation() {
        try {
            // Validate daemon first
            if (!validateDaemonConnection()) {
                Log.e(TAG, "Daemon validation failed");
                completeSyncOperation(false);
                return;
            }
            
            // Set up listener
            WalletListener syncListener = new WalletListener() {
                @Override
                public void moneySent(String txId, long amount) {
                    Log.d(TAG, "[SYNC] moneySent: " + txId);
                }

                @Override
                public void moneyReceived(String txId, long amount) {
                    Log.d(TAG, "[SYNC] moneyReceived: " + txId + " = " + (amount / 1e12) + " XMR");
                    if (transactionListener != null) {
                        mainHandler.post(() -> transactionListener.onOutputReceived(amount, txId, false));
                    }
                }

                @Override
                public void unconfirmedMoneyReceived(String txId, long amount) {
                    Log.d(TAG, "[SYNC] unconfirmedMoneyReceived: " + txId);
                }

                @Override
                public void newBlock(long height) {
                    // Log every 1000 blocks to avoid spam
                    if (height % 1000 == 0) {
                        Log.d(TAG, "[SYNC] Block: " + height);
                    }
                }

                @Override
                public void updated() {
                    // Called frequently, don't log
                }

                @Override
                public void refreshed() {
                    Log.d(TAG, "[SYNC] Refreshed callback - sync complete");
                    completeSyncOperation(true);
                }
            };

            wallet.setListener(syncListener);
            
            long heightBefore = wallet.getBlockChainHeight();
            long startTime = System.currentTimeMillis();
            
            Log.d(TAG, "Starting wallet.rescanBlockchainAsync() from height " + heightBefore);
            
            // THIS IS THE BLOCKING CALL - will take minutes on slow connections
            wallet.rescanBlockchainAsync();
            
            long duration = System.currentTimeMillis() - startTime;
            long heightAfter = wallet.getBlockChainHeight();
            long blocksProcessed = heightAfter - heightBefore;
            
            Log.i(TAG, "Sync refresh completed:");
            Log.i(TAG, "  Duration: " + (duration / 1000) + "s");
            Log.i(TAG, "  Blocks processed: " + blocksProcessed);
            Log.i(TAG, "  Final height: " + heightAfter);
            
            // Completion handled by refreshed() callback or we complete here if callback didn't fire
            if (currentState.get() == WalletState.SYNCING) {
                completeSyncOperation(true);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "✗ Sync exception", e);
            completeSyncOperation(false);
        }
    }

    /**
     * Completes sync operation and updates state
     * CRITICAL: This is the ONLY place state should transition from SYNCING -> IDLE
     */
    private void completeSyncOperation(boolean success) {
        // Only complete if we're actually syncing
        if (!currentState.compareAndSet(WalletState.SYNCING, WalletState.IDLE)) {
            Log.d(TAG, "Sync already completed or interrupted");
            return;
        }
        
        // Cancel timeout
        if (currentSyncTimeout != null) {
            currentSyncTimeout.cancel(false);
            currentSyncTimeout = null;
        }
        
        long duration = System.currentTimeMillis() - lastSyncStartTime.get();
        Log.i(TAG, "=== SYNC COMPLETED (success=" + success + ", duration=" + (duration / 1000) + "s) ===");
        
        try {
            // Remove listener
            wallet.setListener(null);
            
            // Update balances and state
            long bal = wallet.getBalance();
            long unl = wallet.getUnlockedBalance();
            long walletHeight = wallet.getBlockChainHeight();
            long daemonHeight = getDaemonHeightViaHttp();
            
            balance.set(bal);
            unlockedBalance.set(unl);
            
            double percent = daemonHeight > 0 ? (100.0 * walletHeight / daemonHeight) : 100.0;
            
            Log.i(TAG, "Sync results:");
            Log.i(TAG, "  Wallet height: " + walletHeight);
            Log.i(TAG, "  Daemon height: " + daemonHeight);
            Log.i(TAG, "  Progress: " + String.format("%.2f%%", percent));
            Log.i(TAG, "  Balance: " + (bal / 1e12) + " XMR");
            Log.i(TAG, "  Unlocked: " + (unl / 1e12) + " XMR");
            
            // Notify listeners
            if (statusListener != null) {
                mainHandler.post(() -> {
                    statusListener.onSyncProgress(walletHeight, walletHeight, daemonHeight, percent);
                    statusListener.onBalanceUpdated(bal, unl);
                });
            }
            
            // Check if rescan needed (only after successful sync to daemon height)
            if (success && percent >= 99.0) {
                triggerRescan();
                //checkAndTriggerRescan(walletHeight, daemonHeight);
            }
            
            // ONLY persist if balance is valid (non-zero or we have no transactions)
            if (bal > 0 || getTxCount() == 0) {
                persistWallet();
            } else {
                Log.w(TAG, "⚠️ Skipping persist - suspicious zero balance with transactions");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in sync completion", e);
        }
    }

    private int getTxCount() {
        try {
            TransactionHistory history = wallet.getHistory();
            if (history == null) return 0;
            history.refresh();
            List<TransactionInfo> allTxs = history.getAll();
            return (allTxs != null) ? allTxs.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Triggers a rescan as a result of a Monerujo bug
     * BLOCKS ALL OTHER OPERATIONS until complete
     * 
     */
    private void triggerRescan() {
        // State transition - CRITICAL: Nothing else can run during rescan
        if (!currentState.compareAndSet(WalletState.IDLE, WalletState.RESCANNING)) {
            Log.w(TAG, "Cannot trigger rescan - state: " + currentState.get());
            if (rescanCallback != null) {
                final RescanCallback callback = rescanCallback;
                rescanCallback = null;
                mainHandler.post(() -> callback.onError("Cannot trigger rescan - wallet busy"));
            }
            return;
        }
        
        lastRescanTime.set(System.currentTimeMillis());
        stopPeriodicSync();
        
        syncExecutor.execute(() -> {
            try {
                String walletPath = wallet.getPath();
                
                // Step 1: SKIP storing wallet state (it's corrupted!)
                Log.d(TAG, "[1/5] Skipping store (cache is corrupted)");
               
                // Step 2: Close wallet cleanly
                Log.d(TAG, "[2/5] Closing wallet...");
                wallet.setListener(null);
                try {
                    // CRITICAL: Persist fresh state before destructive rescan
                    if (wallet != null && !currentWalletPath.isEmpty()) {
                        wallet.store(currentWalletPath);
                        Log.i(TAG, " Wallet persisted before rescan");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to persist before rescan", e);
                    // Continue anyway
                }                
                wallet.close();
                wallet = null;
                Thread.sleep(1000); // Give JNI time to clean up
                
                // Step 3: Reopen wallet (creates fresh cache)
                Log.d(TAG, "[3/5] Reopening wallet...");
                wallet = walletManager.openWallet(walletPath);
                if (wallet == null) {
                    Log.e(TAG, "✗ CRITICAL: Failed to reopen wallet - ABORTING");
                    currentState.set(WalletState.IDLE);
                    startPeriodicSync();
                    
                    // Notify callback of failure
                    if (rescanCallback != null) {
                        final RescanCallback callback = rescanCallback;
                        rescanCallback = null;
                        mainHandler.post(() -> callback.onError("Failed to reopen wallet"));
                    }
                    return;
                }
                Log.i(TAG, "✓ Wallet reopened with fresh cache");
                
                // Step 6: Reconnect to daemon
                Log.d(TAG, "[4/5] Reconnecting to daemon...");
                try {
                    Node node = walletManager.createNodeFromConfig();
                    long handle = wallet.initJ(
                        node.getAddress(), 0,
                        node.getUsername(), node.getPassword(),
                        node.isSsl(), false, ""
                    );
                    if (handle > 0) {
                        Log.d(TAG, "✓ Daemon connected (handle: " + handle + ")");
                    } else {
                        Log.w(TAG, "Daemon init returned 0 (may still work)");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Daemon reconnection error (continuing)", e);
                }
                
                // Step 7: Set restore height to 1 and start rescan
                Log.d(TAG, "[5/5] Initializing wallet cache and starting rescan...");
                
                // CRITICAL: We need to do a forward sync FIRST to initialize the cache
                Log.d(TAG, "Step 5a: Performing initial forward sync to initialize cache...");
                
                try {
                    // Do a quick forward refresh to current height to initialize cache structure
                    wallet.refreshAsync();
                    Thread.sleep(3000); // Give it time to initialize
                    
                    long currentHeight = wallet.getBlockChainHeight();
                    Log.i(TAG, "✓ Cache initialized at height: " + currentHeight);
                } catch (Exception e) {
                    Log.w(TAG, "Initial refresh error (continuing)", e);
                }
                
                // Start progress monitoring (this will handle state transition and callback)
                mainHandler.postDelayed(this::monitorRescanProgress, 5000);
                
            } catch (InterruptedException e) {
                Log.w(TAG, "Rescan interrupted", e);
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
                Thread.currentThread().interrupt();
                
                // Notify callback of interruption
                if (rescanCallback != null) {
                    final RescanCallback callback = rescanCallback;
                    rescanCallback = null;
                    mainHandler.post(() -> callback.onError("Rescan interrupted"));
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ Rescan failed with exception", e);
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
                
                // Notify callback of failure
                if (rescanCallback != null) {
                    final RescanCallback callback = rescanCallback;
                    rescanCallback = null;
                    final String error = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    mainHandler.post(() -> callback.onError("Rescan failed: " + error));
                }
            }
        });
    }    
    private void notifyCallbackSuccess(final RescanCallback callback, final long balance, final long unlocked) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onComplete(balance, unlocked);
            }
        });
    }
    
    private void notifyCallbackError(final RescanCallback callback, final String error) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(error);
            }
        });
    }
    
    public TransactionHistory getHistory() {
        return wallet.getHistory();
    }
    
    // Add method to get balance (thread-safe)
    public long getBalanceAtomic() {
        synchronized (walletLock) {
            if (wallet == null) {
                return 0L;
            }
            return wallet.getBalance();
        }
    }
    
    public long getUnlockedBalanceAtomic() {
        synchronized (walletLock) {
            if (wallet == null) {
                return 0L;
            }
            return wallet.getUnlockedBalance();
        }
    }
    
    /**
     * Monitors rescan progress with visible updates
     * Shows progress every check until complete
     * CRITICAL: This is the ONLY method that should store wallet after rescan
     */
    private void monitorRescanProgress() {
        if (currentState.get() != WalletState.RESCANNING || wallet == null) {
            Log.d(TAG, "@monitorRescanProgress Rescan monitoring stopped - state changed or wallet null");
            return;
        }
        
        try {
            long height = wallet.getBlockChainHeight();
            long daemonHeight = wallet.getRestoreHeight();
            long balance = wallet.getBalance();
            long unlockedBalance = wallet.getUnlockedBalance();
            int txCount = wallet.getHistory().getCount();
            
            double progress = daemonHeight > 0 ? (height * 100.0 / daemonHeight) : 0;
            
            Log.i(TAG, "@monitorRescanProgress === RESCAN PROGRESS ===");
            Log.i(TAG, "@monitorRescanProgress  Height: " + height + " / " + daemonHeight + " (" + String.format("%.1f", progress) + "%)");
            Log.i(TAG, "@monitorRescanProgress  Balance: " + convertAtomicToXmr(balance) + " XMR");
            Log.i(TAG, "@monitorRescanProgress  Unlocked: " + convertAtomicToXmr(unlockedBalance) + " XMR");
            Log.i(TAG, "@monitorRescanProgress  Transactions found: " + txCount);
            
            // NOTIFY any pending transaction of updated balances
            if (rescanBalanceCallback != null) {
                mainHandler.post(() -> rescanBalanceCallback.onBalanceUpdated(balance, unlockedBalance));
            }
            
            // Check if rescan is complete
            if (height >= daemonHeight - 1 || progress >= 99.9) {
                Log.i(TAG, "@monitorRescanProgress ✓✓✓ RESCAN COMPLETE ✓✓✓");
                Log.i(TAG, "@monitorRescanProgress Final state:");
                Log.i(TAG, "@monitorRescanProgress  - Height: " + height);
                Log.i(TAG, "@monitorRescanProgress  - Balance: " + convertAtomicToXmr(balance) + " XMR");
                Log.i(TAG, "@monitorRescanProgress  - Unlocked: " + convertAtomicToXmr(unlockedBalance) + " XMR");
                Log.i(TAG, "@monitorRescanProgress  - Transactions: " + txCount);
                
                // Store wallet state
                try {
                    wallet.store();
                    Log.d(TAG, "@monitorRescanProgress Wallet was already persisted immediately after rescan");
                } catch (Exception e) {
                    Log.w(TAG, "@monitorRescanProgress Failed to store wallet after rescan", e);
                }
                
                // Transition back to IDLE and restart periodic sync
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
                
                // Notify status listener of balance update
                if (statusListener != null) {
                    final long finalBalance = balance;
                    final long finalUnlockedBalance = unlockedBalance;
                    mainHandler.post(() -> statusListener.onBalanceUpdated(finalBalance, finalUnlockedBalance));
                }
                
                // Notify rescan callback of completion
                if (rescanCallback != null) {
                    final RescanCallback callback = rescanCallback;
                    rescanCallback = null;
                    final long finalBalance = balance;
                    final long finalUnlockedBalance = unlockedBalance;
                    mainHandler.post(() -> {
                        Log.d(TAG, "@monitorRescanProgress Invoking rescan callback with balance: " + convertAtomicToXmr(finalUnlockedBalance) + " XMR");
                        callback.onComplete(finalBalance, finalUnlockedBalance);
                    });
                }
                
                // Clear the rescan balance callback
                rescanBalanceCallback = null;
                
                return; // Stop monitoring
            }
            
            // Continue monitoring
            mainHandler.postDelayed(this::monitorRescanProgress, 5000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error monitoring rescan progress", e);
            currentState.set(WalletState.IDLE);
            startPeriodicSync();
            rescanBalanceCallback = null;
            
            // Notify callback of error
            if (rescanCallback != null) {
                final RescanCallback callback = rescanCallback;
                rescanCallback = null;
                final String error = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError("@monitorRescanProgress Rescan monitoring failed: " + error));
            }
        }
    }

    /**
     * Backs up wallet cache before destructive operations
     */
    private void backupWalletCache() {
        try {
            String walletPath = wallet.getPath();
            File cacheFile = new File(walletPath);
            
            if (!cacheFile.exists()) {
                Log.d(TAG, "No cache to backup");
                return;
            }
            
            // Clean up old backups (keep last 3)
            cleanupOldBackups(walletPath);
            
            // Create new backup
            String backupPath = walletPath + ".bak." + System.currentTimeMillis();
            File backupFile = new File(backupPath);
            
            copyFile(cacheFile, backupFile);
            Log.i(TAG, "✓ Cache backed up: " + backupPath);
            
        } catch (Exception e) {
            Log.w(TAG, "Cache backup failed (continuing anyway)", e);
        }
    }
    
    private void cleanupOldBackups(String walletPath) {
        try {
            File walletDir = new File(walletPath).getParentFile();
            String walletName = new File(walletPath).getName();
            
            File[] backups = walletDir.listFiles((dir, name) -> 
                name.startsWith(walletName + ".bak."));
            
            if (backups == null || backups.length <= 3) {
                return;
            }
            
            Arrays.sort(backups, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            
            for (int i = 3; i < backups.length; i++) {
                backups[i].delete();
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Error cleaning up old backups", e);
        }
    }

    private void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.flush();
        }
    }

    /**
     * Persists wallet to disk
     * Safe to call frequently - won't persist during rescan
     */
    private void persistWallet() {
        if (wallet == null || !isInitialized) {
            return;
        }
        
        if (currentState.get() == WalletState.RESCANNING) {
            Log.d(TAG, "Skipping persist - rescan in progress");
            return;
        }
        
        syncExecutor.execute(() -> {
            try {
                String path = wallet.getPath();
                if (path != null && !path.isEmpty()) {
                    boolean stored = wallet.store(path);
                    if (stored) {
                        Log.d(TAG, "✓ Wallet persisted");
                    } else {
                        Log.w(TAG, "Wallet store returned false: " + wallet.getErrorString());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Wallet persist error", e);
            }
        });
    }
    
    private long getDaemonHeightViaHttp() {
        HttpURLConnection conn = null;
        try {
            String daemonAddress = walletManager.getDaemonAddress();
            int daemonPort = walletManager.getDaemonPort();

            if (daemonAddress == null || daemonAddress.isEmpty()) {
                return cachedDaemonHeight.get();
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
                        cachedDaemonHeight.set(height);
                        return height;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting daemon height: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }

        return cachedDaemonHeight.get();
    }
        
    private boolean validateDaemonConnection() {
        try {
            if (wallet == null) {
                Log.w(TAG, "Cannot validate daemon - wallet is null");
                return false;
            }

            long daemonHeight = getDaemonHeightViaHttp();
            
            if (daemonHeight <= 0) {
                Log.w(TAG, "Daemon validation failed: invalid height (" + daemonHeight + ")");
                return false;
            }

            Log.i(TAG, "✓ Daemon connected: height=" + daemonHeight);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "✗ Daemon validation exception", e);
            return false;
        }
    }

    public boolean isSyncing() {
        WalletState state = currentState.get();
        return state == WalletState.SYNCING || state == WalletState.RESCANNING;
    }

    public SyncStatus getSyncStatus() {
        if (!isInitialized || wallet == null) {
            return new SyncStatus(false, 0, 0, 0.0);
        }
        try {
            long walletHeight = wallet.getBlockChainHeight();
            long daemonHeight = getDaemonHeightViaHttp();
            double percent = daemonHeight > 0 ? (100.0 * walletHeight / daemonHeight) : 0.0;
            return new SyncStatus(isSyncing(), walletHeight, daemonHeight, percent);
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
    
    /**
     * Determines the appropriate mixin value based on the current network type.
     * 
     * Monero enforces a fixed ring size of 16 (mixin = 15) across all networks
     * as of recent protocol versions. Ring size = mixin + 1.
     * 
     * @return The mixin value to use for transactions (typically 15)
     */
    private int getMixinForNetwork() {
        NetworkType currentNetwork = walletManager.getNetworkType();
        
        // Current Monero protocol enforces ring size of 16 (mixin 15) across all networks
        final int DEFAULT_MIXIN = 15;
        
        // Log the network type for debugging
        String networkName = "UNKNOWN";
        if (currentNetwork != null) {
            switch (currentNetwork) {
                case NetworkType_Mainnet:
                    networkName = "MAINNET";
                    break;
                case NetworkType_Testnet:
                    networkName = "TESTNET";
                    break;
                case NetworkType_Stagenet:
                    networkName = "STAGENET";
                    break;
            }
        }
        
        Log.d(TAG, "Network type: " + networkName + ", using mixin: " + DEFAULT_MIXIN);
        
        // All networks use the same mixin value in current Monero protocol
        return DEFAULT_MIXIN;
    }

    /**
     * Gets the ring size (mixin + 1) for the current network.
     * 
     * @return The ring size value (typically 16)
     */
    private int getRingSizeForNetwork() {
        return getMixinForNetwork() + 1;
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

        syncExecutor.execute(() -> {
            try {
                Log.i(TAG, "=== WALLET INITIALIZATION STARTED ===");
                String walletName = walletManager.getWalletName();
                Log.d(TAG, "Wallet name: " + walletName);

                // Step 1: Check for wallet backup on SD card
                File sdcardDir = new File(Environment.getExternalStorageDirectory(),
                        "Android/data/com.bitchat.droid/files");
                Log.d(TAG, "Checking SD card directory: " + sdcardDir.getAbsolutePath());
                
                File backupFile = new File(sdcardDir, walletName);
                File backupKeysFile = new File(sdcardDir, walletName + ".keys");
                File backupAddressFile = new File(sdcardDir, walletName + ".address.txt");

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

                // Step 3: Copy wallet from SD card if exists
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
                    Log.d(TAG, "No backup found on SD card");
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
                        walletManager.setDaemonAddress(node.getAddress());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception during daemon init", e);
                    walletManager.setDaemonAddress(walletManager.getDaemonAddress());
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

                // Step 8: Get metadata
                try {
                    walletAddress = wallet.getAddress();
                    Log.i(TAG, "Wallet address: " + walletAddress);
                    Log.d(TAG, "Height=" + wallet.getBlockChainHeight() + 
                          ", Restore=" + wallet.getRestoreHeight());
                } catch (Exception e) {
                    Log.w(TAG, "Metadata fetch failed", e);
                }

                // Step 9: Start syncing
                isInitialized = true;
                performSync();
                startPeriodicSync();

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
        syncExecutor.execute(() -> {
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

                    performSync();
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
        syncExecutor.execute(() -> {
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
        syncExecutor.execute(() -> {
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
        performSync();
    }

    public void createTxBlob(String to, String amount, TxBlobCallback cb) {
        if (!isInitialized || wallet == null) {
            cb.onError("Wallet not ready");
            return;
        }
        syncExecutor.execute(() -> {
            try {
                long atomic = Helper.getAmountFromString(amount);
                Log.i(TAG, "Using mixin value of: " + 11);
                long handle = wallet.createTransaction(to, "", atomic, 11, 
                    PendingTransaction.Priority.Priority_Default.getValue(), 0);
                /*
                if (tx.getStatus() != PendingTransaction.Status.Status_Ok.ordinal()) {
                    mainHandler.post(() -> cb.onError(tx.getErrorString()));
                    return;
                }
                byte[] raw = tx.getSerializedTransaction();
                String b64 = Base64.encodeToString(raw, Base64.NO_WRAP);
                mainHandler.post(() -> cb.onSuccess(tx.getFirstTxId(), b64));
                */
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
        syncExecutor.execute(() -> {
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
    
    public void setDaemonConfigCallback(DaemonConfigCallback callback) {
        this.daemonConfigCallback = callback;
    }

    private void notifyWalletInitialized(boolean ok, String msg) {
        if (statusListener != null)
            mainHandler.post(() -> statusListener.onWalletInitialized(ok, msg));
    }

    public boolean isReady() {
        return isInitialized && wallet != null && 
               wallet.getStatus() == Wallet.Status.Status_Ok.ordinal();
    }

    private boolean isReadyForTransaction() {
        WalletState state = currentState.get();
        return isInitialized && 
               wallet != null && 
               state == WalletState.IDLE &&
               wallet.getStatus() == Wallet.Status.Status_Ok.ordinal();
    }

    /**
     * Gracefully terminates all sync/rescan operations
     * and prevents new ones from starting
     */
    private void haltAllScanOperations() {
        Log.i(TAG, "╔════════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║ HALTING ALL SCAN OPERATIONS - TRANSACTION STARTING             ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════════╝");
        
        long timestamp = System.currentTimeMillis();
        Log.i(TAG, "[HALT] Timestamp: " + timestamp);
        Log.i(TAG, "[HALT] Current state before halt: " + currentState.get());
        Log.i(TAG, "[HALT] Periodic sync task active: " + (periodicSyncTask != null && !periodicSyncTask.isDone()));
        Log.i(TAG, "[HALT] Current sync timeout active: " + (currentSyncTimeout != null && !currentSyncTimeout.isDone()));
        
        // Prevent new syncs from starting
        transactionInProgress = true;
        Log.i(TAG, "[HALT]  Set transactionInProgress = true");
        
        // Stop periodic sync
        if (periodicSyncTask != null && !periodicSyncTask.isDone()) {
            Log.i(TAG, "[HALT] Stopping periodic sync scheduler...");
            stopPeriodicSync();
            Log.i(TAG, "[HALT]  Periodic sync stopped");
        } else {
            Log.i(TAG, "[HALT] ℹ Periodic sync already stopped or null");
        }
        
        // Cancel any pending sync timeout
        if (currentSyncTimeout != null && !currentSyncTimeout.isDone()) {
            Log.i(TAG, "[HALT] Cancelling pending sync timeout...");
            currentSyncTimeout.cancel(false);
            currentSyncTimeout = null;
            Log.i(TAG, "[HALT]  Sync timeout cancelled");
        } else {
            Log.i(TAG, "[HALT] ℹ No sync timeout to cancel");
        }
        
        // If a sync/rescan is currently running, force it to IDLE state
        WalletState state = currentState.get();
        Log.i(TAG, "[HALT] Checking current wallet state: " + state);
        
        if (state == WalletState.SYNCING) {
            Log.i(TAG, "[HALT] ⚠ INTERRUPTING ACTIVE SYNC OPERATION");
            Log.i(TAG, "[HALT] Force transitioning SYNCING -> IDLE");
            currentState.set(WalletState.IDLE);
            Log.i(TAG, "[HALT]  Sync operation forcefully interrupted");
        } else if (state == WalletState.RESCANNING) {
            Log.i(TAG, "[HALT] ⚠ INTERRUPTING ACTIVE RESCAN OPERATION");
            Log.i(TAG, "[HALT] Force transitioning RESCANNING -> IDLE");
            currentState.set(WalletState.IDLE);
            rescanCallback = null;
            Log.i(TAG, "[HALT]  Cleared rescanCallback");
            rescanBalanceCallback = null;
            Log.i(TAG, "[HALT]  Cleared rescanBalanceCallback");
        } else if (state == WalletState.CLOSING) {
            Log.w(TAG, "[HALT] ⚠ Wallet is in CLOSING state during transaction - unexpected!");
        } else {
            Log.i(TAG, "[HALT] ℹ Wallet state is already IDLE, no interruption needed");
        }
        
        Log.i(TAG, "[HALT] Final state: " + currentState.get());
        Log.i(TAG, "╔════════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║ ALL SCAN OPERATIONS HALTED - TRANSACTION CAN NOW PROCEED       ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════════╝");
    }

    /**
     * Resumes normal sync operations after transaction completes
     */
    private void resumeNormalSyncOperations() {
        long timestamp = System.currentTimeMillis();
        long elapsedMs = timestamp - transactionStartTime;
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║ RESUMING NORMAL SYNC OPERATIONS - TRANSACTION COMPLETE         ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════════╝");
        Log.i(TAG, "[RESUME] Timestamp: " + timestamp);
        Log.i(TAG, "[RESUME] Transaction elapsed time: " + elapsedMs + "ms (" + (elapsedMs/1000.0) + "s)");
        
        transactionInProgress = false;
        Log.i(TAG, "[RESUME]  Set transactionInProgress = false");
        Log.i(TAG, "[RESUME] Current wallet state: " + currentState.get());
        
        Log.i(TAG, "[RESUME] Starting periodic sync scheduler...");
        startPeriodicSync();
        Log.i(TAG, "[RESUME]  Periodic sync scheduler restarted");
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║ SYNC OPERATIONS RESUMED - NORMAL OPERATION RESTORED            ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════════╝");
    }

    /**
     * Sends a Monero transaction with proper state management and error handling
     * BLOCKS ALL OTHER OPERATIONS until complete
     * 
     * Replace the existing sendTransaction() method in WalletSuite.java with this version
     * 
     * @param destinationAddress The recipient's Monero address
     * @param amountXmr The amount to send in XMR (will be converted to atomic units)
     * @param cachedBalance The cached total balance (atomic units)
     * @param cachedUnlockedBalance The cached unlocked balance (atomic units)
     * @param callback Callback for success/failure notification
     */
    public void sendTransaction(
        String destinationAddress, 
        double amountXmr, 
        long cachedBalance,
        long cachedUnlockedBalance,
        TransactionCallback callback
    ) {
        transactionStartTime = System.currentTimeMillis();
        
        Log.i(TAG, "╔═══════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║ SEND TRANSACTION REQUEST RECEIVED                         ║");
        Log.i(TAG, "╚═══════════════════════════════════════════════════════════╝");
        Log.i(TAG, "[TX_START] Timestamp: " + transactionStartTime);
        Log.i(TAG, "[TX_START] Destination: " + destinationAddress.substring(0, Math.min(20, destinationAddress.length())) + "...");
        Log.i(TAG, "[TX_START] Amount: " + amountXmr + " XMR");
        Log.i(TAG, "[TX_START] Cached Balance: " + convertAtomicToXmr(cachedBalance) + " XMR");
        Log.i(TAG, "[TX_START] Cached Unlocked: " + convertAtomicToXmr(cachedUnlockedBalance) + " XMR");
        
        // Basic validation
        if (!isInitialized || wallet == null) {
            Log.e(TAG, "[TX_START] ✗ Wallet not initialized");
            callback.onError("Wallet not ready");
            return;
        }
        Log.i(TAG, "[TX_START] ✓ Wallet is initialized");
        
        // Prevent recursive transactions
        if (transactionInProgress) {
            Log.e(TAG, "[TX_START] ✗ BLOCKED: Transaction already in progress!");
            callback.onError("Transaction already in progress");
            return;
        }
        Log.i(TAG, "[TX_START] ✓ No existing transaction in progress");
        
        // Convert amount to atomic units
        long amountAtomic = (long) (amountXmr * 1e12);
        
        // Acquire transaction lock and halt all scans atomically
        Log.i(TAG, "[TX_START] Acquiring transactionLock...");
        synchronized (transactionLock) {
            Log.i(TAG, "[TX_START] ✓ transactionLock acquired");
            
            // Check wallet state under lock
            WalletState state = currentState.get();
            Log.i(TAG, "[TX_START] Current wallet state: " + state);
            
            if (state != WalletState.IDLE) {
                String error = "Cannot send transaction - wallet is " + state + 
                              ". Please wait for current operation to complete.";
                Log.e(TAG, "[TX_START] ✗ BLOCKED: Wallet not in IDLE state");
                callback.onError(error);
                return;
            }
            
            // Verify wallet status
            int status = wallet.getStatus();
            if (status != Wallet.Status.Status_Ok.ordinal()) {
                String statusName = (status < Wallet.Status.values().length)
                        ? Wallet.Status.values()[status].name() : "UNKNOWN";
                String error = "Wallet not in OK state: " + statusName;
                Log.e(TAG, "[TX_START] ✗ Wallet status: " + statusName);
                callback.onError(error);
                return;
            }
            Log.i(TAG, "[TX_START] ✓ Wallet status is OK");
            
            // Halt all scanning operations
            haltAllScanOperations();
            
            // Transition state to SYNCING (used as "busy" flag for transactions)
            if (!currentState.compareAndSet(WalletState.IDLE, WalletState.SYNCING)) {
                String error = "Failed to acquire wallet lock for transaction";
                Log.e(TAG, "[TX_START] ✗ State transition failed");
                resumeNormalSyncOperations();
                callback.onError(error);
                return;
            }
            Log.i(TAG, "[TX_START] ✓ State transition successful: IDLE -> SYNCING");
        }
        
        // Schedule transaction timeout (safety net)
        final ScheduledFuture<?>[] timeoutFuture = new ScheduledFuture<?>[1];
        timeoutFuture[0] = periodicSyncScheduler.schedule(() -> {
            if (currentState.get() == WalletState.SYNCING && transactionInProgress) {
                Log.e(TAG, "🚨 TRANSACTION TIMEOUT - 120 seconds elapsed");
                currentState.set(WalletState.IDLE);
                resumeNormalSyncOperations();
                mainHandler.post(() -> callback.onError("Transaction timeout - operation took too long"));
            }
        }, 120, TimeUnit.SECONDS);
        
        // Execute transaction on background thread
        syncExecutor.execute(() -> {
            PendingTransaction pendingTx = null;
            
            try {
                Log.i(TAG, "╔═══════════════════════════════════════════════════════════╗");
                Log.i(TAG, "║ TRANSACTION EXECUTION STARTED                             ║");
                Log.i(TAG, "╚═══════════════════════════════════════════════════════════╝");
                Log.i(TAG, "[TX_EXEC] Thread: " + Thread.currentThread().getName());
                
                // Step 1: Validate balance using cached values
                Log.i(TAG, "[TX_EXEC] [1/3] VALIDATING BALANCE");
                long unlockedBalance = cachedUnlockedBalance;
                long totalBalance = cachedBalance;
                Log.i(TAG, "[TX_EXEC] Cached unlocked: " + convertAtomicToXmr(unlockedBalance) + " XMR");
                Log.i(TAG, "[TX_EXEC] Cached total: " + convertAtomicToXmr(totalBalance) + " XMR");
                
                // Calculate required amount with fee buffer
                long requiredAtomic = amountAtomic + 10000000000L; // +0.01 XMR fee buffer
                Log.i(TAG, "[TX_EXEC] Amount to send: " + convertAtomicToXmr(amountAtomic) + " XMR");
                Log.i(TAG, "[TX_EXEC] Required (with buffer): " + convertAtomicToXmr(requiredAtomic) + " XMR");
                
                if (unlockedBalance < requiredAtomic) {
                    String error = "Insufficient unlocked balance: " + 
                                 convertAtomicToXmr(unlockedBalance) + " XMR (need " +
                                 convertAtomicToXmr(requiredAtomic) + " XMR)";
                    Log.e(TAG, "[TX_EXEC] ✗ BALANCE CHECK FAILED");
                    Log.e(TAG, "[TX_EXEC] " + error);
                    mainHandler.post(() -> callback.onError(error));
                    return;
                }
                Log.i(TAG, "[TX_EXEC] ✓ Balance check passed");
                
                // Step 2: Create transaction
                Log.i(TAG, "[TX_EXEC] [2/3] CREATING TRANSACTION");
                Log.i(TAG, "[TX_EXEC] Destination: " + destinationAddress);
                Log.i(TAG, "[TX_EXEC] Amount (atomic): " + amountAtomic);
                Log.i(TAG, "[TX_EXEC] Amount (XMR): " + amountXmr);
                
                // Get correct mixin for network
                int mixin = getMixinForNetwork();
                Log.i(TAG, "[TX_EXEC] Using mixin: " + mixin + " (ring size: " + (mixin + 1) + ")");
                
                // Create transaction using TxData
                TxData txData = new TxData();
                txData.setDestination(destinationAddress);
                txData.setAmount(amountAtomic);
                txData.setMixin(mixin);
                txData.setPriority(PendingTransaction.Priority.Priority_Default);
                
                Log.i(TAG, "[TX_EXEC] Calling wallet.createTransaction()...");
                pendingTx = wallet.createTransaction(txData);
                
                if (pendingTx == null) {
                    String error = "Failed to create transaction: wallet returned null";
                    Log.e(TAG, "[TX_EXEC] ✗ " + error);
                    mainHandler.post(() -> callback.onError(error));
                    return;
                }
                Log.i(TAG, "[TX_EXEC] ✓ Transaction object created");
                
                // Check transaction status
                PendingTransaction.Status txStatus = pendingTx.getStatus();
                Log.i(TAG, "[TX_EXEC] Transaction status: " + txStatus.name());
                
                if (txStatus != PendingTransaction.Status.Status_Ok) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "[TX_EXEC] ✗ Transaction creation failed: " + error);
                    mainHandler.post(() -> callback.onError("Transaction creation failed: " + error));
                    return;
                }
                Log.i(TAG, "[TX_EXEC] ✓ Transaction status is OK");
                
                // Log transaction details
                try {
                    long fee = pendingTx.getFee();
                    long amount = pendingTx.getAmount();
                    long txCount = pendingTx.getTxCount();
                    
                    Log.i(TAG, "[TX_EXEC] Transaction details:");
                    Log.i(TAG, "[TX_EXEC]   Amount: " + convertAtomicToXmr(amount) + " XMR");
                    Log.i(TAG, "[TX_EXEC]   Fee: " + convertAtomicToXmr(fee) + " XMR");
                    Log.i(TAG, "[TX_EXEC]   Total: " + convertAtomicToXmr(amount + fee) + " XMR");
                    Log.i(TAG, "[TX_EXEC]   TX count: " + txCount);
                } catch (Exception e) {
                    Log.w(TAG, "[TX_EXEC] Could not retrieve transaction details: " + e.getMessage());
                }
                
                // Step 3: Commit transaction
                Log.i(TAG, "[TX_EXEC] [3/3] COMMITTING TRANSACTION");
                Log.i(TAG, "[TX_EXEC] Calling pendingTx.commit(\"\", true)...");
                
                boolean committed = pendingTx.commit("", true);
                Log.i(TAG, "[TX_EXEC] commit() returned: " + committed);
                
                if (!committed) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "[TX_EXEC] ✗ Transaction commit failed: " + error);
                    mainHandler.post(() -> callback.onError("Transaction broadcast failed: " + error));
                    return;
                }
                Log.i(TAG, "[TX_EXEC] ✓ Transaction committed successfully");
                
                // IMPORTANT: Transaction is now broadcast but not yet confirmed
                // It may take a few seconds to appear in the mempool
                
                // Get transaction ID
                String txId;
                try {
                    // Call getFirstTxIdJ() directly to avoid IndexOutOfBoundsException
                    txId = pendingTx.getFirstTxIdJ();
                    if (txId == null || txId.isEmpty()) {
                        Log.w(TAG, "[TX_EXEC] ⚠ Transaction ID is null or empty");
                        txId = "UNKNOWN";
                    } else {
                        Log.i(TAG, "[TX_EXEC] ✓ Transaction ID: " + txId);
                        Log.i(TAG, "[TX_EXEC] ℹ Transaction broadcast to network (0 confirmations)");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TX_EXEC] ✗ Failed to retrieve transaction ID", e);
                    txId = "UNKNOWN";
                }
                
                // Get final amount
                long finalAmount;
                try {
                    finalAmount = pendingTx.getAmount();
                } catch (Exception e) {
                    Log.w(TAG, "[TX_EXEC] Could not get final amount, using requested amount");
                    finalAmount = amountAtomic;
                }
                
                // Persist wallet state
                Log.i(TAG, "[TX_EXEC] Persisting wallet...");
                persistWallet();
                Log.i(TAG, "[TX_EXEC] ✓ Wallet persisted");
                
                // Wait briefly for transaction to propagate to mempool
                Log.i(TAG, "[TX_EXEC] Waiting 3 seconds for mempool propagation...");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Verify transaction appears in wallet history
                final String finalTxId = txId;
                if (!finalTxId.equals("UNKNOWN")) {
                    try {
                        TransactionHistory history = wallet.getHistory();
                        if (history != null) {
                            history.refresh();
                            List<TransactionInfo> allTxs = history.getAll();
                            boolean found = false;
                            if (allTxs != null) {
                                for (TransactionInfo info : allTxs) {
                                    if (info.hash.equals(finalTxId)) {
                                        found = true;
                                        Log.i(TAG, "[TX_EXEC] ✓ Transaction verified in wallet history");
                                        Log.i(TAG, "[TX_EXEC]   Confirmations: " + info.confirmations);
                                        Log.i(TAG, "[TX_EXEC]   Block height: " + info.blockheight);
                                        break;
                                    }
                                }
                            }
                            if (!found) {
                                Log.w(TAG, "[TX_EXEC] ⚠ Transaction not yet visible in wallet history");
                                Log.w(TAG, "[TX_EXEC]   This is normal - it may take 10-30 seconds to appear");
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "[TX_EXEC] Could not verify transaction in history: " + e.getMessage());
                    }
                }
                
                // Success callback
                final String finalCallbackTxId = txId;
                final long finalAmountValue = finalAmount;
                mainHandler.post(() -> {
                    Log.i(TAG, "[TX_CALLBACK] Success callback invoked");
                    Log.i(TAG, "[TX_CALLBACK] TxID: " + finalCallbackTxId);
                    Log.i(TAG, "[TX_CALLBACK] Amount: " + convertAtomicToXmr(finalAmountValue) + " XMR");
                    callback.onSuccess(finalCallbackTxId, finalAmountValue);
                });
                
                Log.i(TAG, "╔═══════════════════════════════════════════════════════════╗");
                Log.i(TAG, "║ TRANSACTION EXECUTION SUCCESSFUL                          ║");
                Log.i(TAG, "╚═══════════════════════════════════════════════════════════╝");
                
            } catch (Exception e) {
                Log.e(TAG, "[TX_EXEC] ✗ Transaction exception", e);
                Log.e(TAG, "[TX_EXEC] Exception type: " + e.getClass().getSimpleName());
                Log.e(TAG, "[TX_EXEC] Message: " + e.getMessage());
                e.printStackTrace();
                
                final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError("Transaction failed: " + errorMsg));
                
            } finally {
                // Cancel timeout
                if (timeoutFuture[0] != null) {
                    timeoutFuture[0].cancel(false);
                }
                
                Log.i(TAG, "[TX_FINALLY] ╔═══════════════════════════════════════════════════════╗");
                Log.i(TAG, "[TX_FINALLY] ║ CLEANUP AND STATE RESTORATION                         ║");
                Log.i(TAG, "[TX_FINALLY] ╚═══════════════════════════════════════════════════════╝");
                
                // CRITICAL: Dispose pending transaction to prevent memory leak
                if (pendingTx != null) {
                    try {
                        // Call disposePendingTransaction on wallet, not on pendingTx
                        wallet.disposePendingTransaction();
                        Log.i(TAG, "[TX_FINALLY] ✓ Pending transaction disposed (native memory freed)");
                    } catch (Exception e) {
                        Log.e(TAG, "[TX_FINALLY] ✗ Failed to dispose pending transaction", e);
                    }
                } else {
                    Log.i(TAG, "[TX_FINALLY] ℹ No pending transaction to dispose");
                }
                
                // Release wallet state lock
                Log.i(TAG, "[TX_FINALLY] Transitioning state: SYNCING -> IDLE");
                boolean stateReleased = currentState.compareAndSet(WalletState.SYNCING, WalletState.IDLE);
                Log.i(TAG, "[TX_FINALLY] State release result: " + stateReleased);
                Log.i(TAG, "[TX_FINALLY] Current state: " + currentState.get());
                
                // Resume normal sync operations after brief delay
                Log.i(TAG, "[TX_FINALLY] Scheduling sync operations resume (2000ms delay)");
                mainHandler.postDelayed(() -> {
                    Log.i(TAG, "[TX_RESUME] ╔═══════════════════════════════════════════════════════╗");
                    Log.i(TAG, "[TX_RESUME] ║ RESUMING SYNC OPERATIONS                              ║");
                    Log.i(TAG, "[TX_RESUME] ╚═══════════════════════════════════════════════════════╝");
                    synchronized (transactionLock) {
                        Log.i(TAG, "[TX_RESUME] ✓ transactionLock acquired");
                        resumeNormalSyncOperations();
                    }
                }, 2000);
                
                Log.i(TAG, "[TX_FINALLY] ✓ Finally block complete");
            }
        });
    }

    /**
     * Override forceRescan to respect transaction context
     */
    public void forceRescan(final RescanCallback callback) {
        long timestamp = System.currentTimeMillis();
        Log.i(TAG, "╔════════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║ FORCE RESCAN REQUEST RECEIVED                                 ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════════╝");
        Log.i(TAG, "[RESCAN_REQ] Timestamp: " + timestamp);
        Log.i(TAG, "[RESCAN_REQ] transactionInProgress: " + transactionInProgress);
        Log.i(TAG, "[RESCAN_REQ] Callback provided: " + (callback != null));
        
        // Prevent rescan during transaction
        if (transactionInProgress) {
            Log.w(TAG, "[RESCAN_REQ] âœ— BLOCKED: Cannot rescan during transaction");
            Log.w(TAG, "[RESCAN_REQ] transactionInProgress flag is TRUE");
            if (callback != null) {
                mainHandler.post(() -> {
                    Log.i(TAG, "[RESCAN_REQ] Posting error callback: 'Cannot rescan while transaction in progress'");
                    callback.onError("Cannot rescan while transaction is in progress");
                });
            }
            return;
        }
        Log.i(TAG, "[RESCAN_REQ]  Transaction flag check passed");
        
        Log.d(TAG, "[RESCAN_REQ] Setting rescan callback and delegating to triggerRescan()");
        this.rescanCallback = callback;

        executorService.execute(() -> {
            Log.i(TAG, "[RESCAN_EXEC] Rescan executor thread started: " + Thread.currentThread().getName());
            synchronized (walletLock) {
                Log.i(TAG, "[RESCAN_EXEC]  walletLock acquired");
                try {
                    Log.i(TAG, "[RESCAN_EXEC] Calling triggerRescan()...");
                    triggerRescan();
                    Log.i(TAG, "[RESCAN_EXEC]  triggerRescan() returned");
                } catch (Exception e) {
                    Log.e(TAG, "[RESCAN_EXEC] âœ— triggerRescan() threw exception", e);
                    if (callback != null) {
                        final String error = e.getMessage() != null ? e.getMessage() : "Unknown error";
                        mainHandler.post(() -> {
                            Log.i(TAG, "[RESCAN_EXEC] Posting error callback for exception");
                            callback.onError("Rescan failed: " + error);
                        });
                    }
                }
            }
        });
    }

    public void searchAndImportTransaction(String txId, TransactionSearchCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not ready");
            return;
        }

        syncExecutor.execute(() -> {
            try {
                Log.i(TAG, "=== SEARCH TRANSACTION: " + txId + " ===");

                wallet.refreshAsync();

                TransactionHistory history = wallet.getHistory();
                if (history == null) {
                    mainHandler.post(() -> callback.onError("Transaction history unavailable"));
                    return;
                }

                history.refresh();
                List<TransactionInfo> allTxs = history.getAll();

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
                    Log.i(TAG, "✓ Transaction found");
                    long amount = txInfo.amount;
                    long confirmations = txInfo.confirmations;
                    long blockHeight = txInfo.blockheight;

                    if (statusListener != null) {
                        long bal = wallet.getBalance();
                        long ubal = wallet.getUnlockedBalance();
                        mainHandler.post(() -> statusListener.onBalanceUpdated(bal, ubal));
                    }

                    persistWallet();
                    mainHandler.post(() -> callback.onTransactionFound(txId, amount, confirmations, blockHeight));
                } else {
                    Log.w(TAG, "✗ Transaction not found");
                    mainHandler.post(() -> callback.onTransactionNotFound(txId));
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ Transaction search error", e);
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
        syncExecutor.execute(() -> {
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
                mainHandler.post(() -> {
                    cb.onSuccess(txId);
                    performSync();
                });
            } catch (Exception e) {
                Log.e(TAG, "✗ Import exception", e);
                mainHandler.post(() -> cb.onError("Import failed: " + e.getMessage()));
            }
        });
    }

    public void performManualRescan(long fromHeight, SyncCallback callback) {
        Log.i(TAG, "Manual rescan requested from height: " + fromHeight);
        
        if (currentState.get() == WalletState.RESCANNING) {
            callback.onError("Rescan already in progress");
            return;
        }
        
        triggerRescan();
        callback.onSuccess("Manual rescan initiated from block 1");
    }
    
    public void manualRescanFromHeight(long fromHeight, SyncCallback callback) {
        performManualRescan(fromHeight, callback);
    }
    
    public void importOutputs(File outputsFile) {
        if (outputsFile.exists()) {
            syncExecutor.execute(() -> {
                int success = wallet.importOutputs(outputsFile.getAbsolutePath());
                if (success > -1) {
                    Log.i(TAG, "✓ Outputs imported from " + outputsFile);
                    triggerRescan();
                } else {
                    Log.w(TAG, "⚠️ Failed to import outputs from " + outputsFile);
                }
            });
        }
    }
}
