package com.bitchat.android.monero.model;

import java.io.Serializable;

/**
 * Model for Monero payment information
 */
public class MoneroPayment implements Serializable {
    private String amount;
    private String address;
    private String txId;
    private String fromUser;
    private long timestamp;
    private String note;
    
    public MoneroPayment() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public MoneroPayment(String amount, String address, String fromUser) {
        this.amount = amount;
        this.address = address;
        this.fromUser = fromUser;
        this.timestamp = System.currentTimeMillis();
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
    
    public String getTxId() {
        return txId;
    }
    
    public void setTxId(String txId) {
        this.txId = txId;
    }
    
    public String getFromUser() {
        return fromUser;
    }
    
    public void setFromUser(String fromUser) {
        this.fromUser = fromUser;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getNote() {
        return note;
    }
    
    public void setNote(String note) {
        this.note = note;
    }
    
    @Override
    public String toString() {
        return "MoneroPayment{" +
                "amount='" + amount + '\'' +
                ", address='" + address + '\'' +
                ", txId='" + txId + '\'' +
                ", fromUser='" + fromUser + '\'' +
                ", timestamp=" + timestamp +
                ", note='" + note + '\'' +
                '}';
    }
}
