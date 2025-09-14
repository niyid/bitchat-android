package com.m2049r.xmrwallet.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class WalletManager {

    /* ===== SINGLETON ===== */
    private static WalletManager instance;

    /* ===== INSTANCE VARIABLES ===== */
    private String errorString = "";

    /* ===== NETWORK TYPES ===== */
    public static final int MAINNET = 0;
    public static final int TESTNET = 1;
    public static final int STAGENET = 2;

    /* ===== PRIVATE CONSTRUCTOR ===== */
    private WalletManager() {}

    public static synchronized WalletManager getInstance() {
        if (instance == null) {
            instance = new WalletManager();
        }
        return instance;
    }

    /* ===== JNI BINDINGS ===== */

    public native long createWalletJ(String path, String password, String language, int nettype);

    public native long openWalletJ(String path, String password, int nettype);

    public native long recoveryWalletJ(String path, String password, String mnemonic,
                                        int nettype, long restoreHeight);

    private native boolean verifyWalletPasswordJ(String keysFileName, String password, boolean noSpendKey);

    private native String getErrorStringJ();

    private native String[] findWalletsJ(String path);
    
    public native boolean setDaemonAddressJ(String address, int port);


    /* ===== JAVA WRAPPERS ===== */
    
    
    public boolean setDaemonAddress(String address, int port) {
        return setDaemonAddressJ(address, port);
    }    

    public Wallet createWallet(String path, String password, String language, int nettype) {
        long walletHandle = createWalletJ(path, password, language, nettype);
        return walletHandle != 0 ? new Wallet(walletHandle) : null;
    }

    public Wallet openWallet(String path, String password, int nettype) {
        long walletHandle = openWalletJ(path, password, nettype);
        return walletHandle != 0 ? new Wallet(walletHandle) : null;
    }

    public Wallet recoveryWallet(String path, String password, String mnemonic, int nettype, long restoreHeight) {
        long walletHandle = recoveryWalletJ(path, password, mnemonic, nettype, restoreHeight);
        return walletHandle != 0 ? new Wallet(walletHandle) : null;
    }

    public boolean walletExists(String path) {
        return new File(path).exists();
    }

    public boolean verifyWalletPassword(String keysFileName, String password, boolean noSpendKey) {
        return verifyWalletPasswordJ(keysFileName, password, noSpendKey);
    }

    public List<String> findWallets(String path) {
        List<String> wallets = new ArrayList<>();
        String[] result = findWalletsJ(path);
        if (result != null) {
            for (String walletName : result) {
                wallets.add(walletName);
            }
        }
        return wallets;
    }

    public String getErrorString() {
        return getErrorStringJ();
    }
}

