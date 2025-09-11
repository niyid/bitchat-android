package com.m2049r.xmrwallet.model;

public class Coins {
    private long handle; 

    Coins(long handle) {
        this.handle = handle;
    }

    // Return the number of coins
    public native int getCount();

    // Get a single coin’s info by index
    public native CoinsInfo get(int index);

    // Refresh list from native wallet
    public native void refresh();

    // Freeze/unfreeze a specific output
    public native void freeze(String keyImage);
    public native void thaw(String keyImage);
}

