package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AnalyticsPoint
import com.example.data.model.PacketLogEntry
import com.example.ui.theme.*
import com.example.ui.viewmodel.RadioChatViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: RadioChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val analytics by viewModel.analytics.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()

    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredLogs = remember(logs, selectedFilter) {
        when (selectedFilter) {
            "TX" -> logs.filter { it.direction == PacketLogEntry.Direction.TX && !it.isAck }
            "RX" -> logs.filter { it.direction == PacketLogEntry.Direction.RX && !it.isAck }
            "ACK" -> logs.filter { it.isAck }
            "CMD" -> logs.filter { it.typeName.startsWith("CMD") }
            else -> logs
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    val totalPackets = analytics.packetsSent + analytics.packetsReceived
    val successRate = if (totalPackets > 0) {
        ((totalPackets - analytics.packetsFailed).toFloat() / totalPackets.toFloat() * 100f).coerceIn(0f, 100f)
    } else {
        100f
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TelegramBgDark)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NETWORK ANALYTICS & LOGS",
                        style = MaterialTheme.typography.titleSmall,
                        color = TelegramBlue,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Real-time throughput over NRF24 Ch 115 (Last 60 Minutes)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                IconButton(
                    onClick = {
                        val textToCopy = filteredLogs.joinToString("\n") {
                            "[${it.direction}] TYPE:${it.typeName} ID:${it.messageId} CHK:${it.chunkIndex}/${it.totalChunks}\nHEX: ${it.hexDump}"
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("RF Logs", textToCopy))
                        Toast.makeText(context, "Copied logs to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("copy_logs_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy logs",
                        tint = TelegramBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Metrics Grid (4 KPI Cards)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    title = "TX Packets",
                    value = "${analytics.packetsSent}",
                    color = TelegramBlue,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "RX Packets",
                    value = "${analytics.packetsReceived}",
                    color = TelegramGreen,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Success Rate",
                    value = String.format("%.1f%%", successRate),
                    color = if (successRate > 90) TelegramGreen else TelegramAmber,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Failed",
                    value = "${analytics.packetsFailed}",
                    color = if (analytics.packetsFailed > 0) TelegramRed else TextMuted,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Secondary metrics: Gzip Bandwidth Savings
        item {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Compress,
                            contentDescription = "Compression",
                            tint = TelegramBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Bandwidth Saved via GZIP:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBright
                        )
                    }

                    Text(
                        text = "${analytics.bytesSavedGzip} Bytes (${(analytics.bytesSavedGzip / 26)} chunks saved)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TelegramGreen,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Real-Time Canvas Line Graph
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PACKET THROUGHPUT (60m WINDOW)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextBright,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )

                        // Legend
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LegendDot(color = TelegramBlue, label = "Sent")
                            LegendDot(color = TelegramGreen, label = "Recv")
                            LegendDot(color = TelegramRed, label = "Failed")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Compose Canvas Chart
                    AnalyticsLineChart(
                        points = analytics.timePoints,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("-60m", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 9.sp)
                        Text("-45m", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 9.sp)
                        Text("-30m", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 9.sp)
                        Text("-15m", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 9.sp)
                        Text("Now", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 9.sp)
                    }
                }
            }
        }

        // Filter chips for Raw Hex Log
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text("ALL (${logs.size})", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = selectedFilter == "TX",
                        onClick = { selectedFilter = "TX" },
                        label = { Text("TX", fontSize = 11.sp, color = LogTxColor) }
                    )
                    FilterChip(
                        selected = selectedFilter == "RX",
                        onClick = { selectedFilter = "RX" },
                        label = { Text("RX", fontSize = 11.sp, color = LogRxColor) }
                    )
                    FilterChip(
                        selected = selectedFilter == "ACK",
                        onClick = { selectedFilter = "ACK" },
                        label = { Text("ACKs", fontSize = 11.sp, color = LogAckColor) }
                    )
                    FilterChip(
                        selected = selectedFilter == "CMD",
                        onClick = { selectedFilter = "CMD" },
                        label = { Text("Commands", fontSize = 11.sp, color = TelegramAmber) }
                    )
                }

                IconButton(
                    onClick = { viewModel.clearLogs() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear logs",
                        tint = TelegramRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Raw 32-Byte Hex Packet Inspector Entries
        if (filteredLogs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No packets matching filter.",
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            items(filteredLogs.takeLast(50), key = { it.id }) { log ->
                HexPacketInspectorItem(log = log)
            }
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = TelegramSurface),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontSize = 9.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, color = TextMuted, fontSize = 10.sp)
    }
}

/**
 * Pure Jetpack Compose Canvas Line Graph for 60-Minute Real-Time Packet Rates.
 */
@Composable
fun AnalyticsLineChart(
    points: List<AnalyticsPoint>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val padding = 16f

        val effectiveWidth = width - padding * 2
        val effectiveHeight = height - padding * 2

        // Draw horizontal grid lines
        val gridLines = 4
        for (i in 0..gridLines) {
            val y = padding + (effectiveHeight / gridLines) * i
            drawLine(
                color = Color(0xFF232E3C),
                start = Offset(padding, y),
                end = Offset(width - padding, y),
                strokeWidth = 1f
            )
        }

        if (points.isEmpty()) return@Canvas

        val maxSent = points.maxOfOrNull { it.sentCount } ?: 1
        val maxRecv = points.maxOfOrNull { it.receivedCount } ?: 1
        val maxFail = points.maxOfOrNull { it.failedCount } ?: 1
        val maxY = max(10, maxOf(maxSent, maxRecv, maxFail))

        fun getX(index: Int): Float {
            val step = effectiveWidth / max(1, points.size - 1)
            return padding + index * step
        }

        fun getY(count: Int): Float {
            val ratio = count.toFloat() / maxY.toFloat()
            return padding + effectiveHeight - (ratio * effectiveHeight)
        }

        // Draw Sent line (Telegram Blue)
        drawMetricPath(points.mapIndexed { idx, p -> Offset(getX(idx), getY(p.sentCount)) }, TelegramBlue)

        // Draw Received line (Telegram Green)
        drawMetricPath(points.mapIndexed { idx, p -> Offset(getX(idx), getY(p.receivedCount)) }, TelegramGreen)

        // Draw Failed line (Telegram Red)
        drawMetricPath(points.mapIndexed { idx, p -> Offset(getX(idx), getY(p.failedCount)) }, TelegramRed)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMetricPath(
    coords: List<Offset>,
    color: Color
) {
    if (coords.isEmpty()) return

    val path = Path()
    path.moveTo(coords.first().x, coords.first().y)
    for (i in 1 until coords.size) {
        path.lineTo(coords[i].x, coords[i].y)
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.5f)
    )

    // Dots at coordinate points
    for (point in coords) {
        drawCircle(
            color = color,
            radius = 3f,
            center = point
        )
    }
}

@Composable
fun HexPacketInspectorItem(log: PacketLogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val formattedTime = remember(log.timestamp) { timeFormat.format(Date(log.timestamp)) }

    val dirColor = when {
        log.isAck -> LogAckColor
        log.direction == PacketLogEntry.Direction.TX -> LogTxColor
        log.direction == PacketLogEntry.Direction.RX -> LogRxColor
        else -> LogErrColor
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(LogTerminalBg)
            .border(0.6.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(7.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = dirColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Text(
                            text = if (log.isAck) "ACK" else log.direction.name,
                            color = dirColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${log.typeName} (0x${String.format("%02X", log.messageId)})",
                        color = TextBright,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Chunk ${log.chunkIndex}/${log.totalChunks}",
                        color = TelegramBlue,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = formattedTime,
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = log.hexDump,
                color = if (log.isAck) LogAckColor else TextBright,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 12.sp
            )
        }
    }
}
