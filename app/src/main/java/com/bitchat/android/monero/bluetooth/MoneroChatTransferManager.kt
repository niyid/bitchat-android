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
        Log.d(TAG, "=== INITIALIZE CALLED ===")
        Log.d(TAG, "Current initialization state: $isMoneroTransferInitialized")
        Log.d(TAG, "MeshService available: ${meshService != null}")
        Log.d(TAG, "EncryptionService available: ${encryptionService != null}")
        
        if (isMoneroTransferInitialized) {
            Log.w(TAG, "Already initialized, skipping initialization.")
            return
        }

        Log.i(TAG, "Initializing MoneroChatTransferManager...")
        try {
            moneroTransfer.initialize(
                meshService = meshService,
                encryptionService = encryptionService,
                viewModel = viewModel,
                callback = object : BitchatMoneroTransfer.MoneroTransferCallback {
                    override fun onTransactionReceived(
                        transaction: BitchatMoneroTransfer.MoneroTransaction,
                        fromPeer: String
                    ) {
                        Log.i(TAG, "=== TRANSACTION RECEIVED CALLBACK ===")
                        Log.d(TAG, "From peer: $fromPeer")
                        Log.d(TAG, "TxId: ${transaction.txHash}")
                        Log.d(TAG, "Blob length: ${transaction.blob.length}")
                        Log.d(TAG, "Timestamp: ${transaction.timestamp}")
                        Log.d(TAG, "Metadata: ${transaction.metadata}")
                        
                        handleReceivedTransaction(transaction, fromPeer)
                    }

                    override fun onTransactionSent(success: Boolean, txHash: String, toPeer: String) {
                        Log.i(TAG, "=== TRANSACTION SENT CALLBACK ===")
                        Log.d(TAG, "Success: $success")
                        Log.d(TAG, "TxHash: $txHash")
                        Log.d(TAG, "To peer: $toPeer")
                        
                        if (success) {
                            Log.i(TAG, "Transaction successfully sent to peer")
                            viewModel.addSystemMessage("✅ Transaction sent to $toPeer (TxID: $txHash)")
                        } else {
                            Log.e(TAG, "Transaction send failed to peer $toPeer")
                            viewModel.addSystemMessage("⛔ Failed to send transaction to $toPeer")
                        }
                    }

                    override fun onTransferError(error: String, txId: String) {
                        Log.e(TAG, "=== TRANSFER ERROR CALLBACK ===")
                        Log.e(TAG, "Error: $error")
                        Log.e(TAG, "TxId: $txId")
                        
                        viewModel.addSystemMessage("⛔ Transfer error: $error (txId=$txId)")
                    }

                    override fun onTransferProgress(txId: String, progress: Float) {
                        Log.d(TAG, "=== TRANSFER PROGRESS CALLBACK ===")
                        Log.d(TAG, "TxId: $txId")
                        Log.d(TAG, "Progress: $progress%")
                        
                        viewModel.addSystemMessage("📤 File transfer with $txId: $progress%")
                    }
     
                    override fun onPeerRequestedTransaction(fromPeer: String, txHash: String) {
                        Log.i(TAG, "=== PEER REQUESTED TRANSACTION CALLBACK ===")
                        Log.d(TAG, "From peer: $fromPeer")
                        Log.d(TAG, "TxHash: $txHash")
                        
                        viewModel.addSystemMessage("ℹ️ Transaction requested by peer: $fromPeer (txHash=$txHash)")
                    }               
                }
            )
            isMoneroTransferInitialized = true
            Log.i(TAG, "=== INITIALIZATION SUCCESSFUL ===")
        } catch (e: Exception) {
            Log.e(TAG, "=== INITIALIZATION FAILED ===", e)
            Log.e(TAG, "Exception during initialization: ${e.message}")
            isMoneroTransferInitialized = false
            throw e
        }
    }

    /**
     * Creates and sends a Monero transaction to a peer.
     * Enhanced with comprehensive logging and validation
     */
    fun handleMoneroSend(
        amount: String,
        selectedPrivatePeer: String?,
        canReceiveMonero: Boolean,
        receiverMoneroAddress: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.i(TAG, "=== HANDLE MONERO SEND INITIATED ===")
        Log.d(TAG, "Amount: $amount")
        Log.d(TAG, "Selected peer: $selectedPrivatePeer")
        Log.d(TAG, "Can receive Monero: $canReceiveMonero")
        Log.d(TAG, "Receiver address: $receiverMoneroAddress")
        Log.d(TAG, "Transfer initialized: $isMoneroTransferInitialized")

        // Basic validation with detailed logging
        if (selectedPrivatePeer == null) {
            Log.e(TAG, "SEND_FAILED - Selected peer is null")
            onError("Cannot send Monero: no peer selected")
            return
        }
        
        if (!canReceiveMonero) {
            Log.e(TAG, "SEND_FAILED - Peer cannot receive Monero: $selectedPrivatePeer")
            onError("Cannot send Monero: peer cannot receive payments")
            return
        }

        if (receiverMoneroAddress == null || receiverMoneroAddress.isBlank()) {
            Log.w(TAG, "SEND_FAILED - Receiver address not available for peer: $selectedPrivatePeer")
            Log.d(TAG, "Sending address request to peer")
            
            try {
                val requestSent = viewModel.sendDirectMessage(selectedPrivatePeer, "[REQUEST_MONERO_ADDRESS]")
                Log.d(TAG, "Address request sent successfully: $requestSent")
                
                onError("Peer address not available. Address request sent.")
                viewModel.addSystemMessage("⏳ Requesting Monero address from $selectedPrivatePeer")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send address request", e)
                onError("Failed to request peer address: ${e.message}")
            }
            return
        }

        // Validate wallet availability
        val walletSuite = viewModel.walletSuite
        if (walletSuite == null) {
            Log.e(TAG, "SEND_FAILED - WalletSuite is null")
            onError("Wallet not available")
            return
        }
        
        Log.d(TAG, "WalletSuite validation passed")

        // Validate amount format
        try {
            val amountDouble = amount.toDouble()
            Log.d(TAG, "Amount validation passed: $amountDouble XMR")
            
            if (amountDouble <= 0) {
                Log.e(TAG, "SEND_FAILED - Invalid amount: $amountDouble (must be > 0)")
                onError("Invalid amount: must be greater than 0")
                return
            }
        } catch (e: NumberFormatException) {
            Log.e(TAG, "SEND_FAILED - Amount parsing error", e)
            onError("Invalid amount format: ${e.message}")
            return
        }

        // All validations passed, proceed with transaction creation
        Log.i(TAG, "All validations passed, creating transaction blob...")
        
        try {
            Log.d(TAG, "Calling WalletSuite.createTxBlob...")
            Log.d(TAG, "  - Destination: $receiverMoneroAddress")
            Log.d(TAG, "  - Amount: $amount XMR")
            
            walletSuite.createTxBlob(receiverMoneroAddress, amount, object : WalletSuite.TxBlobCallback {
                override fun onSuccess(txId: String, base64Blob: String) {
                    Log.i(TAG, "=== TX BLOB CREATION SUCCESS ===")
                    Log.d(TAG, "Transaction ID: $txId")
                    Log.d(TAG, "Blob size: ${base64Blob.length} characters")
                    Log.d(TAG, "Blob size in bytes: ${base64Blob.length * 3 / 4} (estimated)")
                    Log.d(TAG, "File threshold: $MONERO_FILE_THRESHOLD bytes")
                    
                    // Validate blob content
                    if (base64Blob.isBlank()) {
                        Log.e(TAG, "SEND_FAILED - Empty blob returned from wallet")
                        onError("Invalid transaction blob created")
                        return
                    }
                    
                    if (txId.isBlank()) {
                        Log.e(TAG, "SEND_FAILED - Empty transaction ID returned from wallet")
                        onError("Invalid transaction ID created")
                        return
                    }

                    // Create transaction object
                    val transaction = BitchatMoneroTransfer.MoneroTransaction(
                        blob = base64Blob,
                        txHash = txId,
                        fee = null, // Fee will be calculated by wallet
                        timestamp = System.currentTimeMillis(),
                        metadata = mapOf(
                            "amount" to amount,
                            "destination_address" to receiverMoneroAddress,
                            "source" to "bitchat_wallet",
                            "created_at" to System.currentTimeMillis().toString()
                        )
                    )
                    
                    Log.d(TAG, "Transaction object created with metadata: ${transaction.metadata}")

                    // Determine send method based on blob size
                    val blobSizeBytes = base64Blob.length * 3 / 4 // Rough estimate
                    if (blobSizeBytes < MONERO_FILE_THRESHOLD) {
                        Log.i(TAG, "Using message transfer (size: $blobSizeBytes bytes < $MONERO_FILE_THRESHOLD threshold)")
                        
                        try {
                            sendViaMessage(selectedPrivatePeer, base64Blob, txId)
                            Log.i(TAG, "=== MESSAGE SEND SUCCESSFUL ===")
                            viewModel.addSystemMessage("💰 Created and sent Monero tx for $amount XMR — TxID: $txId")
                            onSuccess()
                        } catch (e: Exception) {
                            Log.e(TAG, "=== MESSAGE SEND FAILED ===", e)
                            onError("Failed to send transaction via message: ${e.message}")
                        }
                    } else {
                        Log.i(TAG, "Using file transfer (size: $blobSizeBytes bytes >= $MONERO_FILE_THRESHOLD threshold)")
                        
                        sendViaFile(transaction, selectedPrivatePeer, onSuccess, onError)
                        viewModel.addSystemMessage("💰 Created Monero tx for $amount XMR — TxID: $txId (sending via file)")
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "=== TX BLOB CREATION FAILED ===")
                    Log.e(TAG, "Wallet error: $error")
                    Log.d(TAG, "Amount: $amount")
                    Log.d(TAG, "Address: $receiverMoneroAddress")
                    
                    onError("Payment failed: $error")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "=== EXCEPTION IN HANDLE MONERO SEND ===", e)
            Log.e(TAG, "Exception type: ${e::class.java.simpleName}")
            Log.e(TAG, "Exception message: ${e.message}")
            onError("Unexpected error: ${e.message}")
        } catch (e: Error) {
            Log.e(TAG, "=== ERROR IN HANDLE MONERO SEND ===", e)
            Log.e(TAG, "Error type: ${e::class.java.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            onError("Unexpected error: ${e.message}")
        }
    }

    /**
     * Sends a transaction blob inside a direct message (base64 string).
     * Enhanced with comprehensive validation and logging.
     */
    private fun sendViaMessage(peer: String, base64Blob: String, txId: String) {
        Log.i(TAG, "=== SEND VIA MESSAGE INITIATED ===")
        Log.d(TAG, "Peer: $peer")
        Log.d(TAG, "TxId: $txId")
        Log.d(TAG, "Blob size: ${base64Blob.length} characters")
        Log.d(TAG, "Estimated blob size in bytes: ${base64Blob.length * 3 / 4}")
        
        try {
            // Validate inputs with detailed logging
            if (peer.isBlank()) {
                Log.e(TAG, "SEND_VIA_MESSAGE_FAILED - Peer is blank")
                throw IllegalArgumentException("Peer cannot be blank")
            }
            
            if (base64Blob.isBlank()) {
                Log.e(TAG, "SEND_VIA_MESSAGE_FAILED - Blob is blank")
                throw IllegalArgumentException("Transaction blob cannot be blank")
            }
            
            if (txId.isBlank()) {
                Log.e(TAG, "SEND_VIA_MESSAGE_FAILED - TxId is blank")
                throw IllegalArgumentException("Transaction ID cannot be blank")
            }
            
            Log.d(TAG, "Input validation passed")
            
            // Validate base64 format
            Log.d(TAG, "Validating base64 format...")
            try {
                val decodedBytes = Base64.decode(base64Blob, Base64.DEFAULT)
                Log.d(TAG, "Base64 validation passed - decoded to ${decodedBytes.size} bytes")
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "SEND_VIA_MESSAGE_FAILED - Invalid base64 format", e)
                throw IllegalArgumentException("Invalid base64 blob format", e)
            }
            
            // Create and send the message
            val message = "[XMR_TX_BLOB]$base64Blob"
            val totalMessageSize = message.length
            
            Log.d(TAG, "Preparing message:")
            Log.d(TAG, "  - Prefix: [XMR_TX_BLOB]")
            Log.d(TAG, "  - Blob length: ${base64Blob.length}")
            Log.d(TAG, "  - Total message length: $totalMessageSize")
            
            Log.i(TAG, "Sending transaction message to peer...")
            val sendResult = viewModel.sendDirectMessage(peer, message)
            
            Log.i(TAG, "=== SEND VIA MESSAGE COMPLETED ===")
            Log.d(TAG, "Send result: $sendResult")
            Log.i(TAG, "Successfully sent Monero transaction blob via message (TxID: $txId)")
            
            viewModel.addSystemMessage("📩 Sent Monero transaction blob via message (TxID: $txId)")
            
        } catch (e: Exception) {
            Log.e(TAG, "=== SEND VIA MESSAGE FAILED ===", e)
            Log.e(TAG, "Exception type: ${e::class.java.simpleName}")
            Log.e(TAG, "TxId: $txId")
            Log.e(TAG, "Peer: $peer")
            throw e // Re-throw so the calling code can handle it
        }
    }

    /**
     * Sends a large transaction as a file (.raw).
     * Enhanced with comprehensive logging and error handling.
     */
    private fun sendViaFile(
        transaction: BitchatMoneroTransfer.MoneroTransaction,
        peer: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.i(TAG, "=== SEND VIA FILE INITIATED ===")
        Log.d(TAG, "Peer: $peer")
        Log.d(TAG, "TxId: ${transaction.txHash}")
        Log.d(TAG, "Transfer initialized: $isMoneroTransferInitialized")
        
        if (!isMoneroTransferInitialized) {
            Log.e(TAG, "SEND_VIA_FILE_FAILED - Transfer system not initialized")
            onError("Monero file transfer not initialized")
            return
        }

        Log.d(TAG, "Launching coroutine for file transfer...")
        viewModel.viewModelScope.launch {
            try {
                Log.d(TAG, "Sending file transfer initiation message to peer")
                val initMessageSent = viewModel.sendDirectMessage(peer, "[XMR_FILE_TRANSFER]STARTING")
                Log.d(TAG, "Initiation message sent: $initMessageSent")

                Log.i(TAG, "Starting file transfer via BitchatMoneroTransfer...")
                moneroTransfer.sendTransaction(transaction, peer, object : BitchatMoneroTransfer.MoneroTransferCallback {
                    override fun onTransactionSent(success: Boolean, txHash: String, toPeer: String) {
                        Log.i(TAG, "=== FILE TRANSFER SENT CALLBACK ===")
                        Log.d(TAG, "Success: $success")
                        Log.d(TAG, "TxHash: $txHash")
                        Log.d(TAG, "To peer: $toPeer")
                        
                        if (success) {
                            Log.i(TAG, "File transfer completed successfully")
                            viewModel.addSystemMessage("✅ Sent Monero tx via file transfer (TxID: $txHash)")
                            onSuccess()
                        } else {
                            Log.e(TAG, "File transfer failed")
                            onError("File transfer failed")
                        }
                    }
                    
                    override fun onPeerRequestedTransaction(fromPeer: String, txHash: String) {
                        Log.i(TAG, "=== FILE TRANSFER PEER REQUESTED ===")
                        Log.d(TAG, "From peer: $fromPeer")
                        Log.d(TAG, "TxHash: $txHash")
                        
                        // This might be normal during file transfer negotiation
                        Log.w(TAG, "Peer requested transaction during file transfer")
                    }

                    override fun onTransferError(error: String, txId: String) {
                        Log.e(TAG, "=== FILE TRANSFER ERROR ===")
                        Log.e(TAG, "Error: $error")
                        Log.e(TAG, "TxId: $txId")
                        
                        onError("File transfer error: $error")
                    }

                    override fun onTransferProgress(txId: String, progress: Float) {
                        Log.d(TAG, "=== FILE TRANSFER PROGRESS ===")
                        Log.d(TAG, "TxId: $txId")
                        Log.d(TAG, "Progress: $progress%")
                        
                        if (progress % 25 == 0f || progress == 100f) { // Log major milestones
                            Log.i(TAG, "File transfer progress milestone: $progress%")
                        }
                        
                        viewModel.addSystemMessage("📤 File transfer progress: $progress%")
                    }

                    override fun onTransactionReceived(
                        transaction: BitchatMoneroTransfer.MoneroTransaction,
                        fromPeer: String
                    ) {
                        Log.w(TAG, "=== UNEXPECTED TRANSACTION RECEIVED DURING SEND ===")
                        Log.w(TAG, "From peer: $fromPeer")
                        Log.w(TAG, "TxId: ${transaction.txHash}")
                        Log.w(TAG, "This should not happen during sending phase")
                    }
                })
                
                Log.d(TAG, "File transfer initiated successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "=== EXCEPTION IN SEND VIA FILE ===", e)
                Log.e(TAG, "Exception type: ${e::class.java.simpleName}")
                Log.e(TAG, "Exception message: ${e.message}")
                Log.e(TAG, "TxId: ${transaction.txHash}")
                Log.e(TAG, "Peer: $peer")
                
                onError("File transfer error: ${e.message}")
            }
        }
    }

    /**
     * Handles a received Monero transaction file or blob.
     * Enhanced with comprehensive logging and validation.
     */
    private fun handleReceivedTransaction(
        transaction: BitchatMoneroTransfer.MoneroTransaction,
        fromPeer: String
    ) {
        Log.i(TAG, "=== HANDLE RECEIVED TRANSACTION ===")
        Log.d(TAG, "From peer: $fromPeer")
        Log.d(TAG, "TxId: ${transaction.txHash}")
        Log.d(TAG, "Blob length: ${transaction.blob.length}")
        Log.d(TAG, "Timestamp: ${transaction.timestamp}")
        Log.d(TAG, "Fee: ${transaction.fee}")
        Log.d(TAG, "Metadata: ${transaction.metadata}")
        
        // Validate transaction
        if (transaction.blob.isBlank()) {
            Log.e(TAG, "RECEIVE_FAILED - Empty transaction blob")
            viewModel.addSystemMessage("⛔ Received invalid transaction from $fromPeer: empty blob")
            return
        }
        
        if (transaction.txHash.isBlank()) {
            Log.e(TAG, "RECEIVE_FAILED - Empty transaction hash")
            viewModel.addSystemMessage("⛔ Received invalid transaction from $fromPeer: empty hash")
            return
        }
        
        Log.d(TAG, "Transaction validation passed")
        viewModel.addSystemMessage("💰 Received Monero transaction from $fromPeer — TxID: ${transaction.txHash}")

        // Check wallet availability
        val walletSuite = viewModel.walletSuite
        if (walletSuite == null) {
            Log.e(TAG, "RECEIVE_FAILED - WalletSuite is null")
            viewModel.addSystemMessage("⛔ Failed to handle received tx: Wallet not available")
            return
        }
        
        Log.d(TAG, "WalletSuite available, proceeding with save and submit")

        try {
            Log.i(TAG, "Calling moneroTransfer.saveAndSubmitTransaction...")
            moneroTransfer.saveAndSubmitTransaction(
                transaction,
                walletSuite,
                onSuccess = { txHash ->
                    Log.i(TAG, "=== RECEIVED TRANSACTION SUBMITTED SUCCESSFULLY ===")
                    Log.d(TAG, "Submitted TxHash: $txHash")
                    Log.d(TAG, "Original TxHash: ${transaction.txHash}")
                    
                    viewModel.addSystemMessage("✅ Submitted received Monero tx to network: $txHash")
                },
                onError = { error ->
                    Log.e(TAG, "=== RECEIVED TRANSACTION SUBMISSION FAILED ===")
                    Log.e(TAG, "Error: $error")
                    Log.e(TAG, "TxId: ${transaction.txHash}")
                    Log.e(TAG, "From peer: $fromPeer")
                    
                    viewModel.addSystemMessage("⛔ Failed to handle received tx: $error")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "=== EXCEPTION IN HANDLE RECEIVED TRANSACTION ===", e)
            Log.e(TAG, "Exception type: ${e::class.java.simpleName}")
            Log.e(TAG, "Exception message: ${e.message}")
            Log.e(TAG, "TxId: ${transaction.txHash}")
            Log.e(TAG, "From peer: $fromPeer")
            
            viewModel.addSystemMessage("⛔ Exception handling received tx: ${e.message}")
        }
    }
}
