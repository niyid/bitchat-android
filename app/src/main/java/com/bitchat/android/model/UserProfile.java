package com.bitchat.android.model;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.ParcelUuid;
import java.util.UUID;

/**
 * Enhanced UserProfile with Monero capability support
 * Shows (μ) indicator for users who can receive Monero payments
 */
public class UserProfile {
    
    private String username;
    private String deviceId;
    private boolean moneroCapable = false;
    private String moneroAddress;
    private long lastSeen;
    private boolean isOnline;
    
    // Bluetooth service UUID for Monero capability advertisement
    public static final UUID MONERO_SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB");
    
    // Greek letter mu symbol for Monero indicator
    private static final String MONERO_INDICATOR = "(μ)";
    
    public UserProfile() {
        // Default constructor
    }
    
    public UserProfile(String username, String deviceId) {
        this.username = username;
        this.deviceId = deviceId;
    }
    
    public UserProfile(String username, String deviceId, boolean moneroCapable) {
        this.username = username;
        this.deviceId = deviceId;
        this.moneroCapable = moneroCapable;
    }
    
    // Getters and Setters
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getDeviceId() {
        return deviceId;
    }
    
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
    
    public boolean isMoneroCapable() {
        return moneroCapable;
    }
    
    public void setMoneroCapable(boolean moneroCapable) {
        this.moneroCapable = moneroCapable;
        // Update Bluetooth advertisement when capability changes
        updateBluetoothAdvertisement();
    }
    
    public String getMoneroAddress() {
        return moneroAddress;
    }
    
    public void setMoneroAddress(String moneroAddress) {
        this.moneroAddress = moneroAddress;
        // If we have an address, we're Monero capable
        if (moneroAddress != null && !moneroAddress.isEmpty()) {
            setMoneroCapable(true);
        }
    }
    
    public long getLastSeen() {
        return lastSeen;
    }
    
    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }
    
    public boolean isOnline() {
        return isOnline;
    }
    
    public void setOnline(boolean online) {
        isOnline = online;
    }
    
    /**
     * Get display name with Monero capability indicator
     * Returns "username (μ)" for Monero-capable users, "username" otherwise
     */
    public String getDisplayName() {
        if (moneroCapable && username != null) {
            return username + " " + MONERO_INDICATOR;
        }
        return username != null ? username : "";
    }
    
    /**
     * Get the raw username without any indicators
     */
    public String getRawUsername() {
        return username != null ? username : "";
    }
    
    /**
     * Check if this user can receive Monero payments
     */
    public boolean canReceiveMonero() {
        return moneroCapable && moneroAddress != null && !moneroAddress.isEmpty();
    }
    
    /**
     * Update Bluetooth advertisement to include Monero capability
     * This should be called whenever Monero capability changes
     */
    public void updateBluetoothAdvertisement() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                return;
            }
            
            BluetoothLeAdvertiser advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                return;
            }
            
            AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder();
            dataBuilder.setIncludeDeviceName(true);
            dataBuilder.setIncludeTxPowerLevel(false);
            
            // Add Monero capability to service data
            if (moneroCapable) {
                byte[] serviceData = new byte[]{0x01}; // Flag indicating Monero support
                dataBuilder.addServiceData(new ParcelUuid(MONERO_SERVICE_UUID), serviceData);
            }
            
            // Note: Actual advertising should be handled by BluetoothService
            // This method prepares the data structure
            
        } catch (Exception e) {
            // Handle Bluetooth errors gracefully
            e.printStackTrace();
        }
    }
    
    /**
     * Create UserProfile from Bluetooth scan data
     */
    public static UserProfile fromBluetoothScanResult(String deviceName, String deviceId, byte[] scanRecord) {
        UserProfile profile = new UserProfile();
        profile.setUsername(deviceName);
        profile.setDeviceId(deviceId);
        
        // Parse scan record for Monero capability
        if (scanRecord != null) {
            profile.setMoneroCapable(parseMoneroCapabilityFromScanRecord(scanRecord));
        }
        
        return profile;
    }
    
    /**
     * Parse Bluetooth scan record to detect Monero capability
     */
    private static boolean parseMoneroCapabilityFromScanRecord(byte[] scanRecord) {
        try {
            // Simple parsing - look for our service UUID and data
            // In a real implementation, you'd want more robust parsing
            String recordString = new String(scanRecord);
            return recordString.contains(MONERO_SERVICE_UUID.toString().substring(0, 8));
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get Bluetooth advertisement data for this profile
     */
    public AdvertiseData getAdvertiseData() {
        AdvertiseData.Builder builder = new AdvertiseData.Builder();
        builder.setIncludeDeviceName(true);
        builder.setIncludeTxPowerLevel(false);
        
        if (moneroCapable) {
            byte[] serviceData = new byte[]{0x01}; // Monero capability flag
            builder.addServiceData(new ParcelUuid(MONERO_SERVICE_UUID), serviceData);
        }
        
        return builder.build();
    }
    
    @Override
    public String toString() {
        return "UserProfile{" +
                "username='" + username + '\'' +
                ", deviceId='" + deviceId + '\'' +
                ", moneroCapable=" + moneroCapable +
                ", displayName='" + getDisplayName() + '\'' +
                ", isOnline=" + isOnline +
                '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        UserProfile that = (UserProfile) obj;
        return deviceId != null ? deviceId.equals(that.deviceId) : that.deviceId == null;
    }
    
    @Override
    public int hashCode() {
        return deviceId != null ? deviceId.hashCode() : 0;
    }
    
    /**
     * Create a copy of this profile
     */
    public UserProfile copy() {
        UserProfile copy = new UserProfile();
        copy.username = this.username;
        copy.deviceId = this.deviceId;
        copy.moneroCapable = this.moneroCapable;
        copy.moneroAddress = this.moneroAddress;
        copy.lastSeen = this.lastSeen;
        copy.isOnline = this.isOnline;
        return copy;
    }
    
    /**
     * Update this profile with data from another profile
     */
    public void updateFrom(UserProfile other) {
        if (other == null) return;
        
        if (other.username != null) {
            this.username = other.username;
        }
        this.moneroCapable = other.moneroCapable;
        if (other.moneroAddress != null) {
            this.moneroAddress = other.moneroAddress;
        }
        this.lastSeen = other.lastSeen;
        this.isOnline = other.isOnline;
    }
    
    /**
     * Validate if the profile has minimum required data
     */
    public boolean isValid() {
        return username != null && !username.trim().isEmpty() 
               && deviceId != null && !deviceId.trim().isEmpty();
    }
    
    /**
     * Get status string for UI display
     */
    public String getStatusString() {
        if (isOnline) {
            return "Online" + (moneroCapable ? " • Monero enabled" : "");
        } else {
            return "Last seen: " + formatLastSeen();
        }
    }
    
    private String formatLastSeen() {
        if (lastSeen == 0) return "Unknown";
        
        long now = System.currentTimeMillis();
        long diff = now - lastSeen;
        
        if (diff < 60000) { // Less than 1 minute
            return "Just now";
        } else if (diff < 3600000) { // Less than 1 hour
            return (diff / 60000) + " minutes ago";
        } else if (diff < 86400000) { // Less than 1 day
            return (diff / 3600000) + " hours ago";
        } else {
            return (diff / 86400000) + " days ago";
        }
    }
}
