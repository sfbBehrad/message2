package com.example.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.data.model.RadioPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class ConnectionStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

enum class TransportMode {
    HARDWARE_BLUETOOTH,
    VIRTUAL_SIMULATOR
}

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val isPaired: Boolean
)

class BluetoothService(private val context: Context) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val DEFAULT_NODE_1_NAME = "ESP_Node_1"
        const val DEFAULT_NODE_2_NAME = "ESP_Node_2"
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val _transportMode = MutableStateFlow(TransportMode.VIRTUAL_SIMULATOR)
    val transportMode: StateFlow<TransportMode> = _transportMode

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices

    // Flow of incoming 32-byte RadioPackets
    private val _incomingPackets = MutableSharedFlow<RadioPacket>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<RadioPacket> = _incomingPackets

    // Raw outgoing packets for logging/events
    private val _outgoingPackets = MutableSharedFlow<RadioPacket>(extraBufferCapacity = 64)
    val outgoingPackets: SharedFlow<RadioPacket> = _outgoingPackets

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null

    // Virtual simulator settings
    var simulatedPacketDropRatePercent: Int = 0 // 0 to 100%

    init {
        // Default to Hardware if Bluetooth is available and enabled, otherwise Simulator
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
            _transportMode.value = TransportMode.HARDWARE_BLUETOOTH
        } else {
            _transportMode.value = TransportMode.VIRTUAL_SIMULATOR
        }
    }

    fun setTransportMode(mode: TransportMode) {
        disconnect()
        _transportMode.value = mode
        if (mode == TransportMode.VIRTUAL_SIMULATOR) {
            _connectedDeviceName.value = "Virtual ESP_Node_2 (NRF24)"
            _connectionStatus.value = ConnectionStatus.CONNECTED
        }
    }

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
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
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String, deviceName: String? = null) {
        if (_transportMode.value == TransportMode.VIRTUAL_SIMULATOR) {
            _connectedDeviceName.value = deviceName ?: "Virtual ESP_Node_2"
            _connectionStatus.value = ConnectionStatus.CONNECTED
            return
        }

        val adapter = bluetoothAdapter ?: return
        disconnect()

        _connectionStatus.value = ConnectionStatus.CONNECTING
        _connectedDeviceName.value = deviceName ?: deviceAddress

        ioScope.launch {
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

                startReadingPackets()
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
            }
        }
    }

    fun connectToNodeByName(targetName: String) {
        if (_transportMode.value == TransportMode.VIRTUAL_SIMULATOR) {
            _connectedDeviceName.value = targetName
            _connectionStatus.value = ConnectionStatus.CONNECTED
            return
        }

        refreshPairedDevices()
        val match = _discoveredDevices.value.find { it.name.equals(targetName, ignoreCase = true) }
        if (match != null) {
            connectToDevice(match.address, match.name)
        } else {
            // Check bonded directly
            try {
                val dev = bluetoothAdapter?.bondedDevices?.find { it.name?.equals(targetName, ignoreCase = true) == true }
                if (dev != null) {
                    connectToDevice(dev.address, dev.name)
                } else {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    private fun startReadingPackets() {
        readJob?.cancel()
        readJob = ioScope.launch {
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
                        _incomingPackets.emit(packet)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        disconnect()
                    }
                    break
                }
            }
        }
    }

    /**
     * Sends a strict 32-byte packet to Bluetooth serial stream.
     */
    suspend fun sendPacket(packet: RadioPacket): Boolean {
        return withContext(Dispatchers.IO) {
            _outgoingPackets.emit(packet)

            if (_transportMode.value == TransportMode.VIRTUAL_SIMULATOR) {
                simulateVirtualTransmission(packet)
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

    /**
     * Emulates the remote ESP32 Node 2 behavior over virtual RF.
     */
    private fun simulateVirtualTransmission(packet: RadioPacket) {
        ioScope.launch {
            // Check if dropped by simulated RF interference
            if (simulatedPacketDropRatePercent > 0) {
                val rand = (0..99).random()
                if (rand < simulatedPacketDropRatePercent) {
                    // Packet lost in RF! Sender will retry after 500ms.
                    return@launch
                }
            }

            // Radio propagation delay (60-120ms)
            delay((60..120).random().toLong())

            // If Type 1 (TCP text) or Type 2 (File chunk), peer immediately replies with Type 3 (Delivered ACK)
            if (packet.type == RadioPacket.TYPE_TCP_TEXT || packet.type == RadioPacket.TYPE_FILE_CHUNK) {
                val ack = RadioPacket.buildAckDelivered(packet)
                _incomingPackets.emit(ack)

                // If this was the final chunk, simulate peer opening the chat and sending Type 4 (Seen ACK) after 1.5s
                if (packet.chunkIndex == packet.totalChunks - 1) {
                    delay(1500)
                    val seenAck = RadioPacket.buildAckSeen(packet.messageId)
                    _incomingPackets.emit(seenAck)
                }
            }
        }
    }

    /**
     * Injects a packet as if received from radio (useful for testing peer messages in Simulator).
     */
    fun injectSimulatedPacket(packet: RadioPacket) {
        ioScope.launch {
            _incomingPackets.emit(packet)
        }
    }

    fun disconnect() {
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
    }
}
