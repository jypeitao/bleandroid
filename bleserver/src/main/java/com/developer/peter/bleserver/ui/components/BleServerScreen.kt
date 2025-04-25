package com.developer.peter.bleserver.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.developer.peter.bleserver.BleServerViewModel
import com.developer.peter.bleserver.data.ConnectionState

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleServerScreen(
    viewModel: BleServerViewModel = viewModel(),
    onRequestPermission: () -> Unit
) {
    val isAdvertising by viewModel.isAdvertising.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val messages by viewModel.messages.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE Server") },
                actions = {
                    Button(
                        onClick = {
                            viewModel.toggleAdvertising(onRequestPermission)
                        },
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Text(if (isAdvertising) "Stop Broadcasting" else "Start Broadcasting")
                    }
                }

            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 显示广播状态
            AnimatedVisibility(
                visible = isAdvertising,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF4CAF50))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 8.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Text(
                        "Broadcasting...",
                        color = Color.White
                    )
                }
            }

            // 显示连接状态
            when (val state = connectionState) {
                is ConnectionState.Connected -> {
                    Text(
                        "Connected to: ${state.deviceAddress}",
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFF4CAF50)
                    )
                }

                is ConnectionState.Disconnected -> {
                    Text(
                        "Not Connected",
                        modifier = Modifier.padding(16.dp),
                        color = Color.Gray
                    )
                }

                is ConnectionState.Error -> {
                    Text(
                        "Error: ${state.message}",
                        modifier = Modifier.padding(16.dp),
                        color = Color.Red
                    )
                }
            }

            // 消息列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(messages) { message ->
                    MessageItem(message = message)
                }
            }

            // 消息输入框
            MessageInput(
                onSendMessage = { message ->
                    viewModel.sendMessage(message)
                },
                enabled = connectionState is ConnectionState.Connected
            )
        }
    }
}