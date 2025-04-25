package com.developer.peter.bleserver.data

import java.util.UUID

data class BleMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageType {
    SENT, RECEIVED
}