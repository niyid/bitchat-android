package com.m2049r.xmrwallet.model;

public class WalletManager {
    static {
        System.loadLibrary("monerujo");
    }

    private static WalletManager instance = null;

    public static synchronized WalletManager getInstance() {
        if (instance == null) {
            instance = new WalletManager();
        }
        return instance;
    }

    private WalletManager() {}

    // JNI methods
    public native Wallet createWallet(String path, String password, String language, int networkType);
    public native Wallet openWallet(String path, String password);
    public native Wallet recoveryWallet(String path, String password, String mnemonic, String seedOffset, long restoreHeight);
    public native Wallet loadWallet(String path, String password);
    public native boolean walletExists(String path);
}

