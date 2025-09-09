package com.bitchat.android.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.monero.wallet.MoneroWalletManager
import com.bitchat.android.monero.messaging.MoneroMessageHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap

/**
 * Main ChatScreen - REFACTORED to use component-based architecture with Monero integration
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.observeAsState(emptyList())
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val nickname by viewModel.nickname.observeAsState("")
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.observeAsState()
    val currentChannel by viewModel.currentChannel.observeAsState()
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptySet())
    val hasUnreadChannels by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val privateChats by viewModel.privateChats.observeAsState(emptyMap())
    val channelMessages by viewModel.channelMessages.observeAsState(emptyMap())
    val showSidebar by viewModel.showSidebar.observeAsState(false)
    val showCommandSuggestions by viewModel.showCommandSuggestions.observeAsState(false)
    val commandSuggestions by viewModel.commandSuggestions.observeAsState(emptyList())
    val showMentionSuggestions by viewModel.showMentionSuggestions.observeAsState(false)
    val mentionSuggestions by viewModel.mentionSuggestions.observeAsState(emptyList())
    val showAppInfo by viewModel.showAppInfo.observeAsState(false)

    // Monero-related state
    var moneroWalletManager by remember { mutableStateOf<MoneroWalletManager?>(null) }
    var moneroMessageHandler by remember { mutableStateOf<MoneroMessageHandler?>(null) }
    var isMoneroModeActive by remember { mutableStateOf(false) }
    var currentBalance by remember { mutableStateOf("0.000000") }
    var isSyncing by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf(0) }
    var walletStatusMessage by remember { mutableStateOf("Wallet initializing...") }
    var isWalletReady by remember { mutableStateOf(false) }
    var peerMoneroAddresses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var showLocationChannelsSheet by remember { mutableStateOf(false) }
    var showUserSheet by remember { mutableStateOf(false) }
    var selectedUserForSheet by remember { mutableStateOf("") }
    var selectedMessageForSheet by remember { mutableStateOf<BitchatMessage?>(null) }
    var forceScrollToBottom by remember { mutableStateOf(false) }
    var isScrolledUp by remember { mutableStateOf(false) }

    // Initialize Monero components
    LaunchedEffect(Unit) {
        moneroWalletManager = MoneroWalletManager.getInstance(context).apply {
            setWalletStatusListener(object : MoneroWalletManager.WalletStatusListener {
                override fun onWalletInitialized(success: Boolean, message: String) {
                    isWalletReady = success
                    if (success) {
                        walletStatusMessage = "Wallet ready"
                        getBalance(object : MoneroWalletManager.BalanceCallback {
                            override fun onSuccess(balance: Long, unlockedBalance: Long) {
                                currentBalance = MoneroWalletManager.convertAtomicToXmr(unlockedBalance)
                            }
                            override fun onError(error: String) {
                                walletStatusMessage = "Balance error: $error"
                            }
                        })
                    } else {
                        walletStatusMessage = "Wallet failed: $message"
                    }
                }

                override fun onBalanceUpdated(balance: Long, unlockedBalance: Long) {
                    currentBalance = MoneroWalletManager.convertAtomicToXmr(unlockedBalance)
                }

                override fun onSyncProgress(height: Long, targetHeight: Long, percentDone: Double) {
                    isSyncing = percentDone < 1.0
                    syncProgress = (percentDone * 100).toInt()
                    
                    if (isSyncing) {
                        walletStatusMessage = "Syncing: $syncProgress% ($height/$targetHeight)"
                    } else {
                        walletStatusMessage = "Wallet synchronized"
                    }
                }
            })

            setTransactionListener(object : MoneroWalletManager.TransactionListener {
                override fun onTransactionCreated(txId: String, amount: Long) {
                    val amountXmr = MoneroWalletManager.convertAtomicToXmr(amount)
                    viewModel.addSystemMessage("💰 Transaction created: $amountXmr XMR (tx: $txId)")
                }

                override fun onTransactionConfirmed(txId: String) {
                    viewModel.updateTransactionStatus(txId, "confirmed")
                    viewModel.addSystemMessage("✅ Transaction confirmed: $txId")
                }

                override fun onTransactionFailed(txId: String, error: String) {
                    viewModel.updateTransactionStatus(txId, "failed")
                    viewModel.addSystemMessage("❌ Transaction failed: $txId - $error")
                }

                override fun onOutputReceived(amount: Long, txHash: String, isConfirmed: Boolean) {
                    val amountXmr = MoneroWalletManager.convertAtomicToXmr(amount)
                    val status = if (isConfirmed) "confirmed" else "pending"
                    viewModel.addSystemMessage("💰 Output received: $amountXmr XMR ($status) - tx: $txHash")
                }
            })

            initializeWallet(true)
        }

        moneroMessageHandler = MoneroMessageHandler().apply {
            setMessageListener(object : MoneroMessageHandler.MoneroMessageListener {
                override fun onPaymentReceived(payment: MoneroMessageHandler.MoneroPaymentMessage) {
                    val paymentMessage = "💰 Received ${payment.amount} XMR from ${payment.fromUser}"
                    viewModel.addSystemMessage(paymentMessage)
                }

                override fun onAddressShared(address: String, fromUser: String) {
                    peerMoneroAddresses = peerMoneroAddresses + (fromUser to address)
                    viewModel.addSystemMessage("🔑 $fromUser shared Monero address")
                }

                override fun onPaymentRequested(request: MoneroMessageHandler.MoneroPaymentRequest) {
                    val requestMessage = "💳 ${request.fromUser} requested ${request.amount} XMR" +
                        if (request.reason.isNotEmpty()) " - ${request.reason}" else ""
                    viewModel.addSystemMessage(requestMessage)
                }

                override fun onPaymentStatusUpdated(txId: String, status: String) {
                    viewModel.updateTransactionStatus(txId, status)
                }
            })
        }
    }

    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }

    val isConnected by viewModel.isConnected.observeAsState(false)
    val passwordPromptChannel by viewModel.passwordPromptChannel.observeAsState(null)

    // Determine what messages to show
    val displayMessages = when {
        selectedPrivatePeer != null -> privateChats[selectedPrivatePeer] ?: emptyList()
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> messages
    }

    // Check if current chat partner can receive Monero (for private chats)
    val canReceiveMonero = selectedPrivatePeer != null && 
                          peerMoneroAddresses.containsKey(selectedPrivatePeer)

    // Use WindowInsets to handle keyboard properly
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        val headerHeight = if (isWalletReady && selectedPrivatePeer != null) 64.dp else 42.dp
        
        // Main content area that responds to keyboard/window insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // Header spacer - creates exact space for the floating header
            Spacer(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(headerHeight)
            )

            // Messages area - takes up available space, will compress when keyboard appears
            MessagesList(
                messages = displayMessages,
                currentUserNickname = nickname,
                meshService = viewModel.meshService,
                modifier = Modifier.weight(1f),
                forceScrollToBottom = forceScrollToBottom,
                onScrolledUpChanged = { isUp: Boolean -> isScrolledUp = isUp },
                onNicknameClick = { fullSenderName: String ->
                    // Single click - mention user in text input
                    val currentText = messageText.text
                    
                    // Extract base nickname and hash suffix from full sender name
                    val (baseName, hashSuffix) = splitSuffix(fullSenderName)
                    
                    // Check if we're in a geohash channel to include hash suffix
                    val selectedLocationChannel = viewModel.selectedLocationChannel.value
                    val mentionText = if (selectedLocationChannel != null && hashSuffix.isNotEmpty()) {
                        "@$baseName$hashSuffix"
                    } else {
                        "@$baseName"
                    }
                    
                    val newText = when {
                        currentText.isEmpty() -> "$mentionText "
                        currentText.endsWith(" ") -> "$currentText$mentionText "
                        else -> "$currentText $mentionText "
                    }
                    
                    messageText = TextFieldValue(
                        text = newText,
                        selection = TextRange(newText.length)
                    )
                },
                onMessageLongPress = { message: BitchatMessage ->
                    val (baseName, _) = splitSuffix(message.sender)
                    selectedUserForSheet = baseName
                    selectedMessageForSheet = message
                    showUserSheet = true
                }
            )
            
            // Input area - stays at bottom with Monero integration
            ChatInputSection(
                messageText = messageText,
                onMessageTextChange = { newText: TextFieldValue ->
                    messageText = newText
                    viewModel.updateCommandSuggestions(newText.text)
                    viewModel.updateMentionSuggestions(newText.text)
                },
                onSend = {
                    if (messageText.text.trim().isNotEmpty()) {
                        if (isMoneroModeActive) {
                            // Handle Monero send
                            handleMoneroSend(
                                amount = messageText.text.trim(),
                                moneroWalletManager = moneroWalletManager,
                                selectedPrivatePeer = selectedPrivatePeer,
                                canReceiveMonero = canReceiveMonero,
                                peerMoneroAddresses = peerMoneroAddresses,
                                viewModel = viewModel,
                                onSuccess = {
                                    messageText = TextFieldValue("")
                                    isMoneroModeActive = false
                                    forceScrollToBottom = !forceScrollToBottom
                                },
                                onError = { error ->
                                    // Handle error (show toast or error message)
                                    viewModel.addSystemMessage("❌ Payment error: $error")
                                }
                            )
                        } else {
                            // Handle regular message send
                            viewModel.sendMessage(messageText.text.trim())
                            messageText = TextFieldValue("")
                            forceScrollToBottom = !forceScrollToBottom
                        }
                    }
                },
                showCommandSuggestions = showCommandSuggestions,
                commandSuggestions = commandSuggestions,
                showMentionSuggestions = showMentionSuggestions,
                mentionSuggestions = mentionSuggestions,
                onCommandSuggestionClick = { suggestion: CommandSuggestion ->
                    val commandText = viewModel.selectCommandSuggestion(suggestion)
                    messageText = TextFieldValue(
                        text = commandText,
                        selection = TextRange(commandText.length)
                    )
                },
                onMentionSuggestionClick = { mention: String ->
                    val mentionText = viewModel.selectMentionSuggestion(mention, messageText.text)
                    messageText = TextFieldValue(
                        text = mentionText,
                        selection = TextRange(mentionText.length)
                    )
                },
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                colorScheme = colorScheme,
                // Monero-specific parameters
                isMoneroModeActive = isMoneroModeActive,
                onMoneroModeToggle = { 
                    if (canReceiveMonero && isWalletReady) {
                        isMoneroModeActive = !isMoneroModeActive 
                    }
                },
                canReceiveMonero = canReceiveMonero,
                isWalletReady = isWalletReady
            )
        }

        // Floating header with Monero wallet info
        ChatFloatingHeader(
            headerHeight = headerHeight,
            selectedPrivatePeer = selectedPrivatePeer,
            currentChannel = currentChannel,
            nickname = nickname,
            viewModel = viewModel,
            colorScheme = colorScheme,
            onSidebarToggle = { viewModel.showSidebar() },
            onShowAppInfo = { viewModel.showAppInfo() },
            onPanicClear = { viewModel.panicClearAllData() },
            onLocationChannelsClick = { showLocationChannelsSheet = true },
            // Monero wallet info
            isWalletReady = isWalletReady,
            currentBalance = currentBalance,
            walletStatusMessage = walletStatusMessage,
            isSyncing = isSyncing,
            syncProgress = syncProgress
        )

        // Divider under header
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .offset(y = headerHeight)
                .zIndex(1f),
            color = colorScheme.outline.copy(alpha = 0.3f)
        )

        val alpha by animateFloatAsState(
            targetValue = if (showSidebar) 0.5f else 0f,
            animationSpec = tween(
                durationMillis = 300,
                easing = EaseOutCubic
            ), label = "overlayAlpha"
        )

        // Background overlay for sidebar
        if (alpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = alpha))
                    .clickable { viewModel.hideSidebar() }
                    .zIndex(1f)
            )
        }

        // Scroll-to-bottom floating button
        AnimatedVisibility(
            visible = isScrolledUp && !showSidebar,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 64.dp)
                .zIndex(1.5f)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            Surface(
                shape = CircleShape,
                color = colorScheme.background,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(2.dp, Color(0xFF00C851))
            ) {
                IconButton(onClick = { forceScrollToBottom = !forceScrollToBottom }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = "Scroll to bottom",
                        tint = Color(0xFF00C851)
                    )
                }
            }
        }

        // Sidebar
        AnimatedVisibility(
            visible = showSidebar,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300, easing = EaseOutCubic)
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(250, easing = EaseInCubic)
            ) + fadeOut(animationSpec = tween(250)),
            modifier = Modifier.zIndex(2f)
        ) {
            SidebarOverlay(
                viewModel = viewModel,
                onDismiss = { viewModel.hideSidebar() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // Dialogs and Sheets
    ChatDialogs(
        showPasswordDialog = showPasswordDialog,
        passwordPromptChannel = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = { passwordInput = it },
        onPasswordConfirm = {
            if (passwordInput.isNotEmpty()) {
                val success = viewModel.joinChannel(passwordPromptChannel!!, passwordInput)
                if (success) {
                    showPasswordDialog = false
                    passwordInput = ""
                }
            }
        },
        onPasswordDismiss = {
            showPasswordDialog = false
            passwordInput = ""
        },
        showAppInfo = showAppInfo,
        onAppInfoDismiss = { viewModel.hideAppInfo() },
        showLocationChannelsSheet = showLocationChannelsSheet,
        onLocationChannelsSheetDismiss = { showLocationChannelsSheet = false },
        showUserSheet = showUserSheet,
        onUserSheetDismiss = { 
            showUserSheet = false
            selectedMessageForSheet = null
        },
        selectedUserForSheet = selectedUserForSheet,
        selectedMessageForSheet = selectedMessageForSheet,
        viewModel = viewModel
    )
}

// Monero send handler function
private fun handleMoneroSend(
    amount: String,
    moneroWalletManager: MoneroWalletManager?,
    selectedPrivatePeer: String?,
    canReceiveMonero: Boolean,
    peerMoneroAddresses: Map<String, String>,
    viewModel: ChatViewModel,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    if (moneroWalletManager == null || selectedPrivatePeer == null || !canReceiveMonero) {
        onError("Cannot send Monero: wallet not ready or peer cannot receive Monero")
        return
    }

    try {
        val amountValue = amount.toDouble()
        if (amountValue <= 0) {
            onError("Please enter a valid amount")
            return
        }
    } catch (e: NumberFormatException) {
        onError("Please enter a valid amount")
        return
    }

    // Add sending message
    viewModel.sendMessage("Sending $amount XMR...")

    // Get Monero address for the peer
    val peerMoneroAddress = peerMoneroAddresses[selectedPrivatePeer]
    if (peerMoneroAddress == null) {
        onError("Peer Monero address not found")
        return
    }

    moneroWalletManager.sendMonero(
        peerMoneroAddress, 
        amount?.toLongOrNull() ?: 0L,
        object : MoneroWalletManager.SendCallback {
            override fun onSuccess(txId: String, atomicAmount: Long) {
                val successMessage = "💰 Sent $amount XMR (pending)"
                // Update last message or add new one
                viewModel.sendMessage(successMessage)

                val paymentMessage = MoneroMessageHandler.createPaymentMessage(
                    amount, txId, peerMoneroAddress
                )
                viewModel.sendMessage(paymentMessage)
                
                onSuccess()
            }

            override fun onError(error: String) {
                val errorMessage = "❌ Failed to send $amount XMR"
                viewModel.sendMessage(errorMessage)
                onError("Payment failed: $error")
            }
        }
    )
}

@Composable
private fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    onNicknameClick: (String) -> Unit,
    onMessageLongPress: (BitchatMessage) -> Unit
) {
    val isCurrentUser = message.sender == currentUserNickname
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onNicknameClick(message.sender) },
                onLongClick = { onMessageLongPress(message) }
            ),
        horizontalArrangement = if (isCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
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
                shape = RoundedCornerShape(
                    topStart = if (isCurrentUser) 16.dp else 4.dp,
                    topEnd = if (isCurrentUser) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                color = if (isCurrentUser) colorScheme.primary else colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = message.content,
                        color = if (isCurrentUser) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    
                    Text(
                        text = formatTimestamp(message.timestamp?.time ?: 0L),
                        fontSize = 10.sp,
                        color = (if (isCurrentUser) colorScheme.onPrimary else colorScheme.onSurfaceVariant).copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
private fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
    onMentionSuggestionClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme,
    // Monero parameters
    isMoneroModeActive: Boolean,
    onMoneroModeToggle: () -> Unit,
    canReceiveMonero: Boolean,
    isWalletReady: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colorScheme.background
    ) {
        Column {
            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
            
            // Command suggestions box
            if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
                CommandSuggestionsBox(
                    suggestions = commandSuggestions,
                    onSuggestionClick = onCommandSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
            
            // Mention suggestions box
            if (showMentionSuggestions && mentionSuggestions.isNotEmpty()) {
                MentionSuggestionsBox(
                    suggestions = mentionSuggestions,
                    onSuggestionClick = onMentionSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }

            // Enhanced message input with Monero toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Message input field
                OutlinedTextField(
                    value = messageText,
                    onValueChange = onMessageTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { 
                        Text(
                            if (isMoneroModeActive) "Enter amount (XMR)" else "Type a message...",
                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (isMoneroModeActive) Color(0xFFFF6B35) else colorScheme.primary,
                        unfocusedBorderColor = colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { onSend() }
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Monero toggle button (only visible in private chats)
                if (selectedPrivatePeer != null && isWalletReady) {
                    Surface(
                        shape = CircleShape,
                        color = if (isMoneroModeActive) Color(0xFFFF6B35) else colorScheme.surface,
                        tonalElevation = if (isMoneroModeActive) 0.dp else 2.dp,
                        modifier = Modifier.size(48.dp)
                    ) {
                        IconButton(
                            onClick = onMoneroModeToggle,
                            enabled = canReceiveMonero
                        ) {
                            Icon(
                                imageVector = Icons.Default.CurrencyExchange,
                                contentDescription = if (isMoneroModeActive) "Switch to message mode" else "Switch to Monero mode",
                                tint = if (isMoneroModeActive) Color.White else 
                                      if (canReceiveMonero) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Send button
                Surface(
                    shape = CircleShape,
                    color = if (messageText.text.isNotBlank()) {
                        if (isMoneroModeActive) Color(0xFFFF6B35) else colorScheme.primary
                    } else colorScheme.surface,
                    tonalElevation = if (messageText.text.isNotBlank()) 0.dp else 2.dp,
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(
                        onClick = onSend,
                        enabled = messageText.text.isNotBlank()
                    ) {
                        Icon(
                            imageVector = if (isMoneroModeActive) Icons.Default.CurrencyExchange else Icons.Default.Send,
                            contentDescription = if (isMoneroModeActive) "Send Monero" else "Send message",
                            tint = if (messageText.text.isNotBlank()) Color.White else colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatFloatingHeader(
    headerHeight: Dp,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    colorScheme: ColorScheme,
    onSidebarToggle: () -> Unit,
    onShowAppInfo: () -> Unit,
    onPanicClear: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    // Monero wallet parameters
    isWalletReady: Boolean,
    currentBalance: String,
    walletStatusMessage: String,
    isSyncing: Boolean,
    syncProgress: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.statusBars),
        color = colorScheme.background
    ) {
        Column {
            // Main header
            TopAppBar(
                title = {
                    ChatHeaderContent(
                        selectedPrivatePeer = selectedPrivatePeer,
                        currentChannel = currentChannel,
                        nickname = nickname,
                        viewModel = viewModel,
                        onBackClick = {
                            when {
                                selectedPrivatePeer != null -> viewModel.endPrivateChat()
                                currentChannel != null -> viewModel.switchToChannel(null)
                            }
                        },
                        onSidebarClick = onSidebarToggle,
                        onTripleClick = onPanicClear,
                        onShowAppInfo = onShowAppInfo,
                        onLocationChannelsClick = onLocationChannelsClick
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier.height(42.dp)
            )

            // Monero wallet status bar (only visible in private chats when wallet is available)
            if (selectedPrivatePeer != null && (isWalletReady || isSyncing)) {
                MoneroWalletStatusBar(
                    isWalletReady = isWalletReady,
                    currentBalance = currentBalance,
                    walletStatusMessage = walletStatusMessage,
                    isSyncing = isSyncing,
                    syncProgress = syncProgress,
                    colorScheme = colorScheme
                )
            }
        }
    }
}

@Composable
private fun MoneroWalletStatusBar(
    isWalletReady: Boolean,
    currentBalance: String,
    walletStatusMessage: String,
    isSyncing: Boolean,
    syncProgress: Int,
    colorScheme: ColorScheme
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp),
        color = if (isSyncing) Color(0xFF2196F3).copy(alpha = 0.1f) 
               else if (isWalletReady) Color(0xFF4CAF50).copy(alpha = 0.1f)
               else Color(0xFFFF9800).copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Status message
            Text(
                text = walletStatusMessage,
                fontSize = 11.sp,
                color = if (isSyncing) Color(0xFF2196F3)
                       else if (isWalletReady) Color(0xFF4CAF50)
                       else Color(0xFFFF9800),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Balance (only show when wallet is ready)
            if (isWalletReady && !isSyncing) {
                Text(
                    text = "Balance: $currentBalance XMR",
                    fontSize = 11.sp,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium
                )
            }

            // Sync progress (only show when syncing)
            if (isSyncing) {
                Text(
                    text = "$syncProgress%",
                    fontSize = 10.sp,
                    color = Color(0xFF2196F3),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SidebarChannelItem(
    name: String,
    isSelected: Boolean,
    hasUnread: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer 
                      else MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = name,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer 
                       else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            
            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            Color(0xFF00C851),
                            CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun SidebarPeerItem(
    name: String,
    isSelected: Boolean,
    hasUnread: Boolean,
    isOnline: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer 
                          else MaterialTheme.colorScheme.onSurface
                )
                
                // Online indicator
                if (isOnline) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color(0xFF00C851), CircleShape)
                            .align(Alignment.BottomEnd)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = name,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer 
                       else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            Color(0xFF00C851),
                            CircleShape
                        )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit
) {
    if (isPresented) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "About BitChat",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Text(
                    text = "BitChat is a decentralized messaging application with integrated Monero payments.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Text(
                    text = "Features:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                val features = listOf(
                    "Mesh networking for offline communication",
                    "Encrypted private messaging",
                    "Channel-based group chat",
                    "Location-based channels",
                    "Integrated Monero wallet",
                    "Peer-to-peer payments"
                )
                
                features.forEach { feature ->
                    Text(
                        text = "• $feature",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun LocationChannelItem(
    channelName: String,
    distance: String,
    memberCount: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = channelName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "$distance • $memberCount members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Join",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun UserActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ChatDialogs(
    showPasswordDialog: Boolean,
    passwordPromptChannel: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirm: () -> Unit,
    onPasswordDismiss: () -> Unit,
    showAppInfo: Boolean,
    onAppInfoDismiss: () -> Unit,
    showLocationChannelsSheet: Boolean,
    onLocationChannelsSheetDismiss: () -> Unit,
    showUserSheet: Boolean,
    onUserSheetDismiss: () -> Unit,
    selectedUserForSheet: String,
    selectedMessageForSheet: BitchatMessage?,
    viewModel: ChatViewModel
) {
    // Password dialog
    PasswordPromptDialog(
        show = showPasswordDialog,
        channelName = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = onPasswordChange,
        onConfirm = onPasswordConfirm,
        onDismiss = onPasswordDismiss
    )

    // About sheet
    AboutSheet(
        isPresented = showAppInfo,
        onDismiss = onAppInfoDismiss
    )
    
    // Location channels sheet
    LocationChannelsSheet(
        isPresented = showLocationChannelsSheet,
        onDismiss = onLocationChannelsSheetDismiss,
        viewModel = viewModel
    )
    
    // User action sheet
    ChatUserSheet(
        isPresented = showUserSheet,
        onDismiss = onUserSheetDismiss,
        targetNickname = selectedUserForSheet,
        selectedMessage = selectedMessageForSheet,
        viewModel = viewModel
    )
}

// Additional data classes and extensions for the ChatViewModel integration

// Location channel data class
data class LocationChannel(
    val name: String,
    val distance: String,
    val memberCount: Int,
    val geohash: String
)
