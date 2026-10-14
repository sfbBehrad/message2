package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.bluetooth.ConnectionStatus
import com.example.data.bluetooth.TransportMode
import com.example.data.model.TopologyState
import com.example.service.BluetoothForegroundService
import com.example.ui.theme.*
import com.example.ui.viewmodel.RadioChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NetworkDashboardScreen(
    viewModel: RadioChatViewModel,
    modifier: Modifier = Modifier
) {
    val topology by viewModel.topologyState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val transportMode by viewModel.transportMode.collectAsState()
    val settings by viewModel.appSettings.collectAsState()

    // Pulse animation for active radio RF waves
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TelegramBgDark)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Overall Status Hero Banner
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (topology.statusLevel) {
                        TopologyState.StatusLevel.OK -> Color(0xFF0F382A)
                        TopologyState.StatusLevel.WARNING -> Color(0xFF382F10)
                        TopologyState.StatusLevel.ERROR -> Color(0xFF381515)
                    }
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        when (topology.statusLevel) {
                            TopologyState.StatusLevel.OK -> TelegramGreen
                            TopologyState.StatusLevel.WARNING -> TelegramAmber
                            TopologyState.StatusLevel.ERROR -> TelegramRed
                        }
                    )
                ),
                modifier = Modifier.fillMaxWidth().testTag("network_topology_banner")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (topology.statusLevel) {
                                            TopologyState.StatusLevel.OK -> TelegramGreen
                                            TopologyState.StatusLevel.WARNING -> TelegramAmber
                                            TopologyState.StatusLevel.ERROR -> TelegramRed
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = topology.statusSummary.uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextBright
                            )
                        }

                        // Heartbeat trigger button
                        FilledTonalButton(
                            onClick = { viewModel.triggerImmediatePing() },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = TelegramSurface,
                                contentColor = TelegramBlue
                            ),
                            modifier = Modifier.testTag("ping_now_btn")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ping Now", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Round-Trip Latency", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text(
                                text = if (topology.remoteLinkActive && topology.roundTripLatencyMs > 0) "${topology.roundTripLatencyMs} ms" else "---",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (topology.remoteLinkActive) TelegramGreen else TextMuted,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Heartbeat Stats", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text(
                                text = "TX: ${topology.pingsSent}  •  RX: ${topology.pongsReceived}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextBright,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // 2. Visual Topology Map (Phone 1 -> ESP 1 -> ESP 2 -> Phone 2)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "END-TO-END MESH TOPOLOGY",
                        style = MaterialTheme.typography.labelSmall,
                        color = TelegramBlue,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Topology visual row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Node 1: Local Phone
                        TopologyNodeItem(
                            icon = Icons.Default.PhoneAndroid,
                            title = "Phone 1",
                            subtitle = "This App",
                            isActive = true
                        )

                        // Leg A Link: Phone <-> ESP 1
                        TopologyLinkItem(
                            label = "BT SPP",
                            isActive = topology.localLinkActive,
                            modifier = Modifier.weight(1f)
                        )

                        // Node 2: Local ESP Node 1
                        TopologyNodeItem(
                            icon = Icons.Default.Router,
                            title = topology.localNodeName,
                            subtitle = "Bridge 1",
                            isActive = topology.localLinkActive
                        )

                        // Leg B Link: ESP 1 <-> ESP 2 (NRF24 RF)
                        TopologyLinkItem(
                            label = "NRF24 RF",
                            sublabel = "Ch 115",
                            isActive = topology.radioLinkActive,
                            pulseAlpha = if (topology.radioLinkActive) pulseAlpha else 1f,
                            isRf = true,
                            modifier = Modifier.weight(1.2f)
                        )

                        // Node 3: Remote ESP Node 2
                        TopologyNodeItem(
                            icon = Icons.Default.Router,
                            title = topology.remoteNodeName,
                            subtitle = "Bridge 2",
                            isActive = topology.radioLinkActive
                        )

                        // Leg C Link: ESP 2 <-> Phone 2
                        TopologyLinkItem(
                            label = "Remote BT",
                            isActive = topology.remoteLinkActive,
                            modifier = Modifier.weight(1f)
                        )

                        // Node 4: Remote Phone 2
                        TopologyNodeItem(
                            icon = Icons.Default.PhoneAndroid,
                            title = "Phone 2",
                            subtitle = "Peer App",
                            isActive = topology.remoteLinkActive
                        )
                    }
                }
            }
        }

        // 3. Three Distinct Communication Legs Details
        item {
            Text(
                text = "COMMUNICATION LEGS BREAKDOWN",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Leg A: Local Link
        item {
            LegDetailCard(
                legTitle = "Leg A: Local Link (Phone 1 ⇄ ESP 1)",
                protocol = "Bluetooth Classic RFCOMM (SPP)",
                details = listOf(
                    "Socket Status" to if (topology.localLinkActive) "Connected (Active Socket)" else "Disconnected",
                    "Hardware Device" to (connectedDeviceName ?: "ESP_Node_1"),
                    "Transport" to if (transportMode == TransportMode.HARDWARE_BLUETOOTH) "Physical Bluetooth Radio" else "Virtual Loopback Simulator"
                ),
                isActive = topology.localLinkActive
            )
        }

        // Leg B: Radio Link
        item {
            LegDetailCard(
                legTitle = "Leg B: Radio Link (ESP 1 ⇄ ESP 2)",
                protocol = "NRF24L01+ 2.4 GHz Transceiver",
                details = listOf(
                    "RF Channel" to "Channel 115 (2.515 GHz)",
                    "Data Rate" to "1 MBPS (AutoAck = false)",
                    "PA Level" to "RF24_PA_MAX",
                    "Radio Verification" to if (topology.radioLinkActive) "Verified (Packet Traffic Alive)" else "Unreachable / No Packets"
                ),
                isActive = topology.radioLinkActive
            )
        }

        // Leg C: Remote Link
        item {
            LegDetailCard(
                legTitle = "Leg C: Remote Link (ESP 2 ⇄ Phone 2)",
                protocol = "End-to-End Heartbeat (Ping/Pong)",
                details = listOf(
                    "Ping Header" to "Header Type 9 (32 Bytes, Every ${settings.pingIntervalSec}s)",
                    "Pong Header" to "Header Type 10 (Echoed Response)",
                    "Round-Trip Time" to if (topology.roundTripLatencyMs > 0) "${topology.roundTripLatencyMs} ms" else "No Pong Received",
                    "Peer State" to if (topology.remoteLinkActive) "Destination App Online & Answering" else "Remote Phone App Offline or Unreachable"
                ),
                isActive = topology.remoteLinkActive
            )
        }

        // 4. Quick Actions
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "NETWORK CONTROLS & DIAGNOSTICS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TelegramBlue,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (transportMode == TransportMode.HARDWARE_BLUETOOTH) {
                                    viewModel.setTransportMode(TransportMode.VIRTUAL_SIMULATOR)
                                } else {
                                    viewModel.setTransportMode(TransportMode.HARDWARE_BLUETOOTH)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (transportMode == TransportMode.HARDWARE_BLUETOOTH) "Switch to Simulator" else "Switch to Hardware",
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = { viewModel.refreshDevices() },
                            colors = ButtonDefaults.buttonColors(containerColor = TelegramBlue),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Rescan BT Nodes", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TopologyNodeItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(52.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isActive) TelegramBlueDark else TelegramSurfaceHover)
                .border(
                    width = 2.dp,
                    color = if (isActive) TelegramBlue else TextMuted.copy(alpha = 0.4f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isActive) Color.White else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isActive) TextBright else TextMuted,
            fontSize = 10.sp,
            maxLines = 1
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

@Composable
fun TopologyLinkItem(
    label: String,
    sublabel: String? = null,
    isActive: Boolean,
    pulseAlpha: Float = 1f,
    isRf: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeColor = if (isActive) (if (isRf) TelegramAmber else TelegramGreen) else TelegramRed
    val lineColor = activeColor.copy(alpha = if (isActive) pulseAlpha else 0.5f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            color = activeColor,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        sublabel?.let {
            Text(
                text = it,
                fontSize = 7.sp,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(2.dp))

        // Custom drawn connection line with dashed or solid effect
        Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            val y = size.height / 2
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (isActive) 3.dp.toPx() else 2.dp.toPx(),
                pathEffect = if (isRf) PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f) else null
            )
        }
    }
}

@Composable
fun LegDetailCard(
    legTitle: String,
    protocol: String,
    details: List<Pair<String, String>>,
    isActive: Boolean
) {
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
                    text = legTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextBright
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isActive) TelegramGreen else TelegramRed)
                )
            }
            Text(
                text = protocol,
                style = MaterialTheme.typography.labelSmall,
                color = TelegramBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            details.forEach { (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(key, fontSize = 11.sp, color = TextMuted)
                    Text(value, fontSize = 11.sp, color = TextBright, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
