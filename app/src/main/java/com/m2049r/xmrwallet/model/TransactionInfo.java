package com.m2049r.xmrwallet.model;

public class TransactionInfo {
    private long handle;

    TransactionInfo(long handle) {
        this.handle = handle;
    }

    public enum Direction {
        Direction_In,
        Direction_Out
    }

    public enum State {
        State_Pending,
        State_Confirmed,
        State_Failed,
        State_Pool
    }

    public native String getHash();
    public native long getAmount();
    public native long getFee();
    public native long getBlockHeight();
    public native long getTimestamp();
    public native int getConfirmations();
    public native int getDirection();
    public native int getState();
}

