package com.developer.peter.bleserver.util

object BleUtils {
    /**
     * 将蓝牙地址（如 "11:22:33:44:55:66" 或 "112233445566"）转换为 6 字节数组
     */
    fun macToBytes(mac: String): ByteArray {
        val cleanMac = mac.replace(":", "")
        require(cleanMac.length == 12) { "Invalid MAC address: $mac" }
        val bytes = ByteArray(6)
        for (i in 0 until 6) {
            bytes[i] = cleanMac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    /**
     * 将 6 字节数组转换为蓝牙地址（如 "11:22:33:44:55:66"）
     */
    fun bytesToMac(bytes: ByteArray): String {
        require(bytes.size == 6) { "Invalid byte array length for MAC: ${bytes.size}" }
        return bytes.joinToString(":") { "%02X".format(it) }
    }
}