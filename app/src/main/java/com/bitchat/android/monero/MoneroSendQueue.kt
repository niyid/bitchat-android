package com.bitchat.android.monero

import android.util.Log
import com.bitchat.android.monero.wallet.WalletSuite
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean


data class PendingSend(
    val peerId: String,
    val address: String,
    val amount: Double,
    val onSuccess: (String) -> Unit,
    val onError: (String) -> Unit
)

class MoneroSendQueue(private val walletSuite: WalletSuite?) {
    private val sendQueue = ConcurrentLinkedQueue<PendingSend>()
    private val isProcessing = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "MoneroSendQueue"
    }
    
    fun enqueueSend(
        peerId: String,
        address: String,
        amount: Double,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (walletSuite == null) {
            Log.e(TAG, "WalletSuite is null, cannot enqueue send")
            onError("Wallet not initialized")
            return
        }
        
        val pendingSend = PendingSend(peerId, address, amount, onSuccess, onError)
        sendQueue.offer(pendingSend)
        Log.d(TAG, "Enqueued send: $amount XMR to $peerId (queue size: ${sendQueue.size})")
        
        // Trigger rescan first to ensure accurate balance
        scope.launch {
            triggerRescanThenProcess()
        }
    }
    
    private fun triggerRescanThenProcess() {
        if (walletSuite == null) {
            Log.e(TAG, "WalletSuite is null during rescan")
            sendQueue.forEach { it.onError("Wallet not initialized") }
            sendQueue.clear()
            return
        }
        
        if (!isProcessing.compareAndSet(false, true)) {
            Log.d(TAG, "Rescan already in progress")
            return
        }
        
        Log.d(TAG, "Triggering forceRescan to get accurate balance...")
        Log.d(TAG, "Rescan will complete when monitorRescanProgress reaches daemon height")
        
        walletSuite.forceRescan(object : WalletSuite.RescanCallback {
            override fun onComplete(newBalance: Long, newUnlockedBalance: Long) {
                val balanceXmr = newBalance / 1e12
                val unlockedXmr = newUnlockedBalance / 1e12
                
                Log.d(TAG, "=== RESCAN FULLY COMPLETE ===")
                Log.d(TAG, "Final Balance from callback: $balanceXmr XMR")
                Log.d(TAG, "Final Unlocked from callback: $unlockedXmr XMR")
                
                // CRITICAL: The callback is fired from monitorRescanProgress after wallet.store()
                // This means the balance should be accurate and persisted
                // However, we add a small delay to ensure all wallet state is stable
                scope.launch {
                    delay(2000) // 2 second safety buffer for wallet state stabilization
                    
                    Log.d(TAG, "Wallet state stabilized - ready to process queue")
                    processQueue()
                }
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "Rescan failed: $error")
                isProcessing.set(false)
                
                // Notify all pending sends of the error
                while (sendQueue.isNotEmpty()) {
                    val send = sendQueue.poll()
                    send?.let { 
                        scope.launch(Dispatchers.Main) {
                            it.onError("Balance refresh failed: $error")
                        }
                    }
                }
            }
        })
    }
    
    private fun processQueue() {
        scope.launch {
            try {
                while (sendQueue.isNotEmpty()) {
                    val send = sendQueue.poll() ?: break
                    Log.d(TAG, "Processing send: ${send.amount} XMR to ${send.peerId}")
                    
                    processSend(send)
                    
                    // Small delay between sends to prevent overwhelming the wallet
                    delay(500)
                }
            } finally {
                isProcessing.set(false)
                
                // Check if new items were added while we were finishing
                if (sendQueue.isNotEmpty()) {
                    triggerRescanThenProcess()
                }
            }
        }
    }
    
    private suspend fun processSend(send: PendingSend) {
        if (walletSuite == null) {
            withContext(Dispatchers.Main) {
                send.onError("Wallet not initialized")
            }
            return
        }
        
        try {
            // Step 1: Verify balance on the background thread (after rescan completed)
            // This should now have the accurate balance from monitorRescanProgress
            val balanceCheck = withContext(Dispatchers.IO) {
                // Force a fresh balance read from the wallet
                walletSuite.getBalance(object : WalletSuite.BalanceCallback {
                    override fun onSuccess(balance: Long, unlockedBalance: Long) {
                        Log.d(TAG, "=== FRESH BALANCE CHECK (Before Send) ===")
                        Log.d(TAG, "Balance: $balance atomic units (${balance / 1e12} XMR)")
                        Log.d(TAG, "Unlocked: $unlockedBalance atomic units (${unlockedBalance / 1e12} XMR)")
                        Log.d(TAG, "Required: ${(send.amount * 1e12).toLong()} atomic units (${send.amount} XMR)")
                    }
                    override fun onError(error: String) {
                        Log.e(TAG, "Failed to get fresh balance: $error")
                    }
                })
                
                // Also get atomic balance for validation
                val balance = walletSuite.getBalanceAtomic()
                val unlocked = walletSuite.getUnlockedBalanceAtomic()
                
                BalanceCheck(balance, unlocked)
            }
            
            // Step 2: Validate sufficient balance
            val requiredAtomic = (send.amount * 1e12).toLong()
            val estimatedFeeAtomic = (0.001 * 1e12).toLong() // ~0.001 XMR fee buffer
            val totalRequiredAtomic = requiredAtomic + estimatedFeeAtomic
            
            if (balanceCheck.unlocked < totalRequiredAtomic) {
                val msg = "Insufficient unlocked balance: ${balanceCheck.unlocked / 1e12} XMR (need ~${totalRequiredAtomic / 1e12} XMR including fee)"
                Log.e(TAG, msg)
                withContext(Dispatchers.Main) {
                    send.onError(msg)
                }
                return
            }
            
            Log.d(TAG, "Balance verification passed - proceeding with transaction")
            
            // Step 3: Execute send on background thread
            val txHash = withContext(Dispatchers.IO) {
                Log.d(TAG, "Executing send on background thread...")
                walletSuite.createAndSendTransaction(send.address, send.amount)
            }
            
            // Step 4: Notify success on main thread
            withContext(Dispatchers.Main) {
                Log.d(TAG, "✅ Send successful: $txHash")
                send.onSuccess(txHash)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}", e)
            
            // Check if it's a balance-related error and suggest rescan
            val errorMsg = e.message ?: "Unknown error"
            val enhancedMsg = if (errorMsg.contains("not enough", ignoreCase = true) ||
                                  errorMsg.contains("insufficient", ignoreCase = true) ||
                                  errorMsg.contains("balance", ignoreCase = true)) {
                "$errorMsg - Wallet may need another rescan"
            } else {
                errorMsg
            }
            
            withContext(Dispatchers.Main) {
                send.onError(enhancedMsg)
            }
        }
    }
    
    fun cancelAll() {
        sendQueue.clear()
        scope.cancel()
    }
    
    private data class BalanceCheck(val balance: Long, val unlocked: Long)
}
