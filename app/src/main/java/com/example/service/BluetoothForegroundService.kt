package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.AppSettings
import com.example.data.SettingsDataStore
import com.example.data.bluetooth.ConnectionStatus
import com.example.data.bluetooth.DiscoveredDevice
import com.example.data.bluetooth.TransportMode
import com.example.data.db.AppDatabase
import com.example.data.db.ChatRepository
import com.example.data.model.MessageStatus
import com.example.data.model.RadioPacket
import com.example.data.model.TopologyState
import com.example.data.protocol.PacketParser
import com.example.data.protocol.RadioProtocolManager
import com.example.util.NotificationHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class BluetoothForegroundService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothForegroundService = this@BluetoothForegroundService
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var retryLoopJob: Job? = null

    private lateinit var database: AppDatabase
    private lateinit var repository: ChatRepository
    private lateinit var settingsDataStore: SettingsDataStore

    private val pingSeq = AtomicInteger(1)

    // Companion object holds shared StateFlows so Compose UI can observe reactive state anywhere
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "channel_radio_bridge_mesh"

        const val ACTION_START = "com.example.service.START_BRIDGE"
        const val ACTION_STOP = "com.example.service.STOP_BRIDGE"
        const val ACTION_CONNECT = "com.example.service.CONNECT"
        const val ACTION_PING_NOW = "com.example.service.PING_NOW"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_NAME = "extra_name"

        // StateFlows for UI binding
        private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

        private val _connectedDeviceName = MutableStateFlow<String?>(null)
        val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

        private val _transportMode = MutableStateFlow(TransportMode.HARDWARE_BLUETOOTH)
        val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

        private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
        val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

        private val _topologyState = MutableStateFlow(TopologyState())
        val topologyState: StateFlow<TopologyState> = _topologyState.asStateFlow()

        private val _incomingPackets = MutableSharedFlow<RadioPacket>(extraBufferCapacity = 128)
        val incomingPackets: SharedFlow<RadioPacket> = _incomingPackets.asSharedFlow()

        private val _outgoingPackets = MutableSharedFlow<RadioPacket>(extraBufferCapacity = 128)
        val outgoingPackets: SharedFlow<RadioPacket> = _outgoingPackets.asSharedFlow()

        // Active service instance pointer
        @Volatile
        var instance: BluetoothForegroundService? = null
            private set

        fun start(context: Context) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                // Gracefully bypass service start on emulators / devices without Bluetooth hardware
                return
            }
            try {
                val intent = Intent(context, BluetoothForegroundService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: SecurityException) {
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, BluetoothForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: SecurityException) {
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun connect(context: Context, address: String, name: String) {
            try {
                val intent = Intent(context, BluetoothForegroundService::class.java).apply {
                    action = ACTION_CONNECT
                    putExtra(EXTRA_ADDRESS, address)
                    putExtra(EXTRA_NAME, name)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: SecurityException) {
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun triggerPingNow() {
            instance?.sendImmediatePing()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getDatabase(applicationContext)
        repository = ChatRepository(database.chatMessageDao())
        settingsDataStore = SettingsDataStore.getInstance(applicationContext)

        acquireWakeLock()
        createNotificationChannel()

        // Sync initial settings from DataStore
        serviceScope.launch {
            settingsDataStore.settingsFlow.collect { settings ->
                if (settings.transportMode == "VIRTUAL_SIMULATOR") {
                    _transportMode.value = TransportMode.VIRTUAL_SIMULATOR
                    if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                        _connectedDeviceName.value = "Virtual ESP_Node_1"
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        updateTopology {
                            copy(
                                localLinkActive = true,
                                radioLinkActive = true,
                                remoteLinkActive = true,
                                localNodeName = "Virtual ESP_Node_1",
                                remoteNodeName = "Virtual ESP_Node_2"
                            )
                        }
                    }
                }
            }
        }

        startHeartbeatLoop()
        startInfiniteRetryLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val name = intent.getStringExtra(EXTRA_NAME)
                if (address != null) {
                    connectToBluetoothDevice(address, name)
                }
            }
            ACTION_PING_NOW -> {
                sendImmediatePing()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildForegroundNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        heartbeatJob?.cancel()
        retryLoopJob?.cancel()
        readJob?.cancel()
        serviceScope.cancel()
        disconnectSocket()
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RadioBridge::MeshForegroundLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours max safeguard
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    // -------------------------------------------------------------
    // Bluetooth Connection Management & Stream IO
    // -------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        try {
            val list = mutableListOf<DiscoveredDevice>()
            bluetoothAdapter?.bondedDevices?.forEach { device ->
                list.add(
                    DiscoveredDevice(
                        name = device.name ?: "Unknown",
                        address = device.address,
                        isPaired = true
                    )
                )
            }
            _discoveredDevices.value = list
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToBluetoothDevice(deviceAddress: String, deviceName: String? = null) {
        if (_transportMode.value == TransportMode.VIRTUAL_SIMULATOR) {
            _connectedDeviceName.value = deviceName ?: "Virtual ESP_Node_1"
            _connectionStatus.value = ConnectionStatus.CONNECTED
            updateTopology {
                copy(
                    localLinkActive = true,
                    radioLinkActive = true,
                    remoteLinkActive = true,
                    localNodeName = deviceName ?: "Virtual ESP_Node_1"
                )
            }
            updateNotification()
            return
        }

        val adapter = bluetoothAdapter ?: return
        disconnectSocket()

        _connectionStatus.value = ConnectionStatus.CONNECTING
        _connectedDeviceName.value = deviceName ?: deviceAddress
        updateTopology { copy(localLinkActive = false, radioLinkActive = false, remoteLinkActive = false) }
        updateNotification()

        serviceScope.launch {
            try {
                adapter.cancelDiscovery()
                val device = adapter.getRemoteDevice(deviceAddress)
                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()

                socket = newSocket
                inputStream = newSocket.inputStream
                outputStream = newSocket.outputStream

                _connectionStatus.value = ConnectionStatus.CONNECTED
                _connectedDeviceName.value = device.name ?: deviceAddress

                updateTopology {
                    copy(
                        localLinkActive = true,
                        localNodeName = device.name ?: "ESP_Node_1"
                    )
                }
                updateNotification()

                startReadingPackets()
            } catch (e: SecurityException) {
                e.printStackTrace()
                disconnectSocket()
            } catch (e: Exception) {
                e.printStackTrace()
                disconnectSocket()
            }
        }
    }

    fun setTransportMode(mode: TransportMode) {
        disconnectSocket()
        _transportMode.value = mode
        serviceScope.launch {
            settingsDataStore.updateTransportMode(mode.name)
        }
        if (mode == TransportMode.VIRTUAL_SIMULATOR) {
            _connectedDeviceName.value = "Virtual ESP_Node_1"
            _connectionStatus.value = ConnectionStatus.CONNECTED
            updateTopology {
                copy(
                    localLinkActive = true,
                    radioLinkActive = true,
                    remoteLinkActive = true,
                    localNodeName = "Virtual ESP_Node_1",
                    remoteNodeName = "Virtual ESP_Node_2"
                )
            }
        }
        updateNotification()
    }

    private fun startReadingPackets() {
        readJob?.cancel()
        readJob = serviceScope.launch {
            val stream = inputStream ?: return@launch
            val buffer = ByteArray(RadioPacket.PACKET_SIZE)

            while (isActive) {
                try {
                    var bytesRead = 0
                    while (bytesRead < RadioPacket.PACKET_SIZE) {
                        val count = stream.read(buffer, bytesRead, RadioPacket.PACKET_SIZE - bytesRead)
                        if (count < 0) throw Exception("Stream closed")
                        bytesRead += count
                    }

                    val packetBytes = buffer.copyOf()
                    val packet = RadioPacket.fromBytes(packetBytes)
                    if (packet != null) {
                        handleIncomingServicePacket(packet)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        disconnectSocket()
                    }
                    break
                }
            }
        }
    }

    suspend fun sendPacket(packet: RadioPacket): Boolean {
        return withContext(Dispatchers.IO) {
            _outgoingPackets.emit(packet)

            if (_transportMode.value == TransportMode.VIRTUAL_SIMULATOR) {
                simulateVirtualEcho(packet)
                return@withContext true
            }

            try {
                val out = outputStream ?: return@withContext false
                val bytes = RadioPacket.toBytes(packet)
                out.write(bytes)
                out.flush()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun handleIncomingServicePacket(packet: RadioPacket) {
        serviceScope.launch {
            _incomingPackets.emit(packet)

            val now = System.currentTimeMillis()

            // Radio link is active because we received a packet from the radio bridge!
            updateTopology {
                copy(
                    radioLinkActive = true,
                    localLinkActive = true
                )
            }

            when (packet.type) {
                // Type 9: Peer sent Ping -> immediately respond with Type 10 Pong!
                RadioPacket.TYPE_HEARTBEAT_PING -> {
                    val pongPacket = RadioPacket.buildHeartbeatPong(packet)
                    sendPacket(pongPacket)
                }

                // Type 10: Received Pong -> Remote Link (Leg C) is confirmed alive!
                RadioPacket.TYPE_HEARTBEAT_PONG -> {
                    var sentTs = 0L
                    if (packet.payload.size >= 8) {
                        for (i in 0..7) {
                            sentTs = (sentTs shl 8) or (packet.payload[i].toLong() and 0xFF)
                        }
                    }
                    val latency = if (sentTs > 0) (now - sentTs).coerceAtLeast(1L) else 0L

                    updateTopology {
                        copy(
                            localLinkActive = true,
                            radioLinkActive = true,
                            remoteLinkActive = true,
                            lastPongReceivedTime = now,
                            roundTripLatencyMs = latency,
                            pongsReceived = pongsReceived + 1
                        )
                    }
                    updateNotification()
                }

                // Type 3: Delivered ACK -> Confirms both Radio Link and Remote Node
                RadioPacket.TYPE_ACK_DELIVERED -> {
                    updateTopology {
                        copy(
                            localLinkActive = true,
                            radioLinkActive = true
                        )
                    }
                }
            }
        }
    }

    private fun simulateVirtualEcho(packet: RadioPacket) {
        serviceScope.launch {
            delay((40..90).random().toLong())

            when (packet.type) {
                RadioPacket.TYPE_HEARTBEAT_PING -> {
                    val pong = RadioPacket.buildHeartbeatPong(packet)
                    _incomingPackets.emit(pong)
                    updateTopology {
                        copy(
                            localLinkActive = true,
                            radioLinkActive = true,
                            remoteLinkActive = true,
                            lastPongReceivedTime = System.currentTimeMillis(),
                            roundTripLatencyMs = (35..75).random().toLong(),
                            pongsReceived = pongsReceived + 1
                        )
                    }
                }
                RadioPacket.TYPE_TCP_TEXT,
                RadioPacket.TYPE_FILE_CHUNK,
                RadioPacket.TYPE_SILENT,
                RadioPacket.TYPE_CRITICAL_ALARM -> {
                    val ack = RadioPacket.buildAckDelivered(packet)
                    _incomingPackets.emit(ack)
                    if (packet.chunkIndex == packet.totalChunks - 1) {
                        delay(1200)
                        val seen = RadioPacket.buildAckSeen(packet.messageId)
                        _incomingPackets.emit(seen)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Heartbeat Loop (Requirement 1: Ping / Pong & 3-Leg Topology)
    // -------------------------------------------------------------

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                val settings = settingsDataStore.settingsFlow.first()
                val intervalSec = settings.pingIntervalSec.coerceAtLeast(2)
                delay(intervalSec * 1000L)

                if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                    sendImmediatePing()

                    // Check timeout for Remote Link (if no pong for 3*interval, remote phone is offline)
                    val now = System.currentTimeMillis()
                    val topology = _topologyState.value
                    if (topology.lastPongReceivedTime > 0 && (now - topology.lastPongReceivedTime) > (intervalSec * 3000L)) {
                        updateTopology { copy(remoteLinkActive = false) }
                        updateNotification()
                    }
                }
            }
        }
    }

    fun sendImmediatePing() {
        serviceScope.launch {
            val seq = pingSeq.getAndIncrement()
            val ping = RadioPacket.buildHeartbeatPing(seq)
            updateTopology {
                copy(
                    lastPingSentTime = System.currentTimeMillis(),
                    pingsSent = pingsSent + 1
                )
            }
            sendPacket(ping)
        }
    }

    // -------------------------------------------------------------
    // Infinite Retry Loop (Requirement 4: Accurate Delivery & Ticks)
    // -------------------------------------------------------------

    private fun startInfiniteRetryLoop() {
        retryLoopJob?.cancel()
        retryLoopJob = serviceScope.launch {
            while (isActive) {
                val settings = settingsDataStore.settingsFlow.first()
                val resendInterval = settings.resendIntervalMs.coerceAtLeast(500L)
                delay(resendInterval)

                // Only perform background auto-retry when retry limit is Infinite and connected
                if (settings.tcpRetryLimit == Int.MAX_VALUE && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                    val unacked = repository.getUnackedOutgoingMessages()
                    for (msg in unacked) {
                        if (!isActive) break
                        // Resend unacked TCP message chunks
                        if (msg.type != RadioPacket.TYPE_UDP_TEXT && msg.textContent != null) {
                            val stream = RadioProtocolManager.prepareFramedStream(msg.textContent)
                            val totalChunks = (stream.size + RadioPacket.MAX_PAYLOAD_SIZE - 1) / RadioPacket.MAX_PAYLOAD_SIZE

                            for (chunkIdx in 0 until totalChunks) {
                                val start = chunkIdx * RadioPacket.MAX_PAYLOAD_SIZE
                                val end = minOf(start + RadioPacket.MAX_PAYLOAD_SIZE, stream.size)
                                val chunkPayload = stream.sliceArray(start until end)

                                val packet = RadioPacket(
                                    type = msg.type,
                                    messageId = msg.messageIdByte,
                                    chunkIndex = chunkIdx,
                                    totalChunks = totalChunks,
                                    payload = chunkPayload
                                )
                                sendPacket(packet)
                                delay(25)
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Topology & Notification Helpers
    // -------------------------------------------------------------

    private inline fun updateTopology(transform: TopologyState.() -> TopologyState) {
        _topologyState.value = _topologyState.value.transform()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RadioBridge Mesh Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps NRF24 + ESP32 radio link alive and monitors heartbeat"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val topology = _topologyState.value
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (topology.isE2EConnected) {
            "E2E Connected • Latency: ${topology.roundTripLatencyMs}ms • Ch 115"
        } else {
            "${topology.statusSummary} • NRF24 Ch 115"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RadioBridge Mesh Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildForegroundNotification())
    }

    fun disconnectSocket() {
        readJob?.cancel()
        readJob = null
        try {
            outputStream?.close()
            inputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        socket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _connectedDeviceName.value = null
        updateTopology {
            copy(
                localLinkActive = false,
                radioLinkActive = false,
                remoteLinkActive = false
            )
        }
        updateNotification()
    }
}
