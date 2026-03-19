package com.developer.peter.batteryserver.data

import java.util.UUID

object BleServiceConstants {
    // Battery Service
    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    // Battery Level characteristic
    val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    
    // Client Characteristic Configuration Descriptor
    val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connected(val deviceAddress: String) : ConnectionState()
}