package com.developer.peter.bleandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BatteryMonitor(private val context: Context) {
    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel = _batteryLevel.asStateFlow()

    private val _currentMa = MutableStateFlow(0)
    val currentMa = _currentMa.asStateFlow()

    private val _currentAvgMa = MutableStateFlow(0)
    val currentAvgMa = _currentAvgMa.asStateFlow()

    private val _calculatedAvgMa = MutableStateFlow(0)
    val calculatedAvgMa = _calculatedAvgMa.asStateFlow()

    private val _currentHistory = MutableStateFlow<List<Int>>(emptyList())
    val currentHistory = _currentHistory.asStateFlow()

    private val _lastPercentChangeTime = MutableStateFlow(0L) // In seconds
    val lastPercentChangeTime = _lastPercentChangeTime.asStateFlow()

    private val _batteryCapacity = MutableStateFlow(0.0) // In mAh
    val batteryCapacity = _batteryCapacity.asStateFlow()

    private var lastLevel: Int = -1
    private var lastLevelTime: Long = 0L

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val monitorScope = CoroutineScope(Dispatchers.IO + Job())
    private var monitoringJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                updateBatteryInfo(intent)
            }
        }
    }

    init {
        // Initial check
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.let { updateBatteryInfo(it) }
        
        // Try to get total capacity
        _batteryCapacity.value = getBatteryCapacity()
    }

    private fun getBatteryCapacity(): Double {
        val powerProfileClass = "com.android.internal.os.PowerProfile"
        return try {
            val powerProfile = Class.forName(powerProfileClass)
                .getConstructor(Context::class.java)
                .newInstance(context)
            val capacity = Class.forName(powerProfileClass)
                .getMethod("getBatteryCapacity")
                .invoke(powerProfile) as Double
            capacity
        } catch (e: Exception) {
            0.0
        }
    }

    fun startMonitoring() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)

        monitoringJob?.cancel()
        monitoringJob = monitorScope.launch {
            while (isActive) {
                updateBatteryManagerProperties()
                delay(1000)
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
    }

    private fun updateBatteryManagerProperties() {
        val currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentAvg = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)

        // Convert microamperes to milliamperes
        val ma = (currentNow / 1000).toInt()
        _currentMa.value = ma
        _currentAvgMa.value = (currentAvg / 1000).toInt()

        // Update history (keep last 60 points)
        val history = _currentHistory.value.toMutableList()
        history.add(ma)
        if (history.size > 60) {
            history.removeAt(0)
        }
        _currentHistory.value = history
        
        // Calculate average from history
        if (history.isNotEmpty()) {
            _calculatedAvgMa.value = history.average().toInt()
        }
    }

    private fun updateBatteryInfo(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level != -1 && scale != -1) {
            val batteryPct = (level * 100 / scale.toFloat()).toInt()
            
            if (lastLevel != -1 && batteryPct != lastLevel) {
                val currentTime = System.currentTimeMillis()
                if (lastLevelTime != 0L) {
                    val timeDiffSeconds = (currentTime - lastLevelTime) / 1000
                    _lastPercentChangeTime.value = timeDiffSeconds
                }
                lastLevelTime = currentTime
            } else if (lastLevel == -1) {
                lastLevelTime = System.currentTimeMillis()
            }
            
            lastLevel = batteryPct
            _batteryLevel.value = batteryPct
        }
        
        // Also update properties here to ensure sync with broadcast
        updateBatteryManagerProperties()
    }
}