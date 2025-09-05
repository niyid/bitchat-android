package com.bitchat.android.monero.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import androidx.appcompat.widget.AppCompatButton;

/**
 * Dual-mode send button that toggles between message and Monero sending modes
 */
public class MoneroSendButton extends AppCompatButton {
    
    public enum SendMode {
        MESSAGE, MONERO
    }
    
    private SendMode currentMode = SendMode.MESSAGE;
    private OnModeChangeListener modeChangeListener;
    private OnClickListener externalClickListener;
    private GradientDrawable messageBackground;
    private GradientDrawable moneroBackground;
    
    // Colors
    private static final int MESSAGE_COLOR = Color.parseColor("#007AFF");
    private static final int MONERO_COLOR = Color.parseColor("#FF6600");
    private static final int TEXT_COLOR = Color.WHITE;
    
    // Monero symbol
    private static final String MONERO_SYMBOL = "Ɱ";
    private static final String MESSAGE_TEXT = "Send";
    
    public interface OnModeChangeListener {
        void onModeChanged(SendMode newMode);
    }
    
    public MoneroSendButton(Context context) {
        super(context);
        init();
    }
    
    public MoneroSendButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public MoneroSendButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        setupBackgrounds();
        updateButtonAppearance();
        setupClickListener();
    }
    
    private void setupBackgrounds() {
        // Message mode background
        messageBackground = new GradientDrawable();
        messageBackground.setShape(GradientDrawable.RECTANGLE);
        messageBackground.setColor(MESSAGE_COLOR);
        messageBackground.setCornerRadius(8f);
        
        // Monero mode background
        moneroBackground = new GradientDrawable();
        moneroBackground.setShape(GradientDrawable.RECTANGLE);
        moneroBackground.setColor(MONERO_COLOR);
        moneroBackground.setCornerRadius(8f);
    }
    
    private void setupClickListener() {
        super.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                handleSendAction();
            }
        });
        
        setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                toggleMode();
                return true; // Consume the long click
            }
        });
    }
    
    @Override
    public void setOnClickListener(OnClickListener listener) {
        // Store the external click listener instead of setting it directly
        this.externalClickListener = listener;
    }
    
    public void toggleMode() {
        currentMode = (currentMode == SendMode.MESSAGE) 
            ? SendMode.MONERO 
            : SendMode.MESSAGE;
        updateButtonAppearance();
        
        if (modeChangeListener != null) {
            modeChangeListener.onModeChanged(currentMode);
        }
    }
    
    public void setMode(SendMode mode) {
        if (this.currentMode != mode) {
            this.currentMode = mode;
            updateButtonAppearance();
            
            if (modeChangeListener != null) {
                modeChangeListener.onModeChanged(currentMode);
            }
        }
    }
    
    private void updateButtonAppearance() {
        if (currentMode == SendMode.MONERO) {
            setText(MONERO_SYMBOL);
            setBackground(moneroBackground);
            setContentDescription("Send Monero");
        } else {
            setText(MESSAGE_TEXT);
            setBackground(messageBackground);
            setContentDescription("Send Message");
        }
        setTextColor(TEXT_COLOR);
    }
    
    private void handleSendAction() {
        // Call the external click listener if it's set
        if (externalClickListener != null) {
            externalClickListener.onClick(this);
        }
    }
    
    public SendMode getCurrentMode() {
        return currentMode;
    }
    
    public boolean isMoneroMode() {
        return currentMode == SendMode.MONERO;
    }
    
    public boolean isMessageMode() {
        return currentMode == SendMode.MESSAGE;
    }
    
    public void setOnModeChangeListener(OnModeChangeListener listener) {
        this.modeChangeListener = listener;
    }
    
    // Method to reset to message mode (useful when message is sent)
    public void resetToMessageMode() {
        if (currentMode == SendMode.MONERO) {
            setMode(SendMode.MESSAGE);
        }
    }
    
    // Method to force Monero mode (useful when user wants to send Monero)
    public void forceMoneroMode() {
        if (currentMode == SendMode.MESSAGE) {
            setMode(SendMode.MONERO);
        }
    }
    
    // Enable/disable the button based on conditions
    public void updateButtonState(boolean hasText, boolean hasMoneroCapability) {
        if (currentMode == SendMode.MONERO) {
            // For Monero mode, need text (amount) and Monero capability
            setEnabled(hasText && hasMoneroCapability);
        } else {
            // For message mode, just need text
            setEnabled(hasText);
        }
    }
}
