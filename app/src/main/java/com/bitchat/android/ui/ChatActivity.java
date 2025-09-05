package com.bitchat.android.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
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

public class ChatActivity extends AppCompatActivity implements
        MoneroSendButton.OnModeChangeListener,
        MoneroWalletManager.WalletStatusListener,
        MoneroWalletManager.TransactionListener,
        MoneroMessageHandler.MoneroMessageListener {

    private static final String TAG = "ChatActivity";
    private static final String EXTRA_USER_PROFILE = "user_profile";

    private RecyclerView messagesRecyclerView;
    private EditText messageInput;
    private MoneroSendButton sendButton;
    private TextView userNameText;
    private TextView connectionStatusText;
    private TextView balanceText;
    private ProgressBar syncProgressBar;

    private UserProfile chatPartner;
    private MessageAdapter messageAdapter;
    private MoneroWalletManager walletManager;
    private MoneroMessageHandler messageHandler;

    private boolean isMoneroModeActive = false;
    private String currentBalance = "0.000000";
    private boolean isSyncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

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
        syncProgressBar = findViewById(R.id.syncProgressBar);
        
        // Initially hide the progress bar
        syncProgressBar.setVisibility(View.GONE);
    }

    private void setupRecyclerView() {
        messageAdapter = new MessageAdapter(this);
        messagesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        messagesRecyclerView.setAdapter(messageAdapter);
    }

    private void setupSendButton() {
        sendButton.setOnModeChangeListener(this);
        sendButton.setOnClickListener(v -> handleSendButtonClick());
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
        updateInputHint();
    }

    private void initializeMonero() {
        walletManager = MoneroWalletManager.getInstance(this);
        walletManager.setWalletStatusListener(this);
        walletManager.setTransactionListener(this);
        walletManager.initializeWallet();

        messageHandler = new MoneroMessageHandler();
        messageHandler.setMessageListener(this);
    }

    private void updateUI() {
        userNameText.setText(chatPartner.getDisplayName());
        updateConnectionStatus();
        updateBalanceDisplay();
    }

    private void updateConnectionStatus() {
        if (isSyncing) {
            // Don't update connection status while syncing
            return;
        }
        
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
            balanceText.setVisibility(View.VISIBLE);
        } else {
            balanceText.setText("Wallet initializing...");
            balanceText.setVisibility(View.VISIBLE);
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
        if (inputText.isEmpty()) return;

        if (sendButton.isMoneroMode()) {
            handleMoneroSend(inputText);
        } else {
            handleMessageSend(inputText);
        }
    }

    private void handleMessageSend(String message) {
        messageAdapter.addMessage(message, true, System.currentTimeMillis());
        sendBluetoothMessage(message);
        messageInput.setText("");
        messagesRecyclerView.scrollToPosition(messageAdapter.getItemCount() - 1);
    }

    private void handleMoneroSend(String amount) {
        if (!chatPartner.canReceiveMonero()) {
            Toast.makeText(this, "This user cannot receive Monero", Toast.LENGTH_SHORT).show();
            return;
        }
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

        String sendingMessage = "Sending " + amount + " XMR...";
        messageAdapter.addMessage(sendingMessage, true, System.currentTimeMillis());

        walletManager.sendMonero(chatPartner.getMoneroAddress(), amount,
                new MoneroWalletManager.SendCallback() {
                    @Override
                    public void onSuccess(String txId, BigInteger atomicAmount, BigInteger fee) {
                        runOnUiThread(() -> {
                            String successMessage = "💰 Sent " + amount + " XMR (pending)";
                            messageAdapter.updateLastMessage(successMessage);

                            String paymentMessage = MoneroMessageHandler.createPaymentMessage(
                                    amount, txId, chatPartner.getMoneroAddress());
                            sendBluetoothMessage(paymentMessage);

                            messageInput.setText("");
                            sendButton.resetToMessageMode();
                            Toast.makeText(ChatActivity.this, "Payment sent!", Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            String errorMessage = "❌ Failed to send " + amount + " XMR";
                            messageAdapter.updateLastMessage(errorMessage);
                            Toast.makeText(ChatActivity.this,
                                    "Payment failed: " + error, Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void sendBluetoothMessage(String message) {
        // BluetoothService.getInstance().sendMessage(chatPartner.getDeviceId(), message);
    }

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

    @Override
    public void onWalletInitialized(boolean success, String message) {
        runOnUiThread(() -> {
            if (success) {
                updateBalanceDisplay();
                updateSendButtonState();
                walletManager.getBalance(new MoneroWalletManager.BalanceCallback() {
                    @Override
                    public void onSuccess(BigInteger balance, BigInteger unlockedBalance) {
                        currentBalance = MoneroWalletManager.convertAtomicToXmr(unlockedBalance);
                        runOnUiThread(() -> updateBalanceDisplay());
                    }
                    @Override
                    public void onError(String error) {}
                });
            } else {
                Toast.makeText(this,
                        "Wallet initialization failed: " + message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onBalanceUpdated(BigInteger balance, BigInteger unlockedBalance) {
        currentBalance = MoneroWalletManager.convertAtomicToXmr(unlockedBalance);
        runOnUiThread(this::updateBalanceDisplay);
    }

    @Override
    public void onSyncProgress(long height, long startHeight, long targetHeight, double percentDone) {
        runOnUiThread(() -> {
            isSyncing = (percentDone < 1.0);
            
            if (isSyncing) {
                // Show sync progress
                syncProgressBar.setVisibility(View.VISIBLE);
                int progress = (int) (percentDone * 100);
                syncProgressBar.setProgress(progress);
                
                String statusText = "Syncing: " + progress + "% (" + height + "/" + targetHeight + ")";
                connectionStatusText.setText(statusText);
                connectionStatusText.setTextColor(getColor(android.R.color.holo_blue_dark));
            } else {
                // Sync complete
                syncProgressBar.setVisibility(View.GONE);
                updateConnectionStatus();
                Toast.makeText(ChatActivity.this, "Wallet synchronized", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onTransactionCreated(String txId, BigInteger amount) {}

    @Override
    public void onTransactionConfirmed(String txId) {
        runOnUiThread(() -> {
            messageAdapter.updateTransactionStatus(txId, "confirmed");
            String statusMessage =
                    MoneroMessageHandler.createPaymentStatusMessage(txId, "confirmed");
            sendBluetoothMessage(statusMessage);
        });
    }

    @Override
    public void onTransactionFailed(String txId, String error) {
        runOnUiThread(() -> {
            messageAdapter.updateTransactionStatus(txId, "failed");
            Toast.makeText(this,
                    "Transaction failed: " + error,
                    Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onOutputReceived(BigInteger amount, String txId, boolean confirmed) {
        runOnUiThread(() -> {
            String msg = "💰 Output received: " +
                    MoneroWalletManager.convertAtomicToXmr(amount) +
                    " XMR (tx: " + txId + ")";
            messageAdapter.addMessage(msg, false, System.currentTimeMillis());
        });
    }

    @Override
    public void onPaymentReceived(MoneroMessageHandler.MoneroPaymentMessage payment) {
        runOnUiThread(() -> {
            String message = "💰 Received " + payment.getAmount() +
                    " XMR from " + payment.getFromUser();
            messageAdapter.addMessage(message, false, payment.getTimestamp());
            messagesRecyclerView.scrollToPosition(messageAdapter.getItemCount() - 1);
        });
    }

    @Override
    public void onAddressShared(String address, String fromUser) {}

    @Override
    public void onPaymentRequested(MoneroMessageHandler.MoneroPaymentRequest request) {
        runOnUiThread(() -> {
            String message = "💳 " + request.getFromUser() + " requested " +
                    request.getAmount() + " XMR";
            if (!request.getReason().isEmpty()) {
                message += " - " + request.getReason();
            }
            messageAdapter.addMessage(message, false, request.getTimestamp());
            messagesRecyclerView.scrollToPosition(messageAdapter.getItemCount() - 1);
        });
    }

    @Override
    public void onPaymentStatusUpdated(String txId, String status) {
        runOnUiThread(() -> messageAdapter.updateTransactionStatus(txId, status));
    }

    public void onBluetoothMessageReceived(String message, String fromUser) {
        if (messageHandler.handleMessage(message, fromUser)) return;
        runOnUiThread(() -> {
            messageAdapter.addMessage(message, false, System.currentTimeMillis());
            messagesRecyclerView.scrollToPosition(messageAdapter.getItemCount() - 1);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (walletManager != null && walletManager.isReady()) {
            walletManager.getBalance(new MoneroWalletManager.BalanceCallback() {
                @Override
                public void onSuccess(BigInteger balance, BigInteger unlockedBalance) {
                    currentBalance = MoneroWalletManager.convertAtomicToXmr(unlockedBalance);
                    runOnUiThread(() -> updateBalanceDisplay());
                }
                @Override
                public void onError(String error) {}
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (walletManager != null) {
            walletManager.setWalletStatusListener(null);
            walletManager.setTransactionListener(null);
        }
    }
}
