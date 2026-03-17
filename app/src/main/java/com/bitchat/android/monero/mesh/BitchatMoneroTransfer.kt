package com.bitchat.android.monero.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.bitchat.android.monero.wallet.WalletSuite

/**
 * Monero Transaction Transfer integrated with Bitchat mesh network
 * Leverages Bitchat's existing Bluetooth mesh, encryption, and peer discovery
 */
class BitchatMoneroTransfer(private val context: Context) {

    companion object {
        private const val TAG = "com.bitchat.BitchatMoneroTx"
        private const val TX_MESSAGE_TYPE = 0x42 // Custom message type for Monero transactions
        private const val MAX_FRAGMENT_SIZE = 512 // Match Bitchat's fragmentation size
        private const val TX_FILE_EXTENSION = ".monerotx"
        private const val TRANSACTION_CHANNEL = "#monero-tx" // Dedicated channel for transactions
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val transferScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Integration with Bitchat's existing services
    private var bluetoothMeshService: Any? = null // Will be injected from Bitchat
    private var encryptionService: Any? = null    // Will be injected from Bitchat
    private var chatViewModel: Any? = null        // Will be injected from Bitchat

    // Monero transaction data structures
    data class MoneroTransaction(
        val blob: String,
        val txHash: String,
        val fee: Long? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap(),
        val senderPubKey: String? = null // Bitchat peer identity
    )

    data class TransactionPacket(
        val id: String,
        val fragmentIndex: Int,
        val totalFragments: Int,
        val data: ByteArray,
        val isComplete: Boolean = false,
        val metadata: TransactionMetadata? = null
    )

    data class TransactionMetadata(
        val fileName: String,
        val fileSize: Long,
        val checksum: String,
        val txHash: String?,
        val fee: Long?,
        val timestamp: Long,
        val senderNickname: String?
    )

    interface MoneroTransferCallback {
        fun onTransactionReceived(transaction: MoneroTransaction, fromPeer: String)
        fun onTransactionSent(success: Boolean, txHash: String, toPeer: String)
        fun onTransferProgress(txId: String, progress: Float)
        fun onTransferError(error: String, txId: String)
        fun onPeerRequestedTransaction(fromPeer: String, txHash: String)
    }

    private val activeTransfers = mutableMapOf<String, MutableList<TransactionPacket>>()
    private val completedTransfers = mutableSetOf<String>()
    private var transferCallback: MoneroTransferCallback? = null

    /**
     * Initialize with Bitchat services (called from Bitchat app)
     */
    fun initialize(
        meshService: Any,
        encryptionService: Any,
        viewModel: Any,
        callback: MoneroTransferCallback
    ) {
        this.bluetoothMeshService = meshService
        this.encryptionService = encryptionService
        this.chatViewModel = viewModel
        this.transferCallback = callback

        // Register for Bitchat message handling
        registerMoneroMessageHandler()
    }

    /**
     * Send Monero transaction through Bitchat mesh network
     * Can send to specific peer or broadcast to channel
     */
    suspend fun sendTransaction(
        transaction: MoneroTransaction,
        targetPeer: String,
        callback: MoneroTransferCallback? = null
    ) = withContext(Dispatchers.IO) {

        try {
            // Create transaction file in Bitchat's format
            val txFile = createTransactionFile(transaction)
            val metadata = createTransactionMetadata(txFile, transaction)

            // Fragment the transaction for mesh transmission
            val fragments = fragmentTransaction(txFile, metadata)

            // Send through Bitchat's mesh network
            sendToSpecificPeer(fragments, targetPeer)

            // Cleanup temporary file
            txFile.delete()

            callback?.onTransactionSent(true, transaction.txHash, targetPeer)
            transferCallback?.onTransactionSent(true, transaction.txHash, targetPeer)

        } catch (e: Exception) {
            Log.e(TAG, "Error sending Monero transaction", e)
            callback?.onTransferError("Send failed: ${e.message}", transaction.txHash)
            transferCallback?.onTransferError("Send failed: ${e.message}", transaction.txHash)
        }
    }

    /**
     * Request a specific transaction from the mesh network
     */
    suspend fun requestTransaction(txHash: String, fromPeer: String? = null) {
        val requestMessage = createTransactionRequest(txHash)

        if (fromPeer != null) {
            sendBitchatMessage(requestMessage, targetPeer = fromPeer)
        } else {
            broadcastBitchatMessage(requestMessage, TRANSACTION_CHANNEL)
        }
    }

    /**
     * Handle incoming Bitchat messages for Monero transactions
     */
    private fun registerMoneroMessageHandler() {
        // This would integrate with Bitchat's existing message handler
        // Pseudo-code for integration:
        /*
        bitchatMessageHandler.registerCustomHandler(TX_MESSAGE_TYPE) { message, fromPeer ->
            handleIncomingMoneroMessage(message, fromPeer)
        }
        */
    }

    /**
     * Process incoming Monero transaction messages
     */
    private suspend fun handleIncomingMoneroMessage(
        messageData: ByteArray,
        fromPeer: String
    ) = withContext(Dispatchers.IO) {

        try {
            val packet = deserializeTransactionPacket(messageData)

            when {
                packet.metadata != null && packet.fragmentIndex == 0 -> {
                    // First fragment with metadata
                    startReceivingTransaction(packet, fromPeer)
                }

                packet.isComplete -> {
                    // Single complete transaction (no prior fragments stored)
                    // store packet and immediately complete
                    activeTransfers[packet.id] = mutableListOf(packet)
                    completeTransactionReceipt(packet.id, fromPeer)
                }

                else -> {
                    // Additional fragment
                    handleTransactionFragment(packet, fromPeer)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming Monero message", e)
            transferCallback?.onTransferError("Receive failed: ${e.message}", "")
        }
    }

    /**
     * Create transaction file from Monero blob
     */
    private fun createTransactionFile(transaction: MoneroTransaction): File {
        val tempDir = File(context.cacheDir, "bitchat_monero_temp")
        tempDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(transaction.timestamp))
        val fileName = "monero_tx_${timestamp}$TX_FILE_EXTENSION"


        val txFile = File(tempDir, fileName)

        // Create structured transaction file content
        val txContent = buildString {
            appendLine("# Bitchat Monero Transaction")
            appendLine("version=1.0")
            appendLine("timestamp=${transaction.timestamp}")
            appendLine("blob=${transaction.blob}")
            transaction.txHash?.let { appendLine("hash=$it") }
            transaction.fee?.let { appendLine("fee=$it") }
            transaction.senderPubKey?.let { appendLine("sender=$it") }

            appendLine("\n# Metadata")
            transaction.metadata.forEach { (key, value) ->
                appendLine("$key=$value")
            }

            // blob already included in header above
        }

        txFile.writeText(txContent)
        return txFile
    }

    /**
     * Create metadata for transaction transfer
     */
    private fun createTransactionMetadata(
        txFile: File,
        transaction: MoneroTransaction
    ): TransactionMetadata {
        val checksum = generateFileChecksum(txFile)
        val senderNickname = getCurrentBitchatNickname()

        return TransactionMetadata(
            fileName = txFile.name,
            fileSize = txFile.length(),
            checksum = checksum,
            txHash = transaction.txHash,
            fee = transaction.fee,
            timestamp = transaction.timestamp,
            senderNickname = senderNickname
        )
    }

    /**
     * Fragment transaction for mesh network transmission
     */
    private fun fragmentTransaction(
        txFile: File,
        metadata: TransactionMetadata
    ): List<TransactionPacket> {
        val fileData = txFile.readBytes()
        val fragments = mutableListOf<TransactionPacket>()
        val txId = UUID.randomUUID().toString()

        // Calculate fragment count
        val dataPerFragment = MAX_FRAGMENT_SIZE - 200 // Reserve space for headers
        val totalFragments = (fileData.size + dataPerFragment - 1) / dataPerFragment

        // Create fragments
        for (i in 0 until totalFragments) {
            val start = i * dataPerFragment
            val end = minOf(start + dataPerFragment, fileData.size)
            val fragmentData = fileData.copyOfRange(start, end)

            val packet = TransactionPacket(
                id = txId,
                fragmentIndex = i,
                totalFragments = totalFragments,
                data = fragmentData,
                isComplete = totalFragments == 1,
                metadata = if (i == 0) metadata else null
            )

            fragments.add(packet)
        }

        return fragments
    }

    /**
     * Send transaction fragments to specific peer
     */
    private suspend fun sendToSpecificPeer(
        fragments: List<TransactionPacket>,
        targetPeer: String
    ) {
        fragments.forEach { fragment ->
            val messageData = serializeTransactionPacket(fragment)
            sendBitchatMessage(messageData, targetPeer)

            // Progress update
            val progress = (fragment.fragmentIndex + 1).toFloat() / fragment.totalFragments
            transferCallback?.onTransferProgress(fragment.id, progress)

            // Small delay to prevent overwhelming the mesh
            delay(50)
        }
    }

    /**
     * Broadcast transaction to Monero channel
     */
    private suspend fun broadcastToMoneroChannel(fragments: List<TransactionPacket>) {
        // Ensure we're in the Monero transaction channel
        joinMoneroChannel()

        fragments.forEach { fragment ->
            val messageData = serializeTransactionPacket(fragment)
            broadcastBitchatMessage(messageData, TRANSACTION_CHANNEL)

            val progress = (fragment.fragmentIndex + 1).toFloat() / fragment.totalFragments
            transferCallback?.onTransferProgress(fragment.id, progress)

            delay(100) // Longer delay for broadcast to prevent mesh congestion
        }
    }

    /**
     * Start receiving a fragmented transaction
     */
    private fun startReceivingTransaction(packet: TransactionPacket, fromPeer: String) {
        activeTransfers[packet.id] = mutableListOf(packet)

        if (packet.totalFragments == 1) {
            // Single fragment transaction -> call suspend completion in coroutine
            transferScope.launch {
                completeTransactionReceipt(packet.id, fromPeer)
            }
        }
    }

    /**
     * Handle additional transaction fragments
     */
    private fun handleTransactionFragment(packet: TransactionPacket, fromPeer: String) {
        val fragments = activeTransfers[packet.id]
        if (fragments != null) {
            fragments.add(packet)

            // Check if we have all fragments
            if (fragments.size >= packet.totalFragments) {
                // call suspend completion in coroutine context
                transferScope.launch {
                    completeTransactionReceipt(packet.id, fromPeer)
                }
            }

            // Update progress
            val progress = fragments.size.toFloat() / packet.totalFragments
            transferCallback?.onTransferProgress(packet.id, progress)
        }
    }

    /**
     * Complete transaction receipt and reconstruct file
     */
    private suspend fun completeTransactionReceipt(
        txId: String,
        fromPeer: String
    ) = withContext(Dispatchers.IO) {

        val fragments = activeTransfers[txId] ?: return@withContext
        activeTransfers.remove(txId)
        completedTransfers.add(txId)

        try {
            // Sort fragments and reconstruct data
            val sortedFragments = fragments.sortedBy { it.fragmentIndex }
            val metadata = sortedFragments[0].metadata ?: return@withContext

            val reconstructedData = ByteArray(metadata.fileSize.toInt())
            var offset = 0

            sortedFragments.forEach { fragment ->
                fragment.data.copyInto(reconstructedData, offset)
                offset += fragment.data.size
            }

            // Verify checksum
            val computedChecksum = generateDataChecksum(reconstructedData)
            if (computedChecksum != metadata.checksum) {
                transferCallback?.onTransferError("Checksum verification failed", txId)
                return@withContext
            }

            // Parse transaction from reconstructed data
            val transaction = parseTransactionFromData(reconstructedData, fromPeer)

            // Store in Bitchat's secure storage if needed
            saveReceivedTransaction(transaction, metadata)

            // Notify callback
            mainHandler.post {
                transferCallback?.onTransactionReceived(transaction, fromPeer)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error completing transaction receipt", e)
            transferCallback?.onTransferError("Receipt failed: ${e.message}", txId)
        }
    }

    /**
     * Parse Monero transaction from reconstructed data
     */
    private fun parseTransactionFromData(data: ByteArray, fromPeer: String): MoneroTransaction {
        val content = String(data, Charsets.UTF_8)
        val lines = content.lines()

        var blob = ""
        var txHash: String = ""
        var fee: Long? = null
        var timestamp = System.currentTimeMillis()
        val metadata = mutableMapOf<String, String>()

        var inBlobSection = false
        val blobBuilder = StringBuilder()

        lines.forEach { line ->
            when {
                line.startsWith("blob=") -> blob = line.substringAfter("=")
                line.startsWith("hash=") -> txHash = line.substringAfter("=")
                line.startsWith("fee=") -> fee = line.substringAfter("=").toLongOrNull()
                line.startsWith("timestamp=") -> timestamp = line.substringAfter("=").toLongOrNull() ?: timestamp
                line == "# Blob Data" -> inBlobSection = true
                inBlobSection && line.isNotBlank() && !line.startsWith("#") -> {
                    blobBuilder.append(line.trim())
                }
                line.contains("=") && !line.startsWith("#") -> {
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        metadata[parts[0]] = parts[1]
                    }
                }
            }
        }

        // Use blob from data section if main blob is empty
        if (blob.isEmpty() && blobBuilder.isNotEmpty()) {
            blob = blobBuilder.toString()
        }

        return MoneroTransaction(
            blob = blob,
            txHash = txHash,
            fee = fee,
            timestamp = timestamp,
            metadata = metadata,
            senderPubKey = fromPeer
        )
    }

    /**
     * Integration helpers for Bitchat services
     */
    private suspend fun sendBitchatMessage(data: ByteArray, targetPeer: String) {
        // This would integrate with Bitchat's mesh service
        // Pseudo-code:
        /*
        bluetoothMeshService?.sendDirectMessage(
            data = data,
            messageType = TX_MESSAGE_TYPE,
            targetPeer = targetPeer
        )
        */
    }
    
    fun saveAndSubmitTransaction(
        transaction: MoneroTransaction,
        walletSuite: WalletSuite,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val decodedBytes = decodeBase64(transaction.blob)

            val file = File(context.filesDir, "rx_${transaction.txHash}.raw")
            file.writeBytes(decodedBytes)
            Log.i(TAG, "Saved received transaction to ${file.absolutePath}")

            walletSuite.submitTxBlob(decodedBytes, object : WalletSuite.TxBlobCallback {
                override fun onSuccess(txHash: String, base64Blob: String) {
                    Log.i(TAG, "Submitted received tx to Monero network: $txHash")
                    onSuccess(txHash)
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Failed to submit received tx: $error")
                    onError(error)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in saveAndSubmitTransaction", e)
            onError("Error saving/submitting transaction: ${e.message}")
        }
    }
    
    private suspend fun broadcastBitchatMessage(data: ByteArray, channel: String) {
        // This would integrate with Bitchat's channel broadcasting
        // Pseudo-code:
        /*
        bluetoothMeshService?.broadcastToChannel(
            data = data,
            messageType = TX_MESSAGE_TYPE,
            channel = channel
        )
        */
    }

    private suspend fun joinMoneroChannel() {
        // Join the dedicated Monero transaction channel
        // Pseudo-code:
        /*
        chatViewModel?.executeCommand("/j $TRANSACTION_CHANNEL")
        */
    }

    private fun getCurrentBitchatNickname(): String {
        // Get current nickname from Bitchat
        // Pseudo-code:
        /*
        return chatViewModel?.getCurrentNickname() ?: "Anonymous"
        */
        return "BitchatUser" // Placeholder
    }

    private fun saveReceivedTransaction(transaction: MoneroTransaction, metadata: TransactionMetadata) {
        // Save to Bitchat's EncryptedSharedPreferences if needed
        // This could be optional based on user settings
    }

    /**
     * Create transaction request message
     */
    private fun createTransactionRequest(txHash: String): ByteArray {
        val request = "REQUEST_TX:$txHash"
        return request.toByteArray(Charsets.UTF_8)
    }

    /**
     * Utility functions
     */
    private fun serializeTransactionPacket(packet: TransactionPacket): ByteArray {
        // Simple serialization - in production use proper binary format
        val json = """
            {
                "id": "${packet.id}",
                "fragmentIndex": ${packet.fragmentIndex},
                "totalFragments": ${packet.totalFragments},
                "isComplete": ${packet.isComplete},
                "data": "${encodeBase64(packet.data)}",
                "metadata": ${if (packet.metadata != null) serializeMetadata(packet.metadata) else "null"}
            }
        """.trimIndent()

        return json.toByteArray(Charsets.UTF_8)
    }

    private fun deserializeTransactionPacket(data: ByteArray): TransactionPacket {
        val json = org.json.JSONObject(String(data, Charsets.UTF_8))
        return TransactionPacket(
            id = json.getString("id"),
            fragmentIndex = json.getInt("fragmentIndex"),
            totalFragments = json.getInt("totalFragments"),
            isComplete = json.getBoolean("isComplete"),
            data = decodeBase64(json.getString("data"))
        )
    }

    private fun serializeMetadata(metadata: TransactionMetadata): String {
        return """
            {
                "fileName": "${metadata.fileName}",
                "fileSize": ${metadata.fileSize},
                "checksum": "${metadata.checksum}",
                "txHash": "${metadata.txHash ?: ""}",
                "fee": ${metadata.fee ?: 0},
                "timestamp": ${metadata.timestamp},
                "senderNickname": "${metadata.senderNickname ?: ""}"
            }
        """.trimIndent()
    }

    private fun generateFileChecksum(file: File): String {
        return generateDataChecksum(file.readBytes())
    }

    private fun generateDataChecksum(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun encodeBase64(data: ByteArray): String {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    }

    private fun decodeBase64(data: String): ByteArray {
        return android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
    }


    /**
     * Cleanup resources
     */
    fun cleanup() {
        transferScope.cancel()
        activeTransfers.clear()
        completedTransfers.clear()

        // Clean up temp files
        val tempDir = File(context.cacheDir, "bitchat_monero_temp")
        tempDir.deleteRecursively()
    }
}

/**
 * Integration Extension for Bitchat MainActivity
 */
/*
// Add to Bitchat's MainActivity or Application class:

class BitchatMainActivity : AppCompatActivity() {

    private lateinit var moneroTransfer: BitchatMoneroTransfer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Monero transfer with Bitchat services
        moneroTransfer = BitchatMoneroTransfer(this)
        moneroTransfer.initialize(
            meshService = bluetoothMeshService,
            encryptionService = encryptionService,
            viewModel = chatViewModel,
            callback = object : BitchatMoneroTransfer.MoneroTransferCallback {
                override fun onTransactionReceived(transaction: BitchatMoneroTransfer.MoneroTransaction, fromPeer: String) {
                    // Handle received Monero transaction
                    showNotification("Monero transaction received from $fromPeer")
                    // Could auto-open wallet app or show transaction details
                }

                override fun onTransactionSent(success: Boolean, txHash: String?, toPeer: String?) {
                    val message = if (success)
                        "Transaction ${txHash} sent successfully"
                    else "Transaction send failed"
                    showToast(message)
                }

                override fun onTransferProgress(txId: String, progress: Float) {
                    // Update progress UI
                }

                override fun onTransferError(error: String, txId: String?) {
                    showToast("Transfer error: $error")
                }

                override fun onPeerRequestedTransaction(fromPeer: String, txHash: String) {
                    // Handle transaction request
                }
            }
        )
    }

    // Add command handler for Monero transactions
    private fun handleMoneroCommand(command: String, args: List<String>) {
        when (command) {
            "/sendtx" -> {
                if (args.isNotEmpty()) {
                    val txBlob = args[0]
                    val targetPeer = if (args.size > 1) args[1] else null

                    val transaction = BitchatMoneroTransfer.MoneroTransaction(
                        blob = txBlob,
                        metadata = mapOf("source" -> "bitchat")
                    )

                    lifecycleScope.launch {
                        moneroTransfer.sendMoneroTransaction(transaction, targetPeer)
                    }
                }
            }

            "/reqtx" -> {
                if (args.isNotEmpty()) {
                    val txHash = args[0]
                    lifecycleScope.launch {
                        moneroTransfer.requestTransaction(txHash)
                    }
                }
            }
        }
    }
}
*/

