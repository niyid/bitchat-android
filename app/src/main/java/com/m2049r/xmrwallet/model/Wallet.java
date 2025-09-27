package com.m2049r.xmrwallet.model;

import java.util.List;

public class Wallet {
    // Wallet status constants

    public enum Status {
        Status_Ok,
        Status_Error,
        Status_Critical
    }

    // Connection status
    public enum ConnectionStatus {
        ConnectionStatus_Disconnected,
        ConnectionStatus_Connected,
        ConnectionStatus_WrongVersion
    }

    // Device type
    public enum Device {
        Device_Software(0, "Software"),
        Device_Ledger(1, "Ledger");

        private int value;
        private String name;

        Device(int value, String name) {
            this.value = value;
            this.name = name;
        }

        public int getValue() { return value; }
        public String getName() { return name; }

        public static Device fromValue(int value) {
            for (Device device : Device.values()) {
                if (device.value == value) return device;
            }
            return Device_Software;
        }
    }

    private int accountIndex = 0;
    private String lastErrorString = null;
    private long handle;
    private long listenerHandle;

    public Wallet(long handle) {
        this.handle = handle;
    }

    // Wallet creation and opening
    public static native boolean walletExists(String path);
    public static native Wallet openWallet(String path, String password, int networkType);
    public static native Wallet createWalletJ(String path, String password, String language, int networkType, long restoreHeight);
    public static native Wallet recoveryWallet(String path, String password, String mnemonic, int networkType, long restoreHeight);
    public static native Wallet createWalletFromKeys(String path, String password, String language, int networkType, long restoreHeight, String address, String viewKey, String spendKey);
    public static native Wallet createWalletFromDevice(String path, String password, int networkType, String deviceName, long restoreHeight, String subaddressLookahead);

    // Wallet operations
    public native String getSeed();
    public native String getSeed(String seedOffset);
    public native String getSeedLanguage();
    public native void setSeedLanguage(String language);
    public native int getStatusJ();
    private native int statusWithErrorString(int[] outStatus, String[] outError);
    public native boolean setPassword(String password);
    private native String getAddressJ(int accountIndex, int addressIndex);
    public native String getPath();
    public native int getNetworkType();
    public native String getSecretViewKey();
    public native String getSecretSpendKey();
    public native String getPublicViewKey();
    public native String getPublicSpendKey();
    public native long getBalance();
    public native long getBalance(int accountIndex);
    public native long getUnlockedBalance();
    public native long getUnlockedBalance(int accountIndex);
    public native long getBlockChainHeight();
    public native long getApproximateBlockChainHeight();
    public native long getDaemonBlockChainHeight();
    public native long getDaemonBlockChainTargetHeight();
    public native boolean isSynchronized();
    public native String getDisplayAmount(long amount);
    public native long getAmountFromString(String amount);
    public native long getAmountFromDouble(double amount);
    public native void setRefreshFromBlockHeight(long height);
    public native long getRefreshFromBlockHeight();
    public native void setRestoreHeight(long height);
    public native long getRestoreHeight();
    public native void refreshHistory();

    // Device operations
    public native Device getDeviceType();
    public native boolean setDeviceType(Device device);
    public native boolean isHardwareWallet();

    // Synchronization
    public native boolean refresh();
    public native void refreshAsync();
    public native void startRefresh();
    public native void pauseRefresh();
    public native boolean isRefreshing();

    // Transaction operations
    public native String submitTransaction(String txData);
    public native PendingTransaction createTransactionJ(String dstAddr, String paymentId, long amount, int mixinCount, int priority);
    public native PendingTransaction createSweepTransaction(String dstAddr, String paymentId, int mixinCount, int priority, int accountIndex);
    public native PendingTransaction createSweepUnmixableTransaction();
    public native void disposeTransaction(PendingTransaction pendingTransaction);
    public native TransactionHistory getHistory();
    public native boolean setUserNote(String txid, String note);
    public native String getUserNote(String txid);
    public native String getTxKey(String txid);
    public native String checkTxKey(String txid, String txKey, String address);
    public native String getTxProof(String txid, String address, String message);
    public native boolean checkTxProof(String txid, String address, String message, String signature);
    public native String getSpendProof(String txid, String message);
    public native boolean checkSpendProof(String txid, String message, String signature);
    public native String getReserveProof(boolean all, int accountIndex, long amount, String message);
    public native boolean checkReserveProof(String address, String message, String signature);

    // Subaddresses
    public native Subaddress getSubaddress(int accountIndex, int addressIndex);
    public native SubaddressBook getSubaddressBook();
    public native String addSubaddress(int accountIndex, String label);

    // Accounts
    public native void addAccount(String label);
    public native String getSubaddressLabel(int accountIndex, int addressIndex);
    public native void setSubaddressLabel(int accountIndex, int addressIndex, String label);
    public native int getNumAccounts();
    public native int getNumSubaddresses(int accountIndex);
    public native String getAccountLabel();
    public native String getAccountLabel(int accountIndex);
    public native void setAccountLabel(int accountIndex, String label);

    // Address book
    public native AddressBook getAddressBook();

    // Connection
    public native boolean connectToDaemon();
    private native int getConnectionStatusJ();
    public native boolean setTrustedDaemon(boolean arg);
    public native boolean isTrustedDaemon();
    public native long getDaemonConnectionTimeout();
    public native void setDaemonConnectionTimeout(long timeout);

    // Daemon operations
    public native long initJ(String daemonAddress,
                             long upperTransactionLimit,
                             String daemonUsername,
                             String daemonPassword,
                             boolean ssl,
                             boolean lightWallet,
                             String proxy);


    // Wallet management
    public native synchronized boolean store(String path);
    public native String getFilename();
    public native boolean rescanBlockchain();
    public native void rescanBlockchainAsync();

    // Key images
    public native String exportKeyImages();
    public native int importKeyImages(String keyImages);

    // Outputs
    public native String exportOutputs();
    public native int importOutputs(String outputs);

    // Multisig
    public native String getMultisigInfo();
    public native String makeMultisig(String multisigInfo, int threshold);
    public native boolean finalizeMultisig(String multisigInfo);
    public native boolean isMultisig();
    public native String signMultisigTxHex(String multisigTxHex);
    public native String[] submitMultisigTxHex(String multisigTxHex);

    // Listener
    public native void setListenerJ(WalletListener listener);
    
    public native int getDefaultMixin();
    public native void setDefaultMixin(int mixin);
    public native boolean setAutoRefreshInterval(int millis);
    public native int getAutoRefreshInterval();

    // Utilities
    public static native boolean paymentIdValid(String paymentId);
    public static native boolean addressValid(String address, int networkType);
    public static native boolean keyValid(String key);
    public static native String getPaymentIdFromAddress(String address, int networkType);
    public static native long getMaximumAllowedAmount();
    public static native void printConnections();
    public static native boolean isKeyImageSpent(String keyImage);

    // Debug
    public native void setLogLevel(int level);
    public native void setLogCategories(String categories);
    
    public int getStatus() {
        return getStatusJ();
    }
    
    public ConnectionStatus getConnectionStatus() {
        int s = getConnectionStatusJ();
        return ConnectionStatus.values()[s];
    }    

    public String getAddress() {
        return getAddress(accountIndex);
    }

    public String getAddress(int accountIndex) {
        return getAddressJ(accountIndex, 0);
    }

    public String getSubaddress(int addressIndex) {
        return getAddressJ(accountIndex, addressIndex);
    }

    public PendingTransaction createTransaction(String dstAddr, String paymentId, long amount, int mixinCount, int priority) {
        return createTransactionJ(dstAddr, paymentId, amount, mixinCount, priority);
    }
    
    public void setListener(WalletListener listener) {
        setListenerJ(listener);
    }
    
    public int getLastStatus() {
        int[] outStatus = new int[1];
        String[] outError = new String[1];
        int r = statusWithErrorString(outStatus, outError);
        lastErrorString = outError[0];
        return outStatus[0];
    }

    public String getErrorString() {
        return lastErrorString;
    }
    
    /**
     * Proxy wrapper for initJ that hides the "proxy" parameter for most cases.
     *
     * @param daemonAddress Daemon host or IP
     * @param upperTransactionLimit Upper transaction size (0 = unlimited)
     * @param daemonUsername Daemon RPC username
     * @param daemonPassword Daemon RPC password
     * @param ssl True if SSL should be used
     * @param lightWallet True if light wallet mode should be enabled
     * @return native wallet handle
     */
    public long init(String daemonAddress,
                     long upperTransactionLimit,
                     String daemonUsername,
                     String daemonPassword,
                     boolean ssl,
                     boolean lightWallet) {
        // Call through to initJ, using empty proxy string
        return initJ(daemonAddress,
                     upperTransactionLimit,
                     daemonUsername,
                     daemonPassword,
                     ssl,
                     lightWallet,
                     "");
    }
    

    public boolean store() {
        return store("");
    }        
        
    // Cleanup
    @Override
    protected void finalize() throws Throwable {
        if (handle != 0) {
            close();
        }
        super.finalize();
    }
    
    public boolean close() {
        return WalletManager.getInstance().close(this);
    }    
}
