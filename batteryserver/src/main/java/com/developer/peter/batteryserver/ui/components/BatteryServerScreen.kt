package com.developer.peter.batteryserver.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.developer.peter.batteryserver.data.ConnectionState

@Composable
fun BatteryServerScreen(
    isAdvertising: Boolean,
    connectionState: ConnectionState,
    currentBatteryLevel: Int,
    currentMa: Int,
    currentAvgMa: Int,
    chargeCounter: Int,
    energyCounter: Long,
    batteryStatus: Int,
    onToggleServer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Battery BLE Service",
            fontSize = 24.sp,
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Current Status", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = if (isAdvertising) "Advertising..." else "Idle",
                    color = if (isAdvertising) Color(0xFF4CAF50) else Color.Gray,
                    style = MaterialTheme.typography.bodyLarge
                )
                
                Text(
                    text = when (connectionState) {
                        is ConnectionState.Connected -> "Connected to: ${connectionState.deviceAddress}"
                        is ConnectionState.Disconnected -> "No client connected"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Battery Data", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BatteryDataColumn("Current", "$currentMa mA")
                    BatteryDataColumn("Level", "$currentBatteryLevel %")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BatteryDataColumn("Avg Current", "$currentAvgMa mA")
                    BatteryDataColumn("Charge Counter", "$chargeCounter")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BatteryDataColumn("Energy Counter", "$energyCounter")
                    BatteryDataColumn("Status", formatStatus(batteryStatus))
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onToggleServer,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isAdvertising) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(text = if (isAdvertising) "Stop Battery Service" else "Start Battery Service")
        }
    }
}

@Composable
fun BatteryDataColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Text(text = value, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
    }
}

fun formatStatus(status: Int): String {
    return when (status) {
        android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
        android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
        android.os.BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
        else -> "Unknown"
    }
}