// MoneroConstants.java
package com.bitchat.android.monero.utils;

/**
 * Constants used throughout the Monero integration
 */
public final class MoneroConstants {
    
    // Network types
    public static final String MAINNET = "mainnet";
    public static final String TESTNET = "testnet";
    public static final String STAGENET = "stagenet";
    
    // Default Monero nodes
    public static final String DEFAULT_MAINNET_NODE = "node.moneroworld.com:18089";
    public static final String DEFAULT_TESTNET_NODE = "testnet.xmr-tw.org:28089";
    
    // Wallet file names
    public static final String WALLET_FILE_NAME = "bitchat_wallet";
    public static final String WALLET_KEYS_FILE = "bitchat_wallet.keys";
    
    // Transaction priorities
    public static final int PRIORITY_LOW = 1;
    public static final int PRIORITY_MEDIUM = 2;
    public static final int PRIORITY_HIGH = 3;
    public static final int PRIORITY_MAX = 4;
    
    // Confirmation requirements
    public static final int CONFIRMATIONS_REQUIRED = 10;
    public static final long AVERAGE_BLOCK_TIME_MS = 120000L; // 2 minutes
    
    // Amount limits
    public static final String MIN_SEND_AMOUNT = "0.000001";
    public static final String MAX_SEND_AMOUNT = "1000.0";
    
    // UI update intervals
    public static final long BALANCE_UPDATE_INTERVAL_MS = 30000L; // 30 seconds
    public static final long TRANSACTION_CHECK_INTERVAL_MS = 60000L; // 1 minute
    
    // Notification IDs
    public static final int NOTIFICATION_WALLET_SYNC = 1001;
    public static final int NOTIFICATION_PAYMENT_RECEIVED = 1002;
    public static final int NOTIFICATION_PAYMENT_CONFIRMED = 1003;
    
    // SharedPreferences keys
    public static final String PREF_WALLET_EXISTS = "wallet_exists";
    public static final String PREF_MONERO_ENABLED = "monero_enabled";
    public static final String PREF_WALLET_ADDRESS = "wallet_address";
    public static final String PREF_LAST_BALANCE_UPDATE = "last_balance_update";
    public static final String PREF_NODE_ADDRESS = "node_address";
    
    // Error messages
    public static final String ERROR_WALLET_NOT_INITIALIZED = "Wallet not initialized";
    public static final String ERROR_INSUFFICIENT_BALANCE = "Insufficient balance";
    public static final String ERROR_INVALID_ADDRESS = "Invalid Monero address";
    public static final String ERROR_INVALID_AMOUNT = "Invalid amount";
    public static final String ERROR_NETWORK_ERROR = "Network error";
    public static final String ERROR_TRANSACTION_FAILED = "Transaction failed";
    
    // Private constructor to prevent instantiation
    private MoneroConstants() {
        throw new AssertionError("Cannot instantiate utility class");
    }
}
