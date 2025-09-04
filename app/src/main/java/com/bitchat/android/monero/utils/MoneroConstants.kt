// MoneroConstants.kt
package com.bitchat.android.monero.utils

/**
 * Constants used throughout the Monero integration
 */
object MoneroConstants {
    
    // Network types
    const val MAINNET = "mainnet"
    const val TESTNET = "testnet"
    const val STAGENET = "stagenet"
    
    // Default Monero nodes
    const val DEFAULT_MAINNET_NODE = "node.moneroworld.com:18089"
    const val DEFAULT_TESTNET_NODE = "testnet.xmr-tw.org:28089"
    
    // Wallet file names
    const val WALLET_FILE_NAME = "bitchat_wallet"
    const val WALLET_KEYS_FILE = "bitchat_wallet.keys"
    
    // Transaction priorities
    const val PRIORITY_LOW = 1
    const val PRIORITY_MEDIUM = 2
    const val PRIORITY_HIGH = 3
    const val PRIORITY_MAX = 4
    
    // Confirmation requirements
    const val CONFIRMATIONS_REQUIRED = 10
    const val AVERAGE_BLOCK_TIME_MS = 120000L // 2 minutes
    
    // Amount limits
    const val MIN_SEND_AMOUNT = "0.000001"
    const val MAX_SEND_AMOUNT = "1000.0"
    
    // UI update intervals
    const val BALANCE_UPDATE_INTERVAL_MS = 30000L // 30 seconds
    const val TRANSACTION_CHECK_INTERVAL_MS = 60000L // 1 minute
    
    // Notification IDs
    const val NOTIFICATION_WALLET_SYNC = 1001
    const val NOTIFICATION_PAYMENT_RECEIVED = 1002
    const val NOTIFICATION_PAYMENT_CONFIRMED = 1003
    
    // SharedPreferences keys
    const val PREF_WALLET_EXISTS = "wallet_exists"
    const val PREF_MONERO_ENABLED = "monero_enabled"
    const val PREF_WALLET_ADDRESS = "wallet_address"
    const val PREF_LAST_BALANCE_UPDATE = "last_balance_update"
    const val PREF_NODE_ADDRESS = "node_address"
    
    // Error messages
    const val ERROR_WALLET_NOT_INITIALIZED = "Wallet not initialized"
    const val ERROR_INSUFFICIENT_BALANCE = "Insufficient balance"
    const val ERROR_INVALID_ADDRESS = "Invalid Monero address"
    const val ERROR_INVALID_AMOUNT = "Invalid amount"
    const val ERROR_NETWORK_ERROR = "Network error"
    const val ERROR_TRANSACTION_FAILED = "Transaction failed"
}
