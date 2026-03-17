package com.developer.peter.bleclient.data

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import androidx.annotation.RequiresPermission

data class BleDevice(
    val device: BluetoothDevice,
    val rssi: Int,
    val isConnectable: Boolean = false,
    val isLegacy: Boolean = true,
    val scanRecord: ScanRecord? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val name: String @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    get() = device.name ?: "Unknown Device"
    val address: String get() = device.address

    val serviceUuids: List<String>
        get() = scanRecord?.serviceUuids?.map { it.toString() } ?: emptyList()

    val manufacturerData: String
        get() {
            val data = scanRecord?.manufacturerSpecificData ?: return ""
            if (data.size() == 0) return ""
            val builder = StringBuilder()
            for (i in 0 until data.size()) {
                val id = data.keyAt(i)
                val bytes = data.valueAt(i)
                builder.append(String.format("ID: 0x%04X, Data: %s\n", id, bytesToHex(bytes)))
            }
            return builder.toString().trim()
        }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BleDevice
        return device.address == other.device.address
    }

    override fun hashCode(): Int = device.address.hashCode()
}