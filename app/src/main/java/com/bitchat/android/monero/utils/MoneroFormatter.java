package com.bitchat.android.monero.utils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Utility class for formatting Monero amounts and addresses
 */
public final class MoneroFormatter {
    
    private static final BigInteger PICONERO_MULTIPLIER = BigInteger.valueOf(1_000_000_000_000L);
    private static final DecimalFormat XMR_FORMAT;
    private static final NumberFormat CURRENCY_FORMAT;
    
    static {
        XMR_FORMAT = new DecimalFormat("#,##0.000000");
        XMR_FORMAT.setMinimumFractionDigits(6);
        XMR_FORMAT.setMaximumFractionDigits(12);
        XMR_FORMAT.setGroupingUsed(true);
        
        CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(Locale.US);
    }
    
    // Private constructor to prevent instantiation
    private MoneroFormatter() {
        throw new AssertionError("Cannot instantiate utility class");
    }
    
    /**
     * Convert atomic units (piconeros) to XMR string
     */
    public static String atomicToXmr(BigInteger atomic) {
        if (atomic == null) {
            return "0.000000";
        }
        
        BigDecimal xmr = new BigDecimal(atomic).divide(
            new BigDecimal(PICONERO_MULTIPLIER), 12, RoundingMode.DOWN
        );
        return XMR_FORMAT.format(xmr);
    }
    
    /**
     * Convert XMR string to atomic units (piconeros)
     */
    public static BigInteger xmrToAtomic(String xmrAmount) throws NumberFormatException {
        if (xmrAmount == null || xmrAmount.trim().isEmpty()) {
            throw new NumberFormatException("Empty amount");
        }
        
        try {
            BigDecimal xmr = new BigDecimal(xmrAmount.trim());
            if (xmr.compareTo(BigDecimal.ZERO) < 0) {
                throw new NumberFormatException("Negative amount");
            }
            
            BigDecimal atomic = xmr.multiply(new BigDecimal(PICONERO_MULTIPLIER));
            return atomic.toBigInteger();
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Invalid amount format: " + xmrAmount);
        }
    }
    
    /**
     * Format XMR amount for display with proper precision
     */
    public static String formatXmr(String amount) {
        try {
            BigDecimal xmr = new BigDecimal(amount);
            return XMR_FORMAT.format(xmr) + " XMR";
        } catch (NumberFormatException e) {
            return amount + " XMR";
        }
    }
    
    /**
     * Format XMR amount as currency (for USD conversion)
     */
    public static String formatAsCurrency(BigDecimal amount) {
        return CURRENCY_FORMAT.format(amount);
    }
    
    /**
     * Abbreviate Monero address for display
     */
    public static String abbreviateAddress(String address) {
        if (address == null || address.length() < 20) {
            return address;
        }
        
        return address.substring(0, 8) + "..." + address.substring(address.length() - 8);
    }
    
    /**
     * Format transaction hash for display
     */
    public static String abbreviateTransactionId(String txId) {
        if (txId == null || txId.length() < 16) {
            return txId;
        }
        
        return txId.substring(0, 8) + "..." + txId.substring(txId.length() - 8);
    }
    
    /**
     * Validate XMR amount format
     */
    public static boolean isValidAmount(String amount) {
        if (amount == null || amount.trim().isEmpty()) {
            return false;
        }
        
        try {
            BigDecimal xmr = new BigDecimal(amount.trim());
            return xmr.compareTo(BigDecimal.ZERO) > 0 && xmr.scale() <= 12; // Max 12 decimal places
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * Get maximum precision for XMR amounts
     */
    public static int getMaxPrecision() {
        return 12;
    }
    
    /**
     * Round XMR amount to specified decimal places
     */
    public static String roundXmr(String amount, int decimalPlaces) {
        try {
            BigDecimal xmr = new BigDecimal(amount);
            BigDecimal rounded = xmr.setScale(decimalPlaces, RoundingMode.DOWN);
            return rounded.toPlainString();
        } catch (NumberFormatException e) {
            return amount;
        }
    }
}
