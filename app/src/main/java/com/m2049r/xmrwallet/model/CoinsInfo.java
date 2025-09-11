package com.m2049r.xmrwallet.model;

public class CoinsInfo {
    private long handle; 

    CoinsInfo(long handle) {
        this.handle = handle;
    }

    // JNI bindings
    public native String getKeyImage();
    public native String getTxId();
    public native long getAmount();
    public native long getBlockHeight();
    public native boolean isSpent();
    public native int getSubAddressIndex();
    public native boolean isUnlocked();
    public native boolean isFrozen();
    public native void setFrozen(boolean frozen);

    @Override
    public String toString() {
        return "CoinsInfo{" +
                "txId='" + getTxId() + '\'' +
                ", amount=" + getAmount() +
                ", blockHeight=" + getBlockHeight() +
                ", spent=" + isSpent() +
                ", unlocked=" + isUnlocked() +
                ", frozen=" + isFrozen() +
                '}';
    }
}

