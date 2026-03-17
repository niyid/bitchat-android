package com.bitchat.android.monero.bluetooth

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.launch
import com.bitchat.android.monero.wallet.WalletSuite
import com.bitchat.android.monero.messaging.MoneroMessageHandler
import com.bitchat.android.monero.mesh.BitchatMoneroTransfer

/**
 * Unified MoneroChatTransferManager
 *
 * Combines:
 * - TxID-based and blob-based flows (legacy + new preferred)
 * - File/message transfer layer via BitchatMoneroTransfer
 * - ChatViewModel + system messages
 * - MoneroMessageHandler integration
 */
class MoneroChatTransferManager(
    private val context: Context,
    private val viewModel: ChatViewModel,
    private val walletSuite: WalletSuite,
    private var messageHandler: MoneroMessageHandler,
    private val sendMessageCallback: (peerID: String, content: String) -> Unit
) {
    companion object {
        private const val TAG = "com.bitchat.MoneroChatTransferManager"
        private const val MONERO_FILE_THRESHOLD = 100 * 1024 // 100KB
    }

    // === Transaction flow modes (from v1) ===
    enum class TransactionFlowMode { TXID_BASED, BLOB_BASED, BOTH }
    private var flowMode: TransactionFlowMode = TransactionFlowMode.TXID_BASED

    // === File transfer layer (from curr) ===
    private val moneroTransfer = BitchatMoneroTransfer(context)
    private var isMoneroTransferInitialized = false

    fun setTransactionFlowMode(mode: TransactionFlowMode) {
        flowMode = mode
        Log.d(TAG, "Transaction flow mode set to: $mode")
    }

    // Add method to update message handler
    fun updateMessageHandler(handler: MoneroMessageHandler) {
        this.messageHandler = handler
        Log.d(TAG, "Message handler updated")
    }

    // ---------------- Initialization ----------------

    fun initialize(meshService: Any, encryptionService: Any) {
        Log.d(TAG, "Initializing MoneroChatTransferManager, current state=$isMoneroTransferInitialized")
        if (isMoneroTransferInitialized) return

        moneroTransfer.initialize(
            meshService = meshService,
            encryptionService = encryptionService,
            viewModel = viewModel,
            callback = object : BitchatMoneroTransfer.MoneroTransferCallback {
                override fun onTransactionReceived(
                    transaction: BitchatMoneroTransfer.MoneroTransaction,
                    fromPeer: String
                ) {
                    handleReceivedTransaction(transaction, fromPeer)
                }
                override fun onTransactionSent(success: Boolean, txHash: String, toPeer: String) {
                    if (success) {
                        viewModel.addSystemMessage("✅ Transaction sent to $toPeer (TxID: $txHash)")
                    } else {
                        viewModel.addSystemMessage("⛔ Failed to send transaction to $toPeer")
                    }
                }
                override fun onTransferError(error: String, txId: String) {
                    viewModel.addSystemMessage("⛔ Transfer error: $error (txId=$txId)")
                }
                override fun onTransferProgress(txId: String, progress: Float) {
                    viewModel.addSystemMessage("📤 File transfer with $txId: $progress%")
                }
                override fun onPeerRequestedTransaction(fromPeer: String, txHash: String) {
                    viewModel.addSystemMessage("ℹ️ Transaction requested by peer: $fromPeer (txHash=$txHash)")
                }
            }
        )
        isMoneroTransferInitialized = true
    }

    // ---------------- Outgoing send ----------------

    fun handleMoneroSend(
        amount: String,
        selectedPrivatePeer: String?,
        canReceiveMonero: Boolean,
        receiverMoneroAddress: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (selectedPrivatePeer == null) {
            onError("No peer selected")
            return
        }
        if (!canReceiveMonero) {
            onError("Peer cannot receive Monero")
            return
        }

        when (flowMode) {
            TransactionFlowMode.TXID_BASED -> sendWithTxIdFlow(amount, selectedPrivatePeer, receiverMoneroAddress, onSuccess, onError)
            TransactionFlowMode.BLOB_BASED -> sendWithBlobFlow(amount, selectedPrivatePeer, receiverMoneroAddress, onSuccess, onError)
            TransactionFlowMode.BOTH -> sendWithTxIdFlow(
                amount, selectedPrivatePeer, receiverMoneroAddress, onSuccess
            ) { error ->
                Log.w(TAG, "TxID flow failed, fallback to blob: $error")
                sendWithBlobFlow(amount, selectedPrivatePeer, receiverMoneroAddress, onSuccess, onError)
            }
        }
    }

    // ---------------- TxID Flow (preferred) ----------------

    private fun sendWithTxIdFlow(
        amount: String,
        selectedPrivatePeer: String,
        receiverMoneroAddress: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (receiverMoneroAddress == null) {
            onError("Receiver address missing")
            return
        }

        val amountXmr = amount.toDoubleOrNull() ?: run {
            onError("Invalid amount: $amount")
            return
        }

        val cachedBalance = walletSuite.getBalanceValue()
        val cachedUnlockedBalance = walletSuite.getUnlockedBalanceValue()
         walletSuite.sendTransaction(receiverMoneroAddress, amountXmr, cachedBalance, cachedUnlockedBalance, object : WalletSuite.TransactionCallback {
            override fun onSuccess(txId: String, amount: Long) {
                val msg = messageHandler.createTransactionIdMessage(
                    txId = txId,
                    amount = WalletSuite.convertAtomicToXmr(amount),
                    toAddress = receiverMoneroAddress
                )
                sendMessageCallback(selectedPrivatePeer, msg)
                onSuccess()
            }
            override fun onError(error: String) = onError(error)
        })
    }

    // ---------------- Blob Flow (legacy + file transfer) ----------------

    private fun sendWithBlobFlow(
        amount: String,
        selectedPrivatePeer: String,
        receiverMoneroAddress: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (receiverMoneroAddress == null) {
            onError("Receiver address missing")
            return
        }
        val amountXmr = amount.toDoubleOrNull() ?: run {
            onError("Invalid amount")
            return
        }

        walletSuite.createTxBlob(receiverMoneroAddress, amount, object : WalletSuite.TxBlobCallback {
            override fun onSuccess(txId: String, base64Blob: String) {
                val transaction = BitchatMoneroTransfer.MoneroTransaction(
                    blob = base64Blob,
                    txHash = txId,
                    fee = null,
                    timestamp = System.currentTimeMillis(),
                    metadata = mapOf("amount" to amount, "destination" to receiverMoneroAddress)
                )
                val sizeBytes = base64Blob.length * 3 / 4
                if (sizeBytes < MONERO_FILE_THRESHOLD) {
                    sendViaMessage(selectedPrivatePeer, base64Blob, txId)
                    onSuccess()
                } else {
                    sendViaFile(transaction, selectedPrivatePeer, onSuccess, onError)
                }
            }
            override fun onError(error: String) = onError(error)
        })
    }

    private fun sendViaMessage(peer: String, base64Blob: String, txId: String) {
        val message = "[XMR_TX_BLOB]$base64Blob"
        viewModel.sendDirectMessage(peer, message)
        viewModel.addSystemMessage("📩 Sent Monero transaction blob (TxID=$txId)")
    }

    private fun sendViaFile(
        transaction: BitchatMoneroTransfer.MoneroTransaction,
        peer: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isMoneroTransferInitialized) {
            onError("File transfer not initialized")
            return
        }
        viewModel.viewModelScope.launch {
            moneroTransfer.sendTransaction(transaction, peer, object : BitchatMoneroTransfer.MoneroTransferCallback {
                override fun onTransactionSent(success: Boolean, txHash: String, toPeer: String) {
                    if (success) onSuccess() else onError("File transfer failed")
                }
                override fun onPeerRequestedTransaction(fromPeer: String, txHash: String) {}
                override fun onTransferError(error: String, txId: String) = onError(error)
                override fun onTransferProgress(txId: String, progress: Float) {}
                override fun onTransactionReceived(transaction: BitchatMoneroTransfer.MoneroTransaction, fromPeer: String) {}
            })
        }
    }

    // ---------------- Incoming Handling ----------------

    private fun handleReceivedTransaction(transaction: BitchatMoneroTransfer.MoneroTransaction, fromPeer: String) {
        moneroTransfer.saveAndSubmitTransaction(transaction, walletSuite,
            onSuccess = { txHash -> viewModel.addSystemMessage("✅ Submitted received tx: $txHash") },
            onError = { err -> viewModel.addSystemMessage("⛔ Failed to handle received tx: $err") })
    }

    fun handleIncomingTransactionId(txIdMessage: MoneroMessageHandler.TransactionIdMessage, onSuccess: () -> Unit, onError: (String) -> Unit) {
        walletSuite.searchAndImportTransaction(txIdMessage.txId, object : WalletSuite.TransactionSearchCallback {
            override fun onTransactionFound(txId: String, amount: Long, confirmations: Long, blockHeight: Long) {
                val msg = messageHandler.createTransactionFoundMessage(txId, WalletSuite.convertAtomicToXmr(amount), confirmations, blockHeight)
                sendMessageCallback(txIdMessage.fromUser, msg)
                onSuccess()
            }
            override fun onTransactionNotFound(txId: String) {
                val msg = messageHandler.createTransactionNotFoundMessage(txId)
                sendMessageCallback(txIdMessage.fromUser, msg)
                onError("Transaction not found yet")
            }
            override fun onError(error: String) = onError(error)
        })
    }

    fun handleIncomingTransactionBlob(payment: MoneroMessageHandler.MoneroPaymentMessage, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val blob = payment.signedTxBlob
        if (blob == null) {
            onError("No transaction blob in payment message")
            return
        }
        walletSuite.importSignedTransactionBlob(blob, object : WalletSuite.TransactionImportCallback {
            override fun onSuccess(txId: String) {
                val msg = messageHandler.createPaymentStatusMessage(txId, "Imported and relayed")
                sendMessageCallback(payment.fromUser, msg)
                onSuccess()
            }
            override fun onError(error: String) = onError(error)
        })
    }

    // ---------------- Search ----------------

    fun searchForMissingTransaction(txId: String, onComplete: (found: Boolean) -> Unit) {
        walletSuite.searchForMissingTransaction(txId, object : WalletSuite.TransactionSearchCallback {
            override fun onTransactionFound(txId: String, amount: Long, confirmations: Long, blockHeight: Long) {
                viewModel.addSystemMessage("✅ Found transaction: ${WalletSuite.convertAtomicToXmr(amount)} XMR ($confirmations confirmations)")
                onComplete(true)
            }
            override fun onTransactionNotFound(txId: String) {
                viewModel.addSystemMessage("⏳ Transaction not found yet, will retry")
                onComplete(false)
            }
            override fun onError(error: String) {
                viewModel.addSystemMessage("⛔ Search error: $error")
                onComplete(false)
            }
        })
    }

    fun requestTransactionSearch(txId: String, peer: String) {
        val msg = messageHandler.createTransactionSearchMessage(txId)
        sendMessageCallback(peer, msg)
    }

    fun handleTransactionSearchRequest(request: MoneroMessageHandler.SearchTransactionRequest, onComplete: () -> Unit) {
        walletSuite.searchForMissingTransaction(request.txId, object : WalletSuite.TransactionSearchCallback {
            override fun onTransactionFound(txId: String, amount: Long, confirmations: Long, blockHeight: Long) {
                val msg = messageHandler.createTransactionFoundMessage(txId, WalletSuite.convertAtomicToXmr(amount), confirmations, blockHeight)
                sendMessageCallback(request.fromUser, msg)
                onComplete()
            }
            override fun onTransactionNotFound(txId: String) {
                val msg = messageHandler.createTransactionNotFoundMessage(txId)
                sendMessageCallback(request.fromUser, msg)
                onComplete()
            }
            override fun onError(error: String) {
                Log.e(TAG, "Search error for ${request.txId}: $error")
                onComplete()
            }
        })
    }
}
