package com.developer.peter.batteryserver

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.developer.peter.batteryserver.data.ConnectionState
import com.developer.peter.batteryserver.util.BlePermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BatteryServerViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()
    
    private val _currentBatteryLevel = MutableStateFlow(0)
    val currentBatteryLevel = _currentBatteryLevel.asStateFlow()
    
    private val _currentMa = MutableStateFlow(0)
    val currentMa = _currentMa.asStateFlow()
    
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising = _isAdvertising.asStateFlow()

    private var batteryServer: BatteryServer? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BatteryServerService.LocalBinder
            val server = binder.getBatteryServer()
            batteryServer = server
            isBound = true
            
            viewModelScope.launch {
                server.connectionState.collect { _connectionState.value = it }
            }
            viewModelScope.launch {
                server.currentBatteryLevel.collect { _currentBatteryLevel.value = it }
            }
            viewModelScope.launch {
                server.currentMa.collect { _currentMa.value = it }
            }
            viewModelScope.launch {
                server.isAdvertising.collect { _isAdvertising.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            batteryServer = null
            isBound = false
        }
    }

    init {
        val intent = Intent(application, BatteryServerService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        // Ensure service is started as foreground if we have permissions
        if (BlePermissionHelper.hasRequiredPermissions(application)) {
            startService()
        }
    }

    private fun startService() {
        val intent = Intent(getApplication(), BatteryServerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    fun toggleServer() {
        if (isAdvertising.value) {
            batteryServer?.stopServer()
            // Optional: stopService if you want to completely kill it
        } else {
            if (BlePermissionHelper.hasRequiredPermissions(getApplication())) {
                startService()
                batteryServer?.startServer()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}