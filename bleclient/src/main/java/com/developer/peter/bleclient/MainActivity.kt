package com.developer.peter.bleclient

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import com.developer.peter.bleclient.ui.BleScreen

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            // Permissions granted, proceed with BLE operations
        } else {
            // Check which permissions were denied
            val deniedPermissions = permissions.filterValues { !it }
            Log.d("MainActivity", "deniedPermissions $deniedPermissions")

            // Show dialog explaining why permissions are needed and provide settings option
            AlertDialog.Builder(this)
                .setTitle("权限请求")
                .setMessage("蓝牙功能需要定位权限才能正常使用。请在设置中开启相关权限。")
                .setPositiveButton("去设置") { _, _ ->
                    // Open app settings
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                }
                .setNegativeButton("取消") { dialog, _ ->
                    dialog.dismiss()
                    // Optionally show a toast or handle the denial
                    Toast.makeText(
                        this,
                        "没有必要的权限，部分功能可能无法正常使用",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .create()
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                BleScreen(
                    onRequestPermissions = {
                        permissionLauncher.launch(BlePermissionHelper.permissions)
                    }
                )
            }
        }
    }
}