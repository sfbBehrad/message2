package com.example.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.example.data.bluetooth.ConnectionStatus
import com.example.data.bluetooth.TransportMode
import com.example.data.model.ChatMessage
import com.example.data.model.MessageStatus
import com.example.data.model.RadioPacket
import com.example.data.model.SenderType
import com.example.data.model.TopologyState
import com.example.data.protocol.PacketParser
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainTab
import com.example.ui.viewmodel.RadioChatViewModel
import com.example.util.NotificationHandler
import com.example.util.PreSendMetrics
import kotlinx.coroutines.launch
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: RadioChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val messages by viewModel.messages.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val deviceName by viewModel.connectedDeviceName.collectAsState()
    val topology by viewModel.topologyState.collectAsState()
    val transportMode by viewModel.transportMode.collectAsState()
    val replyingToMessage by viewModel.replyingToMessage.collectAsState()
    val activeTransfers by viewModel.activeTransfers.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var selectedMessageForAction by remember { mutableStateOf<ChatMessage?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editTextValue by remember { mutableStateOf("") }
    var showSendPriorityMenu by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }

    // Pre-Send Image Quality Configurator state
    var pendingImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var imageQualitySlider by remember { mutableFloatStateOf(50f) }
    var preSendMetrics by remember { mutableStateOf<PreSendMetrics?>(null) }
    var showPreSendSheet by remember { mutableStateOf(false) }

    // Recalculate metrics when quality slider or image changes
    LaunchedEffect(pendingImageBytes, imageQualitySlider) {
        val bytes = pendingImageBytes
        if (bytes != null) {
            preSendMetrics = viewModel.calculateImagePreSendMetrics(bytes, imageQualitySlider.toInt())
        }
    }

    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isKeyboardOpen = imeBottom > 0
    val navBarBottom = WindowInsets.navigationBars.getBottom(density)
    val bottomGapPadding = if (!isKeyboardOpen) {
        with(density) { navBarBottom.toDp() }
    } else {
        0.dp
    }

    LaunchedEffect(Unit) {
        NotificationHandler.stopAlarm()
        viewModel.markChatAsSeen()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
        viewModel.markChatAsSeen()
    }

    // Media picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes() ?: return@launch
                    val mimeType = context.contentResolver.getType(uri) ?: ""
                    val isImage = mimeType.startsWith("image/") || bytes.size < 2000000

                    if (isImage) {
                        pendingImageBytes = bytes
                        imageQualitySlider = 50f
                        preSendMetrics = viewModel.calculateImagePreSendMetrics(bytes, 50)
                        showPreSendSheet = true
                    } else {
                        val fileName = "doc_${System.currentTimeMillis()}.bin"
                        viewModel.sendFile(bytes, fileName, isImage = false)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Failed to load file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TelegramBgDark)
            .imePadding()
            .padding(bottom = bottomGapPadding)
    ) {
        // Telegram-Style Header Bar
        Surface(
            color = TelegramSurface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Topology Nav Button
                IconButton(
                    onClick = { viewModel.selectTab(MainTab.TOPOLOGY) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = "Topology",
                        tint = TelegramBlue
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Avatar Icon
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2AABEE)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "RB",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName ?: "ESP32 Radio Bridge",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextBright,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )

                    // Live Mini-Indicator for E2E Mesh Topology (Requirement 1)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.selectTab(MainTab.TOPOLOGY) }
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
                            text = "Status: ${topology.statusSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when (topology.statusLevel) {
                                TopologyState.StatusLevel.OK -> TelegramGreen
                                TopologyState.StatusLevel.WARNING -> TelegramAmber
                                TopologyState.StatusLevel.ERROR -> TelegramRed
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (topology.roundTripLatencyMs > 0 && topology.remoteLinkActive) {
                            Text(
                                text = " (${topology.roundTripLatencyMs}ms)",
                                fontSize = 10.sp,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Options Menu
                Box {
                    IconButton(
                        onClick = { showTopMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = TextBright
                        )
                    }

                    DropdownMenu(
                        expanded = showTopMenu,
                        onDismissRequest = { showTopMenu = false },
                        modifier = Modifier.background(TelegramSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Network Dashboard", color = TextBright) },
                            onClick = {
                                showTopMenu = false
                                viewModel.selectTab(MainTab.TOPOLOGY)
                            },
                            leadingIcon = { Icon(Icons.Default.Hub, contentDescription = null, tint = TelegramBlue) }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings & Retry Limits", color = TextBright) },
                            onClick = {
                                showTopMenu = false
                                viewModel.selectTab(MainTab.SETTINGS)
                            },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = TelegramBlue) }
                        )
                        DropdownMenuItem(
                            text = { Text("Network Analytics", color = TextBright) },
                            onClick = {
                                showTopMenu = false
                                viewModel.selectTab(MainTab.ANALYTICS)
                            },
                            leadingIcon = { Icon(Icons.Default.Insights, contentDescription = null, tint = TelegramBlue) }
                        )
                    }
                }
            }
        }

        // Active Emergency Alarm Banner (Tap to silence)
        if (NotificationHandler.isAlarmActive) {
            Surface(
                color = TelegramRed,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { NotificationHandler.stopAlarm() })
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Emergency, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CRITICAL ALARM ACTIVE - Tap to Silence",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Active Selective Repeat Media Transfer Progress Bar
        if (activeTransfers.isNotEmpty()) {
            Surface(
                color = TelegramSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    activeTransfers.values.forEach { transfer ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (transfer.isSender) "Transmitting Media (Selective Repeat)..." else "Receiving Media...",
                                fontSize = 11.sp,
                                color = TelegramBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${transfer.percent}% (${transfer.sentOrReceivedChunks}/${transfer.totalChunks} chunks)",
                                fontSize = 10.sp,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { transfer.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = TelegramBlue,
                            trackColor = Color(0x33FFFFFF)
                        )
                    }
                }
            }
        }

        // Messages List
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Encrypted Mesh",
                        tint = TelegramBlueDark,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "End-to-End Encrypted Radio Mesh",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextBright,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Silent Pings monitor E2E link health.\nLong-press Send for Priority or Emergency Alarm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = Color(0x33000000),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "NRF24 Channel 115 • Offline Mesh",
                                    color = TextBright,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    items(messages, key = { it.id }) { message ->
                        TelegramMessageBubble(
                            message = message,
                            onSwipeReply = { viewModel.setReplyingTo(message) },
                            onLongPress = { selectedMessageForAction = message }
                        )
                    }
                }
            }
        }

        // Reply Preview Banner
        AnimatedVisibility(
            visible = replyingToMessage != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            replyingToMessage?.let { replyTarget ->
                Surface(
                    color = TelegramSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(36.dp)
                                .background(TelegramBlue, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (replyTarget.sender == SenderType.ME) "Replying to yourself" else "Replying to Node 2",
                                style = MaterialTheme.typography.labelSmall,
                                color = TelegramBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = replyTarget.textContent ?: (if (replyTarget.isImage) "Image" else "File"),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                maxLines = 1,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(
                            onClick = { viewModel.setReplyingTo(null) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel reply",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Chat Input Bar with Long-Press Send Menu
        Surface(
            color = TelegramSurface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach File / Image button
                IconButton(
                    onClick = { filePickerLauncher.launch("image/*") },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("attach_file_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach Media",
                        tint = Color(0xFFA0B0C0),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Message Text Field
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = {
                        Text(
                            text = "Message...",
                            color = TextMuted,
                            fontSize = 14.sp
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .testTag("chat_text_input"),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextBright,
                        unfocusedTextColor = TextBright,
                        focusedBorderColor = Color(0xFF283647),
                        unfocusedBorderColor = BorderSubtle,
                        focusedContainerColor = TelegramBgDark,
                        unfocusedContainerColor = TelegramBgDark
                    ),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Send Button with Long-Press Anchor (Telegram Style)
                Box {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(TelegramBlue)
                            .combinedClickable(
                                onClick = {
                                    if (textInput.isNotBlank()) {
                                        viewModel.sendTextMessage(textInput)
                                        textInput = ""
                                    }
                                },
                                onLongClick = {
                                    if (textInput.isNotBlank()) {
                                        showSendPriorityMenu = true
                                    } else {
                                        Toast.makeText(context, "Type a message first to select priority", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            .testTag("send_message_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    if (showSendPriorityMenu) {
                        Popup(
                            alignment = Alignment.BottomEnd,
                            offset = IntOffset(-10, -52),
                            onDismissRequest = { showSendPriorityMenu = false },
                            properties = PopupProperties(focusable = true)
                        ) {
                            TelegramPrioritySendMenu(
                                onSelectSilent = {
                                    if (textInput.isNotBlank()) {
                                        viewModel.sendTextMessage(textInput, PacketParser.TYPE_SILENT)
                                        textInput = ""
                                    }
                                    showSendPriorityMenu = false
                                },
                                onSelectSound = {
                                    if (textInput.isNotBlank()) {
                                        viewModel.sendTextMessage(textInput, PacketParser.TYPE_TCP_NORMAL)
                                        textInput = ""
                                    }
                                    showSendPriorityMenu = false
                                },
                                onSelectAlarm = {
                                    if (textInput.isNotBlank()) {
                                        viewModel.sendTextMessage(textInput, PacketParser.TYPE_CRITICAL_ALARM)
                                        textInput = ""
                                    }
                                    showSendPriorityMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Pre-Send Image Quality Configurator ModalBottomSheet (Requirement 3)
    if (showPreSendSheet && pendingImageBytes != null) {
        ModalBottomSheet(
            onDismissRequest = {
                showPreSendSheet = false
                pendingImageBytes = null
            },
            containerColor = TelegramSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "IMAGE QUALITY CONFIGURATOR",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TelegramBlue,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Optimize byte size before AES-256 chunking & Selective Repeat blast",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Quality Slider & Value
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("JPEG Quality: ${imageQualitySlider.toInt()}%", style = MaterialTheme.typography.bodyMedium, color = TextBright, fontWeight = FontWeight.Bold)
                    Text("Max Dim: 800px", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }

                Slider(
                    value = imageQualitySlider,
                    onValueChange = { imageQualitySlider = it },
                    valueRange = 10f..100f,
                    steps = 18,
                    colors = SliderDefaults.colors(
                        thumbColor = TelegramBlue,
                        activeTrackColor = TelegramBlue
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Dynamic Metrics Display Card
                preSendMetrics?.let { metrics ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TelegramBgDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Estimated File Size", fontSize = 10.sp, color = TextMuted)
                                    Text(metrics.fileSizeFormatted, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TelegramGreen, fontFamily = FontFamily.Monospace)
                                }
                                Column {
                                    Text("Estimated 26B Packets", fontSize = 10.sp, color = TextMuted)
                                    Text("${metrics.estimatedPackets} packets", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TelegramAmber, fontFamily = FontFamily.Monospace)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Est. Blast Time", fontSize = 10.sp, color = TextMuted)
                                    Text("~${String.format("%.1f", metrics.estimatedTransferTimeSec)}s", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TelegramBlue, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showPreSendSheet = false
                                pendingImageBytes = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                viewModel.sendImageSelectiveRepeat(metrics.compressedBytes, imageQualitySlider.toInt())
                                showPreSendSheet = false
                                pendingImageBytes = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TelegramBlue),
                            modifier = Modifier.weight(1.5f).testTag("confirm_send_image_btn")
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send (${metrics.estimatedPackets} Packets)")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Message Options Dialog
    selectedMessageForAction?.let { message ->
        AlertDialog(
            onDismissRequest = { selectedMessageForAction = null },
            title = {
                Text(
                    text = "Message Options",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextBright
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            viewModel.setReplyingTo(message)
                            selectedMessageForAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = TelegramBlue)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Reply", color = TextBright)
                        }
                    }

                    if (!message.textContent.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(message.textContent))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                selectedMessageForAction = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TelegramBlue)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Copy Text", color = TextBright)
                            }
                        }
                    }

                    if (message.sender == SenderType.ME && !message.textContent.isNullOrBlank() && !message.isImage) {
                        TextButton(
                            onClick = {
                                editTextValue = message.textContent
                                showEditDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = TelegramAmber)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Edit Message (Type 6)", color = TextBright)
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            viewModel.deleteMessage(message)
                            selectedMessageForAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = TelegramRed)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Delete Message (Type 5)", color = TelegramRed)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessageForAction = null }) {
                    Text("Close", color = TextMuted)
                }
            }
        )
    }

    // Edit Message Dialog
    if (showEditDialog && selectedMessageForAction != null) {
        val messageToEdit = selectedMessageForAction!!
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Message", color = TextBright) },
            text = {
                OutlinedTextField(
                    value = editTextValue,
                    onValueChange = { editTextValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Edited text") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.editMessage(messageToEdit, editTextValue)
                        showEditDialog = false
                        selectedMessageForAction = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TelegramBlue)
                ) {
                    Text("Save & Transmit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TelegramPrioritySendMenu(
    onSelectSilent: () -> Unit,
    onSelectSound: () -> Unit,
    onSelectAlarm: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2836)),
        modifier = Modifier
            .width(220.dp)
            .border(1.dp, Color(0xFF2C394B), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            PriorityMenuItem(
                icon = Icons.Default.VolumeOff,
                title = "Send without sound",
                onClick = onSelectSilent
            )
            HorizontalDivider(color = Color(0xFF2B3747), thickness = 0.6.dp)
            PriorityMenuItem(
                icon = Icons.Default.VolumeUp,
                title = "Send with sound",
                onClick = onSelectSound
            )
            HorizontalDivider(color = Color(0xFF2B3747), thickness = 0.6.dp)
            PriorityMenuItem(
                icon = Icons.Default.Emergency,
                title = "Send with loud alarm",
                tint = TelegramAmber,
                onClick = onSelectAlarm
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PriorityMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tint: Color = Color(0xFFA0B0C0),
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

/**
 * Telegram-style Message Bubble with Coil AsyncImage and accurate tick statuses.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TelegramMessageBubble(
    message: ChatMessage,
    onSwipeReply: () -> Unit,
    onLongPress: () -> Unit
) {
    val isMe = message.sender == SenderType.ME
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    val bubbleBg = if (isMe) TelegramOutgoingBubble else TelegramIncomingBubble
    val bubbleBorder = if (isMe) Color(0xFF386595) else BorderSubtle

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    if (delta > 0 || offsetX.value > 0) {
                        coroutineScope.launch {
                            val newOffset = (offsetX.value + delta).coerceIn(0f, 100f)
                            offsetX.snapTo(newOffset)
                        }
                    }
                },
                onDragStopped = {
                    if (offsetX.value > 50f) {
                        onSwipeReply()
                    }
                    coroutineScope.launch {
                        offsetX.animateTo(0f)
                    }
                }
            )
    ) {
        if (offsetX.value > 10f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = TelegramBlue,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) },
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
        ) {
            Card(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isMe) 16.dp else 4.dp,
                    bottomEnd = if (isMe) 4.dp else 16.dp
                ),
                colors = CardDefaults.cardColors(containerColor = bubbleBg),
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .border(0.8.dp, bubbleBorder, RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {

                    // Replied message preview header
                    if (!message.replyToText.isNullOrBlank()) {
                        Surface(
                            color = Color(0x22000000),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(24.dp)
                                        .background(TelegramBlue, RoundedCornerShape(2.dp))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "Replied",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TelegramBlue,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = message.replyToText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextBright,
                                        maxLines = 1,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    // Priority Badge (Silent or Emergency Alarm)
                    if (message.type == RadioPacket.TYPE_SILENT) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                            Icon(Icons.Default.VolumeOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Silent", color = TextMuted, fontSize = 9.sp)
                        }
                    } else if (message.type == RadioPacket.TYPE_CRITICAL_ALARM) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                            Icon(Icons.Default.Emergency, contentDescription = null, tint = TelegramAmber, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("CRITICAL ALARM", color = TelegramAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Media / Image Preview using Coil AsyncImage (Requirement 3)
                    if (message.isImage && (message.filePath != null || message.fileBytes != null)) {
                        AsyncImage(
                            model = message.filePath ?: message.fileBytes,
                            contentDescription = "Decrypted Radio Image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(170.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Text Content
                    if (!message.textContent.isNullOrBlank()) {
                        Text(
                            text = message.textContent,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (message.isDeleted) TextMuted else TextBright,
                            fontSize = 14.sp,
                            lineHeight = 18.sp
                        )
                    }

                    // Progress bar for multi-chunk transfers
                    if (message.totalChunks > 1 && message.status == MessageStatus.SENDING) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column {
                            val progress = if (message.totalChunks > 0)
                                message.currentChunk.toFloat() / message.totalChunks.toFloat()
                            else 0f

                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = TelegramBlue,
                                trackColor = Color(0x33FFFFFF)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Chunk ${message.currentChunk}/${message.totalChunks} (32B)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (message.retriesCurrentChunk > 0) {
                                    Text(
                                        text = "retry #${message.retriesCurrentChunk}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        color = TelegramAmber,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Message Footer (Timestamp + Delivery Ticks + Edited tag)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.isEdited) {
                            Text(
                                text = "edited",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontSize = 10.sp
                        )

                        if (isMe) {
                            Spacer(modifier = Modifier.width(4.dp))
                            // Accurate Delivery Tick System (Requirement 4)
                            when (message.status) {
                                // 2 Blue Ticks: Read / Seen (Type 4 ACK received)
                                MessageStatus.SEEN -> {
                                    Icon(
                                        imageVector = Icons.Default.DoneAll,
                                        contentDescription = "Read (2 Blue Ticks)",
                                        tint = TelegramSeenBlue,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                // 2 Black/Grey Ticks: Delivered (Type 3 ACK received)
                                MessageStatus.DELIVERED -> {
                                    Icon(
                                        imageVector = Icons.Default.DoneAll,
                                        contentDescription = "Delivered (2 Black Ticks)",
                                        tint = TextBright,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                // 1 Black/Grey Tick: Sent to local ESP32
                                MessageStatus.SENT_UNACKED -> {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Sent to ESP32 (1 Black Tick)",
                                        tint = TextMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                // Clock: In-flight / Attempting
                                MessageStatus.SENDING, MessageStatus.PENDING -> {
                                    Icon(
                                        imageVector = Icons.Default.AccessTime,
                                        contentDescription = "Attempting (Clock)",
                                        tint = TelegramAmber,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                // Failed
                                MessageStatus.FAILED -> {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Failed",
                                        tint = TelegramRed,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
