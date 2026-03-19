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
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // 有设备连接时停止广播
                    stopAdvertisingInternal()
                    _connectionState.value = ConnectionState.Connected(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from ${device.address}")
                    _connectionState.value = ConnectionState.Disconnected
                    subscribedDevices.remove(device)
                    // 断开连接后重新开始广播
                    startAdvertising()
                }
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

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level != -1 && scale != -1) {
                    (level * 100 / scale.toFloat()).toInt()
                } else {
                    0
                }

                // 获取电流（如果有）
                val batteryManager = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val currentNowMicro = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
                val currentNowMa = (currentNowMicro / 1000).toInt()

                Log.d(TAG, "Battery Changed: $batteryPct%, Current: $currentNowMa mA")

                if (currentNowMa != _currentMa.value || batteryPct != _currentBatteryLevel.value) {
                    _currentMa.value = currentNowMa
                    _currentBatteryLevel.value = batteryPct
                    updateCharacteristic(currentNowMa)
                }
            }
        }
    }

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth ON, restarting server")
                        startServer()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth OFF, stopping server")
                        stopServerInternal()
                    }
                }
            }
        }
    }

    init {
        val bluetoothFilter = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothReceiver, bluetoothFilter)
        
        val batteryFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, batteryFilter)
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (!BlePermissionHelper.hasRequiredPermissions(context)) {
            Log.e(TAG, "Missing permissions for BLE server")
            return
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth is disabled, cannot start server")
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
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BleServiceConstants.BATTERY_SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertisingCallback)
    }

    private fun startBatteryMonitoring() {
        // Now using batteryReceiver for updates, but we can still perform an initial check
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentNowMicro = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentNowMa = (currentNowMicro / 1000).toInt()
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        _currentMa.value = currentNowMa
        _currentBatteryLevel.value = level
        updateCharacteristic(currentNowMa)
        
        Log.d(TAG, "Initial battery state: $level%, $currentNowMa mA")
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
    private fun stopAdvertisingInternal() {
        try {
            advertiser?.stopAdvertising(advertisingCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising", e)
        }
        advertiser = null
        _isAdvertising.value = false
    }

    @SuppressLint("MissingPermission")
    private fun stopServerInternal() {
        batteryMonitorJob?.cancel()
        batteryMonitorJob = null
        
        stopAdvertisingInternal()
        
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing gattServer", e)
        }
        gattServer = null
        
        subscribedDevices.clear()
        _connectionState.value = ConnectionState.Disconnected
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            // Already unregistered or context issues
        }
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        stopServerInternal()
    }
}