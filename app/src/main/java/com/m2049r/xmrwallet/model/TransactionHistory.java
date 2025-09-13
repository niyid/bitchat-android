package com.m2049r.xmrwallet.model;

import java.util.List;

public class TransactionHistory {
    private long handle;

    public TransactionHistory(long handle) {
        this.handle = handle;
    }

    public native int count();
    public native TransactionInfo transaction(int index);
    public native TransactionInfo transaction(String id);
    public native List<TransactionInfo> getAll();
    public native void refresh();
    public native void setTxNote(String txid, String note);

    @Override
    protected void finalize() throws Throwable {
        // Handle cleanup handled by wallet
        super.finalize();
    }
}
