package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainTab
import com.example.ui.viewmodel.RadioChatViewModel
import com.example.util.NotificationHandler

class MainActivity : ComponentActivity() {

    private val viewModel: RadioChatViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // Stop any active emergency alarm when user returns to the app
        NotificationHandler.stopAlarm()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Requirement 1: Explicitly set decor fits system windows to false so IME padding works seamlessly
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        setContent {
            RadioBridgeTheme {
                val selectedTab by viewModel.selectedTab.collectAsState()
                var showWiringDialog by remember { mutableStateOf(false) }

                val density = LocalDensity.current
                val isKeyboardOpen = WindowInsets.ime.getBottom(density) > 0

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Column {
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
