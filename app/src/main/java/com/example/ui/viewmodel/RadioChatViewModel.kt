package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppSettings
import com.example.data.SettingsDataStore
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
import com.example.util.MediaTransferManager
import com.example.util.NotificationHelper
import com.example.util.PreSendMetrics
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class MainTab {
    CHAT,
    TOPOLOGY,
    ANALYTICS,
    SETTINGS
}

class RadioChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    val repository = ChatRepository(database.chatMessageDao())
    val settingsDataStore = SettingsDataStore.getInstance(application)

    val bluetoothService = BluetoothService(application.applicationContext)
    val protocolManager = RadioProtocolManager(application.applicationContext, bluetoothService, repository)

    private val _selectedTab = MutableStateFlow(MainTab.CHAT)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _isTcpMode = MutableStateFlow(true)
    val isTcpMode: StateFlow<Boolean> = _isTcpMode.asStateFlow()

    private val _replyingToMessage = MutableStateFlow<ChatMessage?>(null)
    val replyingToMessage: StateFlow<ChatMessage?> = _replyingToMessage.asStateFlow()

    // Persistent Settings from Preferences DataStore
    val appSettings: StateFlow<AppSettings> = settingsDataStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    // Reactive states from Foreground Service (with fallback to local BluetoothService)
    val connectionStatus: StateFlow<ConnectionStatus> = BluetoothForegroundService.connectionStatus
    val connectedDeviceName: StateFlow<String?> = BluetoothForegroundService.connectedDeviceName
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = BluetoothForegroundService.discoveredDevices
    val transportMode: StateFlow<TransportMode> = BluetoothForegroundService.transportMode
    val topologyState: StateFlow<TopologyState> = BluetoothForegroundService.topologyState

    // Flow from Room database
    val messages: StateFlow<List<ChatMessage>> = repository.allMessages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val logs: StateFlow<List<PacketLogEntry>> = protocolManager.logs
    val analytics: StateFlow<AnalyticsSnapshot> = protocolManager.analytics
    val activeTransfers = protocolManager.mediaTransferManager.activeTransfers

    init {
        NotificationHelper.createNotificationChannels(application.applicationContext)

        // Sync DataStore values with memory caches
        viewModelScope.launch {
            appSettings.collect { settings ->
                AesSecurity.currentPassphrase = settings.passphrase
                protocolManager.tcpRetryLimit = settings.tcpRetryLimit
                bluetoothService.simulatedPacketDropRatePercent = settings.simulatedPacketLoss
            }
        }
    }

    fun startBluetoothServiceIfPermitted(context: android.content.Context) {
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                BluetoothForegroundService.start(context)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isBluetoothHardwareSupported(): Boolean {
        return android.bluetooth.BluetoothAdapter.getDefaultAdapter() != null
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
        viewModelScope.launch {
            settingsDataStore.updateTcpRetryLimit(limit)
        }
    }

    fun setPingInterval(seconds: Int) {
        viewModelScope.launch {
            settingsDataStore.updatePingInterval(seconds)
        }
    }

    fun setResendInterval(milliseconds: Long) {
        viewModelScope.launch {
            settingsDataStore.updateResendInterval(milliseconds)
        }
    }

    fun setReplyingTo(message: ChatMessage?) {
        _replyingToMessage.value = message
    }

    fun setTransportMode(mode: TransportMode) {
        BluetoothForegroundService.instance?.setTransportMode(mode) ?: bluetoothService.setTransportMode(mode)
    }

    fun setSimulatedPacketLoss(percent: Int) {
        viewModelScope.launch {
            settingsDataStore.updateSimulatedPacketLoss(percent)
        }
    }

    fun updatePassphrase(newPhrase: String) {
        viewModelScope.launch {
            settingsDataStore.updatePassphrase(newPhrase)
        }
    }

    fun updateTargetNode(nodeName: String) {
        viewModelScope.launch {
            settingsDataStore.updateTargetNode(nodeName)
        }
    }

    fun updateDefaultImageQuality(quality: Int) {
        viewModelScope.launch {
            settingsDataStore.updateDefaultImageQuality(quality)
        }
    }

    fun triggerImmediatePing() {
        BluetoothForegroundService.triggerPingNow()
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

    /**
     * Calculates image preview metrics dynamically for the Quality Configurator BottomSheet.
     */
    fun calculateImagePreSendMetrics(bytes: ByteArray, quality: Int): PreSendMetrics? {
        return MediaTransferManager.calculatePreSendMetrics(bytes, quality)
    }

    /**
     * Sends image via Selective Repeat ARQ.
     */
    fun sendImageSelectiveRepeat(compressedBytes: ByteArray, quality: Int) {
        protocolManager.sendImageSelectiveRepeat(compressedBytes, quality)
    }

    fun sendFile(fileBytes: ByteArray, fileName: String, isImage: Boolean) {
        if (isImage) {
            val metrics = MediaTransferManager.calculatePreSendMetrics(fileBytes, appSettings.value.defaultImageQuality)
            val bytes = metrics?.compressedBytes ?: fileBytes
            protocolManager.sendImageSelectiveRepeat(bytes, appSettings.value.defaultImageQuality)
        } else {
            // Legacy/generic binary file
            val metrics = MediaTransferManager.calculatePreSendMetrics(fileBytes, 50)
            val bytes = metrics?.compressedBytes ?: fileBytes
            protocolManager.sendImageSelectiveRepeat(bytes, 50)
        }
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
        BluetoothForegroundService.instance?.connectToBluetoothDevice(address, name) ?: bluetoothService.connectToDevice(address, name)
    }

    fun connectQuickNode(nodeName: String) {
        BluetoothForegroundService.instance?.let { service ->
            service.refreshPairedDevices()
            val match = BluetoothForegroundService.discoveredDevices.value.find { it.name.equals(nodeName, ignoreCase = true) }
            if (match != null) {
                service.connectToBluetoothDevice(match.address, match.name)
            } else {
                service.connectToBluetoothDevice(nodeName, nodeName)
            }
        } ?: bluetoothService.connectToNodeByName(nodeName)
    }

    fun disconnect() {
        BluetoothForegroundService.instance?.disconnectSocket() ?: bluetoothService.disconnect()
    }

    fun refreshDevices() {
        BluetoothForegroundService.instance?.refreshPairedDevices() ?: bluetoothService.refreshPairedDevices()
    }

    fun clearLogs() {
        protocolManager.clearLogs()
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }
}
