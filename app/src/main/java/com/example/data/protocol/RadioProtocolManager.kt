package com.example.data.protocol

import android.content.Context
import com.example.data.bluetooth.BluetoothService
import com.example.data.crypto.AesSecurity
import com.example.data.db.ChatRepository
import com.example.data.model.*
import com.example.util.MediaCompressor
import com.example.util.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val nextMessageId = AtomicInteger(1)

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

    // 60-minute rolling window: minuteOfDay -> Triple(sent, recv, failed)
    private val minuteBuckets = ConcurrentHashMap<Int, IntArray>() // [0]=sent, [1]=recv, [2]=failed

    // Pending ACK waiter: key = (messageId shl 16) or chunkIndex -> CompletableDeferred<Unit>
    private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    // In-progress reassembly for incoming messages: messageId -> Map<chunkIndex, payloadBytes>
    private val incomingChunks = ConcurrentHashMap<Int, ConcurrentHashMap<Int, ByteArray>>()
    private val incomingMeta = ConcurrentHashMap<Int, Triple<Int, Int, String?>>() // messageId -> (type, totalChunks, optionalRefId)

    init {
        // Collect incoming packets from Bluetooth
        scope.launch {
            bluetoothService.incomingPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }

        // Collect outgoing packets for terminal logging & analytics
        scope.launch {
            bluetoothService.outgoingPackets.collect { packet ->
                val isAck = packet.type == RadioPacket.TYPE_ACK_DELIVERED || packet.type == RadioPacket.TYPE_ACK_SEEN
                addLog(
                    direction = PacketLogEntry.Direction.TX,
                    typeName = RadioPacket.typeName(packet.type),
                    messageId = packet.messageId,
                    chunkIndex = packet.chunkIndex,
                    totalChunks = packet.totalChunks,
                    hexDump = packet.toHexString(),
                    isAck = isAck
                )
                if (!isAck) {
                    recordMetric(sent = 1, recv = 0, failed = 0, retries = 0, bytes = RadioPacket.PACKET_SIZE.toLong())
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
        // Build 15-point window representing last hour
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

    /**
     * Protocol-agnostic incoming packet handler.
     */
    private suspend fun handleIncomingPacket(packet: RadioPacket) {
        val isAck = packet.type == RadioPacket.TYPE_ACK_DELIVERED || packet.type == RadioPacket.TYPE_ACK_SEEN
        addLog(
            direction = PacketLogEntry.Direction.RX,
            typeName = RadioPacket.typeName(packet.type),
            messageId = packet.messageId,
            chunkIndex = packet.chunkIndex,
            totalChunks = packet.totalChunks,
            hexDump = packet.toHexString(),
            isAck = isAck
        )

        if (!isAck) {
            recordMetric(sent = 0, recv = 1, failed = 0, retries = 0, bytes = RadioPacket.PACKET_SIZE.toLong())
        }

        when (packet.type) {
            // Type 3: ACK Delivered (Double Tick ✓✓)
            RadioPacket.TYPE_ACK_DELIVERED -> {
                val key = ackKey(packet.messageId, packet.chunkIndex)
                pendingAcks[key]?.complete(Unit)

                if (packet.chunkIndex == packet.totalChunks - 1) {
                    repository.updateStatusByByteId(packet.messageId, MessageStatus.DELIVERED)
                    NotificationHelper.showMessageDeliveredNotification(context, "Radio Message #${packet.messageId}")
                }
            }

            // Type 4: ACK Seen (Blue Ticks ✓✓)
            RadioPacket.TYPE_ACK_SEEN -> {
                repository.updateStatusByByteId(packet.messageId, MessageStatus.SEEN)
            }

            // Type 5: Remote Delete Message Command
            RadioPacket.TYPE_CMD_DELETE -> {
                val targetMsgId = packet.messageId
                val existing = repository.getLatestOutgoingByByteId(targetMsgId)
                if (existing != null) {
                    repository.markAsDeleted(existing.id)
                } else {
                    // Also check incoming with that byteId
                    val peerMsg = repository.allMessages // check by messageIdByte
                    scope.launch {
                        // find matching message
                    }
                }
            }

            // Type 6: Remote Edit Message Command
            RadioPacket.TYPE_CMD_EDIT -> {
                handleChunkAssembly(packet, isEdit = true)
            }

            // Type 0, 1, 2, 7, 8: Regular text/file/priority data
            RadioPacket.TYPE_UDP_TEXT,
            RadioPacket.TYPE_TCP_TEXT,
            RadioPacket.TYPE_FILE_CHUNK,
            RadioPacket.TYPE_SILENT,
            RadioPacket.TYPE_CRITICAL_ALARM -> {
                // If TCP text, File, Silent, or Alarm, IMMEDIATELY send Type 3 (Delivered ACK) back!
                if (packet.type != RadioPacket.TYPE_UDP_TEXT) {
                    val ackPacket = RadioPacket.buildAckDelivered(packet)
                    bluetoothService.sendPacket(ackPacket)
                }
                handleChunkAssembly(packet, isEdit = false)
            }
        }
    }

    private suspend fun handleChunkAssembly(packet: RadioPacket, isEdit: Boolean) {
        val msgId = packet.messageId
        val chunksMap = incomingChunks.getOrPut(msgId) { ConcurrentHashMap() }
        chunksMap[packet.chunkIndex] = packet.payload
        incomingMeta[msgId] = Triple(packet.type, packet.totalChunks, null)

        // Check if all chunks received
        if (chunksMap.size == packet.totalChunks) {
            reassembleAndProcessMessage(msgId, packet.type, packet.totalChunks, chunksMap, isEdit)
            incomingChunks.remove(msgId)
            incomingMeta.remove(msgId)
        }
    }

    private suspend fun reassembleAndProcessMessage(
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

            // AES-256 Decrypt
            val decryptedRaw = AesSecurity.decrypt(encryptedBytes) ?: return

            // GZIP Decompress
            val decompressedBytes = MediaCompressor.gzipDecompress(decryptedRaw)

            if (isEdit) {
                val editedText = String(decompressedBytes, Charsets.UTF_8)
                val targetMsg = repository.getLatestOutgoingByByteId(messageId)
                if (targetMsg != null) {
                    repository.updateTextContent(targetMsg.id, editedText)
                }
                return
            }

            // Create received chat message
            val isFile = type == RadioPacket.TYPE_FILE_CHUNK
            val isImg = if (isFile) isImageBytes(decompressedBytes) else false
            val text = if (!isFile) String(decompressedBytes, Charsets.UTF_8) else null

            val chatMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                messageIdByte = messageId,
                sender = SenderType.PEER,
                type = type,
                textContent = if (isFile) (if (isImg) "Image (${decompressedBytes.size} bytes)" else "File (${decompressedBytes.size} bytes)") else text,
                fileBytes = if (isFile) decompressedBytes else null,
                fileName = if (isFile) (if (isImg) "radio_img_$messageId.jpg" else "radio_file_$messageId.bin") else null,
                isImage = isImg,
                totalChunks = totalChunks,
                currentChunk = totalChunks,
                status = MessageStatus.DELIVERED
            )

            repository.insertMessage(chatMsg)

            // Trigger notification according to header priority (Silent vs Normal vs Emergency Alarm)
            val preview = chatMsg.textContent ?: (if (isImg) "Sent an image" else "Sent a file")
            com.example.util.NotificationHandler.handleIncomingNotification(context, "ESP32 Node 2", preview, type)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Automatically called when the user opens or views the chat screen.
     * Sends Type 4 (ACK Seen) back to peer for all unseen messages!
     */
    fun markChatAsSeen() {
        scope.launch {
            // Also stop any running emergency alarm
            com.example.util.NotificationHandler.stopAlarm()

            val unseen = repository.getUnseenIncomingMessages()
            for (msg in unseen) {
                val seenPacket = RadioPacket.buildAckSeen(msg.messageIdByte)
                bluetoothService.sendPacket(seenPacket)
            }
            repository.markAllIncomingAsSeen()
        }
    }

    /**
     * Sends a text message with GZIP compression, AES-256, chunking, and TCP ACK loop.
     * headerType: 1 for standard TCP, 0 for UDP, 7 for Silent, 8 for Critical Loud Alarm.
     */
    fun sendTextMessage(
        text: String,
        headerType: Int = RadioPacket.TYPE_TCP_TEXT,
        replyToId: String? = null,
        replyToText: String? = null
    ) {
        val plainBytes = text.toByteArray(Charsets.UTF_8)
        sendMessageInternal(
            rawBytes = plainBytes,
            type = headerType,
            textContent = text,
            isImage = false,
            fileName = null,
            replyToId = replyToId,
            replyToText = replyToText
        )
    }

    /**
     * Sends a file/image with Bitmap downscaling + GZIP compression + AES-256 + TCP ACK loop.
     */
    fun sendFile(fileBytes: ByteArray, fileName: String, isImage: Boolean) {
        val preparedBytes = if (isImage) {
            MediaCompressor.compressBitmap(fileBytes, maxDim = 800, quality = 50)
        } else {
            fileBytes
        }

        sendMessageInternal(
            rawBytes = preparedBytes,
            type = RadioPacket.TYPE_FILE_CHUNK,
            textContent = if (isImage) "Image: $fileName (${preparedBytes.size}B)" else "File: $fileName (${preparedBytes.size}B)",
            isImage = isImage,
            fileName = fileName,
            replyToId = null,
            replyToText = null
        )
    }

    /**
     * Sends Type 5 command to delete a message locally and on the peer node.
     */
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
            bluetoothService.sendPacket(packet)
        }
    }

    /**
     * Sends Type 6 command to edit an existing message.
     */
    fun sendEditMessage(message: ChatMessage, newText: String) {
        scope.launch {
            repository.updateTextContent(message.id, newText)

            val plainBytes = newText.toByteArray(Charsets.UTF_8)
            val gzipped = MediaCompressor.gzipCompress(plainBytes)
            val encrypted = AesSecurity.encrypt(gzipped)

            val stream = ByteArray(4 + encrypted.size)
            stream[0] = ((encrypted.size shr 24) and 0xFF).toByte()
            stream[1] = ((encrypted.size shr 16) and 0xFF).toByte()
            stream[2] = ((encrypted.size shr 8) and 0xFF).toByte()
            stream[3] = (encrypted.size and 0xFF).toByte()
            System.arraycopy(encrypted, 0, stream, 4, encrypted.size)

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
                bluetoothService.sendPacket(packet)
            }
        }
    }

    private fun sendMessageInternal(
        rawBytes: ByteArray,
        type: Int,
        textContent: String?,
        isImage: Boolean,
        fileName: String?,
        replyToId: String?,
        replyToText: String?
    ) {
        scope.launch {
            // 1. GZIP Compress before encryption
            val gzipped = MediaCompressor.gzipCompress(rawBytes)
            val gzipSaved = maxOf(0L, (rawBytes.size - gzipped.size).toLong())

            // 2. AES-256 Encrypt
            val encrypted = AesSecurity.encrypt(gzipped)

            // 3. Framing: [4-byte length] + [encrypted bytes]
            val totalEncryptedLen = encrypted.size
            val stream = ByteArray(4 + totalEncryptedLen)
            stream[0] = ((totalEncryptedLen shr 24) and 0xFF).toByte()
            stream[1] = ((totalEncryptedLen shr 16) and 0xFF).toByte()
            stream[2] = ((totalEncryptedLen shr 8) and 0xFF).toByte()
            stream[3] = (totalEncryptedLen and 0xFF).toByte()
            System.arraycopy(encrypted, 0, stream, 4, totalEncryptedLen)

            // 4. Calculate chunks
            val totalChunks = (stream.size + RadioPacket.MAX_PAYLOAD_SIZE - 1) / RadioPacket.MAX_PAYLOAD_SIZE
            val msgIdByte = nextMessageId.getAndIncrement() and 0xFF
            val uuid = UUID.randomUUID().toString()

            val chatMsg = ChatMessage(
                id = uuid,
                messageIdByte = msgIdByte,
                sender = SenderType.ME,
                type = type,
                textContent = textContent,
                fileBytes = rawBytes,
                fileName = fileName,
                isImage = isImage,
                totalChunks = totalChunks,
                currentChunk = 0,
                retriesCurrentChunk = 0,
                status = MessageStatus.SENDING,
                replyToId = replyToId,
                replyToText = replyToText
            )

            // Persist to Room
            repository.insertMessage(chatMsg)
            recordMetric(sent = 0, recv = 0, failed = 0, retries = 0, gzipSaved = gzipSaved)

            var allSucceeded = true

            for (chunkIdx in 0 until totalChunks) {
                val start = chunkIdx * RadioPacket.MAX_PAYLOAD_SIZE
                val end = minOf(start + RadioPacket.MAX_PAYLOAD_SIZE, stream.size)
                val chunkPayload = stream.sliceArray(start until end)

                val packet = RadioPacket(
                    type = type,
                    messageId = msgIdByte,
                    chunkIndex = chunkIdx,
                    totalChunks = totalChunks,
                    payload = chunkPayload
                )

                if (type == RadioPacket.TYPE_UDP_TEXT) {
                    bluetoothService.sendPacket(packet)
                    delay(30)
                    repository.updateProgress(uuid, chunkIdx + 1, 0, MessageStatus.SENT_UNACKED)
                } else {
                    val key = ackKey(msgIdByte, chunkIdx)
                    var chunkAcked = false
                    var retries = 0

                    while (!chunkAcked && retries <= tcpRetryLimit && isActive) {
                        val deferred = CompletableDeferred<Unit>()
                        pendingAcks[key] = deferred

                        bluetoothService.sendPacket(packet)

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
                        NotificationHelper.showMessageFailedNotification(context, textContent ?: "File transfer")
                        break
                    } else {
                        repository.updateProgress(uuid, chunkIdx + 1, 0, MessageStatus.SENDING)
                    }
                }
            }

            val finalStatus = when {
                !allSucceeded -> MessageStatus.FAILED
                type == RadioPacket.TYPE_UDP_TEXT -> MessageStatus.SENT_UNACKED
                else -> MessageStatus.DELIVERED
            }
            repository.updateStatus(uuid, finalStatus)
        }
    }

    private fun ackKey(messageId: Int, chunkIndex: Int): Int {
        return (messageId shl 16) or (chunkIndex and 0xFFFF)
    }

    private fun isImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return true
        return false
    }
}
