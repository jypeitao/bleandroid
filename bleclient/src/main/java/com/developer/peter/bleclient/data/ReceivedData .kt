package com.developer.peter.bleclient.data

import java.util.UUID

data class ReceivedData(
    val characteristicUuid: UUID,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReceivedData
        return characteristicUuid == other.characteristicUuid &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = characteristicUuid.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}