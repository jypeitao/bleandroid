package com.developer.peter.batteryserver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.developer.peter.batteryserver.data.ConnectionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class BatteryServerViewModel(application: Application) : AndroidViewModel(application) {
    private val batteryServer = BatteryServer(application)
    
    val connectionState = batteryServer.connectionState
    val currentBatteryLevel = batteryServer.currentBatteryLevel
    val currentMa = batteryServer.currentMa
    val isAdvertising = batteryServer.isAdvertising

    fun toggleServer() {
        if (isAdvertising.value) {
            batteryServer.stopServer()
        } else {
            batteryServer.startServer()
        }
    }

    override fun onCleared() {
        super.onCleared()
        batteryServer.stopServer()
    }
}