package com.bitchat.android.monero.utils;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Utility class for validating Monero addresses, amounts, and other inputs
 */
public final class MoneroValidator {
    
    // Monero address patterns
    private static final Pattern MAINNET_ADDRESS_PATTERN = Pattern.compile("^4[0-9AB][1-9A-HJ-NP-Za-km-z]{93}$");
    private static final Pattern TESTNET_ADDRESS_PATTERN = Pattern.compile("^9[0-9AB][1-9A-HJ-NP-Za-km-z]{93}$");
    private static final Pattern STAGENET_ADDRESS_PATTERN = Pattern.compile("^5[0-9AB][1-9A-HJ-NP-Za-km-z]{93}$");
    
    // Transaction ID pattern
    private static final Pattern TX_ID_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");
    
    // Username pattern
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    
    // Amount limits
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.000001"); // 1 micromonero
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("18400000"); // ~Total supply
    
    // Private constructor to prevent instantiation
    private MoneroValidator() {
        throw new AssertionError("Cannot instantiate utility class");
    }
    
    /**
     * Validate Monero address
     */
    public static boolean isValidAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = address.trim();
        return MAINNET_ADDRESS_PATTERN.matcher(trimmed).matches() ||
               TESTNET_ADDRESS_PATTERN.matcher(trimmed).matches() ||
               STAGENET_ADDRESS_PATTERN.matcher(trimmed).matches();
    }
    
    /**
     * Check if address is mainnet
     */
    public static boolean isMainnetAddress(String address) {
        if (address == null) {
            return false;
        }
        return MAINNET_ADDRESS_PATTERN.matcher(address.trim()).matches();
    }
    
    /**
     * Check if address is testnet
     */
    public static boolean isTestnetAddress(String address) {
        if (address == null) {
            return false;
        }
        return TESTNET_ADDRESS_PATTERN.matcher(address.trim()).matches();
    }
    
    /**
     * Validate XMR amount
     */
    public static ValidationResult validateAmount(String amount) {
        if (amount == null || amount.trim().isEmpty()) {
            return ValidationResult.error("Amount cannot be empty");
        }
        
        try {
            BigDecimal xmr = new BigDecimal(amount.trim());
            
            if (xmr.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error("Amount must be greater than zero");
            }
            
            if (xmr.compareTo(MIN_AMOUNT) < 0) {
                return ValidationResult.error("Amount too small (minimum: " + MIN_AMOUNT + " XMR)");
            }
            
            if (xmr.compareTo(MAX_AMOUNT) > 0) {
                return ValidationResult.error("Amount too large (maximum: " + MAX_AMOUNT + " XMR)");
            }
            
            if (xmr.scale() > 12) {
                return ValidationResult.error("Too many decimal places (maximum: 12)");
            }
            
            return ValidationResult.success("Valid amount");
            
        } catch (NumberFormatException e) {
            return ValidationResult.error("Invalid number format");
        }
    }
    
    /**
     * Validate transaction ID
     */
    public static boolean isValidTransactionId(String txId) {
        if (txId == null) {
            return false;
        }
        return TX_ID_PATTERN.matcher(txId.trim()).matches();
    }
    
    /**
     * Validate username for BitChat
     */
    public static ValidationResult validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return ValidationResult.error("Username cannot be empty");
        }
        
        String trimmed = username.trim();
        
        if (trimmed.length() < 3) {
            return ValidationResult.error("Username too short (minimum: 3 characters)");
        }
        
        if (trimmed.length() > 20) {
            return ValidationResult.error("Username too long (maximum: 20 characters)");
        }
        
        if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
            return ValidationResult.error("Username can only contain letters, numbers, underscore, and dash");
        }
        
        return ValidationResult.success("Valid username");
    }
    
    /**
     * Check if balance is sufficient for transaction
     */
    public static boolean hasSufficientBalance(String balance, String amount) {
        try {
            BigDecimal balanceDecimal = new BigDecimal(balance);
            BigDecimal amountDecimal = new BigDecimal(amount);
            return balanceDecimal.compareTo(amountDecimal) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * Validation result class
     */
    public static class ValidationResult {
        private final boolean isValid;
        private final String message;
        
        private ValidationResult(boolean isValid, String message) {
            this.isValid = isValid;
            this.message = message;
        }
        
        public static ValidationResult success(String message) {
            return new ValidationResult(true, message);
        }
        
        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
        
        public boolean isValid() {
            return isValid;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            
            ValidationResult that = (ValidationResult) obj;
            return isValid == that.isValid && 
                   (message != null ? message.equals(that.message) : that.message == null);
        }
        
        @Override
        public int hashCode() {
            int result = Boolean.hashCode(isValid);
            result = 31 * result + (message != null ? message.hashCode() : 0);
            return result;
        }
        
        @Override
        public String toString() {
            return "ValidationResult{" +
                   "isValid=" + isValid +
                   ", message='" + message + '\'' +
                   '}';
        }
    }
}
