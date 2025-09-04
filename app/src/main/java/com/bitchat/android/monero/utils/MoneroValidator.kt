package com.bitchat.android.monero.utils

import java.math.BigDecimal
import java.util.regex.Pattern

/**
 * Utility class for validating Monero addresses, amounts, and other inputs
 */
object MoneroValidator {
    
    // Monero address patterns
    private val MAINNET_ADDRESS_PATTERN = Pattern.compile("^4[0-9AB][1-9A-HJ-NP-Za-km-z]{93}$")
    private val TESTNET_ADDRESS_PATTERN = Pattern.compile("^9[0-9AB][1-9A-HJ-NP-Za-km-z]{93}$")
    private val STAGENET_ADDRESS_PATTERN = Pattern.compile("^5[0-9AB][1-9A-HJ-NP-Za-km-z]{93}$")
    
    // Transaction ID pattern
    private val TX_ID_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$")
    
    // Amount limits
    private val MIN_AMOUNT = BigDecimal("0.000001") // 1 micromonero
    private val MAX_AMOUNT = BigDecimal("18400000") // ~Total supply
    
    /**
     * Validate Monero address
     */
    fun isValidAddress(address: String?): Boolean {
        if (address?.trim().isNullOrEmpty()) {
            return false
        }
        
        val trimmed = address.trim()
        return MAINNET_ADDRESS_PATTERN.matcher(trimmed).matches() ||
               TESTNET_ADDRESS_PATTERN.matcher(trimmed).matches() ||
               STAGENET_ADDRESS_PATTERN.matcher(trimmed).matches()
    }
    
    /**
     * Check if address is mainnet
     */
    fun isMainnetAddress(address: String?): Boolean {
        return address?.let { MAINNET_ADDRESS_PATTERN.matcher(it.trim()).matches() } == true
    }
    
    /**
     * Check if address is testnet
     */
    fun isTestnetAddress(address: String?): Boolean {
        return address?.let { TESTNET_ADDRESS_PATTERN.matcher(it.trim()).matches() } == true
    }
    
    /**
     * Validate XMR amount
     */
    fun validateAmount(amount: String?): ValidationResult {
        if (amount?.trim().isNullOrEmpty()) {
            return ValidationResult.error("Amount cannot be empty")
        }
        
        return try {
            val xmr = BigDecimal(amount.trim())
            
            when {
                xmr <= BigDecimal.ZERO -> 
                    ValidationResult.error("Amount must be greater than zero")
                
                xmr < MIN_AMOUNT -> 
                    ValidationResult.error("Amount too small (minimum: $MIN_AMOUNT XMR)")
                
                xmr > MAX_AMOUNT -> 
                    ValidationResult.error("Amount too large (maximum: $MAX_AMOUNT XMR)")
                
                xmr.scale() > 12 -> 
                    ValidationResult.error("Too many decimal places (maximum: 12)")
                
                else -> ValidationResult.success("Valid amount")
            }
            
        } catch (e: NumberFormatException) {
            ValidationResult.error("Invalid number format")
        }
    }
    
    /**
     * Validate transaction ID
     */
    fun isValidTransactionId(txId: String?): Boolean {
        return txId?.let { TX_ID_PATTERN.matcher(it.trim()).matches() } == true
    }
    
    /**
     * Validate username for BitChat
     */
    fun validateUsername(username: String?): ValidationResult {
        if (username?.trim().isNullOrEmpty()) {
            return ValidationResult.error("Username cannot be empty")
        }
        
        val trimmed = username.trim()
        
        return when {
            trimmed.length < 3 -> 
                ValidationResult.error("Username too short (minimum: 3 characters)")
            
            trimmed.length > 20 -> 
                ValidationResult.error("Username too long (maximum: 20 characters)")
            
            !trimmed.matches(Regex("^[a-zA-Z0-9_-]+$")) -> 
                ValidationResult.error("Username can only contain letters, numbers, underscore, and dash")
            
            else -> ValidationResult.success("Valid username")
        }
    }
    
    /**
     * Check if balance is sufficient for transaction
     */
    fun hasSufficientBalance(balance: String, amount: String): Boolean {
        return try {
            val balanceDecimal = BigDecimal(balance)
            val amountDecimal = BigDecimal(amount)
            balanceDecimal >= amountDecimal
        } catch (e: NumberFormatException) {
            false
        }
    }
    
    /**
     * Validation result class
     */
    data class ValidationResult(val isValid: Boolean, val message: String) {
        companion object {
            fun success(message: String) = ValidationResult(true, message)
            fun error(message: String) = ValidationResult(false, message)
        }
    }
}
