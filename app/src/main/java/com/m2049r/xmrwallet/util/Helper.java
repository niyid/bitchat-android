package com.m2049r.xmrwallet.util;

public class Helper {
    static {
        System.loadLibrary("monerujo");
    }

    public static native long getAmountFromString(String amount);
    public static native String getDisplayAmount(long amount);
}

