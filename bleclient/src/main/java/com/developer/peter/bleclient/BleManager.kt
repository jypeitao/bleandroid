package com.developer.peter.bleclient

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.developer.peter.bleclient.data.BleDevice
import com.developer.peter.bleclient.data.ConnectionState
import com.developer.peter.bleclient.data.ReceivedData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*

class BleManager(private val context: Context) {

    private val TAG = "BleManager"
    private var bluetoothGatt: BluetoothGatt? = null
    private var currentMtu = 23
    private val bluetoothAdapter: BluetoothAdapter? = context.getSystemService(
        BluetoothManager::class.java
    )?.adapter

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults = _scanResults.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _receivedData = MutableSharedFlow<ReceivedData>()
    val receivedData = _receivedData.asSharedFlow()

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

    private val writeMutex = Mutex()
    private var writeDeferred: CompletableDeferred<Int>? = null

    private var isScanning = false
    private val scanScope = CoroutineScope(Dispatchers.IO + Job())


    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device?.name
            if (deviceName.isNullOrEmpty()) {
                return
            }

            val bleDevice = BleDevice(
                device = result.device,
                rssi = result.rssi,
                scanRecord = result.scanRecord?.bytes
            )

            _scanResults.update { currentList ->
                (currentList.filterNot { it.address == bleDevice.address } + bleDevice)
                    .sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _connectionState.value = ConnectionState.Error(
                "Scan failed with error: $errorCode"
            )
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status: $status, newState: $newState")
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    disconnectGatt()
                    closeGatt()
                    resetWriteState()
                    stopSpeedStatistics()
                    _connectionState.value = ConnectionState.Error(
                        "Connection error: $status"
                    )
                }

                newState == BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.Connected(
                        gatt?.device?.address ?: ""
                    )
                    // 已连接，开始统计速率
//                    startSpeedStatistics()
                    gatt?.requestMtu(512)
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt()
                    resetWriteState()
                    stopSpeedStatistics()
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                Log.d(TAG, "onMtuChanged: $mtu")
                gatt?.discoverServices()
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            Log.d(TAG, "onServiceChanged")
        }

        private fun BluetoothGattCharacteristic.isNotifiable(): Boolean {
            return properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        }

        val CHARACTERISTIC_UUID: UUID = UUID.fromString("00005678-0000-1000-8000-00805F9B34FB")
        val SERVICE_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d(TAG, "onServicesDiscovered status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt?.services?.forEach { service ->
                    Log.d(TAG, "onServicesDiscovered:" + service.uuid)
                    if (SERVICE_UUID == service.uuid) {
                        service.characteristics.forEach { characteristic ->
                            Log.d(TAG, "characteristic:" + characteristic.uuid)
                            if (characteristic.isNotifiable()) {
                                enableNotification(characteristic)
                            }
                        }
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)
            Log.d(TAG, "onCharacteristicRead status: $status, uuid: ${characteristic.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                scanScope.launch {
                    _receivedData.emit(
                        ReceivedData(
                            characteristicUuid = characteristic.uuid,
                            data = value
                        )
                    )
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            Log.d(
                TAG,
                "onCharacteristicRead (deprecated) status: $status, uuid: ${characteristic?.uuid}"
            )
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                val value = characteristic.value ?: byteArrayOf()
                scanScope.launch {
                    _receivedData.emit(
                        ReceivedData(
                            characteristicUuid = characteristic.uuid,
                            data = value
                        )
                    )
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "onCharacteristicChanged uuid: ${characteristic.uuid}, size: ${value.size}")
            synchronized(this@BleManager) {
                receivedBytesInLastSecond += value.size
            }

            if (value.size >= 3 &&
                value[0] == 0x01.toByte() &&
                value[1] == 0x01.toByte() &&
                value[2] == 0x01.toByte()
            ) {
                return
            }

            scanScope.launch {
                Log.d(TAG, "onCharacteristicChanged ==")
                _receivedData.emit(
                    ReceivedData(
                        characteristicUuid = characteristic.uuid,
                        data = value
                    )
                )
            }
            Log.d(TAG, "onCharacteristicChanged --")
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            super.onDescriptorRead(gatt, descriptor, status, value)
            Log.d(TAG, "onDescriptorRead status: $status, uuid: ${descriptor.uuid}")
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            Log.d(TAG, "onDescriptorRead (deprecated) status: $status, uuid: ${descriptor?.uuid}")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.d(TAG, "onDescriptorWrite status: $status, uuid: ${descriptor?.uuid}")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite status: $status, uuid: ${characteristic.uuid}")
            synchronized(this@BleManager) {
                writeDeferred?.complete(status)
            }
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
            Log.d(TAG, "onReliableWriteCompleted status: $status")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            Log.d(TAG, "onReadRemoteRssi rssi: $rssi, status: $status")
        }

        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            Log.d(TAG, "onPhyUpdate txPhy: $txPhy, rxPhy: $rxPhy, status: $status")
        }

        override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(gatt, txPhy, rxPhy, status)
            Log.d(TAG, "onPhyRead txPhy: $txPhy, rxPhy: $rxPhy, status: $status")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun resetWriteState() {
        stopStressTest()
        synchronized(this) {
            writeDeferred?.complete(BluetoothGatt.GATT_FAILURE)
        }
    }

    private fun startSpeedStatistics() {
        speedJob?.cancel()
        speedJob = scanScope.launch {
            while (true) {
                delay(1000)
                synchronized(this@BleManager) {
                    _sendSpeed.value = sentBytesInLastSecond
                    _receiveSpeed.value = receivedBytesInLastSecond
                    sentBytesInLastSecond = 0
                    receivedBytesInLastSecond = 0
                    Log.d(TAG, "speed: ${_sendSpeed.value}, ${_receiveSpeed.value}")
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startStressTest(serviceUuid: UUID, characteristicUuid: UUID) {
        if (_isStressTesting.value) return
        _isStressTesting.value = true
        startSpeedStatistics()
        Log.d(TAG, "startStressTest")
        stressTestJob = scanScope.launch {
            val dummyData = ByteArray(currentMtu - 5) { 0x01.toByte() }
            while (_isStressTesting.value) {
                Log.d(TAG, "send dummyData")
                sendDataInternal(serviceUuid, characteristicUuid, dummyData)
                // 稍微延迟，防止过度占用 CPU
                delay(1)

            }
        }
    }

    fun stopStressTest() {
        _isStressTesting.value = false
        stressTestJob?.cancel()
        stressTestJob = null
    }

    private fun stopSpeedStatistics() {
        speedJob?.cancel()
        speedJob = null
        _sendSpeed.value = 0
        _receiveSpeed.value = 0
        sentBytesInLastSecond = 0
        receivedBytesInLastSecond = 0
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (!BlePermissionHelper.hasRequiredPermissions(context)) {
            _connectionState.value = ConnectionState.Error("Missing permissions")
            return
        }

        if (isScanning) return

        _scanResults.value = emptyList()
        isScanning = true

        scanScope.launch {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(
                buildScanFilters(),
                buildScanSettings(),
                scanCallback
            )

            delay(SCAN_PERIOD)
            stopScan()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        if (isScanning) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: BluetoothDevice) {
        if (!BlePermissionHelper.hasRequiredPermissions(context)) {
            _connectionState.value = ConnectionState.Error("Missing permissions")
            return
        }

        disconnectGatt()
        _connectionState.value = ConnectionState.Connecting

        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        disconnectGatt()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readCharacteristic(serviceUuid: UUID, characteristicUuid: UUID) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(serviceUuid) ?: return
        val characteristic = service.getCharacteristic(characteristicUuid) ?: return

        Log.d(TAG, "readCharacteristic: $characteristicUuid")
        gatt.readCharacteristic(characteristic)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendData(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) {
        scanScope.launch {
            sendDataInternal(serviceUuid, characteristicUuid, data)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun sendDataInternal(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        data: ByteArray
    ) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(serviceUuid) ?: return
        val characteristic = service.getCharacteristic(characteristicUuid) ?: return

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        Log.d(TAG, "sendDataInternal: ${data.size}")
        val maxPayload = currentMtu - 5
        data.asSequence()
            .windowed(size = maxPayload, step = maxPayload, partialWindows = true)
            .map { it.toByteArray() }
            .forEach { chunk ->
                Log.d(TAG, "sendDataInternal forEach chunk: ${chunk.size}")
                writeMutex.withLock {
                    Log.d(TAG, "sendDataInternal forEach chunk ff")
                    val deferred = CompletableDeferred<Int>()
                    synchronized(this@BleManager) {
                        writeDeferred = deferred
                    }

                    val success =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            Log.d(TAG, "write to remote: ${chunk.size} -- $currentMtu")
                            gatt.writeCharacteristic(
                                characteristic,
                                chunk,
                                characteristic.writeType
                            ) == BluetoothStatusCodes.SUCCESS
                        } else {
                            Log.d(TAG, "write to remote: ${chunk.size} -- $currentMtu")
                            @Suppress("DEPRECATION")
                            characteristic.value = chunk
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(characteristic)
                        }

                    if (success) {
                        Log.d(TAG, "deferred +++11")
                        withTimeoutOrNull(30000) {
                            Log.d(TAG, "deferred +++")
                            deferred.await()
                            Log.d(TAG, "deferred ---")
                        }
                        synchronized(this@BleManager) {
                            sentBytesInLastSecond += chunk.size
                        }
                        Log.d(TAG, "deferred ---11")
                    } else {
                        synchronized(this@BleManager) {
                            writeDeferred = null
                        }
                    }
                }
            }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun disconnectGatt() {
        bluetoothGatt?.disconnect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotification(characteristic: BluetoothGattCharacteristic) {
        val gatt = bluetoothGatt ?: return
        gatt.setCharacteristicNotification(characteristic, true)

        Log.d(TAG, "enableNotification")
        val descriptor = characteristic.getDescriptor(
            UUID.fromString(CCCD_UUID)
        ) ?: return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // New API (Android 13+)
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        } else {
            // Legacy API (Android 12 and below)
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun buildScanFilters(): List<ScanFilter> {
        return listOf(
            ScanFilter.Builder()
                // 可以在这里添加具体的过滤条件
                .build()
        )
    }

    private fun buildScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }

    companion object {
        private const val SCAN_PERIOD = 10000L // 10 seconds
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}