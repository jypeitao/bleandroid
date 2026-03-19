package com.developer.peter.batteryserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.developer.peter.batteryserver.util.BlePermissionHelper

class BatteryServerService : Service() {
    private val TAG = "BatteryServerService"
    private val CHANNEL_ID = "BatteryServerChannel"
    private val NOTIFICATION_ID = 1

    private lateinit var batteryServer: BatteryServer
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BatteryServerService = this@BatteryServerService
        fun getBatteryServer(): BatteryServer = batteryServer
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        batteryServer = BatteryServer(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (BlePermissionHelper.hasRequiredPermissions(this)) {
            batteryServer.startServer()
        } else {
            Log.w(TAG, "Service started but missing permissions")
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        batteryServer.stopServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Battery Server Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery BLE Server")
            .setContentText("Monitoring battery and broadcasting via BLE")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }
}