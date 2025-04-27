package com.developer.peter.bleserver.data

import java.util.UUID

object BleServiceConstants {
    // Service UUID for your BLE server
    val SERVICE_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")

    // Characteristic UUID for communication
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("00005678-0000-1000-8000-00805F9B34FB")

    val DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Device name that will be advertised
    const val DEVICE_NAME = "BLE Server"

    // Maximum MTU size
    const val MAX_MTU_SIZE = 512
}