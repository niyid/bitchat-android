package com.m2049r.xmrwallet.model;

import java.util.List;

public class TransactionInfo {
    public enum Direction {
        Direction_In,
        Direction_Out
    }

    public enum TransferType {
        Transfer_Cold,
        Transfer_Normal,
        Transfer_Stake,
        Transfer_Mining
    }

    private long handle;

    public TransactionInfo(long handle) {
        this.handle = handle;
    }

    public native Direction getDirection();
    public native boolean isPending();
    public native boolean isFailed();
    public native long getAmount();
    public native long getFee();
    public native long getBlockHeight();
    public native List<String> getSubaddressIndex();
    public native int getSubaddressAccount();
    public native String getLabel();
    public native long getConfirmations();
    public native long getUnlockTime();
    public native String getHash();
    public native long getTimestamp();
    public native String getPaymentId();
    public native List<Transfer> getTransfers();
    public native String getNote();
    public native TransferType getTransferType();

    @Override
    protected void finalize() throws Throwable {
        // Handle cleanup handled by wallet
        super.finalize();
    }
}
