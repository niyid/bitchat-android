package com.m2049r.xmrwallet.model;

public class WalletManager {

    private static WalletManager instance = null;

    public static synchronized WalletManager getInstance() {
        if (instance == null) {
            instance = new WalletManager();
        }
        return instance;
    }

    public native Wallet createWalletJ(String path, String password, String language, int networkType);

    public native Wallet openWalletJ(String path, String password);

    public native Wallet recoveryWalletJ(String path, String password, String mnemonic, String seedOffset, long restoreHeight);

    public native Wallet loadWalletJ(String path, String password);

    public native boolean walletExistsJ(String path);
}

