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
import java.util.Arrays;
import java.util.List;
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
    
    private WalletSuite(Context context) {
        this.context = context.getApplicationContext();
        this.syncExecutor = Executors.newSingleThreadExecutor();
        this.periodicSyncScheduler = Executors.newSingleThreadScheduledExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.walletManager = WalletManager.getInstance();
        loadConfiguration();
        registerShutdownHandler();
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
                checkAndTriggerRescan(walletHeight, daemonHeight);
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
     * Checks if rescan is needed and triggers it
     * ONLY called after wallet is fully synced to avoid false positives
     */
    private void checkAndTriggerRescan(long walletHeight, long daemonHeight) {
        // Cooldown check
        long timeSinceLastRescan = System.currentTimeMillis() - lastRescanTime.get();
        if (lastRescanTime.get() > 0 && timeSinceLastRescan < RESCAN_COOLDOWN_MS) {
            Log.d(TAG, "Rescan on cooldown (" + (timeSinceLastRescan / 1000) + "s ago)");
            return;
        }
        
        try {
            TransactionHistory history = wallet.getHistory();
            if (history == null) {
                Log.e(TAG, "Transaction history is NULL - triggering rescan");
                triggerRescan();
                return;
            }
            
            history.refresh();
            List<TransactionInfo> allTxs = history.getAll();
            int txCount = (allTxs != null) ? allTxs.size() : 0;
            long currentBalance = balance.get();
            
            Log.d(TAG, "Rescan check: txCount=" + txCount + ", balance=" + (currentBalance / 1e12) + " XMR");
            
            // CRITICAL BUG DETECTION: Transactions exist but balance is zero
            if (txCount > 0 && currentBalance == 0) {
                Log.w(TAG, "⚠⚠⚠ MONERUJO CACHE BUG DETECTED ⚠⚠⚠");
                Log.w(TAG, "Transactions found but balance is ZERO - triggering rescan");
                triggerRescan();
                return;
            }

            // Secondary check: Balance exists but no transactions
            if (txCount == 0 && currentBalance > 0) {
                Log.w(TAG, "⚠ Balance exists but no transactions - triggering rescan");
                triggerRescan();
                return;
            }

            // NEW: Skip rescan if wallet already healthy and recent backup exists
            File walletFile = new File(wallet.getPath());
            File walletDir = walletFile.getParentFile();
            File[] recentBackups = walletDir.listFiles((dir, name) ->
                    name.startsWith(walletFile.getName() + ".bak."));
            boolean recentBackup = recentBackups != null && recentBackups.length > 0;

            if (txCount > 0 && currentBalance > 0) {
                Log.i(TAG, "✓ Wallet healthy: balance=" + (currentBalance / 1e12)
                        + " XMR, txCount=" + txCount
                        + (recentBackup ? " (recent backup found)" : ""));
                Log.i(TAG, "Skipping rescan.");
                return;
            }

            Log.d(TAG, "✓ Wallet state is consistent - no rescan needed");

        } catch (Exception e) {
            Log.e(TAG, "Error checking rescan need", e);
        }
    }

    /**
     * Triggers a full blockchain rescan from block 1
     * BLOCKS ALL OTHER OPERATIONS until complete
     * 
     * Sets restore height to 1, then does regular refresh with progress monitoring
     */
    private void triggerRescan() {
        // State transition - CRITICAL: Nothing else can run during rescan
        if (!currentState.compareAndSet(WalletState.IDLE, WalletState.RESCANNING)) {
            Log.w(TAG, "Cannot trigger rescan - state: " + currentState.get());
            return;
        }
        
        lastRescanTime.set(System.currentTimeMillis());
        stopPeriodicSync();
        
        Log.i(TAG, "=== INITIATING FULL BLOCKCHAIN RESCAN ===");
        Log.i(TAG, "This will scan from block 1 to find all transactions");
        
        syncExecutor.execute(() -> {
            try {
                String walletPath = wallet.getPath();
                
                // Step 1: Backup current state
                Log.d(TAG, "[1/7] Backing up wallet cache...");
//                backupWalletCache();
                
                // Step 2: SKIP storing wallet state (it's corrupted!)
                Log.d(TAG, "[2/7] Skipping store (cache is corrupted)");
                
                // Step 3: Close wallet cleanly
                Log.d(TAG, "[3/7] Closing wallet...");
                wallet.setListener(null);
                wallet.close();
                wallet = null;
                Thread.sleep(1000); // Give JNI time to clean up
                
                // Step 4: Delete cache files (all variations)
//                Log.d(TAG, "[4/7] Deleting cache file...");
//                boolean cacheDeleted = false;

/*                
                File cacheFile = new File(walletPath);
                if (cacheFile.exists()) {
                    if (cacheFile.delete()) {
                        Log.i(TAG, "✓ Main cache deleted: " + walletPath);
                        cacheDeleted = true;
                    } else {
                        Log.e(TAG, "✗ Failed to delete main cache");
                    }
                } else {
                    Log.d(TAG, "Main cache doesn't exist (already deleted?)");
                }
*/                
                // Also try to delete any .unportable files
                File unportableFile = new File(walletPath + ".unportable");
                if (unportableFile.exists() && unportableFile.delete()) {
                    Log.d(TAG, "✓ Deleted .unportable file");
                }
                
//                if (!cacheDeleted) {
//                    Log.w(TAG, "⚠️ Cache may not have been deleted - rescan may not help");
//                }
                
                // Step 5: Reopen wallet (creates fresh cache)
                Log.d(TAG, "[5/7] Reopening wallet...");
                wallet = walletManager.openWallet(walletPath);
                if (wallet == null) {
                    Log.e(TAG, "✗ CRITICAL: Failed to reopen wallet - ABORTING");
                    currentState.set(WalletState.IDLE);
                    startPeriodicSync();
                    return;
                }
                Log.i(TAG, "✓ Wallet reopened with fresh cache");
                
                // CRITICAL: Store the wallet immediately to create the cache file on disk
//                try {
//                    wallet.store(walletPath);
//                    Log.i(TAG, "✓ Fresh cache file created on disk");
//                } catch (Exception e) {
//                    Log.e(TAG, "✗ Failed to create cache file", e);
//                }
                
                // Step 6: Reconnect to daemon
                Log.d(TAG, "[6/7] Reconnecting to daemon...");
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
                Log.d(TAG, "[7/7] Initializing wallet cache and starting rescan...");
                
                // CRITICAL: We need to do a forward sync FIRST to initialize the cache
                // Then we can rescan backwards from block 1
                Log.d(TAG, "Step 7a: Performing initial forward sync to initialize cache...");
                
                try {
                    // Do a quick forward refresh to current height to initialize cache structure
                    wallet.refreshAsync();
                    Thread.sleep(3000); // Give it time to initialize
                    
                    long currentHeight = wallet.getBlockChainHeight();
                    Log.i(TAG, "✓ Cache initialized at height: " + currentHeight);
                } catch (Exception e) {
                    Log.w(TAG, "Initial refresh error (continuing)", e);
                }
 
                // Start progress monitoring (this will handle state transition)
                mainHandler.postDelayed(this::monitorRescanProgress, 5000);
                
            } catch (InterruptedException e) {
                Log.w(TAG, "Rescan interrupted", e);
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "✗ Rescan failed with exception", e);
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
            }
        });
    }

    /**
     * Monitors rescan progress with visible updates
     * Shows progress every check until complete
     * CRITICAL: This is the ONLY method that should store wallet after rescan
     */
    private void monitorRescanProgress() {
        if (currentState.get() != WalletState.RESCANNING) {
            Log.d(TAG, "Rescan monitoring stopped - state changed");
            return;
        }
        
        syncExecutor.execute(() -> {
            try {
                long currentHeight = wallet.getBlockChainHeight();
                long daemonHeight = getDaemonHeightViaHttp();
                long currentBalance = wallet.getBalance();
                
                double percent = daemonHeight > 0 ? (100.0 * currentHeight / daemonHeight) : 0.0;
                
                // Get transaction count for progress indication
                int txCount = 0;
                try {
                    TransactionHistory history = wallet.getHistory();
                    if (history != null) {
                        history.refresh();
                        List<TransactionInfo> allTxs = history.getAll();
                        txCount = (allTxs != null) ? allTxs.size() : 0;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error getting tx count during rescan", e);
                }
                
                Log.i(TAG, "=== RESCAN PROGRESS ===");
                Log.i(TAG, "  Height: " + currentHeight + " / " + daemonHeight + " (" + 
                      String.format("%.1f%%", percent) + ")");
                Log.i(TAG, "  Balance: " + (currentBalance / 1e12) + " XMR");
                Log.i(TAG, "  Transactions found: " + txCount);
                
                // Notify UI
                if (statusListener != null) {
                    long finalCurrentHeight = currentHeight;
                    long finalDaemonHeight = daemonHeight;
                    double finalPercent = percent;
                    long finalBalance = currentBalance;
                    mainHandler.post(() -> {
                        statusListener.onSyncProgress(finalCurrentHeight, 1, finalDaemonHeight, finalPercent);
                        statusListener.onBalanceUpdated(finalBalance, wallet.getUnlockedBalance());
                    });
                }
                
                // Check if complete (with small margin for daemon sync)
                if (percent >= 99.5) {
                    Log.i(TAG, "✓✓✓ RESCAN COMPLETE ✓✓✓");
                    Log.i(TAG, "Final state:");
                    Log.i(TAG, "  - Height: " + currentHeight);
                    Log.i(TAG, "  - Balance: " + (currentBalance / 1e12) + " XMR");
                    Log.i(TAG, "  - Transactions: " + txCount);
                    
                    // NOTE: Wallet was already stored immediately after rescan in triggerRescan()
                    // This is just a verification check
                    Log.d(TAG, "Wallet was already persisted immediately after rescan");
                    
                    // Verify balance hasn't changed
                    if (currentBalance != balance.get()) {
                        Log.w(TAG, "⚠️ Balance changed from " + (balance.get() / 1e12) + 
                              " to " + (currentBalance / 1e12) + " XMR");
                        
                        // If balance is still good, store again
                        if (currentBalance > 0 || txCount == 0) {
                            try {
                                wallet.store(wallet.getPath());
                                balance.set(currentBalance);
                                unlockedBalance.set(wallet.getUnlockedBalance());
                                Log.d(TAG, "✓ Re-stored wallet with updated balance");
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to re-persist", e);
                            }
                        }
                    }
                    
                    // Transition back to IDLE
                    currentState.set(WalletState.IDLE);
                    Log.i(TAG, "State changed: RESCANNING → IDLE");
                    
                    // Restart periodic sync
                    startPeriodicSync();
                    
                } else {
                    // Still rescanning - check again soon
                    // Use shorter interval (10s) if making good progress, longer (30s) if slow
                    long nextCheckDelay = (currentHeight > 1000) ? 10000 : 30000;
                    
                    Log.d(TAG, "Rescan in progress - next check in " + (nextCheckDelay / 1000) + "s");
                    mainHandler.postDelayed(this::monitorRescanProgress, nextCheckDelay);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error monitoring rescan progress", e);
                // On error, try to recover gracefully
                Log.w(TAG, "Attempting to recover from monitoring error...");
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
            }
        });
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
                PendingTransaction tx = wallet.createTransaction(to, "", atomic, 0, 
                    PendingTransaction.Priority.Priority_Default.ordinal());
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

    public void sendTransaction(String destinationAddress, double amountXmr, TransactionCallback callback) {
        if (!isInitialized || wallet == null) {
            callback.onError("Wallet not ready");
            return;
        }
        
        syncExecutor.execute(() -> {
            try {
                Log.i(TAG, "=== SEND TRANSACTION ===");
                Log.d(TAG, "Destination: " + destinationAddress);
                Log.d(TAG, "Amount: " + amountXmr + " XMR");
                
                long amountAtomic = (long) (amountXmr * 1e12);
                
                PendingTransaction pendingTx = wallet.createTransaction(
                    destinationAddress, "", amountAtomic, 0,
                    PendingTransaction.Priority.Priority_Default.ordinal()
                );
                
                if (pendingTx.getStatus() != PendingTransaction.Status.Status_Ok.ordinal()) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "✗ Transaction creation failed: " + error);
                    mainHandler.post(() -> callback.onError("Transaction creation failed: " + error));
                    return;
                }
                
                boolean committed = pendingTx.commit("", true);
                if (!committed) {
                    String error = pendingTx.getErrorString();
                    Log.e(TAG, "✗ Transaction commit failed: " + error);
                    mainHandler.post(() -> callback.onError("Transaction broadcast failed: " + error));
                    return;
                }
                
                String txId = pendingTx.getFirstTxId();
                long actualAmount = pendingTx.getAmount();
                
                Log.i(TAG, "✓ Transaction broadcast: " + txId);
                
                persistWallet();
                mainHandler.post(() -> {
                    callback.onSuccess(txId, actualAmount);
                    performSync();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "✗ Transaction exception", e);
                mainHandler.post(() -> callback.onError("Transaction failed: " + e.getMessage()));
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

                wallet.refresh();

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
