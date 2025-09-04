package com.bitchat.android.model;

import java.io.Serializable;

/**
 * Enhanced Message model with support for Monero transactions and delivery status
 */
public class Message implements Serializable {
    
    // Delivery status constants
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DELIVERED = 1;
    public static final int STATUS_READ = 2;
    public static final int STATUS_FAILED = 3;
    
    // Message type constants
    public static final int TYPE_TEXT = 0;
    public static final int TYPE_MONERO = 1;
    public static final int TYPE_SYSTEM = 2;
    
    private String id;
    private String content;
    private boolean isSent;
    private long timestamp;
    private int deliveryStatus;
    private int messageType;
    private String fromUserId;
    private String toUserId;
    
    // Monero-specific fields
    private String transactionId;
    private String moneroAmount;
    private String transactionStatus;
    
    public Message() {
        this.id = generateId();
        this.timestamp = System.currentTimeMillis();
        this.deliveryStatus = STATUS_PENDING;
        this.messageType = TYPE_TEXT;
    }
    
    public Message(String content, boolean isSent, long timestamp) {
        this();
        this.content = content;
        this.isSent = isSent;
        this.timestamp = timestamp;
        
        // Auto-detect message type
        if (isMoneroMessage(content)) {
            this.messageType = TYPE_MONERO;
            parseMoneroData(content);
        }
    }
    
    public Message(String content, boolean isSent, long timestamp, String fromUserId) {
        this(content, isSent, timestamp);
        this.fromUserId = fromUserId;
    }
    
    // Getters and Setters
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
        
        // Update message type when content changes
        if (isMoneroMessage(content)) {
            this.messageType = TYPE_MONERO;
            parseMoneroData(content);
        } else {
            this.messageType = TYPE_TEXT;
        }
    }
    
    public boolean isSent() {
        return isSent;
    }
    
    public void setSent(boolean sent) {
        isSent = sent;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public int getDeliveryStatus() {
        return deliveryStatus;
    }
    
    public void setDeliveryStatus(int deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }
    
    public int getMessageType() {
        return messageType;
    }
    
    public void setMessageType(int messageType) {
        this.messageType = messageType;
    }
    
    public String getFromUserId() {
        return fromUserId;
    }
    
    public void setFromUserId(String fromUserId) {
        this.fromUserId = fromUserId;
    }
    
    public String getToUserId() {
        return toUserId;
    }
    
    public void setToUserId(String toUserId) {
        this.toUserId = toUserId;
    }
    
    // Monero-specific getters and setters
    public String getTransactionId() {
        return transactionId;
    }
    
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
    
    public String getMoneroAmount() {
        return moneroAmount;
    }
    
    public void setMoneroAmount(String moneroAmount) {
        this.moneroAmount = moneroAmount;
    }
    
    public String getTransactionStatus() {
        return transactionStatus;
    }
    
    public void setTransactionStatus(String transactionStatus) {
        this.transactionStatus = transactionStatus;
    }
    
    // Helper methods
    public boolean isTextMessage() {
        return messageType == TYPE_TEXT;
    }
    
    public boolean isMoneroMessage() {
        return messageType == TYPE_MONERO;
    }
    
    public boolean isSystemMessage() {
        return messageType == TYPE_SYSTEM;
    }
    
    public boolean isPending() {
        return deliveryStatus == STATUS_PENDING;
    }
    
    public boolean isDelivered() {
        return deliveryStatus == STATUS_DELIVERED;
    }
    
    public boolean isRead() {
        return deliveryStatus == STATUS_READ;
    }
    
    public boolean isFailed() {
        return deliveryStatus == STATUS_FAILED;
    }
    
    /**
     * Check if content is a Monero message
     */
    private boolean isMoneroMessage(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        
        try {
            org.json.JSONObject json = new org.json.JSONObject(content);
            String type = json.optString("type", "");
            return type.startsWith("monero_");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Parse Monero data from message content
     */
    private void parseMoneroData(String content) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(content);
            this.transactionId = json.optString("tx_id", null);
            this.moneroAmount = json.optString("amount", null);
            this.transactionStatus = json.optString("status", null);
        } catch (Exception e) {
            // Ignore parsing errors
        }
    }
    
    /**
     * Create a system message
     */
    public static Message createSystemMessage(String content) {
        Message message = new Message();
        message.setContent(content);
        message.setMessageType(TYPE_SYSTEM);
        message.setSent(false);
        return message;
    }
    
    /**
     * Create a Monero payment message
     */
    public static Message createMoneroMessage(String content, boolean isSent, String transactionId) {
        Message message = new Message(content, isSent, System.currentTimeMillis());
        message.setTransactionId(transactionId);
        return message;
    }
    
    /**
     * Update transaction status in the message
     */
    public void updateTransactionStatus(String newStatus) {
        this.transactionStatus = newStatus;
        
        // Update content if it's a JSON message
        if (isMoneroMessage() && content != null) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(content);
                json.put("status", newStatus);
                this.content = json.toString();
            } catch (Exception e) {
                // Ignore JSON errors
            }
        }
    }
    
    /**
     * Get delivery status as string
     */
    public String getDeliveryStatusString() {
        switch (deliveryStatus) {
            case STATUS_PENDING:
                return "Pending";
            case STATUS_DELIVERED:
                return "Delivered";
            case STATUS_READ:
                return "Read";
            case STATUS_FAILED:
                return "Failed";
            default:
                return "Unknown";
        }
    }
    
    /**
     * Get message type as string
     */
    public String getMessageTypeString() {
        switch (messageType) {
            case TYPE_TEXT:
                return "Text";
            case TYPE_MONERO:
                return "Monero";
            case TYPE_SYSTEM:
                return "System";
            default:
                return "Unknown";
        }
    }
    
    /**
     * Generate a unique message ID
     */
    private String generateId() {
        return "msg_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);
    }
    
    /**
     * Get display text for the message
     */
    public String getDisplayText() {
        if (isMoneroMessage()) {
            return com.bitchat.android.monero.messaging.MoneroMessageHandler.getDisplayText(content);
        }
        return content;
    }
    
    /**
     * Clone this message
     */
    public Message clone() {
        Message clone = new Message();
        clone.id = this.id;
        clone.content = this.content;
        clone.isSent = this.isSent;
        clone.timestamp = this.timestamp;
        clone.deliveryStatus = this.deliveryStatus;
        clone.messageType = this.messageType;
        clone.fromUserId = this.fromUserId;
        clone.toUserId = this.toUserId;
        clone.transactionId = this.transactionId;
        clone.moneroAmount = this.moneroAmount;
        clone.transactionStatus = this.transactionStatus;
        return clone;
    }
    
    @Override
    public String toString() {
        return "Message{" +
                "id='" + id + '\'' +
                ", content='" + content + '\'' +
                ", isSent=" + isSent +
                ", timestamp=" + timestamp +
                ", deliveryStatus=" + deliveryStatus +
                ", messageType=" + messageType +
                ", transactionId='" + transactionId + '\'' +
                ", moneroAmount='" + moneroAmount + '\'' +
                '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        Message message = (Message) obj;
        return id != null ? id.equals(message.id) : message.id == null;
    }
    
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
