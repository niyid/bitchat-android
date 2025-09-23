package com.bitchat.android.monero.bluetooth

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File
import com.bitchat.android.monero.wallet.WalletSuite
import com.bitchat.android.monero.mesh.BitchatMoneroTransfer


/**
 * MoneroChatTransferManager
 *
 * Responsible for orchestrating Monero transaction transfers inside chat.
 *
 * Flow:
 * ChatScreen -> handleMoneroSend() -> MoneroChatTransferManager
 * - Uses WalletSuite to create/send/submit transactions
 * - Small blobs go via direct message
 * - Large blobs are saved to .raw files and sent over file transfer
 * - Received transactions are decoded, stored, and submitted to the Monero network
 */
class MoneroChatTransferManager(
    private val context: Context,
    private val viewModel: ChatViewModel
) {
    companion object {
        private const val TAG = "com.bitchat.MoneroChatTransferManager"
        private const val MONERO_FILE_THRESHOLD = 100 * 1024 // 100KB
    }

    private val moneroTransfer = BitchatMoneroTransfer(context)
    private var isMoneroTransferInitialized = false

    /**
     * Initialize file transfer layer.
     */
    fun initialize(meshService: Any, encryptionService: Any) {
        if (isMoneroTransferInitialized) {
            Log.w(TAG, "Already initialized, skipping.")
            return
        }

        Log.i(TAG, "Initializing MoneroChatTransferManager...")
        moneroTransfer.initialize(
            meshService = meshService,
            encryptionService = encryptionService,
            viewModel = viewModel,
            callback = object : BitchatMoneroTransfer.MoneroTransferCallback {
                override fun onTransactionReceived(
                    transaction: BitchatMoneroTransfer.MoneroTransaction,
                    fromPeer: String
                ) {
                    Log.d(TAG, "Transaction received from $fromPeer, txId=${transaction.txHash}")
                    handleReceivedTransaction(transaction, fromPeer)
                }

                override fun onTransactionSent(success: Boolean, txHash: String, toPeer: String) {
                    Log.d(TAG, "Transaction sent callback: success=$success, txHash=$txHash, peer=$toPeer")
                    if (success) {
                        viewModel.addSystemMessage("✅ Transaction sent to $toPeer (TxID: $txHash)")
                    } else {
                        viewModel.addSystemMessage("❌ Failed to send transaction to $toPeer")
                    }
                }

                override fun onTransferError(error: String, txId: String) {
                    Log.e(TAG, "Transfer error with peer=$txId: $error")
                    viewModel.addSystemMessage("❌ Transfer error: $error (txId=$txId)")
                }

                override fun onTransferProgress(txId: String, progress: Float) {
                    Log.d(TAG, "File transfer progress with $txId: $progress%")
                    viewModel.addSystemMessage("📤 File transfer with $txId: $progress%")
                }
 
                override fun onPeerRequestedTransaction(fromPeer: String, txHash: String) {
                    Log.e(TAG, "Transfer requested with peer=$fromPeer: $txHash")
                    viewModel.addSystemMessage("❌ Transfer peer: $fromPeer (txHash=$txHash)")
                }               
            }
        )
        isMoneroTransferInitialized = true
        Log.i(TAG, "MoneroChatTransferManager initialized successfully.")
    }

    /**
     * Creates and sends a Monero transaction to a peer.
     * FIXED: Uses viewModel.walletSuite and properly calls onSuccess/onError callbacks
     */
    fun handleMoneroSend(
        amount: String,
        selectedPrivatePeer: String?,
        canReceiveMonero: Boolean,
        receiverMoneroAddress: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.i(TAG, "handleMoneroSend called: amount=$amount, peer=$selectedPrivatePeer, canReceive=$canReceiveMonero")

        if (selectedPrivatePeer == null || !canReceiveMonero) {
            Log.w(TAG, "Cannot send: peer null or peer cannot receive Monero.")
            onError("Cannot send Monero: wallet not ready or peer cannot receive.")
            return
        }

        if (receiverMoneroAddress == null) {
            Log.w(TAG, "Peer $selectedPrivatePeer has no known Monero address. Requesting...")
            viewModel.sendDirectMessage(selectedPrivatePeer, "[REQUEST_MONERO_ADDRESS]")
            onError("Peer address not available. Address request sent.")
            return
        }

        val walletSuite = viewModel.walletSuite
        if (walletSuite == null) {
            Log.e(TAG, "WalletSuite is null, cannot create transaction")
            onError("Wallet not available")
            return
        }

        try {
            Log.i(TAG, "Creating tx blob for $amount XMR to address=$receiverMoneroAddress")
            walletSuite.createTxBlob(receiverMoneroAddress, amount, object : WalletSuite.TxBlobCallback {
                override fun onSuccess(txId: String, base64Blob: String) {
                    Log.i(TAG, "Tx blob created successfully. txId=$txId, blobSize=${base64Blob.length} chars")

                    val transaction = BitchatMoneroTransfer.MoneroTransaction(
                        blob = base64Blob,
                        txHash = txId,
                        fee = null,
                        timestamp = System.currentTimeMillis(),
                        metadata = mapOf(
                            "amount" to amount,
                            "destination_address" to receiverMoneroAddress,
                            "source" to "bitchat_wallet"
                        )
                    )

                    if (base64Blob.length < MONERO_FILE_THRESHOLD) {
                        Log.d(TAG, "Sending tx via message (size under threshold).")
                        try {
                            sendViaMessage(selectedPrivatePeer, base64Blob, txId)
                            viewModel.addSystemMessage("💰 Created tx for $amount XMR — TxID: $txId")
                            onSuccess()
                            Log.d(TAG, "Transaction sent via message successfully, called onSuccess()")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send via message", e)
                            onError("Failed to send transaction via message: ${e.message}")
                        }
                    } else {
                        Log.d(TAG, "Sending tx via file (size exceeds threshold).")
                        sendViaFile(transaction, selectedPrivatePeer, onSuccess, onError)
                        viewModel.addSystemMessage("💰 Created tx for $amount XMR — TxID: $txId")
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Failed to create tx blob: $error")
                    onError("Payment failed: $error")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception in handleMoneroSend", e)
            onError("Unexpected error: ${e.message}")
        } catch (e: Error) {
            Log.e(TAG, "Error in handleMoneroSend", e)
            onError("Unexpected error: ${e.message}")
        }
    }

    /**
     * Sends a transaction blob inside a direct message (base64 string).
     * Enhanced with proper error handling and validation.
     */
    private fun sendViaMessage(peer: String, base64Blob: String, txId: String) {
        Log.i(TAG, "Sending txId=$txId via direct message to peer=$peer")
        Log.d(TAG, "Blob size: ${base64Blob.length} characters")
        
        try {
            // Validate inputs
            if (peer.isBlank()) {
                throw IllegalArgumentException("Peer cannot be blank")
            }
            
            if (base64Blob.isBlank()) {
                throw IllegalArgumentException("Transaction blob cannot be blank")
            }
            
            if (txId.isBlank()) {
                throw IllegalArgumentException("Transaction ID cannot be blank")
            }
            
            // Validate base64 format
            try {
                Base64.decode(base64Blob, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid base64 blob format", e)
            }
            
            // Send the message
            val message = "[XMR_TX_BLOB]$base64Blob"
            Log.d(TAG, "Sending message with prefix [XMR_TX_BLOB] and ${base64Blob.length} char blob")
            
            val success = viewModel.sendDirectMessage(peer, message)
            Log.i(TAG, "Successfully sent Monero transaction blob via message (TxID: $txId)")
            viewModel.addSystemMessage("📩 Sent Monero transaction blob via message (TxID: $txId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send transaction via message: txId=$txId", e)
            throw e // Re-throw so the calling code can handle it
        }
    }

    /**
     * Sends a large transaction as a file (.raw).
     */
    private fun sendViaFile(
        transaction: BitchatMoneroTransfer.MoneroTransaction,
        peer: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isMoneroTransferInitialized) {
            Log.e(TAG, "sendViaFile called but transfer system not initialized.")
            onError("Monero file transfer not initialized")
            return
        }

        viewModel.viewModelScope.launch {
            try {
                viewModel.sendDirectMessage(peer, "[XMR_FILE_TRANSFER]STARTING")

                moneroTransfer.sendTransaction(transaction, peer, object : BitchatMoneroTransfer.MoneroTransferCallback {
                    override fun onTransactionSent(success: Boolean, txHash: String, toPeer: String) {
                        Log.d(TAG, "File transfer sent callback: success=$success, txHash=$txHash, peer=$toPeer")
                        if (success) {
                            viewModel.addSystemMessage("✅ Sent Monero tx via file transfer")
                            onSuccess()
                        } else {
                            onError("File transfer failed")
                        }
                    }
                    
                    override fun onPeerRequestedTransaction(fromPeer: String, txHash: String) {
                        Log.e(TAG, "File transfer peer: $fromPeer, txHash=$txHash")
                        onError("File transfer peer: $fromPeer")
                    }

                    override fun onTransferError(error: String, txId: String) {
                        Log.e(TAG, "File transfer error: $error, txId=$txId")
                        onError("File transfer error: $error")
                    }

                    override fun onTransferProgress(txId: String, progress: Float) {
                        Log.d(TAG, "File transfer progress: txId=$txId, progress=$progress%")
                        viewModel.addSystemMessage("📤 File transfer progress: $progress%")
                    }

                    override fun onTransactionReceived(
                        transaction: BitchatMoneroTransfer.MoneroTransaction,
                        fromPeer: String
                    ) {
                        // Not expected during sending
                        Log.w(TAG, "Unexpected onTransactionReceived during send. peer=$fromPeer, txId=${transaction.txHash}")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Exception in sendViaFile", e)
                onError("File transfer error: ${e.message}")
            }
        }
    }

    /**
     * Handles a received Monero transaction file or blob.
     */
    private fun handleReceivedTransaction(
        transaction: BitchatMoneroTransfer.MoneroTransaction,
        fromPeer: String
    ) {
        Log.i(TAG, "Handling received tx from peer=$fromPeer, txId=${transaction.txHash}")
        viewModel.addSystemMessage("💰 Received Monero transaction from $fromPeer — TxID: ${transaction.txHash}")

        val walletSuite = viewModel.walletSuite
        if (walletSuite == null) {
            Log.e(TAG, "WalletSuite is null, cannot handle received transaction")
            viewModel.addSystemMessage("❌ Failed to handle received tx: Wallet not available")
            return
        }

        moneroTransfer.saveAndSubmitTransaction(
            transaction,
            walletSuite,
            onSuccess = { txHash ->
                viewModel.addSystemMessage("✅ Submitted received Monero tx to network: $txHash")
            },
            onError = { error ->
                viewModel.addSystemMessage("❌ Failed to handle received tx: $error")
            }
        )
    }
}
