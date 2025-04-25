package com.developer.peter.bleserver.data

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connected(val deviceAddress: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}