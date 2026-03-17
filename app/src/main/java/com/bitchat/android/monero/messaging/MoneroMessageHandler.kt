package com.bitchat.android.monero.messaging

import android.util.Log
import com.bitchat.android.model.BitchatMessage
import org.json.JSONObject

private const val TAG = "MoneroMessageHandler"

/**
 * Unified MoneroMessageHandler
 * Supports:
 * - Legacy blob-based flow ([PREFIX] + raw JSON)
 * - JSON type-based flow {"type":"monero_*", ...}
 */
class MoneroMessageHandler(
    private val onPaymentReceived: (com.bitchat.android.monero.messaging.MoneroMessageHandler.MoneroPaymentMessage) -> Unit,
    private val onAddressShared: (address: String, fromUser: String) -> Unit,
    private val onPaymentRequested: (MoneroPaymentRequest) -> Unit,
    private val onPaymentStatusUpdated: (txId: String, status: String) -> Unit,
    private val onTransactionIdReceived: (TransactionIdMessage) -> Unit,
    private val onTransactionSearchRequested: (SearchTransactionRequest) -> Unit
) {

    interface MoneroMessageListener {
        fun onPaymentReceived(payment: com.bitchat.android.monero.messaging.MoneroMessageHandler.MoneroPaymentMessage)
        fun onAddressShared(address: String, fromUser: String)
        fun onPaymentRequested(request: MoneroPaymentRequest)
        fun onPaymentStatusUpdated(txId: String, status: String)
        fun onTransactionIdReceived(txIdMessage: TransactionIdMessage)
        fun onTransactionSearchRequested(request: SearchTransactionRequest)
    }

    // Prefix-style message types (legacy)
    companion object {
        const val TYPE_PAYMENT = "[MONERO_PAYMENT]"
        const val TYPE_ADDRESS_SHARE = "[MONERO_ADDRESS]"
        const val TYPE_PAYMENT_REQUEST = "[MONERO_REQUEST]"
        const val TYPE_PAYMENT_STATUS = "[MONERO_STATUS]"
        const val TYPE_ADDRESS_REQUEST = "[REQUEST_MONERO_ADDRESS]"

        const val TYPE_TRANSACTION_ID = "[MONERO_TXID]"
        const val TYPE_TRANSACTION_SEARCH = "[MONERO_SEARCH_TX]"
        const val TYPE_TRANSACTION_FOUND = "[MONERO_TX_FOUND]"
        const val TYPE_TRANSACTION_NOT_FOUND = "[MONERO_TX_NOT_FOUND]"

        // JSON type-based message types (new)
        const val TYPE_MONERO_PAYMENT = "monero_payment"
        const val TYPE_MONERO_ADDRESS_SHARE = "monero_address_share"
        const val TYPE_MONERO_PAYMENT_REQUEST = "monero_payment_request"
        const val TYPE_MONERO_PAYMENT_STATUS = "monero_payment_status"

        // Payment statuses
        const val STATUS_PENDING = "pending"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_FAILED = "failed"

        @JvmStatic
        fun isMoneroMessage(content: String): Boolean {
            return try {
                val json = JSONObject(content)
                val type = json.optString("type", "")
                type.startsWith("monero_")
            } catch (e: Exception) {
                false
            }
        }

        @JvmStatic
        fun getDisplayText(content: String): String {
            return try {
                val json = JSONObject(content)
                when (val type = json.optString("type", "")) {
                    TYPE_MONERO_PAYMENT -> {
                        val amount = json.getString("amount")
                        val status = json.optString("status", STATUS_PENDING)
                        "💰 Sent $amount XMR ($status)"
                    }
                    TYPE_MONERO_ADDRESS_SHARE -> "📍 Shared Monero address"
                    TYPE_MONERO_PAYMENT_REQUEST -> {
                        val amount = json.getString("amount")
                        val reason = json.optString("reason", "")
                        "💳 Requested $amount XMR" + if (reason.isNotEmpty()) " - $reason" else ""
                    }
                    TYPE_MONERO_PAYMENT_STATUS -> {
                        val txId = json.getString("tx_id")
                        val status = json.getString("status")
                        "ℹ️ Payment ${txId.take(8)}... $status"
                    }
                    else -> content
                }
            } catch (e: Exception) {
                content
            }
        }
        
        @JvmStatic
        fun initializeMoneroMessageHandler(
            listener: MoneroMessageListener
        ): MoneroMessageHandler {
            return MoneroMessageHandler(
                onPaymentReceived = { payment -> 
                    listener.onPaymentReceived(payment)
                    Unit // Add explicit Unit return to fix "missing return value" error
                },
                onAddressShared = { addr, from -> 
                    listener.onAddressShared(addr, from)
                    Unit
                },
                onPaymentRequested = { request -> 
                    listener.onPaymentRequested(request)
                    Unit
                },
                onPaymentStatusUpdated = { txId, status -> 
                    listener.onPaymentStatusUpdated(txId, status)
                    Unit
                },
                onTransactionIdReceived = { txIdMsg -> 
                    listener.onTransactionIdReceived(txIdMsg)
                    Unit
                },
                onTransactionSearchRequested = { request -> 
                    listener.onTransactionSearchRequested(request)
                    Unit
                }
            ).also {
                Log.i("MoneroWalletHandler", "✅ MoneroMessageHandler initialized")
            }
        }      
    }

    // Data classes
    data class MoneroPaymentMessage(
        val amount: String,
        val fromUser: String,
        val signedTxBlob: String? = null,
        val txId: String? = null,
        val toAddress: String? = null,
        val timestamp: Long,
        val status: String = STATUS_PENDING
    )

    data class MoneroPaymentRequest(
        val amount: String,
        val fromUser: String,
        val reason: String,
        val timestamp: Long
    )

    data class TransactionIdMessage(
        val txId: String,
        val amount: String,
        val fromUser: String,
        val toAddress: String,
        val timestamp: Long,
        val blockHeight: Long = 0
    ) {
        companion object {
            fun createDefault(
                txId: String,
                fromUser: String,
                amount: String = "0",
                toAddress: String = "",
                blockHeight: Long = 0
            ): TransactionIdMessage {
                return TransactionIdMessage(
                    txId = txId,
                    amount = amount,
                    fromUser = fromUser,
                    toAddress = toAddress,
                    timestamp = System.currentTimeMillis(),
                    blockHeight = blockHeight
                )
            }
        }
    }


    data class SearchTransactionRequest(
        val txId: String,
        val query: String,
        val fromUser: String,
        val timestamp: Long
    )

    data class TransactionFoundMessage(
        val txId: String,
        val amount: String,
        val confirmations: Long,
        val blockHeight: Long
    )
    
    /**
     * Main message processor
     */
    fun processMoneroMessage(message: BitchatMessage): Boolean {
        val content = message.content

        // Legacy [PREFIX]-style parsing
        when {
            content.startsWith(TYPE_PAYMENT) -> {
                handlePaymentMessage(content, message.sender)
                return true
            }
            content.startsWith(TYPE_ADDRESS_SHARE) -> {
                handleAddressShare(content, message.sender)
                return true
            }
            content.startsWith(TYPE_PAYMENT_REQUEST) -> {
                handlePaymentRequest(content, message.sender)
                return true
            }
            content.startsWith(TYPE_PAYMENT_STATUS) -> {
                handlePaymentStatus(content)
                return true
            }
            content.startsWith(TYPE_TRANSACTION_ID) -> {
                handleTransactionId(content, message.sender)
                return true
            }
            content.startsWith(TYPE_TRANSACTION_SEARCH) -> {
                handleTransactionSearch(content, message.sender)
                return true
            }
            content.startsWith(TYPE_TRANSACTION_FOUND) -> {
                handleTransactionFound(content)
                return true
            }
            content.startsWith(TYPE_TRANSACTION_NOT_FOUND) -> {
                handleTransactionNotFound(content)
                return true
            }
        }

        // JSON type-based parsing
        return try {
            val json = JSONObject(content)
            when (val type = json.optString("type", "")) {
                TYPE_MONERO_PAYMENT -> {
                    val payment = MoneroPaymentMessage(
                        amount = json.getString("amount"),
                        txId = json.optString("tx_id", null),
                        toAddress = json.optString("recipient_address", null),
                        fromUser = message.sender,
                        signedTxBlob = json.optString("signed_tx", null),
                        timestamp = json.getLong("timestamp"),
                        status = json.optString("status", STATUS_PENDING)
                    )
                    onPaymentReceived(payment)
                    true
                }
                TYPE_MONERO_ADDRESS_SHARE -> {
                    val address = json.getString("address")
                    onAddressShared(address, message.sender)
                    true
                }
                TYPE_MONERO_PAYMENT_REQUEST -> {
                    val request = MoneroPaymentRequest(
                        amount = json.getString("amount"),
                        reason = json.optString("reason", ""),
                        timestamp = json.getLong("timestamp"),
                        fromUser = message.sender
                    )
                    onPaymentRequested(request)
                    true
                }
                TYPE_MONERO_PAYMENT_STATUS -> {
                    val txId = json.getString("tx_id")
                    val status = json.getString("status")
                    onPaymentStatusUpdated(txId, status)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    // Legacy handlers
    private fun handlePaymentMessage(content: String, sender: String) {
        try {
            val json = JSONObject(content.substring(TYPE_PAYMENT.length))
            val payment = MoneroPaymentMessage(
                amount = json.getString("amount"),
                fromUser = sender,
                signedTxBlob = json.getString("signed_tx"),
                timestamp = json.getLong("timestamp")
            )
            onPaymentReceived(payment)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse payment message: ${e.message}")
        }
    }

    private fun handleAddressShare(content: String, sender: String) {
        try {
            val json = JSONObject(content.substring(TYPE_ADDRESS_SHARE.length))
            val address = json.getString("address")
            onAddressShared(address, sender)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse address share: ${e.message}")
        }
    }

    private fun handlePaymentRequest(content: String, sender: String) {
        try {
            val json = JSONObject(content.substring(TYPE_PAYMENT_REQUEST.length))
            val request = MoneroPaymentRequest(
                amount = json.getString("amount"),
                fromUser = sender,
                reason = json.optString("reason", ""),
                timestamp = json.getLong("timestamp")
            )
            onPaymentRequested(request)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse payment request: ${e.message}")
        }
    }

    private fun handlePaymentStatus(content: String) {
        try {
            val json = JSONObject(content.substring(TYPE_PAYMENT_STATUS.length))
            val txId = json.getString("tx_id")
            val status = json.getString("status")
            onPaymentStatusUpdated(txId, status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse payment status: ${e.message}")
        }
    }

    private fun handleTransactionId(content: String, sender: String) {
        try {
            val json = JSONObject(content.substring(TYPE_TRANSACTION_ID.length))
            val txIdMessage = TransactionIdMessage(
                txId = json.getString("tx_id"),
                amount = json.getString("amount"),
                fromUser = sender,
                toAddress = json.getString("to_address"),
                timestamp = json.getLong("timestamp"),
                blockHeight = json.optLong("block_height", 0)
            )
            onTransactionIdReceived(txIdMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transaction ID message: ${e.message}")
        }
    }

    private fun handleTransactionSearch(content: String, sender: String) {
        try {
            val json = JSONObject(content.substring(TYPE_TRANSACTION_SEARCH.length))
            val searchRequest = SearchTransactionRequest(
                txId = json.getString("tx_id"),
                query = content,
                fromUser = sender,
                timestamp = json.getLong("timestamp")
            )
            onTransactionSearchRequested(searchRequest)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transaction search request: ${e.message}")
        }
    }

    private fun handleTransactionFound(content: String) {
        try {
            val json = JSONObject(content.substring(TYPE_TRANSACTION_FOUND.length))
            val txId = json.getString("tx_id")
            val status = "Found: ${json.getInt("confirmations")} confirmations"
            onPaymentStatusUpdated(txId, status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transaction found message: ${e.message}")
        }
    }

    private fun handleTransactionNotFound(content: String) {
        try {
            val json = JSONObject(content.substring(TYPE_TRANSACTION_NOT_FOUND.length))
            val txId = json.getString("tx_id")
            onPaymentStatusUpdated(txId, "Not found")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transaction not found message: ${e.message}")
        }
    }

    // Builders (both legacy + JSON)
    fun createPaymentMessage(amount: String, signedTxBlob: String): String {
        val json = JSONObject().apply {
            put("amount", amount)
            put("signed_tx", signedTxBlob)
            put("timestamp", System.currentTimeMillis())
        }
        return "$TYPE_PAYMENT$json"
    }

    fun createAddressShareMessage(address: String): String {
        val json = JSONObject().apply {
            put("address", address)
            put("timestamp", System.currentTimeMillis())
        }
        return "$TYPE_ADDRESS_SHARE$json"
    }

    fun createPaymentRequestMessage(amount: String, reason: String = ""): String {
        val json = JSONObject().apply {
            put("amount", amount)
            put("reason", reason)
            put("timestamp", System.currentTimeMillis())
        }
        return "$TYPE_PAYMENT_REQUEST$json"
    }

    fun createPaymentStatusMessage(txId: String, status: String): String {
        val json = JSONObject().apply {
            put("tx_id", txId)
            put("status", status)
            put("timestamp", System.currentTimeMillis())
        }
        return "$TYPE_PAYMENT_STATUS$json"
    }

    fun createTransactionIdMessage(
        txId: String,
        amount: String,
        toAddress: String,
        blockHeight: Long = 0
    ): String {
        val json = JSONObject().apply {
            put("tx_id", txId)
            put("amount", amount)
            put("to_address", toAddress)
            put("timestamp", System.currentTimeMillis())
            put("block_height", blockHeight)
        }
        return "$TYPE_TRANSACTION_ID$json"
    }

    fun createTransactionSearchMessage(txId: String): String {
        val json = JSONObject().apply {
            put("tx_id", txId)
            put("timestamp", System.currentTimeMillis())
        }
        return "$TYPE_TRANSACTION_SEARCH$json"
    }

    fun createTransactionFoundMessage(
        txId: String,
        amount: String,
        confirmations: Long,
        blockHeight: Long
    ): String {
        val json = JSONObject().apply {
            put("tx_id", txId)
            put("amount", amount)
            put("confirmations", confirmations)
            put("block_height", blockHeight)
            put("timestamp", System.currentTimeMillis())
        }
        return "$TYPE_TRANSACTION_FOUND$json"
    }

    fun createTransactionNotFoundMessage(txId: String): String {
        val json = JSONObject().apply {
            put("tx_id", txId)
            put("timestamp", System.currentTimeMillis())
        }
        return "$TYPE_TRANSACTION_NOT_FOUND$json"
    }
}

