package com.m2049r.xmrwallet.model;

public class TransactionHistory {
    private long handle; 

    TransactionHistory(long handle) {
        this.handle = handle;
    }

    // Number of transactions in history
    public native int getCount();

    // Get a transaction by index
    public native TransactionInfo getTransaction(int index);

    // Refresh from the daemon
    public native void refresh();
}

