package com.example

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.data.model.TopologyState
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.NetworkDashboardScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainTab
import com.example.ui.viewmodel.RadioChatViewModel
import com.example.util.NotificationHandler

class MainActivity : ComponentActivity() {

    private val viewModel: RadioChatViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        NotificationHandler.stopAlarm()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fix keyboard overlap: set decor fits system windows to false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        setContent {
            RadioBridgeTheme {
                val context = LocalContext.current
                val selectedTab by viewModel.selectedTab.collectAsState()
                val topology by viewModel.topologyState.collectAsState()
                var showWiringDialog by remember { mutableStateOf(false) }

                // Check physical Bluetooth hardware
                val isBluetoothHardwarePresent = remember {
                    BluetoothAdapter.getDefaultAdapter() != null
                }

                var hasGrantedPermissions by remember { mutableStateOf(false) }

                // Permission launcher for runtime Bluetooth & Notification permissions
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissionsMap ->
                    val allGranted = permissionsMap.values.all { it }
                    hasGrantedPermissions = allGranted
                    if (allGranted && isBluetoothHardwarePresent) {
                        try {
                            viewModel.startBluetoothServiceIfPermitted(context)
                        } catch (e: SecurityException) {
                            e.printStackTrace()
                        }
                    }
                }

                // Check permissions on startup safely without crashing
                LaunchedEffect(Unit) {
                    if (!isBluetoothHardwarePresent) {
                        // Emulator / Missing hardware bypass: bypass service start entirely
                        return@LaunchedEffect
                    }

                    val requiredPermissions = mutableListOf<String>()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                        requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
                    } else {
                        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    val missing = requiredPermissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }

                    if (missing.isEmpty()) {
                        hasGrantedPermissions = true
                        try {
                            viewModel.startBluetoothServiceIfPermitted(context)
                        } catch (e: SecurityException) {
                            e.printStackTrace()
                        }
                    } else {
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                }

                val density = LocalDensity.current
                val isKeyboardOpen = WindowInsets.ime.getBottom(density) > 0

                Scaffold(
                    topBar = {
                        Column {
                            TopAppBar(
                                title = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "RadioBridge",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = TelegramBlue
                                            )
                                            Text(
                                                text = "NRF24L01 + ESP32 • Offline Encrypted Mesh",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }

                                        // Top Live Topology status pill
                                        Surface(
                                            shape = CircleShape,
                                            color = when (topology.statusLevel) {
                                                TopologyState.StatusLevel.OK -> Color(0xFF0F382A)
                                                TopologyState.StatusLevel.WARNING -> Color(0xFF382F10)
                                                TopologyState.StatusLevel.ERROR -> Color(0xFF381515)
                                            },
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .clickable { viewModel.selectTab(MainTab.TOPOLOGY) }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            when (topology.statusLevel) {
                                                                TopologyState.StatusLevel.OK -> TelegramGreen
                                                                TopologyState.StatusLevel.WARNING -> TelegramAmber
                                                                TopologyState.StatusLevel.ERROR -> TelegramRed
                                                            }
                                                        )
                                                )
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text(
                                                    text = if (topology.isE2EConnected) "E2E OK" else "MESH",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when (topology.statusLevel) {
                                                        TopologyState.StatusLevel.OK -> TelegramGreen
                                                        TopologyState.StatusLevel.WARNING -> TelegramAmber
                                                        TopologyState.StatusLevel.ERROR -> TelegramRed
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                actions = {
                                    IconButton(
                                        onClick = { showWiringDialog = true },
                                        modifier = Modifier.testTag("wiring_info_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Hardware Wiring Guide",
                                            tint = TelegramBlue
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = TelegramSurface,
                                    titleContentColor = TelegramBlue
                                )
                            )

                            // Emulator / Missing hardware bypass UI notice
                            if (!isBluetoothHardwarePresent) {
                                Surface(
                                    color = Color(0xFF382F10),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BluetoothDisabled,
                                            contentDescription = null,
                                            tint = TelegramAmber,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Bluetooth hardware not detected (Emulator Mode) — Virtual Mesh Simulator active",
                                            fontSize = 11.sp,
                                            color = Color(0xFFFFD54F)
                                        )
                                    }
                                }
                            } else if (!hasGrantedPermissions) {
                                Surface(
                                    color = Color(0xFF1B2838),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Bluetooth permissions needed for ESP32 bridge",
                                            fontSize = 11.sp,
                                            color = TextBright
                                        )
                                        TextButton(
                                            onClick = {
                                                val required = mutableListOf<String>()
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                    required.add(Manifest.permission.BLUETOOTH_CONNECT)
                                                    required.add(Manifest.permission.BLUETOOTH_SCAN)
                                                } else {
                                                    required.add(Manifest.permission.ACCESS_FINE_LOCATION)
                                                }
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                    required.add(Manifest.permission.POST_NOTIFICATIONS)
                                                }
                                                permissionLauncher.launch(required.toTypedArray())
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Grant", fontSize = 11.sp, color = TelegramBlue)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        // Hide bottom navigation bar when keyboard is open or when in Chat screen to avoid stacking gaps
                        if (!isKeyboardOpen && selectedTab != MainTab.CHAT) {
                            NavigationBar(
                                containerColor = TelegramSurface,
                                contentColor = TelegramBlue
                            ) {
                                NavigationBarItem(
                                    selected = selectedTab == MainTab.CHAT,
                                    onClick = { viewModel.selectTab(MainTab.CHAT) },
                                    icon = {
                                        Icon(Icons.Default.ChatBubble, contentDescription = "Chat")
                                    },
                                    label = { Text("Chats") },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = TelegramBlueDark,
                                        selectedIconColor = Color.White,
                                        selectedTextColor = TelegramBlue
                                    ),
                                    modifier = Modifier.testTag("nav_chat_tab")
                                )

                                NavigationBarItem(
                                    selected = selectedTab == MainTab.TOPOLOGY,
                                    onClick = { viewModel.selectTab(MainTab.TOPOLOGY) },
                                    icon = {
                                        Icon(Icons.Default.Hub, contentDescription = "Topology")
                                    },
                                    label = { Text("Topology") },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = TelegramBlueDark,
                                        selectedIconColor = Color.White,
                                        selectedTextColor = TelegramBlue
                                    ),
                                    modifier = Modifier.testTag("nav_topology_tab")
                                )

                                NavigationBarItem(
                                    selected = selectedTab == MainTab.ANALYTICS,
                                    onClick = { viewModel.selectTab(MainTab.ANALYTICS) },
                                    icon = {
                                        Icon(Icons.Default.Insights, contentDescription = "Analytics")
                                    },
                                    label = { Text("Analytics") },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = TelegramBlueDark,
                                        selectedIconColor = Color.White,
                                        selectedTextColor = TelegramBlue
                                    ),
                                    modifier = Modifier.testTag("nav_analytics_tab")
                                )

                                NavigationBarItem(
                                    selected = selectedTab == MainTab.SETTINGS,
                                    onClick = { viewModel.selectTab(MainTab.SETTINGS) },
                                    icon = {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    },
                                    label = { Text("Settings") },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = TelegramBlueDark,
                                        selectedIconColor = Color.White,
                                        selectedTextColor = TelegramBlue
                                    ),
                                    modifier = Modifier.testTag("nav_settings_tab")
                                )
                            }
                        }
                    },
                    containerColor = TelegramBgDark,
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = innerPadding.calculateTopPadding(),
                                bottom = if (selectedTab == MainTab.CHAT) 0.dp else innerPadding.calculateBottomPadding()
                            )
                    ) {
                        when (selectedTab) {
                            MainTab.CHAT -> ChatScreen(viewModel = viewModel)
                            MainTab.TOPOLOGY -> NetworkDashboardScreen(viewModel = viewModel)
                            MainTab.ANALYTICS -> AnalyticsScreen(viewModel = viewModel)
                            MainTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                        }
                    }

                    if (showWiringDialog) {
                        HardwareWiringDialog(onDismiss = { showWiringDialog = false })
                    }
                }
            }
        }
    }
}

@Composable
fun HardwareWiringDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "ESP32 + NRF24L01 Pinout",
                fontWeight = FontWeight.Bold,
                color = TelegramBlue
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "NRF24L01 SPI connections on ESP32:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextBright
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "• CE: GPIO 22\n• CSN: GPIO 21\n• SCK: GPIO 18\n• MISO: GPIO 19\n• MOSI: GPIO 23\n• VCC: 3.3V (NOT 5V!)\n• GND: GND",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFFE2E8F0)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Note: Ensure a 10µF–100µF capacitor is across 3.3V and GND of the NRF24 module to handle peak current bursts without resets.",
                    fontSize = 11.sp,
                    color = TelegramAmber
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got It", color = TelegramBlue)
            }
        }
    )
}
