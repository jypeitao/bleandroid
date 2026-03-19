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

    private val _currentAvgMa = MutableStateFlow(0)
    val currentAvgMa = _currentAvgMa.asStateFlow()

    private val _chargeCounter = MutableStateFlow(0)
    val chargeCounter = _chargeCounter.asStateFlow()

    private val _energyCounter = MutableStateFlow(0L)
    val energyCounter = _energyCounter.asStateFlow()

    private val _batteryStatus = MutableStateFlow(BatteryManager.BATTERY_STATUS_UNKNOWN)
    val batteryStatus = _batteryStatus.asStateFlow()

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

                // 获取更多电流和电池信息
                val batteryManager = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                if (batteryManager != null) {
                    val currentNowMa = (batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000).toInt()
                    val currentAvgMa = (batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) / 1000).toInt()
                    val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    val energyCounter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)

                    Log.d(TAG, "Battery Changed: $batteryPct%, Current: $currentNowMa mA, Avg: $currentAvgMa mA, Charge: $chargeCounter, Energy: $energyCounter, Status: $status")

                    _currentMa.value = currentNowMa
                    _currentAvgMa.value = currentAvgMa
                    _chargeCounter.value = chargeCounter
                    _energyCounter.value = energyCounter
                    _batteryStatus.value = status
                    _currentBatteryLevel.value = batteryPct
                    
                    updateCharacteristics(currentNowMa, currentAvgMa, chargeCounter, energyCounter, status)
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

        val currentAvgChar = BluetoothGattCharacteristic(
            BleServiceConstants.BATTERY_CURRENT_AVG_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val chargeCounterChar = BluetoothGattCharacteristic(
            BleServiceConstants.BATTERY_CHARGE_COUNTER_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val energyCounterChar = BluetoothGattCharacteristic(
            BleServiceConstants.BATTERY_ENERGY_COUNTER_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val batteryStatusChar = BluetoothGattCharacteristic(
            BleServiceConstants.BATTERY_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccDescriptor = BluetoothGattDescriptor(
            BleServiceConstants.CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        batteryLevelChar.addDescriptor(cccDescriptor)
        currentAvgChar.addDescriptor(BluetoothGattDescriptor(BleServiceConstants.CCC_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        chargeCounterChar.addDescriptor(BluetoothGattDescriptor(BleServiceConstants.CCC_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        energyCounterChar.addDescriptor(BluetoothGattDescriptor(BleServiceConstants.CCC_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        batteryStatusChar.addDescriptor(BluetoothGattDescriptor(BleServiceConstants.CCC_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))

        batteryService.addCharacteristic(batteryLevelChar)
        batteryService.addCharacteristic(currentAvgChar)
        batteryService.addCharacteristic(chargeCounterChar)
        batteryService.addCharacteristic(energyCounterChar)
        batteryService.addCharacteristic(batteryStatusChar)
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
        val currentNowMa = (batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000).toInt()
        val currentAvgMa = (batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) / 1000).toInt()
        val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val energyCounter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        // Note: Initial status is hard to get without intent, we can use unknown or try to get from battery manager if possible
        val status = BatteryManager.BATTERY_STATUS_UNKNOWN

        _currentMa.value = currentNowMa
        _currentAvgMa.value = currentAvgMa
        _chargeCounter.value = chargeCounter
        _energyCounter.value = energyCounter
        _batteryStatus.value = status
        _currentBatteryLevel.value = level
        
        updateCharacteristics(currentNowMa, currentAvgMa, chargeCounter, energyCounter, status)
        
        Log.d(TAG, "Initial battery state: $level%, $currentNowMa mA")
    }

    @SuppressLint("MissingPermission")
    private fun updateCharacteristics(currentNow: Int, currentAvg: Int, chargeCounter: Int, energyCounter: Long, status: Int) {
        val service = gattServer?.getService(BleServiceConstants.BATTERY_SERVICE_UUID) ?: return
        
        val nowChar = service.getCharacteristic(BleServiceConstants.BATTERY_LEVEL_UUID)
        if (nowChar != null) {
            val value = ByteBuffer.allocate(4).putInt(currentNow).array()
            nowChar.value = value
            notifySubscribers(nowChar)
        }

        val avgChar = service.getCharacteristic(BleServiceConstants.BATTERY_CURRENT_AVG_UUID)
        if (avgChar != null) {
            val value = ByteBuffer.allocate(4).putInt(currentAvg).array()
            avgChar.value = value
            notifySubscribers(avgChar)
        }

        val chargeChar = service.getCharacteristic(BleServiceConstants.BATTERY_CHARGE_COUNTER_UUID)
        if (chargeChar != null) {
            val value = ByteBuffer.allocate(4).putInt(chargeCounter).array()
            chargeChar.value = value
            notifySubscribers(chargeChar)
        }

        val energyChar = service.getCharacteristic(BleServiceConstants.BATTERY_ENERGY_COUNTER_UUID)
        if (energyChar != null) {
            val value = ByteBuffer.allocate(8).putLong(energyCounter).array()
            energyChar.value = value
            notifySubscribers(energyChar)
        }

        val statusChar = service.getCharacteristic(BleServiceConstants.BATTERY_STATUS_UUID)
        if (statusChar != null) {
            val value = ByteBuffer.allocate(4).putInt(status).array()
            statusChar.value = value
            notifySubscribers(statusChar)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifySubscribers(characteristic: BluetoothGattCharacteristic) {
        for (device in subscribedDevices) {
            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }
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