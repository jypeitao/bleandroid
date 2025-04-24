package com.developer.peter.bleclient.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.developer.peter.bleclient.data.BleDevice
import com.developer.peter.bleclient.data.BleMessage
import com.developer.peter.bleclient.BlePermissionHelper
import com.developer.peter.bleclient.BleViewModel
import com.developer.peter.bleclient.data.ConnectionState

@SuppressLint("MissingPermission")
@Composable
fun BleScreen(
    viewModel: BleViewModel = viewModel(),
    onRequestPermissions: () -> Unit
) {
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    var showPermissionDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current


    LaunchedEffect(Unit) {
        if (!BlePermissionHelper.hasRequiredPermissions(context)) {
            showPermissionDialog = true
        }
    }

    Scaffold(
        topBar = @Composable {
            BleTopAppBar(
                connectionState = connectionState,
                onScanClick = {
                    if (BlePermissionHelper.hasRequiredPermissions(context)) {
                        viewModel.startScan()
                    } else {
                        showPermissionDialog = true
                    }
                },
                onDisconnectClick = {
                    viewModel.disconnect()
                }
            )
        }
    ) { padding ->
        when (connectionState) {
            is ConnectionState.Connected -> {
                ChatScreen(
                    messages = messages,
                    onSendMessage = viewModel::sendMessage,
                    modifier = Modifier.padding(padding)
                )
            }

            else -> {
                DeviceList(
                    devices = scanResults,
                    onDeviceClick = { device ->
                        viewModel.connect(device.device)
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    if (showPermissionDialog) {
        PermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onConfirm = {
                showPermissionDialog = false
                onRequestPermissions()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BleTopAppBar(
    connectionState: ConnectionState,
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    TopAppBar(
        title = { Text("BLE Client") },
        actions = {
            when (connectionState) {
                is ConnectionState.Connected -> {
                    IconButton(onClick = onDisconnectClick) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Disconnect"
                        )
                    }
                }

                is ConnectionState.Disconnected -> {
                    IconButton(onClick = onScanClick) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Scan"
                        )
                    }
                }

                else -> {}
            }
        }
    )
}

@Composable
private fun DeviceList(
    devices: List<BleDevice>,
    onDeviceClick: (BleDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        items(
            items = devices,
            key = { it.address }
        ) { device ->
            DeviceItem(
                device = device,
                onClick = { onDeviceClick(device) }
            )
        }
    }
}

@Composable
private fun ChatScreen(
    messages: List<BleMessage>,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var messageText by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            reverseLayout = true
        ) {
            items(
                items = messages.asReversed(),
                key = { it.id }
            ) { message ->
                MessageBubble(message = message)
            }
        }

        SendMessageBar(
            message = messageText,
            onMessageChange = { messageText = it },
            onSend = {
                if (messageText.isNotEmpty()) {
                    onSendMessage(messageText)
                    messageText = ""
                }
            }
        )
    }
}