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
        } else {
            // Already have permissions, ViewModel init will start service
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
                    val currentAvgMa by viewModel.currentAvgMa.collectAsState()
                    val chargeCounter by viewModel.chargeCounter.collectAsState()
                    val energyCounter by viewModel.energyCounter.collectAsState()
                    val batteryStatus by viewModel.batteryStatus.collectAsState()

                    BatteryServerScreen(
                        isAdvertising = isAdvertising,
                        connectionState = connectionState,
                        currentBatteryLevel = currentBatteryLevel,
                        currentMa = currentMa,
                        currentAvgMa = currentAvgMa,
                        chargeCounter = chargeCounter,
                        energyCounter = energyCounter,
                        batteryStatus = batteryStatus,
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