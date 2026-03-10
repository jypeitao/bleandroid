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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import com.developer.peter.bleserver.data.BleMessage
import com.developer.peter.bleserver.data.BleServiceConstants
import com.developer.peter.bleserver.data.ConnectionState
import com.developer.peter.bleserver.data.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val _isStressTesting = MutableStateFlow(false)
    val isStressTesting = _isStressTesting.asStateFlow()

    private val _sendSpeed = MutableStateFlow(0L) // bytes per second
    val sendSpeed = _sendSpeed.asStateFlow()

    private val _receiveSpeed = MutableStateFlow(0L) // bytes per second
    val receiveSpeed = _receiveSpeed.asStateFlow()

    private var sentBytesInLastSecond = 0L
    private var receivedBytesInLastSecond = 0L
    private var speedJob: Job? = null
    private var stressTestJob: Job? = null

    private val notificationMutex = Mutex()
    private var notificationDeferred: CompletableDeferred<Int>? = null

    private val notifyingDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON, initializing GATT server")
                        setupGattServer()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF, uninitializing GATT server")
                        teardownGattServer()
                    }
                }
            }
        }
    }


    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status: $status, newState: $newState dev: ${device.name}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    stopAdvertising()
                    _connectionState.value = ConnectionState.Connected(device.address)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.Disconnected
                    notifyingDevices.remove(device)
                    stopStressTest()
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
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == characteristicUUID) {
                Log.d(TAG, "onCharacteristicReadRequest")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    characteristic.value
                )
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
            Log.d(TAG,"onCharacteristicWriteRequest22")
            if (characteristic.uuid == characteristicUUID) {
                synchronized(this@BleServer) {
                    receivedBytesInLastSecond += value.size
                }

                if ((value.size >= 3 &&
                    value[0] == 0x01.toByte() &&
                    value[1] == 0x01.toByte() &&
                    value[2] == 0x01.toByte()).not()
                ) {

                    val message = String(value)
//                characteristic.value = value
                    _messages.update { currentList ->
                        currentList + BleMessage(
                            content = message,
                            type = MessageType.RECEIVED
                        )
                    }
                }
                Log.d(TAG,"onCharacteristicWriteRequest")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                val message = String(value)
                Log.d(TAG, "onCharacteristicWriteRequest2:$message")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            Log.d(TAG, "onDescriptorReadRequest")
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                descriptor.value
            )
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
                descriptor.value = value

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
            super.onMtuChanged(device, mtu)
            Log.d(TAG, "onMtuChanged: $mtu")
            currentMtu = mtu
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            super.onExecuteWrite(device, requestId, execute)
            Log.d(TAG, "onExecuteWrite requestId: $requestId, execute: $execute")
        }

        override fun onPhyUpdate(device: BluetoothDevice?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(device, txPhy, rxPhy, status)
            Log.d(TAG, "onPhyUpdate txPhy: $txPhy, rxPhy: $rxPhy, status: $status")
        }

        override fun onPhyRead(device: BluetoothDevice?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(device, txPhy, rxPhy, status)
            Log.d(TAG, "onPhyRead txPhy: $txPhy, rxPhy: $rxPhy, status: $status")
        }
    }

    // 添加广播状态流
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising = _isAdvertising.asStateFlow()

    init {
        // 注册广播接收器以监听蓝牙状态变化
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothReceiver, filter)

        // 只有在已经有权限且蓝牙开启的情况下才初始化
        // 否则，我们将等待 startAdvertising() 调用 ensureInitialized()
        // 或者等待蓝牙开启广播
        if (bluetoothAdapter?.isEnabled == true &&
            com.developer.peter.bleserver.util.BlePermissionHelper.hasRequiredPermissions(context)
        ) {
            setupGattServer()
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (_isAdvertising.value) return

        // 确保 GATT Server 已在拥有权限的前提下初始化
        ensureInitialized()

        Log.d(TAG, "startAdvertising")
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

    private fun startSpeedStatistics() {
        speedJob?.cancel()
        speedJob = serverScope.launch {
            while (true) {
                delay(1000)
                synchronized(this@BleServer) {
                    _sendSpeed.value = sentBytesInLastSecond
                    _receiveSpeed.value = receivedBytesInLastSecond
                    sentBytesInLastSecond = 0
                    receivedBytesInLastSecond = 0
                    Log.d(TAG, "sendSpeed = ${_sendSpeed.value}, receiveSpeed = ${_receiveSpeed.value}")
                }
            }
        }
    }

    fun startStressTest() {
        if (_isStressTesting.value) return
        _isStressTesting.value = true
        startSpeedStatistics()

        stressTestJob = serverScope.launch {
            val dummyData = ByteArray(currentMtu - 5) { 0x01.toByte() }
            while (_isStressTesting.value) {
                sendLargeData(dummyData, true)
                delay(1)
            }
        }
    }

    fun stopStressTest() {
        _isStressTesting.value = false
        stressTestJob?.cancel()
        stressTestJob = null
        speedJob?.cancel()
        speedJob = null
        _sendSpeed.value = 0
        _receiveSpeed.value = 0
        sentBytesInLastSecond = 0
        receivedBytesInLastSecond = 0
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        Log.d(TAG, "stopAdvertising")
        advertiser?.stopAdvertising(advertisingCallback)
        advertiser = null
        _isAdvertising.value = false
    }

    fun ensureInitialized() {
        if (gattServer == null &&
            com.developer.peter.bleserver.util.BlePermissionHelper.hasRequiredPermissions(context) &&
            bluetoothAdapter?.isEnabled == true
        ) {
            setupGattServer()
        }
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


    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        if (gattServer != null) {
            return
        }
        // 没有运行时权限时直接返回，避免 SecurityException
        if (!com.developer.peter.bleserver.util.BlePermissionHelper.hasRequiredPermissions(context)) {
            Log.w(TAG, "Missing BLE permissions. Skip GATT server setup.")
            return
        }

        val service = BluetoothGattService(
            serviceUUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val characteristic = BluetoothGattCharacteristic(
            characteristicUUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val cccd = BluetoothGattDescriptor(
            descriptorUUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        )
        characteristic.addDescriptor(cccd)

        service.addCharacteristic(characteristic)

        val characteristic2 = BluetoothGattCharacteristic(
            BleServiceConstants.CHARACTERISTIC_UUID2,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM
        )
        service.addCharacteristic(characteristic2)

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            gattServer?.addService(service)
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException opening GATT server. Missing permission?", se)
            gattServer = null
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(message: String, confirm: Boolean = false) {
        _messages.update { currentList ->
            currentList + BleMessage(
                content = message,
                type = MessageType.SENT
            )
        }
        Log.d(TAG, "sendMessage: $message")
        serverScope.launch {
            sendLargeData(message.toByteArray(), confirm)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun sendLargeData(data: ByteArray, confirm: Boolean = false) {
        val characteristic =
            gattServer?.getService(serviceUUID)?.getCharacteristic(characteristicUUID) ?: return
        val chunkSize = currentMtu - 5 // ATT header 占用 3 字节
        Log.d(TAG, "sendLargeData: chunkSize = ${data.size}")
        data.asSequence()
            .windowed(size = chunkSize, step = chunkSize, partialWindows = true)
            .map { it.toByteArray() }
            .forEach { chunk ->
                sendNotification(characteristic, chunk, confirm)
            }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendNotification(
        characteristic: BluetoothGattCharacteristic?,
        value: ByteArray,
        confirm: Boolean
    ): Boolean {
        if (notifyingDevices.isEmpty() || characteristic == null) {
            Log.w(TAG, "No devices to notify" + notifyingDevices.isEmpty())
            return false
        }

        var success = true

        val devices = synchronized(notifyingDevices) {
            Log.d(TAG, "notifyingDevices size = ${notifyingDevices.size}")
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
                    Log.d(TAG, "notifyCharacteristicChanged: ${device.address}")
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
                    // 等待回调，设置 20 秒超时以防万一
                    val status = withTimeoutOrNull(20000) {
                        deferred.await()
                    }
                    if (status == null) {
                        Log.e(TAG, "Notification timed out for device: ${device.address}")
                    } else if (status == BluetoothGatt.GATT_SUCCESS) {
                        synchronized(this@BleServer) {
                            sentBytesInLastSecond += value.size
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to notify device: ${device.address}")
                }

                success = success && notified
            } finally {
                notificationMutex.unlock()
            }
        }

        return success
    }


    @SuppressLint("MissingPermission")
    private fun teardownGattServer() {
        Log.d(TAG, "teardownGattServer")
        synchronized(this) {
            notificationDeferred?.complete(BluetoothGatt.GATT_FAILURE)
            notificationDeferred = null
        }
        gattServer?.close()
        gattServer = null
        notifyingDevices.clear()
        _isAdvertising.value = false
        _connectionState.value = ConnectionState.Disconnected
        stopStressTest()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        teardownGattServer()
        advertiser?.stopAdvertising(advertisingCallback)
    }
}