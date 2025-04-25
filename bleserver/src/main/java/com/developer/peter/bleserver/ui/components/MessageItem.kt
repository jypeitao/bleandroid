package com.developer.peter.bleserver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.developer.peter.bleserver.data.BleMessage
import com.developer.peter.bleserver.data.MessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageItem(message: BleMessage) {
    val alignment = if (message.type == MessageType.SENT)
        Alignment.End else Alignment.Start
    val backgroundColor = if (message.type == MessageType.SENT)
        Color(0xFF90CAF9) else Color(0xFFE0E0E0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = backgroundColor,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            Text(text = message.content)
        }

        Text(
            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(message.timestamp)),
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}