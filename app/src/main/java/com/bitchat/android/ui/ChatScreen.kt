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
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.bitchat.android.monero.wallet.WalletSuite
import com.bitchat.android.monero.messaging.MoneroMessageHandler
import com.bitchat.android.monero.bluetooth.MoneroChatTransferManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap
import android.util.Log

import com.m2049r.xmrwallet.model.TransactionInfo

import androidx.compose.runtime.rememberCoroutineScope

//import com.bitchat.android.ui.TransactionSearchDialog
//import com.bitchat.android.ui.PendingTransactionsSheet
//import com.bitchat.android.ui.PendingTransactionsIndicator

private const val TAG = "com.bitchat.ChatScreen"

/**
 * Main ChatScreen - REFACTORED to use component-based architecture with Monero integration
 * Modified to properly share wallet addresses only during private chat sessions
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
    val walletSuite = viewModel.walletSuite
    val moneroMessageHandler = viewModel.moneroMessageHandler
    val isMoneroModeActive = viewModel.isMoneroModeActive
    val currentBalance = viewModel.currentBalance
    val isSyncing = viewModel.isSyncing
    val syncProgress = viewModel.syncProgress
    val walletStatusMessage = viewModel.walletStatusMessage
    val isWalletReady = viewModel.isWalletReady
    val peerMoneroAddresses by viewModel.peerMoneroAddresses
    val moneroChatTransferManager = viewModel.moneroChatTransferManager
    val myWalletAddress by viewModel.myWalletAddress.observeAsState()
    val showDaemonConfigDialog = viewModel.showDaemonConfigDialog
    val daemonConfigLoading = viewModel.daemonConfigLoading    
    
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
    
    var showTransactionSearchDialog by remember { mutableStateOf(false) }
    var showPendingTransactionsSheet by remember { mutableStateOf(false) }
    val pendingTransactions by viewModel.pendingTransactionSearches.observeAsState(emptySet())
    
    val scope = rememberCoroutineScope()
    
    fun sendMoneroTransaction(
        amount: Double,
        peer: String,
        address: String
    ) { 
        Log.d(TAG, "=== MONERO SEND INITIATED ===")
        Log.d(TAG, "Amount to send: $amount")
        Log.d(TAG, "Selected peer: $peer")
        Log.d(TAG, "Receiver address: $address")
        
        viewModel.addSystemMessage("⏳ Preparing transaction of $amount XMR...")
        
        // Get cached balances from ViewModel
        val cachedBalance = viewModel.getCachedBalance()
        val cachedUnlocked = viewModel.getCachedUnlockedBalance()
        
        Log.d(TAG, "Using cached balance: ${WalletSuite.convertAtomicToXmr(cachedBalance)} XMR")
        Log.d(TAG, "Using cached unlocked: ${WalletSuite.convertAtomicToXmr(cachedUnlocked)} XMR")
        
        // Direct call to WalletSuite with cached balances
        walletSuite?.sendTransaction(
            address,
            amount,
            cachedBalance,      // Pass cached balance
            cachedUnlocked,     // Pass cached unlocked balance
            object : WalletSuite.TransactionCallback {
                override fun onSuccess(txId: String, sentAmount: Long) {
                    val sentAmountXmr = WalletSuite.convertAtomicToXmr(sentAmount)
                    Log.i(TAG, "=== MONERO SEND SUCCESSFUL ===")
                    Log.i(TAG, "Transaction ID: $txId")
                    Log.i(TAG, "Amount sent: $sentAmountXmr XMR")
                    
                    viewModel.addSystemMessage("✅ Sent $sentAmountXmr XMR successfully")
                    viewModel.addSystemMessage("🔍 Transaction ID: ${txId.take(16)}...")
                    
                    // Share transaction ID with peer via direct message
                    val txMessage = "[TX_ID:$txId]"
                    viewModel.sendDirectMessage(peer, txMessage)
                }

                override fun onError(error: String) {
                    Log.e(TAG, "=== MONERO SEND FAILED ===")
                    Log.e(TAG, "Error: $error")
                    
                    viewModel.addSystemMessage("❌ Payment failed: $error")
                }
            }
        )
    }      

    // Initialize Monero components - SIMPLIFIED: Let WalletSuite handle initialization
    LaunchedEffect(Unit) {
        viewModel.initializeWalletSuite(context, object : WalletSuite.WalletStatusListener {
            override fun onWalletInitialized(success: Boolean, message: String) {
                viewModel.updateWalletReadyState(success)
                Log.d(TAG, "Wallet init result: $message")
                if (success) {
                    viewModel.updateWalletStatusMessage("Wallet ready")
                    // Get wallet address when ready
                    viewModel.walletSuite?.getAddress(object : WalletSuite.AddressCallback {
                        override fun onSuccess(address: String) {
                            Log.d(TAG, "Wallet address retrieved: $address")
                            viewModel.updateMyWalletAddress(address)
                        }
                        override fun onError(error: String) {
                            Log.e(TAG, "Failed to get wallet address: $error")
                            viewModel.updateWalletStatusMessage("Address error: $error")
                        }
                    })
                    // Get balance when wallet is ready
                    viewModel.walletSuite?.getBalance(object : WalletSuite.BalanceCallback {
                        override fun onSuccess(balance: Long, unlockedBalance: Long) {
                            Log.d(TAG, "Balance loaded after init")
                            viewModel.updateCurrentBalance(WalletSuite.convertAtomicToXmr(unlockedBalance))
                        }
                        override fun onError(error: String) {
                            viewModel.updateWalletStatusMessage("Balance error: $error")
                        }
                    })
                } else {
                    viewModel.updateWalletStatusMessage("Wallet failed: $message")
                    // No manual retry - WalletSuite handles its own retry logic
                }
            }
            override fun onBalanceUpdated(balance: Long, unlockedBalance: Long) {
                Log.d(TAG, "BALANCE: balance: $balance| unlockedBalance: $unlockedBalance")
                
                // Use setter methods instead of direct access
                viewModel.updateCachedBalance(balance)
                viewModel.updateCachedUnlockedBalance(unlockedBalance)
                
                viewModel.updateCurrentBalance(WalletSuite.convertAtomicToXmr(unlockedBalance))
            }
            override fun onSyncProgress(
                height: Long,
                startHeight: Long,
                targetHeight: Long,
                percentDone: Double
            ) {
                val syncing = percentDone < 100.0
                val progress = percentDone.toInt()
                viewModel.updateSyncState(syncing, progress)
                if (syncing) {
                    viewModel.updateWalletStatusMessage("Syncing: $progress% ($height/$targetHeight)")
                } else {
                    viewModel.updateWalletStatusMessage("Wallet synchronized")
                }
            }
        })
        
        // Set daemon config callback
        viewModel.walletSuite?.setDaemonConfigCallback(object : WalletSuite.DaemonConfigCallback {
            override fun onConfigNeeded() {
                Log.d(TAG, "Daemon configuration needed - showing dialog")
                viewModel.showDaemonConfigDialog()
            }
            
            override fun onConfigError(error: String) {
                Log.e(TAG, "Daemon config error: $error")
                viewModel.addSystemMessage("⚠️ Daemon error: $error")
            }
        })
        
        MoneroMessageHandler.initializeMoneroMessageHandler(object : MoneroMessageHandler.MoneroMessageListener {
            override fun onPaymentReceived(payment: MoneroMessageHandler.MoneroPaymentMessage) {
                val paymentMessage = "💰 Received ${payment.amount} XMR from ${payment.fromUser}"
                viewModel.addSystemMessage(paymentMessage)
            }
            override fun onAddressShared(address: String, fromUser: String) {
                viewModel.addPeerMoneroAddress(fromUser, address)
                viewModel.addSystemMessage("🔗 $fromUser shared Monero address")
            }
            override fun onPaymentRequested(request: MoneroMessageHandler.MoneroPaymentRequest) {
                val reason = if (request.reason.isNotEmpty()) " - ${request.reason}" else ""
                val requestMessage = "💳 ${request.fromUser} requested ${request.amount} XMR$reason"
                viewModel.addSystemMessage(requestMessage)
            }
            override fun onPaymentStatusUpdated(txId: String, status: String) {
                viewModel.updateTransactionStatus(txId, status)
            }
            override fun onTransactionIdReceived(txIdMessage: MoneroMessageHandler.TransactionIdMessage) {
                viewModel.addSystemMessage("📨 Transaction ID received: ${txIdMessage.txId}")
            }

            override fun onTransactionSearchRequested(request: MoneroMessageHandler.SearchTransactionRequest) {
                viewModel.addSystemMessage("🔍 Transaction search requested for: ${request.txId}")
            }
        })
    }

    // Enhanced address sharing with better logging
    LaunchedEffect(selectedPrivatePeer, isWalletReady, myWalletAddress) {
        Log.d(TAG, "Address sharing check: peer=$selectedPrivatePeer, ready=$isWalletReady, address=${myWalletAddress != null}")
        
        if (selectedPrivatePeer != null && isWalletReady && myWalletAddress != null) {
            val peer = selectedPrivatePeer!!
            val alreadySent = viewModel.moneroAddressSentTo.contains(peer)
            val hasTheirAddress = peerMoneroAddresses.containsKey(peer)
            
            Log.d(TAG, "Sharing conditions: alreadySent=$alreadySent, hasTheirAddress=$hasTheirAddress")
            
            if (!alreadySent) {
                Log.d(TAG, "Sharing wallet address with private chat peer: $peer")
                viewModel.shareMoneroAddressWithPeer(peer, myWalletAddress!!)
            }
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
                },
                walletSuite = walletSuite,   
                moneroChatTransferManager = moneroChatTransferManager,
                viewModel = viewModel                        
            )
            
            val canReceiveMonero = selectedPrivatePeer != null && 
                       isWalletReady && 
                       peerMoneroAddresses.containsKey(selectedPrivatePeer)

            SideEffect {
                Log.d(TAG, "canReceiveMonero=$canReceiveMonero for peer=$selectedPrivatePeer")
            }
            
            Log.d(TAG, "selectedPrivatePeer=$selectedPrivatePeer")
            Log.d(TAG, "isWalletReady=$isWalletReady")
            Log.d(TAG, "peerMoneroAddresses=$peerMoneroAddresses")
            Log.d(TAG, "peerMoneroAddresses has selectedPrivatePeer=${peerMoneroAddresses.containsKey(selectedPrivatePeer)}")
                        
            // Input area - stays at bottom with Monero integration
            ChatInputSection(
                messageText = messageText,
                onMessageTextChange = { newText: TextFieldValue ->
                    messageText = newText
                    viewModel.updateCommandSuggestions(newText.text)
                    viewModel.updateMentionSuggestions(newText.text)
                },
                onSend = {
                    Log.d(TAG, "Sending message/Monero now...")
                    if (messageText.text.trim().isNotEmpty()) {
                        if (isMoneroModeActive) {
                            // Handle Monero send - SIMPLIFIED
                            val receiverMoneroAddress = peerMoneroAddresses[selectedPrivatePeer]
                            Log.d(TAG, "Sending Monero to $selectedPrivatePeer with address: $receiverMoneroAddress")
                            
                            sendMoneroTransaction(
                                amount = messageText.text.toDoubleOrNull() ?: 0.0,
                                peer = selectedPrivatePeer!!,
                                address = receiverMoneroAddress!!
                            )
                            
                            messageText = TextFieldValue("")
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
                        viewModel.isMoneroModeActive = !isMoneroModeActive
                    }
                },
                canReceiveMonero = canReceiveMonero,
                isWalletReady = isWalletReady
            )
        }
        
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
            onTransactionSearchClick = { showTransactionSearchDialog = true },
            onPendingTransactionsClick = { showPendingTransactionsSheet = true },
            isWalletReady = isWalletReady,
            currentBalance = currentBalance,
            walletStatusMessage = walletStatusMessage,
            isSyncing = isSyncing,
            syncProgress = syncProgress,
            pendingCount = pendingTransactions.size
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
        showDaemonConfigDialog = showDaemonConfigDialog,
        daemonConfigLoading = daemonConfigLoading,
        onDaemonConfigDismiss = { viewModel.hideDaemonConfigDialog() },
        onDaemonConfigSave = { config -> viewModel.saveDaemonConfigAndReconnect(config) },
        viewModel = viewModel
    )
    
    TransactionSearchDialog(
        isVisible = showTransactionSearchDialog,
        onDismiss = { showTransactionSearchDialog = false },
        onSearch = { txId ->
            viewModel.searchForMissingTransaction(txId)
        }
    )

    PendingTransactionsSheet(
        isPresented = showPendingTransactionsSheet,
        onDismiss = { showPendingTransactionsSheet = false },
        pendingTransactions = pendingTransactions,
        onRetryAll = { viewModel.retryPendingTransactionSearches() },
        onRetryOne = { txId -> viewModel.searchForMissingTransaction(txId) },
        onClearOne = { txId -> viewModel.clearPendingTransaction(txId) }
    )    
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
                            imageVector = if (isMoneroModeActive) Icons.Default.CurrencyExchange else Icons.AutoMirrored.Filled.Send,
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
    onTransactionSearchClick: () -> Unit,  
    onPendingTransactionsClick: () -> Unit,  
    // Monero wallet parameters
    isWalletReady: Boolean,
    currentBalance: String,
    walletStatusMessage: String,
    isSyncing: Boolean,
    syncProgress: Int,
    pendingCount: Int  
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
                        onLocationChannelsClick = onLocationChannelsClick,
                        onTransactionSearchClick = onTransactionSearchClick,  
                        onPendingTransactionsClick = onPendingTransactionsClick,  
                        isWalletReady = isWalletReady,  
                        pendingCount = pendingCount  
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
                    colorScheme = colorScheme,
                    onDaemonConfigClick = { viewModel.showDaemonConfigDialog() }
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
    colorScheme: ColorScheme,
    onDaemonConfigClick: () -> Unit,
    pendingCount: Int = 0,             
    onPendingClick: () -> Unit = {} 
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
            // Balance display
            if (isWalletReady) {
                Text(
                    text = if (isSyncing) {
                        "Balance (syncing...): $currentBalance XMR"
                    } else {
                        "Balance: $currentBalance XMR"
                    },
                    fontSize = 11.sp,
                    color = if (isSyncing) Color(0xFF2196F3) else Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium
                )
            } else {
                // Show daemon config button when wallet is not ready
                Surface(
                    onClick = onDaemonConfigClick,
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFFF9800).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "Configure",
                        fontSize = 10.sp,
                        color = Color(0xFFFF9800),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            // Sync progress
            if (isSyncing) {
                Text(
                    text = "$syncProgress%",
                    fontSize = 10.sp,
                    color = Color(0xFF2196F3),
                    fontWeight = FontWeight.Bold
                )
            }
            
            if (pendingCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                PendingTransactionsIndicator(
                    pendingCount = pendingCount,
                    onClick = onPendingClick  // Remove the () - it's already a lambda
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
                    "Peer-to-peer payments",
                    "Automatic address sharing for seamless payments"
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
    showDaemonConfigDialog: Boolean,
    daemonConfigLoading: Boolean,
    onDaemonConfigDismiss: () -> Unit,
    onDaemonConfigSave: (DaemonConfig) -> Unit,
    viewModel: ChatViewModel
) {
    val context = LocalContext.current
    DaemonConfigDialog(
        isVisible = showDaemonConfigDialog,
        initialConfig = loadDaemonConfig(context),
        onDismiss = onDaemonConfigDismiss,
        onSave = onDaemonConfigSave,
        isLoading = daemonConfigLoading
    )
    
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

