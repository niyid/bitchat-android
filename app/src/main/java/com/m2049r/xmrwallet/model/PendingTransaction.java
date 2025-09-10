package com.m2049r.xmrwallet.model;

public class PendingTransaction {
    static {
        System.loadLibrary("monerujo");
    }

    private long handle;

    PendingTransaction(long handle) {
        this.handle = handle;
    }

    public enum Status {
        Status_Ok,
        Status_Error
    }

    public enum Priority {
        Priority_Default,
        Priority_Low,
        Priority_Medium,
        Priority_High
    }

    public native int getStatus();
    public native String getErrorString();
    public native boolean commit(String filename, boolean overwrite);
    public native String getFirstTxId();
    public native long getFee();
    public native byte[] getSerializedTransaction();    
}

