package com.example.data.protocol

import android.content.Context
import com.example.data.bluetooth.BluetoothService
import com.example.data.crypto.AesSecurity
import com.example.data.db.ChatRepository
import com.example.data.model.*
import com.example.service.BluetoothForegroundService
import com.example.util.MediaCompressor
import com.example.util.MediaTransferManager
import com.example.util.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RadioProtocolManager(
    private val context: Context,
    private val bluetoothService: BluetoothService,
    private val repository: ChatRepository
) {
    companion object {
        const val ACK_TIMEOUT_MS = 500L
        const val DEFAULT_RETRY_LIMIT = 10

        fun prepareFramedStream(text: String): ByteArray {
            val plainBytes = text.toByteArray(Charsets.UTF_8)
            val gzipped = MediaCompressor.gzipCompress(plainBytes)
            val encrypted = AesSecurity.encrypt(gzipped)
            val totalEncryptedLen = encrypted.size
            val stream = ByteArray(4 + totalEncryptedLen)
            stream[0] = ((totalEncryptedLen shr 24) and 0xFF).toByte()
            stream[1] = ((totalEncryptedLen shr 16) and 0xFF).toByte()
            stream[2] = ((totalEncryptedLen shr 8) and 0xFF).toByte()
            stream[3] = (totalEncryptedLen and 0xFF).toByte()
            System.arraycopy(encrypted, 0, stream, 4, totalEncryptedLen)
            return stream
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val nextMessageId = AtomicInteger(1)

    val mediaTransferManager = MediaTransferManager(context)

    // Configurable TCP retry limit: 10, 50, 100, 1000, or Int.MAX_VALUE
    var tcpRetryLimit: Int = DEFAULT_RETRY_LIMIT

    // Log buffer for terminal screen
    private val _logs = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val logs: StateFlow<List<PacketLogEntry>> = _logs

    // Analytics state
    private val _analytics = MutableStateFlow(AnalyticsSnapshot())
    val analytics: StateFlow<AnalyticsSnapshot> = _analytics

    private var totalSentCount = 0
    private var totalReceivedCount = 0
    private var totalFailedCount = 0
    private var totalRetriedCount = 0
    private var totalBytesTransferred = 0L
    private var totalBytesSavedGzip = 0L

    private val minuteBuckets = ConcurrentHashMap<Int, IntArray>()

    // Pending ACK waiter: key = (messageId shl 16) or chunkIndex -> CompletableDeferred<Unit>
    private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    // In-progress reassembly for incoming text messages: messageId -> Map<chunkIndex, payloadBytes>
    private val incomingChunks = ConcurrentHashMap<Int, ConcurrentHashMap<Int, ByteArray>>()

    // Active Selective Repeat outgoing packets: messageId -> Map<chunkIndex, RadioPacket>
    private val activeOutgoingMediaPackets = ConcurrentHashMap<Int, Map<Int, RadioPacket>>()

    init {
        // Collect incoming packets from Foreground Service (or fallback bluetoothService)
        scope.launch {
            BluetoothForegroundService.incomingPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }
        scope.launch {
            bluetoothService.incomingPackets.collect { packet ->
                if (BluetoothForegroundService.instance == null) {
                    handleIncomingPacket(packet)
                }
            }
        }

        // Collect outgoing packets for terminal logging & analytics
        scope.launch {
            BluetoothForegroundService.outgoingPackets.collect { packet ->
                logOutgoingPacket(packet)
            }
        }
        scope.launch {
            bluetoothService.outgoingPackets.collect { packet ->
                if (BluetoothForegroundService.instance == null) {
                    logOutgoingPacket(packet)
                }
            }
        }

        // Periodic analytics refresh
        scope.launch {
            while (isActive) {
                delay(2000)
                refreshAnalyticsSnapshot()
            }
        }
    }

    private fun logOutgoingPacket(packet: RadioPacket) {
        val isAck = packet.type == RadioPacket.TYPE_ACK_DELIVERED ||
                packet.type == RadioPacket.TYPE_ACK_SEEN ||
                packet.type == RadioPacket.TYPE_HEARTBEAT_PONG ||
                packet.type == RadioPacket.TYPE_NACK
        addLog(
            direction = PacketLogEntry.Direction.TX,
            typeName = RadioPacket.typeName(packet.type),
            messageId = packet.messageId,
            chunkIndex = packet.chunkIndex,
            totalChunks = packet.totalChunks,
            hexDump = packet.toHexString(),
            isAck = isAck
        )
        if (!isAck && packet.type != RadioPacket.TYPE_HEARTBEAT_PING) {
            recordMetric(sent = 1, recv = 0, failed = 0, retries = 0, bytes = RadioPacket.PACKET_SIZE.toLong())
        }
    }

    private fun currentMinuteBucketKey(): Int {
        val now = System.currentTimeMillis()
        return ((now / 60000) % 60).toInt()
    }

    @Synchronized
    private fun recordMetric(sent: Int, recv: Int, failed: Int, retries: Int, bytes: Long = 0, gzipSaved: Long = 0) {
        totalSentCount += sent
        totalReceivedCount += recv
        totalFailedCount += failed
        totalRetriedCount += retries
        totalBytesTransferred += bytes
        totalBytesSavedGzip += gzipSaved

        val bucketKey = currentMinuteBucketKey()
        val counts = minuteBuckets.getOrPut(bucketKey) { IntArray(3) }
        counts[0] += sent
        counts[1] += recv
        counts[2] += failed

        refreshAnalyticsSnapshot()
    }

    private fun refreshAnalyticsSnapshot() {
        val currentKey = currentMinuteBucketKey()
        val points = mutableListOf<AnalyticsPoint>()
        for (i in 14 downTo 0) {
            val key = (currentKey - i * 4 + 60) % 60
            val bucket = minuteBuckets[key]
            points.add(
                AnalyticsPoint(
                    minuteOffset = (14 - i) * 4,
                    sentCount = bucket?.get(0) ?: 0,
                    receivedCount = bucket?.get(1) ?: 0,
                    failedCount = bucket?.get(2) ?: 0
                )
            )
        }

        _analytics.value = AnalyticsSnapshot(
            packetsSent = totalSentCount,
            packetsReceived = totalReceivedCount,
            packetsFailed = totalFailedCount,
            packetsRetried = totalRetriedCount,
            totalBytesTransferred = totalBytesTransferred,
            bytesSavedGzip = totalBytesSavedGzip,
            timePoints = points
        )
    }

    private fun addLog(
        direction: PacketLogEntry.Direction,
        typeName: String,
        messageId: Int,
        chunkIndex: Int,
        totalChunks: Int,
        hexDump: String,
        isAck: Boolean
    ) {
        val entry = PacketLogEntry(
            direction = direction,
            typeName = typeName,
            messageId = messageId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            hexDump = hexDump,
            isAck = isAck
        )
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > 200) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    suspend fun transmitPacket(packet: RadioPacket): Boolean {
        val service = BluetoothForegroundService.instance
        return if (service != null) {
            service.sendPacket(packet)
        } else {
            bluetoothService.sendPacket(packet)
        }
    }

    /**
     * Protocol-agnostic incoming packet handler.
     */
    private suspend fun handleIncomingPacket(packet: RadioPacket) {
        val isAck = packet.type == RadioPacket.TYPE_ACK_DELIVERED ||
                packet.type == RadioPacket.TYPE_ACK_SEEN ||
                packet.type == RadioPacket.TYPE_HEARTBEAT_PONG ||
                packet.type == RadioPacket.TYPE_NACK

        addLog(
            direction = PacketLogEntry.Direction.RX,
            typeName = RadioPacket.typeName(packet.type),
            messageId = packet.messageId,
            chunkIndex = packet.chunkIndex,
            totalChunks = packet.totalChunks,
            hexDump = packet.toHexString(),
            isAck = isAck
        )

        if (!isAck && packet.type != RadioPacket.TYPE_HEARTBEAT_PING) {
            recordMetric(sent = 0, recv = 1, failed = 0, retries = 0, bytes = RadioPacket.PACKET_SIZE.toLong())
        }

        when (packet.type) {
            // Type 3: ACK Delivered (Double Tick ✓✓)
            RadioPacket.TYPE_ACK_DELIVERED -> {
                val key = ackKey(packet.messageId, packet.chunkIndex)
                pendingAcks[key]?.complete(Unit)

                // Complete whole file transfer if it's the final chunk or whole-file ACK
                if (packet.chunkIndex == packet.totalChunks - 1 || packet.chunkIndex == 0) {
                    repository.updateStatusByByteId(packet.messageId, MessageStatus.DELIVERED)
                    activeOutgoingMediaPackets.remove(packet.messageId)
                    mediaTransferManager.updateProgress(packet.messageId, packet.totalChunks, packet.totalChunks, isSender = true, isComplete = true)
                    NotificationHelper.showMessageDeliveredNotification(context, "Radio Message #${packet.messageId}")
                }
            }

            // Type 4: ACK Seen (Blue Ticks ✓✓)
            RadioPacket.TYPE_ACK_SEEN -> {
                repository.updateStatusByByteId(packet.messageId, MessageStatus.SEEN)
            }

            // Type 11: NACK (Selective Repeat: Resend only missing chunks requested by receiver)
            RadioPacket.TYPE_NACK -> {
                val missingIndices = PacketParser.parseNackMissingIndices(packet)
                val storedMap = activeOutgoingMediaPackets[packet.messageId]
                if (storedMap != null) {
                    scope.launch {
                        for (idx in missingIndices) {
                            val resendPacket = storedMap[idx]
                            if (resendPacket != null) {
                                transmitPacket(resendPacket)
                                delay(MediaTransferManager.PACKET_BLAST_DELAY_MS)
                            }
                        }
                    }
                }
            }

            // Type 2: File/Image Chunk (Selective Repeat ARQ Reassembly)
            RadioPacket.TYPE_FILE_CHUNK -> {
                mediaTransferManager.handleIncomingChunk(
                    packet = packet,
                    onSendNack = { nackPacket ->
                        scope.launch { transmitPacket(nackPacket) }
                    },
                    onComplete = { filePath, rawBytes ->
                        scope.launch {
                            // Immediately send Type 3 ACK to sender!
                            val ack = RadioPacket(
                                type = RadioPacket.TYPE_ACK_DELIVERED,
                                messageId = packet.messageId,
                                chunkIndex = packet.totalChunks - 1,
                                totalChunks = packet.totalChunks,
                                payload = ByteArray(RadioPacket.MAX_PAYLOAD_SIZE)
                            )
                            transmitPacket(ack)

                            val chatMsg = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                messageIdByte = packet.messageId,
                                sender = SenderType.PEER,
                                type = RadioPacket.TYPE_FILE_CHUNK,
                                textContent = "Image (${rawBytes.size} bytes)",
                                fileBytes = rawBytes,
                                filePath = filePath,
                                fileName = "radio_img_${packet.messageId}.jpg",
                                isImage = true,
                                totalChunks = packet.totalChunks,
                                currentChunk = packet.totalChunks,
                                status = MessageStatus.DELIVERED
                            )
                            repository.insertMessage(chatMsg)
                            com.example.util.NotificationHandler.handleIncomingNotification(
                                context,
                                "ESP32 Node 2",
                                "Sent an image (${rawBytes.size} bytes)",
                                RadioPacket.TYPE_FILE_CHUNK
                            )
                        }
                    }
                )
            }

            // Type 5: Remote Delete Message Command
            RadioPacket.TYPE_CMD_DELETE -> {
                val targetMsgId = packet.messageId
                val existing = repository.getLatestOutgoingByByteId(targetMsgId)
                if (existing != null) {
                    repository.markAsDeleted(existing.id)
                }
            }

            // Type 6: Remote Edit Message Command
            RadioPacket.TYPE_CMD_EDIT -> {
                handleChunkAssembly(packet, isEdit = true)
            }

            // Type 0, 1, 7, 8: Text Messages
            RadioPacket.TYPE_UDP_TEXT,
            RadioPacket.TYPE_TCP_TEXT,
            RadioPacket.TYPE_SILENT,
            RadioPacket.TYPE_CRITICAL_ALARM -> {
                // If TCP or priority text, send Type 3 (Delivered ACK) back!
                if (packet.type != RadioPacket.TYPE_UDP_TEXT) {
                    val ackPacket = RadioPacket.buildAckDelivered(packet)
                    transmitPacket(ackPacket)
                }
                handleChunkAssembly(packet, isEdit = false)
            }
        }
    }

    private suspend fun handleChunkAssembly(packet: RadioPacket, isEdit: Boolean) {
        val msgId = packet.messageId
        val chunksMap = incomingChunks.getOrPut(msgId) { ConcurrentHashMap() }
        chunksMap[packet.chunkIndex] = packet.payload

        if (chunksMap.size == packet.totalChunks) {
            reassembleAndProcessTextMessage(msgId, packet.type, packet.totalChunks, chunksMap, isEdit)
            incomingChunks.remove(msgId)
        }
    }

    private suspend fun reassembleAndProcessTextMessage(
        messageId: Int,
        type: Int,
        totalChunks: Int,
        chunksMap: Map<Int, ByteArray>,
        isEdit: Boolean
    ) {
        try {
            val totalBytes = totalChunks * RadioPacket.MAX_PAYLOAD_SIZE
            val combined = ByteArray(totalBytes)
            var offset = 0
            for (i in 0 until totalChunks) {
                val chunk = chunksMap[i] ?: ByteArray(RadioPacket.MAX_PAYLOAD_SIZE)
                System.arraycopy(chunk, 0, combined, offset, RadioPacket.MAX_PAYLOAD_SIZE)
                offset += RadioPacket.MAX_PAYLOAD_SIZE
            }

            if (combined.size < 4) return
            val totalLen = ((combined[0].toInt() and 0xFF) shl 24) or
                    ((combined[1].toInt() and 0xFF) shl 16) or
                    ((combined[2].toInt() and 0xFF) shl 8) or
                    (combined[3].toInt() and 0xFF)

            if (totalLen <= 0 || totalLen > combined.size - 4) return
            val encryptedBytes = combined.sliceArray(4 until (4 + totalLen))

            val decryptedRaw = AesSecurity.decrypt(encryptedBytes) ?: return
            val decompressedBytes = MediaCompressor.gzipDecompress(decryptedRaw)

            if (isEdit) {
                val editedText = String(decompressedBytes, Charsets.UTF_8)
                val targetMsg = repository.getLatestOutgoingByByteId(messageId)
                if (targetMsg != null) {
                    repository.updateTextContent(targetMsg.id, editedText)
                }
                return
            }

            val text = String(decompressedBytes, Charsets.UTF_8)
            val chatMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                messageIdByte = messageId,
                sender = SenderType.PEER,
                type = type,
                textContent = text,
                totalChunks = totalChunks,
                currentChunk = totalChunks,
                status = MessageStatus.DELIVERED
            )

            repository.insertMessage(chatMsg)
            com.example.util.NotificationHandler.handleIncomingNotification(
                context,
                "ESP32 Node 2",
                text,
                type
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun markChatAsSeen() {
        scope.launch {
            com.example.util.NotificationHandler.stopAlarm()
            val unseen = repository.getUnseenIncomingMessages()
            for (msg in unseen) {
                val seenPacket = RadioPacket.buildAckSeen(msg.messageIdByte)
                transmitPacket(seenPacket)
            }
            repository.markAllIncomingAsSeen()
        }
    }

    /**
     * Sends a text message with GZIP compression, AES-256, chunking, and TCP ACK loop.
     */
    fun sendTextMessage(
        text: String,
        headerType: Int = RadioPacket.TYPE_TCP_TEXT,
        replyToId: String? = null,
        replyToText: String? = null
    ) {
        scope.launch {
            val stream = prepareFramedStream(text)
            val totalChunks = (stream.size + RadioPacket.MAX_PAYLOAD_SIZE - 1) / RadioPacket.MAX_PAYLOAD_SIZE
            val msgIdByte = nextMessageId.getAndIncrement() and 0xFF
            val uuid = UUID.randomUUID().toString()

            val chatMsg = ChatMessage(
                id = uuid,
                messageIdByte = msgIdByte,
                sender = SenderType.ME,
                type = headerType,
                textContent = text,
                totalChunks = totalChunks,
                currentChunk = 0,
                retriesCurrentChunk = 0,
                status = MessageStatus.SENDING,
                replyToId = replyToId,
                replyToText = replyToText
            )

            repository.insertMessage(chatMsg)

            var allSucceeded = true

            for (chunkIdx in 0 until totalChunks) {
                val start = chunkIdx * RadioPacket.MAX_PAYLOAD_SIZE
                val end = minOf(start + RadioPacket.MAX_PAYLOAD_SIZE, stream.size)
                val chunkPayload = stream.sliceArray(start until end)

                val packet = RadioPacket(
                    type = headerType,
                    messageId = msgIdByte,
                    chunkIndex = chunkIdx,
                    totalChunks = totalChunks,
                    payload = chunkPayload
                )

                if (headerType == RadioPacket.TYPE_UDP_TEXT) {
                    transmitPacket(packet)
                    delay(25)
                    repository.updateProgress(uuid, chunkIdx + 1, 0, MessageStatus.SENT_UNACKED)
                } else {
                    val key = ackKey(msgIdByte, chunkIdx)
                    var chunkAcked = false
                    var retries = 0

                    while (!chunkAcked && retries <= tcpRetryLimit && isActive) {
                        val deferred = CompletableDeferred<Unit>()
                        pendingAcks[key] = deferred

                        transmitPacket(packet)

                        // Marked as SENT_UNACKED (1 tick: sent to local ESP32)
                        if (chunkIdx == 0 && retries == 0) {
                            repository.updateStatus(uuid, MessageStatus.SENT_UNACKED)
                        }

                        try {
                            withTimeout(ACK_TIMEOUT_MS) {
                                deferred.await()
                            }
                            chunkAcked = true
                            pendingAcks.remove(key)
                        } catch (e: TimeoutCancellationException) {
                            retries++
                            pendingAcks.remove(key)
                            recordMetric(sent = 0, recv = 0, failed = 0, retries = 1)
                            repository.updateProgress(uuid, chunkIdx, retries, MessageStatus.SENDING)
                        }
                    }

                    if (!chunkAcked) {
                        allSucceeded = false
                        recordMetric(sent = 0, recv = 0, failed = 1, retries = 0)
                        break
                    } else {
                        repository.updateProgress(uuid, chunkIdx + 1, 0, MessageStatus.SENDING)
                    }
                }
            }

            val finalStatus = when {
                !allSucceeded -> MessageStatus.FAILED
                headerType == RadioPacket.TYPE_UDP_TEXT -> MessageStatus.SENT_UNACKED
                else -> MessageStatus.DELIVERED
            }
            repository.updateStatus(uuid, finalStatus)
        }
    }

    /**
     * Sends an image using Selective Repeat ARQ:
     * Blasts ALL chunks sequentially, saves JPG in cacheDir for Coil, and awaits NACK/ACK.
     */
    fun sendImageSelectiveRepeat(
        compressedBytes: ByteArray,
        quality: Int
    ) {
        scope.launch {
            val msgIdByte = nextMessageId.getAndIncrement() and 0xFF
            val uuid = UUID.randomUUID().toString()

            // Save JPG to local cacheDir for instant Coil display in UI
            val cachedPath = MediaTransferManager.saveImageToCache(context, compressedBytes, msgIdByte)

            val (packets, gzipSaved) = mediaTransferManager.prepareSelectiveRepeatPackets(
                rawBytes = compressedBytes,
                messageId = msgIdByte,
                headerType = RadioPacket.TYPE_FILE_CHUNK
            )

            val totalChunks = packets.size
            val packetMap = packets.associateBy { it.chunkIndex }
            activeOutgoingMediaPackets[msgIdByte] = packetMap

            val chatMsg = ChatMessage(
                id = uuid,
                messageIdByte = msgIdByte,
                sender = SenderType.ME,
                type = RadioPacket.TYPE_FILE_CHUNK,
                textContent = "Image (${compressedBytes.size} bytes • Q$quality)",
                fileBytes = compressedBytes,
                filePath = cachedPath,
                fileName = "radio_img_$msgIdByte.jpg",
                isImage = true,
                totalChunks = totalChunks,
                currentChunk = 0,
                status = MessageStatus.SENDING
            )
            repository.insertMessage(chatMsg)
            recordMetric(sent = 0, recv = 0, failed = 0, retries = 0, gzipSaved = gzipSaved)

            // Blast all chunks sequentially without waiting for individual ACKs
            for ((idx, packet) in packets.withIndex()) {
                transmitPacket(packet)
                mediaTransferManager.updateProgress(msgIdByte, totalChunks, idx + 1, isSender = true)
                delay(MediaTransferManager.PACKET_BLAST_DELAY_MS)
            }

            // Once blasted to local ESP32, mark as SENT_UNACKED (1 tick)
            repository.updateStatus(uuid, MessageStatus.SENT_UNACKED)
        }
    }

    fun sendDeleteMessage(message: ChatMessage) {
        scope.launch {
            repository.markAsDeleted(message.id)
            val packet = RadioPacket(
                type = RadioPacket.TYPE_CMD_DELETE,
                messageId = message.messageIdByte,
                chunkIndex = 0,
                totalChunks = 1,
                payload = message.id.take(26).toByteArray(Charsets.UTF_8)
            )
            transmitPacket(packet)
        }
    }

    fun sendEditMessage(message: ChatMessage, newText: String) {
        scope.launch {
            repository.updateTextContent(message.id, newText)
            val stream = prepareFramedStream(newText)
            val totalChunks = (stream.size + RadioPacket.MAX_PAYLOAD_SIZE - 1) / RadioPacket.MAX_PAYLOAD_SIZE

            for (i in 0 until totalChunks) {
                val start = i * RadioPacket.MAX_PAYLOAD_SIZE
                val end = minOf(start + RadioPacket.MAX_PAYLOAD_SIZE, stream.size)
                val chunkData = stream.sliceArray(start until end)

                val packet = RadioPacket(
                    type = RadioPacket.TYPE_CMD_EDIT,
                    messageId = message.messageIdByte,
                    chunkIndex = i,
                    totalChunks = totalChunks,
                    payload = chunkData
                )
                transmitPacket(packet)
            }
        }
    }

    private fun ackKey(messageId: Int, chunkIndex: Int): Int {
        return (messageId shl 16) or (chunkIndex and 0xFFFF)
    }
}
