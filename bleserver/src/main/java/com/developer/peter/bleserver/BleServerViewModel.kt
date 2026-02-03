package com.developer.peter.bleserver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import com.developer.peter.bleserver.data.BleMessage
import com.developer.peter.bleserver.data.ConnectionState
import com.developer.peter.bleserver.util.BlePermissionHelper
import kotlinx.coroutines.flow.StateFlow

class BleServerViewModel(private val application: Application) : AndroidViewModel(application) {
    private var _bleServer: BleServer? = null
    private val bleServer: BleServer
        get() {
            if (_bleServer == null) {
                _bleServer = BleServer(application)
            }
            return _bleServer!!
        }

    val connectionState: StateFlow<ConnectionState> by lazy { bleServer.connectionState }
    val messages: StateFlow<List<BleMessage>> by lazy { bleServer.messages }
    val isAdvertising: StateFlow<Boolean> by lazy { bleServer.isAdvertising }

    val isStressTesting: StateFlow<Boolean> by lazy { bleServer.isStressTesting }
    val sendSpeed: StateFlow<Long> by lazy { bleServer.sendSpeed }
    val receiveSpeed: StateFlow<Long> by lazy { bleServer.receiveSpeed }

    @SuppressLint("MissingPermission")
    fun sendMessage(message: String, confirm: Boolean = false) {
        bleServer.sendMessage(message, confirm)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    fun toggleAdvertising(onRequestPermission: () -> Unit) {
        if (bleServer.isAdvertising.value) {
            bleServer.stopAdvertising()
        } else {
            if (BlePermissionHelper.hasRequiredPermissions(application)) {
                bleServer.startAdvertising()
            } else {
                // 请求权限
                onRequestPermission()
            }
        }
    }


    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (BlePermissionHelper.hasRequiredPermissions(application)) {
            bleServer.startAdvertising()
        }
    }

    fun toggleStressTest() {
        if (isStressTesting.value) {
            bleServer.stopStressTest()
        } else {
            bleServer.startStressTest()
        }
    }


    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onCleared() {
        super.onCleared()
        _bleServer?.stop()
    }
}