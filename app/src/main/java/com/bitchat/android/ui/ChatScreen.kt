package com.bitchat.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.monero.wallet.MoneroWalletManager
import com.bitchat.android.monero.messaging.MoneroMessageHandler
import java.math.BigInteger

// Data classes for command and mention suggestions
data class CommandSuggestion(
    val command: String,
    val description: String,
    val usage: String = ""
)

/**
 * Main ChatScreen - REFACTORED to use component-based architecture with Monero integration
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val hasUnreadChannels by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val showCommandSuggestions by viewModel.showCommandSuggestions.collectAsStateWithLifecycle()
    val commandSuggestions by viewModel.commandSuggestions.collectAsStateWithLifecycle()
    val showMentionSuggestions by viewModel.showMentionSuggestions.collectAsStateWithLifecycle()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    val showAppInfo by viewModel.showAppInfo.collectAsStateWithLifecycle()
    val showMeshPeerListSheet by viewModel.showMeshPeerList.collectAsStateWithLifecycle()
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()
    val showVerificationSheet by viewModel.showVerificationSheet.collectAsStateWithLifecycle()
    val showSecurityVerificationSheet by viewModel.showSecurityVerificationSheet.collectAsStateWithLifecycle()

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
    var showLocationNotesSheet by remember { mutableStateOf(false) }
    var showUserSheet by remember { mutableStateOf(false) }
    var selectedUserForSheet by remember { mutableStateOf("") }
    var selectedMessageForSheet by remember { mutableStateOf<BitchatMessage?>(null) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var viewerImagePaths by remember { mutableStateOf(emptyList<String>()) }
    var initialViewerIndex by remember { mutableStateOf(0) }
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
                            override fun onSuccess(balance: BigInteger, unlockedBalance: BigInteger) {
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

                override fun onBalanceUpdated(balance: BigInteger, unlockedBalance: BigInteger) {
                    currentBalance = MoneroWalletManager.convertAtomicToXmr(unlockedBalance)
                }

                override fun onSyncProgress(height: Long, startHeight: Long, targetHeight: Long, percentDone: Double) {
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
                override fun onTransactionCreated(txId: String, amount: BigInteger) {
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

                override fun onOutputReceived(amount: BigInteger, txId: String, confirmed: Boolean) {
                    val amountXmr = MoneroWalletManager.convertAtomicToXmr(amount)
                    val status = if (confirmed) "confirmed" else "pending"
                    viewModel.addSystemMessage("💰 Output received: $amountXmr XMR ($status) - tx: $txId")
                }
            })

            initializeWallet()
        }

        moneroMessageHandler = MoneroMessageHandler().apply {
            setMessageListener(object : MoneroMessageHandler.MoneroMessageListener {
                override fun onPaymentReceived(payment: MoneroMessageHandler.MoneroPaymentMessage) {
                    val paymentMessage = "💰 Received ${payment.amount} XMR from ${payment.fromUser}"
                    viewModel.addSystemMessage(paymentMessage)
                }

                override fun onAddressShared(address: String, fromUser: String) {
                    peerMoneroAddresses = peerMoneroAddresses + (fromUser to address)
                    viewModel.addSystemMessage("📍 $fromUser shared Monero address")
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

    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val passwordPromptChannel by viewModel.passwordPromptChannel.collectAsStateWithLifecycle()

    // Get location channel info for timeline switching
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()

    // Determine what messages to show based on current context (unified timelines)
    val displayMessages = when {
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> {
            val locationChannel = selectedLocationChannel
            if (locationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                val geokey = "geo:${locationChannel.channel.geohash}"
                channelMessages[geokey] ?: emptyList()
            } else {
                messages // Mesh timeline
            }
        }
    }

    // Determine whether to show media buttons (only hide in geohash location chats)
    val showMediaButtons = when {
        currentChannel != null -> true
        else -> selectedLocationChannel !is com.bitchat.android.geohash.ChannelID.Location
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
                onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                onNicknameClick = { fullSenderName ->
                    // Single click - mention user in text input
                    val currentText = messageText.text
                    
                    // Extract base nickname and hash suffix from full sender name
                    val (baseName, hashSuffix) = splitSuffix(fullSenderName)
                    
                    // Check if we're in a geohash channel to include hash suffix
                    val selectedLocationChannel = viewModel.selectedLocationChannel.value
                    val mentionText = if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location && hashSuffix.isNotEmpty()) {
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
                onMessageLongPress = { message ->
                    val (baseName, _) = splitSuffix(message.sender)
                    selectedUserForSheet = baseName
                    selectedMessageForSheet = message
                    showUserSheet = true
                },
                onCancelTransfer = { msg ->
                    viewModel.cancelMediaSend(msg.id)
                },
                onImageClick = { currentPath, allImagePaths, initialIndex ->
                    viewerImagePaths = allImagePaths
                    initialViewerIndex = initialIndex
                    showFullScreenImageViewer = true
                }
            )
            
            // Input area - stays at bottom with file share bridge and Monero integration
            // Bridge file share from lower-level input to ViewModel
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.bitchat.android.ui.events.FileShareDispatcher.setHandler { peer, channel, path ->
                    viewModel.sendFileNote(peer, channel, path)
                }
            }

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
                onSendVoiceNote = { peer, onionOrChannel, path ->
                    viewModel.sendVoiceNote(peer, onionOrChannel, path)
                },
                onSendImageNote = { peer, onionOrChannel, path ->
                    viewModel.sendImageNote(peer, onionOrChannel, path)
                },
                onSendFileNote = { peer, onionOrChannel, path ->
                    viewModel.sendFileNote(peer, onionOrChannel, path)
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
                selectedPrivatePeer = null,
                currentChannel = currentChannel,
                nickname = nickname,
                colorScheme = colorScheme,
                showMediaButtons = showMediaButtons,
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
            selectedPrivatePeer = null,
            currentChannel = currentChannel,
            nickname = nickname,
            viewModel = viewModel,
            colorScheme = colorScheme,
            onSidebarToggle = { viewModel.showMeshPeerList() },
            onShowAppInfo = { viewModel.showAppInfo() },
            onPanicClear = { viewModel.panicClearAllData() },
            onLocationChannelsClick = { showLocationChannelsSheet = true },
            onLocationNotesClick = { showLocationNotesSheet = true },
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

        // Scroll-to-bottom floating button
        AnimatedVisibility(
            visible = isScrolledUp,
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
                        contentDescription = stringResource(com.bitchat.android.R.string.cd_scroll_to_bottom),
                        tint = Color(0xFF00C851)
                    )
                }
            }
        }
    }

    // Full-screen image viewer - separate from other sheets to allow image browsing without navigation
    if (showFullScreenImageViewer) {
        FullScreenImageViewer(
            imagePaths = viewerImagePaths,
            initialIndex = initialViewerIndex,
            onClose = { showFullScreenImageViewer = false }
        )
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
        showLocationNotesSheet = showLocationNotesSheet,
        onLocationNotesSheetDismiss = { showLocationNotesSheet = false },
        showUserSheet = showUserSheet,
        onUserSheetDismiss = { 
            showUserSheet = false
            selectedMessageForSheet = null
        },
        selectedUserForSheet = selectedUserForSheet,
        selectedMessageForSheet = selectedMessageForSheet,
        viewModel = viewModel,
        showVerificationSheet = showVerificationSheet,
        onVerificationSheetDismiss = viewModel::hideVerificationSheet,
        showSecurityVerificationSheet = showSecurityVerificationSheet,
        onSecurityVerificationSheetDismiss = viewModel::hideSecurityVerificationSheet,
        showMeshPeerListSheet = showMeshPeerListSheet,
        onMeshPeerListDismiss = viewModel::hideMeshPeerList,
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

    viewModel.sendMessage("Sending $amount XMR...")

    val peerMoneroAddress = peerMoneroAddresses[selectedPrivatePeer]
    if (peerMoneroAddress == null) {
        onError("Peer Monero address not found")
        return
    }

    moneroWalletManager.sendMonero(
        peerMoneroAddress, 
        amount,
        object : MoneroWalletManager.SendCallback {
            override fun onSuccess(txId: String, atomicAmount: BigInteger, fee: BigInteger) {
                val successMessage = "💰 Sent $amount XMR (pending)"
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

// Utility function to split nickname and hash suffix
fun splitSuffix(fullSenderName: String): Pair<String, String> {
    val hashPattern = Regex("(.*)-([a-f0-9]{6})$")
    val match = hashPattern.find(fullSenderName)
    return if (match != null) {
        Pair(match.groupValues[1], "-${match.groupValues[2]}")
    } else {
        Pair(fullSenderName, "")
    }
}

@Composable
fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
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
    showMediaButtons: Boolean,
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
            
            MessageInput(
                value = messageText,
                onValueChange = onMessageTextChange,
                onSend = onSend,
                onSendVoiceNote = onSendVoiceNote,
                onSendImageNote = onSendImageNote,
                onSendFileNote = onSendFileNote,
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                showMediaButtons = showMediaButtons,
                modifier = Modifier.fillMaxWidth(),
                // Monero parameters
                isMoneroModeActive = isMoneroModeActive,
                onMoneroModeToggle = onMoneroModeToggle,
                canReceiveMonero = canReceiveMonero,
                isWalletReady = isWalletReady
            )
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
    onLocationNotesClick: () -> Unit,
    // Monero wallet parameters
    isWalletReady: Boolean,
    currentBalance: String,
    walletStatusMessage: String,
    isSyncing: Boolean,
    syncProgress: Int
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val locationManager = remember { com.bitchat.android.geohash.LocationChannelManager.getInstance(context) }
    
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
                        onLocationChannelsClick = onLocationChannelsClick,
                        onLocationNotesClick = {
                            locationManager.refreshChannels()
                            onLocationNotesClick()
                        }
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

            if (isWalletReady && !isSyncing) {
                Text(
                    text = "Balance: $currentBalance XMR",
                    fontSize = 11.sp,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium
                )
            }

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
private fun ChatHeaderContent(
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onTripleClick: () -> Unit,
    onShowAppInfo: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit
) {
    var clickCount by remember { mutableStateOf(0) }
    val clickTimeoutJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side - Back button and title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Back button (only show when in private chat or specific channel)
            if (selectedPrivatePeer != null || currentChannel != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to main chat"
                    )
                }
            }

            // Title with triple-click handler
            Text(
                text = when {
                    selectedPrivatePeer != null -> "Chat with $selectedPrivatePeer"
                    currentChannel != null -> "#$currentChannel"
                    else -> "BitChat"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable {
                    clickCount++
                    clickTimeoutJob?.cancel()
                    
                    if (clickCount >= 3) {
                        onTripleClick()
                        clickCount = 0
                    } else {
                        scope.launch {
                            kotlinx.coroutines.delay(500)
                            clickCount = 0
                        }
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Right side - Action buttons
        Row {
            // Location channels button
            IconButton(onClick = onLocationChannelsClick) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Location channels"
                )
            }

            // App info button
            IconButton(onClick = onShowAppInfo) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "App info"
                )
            }

            // Sidebar toggle
            IconButton(onClick = onSidebarClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open sidebar"
                )
            }
        }
    }
}

@Composable
private fun CommandSuggestionsBox(
    suggestions: List<CommandSuggestion>,
    onSuggestionClick: (CommandSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(suggestions) { suggestion ->
            Surface(
                onClick = { onSuggestionClick(suggestion) },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.animateItemPlacement()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = suggestion.command,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (suggestion.description.isNotEmpty()) {
                        Text(
                            text = suggestion.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionSuggestionsBox(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(suggestions) { mention ->
            Surface(
                onClick = { onSuggestionClick(mention) },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.animateItemPlacement()
            ) {
                Text(
                    text = "@$mention",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
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
    showLocationNotesSheet: Boolean,
    onLocationNotesSheetDismiss: () -> Unit,
    showUserSheet: Boolean,
    onUserSheetDismiss: () -> Unit,
    selectedUserForSheet: String,
    selectedMessageForSheet: BitchatMessage?,
    viewModel: ChatViewModel,
    showVerificationSheet: Boolean,
    onVerificationSheetDismiss: () -> Unit,
    showSecurityVerificationSheet: Boolean,
    onSecurityVerificationSheetDismiss: () -> Unit,
    showMeshPeerListSheet: Boolean,
    onMeshPeerListDismiss: () -> Unit,
) {
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()

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
    var showDebugSheet by remember { mutableStateOf(false) }
    AboutSheet(
        isPresented = showAppInfo,
        onDismiss = onAppInfoDismiss,
        onShowDebug = { showDebugSheet = true }
    )
    if (showDebugSheet) {
        com.bitchat.android.ui.debug.DebugSettingsSheet(
            isPresented = showDebugSheet,
            onDismiss = { showDebugSheet = false },
            meshService = viewModel.meshService
        )
    }
    
    // Location channels sheet
    LocationChannelsSheet(
        isPresented = showLocationChannelsSheet,
        onDismiss = onLocationChannelsSheetDismiss,
        viewModel = viewModel
    )
    
    // Location notes sheet (extracted to separate presenter)
    if (showLocationNotesSheet) {
        LocationNotesSheetPresenter(
            viewModel = viewModel,
            onDismiss = onLocationNotesSheetDismiss
        )
    }
    
    // User action sheet
    if (showUserSheet) {
        ChatUserSheet(
            isPresented = showUserSheet,
            onDismiss = onUserSheetDismiss,
            targetNickname = selectedUserForSheet,
            selectedMessage = selectedMessageForSheet,
            viewModel = viewModel
        )
    }

    // MeshPeerList sheet (network view)
    if (showMeshPeerListSheet){
        MeshPeerListSheet(
            isPresented = showMeshPeerListSheet,
            viewModel = viewModel,
            onDismiss = onMeshPeerListDismiss,
            onShowVerification = {
                onMeshPeerListDismiss()
                viewModel.showVerificationSheet(fromSidebar = true)
            }
        )
    }

    if (showVerificationSheet) {
        VerificationSheet(
            isPresented = showVerificationSheet,
            onDismiss = onVerificationSheetDismiss,
            viewModel = viewModel
        )
    }

    if (showSecurityVerificationSheet) {
        SecurityVerificationSheet(
            isPresented = showSecurityVerificationSheet,
            onDismiss = onSecurityVerificationSheetDismiss,
            viewModel = viewModel
        )
    }

    if (privateChatSheetPeer != null) {
        PrivateChatSheet(
            isPresented = true,
            peerID = privateChatSheetPeer!!,
            viewModel = viewModel,
            onDismiss = {
                viewModel.hidePrivateChatSheet()
                viewModel.endPrivateChat()
            }
        )
    }
}
