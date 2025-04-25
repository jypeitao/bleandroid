package com.developer.peter.bleclient

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.developer.peter.bleclient.data.BleMessage
import com.developer.peter.bleclient.data.MessageType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

class BleViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val bleManager = BleManager(application)

    val scanResults = bleManager.scanResults
    val connectionState = bleManager.connectionState
    private val receivedData = bleManager.receivedData.map {
        String(it.data)
    }.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        ""
    )

    private val _messages = MutableStateFlow<List<BleMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    init {
        viewModelScope.launch {
            receivedData.collect { message ->
                _messages.update { current ->
                    current + BleMessage(
                        content = message,
                        type = MessageType.RECEIVED
                    )
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        Log.d("BleViewModel", "startScan $this")
        bleManager.startScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        bleManager.stopScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: BluetoothDevice) {
        bleManager.connect(device)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        bleManager.disconnect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessage(message: String) {
        bleManager.sendData(
            SERVICE_UUID,
            CHARACTERISTIC_UUID,
            message.toByteArray()
        )

        _messages.update { current ->
            current + BleMessage(
                content = message,
                type = MessageType.SENT
            )
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCleared() {
        super.onCleared()
        disconnect()
    }

    companion object {
        private val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_UUID = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")
    }
}