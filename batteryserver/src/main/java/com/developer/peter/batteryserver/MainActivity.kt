package com.developer.peter.batteryserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.developer.peter.batteryserver.ui.components.BatteryServerScreen
import com.developer.peter.batteryserver.util.BlePermissionHelper

class MainActivity : ComponentActivity() {
    private val viewModel: BatteryServerViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            // Permissions granted, we can start server if needed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!BlePermissionHelper.hasRequiredPermissions(this)) {
            requestPermissionLauncher.launch(BlePermissionHelper.permissions)
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isAdvertising by viewModel.isAdvertising.collectAsState()
                    val connectionState by viewModel.connectionState.collectAsState()
                    val currentBatteryLevel by viewModel.currentBatteryLevel.collectAsState()
                    val currentMa by viewModel.currentMa.collectAsState()

                    BatteryServerScreen(
                        isAdvertising = isAdvertising,
                        connectionState = connectionState,
                        currentBatteryLevel = currentBatteryLevel,
                        currentMa = currentMa,
                        onToggleServer = {
                            if (BlePermissionHelper.hasRequiredPermissions(this)) {
                                viewModel.toggleServer()
                            } else {
                                requestPermissionLauncher.launch(BlePermissionHelper.permissions)
                            }
                        }
                    )
                }
            }
        }
    }
}