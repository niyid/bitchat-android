package com.bitchat.android.monero.model;

import java.io.Serializable;

/**
 * Model for Monero transaction search request
 */
public class MoneroSearchRequest implements Serializable {
    private String txId;
    private String requestedBy;
    private long timestamp;
    private String searchType;
    
    public MoneroSearchRequest() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public MoneroSearchRequest(String txId, String requestedBy) {
        this.txId = txId;
        this.requestedBy = requestedBy;
        this.timestamp = System.currentTimeMillis();
        this.searchType = "TRANSACTION";
    }
    
    // Getters and Setters
    public String getTxId() {
        return txId;
    }
    
    public void setTxId(String txId) {
        this.txId = txId;
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
}
