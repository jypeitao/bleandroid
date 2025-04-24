package com.developer.peter.bleclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.developer.peter.bleclient.data.BleMessage
import com.developer.peter.bleclient.data.MessageType

@Composable
fun MessageBubble(
    message: BleMessage,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = when (message.type) {
            MessageType.SENT -> Arrangement.End
            MessageType.RECEIVED -> Arrangement.Start
        }
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = when (message.type) {
                MessageType.SENT -> MaterialTheme.colorScheme.primary
                MessageType.RECEIVED -> MaterialTheme.colorScheme.secondary
            },
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(8.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}