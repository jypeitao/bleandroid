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
    private val bleServer = BleServer(application)

    val connectionState: StateFlow<ConnectionState> = bleServer.connectionState
    val messages: StateFlow<List<BleMessage>> = bleServer.messages
    val isAdvertising: StateFlow<Boolean> = bleServer.isAdvertising


    fun sendMessage(message: String) {
        bleServer.sendMessage(message)
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


    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onCleared() {
        super.onCleared()
        bleServer.stop()
    }
}