package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.bluetooth.ConnectionStatus
import com.example.data.bluetooth.DiscoveredDevice
import com.example.data.bluetooth.TransportMode
import com.example.ui.theme.*
import com.example.ui.viewmodel.RadioChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: RadioChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val transportMode by viewModel.transportMode.collectAsState()
    val simulatedPacketLoss by viewModel.simulatedPacketLoss.collectAsState()
    val passphrase by viewModel.passphrase.collectAsState()
    val tcpRetryLimit by viewModel.tcpRetryLimit.collectAsState()
    val isForegroundEnabled by viewModel.isForegroundServiceEnabled.collectAsState()

    var showKeyDialog by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf(passphrase) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.refreshDevices()
        }
    }

    fun requestPermissionsAndRefresh() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.refreshDevices()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TelegramBgDark)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section: Protocol & TCP Retry Limit (Requirement 3)
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Repeat, contentDescription = null, tint = TelegramBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TCP RETRANSMISSION & TIMEOUT",
                            style = MaterialTheme.typography.labelSmall,
                            color = TelegramBlue,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Current TCP Retry Limit: ${if (tcpRetryLimit == Int.MAX_VALUE) "Infinite (∞)" else "$tcpRetryLimit retries"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextBright,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "The 500ms loop will resend unacknowledged 32B chunks up to this limit before marking as Failed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Preset buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(10, 50, 100, 1000, Int.MAX_VALUE).forEach { limit ->
                            val label = if (limit == Int.MAX_VALUE) "∞ Infinite" else "$limit"
                            val isSelected = tcpRetryLimit == limit
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setTcpRetryLimit(limit) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TelegramBlueDark,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // Section: Transmission Medium (Hardware vs Simulator)
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "TRANSMISSION MEDIUM",
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
                        FilledTonalButton(
                            onClick = {
                                viewModel.setTransportMode(TransportMode.HARDWARE_BLUETOOTH)
                                requestPermissionsAndRefresh()
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (transportMode == TransportMode.HARDWARE_BLUETOOTH) TelegramBlueDark else TelegramSurfaceHover,
                                contentColor = if (transportMode == TransportMode.HARDWARE_BLUETOOTH) Color.White else TextMuted
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ESP32 Bluetooth", fontSize = 12.sp)
                        }

                        FilledTonalButton(
                            onClick = { viewModel.setTransportMode(TransportMode.VIRTUAL_SIMULATOR) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (transportMode == TransportMode.VIRTUAL_SIMULATOR) TelegramBlueDark else TelegramSurfaceHover,
                                contentColor = if (transportMode == TransportMode.VIRTUAL_SIMULATOR) Color.White else TextMuted
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Virtual Mesh", fontSize = 12.sp)
                        }
                    }

                    // Packet loss slider if in simulator mode
                    if (transportMode == TransportMode.VIRTUAL_SIMULATOR) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Simulated RF Loss: $simulatedPacketLoss% (Tests 500ms ACK Retries)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TelegramAmber,
                            fontSize = 11.sp
                        )
                        Slider(
                            value = simulatedPacketLoss.toFloat(),
                            onValueChange = { viewModel.setSimulatedPacketLoss(it.toInt()) },
                            valueRange = 0f..60f,
                            steps = 5,
                            colors = SliderDefaults.colors(
                                thumbColor = TelegramAmber,
                                activeTrackColor = TelegramAmber
                            )
                        )
                    }
                }
            }
        }

        // Section: Foreground Service
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "KEEP BRIDGE RUNNING IN BACKGROUND",
                            style = MaterialTheme.typography.labelSmall,
                            color = TelegramBlue,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Maintains persistent Bluetooth connection and listens for radio packets when minimized.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = isForegroundEnabled,
                        onCheckedChange = { viewModel.toggleForegroundService(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TelegramBlue
                        )
                    )
                }
            }
        }

        // Section: AES-256 Pre-Shared Key
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AES-256 PRE-SHARED KEY",
                            style = MaterialTheme.typography.labelSmall,
                            color = TelegramBlue,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Key: ${passphrase.take(16)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBright,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            keyInput = passphrase
                            showKeyDialog = true
                        }
                    ) {
                        Text("Edit", fontSize = 11.sp)
                    }
                }
            }
        }

        // Section: Target ESP32 Nodes
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "QUICK CONNECT ESP32 NODES",
                        style = MaterialTheme.typography.labelSmall,
                        color = TelegramBlue,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.connectQuickNode("ESP_Node_1") },
                            modifier = Modifier.weight(1f),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(TelegramBlue)
                            )
                        ) {
                            Text("ESP_Node_1", color = TelegramBlue, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { viewModel.connectQuickNode("ESP_Node_2") },
                            modifier = Modifier.weight(1f),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(TelegramAmber)
                            )
                        ) {
                            Text("ESP_Node_2", color = TelegramAmber, fontSize = 12.sp)
                        }
                    }

                    if (connectionStatus == ConnectionStatus.CONNECTED) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.disconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = TelegramRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Disconnect ($connectedDeviceName)")
                        }
                    }
                }
            }
        }

        // Section: Clear Database History
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TelegramSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LOCAL ROOM DATABASE",
                            style = MaterialTheme.typography.labelSmall,
                            color = TelegramRed,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Erase all local message history and cached packets.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TelegramRed),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(TelegramRed)
                        )
                    ) {
                        Text("Clear All")
                    }
                }
            }
        }
    }

    // Key Dialog
    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("AES-256 Pre-Shared Key") },
            text = {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (keyInput.isNotBlank()) {
                            viewModel.updatePassphrase(keyInput.trim())
                        }
                        showKeyDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Clear Confirm Dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All Chats?") },
            text = { Text("This will permanently delete all stored messages from the local Room database.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearChatHistory()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TelegramRed)
                ) {
                    Text("Yes, Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
