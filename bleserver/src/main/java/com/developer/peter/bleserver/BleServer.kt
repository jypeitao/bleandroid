package com.developer.peter.bleserver

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.developer.peter.bleserver.data.BleMessage
import com.developer.peter.bleserver.data.BleServiceConstants
import com.developer.peter.bleserver.data.ConnectionState
import com.developer.peter.bleserver.data.MessageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections

class BleServer(private val context: Context) {
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<List<BleMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val serviceUUID = BleServiceConstants.SERVICE_UUID
    private val characteristicUUID = BleServiceConstants.CHARACTERISTIC_UUID
    private val descriptorUUID = BleServiceConstants.DESCRIPTOR_UUID

    private var currentMtu = 23

    private val notifyingDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    stopAdvertising()
                    _connectionState.value = ConnectionState.Connected(device.address)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.Disconnected
                    notifyingDevices.remove(device)
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == characteristicUUID) {
                val message = String(value)
                _messages.update { currentList ->
                    currentList + BleMessage(
                        content = message,
                        type = MessageType.RECEIVED
                    )
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == descriptorUUID) {
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                        notifyingDevices.add(device)
                    }

                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                        notifyingDevices.remove(device)
                    }
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    )
                }
            }
        }


        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            currentMtu = mtu
        }
    }

    // 添加广播状态流
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising = _isAdvertising.asStateFlow()

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    fun startAdvertising() {
        if (_isAdvertising.value) return

        setupGattServer()
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertisingCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertisingCallback)
        advertiser = null
        _isAdvertising.value = false
    }

    private val advertisingCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            _isAdvertising.value = false
            // 可以添加错误处理逻辑
        }
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setupGattServer() {
        val service = BluetoothGattService(
            serviceUUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val characteristic = BluetoothGattCharacteristic(
            characteristicUUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            descriptorUUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        )
        characteristic.addDescriptor(cccd)

        service.addCharacteristic(characteristic)
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        gattServer?.addService(service)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessage(message: String) {
        _messages.update { currentList ->
            currentList + BleMessage(
                content = message,
                type = MessageType.SENT
            )
        }
        sendLargeData(message.toByteArray())
    }

    @SuppressLint("MissingPermission")
    fun sendLargeData(data: ByteArray) {
        val characteristic =
            gattServer?.getService(serviceUUID)?.getCharacteristic(characteristicUUID)
        val chunkSize = currentMtu - 3 // ATT header 占用 3 字节
        data.asSequence()
            .windowed(size = chunkSize, step = chunkSize, partialWindows = true)
            .map { it.toByteArray() }
            .forEach { chunk ->
                sendNotification(characteristic, chunk)
            }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendNotification(
        characteristic: BluetoothGattCharacteristic?,
        value: ByteArray
    ): Boolean {
        if (notifyingDevices.isEmpty()) {
            return false
        }

        var success = true
        characteristic?.value = value

        // 向所有已订阅通知的设备发送数据
        synchronized(notifyingDevices) {
            notifyingDevices.forEach { device ->
                val notified = gattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false
                ) ?: false
                success = success && notified
            }
        }

        return success
    }


    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    fun stop() {
        gattServer?.close()
        advertiser?.stopAdvertising(advertisingCallback)
    }
}