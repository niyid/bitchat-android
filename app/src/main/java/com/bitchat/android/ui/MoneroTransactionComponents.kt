package com.bitchat.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.monero.bluetooth.MoneroChatTransferManager

/**
 * Transaction Search Dialog
 * Allows manual transaction lookup by TxID
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionSearchDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit
) {
    if (!isVisible) return

    var txIdInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search for Transaction") },
        text = {
            Column {
                Text(
                    "Enter the transaction ID to search for a missing transaction:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = txIdInput,
                    onValueChange = { txIdInput = it },
                    label = { Text("Transaction ID") },
                    placeholder = { Text("Enter TxID...") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "This will search the blockchain for the transaction and import it if found.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (txIdInput.isNotBlank()) {
                        onSearch(txIdInput.trim())
                        txIdInput = ""
                        onDismiss()
                    }
                },
                enabled = txIdInput.isNotBlank()
            ) {
                Text("Search")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Pending Transactions Indicator
 * Shows badge with count of pending transactions
 */
@Composable
fun PendingTransactionsIndicator(
    pendingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (pendingCount == 0) return

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFF9800).copy(alpha = 0.2f),
        border = BorderStroke(1.dp, Color(0xFFFF9800))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.HourglassEmpty,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$pendingCount pending",
                fontSize = 12.sp,
                color = Color(0xFFFF9800),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Pending Transactions Sheet
 * Shows all pending transactions with retry/clear actions
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingTransactionsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    pendingTransactions: Set<String>,
    onRetryAll: () -> Unit,
    onRetryOne: (String) -> Unit,
    onClearOne: (String) -> Unit
) {
    if (!isPresented) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pending Transactions",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                if (pendingTransactions.isNotEmpty()) {
                    TextButton(onClick = onRetryAll) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry all",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (pendingTransactions.isEmpty()) {
                Text(
                    "No pending transactions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                pendingTransactions.forEach { txId ->
                    PendingTransactionItem(
                        txId = txId,
                        onRetry = { onRetryOne(txId) },
                        onClear = { onClearOne(txId) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // Info box
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Pending transactions are automatically retried every minute. Transactions may take a few minutes to appear on the blockchain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PendingTransactionItem(
    txId: String,
    onRetry: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.HourglassEmpty,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Transaction ID",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = if (txId.length > 16) txId.take(16) + "..." else txId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            IconButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Transaction Mode Selector
 * Allows user to choose between TxID/Blob/Both modes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionModeDialog(
    isVisible: Boolean,
    currentMode: MoneroChatTransferManager.TransactionFlowMode,
    onDismiss: () -> Unit,
    onModeSelected: (MoneroChatTransferManager.TransactionFlowMode) -> Unit
) {
    if (!isVisible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transaction Mode") },
        text = {
            Column {
                Text(
                    "Choose how Monero transactions are sent:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                TransactionModeOption(
                    title = "TxID-Based (Recommended)",
                    description = "Send transaction normally, share TxID with receiver. More reliable.",
                    isSelected = currentMode == MoneroChatTransferManager.TransactionFlowMode.TXID_BASED,
                    onClick = {
                        onModeSelected(MoneroChatTransferManager.TransactionFlowMode.TXID_BASED)
                        onDismiss()
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TransactionModeOption(
                    title = "Blob-Based (Legacy)",
                    description = "Send signed transaction blob directly. Original method.",
                    isSelected = currentMode == MoneroChatTransferManager.TransactionFlowMode.BLOB_BASED,
                    onClick = {
                        onModeSelected(MoneroChatTransferManager.TransactionFlowMode.BLOB_BASED)
                        onDismiss()
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TransactionModeOption(
                    title = "Hybrid (Both)",
                    description = "Try TxID first, fallback to blob if needed. Best compatibility.",
                    isSelected = currentMode == MoneroChatTransferManager.TransactionFlowMode.BOTH,
                    onClick = {
                        onModeSelected(MoneroChatTransferManager.TransactionFlowMode.BOTH)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun TransactionModeOption(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            
            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Add to your MoneroWalletStatusBar to show pending transactions and mode
 */
@Composable
fun EnhancedMoneroWalletStatusBar(
    isWalletReady: Boolean,
    currentBalance: String,
    walletStatusMessage: String,
    isSyncing: Boolean,
    syncProgress: Int,
    colorScheme: ColorScheme,
    pendingCount: Int,
    currentMode: MoneroChatTransferManager.TransactionFlowMode,
    onDaemonConfigClick: () -> Unit,
    onPendingClick: () -> Unit,
    onModeClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp),
        color = when {
            isSyncing -> Color(0xFF2196F3).copy(alpha = 0.1f)
            isWalletReady -> Color(0xFF4CAF50).copy(alpha = 0.1f)
            else -> Color(0xFFFF9800).copy(alpha = 0.1f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Status message (clickable to open daemon config)
            Text(
                text = walletStatusMessage,
                fontSize = 11.sp,
                color = when {
                    isSyncing -> Color(0xFF2196F3)
                    isWalletReady -> Color(0xFF4CAF50)
                    else -> Color(0xFFFF9800)
                },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onDaemonConfigClick() }
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Pending transactions indicator
            if (pendingCount > 0) {
                Surface(
                    onClick = onPendingClick,
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFF9800).copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = pendingCount.toString(),
                            fontSize = 10.sp,
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Transaction mode indicator
            Surface(
                onClick = onModeClick,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            ) {
                Text(
                    text = when (currentMode) {
                        MoneroChatTransferManager.TransactionFlowMode.TXID_BASED -> "TxID"
                        MoneroChatTransferManager.TransactionFlowMode.BLOB_BASED -> "Blob"
                        MoneroChatTransferManager.TransactionFlowMode.BOTH -> "Both"
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Balance display
            if (isWalletReady) {
                Text(
                    text = if (isSyncing) {
                        "Bal: $currentBalance XMR"
                    } else {
                        "$currentBalance XMR"
                    },
                    fontSize = 11.sp,
                    color = if (isSyncing) Color(0xFF2196F3) else Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
                    
