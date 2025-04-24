package com.developer.peter.bleclient.data

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val deviceAddress: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}