package com.developer.peter.batteryserver.data

import java.util.UUID

object BleServiceConstants {
    // Battery Service
    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    // Battery Level characteristic
    val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_STATUS: UUID = UUID.fromString("00002bed-0000-1000-8000-00805f9b34fb")
    
    // Additional Battery Characteristics (Custom UUIDs as standard ones are limited)
    val BATTERY_CURRENT_AVG_UUID: UUID = UUID.fromString("00002a1a-0000-1000-8000-00805f9b34fb")
    val BATTERY_CHARGE_COUNTER_UUID: UUID = UUID.fromString("00002a1b-0000-1000-8000-00805f9b34fb")
    val BATTERY_ENERGY_COUNTER_UUID: UUID = UUID.fromString("00002a1c-0000-1000-8000-00805f9b34fb")
    val BATTERY_STATUS_UUID: UUID = UUID.fromString("00002a1d-0000-1000-8000-00805f9b34fb")
    
    // Client Characteristic Configuration Descriptor
    val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connected(val deviceAddress: String) : ConnectionState()
}