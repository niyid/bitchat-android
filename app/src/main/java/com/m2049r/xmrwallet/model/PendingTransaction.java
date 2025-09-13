package com.m2049r.xmrwallet.model;

import java.util.List;
import java.util.ArrayList;

public class PendingTransaction {

    /* ===== ENUMS ===== */

    public enum Status {
        Status_Ok(0),
        Status_Error(1),
        Status_Critical(2);

        private final int value;

        Status(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static Status fromInteger(int x) {
            switch (x) {
                case 0:
                    return Status_Ok;
                case 1:
                    return Status_Error;
                case 2:
                    return Status_Critical;
                default:
                    return Status_Error;
            }
        }
    }

    public enum Priority {
        Priority_Default(0),
        Priority_Low(1),
        Priority_Medium(2),
        Priority_High(3),
        Priority_Last(4);

        private final int value;

        Priority(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static Priority fromInteger(int x) {
            switch (x) {
                case 0:
                    return Priority_Default;
                case 1:
                    return Priority_Low;
                case 2:
                    return Priority_Medium;
                case 3:
                    return Priority_High;
                case 4:
                    return Priority_Last;
                default:
                    return Priority_Default;
            }
        }
    }

    /* ===== INSTANCE VARIABLES ===== */

    private long handle;           // C++ pointer to PendingTransaction
    private Status status = Status.Status_Ok;
    private String errorString = "";

    private long amount = 0;
    private long fee = 0;
    private final List<String> txIds = new ArrayList<>();

    /* ===== CONSTRUCTOR ===== */

    protected PendingTransaction(long handle) {
        this.handle = handle;
    }

    /* ===== JNI BINDINGS ===== */

    public native int getStatusNative(long handle);

    public native String getErrorStringNative(long handle);

    public native boolean commitNative(long handle, String filename, boolean overwrite);

    public native long getAmountNative(long handle);

    public native long getFeeNative(long handle);

    public native String[] getTxIdsNative(long handle);

    public native long getTxCountNative(long handle);

    public native void disposeNative(long handle);
    
    public native String getFirstTxId();
    
    public native byte[] getSerializedTransaction();
    
    public native int getStatus();    


    /* ===== JAVA WRAPPERS ===== */

    public String getErrorString() {
        return getErrorStringNative(handle);
    }

    public boolean commit(String filename, boolean overwrite) {
        return commitNative(handle, filename, overwrite);
    }

    public long getAmount() {
        return getAmountNative(handle);
    }

    public long getFee() {
        return getFeeNative(handle);
    }

    public List<String> getTxId() {
        String[] ids = getTxIdsNative(handle);
        txIds.clear();
        if (ids != null) {
            for (String id : ids) {
                txIds.add(id);
            }
        }
        return txIds;
    }

    public long getTxCount() {
        return getTxCountNative(handle);
    }

    public void dispose() {
        if (handle != 0) {
            disposeNative(handle);
            handle = 0;
        }
    }
}

