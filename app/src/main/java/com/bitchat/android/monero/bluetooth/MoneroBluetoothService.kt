package com.bitchat.android.monero.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.bitchat.android.model.UserProfile;
import com.bitchat.android.monero.messaging.MoneroMessageHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enhanced Bluetooth service with Monero capability advertisement and messaging
 * Extends BitChat's existing Bluetooth functionality
 */
public class MoneroBluetoothService {
    
    private static final String TAG = "MoneroBluetoothService";
    
    // Service UUIDs
    public static final UUID BITCHAT_SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB");
    public static final UUID MONERO_SERVICE_UUID = UUID.fromString("00001235-0000-1000-8000-00805F9B34FB");
    
    // Characteristic UUIDs
    public static final UUID MESSAGE_CHARACTERISTIC_UUID = UUID.fromString("00002234-0000-1000-8000-00805F9B34FB");
    public static final UUID MONERO_CHARACTERISTIC_UUID = UUID.fromString("00002235-0000-1000-8000-00805F9B34FB");
    
    private static MoneroBluetoothService instance;
    
    private Context context;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer gattServer;
    
    // State management
    private boolean isAdvertising = false;
    private boolean isScanning = false;
    private boolean isMoneroEnabled = false;
    private UserProfile localUserProfile;
    
    // Connected devices and their profiles
    private Map<String, UserProfile> connectedDevices = new HashMap<>();
    private Map<String, BluetoothGatt> deviceConnections = new HashMap<>();
    
    // Listeners
    private BluetoothServiceListener serviceListener;
    private MoneroMessageHandler messageHandler;
    
    public interface BluetoothServiceListener {
        void onDeviceDiscovered(UserProfile userProfile);
        void onDeviceConnected(UserProfile userProfile);
        void onDeviceDisconnected(String deviceId);
        void onMessageReceived(String message, String fromDeviceId);
        void onMoneroMessageReceived(String message, String fromDeviceId);
        void onAdvertisingStarted();
        void onAdvertisingFailed(int errorCode);
    }
    
    private MoneroBluetoothService(Context context) {
        this.context = context.getApplicationContext();
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager.getAdapter();
        this.messageHandler = new MoneroMessageHandler();
        
        if (bluetoothAdapter != null) {
            this.advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            this.scanner = bluetoothAdapter.getBluetoothLeScanner();
        }
    }
    
    public static synchronized MoneroBluetoothService getInstance(Context context) {
        if (instance == null) {
            instance = new MoneroBluetoothService(context);
        }
        return instance;
    }
    
    public void setServiceListener(BluetoothServiceListener listener) {
        this.serviceListener = listener;
    }
    
    public void setLocalUserProfile(UserProfile profile) {
        this.localUserProfile = profile;
        this.isMoneroEnabled = profile.isMoneroCapable();
    }
    
    /**
     * Start advertising BitChat service with Monero capability
     */
    public void startAdvertising() {
        if (!isBluetoothAvailable() || isAdvertising) {
            return;
        }
        
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();
        
        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(BITCHAT_SERVICE_UUID));
        
        // Add Monero capability if enabled
        if (isMoneroEnabled) {
            dataBuilder.addServiceUuid(new ParcelUuid(MONERO_SERVICE_UUID));
            // Add service data to indicate Monero support
            byte[] moneroData = new byte[]{0x01}; // Monero capability flag
            dataBuilder.addServiceData(new ParcelUuid(MONERO_SERVICE_UUID), moneroData);
        }
        
        AdvertiseData advertiseData = dataBuilder.build();
        
        advertiser.startAdvertising(settings, advertiseData, advertiseCallback);
        Log.d(TAG, "Started advertising with Monero capability: " + isMoneroEnabled);
    }
    
    /**
     * Stop advertising
     */
    public void stopAdvertising() {
        if (!isBluetoothAvailable() || !isAdvertising) {
            return;
        }
        
        advertiser.stopAdvertising(advertiseCallback);
        isAdvertising = false;
        Log.d(TAG, "Stopped advertising");
    }
    
    /**
     * Start scanning for BitChat and Monero-enabled devices
     */
    public void startScanning() {
        if (!isBluetoothAvailable() || isScanning) {
            return;
        }
        
        List<ScanFilter> filters = new ArrayList<>();
        
        // Filter for BitChat service
        ScanFilter bitchatFilter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BITCHAT_SERVICE_UUID))
                .build();
        filters.add(bitchatFilter);
        
        // Filter for Monero service
        ScanFilter moneroFilter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(MONERO_SERVICE_UUID))
                .build();
        filters.add(moneroFilter);
        
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        
        scanner.startScan(filters, settings, scanCallback);
        isScanning = true;
        Log.d(TAG, "Started scanning for devices");
    }
    
    /**
     * Stop scanning
     */
    public void stopScanning() {
        if (!isBluetoothAvailable() || !isScanning) {
            return;
        }
        
        scanner.stopScan(scanCallback);
        isScanning = false;
        Log.d(TAG, "Stopped scanning");
    }
    
    /**
     * Connect to a discovered device
     */
    public void connectToDevice(UserProfile userProfile) {
        if (!isBluetoothAvailable()) {
            return;
        }
        
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(userProfile.getDeviceId());
        if (device != null) {
            BluetoothGatt gatt = device.connectGatt(context, false, gattCallback);
            deviceConnections.put(userProfile.getDeviceId(), gatt);
            Log.d(TAG, "Connecting to device: " + userProfile.getDisplayName());
        }
    }
    
    /**
     * Send message to connected device
     */
    public void sendMessage(String deviceId, String message) {
        BluetoothGatt gatt = deviceConnections.get(deviceId);
        if (gatt == null) {
            Log.w(TAG, "No connection to device: " + deviceId);
            return;
        }
        
        BluetoothGattService service = gatt.getService(BITCHAT_SERVICE_UUID);
        if (service == null) {
            Log.w(TAG, "BitChat service not found on device: " + deviceId);
            return;
        }
        
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID);
        if (characteristic == null) {
            Log.w(TAG, "Message characteristic not found on device: " + deviceId);
            return;
        }
        
        characteristic.setValue(message.getBytes());
        boolean success = gatt.writeCharacteristic(characteristic);
        Log.d(TAG, "Sending message to " + deviceId + ": " + success);
    }
    
    /**
     * Send Monero message to connected device
     */
    public void sendMoneroMessage(String deviceId, String moneroMessage) {
        BluetoothGatt gatt = deviceConnections.get(deviceId);
        if (gatt == null) {
            Log.w(TAG, "No connection to device: " + deviceId);
            return;
        }
        
        BluetoothGattService service = gatt.getService(MONERO_SERVICE_UUID);
        if (service == null) {
            Log.w(TAG, "Monero service not found on device: " + deviceId);
            return;
        }
        
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(MONERO_CHARACTERISTIC_UUID);
        if (characteristic == null) {
            Log.w(TAG, "Monero characteristic not found on device: " + deviceId);
            return;
        }
        
        characteristic.setValue(moneroMessage.getBytes());
        boolean success = gatt.writeCharacteristic(characteristic);
        Log.d(TAG, "Sending Monero message to " + deviceId + ": " + success);
    }
    
    /**
     * Setup GATT server for incoming connections
     */
    public void startGattServer() {
        if (!isBluetoothAvailable()) {
            return;
        }
        
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        
        // Add BitChat service
        BluetoothGattService bitchatService = new BluetoothGattService(
                BITCHAT_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        
        BluetoothGattCharacteristic messageChar = new BluetoothGattCharacteristic(
                MESSAGE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        
        bitchatService.addCharacteristic(messageChar);
        gattServer.addService(bitchatService);
        
        // Add Monero service if enabled
        if (isMoneroEnabled) {
            BluetoothGattService moneroService = new BluetoothGattService(
                    MONERO_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            
            BluetoothGattCharacteristic moneroChar = new BluetoothGattCharacteristic(
                    MONERO_CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
            
            moneroService.addCharacteristic(moneroChar);
            gattServer.addService(moneroService);
        }
        
        Log.d(TAG, "GATT server started with Monero support: " + isMoneroEnabled);
    }
    
    /**
     * Stop GATT server
     */
    public void stopGattServer() {
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
    }
    
    private boolean isBluetoothAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }
    
    // Advertise callback
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isAdvertising = true;
            Log.d(TAG, "Advertising started successfully");
            if (serviceListener != null) {
                serviceListener.onAdvertisingStarted();
            }
        }
        
        @Override
        public void onStartFailure(int errorCode) {
            isAdvertising = false;
            Log.e(TAG, "Advertising failed with error: " + errorCode);
            if (serviceListener != null) {
                serviceListener.onAdvertisingFailed(errorCode);
            }
        }
    };
    
    // Scan callback
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;
            
            String deviceId = device.getAddress();
            String deviceName = device.getName();
            
            if (deviceName == null || deviceName.isEmpty()) {
                deviceName = "Unknown Device";
            }
            
            // Check for Monero capability in scan record
            boolean hasMoneroCapability = hasMoneroCapability(result);
            
            UserProfile userProfile = new UserProfile(deviceName, deviceId, hasMoneroCapability);
            userProfile.setOnline(true);
            userProfile.setLastSeen(System.currentTimeMillis());
            
            Log.d(TAG, "Discovered device: " + userProfile.getDisplayName());
            
            if (serviceListener != null) {
                serviceListener.onDeviceDiscovered(userProfile);
            }
        }
        
        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed with error: " + errorCode);
            isScanning = false;
        }
    };
    
    private boolean hasMoneroCapability(ScanResult result) {
        List<ParcelUuid> serviceUuids = result.getScanRecord().getServiceUuids();
        if (serviceUuids != null) {
            for (ParcelUuid uuid : serviceUuids) {
                if (MONERO_SERVICE_UUID.equals(uuid.getUuid())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    // GATT callback for client connections
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String deviceId = gatt.getDevice().getAddress();
            
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to device: " + deviceId);
                gatt.discoverServices();
                
                UserProfile profile = connectedDevices.get(deviceId);
                if (profile != null && serviceListener != null) {
                    serviceListener.onDeviceConnected(profile);
                }
                
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from device: " + deviceId);
                deviceConnections.remove(deviceId);
                connectedDevices.remove(deviceId);
                
                if (serviceListener != null) {
                    serviceListener.onDeviceDisconnected(deviceId);
                }
            }
        }
        
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Message sent successfully to " + gatt.getDevice().getAddress());
            } else {
                Log.w(TAG, "Failed to send message to " + gatt.getDevice().getAddress() + ", status: " + status);
            }
        }
        
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            String deviceId = gatt.getDevice().getAddress();
            String message = new String(characteristic.getValue());
            
            UUID charUuid = characteristic.getUuid();
            
            if (MESSAGE_CHARACTERISTIC_UUID.equals(charUuid)) {
                // Regular message
                if (serviceListener != null) {
                    serviceListener.onMessageReceived(message, deviceId);
                }
            } else if (MONERO_CHARACTERISTIC_UUID.equals(charUuid)) {
                // Monero message
                if (serviceListener != null) {
                    serviceListener.onMoneroMessageReceived(message, deviceId);
                }
            }
        }
    };
    
    // GATT server callback for incoming connections
    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            String deviceId = device.getAddress();
            
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device connected to server: " + deviceId);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected from server: " + deviceId);
            }
        }
        
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, 
                BluetoothGattCharacteristic characteristic, boolean preparedWrite, 
                boolean responseNeeded, int offset, byte[] value) {
            
            String deviceId = device.getAddress();
            String message = new String(value);
            UUID charUuid = characteristic.getUuid();
            
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            
            if (MESSAGE_CHARACTERISTIC_UUID.equals(charUuid)) {
                // Regular message received
                if (serviceListener != null) {
                    serviceListener.onMessageReceived(message, deviceId);
                }
            } else if (MONERO_CHARACTERISTIC_UUID.equals(charUuid)) {
                // Monero message received
                if (serviceListener != null) {
                    serviceListener.onMoneroMessageReceived(message, deviceId);
                }
            }
            
            Log.d(TAG, "Received message from " + deviceId + ": " + message);
        }
    };
    
    /**
     * Cleanup resources
     */
    public void cleanup() {
        stopAdvertising();
        stopScanning();
        stopGattServer();
        
        // Disconnect all devices
        for (BluetoothGatt gatt : deviceConnections.values()) {
            gatt.disconnect();
            gatt.close();
        }
        deviceConnections.clear();
        connectedDevices.clear();
        
        Log.d(TAG, "Bluetooth service cleaned up");
    }
    
    public boolean isAdvertising() {
        return isAdvertising;
    }
    
    public boolean isScanning() {
        return isScanning;
    }
    
    public Map<String, UserProfile> getConnectedDevices() {
        return new HashMap<>(connectedDevices);
    }
}
