package com.bitchat.android.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bitchat.android.R;
import com.bitchat.android.model.UserProfile;
import com.bitchat.android.monero.wallet.MoneroWalletManager;
import com.bitchat.android.monero.messaging.MoneroMessageHandler;
import com.bitchat.android.monero.ui.MoneroSendButton;
import com.bitchat.android.ui.adapters.MessageAdapter;

import java.math.BigInteger;

/**
 * Modified ChatActivity with Monero integration
 * Supports dual-mode send button and Monero payments
 */
public class ChatActivity extends AppCompatActivity implements 
    MoneroSendButton.OnModeChangeListener,
    MoneroWalletManager.WalletStatusListener,
    MoneroWalletManager.TransactionListener,
    MoneroMessageHandler.MoneroMessageListener {
    
    private static final String TAG = "ChatActivity";
    private static final String EXTRA_USER_PROFILE = "user_profile";
    
    // UI Components
    private RecyclerView messagesRecyclerView;
    private EditText messageInput;
    private MoneroSendButton sendButton;
    private TextView userNameText;
    private TextView connectionStatusText;
    private TextView balanceText;
    
    // Data
    private UserProfile chatPartner;
    private MessageAdapter messageAdapter;
    private MoneroWalletManager walletManager;
    private MoneroMessageHandler messageHandler;
    
    // State
    private boolean isMoneroModeActive = false;
    private String currentBalance = "0.000000";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        
        // Get chat partner from intent
        chatPartner = (UserProfile) getIntent().getSerializableExtra(EXTRA_USER_PROFILE);
        if (chatPartner == null) {
            finish();
            return;
        }
        
        initializeViews();
        setupRecyclerView();
        setupSendButton();
        setupMessageInput();
        initializeMonero();
        updateUI();
    }
    
    private void initializeViews() {
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        userNameText = findViewById(R.id.userNameText);
        connectionStatusText = findViewById(R.id.connectionStatusText);
        balanceText = findViewById(R.id.balanceText);
    }
    
    private void setupRecyclerView() {
        messageAdapter = new MessageAdapter(this);
        messagesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        messagesRecyclerView.setAdapter(messageAdapter);
    }
    
    private void setupSendButton() {
        sendButton.setOnModeChangeListener(this);
        sendButton.setOnClickListener(v -> handleSendButtonClick());
        
        // Initially disabled until we have text or Monero is ready
        sendButton.setEnabled(false);
    }
    
    private void setupMessageInput() {
        messageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSendButtonState();
            }
            
            @Override
            public void afterTextChanged(Editable s) {}
        });
        
        // Update hint based on mode
        updateInputHint();
    }
    
    private void initializeMonero() {
        // Initialize wallet manager
        walletManager = MoneroWalletManager.getInstance(this);
        walletManager.setWalletStatusListener(this);
        walletManager.setTransactionListener(this);
        walletManager.initializeWallet();
        
        // Initialize message handler
        messageHandler = new MoneroMessageHandler();
        messageHandler.setMessageListener(this);
    }
    
    private void updateUI() {
        // Set user name with Monero indicator
        userNameText.setText(chatPartner.getDisplayName());
        
        // Set connection status
        updateConnectionStatus();
        
        // Update balance display
        updateBalanceDisplay();
    }
    
    private void updateConnectionStatus() {
        if (chatPartner.isOnline()) {
            connectionStatusText.setText("Connected");
            connectionStatusText.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            connectionStatusText.setText("Connecting...");
            connectionStatusText.setTextColor(getColor(android.R.color.holo_orange_dark));
        }
    }
    
    private void updateBalanceDisplay() {
        if (walletManager != null && walletManager.isReady()) {
            balanceText.setText("Balance: " + currentBalance + " XMR");
            balanceText.setVisibility(android.view.View.VISIBLE);
        } else {
            balanceText.setText("Wallet initializing...");
            balanceText.setVisibility(android.view.View.VISIBLE);
        }
    }
    
    private void updateInputHint() {
        if (isMoneroModeActive) {
            messageInput.setHint("Enter amount (XMR)");
        } else {
            messageInput.setHint("Type a message...");
        }
    }
    
    private void updateSendButtonState() {
        boolean hasText = messageInput.getText().toString().trim().length() > 0;
        boolean canSendMonero = chatPartner.canReceiveMonero();
        
        sendButton.updateButtonState(hasText, canSendMonero);
    }
    
    private void handleSendButtonClick() {
        String inputText = messageInput.getText().toString().trim();
        if (inputText.isEmpty()) {
            return;
        }
        
        if (sendButton.isMoneroMode()) {
            handleMoneroSend(inputText);
        } else {
            handleMessageSend(inputText);
        }
    }
    
    private void handleMessageSend(String message) {
        // Add message to adapter
        messageAdapter.addMessage(message, true, System.currentTimeMillis());
        
        // Send via Bluetooth (integrate with existing BitChat sending logic)
        sendBluetoothMessage(message);
        
        // Clear input
        messageInput.setText("");
        
        // Scroll to bottom
        messagesRecyclerView.scrollToPosition(messageAdapter.getItemCount() - 1);
    }
    
    private void handleMoneroSend(String amount) {
        if (!chatPartner.canReceiveMonero()) {
            Toast.makeText(this, "This user cannot receive Monero", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Validate amount
        try {
            double amountValue = Double.parseDouble(amount);
            if (amountValue <= 0) {
                Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Show sending status
        String sendingMessage = "Sending " + amount + " XMR...";
        messageAdapter.addMessage(sendingMessage, true, System.currentTimeMillis());
        
        // Send Monero payment
        walletManager.sendMonero(chatPartner.getMoneroAddress(), amount, new MoneroWalletManager.SendCallback() {
            @Override
            public void onSuccess(String txId, BigInteger atomicAmount) {
                runOnUiThread(() -> {
                    // Update the message to show success
                    String successMessage = "💰 Sent " + amount + " XMR (pending)";
                    messageAdapter.updateLastMessage(successMessage);
                    
                    // Send payment notification via Bluetooth
                    String paymentMessage = MoneroMessageHandler.createPaymentMessage(
                        amount, txId, chatPartner.getMoneroAddress());
                    sendBluetoothMessage(paymentMessage);
                    
                    // Clear input and reset to message mode
                    messageInput.setText("");
                    sendButton.resetToMessageMode();
                    
                    Toast.makeText(ChatActivity.this, "Payment sent!", Toast.LENGTH_SHORT).show();
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    // Update message to show error
                    String errorMessage = "❌ Failed to send " + amount + " XMR";
                    messageAdapter.updateLastMessage(errorMessage);
                    
                    Toast.makeText(ChatActivity.this, "Payment failed: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    private void sendBluetoothMessage(String message) {
        // Integrate with existing BitChat Bluetooth sending logic
        // This would call your existing BluetoothService
        // BluetoothService.getInstance().sendMessage(chatPartner.getDeviceId(), message);
    }
    
    // MoneroSendButton.OnModeChangeListener
    @Override
    public void onModeChanged(MoneroSendButton.SendMode newMode) {
        isMoneroModeActive = (newMode == MoneroSendButton.SendMode.MONERO);
        updateInputHint();
        updateSendButtonState();
        
        if (isMoneroModeActive && !chatPartner.canReceiveMonero()) {
            Toast.makeText(this, "This user cannot receive Monero", Toast.LENGTH_SHORT).show();
            sendButton.resetToMessageMode();
        }
    }
    
    // MoneroWalletManager.WalletStatusListener
    @Override
    public void onWalletInitialized(boolean success, String message) {
        runOnUiThread(() -> {
            if (success) {
                updateBalanceDisplay();
                updateSendButtonState();
                
                // Get initial balance
                walletManager.getBalance(new MoneroWalletManager.BalanceCallback() {
                    @Override
                    public void onSuccess(BigInteger balance, BigInteger unlockedBalance) {
                        currentBalance = MoneroWalletManager.convertAtomicToXmr(unlockedBalance);
                        runOnUiThread(() -> updateBalanceDisplay());
                    }
                    
                    @Override
                    public void onError(String error) {
                        // Handle error silently for now
                    }
                });
            } else {
                Toast.makeText(this, "Wallet initialization failed: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }
    
    @Override
    public void onBalanceUpdated(BigInteger balance, BigInteger unlockedBalance) {
        currentBalance = MoneroWalletManager.convertAtomicToXmr(unlockedBalance);
        runOnUiThread(() -> updateBalanceDisplay());
    }
    
    @Override
    public void onSyncProgress(long height, long targetHeight) {
        // Update sync progress if needed
    }
    
    // MoneroWalletManager.TransactionListener
    @Override
    public void onTransactionCreated(String txId, BigInteger amount) {
        runOnUiThread(() -> {
            // Update UI to show transaction created
        });
    }
    
    @Override
    public void onTransactionConfirmed(String txId) {
        runOnUiThread(() -> {
            // Update message status to confirmed
            messageAdapter.updateTransactionStatus(txId, "confirmed");
            
            // Send status update to chat partner
            String statusMessage = MoneroMessageHandler.createPaymentStatusMessage(txId, "confirmed");
            sendBluetoothMessage(statusMessage);
        });
    }
    
    @Override
    public void onTransactionFailed(String txId, String error) {
        runOnUiThread(() -> {
            // Update message status to failed
            messageAdapter.updateTransactionStatus(txId, "failed");
            Toast.makeText(this, "Transaction failed: " + error, Toast.LENGTH_LONG).show();
        });
    }
    
    // MoneroMessageHandler.MoneroMessageListener
    @Override
    public void onPaymentReceived(MoneroMessageHandler.MoneroPaymentMessage payment) {
        runOnUiThread(() -> {
            String message = "💰 Received " + payment.getAmount() + " XMR from " + payment.getFromUser();
            messageAdapter.addMessage(message, false, payment.getTimestamp());
            messagesRecyclerView.scrollToPosition(messageAdapter.getItemCount() - 1);
        });
    }
    
    @Override
    public void onAddressShared(String address, String fromUser) {
        // Handle address sharing if needed
    }
    
    @Override
    public void onPaymentRequested(MoneroMessageHandler.MoneroPaymentRequest request) {
        runOnUiThread(() -> {
            String message = "💳 " + request.getFromUser() + " requested " + request.getAmount() + " XMR";
            if (!request.getReason().isEmpty()) {
                message += " - " + request.getReason();
            }
            messageAdapter.addMessage(message, false, request.getTimestamp());
            messagesRecyclerView.scrollToPosition(messageAdapter.getItemCount() - 1);
        });
    }
    
    @Override
    public void onPaymentStatusUpdated(String txId, String status) {
        runOnUiThread(() -> {
            messageAdapter.updateTransactionStatus(txId, status);
        });
    }
    
    // Handle incoming messages from Bluetooth
    public void onBluetoothMessageReceived(String message, String fromUser) {
        // Check if it's a Monero message
        if (messageHandler.handleMessage(message, fromUser)) {
            // Was handled as Monero message
            return;
        }
        
        // Regular text message
        runOnUiThread(() -> {
            messageAdapter.addMessage(message, false, System.currentTimeMillis());
            messagesRecyclerView.scrollToPosition(messageAdapter.getItemCount() - 1);
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Refresh balance when returning to chat
        if (walletManager != null && walletManager.isReady()) {
            walletManager.getBalance(new MoneroWalletManager.BalanceCallback() {
                @Override
                public void onSuccess(BigInteger balance, BigInteger unlockedBalance) {
                    currentBalance = MoneroWalletManager.convertAtomicToXmr(unlockedBalance);
                    runOnUiThread(() -> updateBalanceDisplay());
                }
                
                @Override
                public void onError(String error) {
                    // Handle silently
                }
            });
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up wallet manager listeners
        if (walletManager != null) {
            walletManager.setWalletStatusListener(null);
            walletManager.setTransactionListener(null);
        }
    }
}
