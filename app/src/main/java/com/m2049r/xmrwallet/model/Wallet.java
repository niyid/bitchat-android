package com.m2049r.xmrwallet.model;

public class Wallet {
    static {
        System.loadLibrary("monerujo");
    }

    public enum Status {
        Status_Ok,
        Status_Error,
        Status_Critical
    }

    private long handle;

    Wallet(long handle) {
        this.handle = handle;
    }

    // Wallet info
    public native String getAddress();
    public native long getBalance();
    public native long getUnlockedBalance();
    public native String getSeed(String seedOffset);
    public native int getStatus();
    public native String getErrorString();

    // Network sync
    public native boolean setDaemonAddress(String address, int port);
    public native void setDaemonLogin(String username, String password);
    public native long getBlockChainHeight();
    public native long getDaemonBlockChainHeight();
    public native boolean refresh();
    public native void refreshHistory();

    // Transactions
    public native PendingTransaction createTransaction(String dstAddress,
                                                       String paymentId,
                                                       long amount,
                                                       int mixin,
                                                       int priority);

    /**
     * Submit a raw transaction to the network.
     *
     * @param txData serialized transaction data (base64 or hex depending on JNI implementation)
     * @return transaction hash if successful, otherwise null
     */
    public native String submitTransaction(String txData);

    public native boolean store(String path);
    public native void close();

    // Listener interface
    public interface Listener {
        void moneySpent(String txId, long amount);
        void moneyReceived(String txId, long amount);
        void unconfirmedMoneyReceived(String txId, long amount);
        void newBlock(long height);
        void updated();
        void refreshed();
    }

    public native void setListener(Listener listener);
}

