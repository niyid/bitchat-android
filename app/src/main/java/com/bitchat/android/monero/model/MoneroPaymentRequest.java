package com.bitchat.android.monero.model;

import java.io.Serializable;

/**
 * Model for Monero payment request
 */
public class MoneroPaymentRequest implements Serializable {
    private String amount;
    private String address;
    private String reason;
    private String requestedBy;
    private long timestamp;
    private String requestId;
    
    public MoneroPaymentRequest() {
        this.timestamp = System.currentTimeMillis();
        this.requestId = generateRequestId();
    }
    
    public MoneroPaymentRequest(String amount, String address, String reason, String requestedBy) {
        this.amount = amount;
        this.address = address;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.timestamp = System.currentTimeMillis();
        this.requestId = generateRequestId();
    }
    
    private String generateRequestId() {
        return "REQ_" + System.currentTimeMillis();
    }
    
    // Getters and Setters
    public String getAmount() {
        return amount;
    }
    
    public void setAmount(String amount) {
        this.amount = amount;
    }
    
    public String getAddress() {
        return address;
    }
    
    public void setAddress(String address) {
        this.address = address;
    }
    
    public String getReason() {
        return reason;
    }
    
    public void setReason(String reason) {
        this.reason = reason;
    }
    
    public String getRequestedBy() {
        return requestedBy;
    }
    
    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
    @Override
    public String toString() {
        return "MoneroPaymentRequest{" +
                "amount='" + amount + '\'' +
                ", address='" + address + '\'' +
                ", reason='" + reason + '\'' +
                ", requestedBy='" + requestedBy + '\'' +
                ", timestamp=" + timestamp +
                ", requestId='" + requestId + '\'' +
                '}';
    }
}
