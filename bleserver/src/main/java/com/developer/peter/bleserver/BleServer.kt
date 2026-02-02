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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.ui.platform.LocalGraphicsContext
import com.developer.peter.bleserver.data.BleMessage
import com.developer.peter.bleserver.data.BleServiceConstants
import com.developer.peter.bleserver.data.ConnectionState
import com.developer.peter.bleserver.data.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

class BleServer(private val context: Context) {

    private val TAG = BleServer::class.java.simpleName
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

    private val serverScope = CoroutineScope(Dispatchers.IO + Job())

    private var currentMtu = 23

    private val notificationMutex = Mutex()
    private var notificationDeferred: CompletableDeferred<Int>? = null

    private val notifyingDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange:$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    stopAdvertising()
                    _connectionState.value = ConnectionState.Connected(device.address)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.Disconnected
                    notifyingDevices.remove(device)
                    synchronized(this@BleServer) {
                        notificationDeferred?.complete(BluetoothGatt.GATT_FAILURE)
                    }
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            Log.d(TAG, "onNotificationSent:$status")
            synchronized(this@BleServer) {
                notificationDeferred?.complete(status)
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            Log.d(TAG, "onServiceAdded:$status -- $service")
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
                Log.d(TAG,"onCharacteristicWriteRequest")
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
            Log.d(TAG, "onDescriptorWriteRequest")
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

        Log.d(TAG, "startAdvertising")
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
            Log.d(TAG, "onStartSuccess")
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.d(TAG, "onStartFailure")
            _isAdvertising.value = false
            // 可以添加错误处理逻辑
        }
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setupGattServer() {
        if (gattServer != null) {
            return
        }

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
    fun sendMessage(message: String, confirm: Boolean = false) {
        _messages.update { currentList ->
            currentList + BleMessage(
                content = message,
                type = MessageType.SENT
            )
        }
        sendLargeData(message.toByteArray(), confirm)
    }

    @SuppressLint("MissingPermission")
    fun sendLargeData(data: ByteArray, confirm: Boolean = false) {
        val characteristic =
            gattServer?.getService(serviceUUID)?.getCharacteristic(characteristicUUID) ?: return
        val chunkSize = currentMtu - 3 // ATT header 占用 3 字节
        
        serverScope.launch {
            data.asSequence()
                .windowed(size = chunkSize, step = chunkSize, partialWindows = true)
                .map { it.toByteArray() }
                .forEach { chunk ->
                    sendNotification(characteristic, chunk, confirm)
                }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun sendNotification(
        characteristic: BluetoothGattCharacteristic?,
        value: ByteArray,
        confirm: Boolean
    ): Boolean {
        if (notifyingDevices.isEmpty() || characteristic == null) {
            return false
        }

        var success = true

        val devices = synchronized(notifyingDevices) {
            notifyingDevices.toList()
        }

        devices.forEach { device ->
            // 使用 Mutex 确保多个协程调用 sendMessage 时是串行的
            notificationMutex.lock()
            try {
                val deferred = CompletableDeferred<Int>()
                synchronized(this@BleServer) {
                    notificationDeferred = deferred
                }

                val notified = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gattServer?.notifyCharacteristicChanged(
                        device,
                        characteristic,
                        confirm,
                        value
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = value
                    @Suppress("DEPRECATION")
                    gattServer?.notifyCharacteristicChanged(
                        device,
                        characteristic,
                        confirm
                    ) ?: false
                }

                if (notified) {
                    // 等待回调，设置 1 秒超时以防万一
                    val status = withTimeoutOrNull(1000) {
                        deferred.await()
                    }
                    if (status == null) {
                        Log.w(TAG, "Notification timed out for device: ${device.address}")
                    }
                }

                success = success && notified
            } finally {
                notificationMutex.unlock()
            }
        }

        return success
    }


    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    fun stop() {
        synchronized(this) {
            notificationDeferred?.complete(BluetoothGatt.GATT_FAILURE)
        }
        gattServer?.close()
        advertiser?.stopAdvertising(advertisingCallback)
    }
}