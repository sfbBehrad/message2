package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.bluetooth.BluetoothService
import com.example.data.bluetooth.ConnectionStatus
import com.example.data.bluetooth.DiscoveredDevice
import com.example.data.bluetooth.TransportMode
import com.example.data.crypto.AesSecurity
import com.example.data.db.AppDatabase
import com.example.data.db.ChatRepository
import com.example.data.model.*
import com.example.data.protocol.RadioProtocolManager
import com.example.service.BluetoothForegroundService
import com.example.util.MediaCompressor
import com.example.util.NotificationHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class MainTab {
    CHAT,
    ANALYTICS,
    SETTINGS
}

class RadioChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    val repository = ChatRepository(database.chatMessageDao())

    val bluetoothService = BluetoothService(application.applicationContext)
    val protocolManager = RadioProtocolManager(application.applicationContext, bluetoothService, repository)

    private val _selectedTab = MutableStateFlow(MainTab.CHAT)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _isTcpMode = MutableStateFlow(true) // Default TCP reliable ACK loop
    val isTcpMode: StateFlow<Boolean> = _isTcpMode.asStateFlow()

    private val _tcpRetryLimit = MutableStateFlow(RadioProtocolManager.DEFAULT_RETRY_LIMIT)
    val tcpRetryLimit: StateFlow<Int> = _tcpRetryLimit.asStateFlow()

    private val _passphrase = MutableStateFlow(AesSecurity.currentPassphrase)
    val passphrase: StateFlow<String> = _passphrase.asStateFlow()

    private val _simulatedPacketLoss = MutableStateFlow(0)
    val simulatedPacketLoss: StateFlow<Int> = _simulatedPacketLoss.asStateFlow()

    private val _replyingToMessage = MutableStateFlow<ChatMessage?>(null)
    val replyingToMessage: StateFlow<ChatMessage?> = _replyingToMessage.asStateFlow()

    private val _isForegroundServiceEnabled = MutableStateFlow(false)
    val isForegroundServiceEnabled: StateFlow<Boolean> = _isForegroundServiceEnabled.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = bluetoothService.connectionStatus
    val connectedDeviceName: StateFlow<String?> = bluetoothService.connectedDeviceName
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = bluetoothService.discoveredDevices
    val transportMode: StateFlow<TransportMode> = bluetoothService.transportMode

    // Flow from Room database
    val messages: StateFlow<List<ChatMessage>> = repository.allMessages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val logs: StateFlow<List<PacketLogEntry>> = protocolManager.logs
    val analytics: StateFlow<AnalyticsSnapshot> = protocolManager.analytics

    init {
        NotificationHelper.createNotificationChannels(application.applicationContext)
    }

    fun selectTab(tab: MainTab) {
        _selectedTab.value = tab
        if (tab == MainTab.CHAT) {
            markChatAsSeen()
        }
    }

    fun setTcpMode(enabled: Boolean) {
        _isTcpMode.value = enabled
    }

    fun setTcpRetryLimit(limit: Int) {
        _tcpRetryLimit.value = limit
        protocolManager.tcpRetryLimit = limit
    }

    fun setReplyingTo(message: ChatMessage?) {
        _replyingToMessage.value = message
    }

    fun setTransportMode(mode: TransportMode) {
        bluetoothService.setTransportMode(mode)
    }

    fun setSimulatedPacketLoss(percent: Int) {
        _simulatedPacketLoss.value = percent
        bluetoothService.simulatedPacketDropRatePercent = percent
    }

    fun updatePassphrase(newPhrase: String) {
        _passphrase.value = newPhrase
        AesSecurity.currentPassphrase = newPhrase
    }

    fun toggleForegroundService(enable: Boolean) {
        _isForegroundServiceEnabled.value = enable
        val context = getApplication<Application>().applicationContext
        if (enable) {
            val name = connectedDeviceName.value ?: "ESP32 Bridge"
            BluetoothForegroundService.start(context, "Active: $name • NRF24 Ch 115")
        } else {
            BluetoothForegroundService.stop(context)
        }
    }

    fun sendTextMessage(text: String, headerType: Int? = null) {
        if (text.isBlank()) return
        val reply = _replyingToMessage.value
        val targetType = headerType ?: (if (_isTcpMode.value) RadioPacket.TYPE_TCP_TEXT else RadioPacket.TYPE_UDP_TEXT)
        protocolManager.sendTextMessage(
            text = text.trim(),
            headerType = targetType,
            replyToId = reply?.id,
            replyToText = reply?.textContent ?: if (reply?.isImage == true) "Image" else null
        )
        _replyingToMessage.value = null
    }

    fun sendFile(fileBytes: ByteArray, fileName: String, isImage: Boolean) {
        protocolManager.sendFile(fileBytes, fileName, isImage)
    }

    fun deleteMessage(message: ChatMessage) {
        protocolManager.sendDeleteMessage(message)
    }

    fun editMessage(message: ChatMessage, newText: String) {
        if (newText.isNotBlank()) {
            protocolManager.sendEditMessage(message, newText.trim())
        }
    }

    fun markChatAsSeen() {
        protocolManager.markChatAsSeen()
    }

    fun connectToDevice(address: String, name: String) {
        bluetoothService.connectToDevice(address, name)
    }

    fun connectQuickNode(nodeName: String) {
        bluetoothService.connectToNodeByName(nodeName)
    }

    fun disconnect() {
        bluetoothService.disconnect()
    }

    fun refreshDevices() {
        bluetoothService.refreshPairedDevices()
    }

    fun clearLogs() {
        protocolManager.clearLogs()
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    /**
     * Injects a simulated peer incoming message from Node 2 (with GZIP + AES)
     */
    fun simulateIncomingPeerMessage(customText: String? = null) {
        viewModelScope.launch {
            val text = customText ?: "Hello from Node 2! NRF24 signal RSSI optimal on Channel 115."
            val plainBytes = text.toByteArray(Charsets.UTF_8)
            val gzipped = MediaCompressor.gzipCompress(plainBytes)
            val encryptedBytes = AesSecurity.encrypt(gzipped)

            val totalEncryptedLen = encryptedBytes.size
            val stream = ByteArray(4 + totalEncryptedLen)
            stream[0] = ((totalEncryptedLen shr 24) and 0xFF).toByte()
            stream[1] = ((totalEncryptedLen shr 16) and 0xFF).toByte()
            stream[2] = ((totalEncryptedLen shr 8) and 0xFF).toByte()
            stream[3] = (totalEncryptedLen and 0xFF).toByte()
            System.arraycopy(encryptedBytes, 0, stream, 4, totalEncryptedLen)

            val totalChunks = (stream.size + RadioPacket.MAX_PAYLOAD_SIZE - 1) / RadioPacket.MAX_PAYLOAD_SIZE
            val msgId = (10..240).random()

            for (i in 0 until totalChunks) {
                val start = i * RadioPacket.MAX_PAYLOAD_SIZE
                val end = minOf(start + RadioPacket.MAX_PAYLOAD_SIZE, stream.size)
                val chunkData = stream.sliceArray(start until end)

                val packet = RadioPacket(
                    type = RadioPacket.TYPE_TCP_TEXT,
                    messageId = msgId,
                    chunkIndex = i,
                    totalChunks = totalChunks,
                    payload = chunkData
                )
                bluetoothService.injectSimulatedPacket(packet)
            }
        }
    }
}
