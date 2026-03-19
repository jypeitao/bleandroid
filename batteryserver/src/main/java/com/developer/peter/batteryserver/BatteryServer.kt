package com.developer.peter.batteryserver

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.BatteryManager
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import com.developer.peter.batteryserver.data.BleServiceConstants
import com.developer.peter.batteryserver.data.ConnectionState
import com.developer.peter.batteryserver.util.BlePermissionHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class BatteryServer(private val context: Context) {
    private val TAG = "BatteryServer"
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()
    
    private val _currentBatteryLevel = MutableStateFlow(0)
    val currentBatteryLevel = _currentBatteryLevel.asStateFlow()
    
    private val _currentMa = MutableStateFlow(0)
    val currentMa = _currentMa.asStateFlow()

    private val serverScope = CoroutineScope(Dispatchers.IO + Job())
    private var batteryMonitorJob: Job? = null
    
    private val subscribedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising = _isAdvertising.asStateFlow()

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: $device, status: $status, newState: $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.Connected(device.address)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.Disconnected
                subscribedDevices.remove(device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "onCharacteristicReadRequest: ${characteristic.uuid}")
            if (characteristic.uuid == BleServiceConstants.BATTERY_LEVEL_UUID) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    characteristic.value
                )
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "onDescriptorWriteRequest: ${descriptor.uuid}")
            if (descriptor.uuid == BleServiceConstants.CCC_DESCRIPTOR_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.d(TAG, "Notifications enabled for ${device.address}")
                    subscribedDevices.add(device)
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    Log.d(TAG, "Notifications disabled for ${device.address}")
                    subscribedDevices.remove(device)
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    private val advertisingCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Advertising started successfully")
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "Advertising failed with error: $errorCode")
            _isAdvertising.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (!BlePermissionHelper.hasRequiredPermissions(context)) {
            Log.e(TAG, "Missing permissions for BLE server")
            return
        }

        setupGattServer()
        startAdvertising()
        startBatteryMonitoring()
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        if (gattServer != null) return

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        
        val batteryService = BluetoothGattService(
            BleServiceConstants.BATTERY_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val batteryLevelChar = BluetoothGattCharacteristic(
            BleServiceConstants.BATTERY_LEVEL_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccDescriptor = BluetoothGattDescriptor(
            BleServiceConstants.CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        batteryLevelChar.addDescriptor(cccDescriptor)

        batteryService.addCharacteristic(batteryLevelChar)
        gattServer?.addService(batteryService)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BleServiceConstants.BATTERY_SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertisingCallback)
    }

    private fun startBatteryMonitoring() {
        batteryMonitorJob?.cancel()
        batteryMonitorJob = serverScope.launch {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            while (isActive) {
                // Get current in microamperes, convert to milliamperes
                val currentNowMicro = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val currentNowMa = (currentNowMicro / 1000).toInt()
                
                // Also get battery percentage for standard compliance if needed
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                if (currentNowMa != _currentMa.value) {
                    _currentMa.value = currentNowMa
                    _currentBatteryLevel.value = level
                    updateCharacteristic(currentNowMa)
                }
                delay(1000) // Poll every second
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateCharacteristic(currentMa: Int) {
        val service = gattServer?.getService(BleServiceConstants.BATTERY_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(BleServiceConstants.BATTERY_LEVEL_UUID) ?: return
        
        // We pack currentMa as a 4-byte integer in the characteristic value
        val value = ByteBuffer.allocate(4).putInt(currentMa).array()
        characteristic.value = value
        
        for (device in subscribedDevices) {
            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }
        Log.d(TAG, "Updated characteristic with current: $currentMa mA, notified ${subscribedDevices.size} devices")
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        batteryMonitorJob?.cancel()
        batteryMonitorJob = null
        
        advertiser?.stopAdvertising(advertisingCallback)
        advertiser = null
        _isAdvertising.value = false
        
        gattServer?.close()
        gattServer = null
        
        subscribedDevices.clear()
        _connectionState.value = ConnectionState.Disconnected
    }
}