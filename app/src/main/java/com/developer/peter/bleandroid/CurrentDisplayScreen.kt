package com.developer.peter.bleandroid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun CurrentDisplayScreen(viewModel: MainViewModel) {
    val currentMa by viewModel.currentMa.collectAsState()
    val currentAvgMa by viewModel.currentAvgMa.collectAsState()
    val batteryPct by viewModel.currentBatteryLevel.collectAsState()
    val currentHistory by viewModel.currentHistory.collectAsState()
    val lastPercentChangeTime by viewModel.lastPercentChangeTime.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "电流监控",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 电池百分比大显示
        Text(
            text = "$batteryPct%",
            fontSize = 72.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (batteryPct > 20) Color(0xFF4CAF50) else Color(0xFFF44336)
        )
        Text(text = "当前电量", fontSize = 16.sp, color = Color.Gray)

        if (lastPercentChangeTime > 0) {
            Text(
                text = "电量变化 1% 用时: ${formatTime(lastPercentChangeTime)}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 电流曲线图
        CurrentChart(
            history = currentHistory,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(vertical = 16.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 电流信息卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoColumn(label = "实时电流", value = "$currentMa mA")
                VerticalDivider(modifier = Modifier.height(40.dp).width(1.dp), color = Color.Gray.copy(alpha = 0.5f))
                InfoColumn(label = "平均电流", value = "$currentAvgMa mA")
            }
        }
    }
}

@Composable
fun CurrentChart(history: List<Int>, modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    
    Box(modifier = modifier) {
        if (history.size > 1) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val maxValRaw = history.maxOfOrNull { it }?.toFloat() ?: 0f
                val minValRaw = history.minOfOrNull { it }?.toFloat() ?: 0f
                
                // Adjust range to include 0 and have some padding
                val displayMax = maxValRaw.coerceAtLeast(100f) + 50f
                val displayMin = minValRaw.coerceAtMost(-100f) - 50f
                val displayRange = displayMax - displayMin

                val width = size.width
                val height = size.height
                
                // Left padding for labels
                val chartPaddingLeft = 40.dp.toPx()
                val chartWidth = width - chartPaddingLeft
                val stepX = chartWidth / (history.size - 1)

                // Draw horizontal grid lines and labels
                val gridLines = listOf(displayMax, (displayMax + displayMin) / 2, displayMin, 0f)
                gridLines.distinct().forEach { value ->
                    if (value in displayMin..displayMax) {
                        val y = height - ((value - displayMin) / displayRange * height)
                        
                        // Reference line
                        drawLine(
                            color = if (value == 0f) labelColor.copy(alpha = 0.5f) else labelColor.copy(alpha = 0.1f),
                            start = androidx.compose.ui.geometry.Offset(chartPaddingLeft, y),
                            end = androidx.compose.ui.geometry.Offset(width, y),
                            strokeWidth = if (value == 0f) 1.dp.toPx() else 0.5.dp.toPx()
                        )
                    }
                }

                // Draw the curve
                val path = Path()
                history.forEachIndexed { index, value ->
                    val x = chartPaddingLeft + index * stepX
                    val y = height - ((value - displayMin) / displayRange * height)
                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                drawPath(
                    path = path,
                    color = primaryColor,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            
            // Labels (using overlay to avoid complex TextMeasurer in Canvas for now)
            Column(
                modifier = Modifier.fillMaxHeight().width(40.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                val maxValRaw = history.maxOfOrNull { it } ?: 0
                val minValRaw = history.minOfOrNull { it } ?: 0
                val displayMax = maxValRaw.coerceAtLeast(100) + 50
                val displayMin = minValRaw.coerceAtMost(-100) - 50
                
                Text(text = "${displayMax}", fontSize = 10.sp, color = labelColor)
                Text(text = "${(displayMax + displayMin) / 2}", fontSize = 10.sp, color = labelColor)
                Text(text = "${displayMin}", fontSize = 10.sp, color = labelColor)
            }
        } else {
            Text(
                text = "正在收集数据...",
                modifier = Modifier.align(Alignment.Center),
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

private fun formatTime(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins}分${secs}秒" else "${secs}秒"
}

@Composable
fun InfoColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}