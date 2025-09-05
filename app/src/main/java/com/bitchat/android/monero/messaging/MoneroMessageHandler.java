package com.bitchat.android.monero.messaging;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigInteger;

/**
 * Handles Monero-specific messages and protocols for BitChat
 * Manages payment messages, address sharing, and transaction status updates
 */
public class MoneroMessageHandler {
    
    private static final String TAG = "MoneroMessageHandler";
    
    // Message types
    public static final String TYPE_MONERO_PAYMENT = "monero_payment";
    public static final String TYPE_MONERO_ADDRESS_SHARE = "monero_address_share";
    public static final String TYPE_MONERO_PAYMENT_REQUEST = "monero_payment_request";
    public static final String TYPE_MONERO_PAYMENT_STATUS = "monero_payment_status";
    
    // Payment status values
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_FAILED = "failed";
    
    private MoneroMessageListener messageListener;
    
    public interface MoneroMessageListener {
        void onPaymentReceived(MoneroPaymentMessage payment);
        void onAddressShared(String address, String fromUser);
        void onPaymentRequested(MoneroPaymentRequest request);
        void onPaymentStatusUpdated(String txId, String status);
    }
    
    public void setMessageListener(MoneroMessageListener listener) {
        this.messageListener = listener;
    }
    
    /**
     * Parse incoming message and determine if it's Monero-related
     */
    public boolean handleMessage(String messageContent, String fromUser) {
        try {
            JSONObject json = new JSONObject(messageContent);
            String type = json.optString("type", "");
            
            switch (type) {
                case TYPE_MONERO_ADDRESS_SHARE:
                    handleAddressShare(json, fromUser);
                    return true;
                    
                case TYPE_MONERO_PAYMENT_REQUEST:
                    handlePaymentRequest(json, fromUser);
                    return true;
                    
                case TYPE_MONERO_PAYMENT_STATUS:
                    handlePaymentStatus(json, fromUser);
                    return true;
                    
                default:
                    return false; // Not a Monero message
            }
            
        } catch (JSONException e) {
            Log.d(TAG, "Message is not JSON, treating as regular text message");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error handling Monero message", e);
            return false;
        }
    }
    
    /**
     * Handle incoming payment message
     */
    private void handlePaymentMessage(JSONObject json, String fromUser) {
        try {
            MoneroPaymentMessage payment = MoneroPaymentMessage.fromJson(json);
            payment.setFromUser(fromUser);
            
            if (messageListener != null) {
                messageListener.onPaymentReceived(payment);
            }
            
            Log.d(TAG, "Received payment: " + payment.getAmount() + " XMR from " + fromUser);
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling payment message", e);
        }
    }
    
    /**
     * Handle address sharing message
     */
    private void handleAddressShare(JSONObject json, String fromUser) {
        try {
            String address = json.getString("address");
            
            if (messageListener != null) {
                messageListener.onAddressShared(address, fromUser);
            }
            
            Log.d(TAG, "Received address share from " + fromUser);
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling address share", e);
        }
    }
    
    /**
     * Handle payment request message
     */
    private void handlePaymentRequest(JSONObject json, String fromUser) {
        try {
            MoneroPaymentRequest request = MoneroPaymentRequest.fromJson(json);
            request.setFromUser(fromUser);
            
            if (messageListener != null) {
                messageListener.onPaymentRequested(request);
            }
            
            Log.d(TAG, "Received payment request from " + fromUser + ": " + request.getAmount() + " XMR");
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling payment request", e);
        }
    }
    
    /**
     * Handle payment status update
     */
    private void handlePaymentStatus(JSONObject json, String fromUser) {
        try {
            String txId = json.getString("tx_id");
            String status = json.getString("status");
            
            if (messageListener != null) {
                messageListener.onPaymentStatusUpdated(txId, status);
            }
            
            Log.d(TAG, "Payment status update: " + txId + " -> " + status);
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling payment status", e);
        }
    }
    
    /**
     * Create a payment message JSON
     */
    public static String createPaymentMessage(String amount, String txId, String recipientAddress) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_MONERO_PAYMENT);
            json.put("amount", amount);
            json.put("tx_id", txId);
            json.put("recipient_address", recipientAddress);
            json.put("timestamp", System.currentTimeMillis());
            json.put("status", STATUS_PENDING);
            
            return json.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating payment message", e);
            return null;
        }
    }
    
    /**
     * Create an address share message JSON
     */
    public static String createAddressShareMessage(String address) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_MONERO_ADDRESS_SHARE);
            json.put("address", address);
            json.put("timestamp", System.currentTimeMillis());
            
            return json.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating address share message", e);
            return null;
        }
    }
    
    /**
     * Create a payment request message JSON
     */
    public static String createPaymentRequestMessage(String amount, String reason) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_MONERO_PAYMENT_REQUEST);
            json.put("amount", amount);
            json.put("reason", reason != null ? reason : "");
            json.put("timestamp", System.currentTimeMillis());
            
            return json.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating payment request message", e);
            return null;
        }
    }
    
    /**
     * Create a payment status update message JSON
     */
    public static String createPaymentStatusMessage(String txId, String status) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_MONERO_PAYMENT_STATUS);
            json.put("tx_id", txId);
            json.put("status", status);
            json.put("timestamp", System.currentTimeMillis());
            
            return json.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating payment status message", e);
            return null;
        }
    }
    
    /**
     * MoneroPaymentMessage class
     */
    public static class MoneroPaymentMessage {
        private String amount;
        private String txId;
        private String recipientAddress;
        private long timestamp;
        private String status;
        private String fromUser;
        
        public static MoneroPaymentMessage fromJson(JSONObject json) throws JSONException {
            MoneroPaymentMessage message = new MoneroPaymentMessage();
            message.amount = json.getString("amount");
            message.txId = json.getString("tx_id");
            message.recipientAddress = json.getString("recipient_address");
            message.timestamp = json.getLong("timestamp");
            message.status = json.optString("status", STATUS_PENDING);
            return message;
        }
        
        // Getters and setters
        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
        
        public String getTxId() { return txId; }
        public void setTxId(String txId) { this.txId = txId; }
        
        public String getRecipientAddress() { return recipientAddress; }
        public void setRecipientAddress(String recipientAddress) { this.recipientAddress = recipientAddress; }
        
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public String getFromUser() { return fromUser; }
        public void setFromUser(String fromUser) { this.fromUser = fromUser; }
        
        public boolean isPending() { return STATUS_PENDING.equals(status); }
        public boolean isConfirmed() { return STATUS_CONFIRMED.equals(status); }
        public boolean isFailed() { return STATUS_FAILED.equals(status); }
    }
    
    /**
     * MoneroPaymentRequest class
     */
    public static class MoneroPaymentRequest {
        private String amount;
        private String reason;
        private long timestamp;
        private String fromUser;
        
        public static MoneroPaymentRequest fromJson(JSONObject json) throws JSONException {
            MoneroPaymentRequest request = new MoneroPaymentRequest();
            request.amount = json.getString("amount");
            request.reason = json.optString("reason", "");
            request.timestamp = json.getLong("timestamp");
            return request;
        }
        
        // Getters and setters
        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
        
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        
        public String getFromUser() { return fromUser; }
        public void setFromUser(String fromUser) { this.fromUser = fromUser; }
    }
    
    /**
     * Utility method to check if a message is Monero-related
     */
    public static boolean isMoneroMessage(String messageContent) {
        try {
            JSONObject json = new JSONObject(messageContent);
            String type = json.optString("type", "");
            return type.startsWith("monero_");
        } catch (JSONException e) {
            return false;
        }
    }
    
    /**
     * Extract display text from Monero message for chat UI
     */
    public static String getDisplayText(String messageContent) {
        try {
            JSONObject json = new JSONObject(messageContent);
            String type = json.optString("type", "");
            
            switch (type) {
                case TYPE_MONERO_PAYMENT:
                    String amount = json.getString("amount");
                    String status = json.optString("status", STATUS_PENDING);
                    return "💰 Sent " + amount + " XMR (" + status + ")";
                    
                case TYPE_MONERO_ADDRESS_SHARE:
                    return "📍 Shared Monero address";
                    
                case TYPE_MONERO_PAYMENT_REQUEST:
                    String reqAmount = json.getString("amount");
                    String reason = json.optString("reason", "");
                    return "💳 Requested " + reqAmount + " XMR" + 
                           (reason.isEmpty() ? "" : " - " + reason);
                    
                case TYPE_MONERO_PAYMENT_STATUS:
                    String txId = json.getString("tx_id");
                    String newStatus = json.getString("status");
                    return "ℹ️ Payment " + txId.substring(0, 8) + "... " + newStatus;
                    
                default:
                    return messageContent;
            }
        } catch (JSONException e) {
            return messageContent;
        }
    }
}
