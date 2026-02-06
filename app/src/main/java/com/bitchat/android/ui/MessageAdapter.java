package com.bitchat.android.ui.adapters;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bitchat.android.R;
import com.bitchat.android.model.Message;
import com.bitchat.android.monero.messaging.MoneroMessageHandler;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enhanced MessageAdapter with support for Monero messages
 * Displays regular messages, Monero payments, and transaction status updates
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {
    
    private static final int TYPE_MESSAGE_SENT = 1;
    private static final int TYPE_MESSAGE_RECEIVED = 2;
    private static final int TYPE_MONERO_SENT = 3;
    private static final int TYPE_MONERO_RECEIVED = 4;
    private static final int TYPE_SYSTEM_MESSAGE = 5;
    
    private Context context;
    private List<Message> messages;
    private Map<String, Integer> transactionMessagePositions; // Track TX positions for updates
    private SimpleDateFormat timeFormat;
    
    public MessageAdapter(Context context) {
        this.context = context;
        this.messages = new ArrayList<>();
        this.transactionMessagePositions = new HashMap<>();
        this.timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    }
    
    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        
        switch (viewType) {
            case TYPE_MESSAGE_SENT:
                return new MessageViewHolder(inflater.inflate(R.layout.item_message_sent, parent, false));
            case TYPE_MESSAGE_RECEIVED:
                return new MessageViewHolder(inflater.inflate(R.layout.item_message_received, parent, false));
            case TYPE_MONERO_SENT:
                return new MessageViewHolder(inflater.inflate(R.layout.item_monero_sent, parent, false));
            case TYPE_MONERO_RECEIVED:
                return new MessageViewHolder(inflater.inflate(R.layout.item_monero_received, parent, false));
            case TYPE_SYSTEM_MESSAGE:
                return new MessageViewHolder(inflater.inflate(R.layout.item_system_message, parent, false));
            default:
                return new MessageViewHolder(inflater.inflate(R.layout.item_message_received, parent, false));
        }
    }
    
    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        
        switch (getItemViewType(position)) {
            case TYPE_MESSAGE_SENT:
            case TYPE_MESSAGE_RECEIVED:
                bindRegularMessage(holder, message);
                break;
            case TYPE_MONERO_SENT:
            case TYPE_MONERO_RECEIVED:
                bindMoneroMessage(holder, message);
                break;
            case TYPE_SYSTEM_MESSAGE:
                bindSystemMessage(holder, message);
                break;
        }
    }
    
    private void bindRegularMessage(MessageViewHolder holder, Message message) {
        holder.messageText.setText(message.getContent());
        holder.timeText.setText(timeFormat.format(new Date(message.getTimestamp())));
        
        // Set delivery status for sent messages
        if (message.isSent() && holder.statusIcon != null) {
            switch (message.getDeliveryStatus()) {
                case Message.STATUS_PENDING:
                    holder.statusIcon.setImageResource(R.drawable.ic_access_time);
                    holder.statusIcon.setColorFilter(Color.GRAY);
                    break;
                case Message.STATUS_DELIVERED:
                    holder.statusIcon.setImageResource(R.drawable.ic_done);
                    holder.statusIcon.setColorFilter(Color.GRAY);
                    break;
                case Message.STATUS_READ:
                    holder.statusIcon.setImageResource(R.drawable.ic_done_all);
                    holder.statusIcon.setColorFilter(Color.BLUE);
                    break;
                case Message.STATUS_FAILED:
                    holder.statusIcon.setImageResource(R.drawable.ic_error);
                    holder.statusIcon.setColorFilter(Color.RED);
                    break;
            }
        }
    }
    
    private void bindMoneroMessage(MessageViewHolder holder, Message message) {
        // Parse Monero message data
        String displayText = MoneroMessageHandler.getDisplayText(message.getContent());
        holder.messageText.setText(displayText);
        holder.timeText.setText(timeFormat.format(new Date(message.getTimestamp())));
        
        // Set Monero icon
        if (holder.moneroIcon != null) {
            holder.moneroIcon.setVisibility(View.VISIBLE);
            holder.moneroIcon.setImageResource(R.drawable.ic_monero);
        }
        
        // Set amount text if available
        if (holder.amountText != null) {
            String amount = extractAmount(message.getContent());
            if (amount != null) {
                holder.amountText.setText(amount + " XMR");
                holder.amountText.setVisibility(View.VISIBLE);
            } else {
                holder.amountText.setVisibility(View.GONE);
            }
        }
        
        // Set transaction status
        if (holder.statusText != null) {
            String status = extractStatus(message.getContent());
            if (status != null) {
                holder.statusText.setText(status.toUpperCase());
                holder.statusText.setVisibility(View.VISIBLE);
                
                // Color code the status
                switch (status.toLowerCase()) {
                    case "pending":
                        holder.statusText.setTextColor(Color.parseColor("#FFA500")); // Orange
                        break;
                    case "confirmed":
                        holder.statusText.setTextColor(Color.parseColor("#4CAF50")); // Green
                        break;
                    case "failed":
                        holder.statusText.setTextColor(Color.parseColor("#F44336")); // Red
                        break;
                    default:
                        holder.statusText.setTextColor(Color.GRAY);
                        break;
                }
            } else {
                holder.statusText.setVisibility(View.GONE);
            }
        }
        
        // Store transaction message position for updates
        String txId = extractTransactionId(message.getContent());
        if (txId != null) {
            transactionMessagePositions.put(txId, holder.getAdapterPosition());
        }
    }
    
    private void bindSystemMessage(MessageViewHolder holder, Message message) {
        holder.messageText.setText(message.getContent());
        holder.timeText.setText(timeFormat.format(new Date(message.getTimestamp())));
        holder.messageText.setTypeface(null, Typeface.ITALIC);
        holder.messageText.setTextColor(Color.GRAY);
    }
    
    @Override
    public int getItemViewType(int position) {
        Message message = messages.get(position);
        
        if (message.isSystemMessage()) {
            return TYPE_SYSTEM_MESSAGE;
        }
        
        boolean isMoneroMessage = MoneroMessageHandler.isMoneroMessage(message.getContent());
        
        if (isMoneroMessage) {
            return message.isSent() ? TYPE_MONERO_SENT : TYPE_MONERO_RECEIVED;
        } else {
            return message.isSent() ? TYPE_MESSAGE_SENT : TYPE_MESSAGE_RECEIVED;
        }
    }
    
    @Override
    public int getItemCount() {
        return messages.size();
    }
    
    /**
     * Add a new message to the list
     */
    public void addMessage(String content, boolean isSent, long timestamp) {
        Message message = new Message(content, isSent, timestamp);
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }
    
    /**
     * Add a message object directly
     */
    public void addMessage(Message message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }
    
    /**
     * Update the last message (useful for updating sending status)
     */
    public void updateLastMessage(String newContent) {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message lastMessage = messages.get(lastIndex);
            lastMessage.setContent(newContent);
            notifyItemChanged(lastIndex);
        }
    }
    
    /**
     * Update transaction status for a specific transaction ID
     */
    public void updateTransactionStatus(String txId, String newStatus) {
        Integer position = transactionMessagePositions.get(txId);
        if (position != null && position < messages.size()) {
            Message message = messages.get(position);
            String updatedContent = updateStatusInContent(message.getContent(), newStatus);
            message.setContent(updatedContent);
            notifyItemChanged(position);
        }
    }
    
    /**
     * Update message delivery status
     */
    public void updateMessageStatus(int position, int status) {
        if (position >= 0 && position < messages.size()) {
            Message message = messages.get(position);
            message.setDeliveryStatus(status);
            notifyItemChanged(position);
        }
    }
    
    /**
     * Clear all messages
     */
    public void clear() {
        messages.clear();
        transactionMessagePositions.clear();
        notifyDataSetChanged();
    }
    
    /**
     * Get all messages
     */
    public List<Message> getMessages() {
        return new ArrayList<>(messages);
    }
    
    // Helper methods for parsing Monero message data
    private String extractAmount(String messageContent) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(messageContent);
            return json.optString("amount", null);
        } catch (Exception e) {
            return null;
        }
    }
    
    private String extractStatus(String messageContent) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(messageContent);
            return json.optString("status", null);
        } catch (Exception e) {
            return null;
        }
    }
    
    private String extractTransactionId(String messageContent) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(messageContent);
            return json.optString("tx_id", null);
        } catch (Exception e) {
            return null;
        }
    }
    
    private String updateStatusInContent(String originalContent, String newStatus) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(originalContent);
            json.put("status", newStatus);
            return json.toString();
        } catch (Exception e) {
            return originalContent;
        }
    }
    
    /**
     * ViewHolder class for different message types
     */
    public static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageText;
        TextView timeText;
        TextView statusText;
        TextView amountText;
        ImageView statusIcon;
        ImageView moneroIcon;
        
        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            
            messageText = itemView.findViewById(R.id.messageText);
            timeText = itemView.findViewById(R.id.timeText);
            statusText = itemView.findViewById(R.id.statusText);
            amountText = itemView.findViewById(R.id.amountText);
            statusIcon = itemView.findViewById(R.id.statusIcon);
            moneroIcon = itemView.findViewById(R.id.moneroIcon);
        }
    }
}
