package com.developer.peter.bleserver

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.developer.peter.bleserver.ui.components.BleServerScreen
import com.developer.peter.bleserver.ui.theme.BleAndroidTheme
import com.developer.peter.bleserver.util.BlePermissionHelper

class MainActivity : ComponentActivity() {
    private val bleServer by lazy { BleServer(this) }

    @SuppressLint("MissingPermission")
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 检查是否所有必需的权限都被授予
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            // 所有权限都获得了，可以开始广播
            bleServer.startAdvertising()
        } else {
            // 显示权限被拒绝的提示
            showPermissionDeniedDialog()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BleAndroidTheme {
                BleServerScreen(
                    onRequestPermission = {
                        requestPermissionLauncher.launch(BlePermissionHelper.permissions)
                    }
                )

            }
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("BLE permissions are required for broadcasting. Please grant them in Settings.")
            .setPositiveButton("Go to Settings") { _, _ ->
                // 打开应用设置页面
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

}