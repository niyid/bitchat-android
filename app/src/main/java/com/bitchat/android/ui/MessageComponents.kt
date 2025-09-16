package com.bitchat.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.monero.wallet.WalletSuite
import com.bitchat.android.monero.bluetooth.MoneroChatTransferManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */
private const val TAG = "com.bitchat.MessageComponents"

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    walletSuite: WalletSuite?,
    moneroChatTransferManager: MoneroChatTransferManager?,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    forceScrollToBottom: Boolean = false,
    onScrolledUpChanged: ((Boolean) -> Unit)? = null,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }

    // Smart scroll
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1

            val isFirstLoad = !hasScrolledToInitialPosition
            val isNearLatest = firstVisibleIndex <= 2

            if (isFirstLoad || isNearLatest) {
                listState.animateScrollToItem(0)
                if (isFirstLoad) {
                    hasScrolledToInitialPosition = true
                }
            }
        }
    }

    // Track scroll away from bottom
    val isAtLatest by remember {
        derivedStateOf {
            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            firstVisibleIndex <= 2
        }
    }
    LaunchedEffect(isAtLatest) {
        onScrolledUpChanged?.invoke(!isAtLatest)
    }

    // Force scroll to bottom
    LaunchedEffect(forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
        reverseLayout = true
    ) {
        items(messages.asReversed()) { message ->
            MessageItem(
                message = message,
                currentUserNickname = currentUserNickname,
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                walletSuite = walletSuite,
                moneroChatTransferManager = moneroChatTransferManager,
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    walletSuite: WalletSuite?,
    moneroChatTransferManager: MoneroChatTransferManager?,
    viewModel: ChatViewModel
) {
    val isCurrentUser = message.sender == currentUserNickname
    val colorScheme = MaterialTheme.colorScheme

    when {
        // ---- FILE TRANSFER SIGNAL ----
        message.content.startsWith("[XMR_FILE_TRANSFER]") -> {
            LaunchedEffect(message.id) {
                Log.d(TAG, "Monero file transfer signal from ${message.sender}")
                // Just notify the user — actual file/tx handling is done via MoneroChatTransferManager callbacks
                viewModel.addSystemMessage("📥 Incoming Monero transaction file from ${message.sender}")
            }
            return
        }

        // ---- CONFIRMATION ----
        message.content.startsWith("[XMR_TX_CONFIRMED]") -> {
            LaunchedEffect(message.id) {
                Log.d(TAG, "Received tx confirmation: ${message.content}")
                handleTxConfirmed(message, viewModel)
            }
            return
        }
        // ---- ADDRESS SHARING ----
        message.content.startsWith("[MONERO_ADDRESS]") -> {
            LaunchedEffect(message.id) {
                val address = message.content.removePrefix("[MONERO_ADDRESS]")
                Log.d(TAG, "Received Monero address from ${message.sender}: $address")
                viewModel.updatePeerMoneroAddress(message.sender, address)
                viewModel.addSystemMessage("🏦 ${message.sender} shared their Monero address")
            }
            return
        }
    }

    // Render normal chat message
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onNicknameClick?.invoke(message.sender) },
                onLongClick = { onMessageLongPress?.invoke(message) }
            ),
        horizontalArrangement = if (isCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Column(modifier = Modifier.widthIn(max = 280.dp)) {
            if (!isCurrentUser) {
                Text(
                    text = message.sender,
                    fontSize = 12.sp,
                    color = colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isCurrentUser) colorScheme.primary else colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.content,
                        color = if (isCurrentUser) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )

                    Text(
                        text = formatTimestamp(message.timestamp?.time ?: 0L),
                        fontSize = 10.sp,
                        color = (if (isCurrentUser) colorScheme.onPrimary else colorScheme.onSurfaceVariant)
                            .copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageTextWithClickableNicknames(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val annotatedText = formatMessageAsAnnotatedString(
        message = message,
        currentUserNickname = currentUserNickname,
        meshService = meshService,
        colorScheme = colorScheme,
        timeFormatter = timeFormatter
    )

    val isSelf = message.senderPeerID == meshService.myPeerID ||
            message.sender == currentUserNickname ||
            message.sender.startsWith("$currentUserNickname#")

    if (!isSelf && (onNicknameClick != null || onMessageLongPress != null)) {
        val haptic = LocalHapticFeedback.current
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = annotatedText,
            modifier = modifier.pointerInput(message) {
                detectTapGestures(
                    onTap = { position ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        val nicknameAnnotations = annotatedText.getStringAnnotations(
                            tag = "nickname_click",
                            start = offset,
                            end = offset
                        )
                        if (nicknameAnnotations.isNotEmpty()) {
                            val nickname = nicknameAnnotations.first().item
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick?.invoke(nickname)
                        }
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
            fontFamily = FontFamily.Monospace,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = androidx.compose.ui.text.TextStyle(color = colorScheme.onSurface),
            onTextLayout = { result -> textLayoutResult = result }
        )
    } else {
        val haptic = LocalHapticFeedback.current
        Text(
            text = annotatedText,
            modifier = if (onMessageLongPress != null) {
                modifier.combinedClickable(
                    onClick = { },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress.invoke(message)
                    }
                )
            } else modifier,
            fontFamily = FontFamily.Monospace,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = androidx.compose.ui.text.TextStyle(color = colorScheme.onSurface)
        )
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    when (status) {
        is DeliveryStatus.Sending,
        is DeliveryStatus.Sent -> Text("○", fontSize = 10.sp, color = colorScheme.primary.copy(alpha = 0.6f))
        is DeliveryStatus.Delivered -> Text("✓", fontSize = 10.sp, color = colorScheme.primary.copy(alpha = 0.8f))
        is DeliveryStatus.Read -> Text("✓✓", fontSize = 10.sp, color = Color(0xFF007AFF), fontWeight = FontWeight.Bold)
        is DeliveryStatus.Failed -> Text("⚠", fontSize = 10.sp, color = Color.Red.copy(alpha = 0.8f))
        is DeliveryStatus.PartiallyDelivered -> Text(
            "✓${status.reached}/${status.total}",
            fontSize = 10.sp,
            color = colorScheme.primary.copy(alpha = 0.6f)
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private suspend fun handleTxConfirmed(
    message: BitchatMessage,
    viewModel: ChatViewModel
) {
    try {
        val parts = message.content.removePrefix("[XMR_TX_CONFIRMED]").split("|")
        val txId = parts.getOrNull(0) ?: "unknown"
        val amount = parts.getOrNull(1) ?: "?"
        val status = parts.getOrNull(2) ?: "unknown"

        val symbol = if (status == "success") "✅" else "❌"
        Log.i(TAG, "Tx confirmed: id=$txId amount=$amount status=$status")
        viewModel.addSystemMessage(
            "$symbol Monero tx $txId ($amount atomic units) status: $status"
        )
    } catch (e: Exception) {
        Log.e(TAG, "Invalid Monero confirmation: ${e.message}", e)
        viewModel.addSystemMessage("⚠️ Invalid Monero confirmation: ${e.message}")
    }
}

