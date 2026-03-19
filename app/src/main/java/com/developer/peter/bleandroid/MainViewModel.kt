package com.developer.peter.bleandroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val batteryMonitor = BatteryMonitor(application)

    val currentBatteryLevel = batteryMonitor.batteryLevel
    val currentMa = batteryMonitor.currentMa
    val currentAvgMa = batteryMonitor.currentAvgMa
    val currentHistory = batteryMonitor.currentHistory
    val lastPercentChangeTime = batteryMonitor.lastPercentChangeTime

    init {
        batteryMonitor.startMonitoring()
    }

    override fun onCleared() {
        super.onCleared()
        batteryMonitor.stopMonitoring()
    }
}