package com.m2049r.xmrwallet.model;

public class Transfer {
    private long handle;

    public Transfer(long handle) {
        this.handle = handle;
    }

    public native String getAddress();
    public native long getAmount();
    
    @Override
    protected void finalize() throws Throwable {
        // Handle cleanup handled by transaction info
        super.finalize();
    }
}
