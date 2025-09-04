package com.bitchat.android.monero.utils

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*

/**
 * Utility class for formatting Monero amounts and addresses
 */
object MoneroFormatter {
    
    private val PICONERO_MULTIPLIER = BigInteger.valueOf(1_000_000_000_000L)
    private val XMR_FORMAT = DecimalFormat("#,##0.000000").apply {
        minimumFractionDigits = 6
        maximumFractionDigits = 12
        isGroupingUsed = true
    }
    private val CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(Locale.US)
    
    /**
     * Convert atomic units (piconeros) to XMR string
     */
    fun atomicToXmr(atomic: BigInteger?): String {
        if (atomic == null) return "0.000000"
        
        val xmr = BigDecimal(atomic).divide(
            BigDecimal(PICONERO_MULTIPLIER), 12, RoundingMode.DOWN
        )
        return XMR_FORMAT.format(xmr)
    }
    
    /**
     * Convert XMR string to atomic units (piconeros)
     */
    fun xmrToAtomic(xmrAmount: String?): BigInteger {
        if (xmrAmount?.trim().isNullOrEmpty()) {
            throw NumberFormatException("Empty amount")
        }
        
        return try {
            val xmr = BigDecimal(xmrAmount.trim())
            if (xmr < BigDecimal.ZERO) {
                throw NumberFormatException("Negative amount")
            }
            
            val atomic = xmr.multiply(BigDecimal(PICONERO_MULTIPLIER))
            atomic.toBigInteger()
        } catch (e: NumberFormatException) {
            throw NumberFormatException("Invalid amount format: $xmrAmount")
        }
    }
    
    /**
     * Format XMR amount for display with proper precision
     */
    fun formatXmr(amount: String): String {
        return try {
            val xmr = BigDecimal(amount)
            "${XMR_FORMAT.format(xmr)} XMR"
        } catch (e: NumberFormatException) {
            "$amount XMR"
        }
    }
    
    /**
     * Format XMR amount as currency (for USD conversion)
     */
    fun formatAsCurrency(amount: BigDecimal): String {
        return CURRENCY_FORMAT.format(amount)
    }
    
    /**
     * Abbreviate Monero address for display
     */
    fun abbreviateAddress(address: String?): String? {
        if (address == null || address.length < 20) {
            return address
        }
        
        return "${address.substring(0, 8)}...${address.substring(address.length - 8)}"
    }
    
    /**
     * Format transaction hash for display
     */
    fun abbreviateTransactionId(txId: String?): String? {
        if (txId == null || txId.length < 16) {
            return txId
        }
        
        return "${txId.substring(0, 8)}...${txId.substring(txId.length - 8)}"
    }
    
    /**
     * Validate XMR amount format
     */
    fun isValidAmount(amount: String?): Boolean {
        if (amount?.trim().isNullOrEmpty()) {
            return false
        }
        
        return try {
            val xmr = BigDecimal(amount.trim())
            xmr > BigDecimal.ZERO && xmr.scale() <= 12 // Max 12 decimal places
        } catch (e: NumberFormatException) {
            false
        }
    }
    
    /**
     * Get maximum precision for XMR amounts
     */
    fun getMaxPrecision(): Int = 12
    
    /**
     * Round XMR amount to specified decimal places
     */
    fun roundXmr(amount: String, decimalPlaces: Int): String {
        return try {
            val xmr = BigDecimal(amount)
            val rounded = xmr.setScale(decimalPlaces, RoundingMode.DOWN)
            rounded.toPlainString()
        } catch (e: NumberFormatException) {
            amount
        }
    }
}
