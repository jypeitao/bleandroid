package com.developer.peter.bleclient.data

import android.Manifest
import android.bluetooth.BluetoothDevice
import androidx.annotation.RequiresPermission

data class BleDevice(
    val device: BluetoothDevice,
    val rssi: Int,
    val scanRecord: ByteArray? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val name: String @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    get() = device.name ?: "Unknown Device"
    val address: String get() = device.address
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BleDevice
        return device.address == other.device.address
    }

    override fun hashCode(): Int = device.address.hashCode()
}