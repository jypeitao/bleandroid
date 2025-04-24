package com.developer.peter.bleclient

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import androidx.annotation.RequiresPermission
import com.developer.peter.bleclient.data.BleDevice
import com.developer.peter.bleclient.data.ConnectionState
import com.developer.peter.bleclient.data.ReceivedData
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
import java.util.*


class BleManager(private val context: Context) {
    private var bluetoothGatt: BluetoothGatt? = null
    private val bluetoothAdapter: BluetoothAdapter? = context.getSystemService(
        BluetoothManager::class.java
    )?.adapter

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults = _scanResults.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _receivedData = MutableSharedFlow<ReceivedData>()
    val receivedData = _receivedData.asSharedFlow()

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
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    disconnectGatt()
                    closeGatt()
                    _connectionState.value = ConnectionState.Error(
                        "Connection error: $status"
                    )
                }

                newState == BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.Connected(
                        gatt?.device?.address ?: ""
                    )
                    gatt?.requestMtu(512)
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt()
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt?.discoverServices()
            }
        }

        private fun BluetoothGattCharacteristic.isNotifiable(): Boolean {
            return properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt?.services?.forEach { service ->
                    service.characteristics.forEach { characteristic ->
                        if (characteristic.isNotifiable()) {
                            enableNotification(characteristic)
                        }
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
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
    fun sendData(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(serviceUuid) ?: return
        val characteristic = service.getCharacteristic(characteristicUuid) ?: return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                data,
                characteristic.writeType
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            bluetoothGatt?.writeCharacteristic(characteristic)
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