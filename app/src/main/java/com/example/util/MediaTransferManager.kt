package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.data.crypto.AesSecurity
import com.example.data.model.RadioPacket
import com.example.data.protocol.PacketParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

data class PreSendMetrics(
    val compressedBytes: ByteArray,
    val fileSizeFormatted: String,
    val rawByteCount: Int,
    val gzipByteCount: Int,
    val encryptedByteCount: Int,
    val estimatedPackets: Int,
    val estimatedTransferTimeSec: Float,
    val width: Int,
    val height: Int
)

data class TransferProgress(
    val messageId: Int,
    val totalChunks: Int,
    val sentOrReceivedChunks: Int,
    val percent: Int,
    val isSender: Boolean,
    val isComplete: Boolean = false,
    val isFailed: Boolean = false
)

class MediaTransferManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Tracks ongoing media transfers for UI progress indicators
    private val _activeTransfers = MutableStateFlow<Map<Int, TransferProgress>>(emptyMap())
    val activeTransfers: StateFlow<Map<Int, TransferProgress>> = _activeTransfers

    // Receiver state: messageId -> ReceiverSession
    private val receiverSessions = ConcurrentHashMap<Int, ReceiverSession>()

    data class ReceiverSession(
        val messageId: Int,
        val totalChunks: Int,
        val chunks: ConcurrentHashMap<Int, ByteArray> = ConcurrentHashMap(),
        var lastReceivedTime: Long = System.currentTimeMillis(),
        var nackJob: Job? = null
    )

    companion object {
        const val PACKET_BLAST_DELAY_MS = 20L
        const val BURST_QUIET_TIMEOUT_MS = 250L

        /**
         * Calculates image compression and packet metrics dynamically for the Quality Configurator UI.
         */
        fun calculatePreSendMetrics(
            originalBytes: ByteArray,
            quality: Int, // 10 to 100
            maxDim: Int = 800
        ): PreSendMetrics? {
            return try {
                val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size) ?: return null
                val origW = bitmap.width
                val origH = bitmap.height

                val scale = if (maxOf(origW, origH) > maxDim) {
                    maxDim.toFloat() / maxOf(origW, origH)
                } else {
                    1f
                }

                val targetW = (origW * scale).roundToInt().coerceAtLeast(1)
                val targetH = (origH * scale).roundToInt().coerceAtLeast(1)

                val scaledBitmap = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                } else {
                    bitmap
                }

                val outStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), outStream)
                val jpegBytes = outStream.toByteArray()

                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
                bitmap.recycle()

                // GZIP compression estimation
                val gzipped = MediaCompressor.gzipCompress(jpegBytes)
                // AES-256 estimation: 16 bytes IV + PKCS5 padding to 16 bytes
                val paddedLen = ((gzipped.size + 15) / 16) * 16
                val encryptedLen = 16 + paddedLen // IV + ciphertext
                val framedStreamLen = 4 + encryptedLen // 4-byte length header + encrypted bytes

                val packetCount = ceil(framedStreamLen.toDouble() / PacketParser.MAX_PAYLOAD_SIZE).toInt()
                val estTime = (packetCount * PACKET_BLAST_DELAY_MS) / 1000f

                val sizeFormatted = when {
                    jpegBytes.size < 1024 -> "${jpegBytes.size} B"
                    jpegBytes.size < 1024 * 1024 -> String.format("%.1f KB", jpegBytes.size / 1024.0)
                    else -> String.format("%.2f MB", jpegBytes.size / (1024.0 * 1024.0))
                }

                PreSendMetrics(
                    compressedBytes = jpegBytes,
                    fileSizeFormatted = sizeFormatted,
                    rawByteCount = jpegBytes.size,
                    gzipByteCount = gzipped.size,
                    encryptedByteCount = encryptedLen,
                    estimatedPackets = packetCount,
                    estimatedTransferTimeSec = estTime,
                    width = targetW,
                    height = targetH
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        /**
         * Saves a reconstructed or sent JPG byte array to context.cacheDir/radio_images/
         */
        fun saveImageToCache(context: Context, imageBytes: ByteArray, messageId: Int): String {
            val dir = File(context.cacheDir, "radio_images")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "radio_img_${messageId}_${UUID.randomUUID().toString().take(6)}.jpg")
            FileOutputStream(file).use { it.write(imageBytes) }
            return file.absolutePath
        }
    }

    /**
     * Prepares packets for Selective Repeat blast transmission.
     */
    fun prepareSelectiveRepeatPackets(
        rawBytes: ByteArray,
        messageId: Int,
        headerType: Int = RadioPacket.TYPE_FILE_CHUNK
    ): Pair<List<RadioPacket>, Long> {
        val gzipped = MediaCompressor.gzipCompress(rawBytes)
        val gzipSaved = maxOf(0L, (rawBytes.size - gzipped.size).toLong())
        val encrypted = AesSecurity.encrypt(gzipped)

        val totalEncryptedLen = encrypted.size
        val stream = ByteArray(4 + totalEncryptedLen)
        stream[0] = ((totalEncryptedLen shr 24) and 0xFF).toByte()
        stream[1] = ((totalEncryptedLen shr 16) and 0xFF).toByte()
        stream[2] = ((totalEncryptedLen shr 8) and 0xFF).toByte()
        stream[3] = (totalEncryptedLen and 0xFF).toByte()
        System.arraycopy(encrypted, 0, stream, 4, totalEncryptedLen)

        val totalChunks = (stream.size + RadioPacket.MAX_PAYLOAD_SIZE - 1) / RadioPacket.MAX_PAYLOAD_SIZE
        val packets = mutableListOf<RadioPacket>()

        for (i in 0 until totalChunks) {
            val start = i * RadioPacket.MAX_PAYLOAD_SIZE
            val end = minOf(start + RadioPacket.MAX_PAYLOAD_SIZE, stream.size)
            val chunk = stream.sliceArray(start until end)

            packets.add(
                RadioPacket(
                    type = headerType,
                    messageId = messageId,
                    chunkIndex = i,
                    totalChunks = totalChunks,
                    payload = chunk
                )
            )
        }

        return Pair(packets, gzipSaved)
    }

    /**
     * Handles incoming file chunk under Selective Repeat protocol.
     * Returns the reconstructed image absolute path if this chunk completed the transfer, otherwise null.
     */
    fun handleIncomingChunk(
        packet: RadioPacket,
        onSendNack: (RadioPacket) -> Unit,
        onComplete: (filePath: String, rawBytes: ByteArray) -> Unit
    ) {
        val msgId = packet.messageId
        val session = receiverSessions.getOrPut(msgId) {
            ReceiverSession(messageId = msgId, totalChunks = packet.totalChunks)
        }

        session.chunks[packet.chunkIndex] = packet.payload
        session.lastReceivedTime = System.currentTimeMillis()

        updateProgress(
            messageId = msgId,
            totalChunks = packet.totalChunks,
            current = session.chunks.size,
            isSender = false,
            isComplete = session.chunks.size == packet.totalChunks
        )

        // If all chunks received, reconstruct immediately!
        if (session.chunks.size == packet.totalChunks) {
            session.nackJob?.cancel()
            receiverSessions.remove(msgId)
            val reconstructed = reconstructBytes(session.totalChunks, session.chunks)
            if (reconstructed != null) {
                val savedPath = saveImageToCache(context, reconstructed, msgId)
                onComplete(savedPath, reconstructed)
            }
            return
        }

        // Schedule NACK check if burst ends with missing chunks
        session.nackJob?.cancel()
        session.nackJob = scope.launch {
            delay(BURST_QUIET_TIMEOUT_MS)
            val missing = mutableListOf<Int>()
            for (i in 0 until session.totalChunks) {
                if (!session.chunks.containsKey(i)) {
                    missing.add(i)
                }
            }
            if (missing.isNotEmpty() && isActive) {
                // Send NACK packet requesting missing chunks
                val nackPacket = RadioPacket.buildNack(msgId, missing, session.totalChunks)
                onSendNack(nackPacket)
            }
        }
    }

    fun trimTrailingZeros(bytes: ByteArray): ByteArray {
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) {
            end--
        }
        return if (end == bytes.size) bytes else bytes.copyOf(end)
    }

    /**
     * Cleans trailing padding bytes from JPEG byte streams.
     * Searches for the standard JPEG End-Of-Image marker (0xFF, 0xD9) and trims any trailing zero bytes.
     */
    fun cleanJpeg(bytes: ByteArray): ByteArray {
        val trimmed = trimTrailingZeros(bytes)
        for (i in (trimmed.size - 2) downTo 0) {
            if (trimmed[i] == 0xFF.toByte() && trimmed[i + 1] == 0xD9.toByte()) {
                return trimmed.copyOf(i + 2)
            }
        }
        return trimmed
    }

    private fun reconstructBytes(totalChunks: Int, chunksMap: Map<Int, ByteArray>): ByteArray? {
        return try {
            val totalBytes = totalChunks * RadioPacket.MAX_PAYLOAD_SIZE
            val combined = ByteArray(totalBytes)
            var offset = 0
            for (i in 0 until totalChunks) {
                val chunk = chunksMap[i] ?: return null
                System.arraycopy(chunk, 0, combined, offset, minOf(chunk.size, RadioPacket.MAX_PAYLOAD_SIZE))
                offset += RadioPacket.MAX_PAYLOAD_SIZE
            }

            val candidates = mutableListOf<ByteArray>()

            // 1. Check exact 4-byte big-endian framing length header
            if (combined.size >= 4) {
                val totalLen = ((combined[0].toInt() and 0xFF) shl 24) or
                        ((combined[1].toInt() and 0xFF) shl 16) or
                        ((combined[2].toInt() and 0xFF) shl 8) or
                        (combined[3].toInt() and 0xFF)

                if (totalLen in 1..(combined.size - 4)) {
                    val exactEncrypted = combined.sliceArray(4 until (4 + totalLen))
                    val decrypted = AesSecurity.decrypt(exactEncrypted)
                    if (decrypted != null) {
                        val decompressed = try {
                            MediaCompressor.gzipDecompress(trimTrailingZeros(decrypted))
                        } catch (_: Exception) {
                            decrypted
                        }
                        candidates.add(decompressed)
                    }
                }
            }

            // 2. Fallback: Trim trailing 0x00 padding bytes from reconstructed byte array
            val trimmedReassembled = trimTrailingZeros(combined)
            if (trimmedReassembled.isNotEmpty()) {
                val rawPayload = if (trimmedReassembled.size >= 4) {
                    val totalLen = ((trimmedReassembled[0].toInt() and 0xFF) shl 24) or
                            ((trimmedReassembled[1].toInt() and 0xFF) shl 16) or
                            ((trimmedReassembled[2].toInt() and 0xFF) shl 8) or
                            (trimmedReassembled[3].toInt() and 0xFF)

                    if (totalLen in 1..(trimmedReassembled.size - 4)) {
                        trimmedReassembled.sliceArray(4 until (4 + totalLen))
                    } else {
                        trimmedReassembled
                    }
                } else {
                    trimmedReassembled
                }

                val decryptedRaw = AesSecurity.decrypt(rawPayload)
                    ?: AesSecurity.decrypt(trimmedReassembled)
                    ?: rawPayload

                val toDecompress = trimTrailingZeros(decryptedRaw)
                try {
                    candidates.add(MediaCompressor.gzipDecompress(toDecompress))
                } catch (_: Exception) {
                    candidates.add(toDecompress)
                }
            }

            // 3. Find the best valid decoded JPEG candidate
            for (candidate in candidates) {
                val cleaned = cleanJpeg(candidate)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(cleaned, 0, cleaned.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    return cleaned
                }
            }

            // Fallback to first non-empty cleaned candidate or trimmed array
            candidates.firstOrNull { it.isNotEmpty() }?.let { return cleanJpeg(it) }
            cleanJpeg(trimmedReassembled)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun updateProgress(
        messageId: Int,
        totalChunks: Int,
        current: Int,
        isSender: Boolean,
        isComplete: Boolean = false,
        isFailed: Boolean = false
    ) {
        val pct = if (totalChunks > 0) ((current.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100) else 0
        val progress = TransferProgress(
            messageId = messageId,
            totalChunks = totalChunks,
            sentOrReceivedChunks = current,
            percent = pct,
            isSender = isSender,
            isComplete = isComplete,
            isFailed = isFailed
        )
        val currentMap = _activeTransfers.value.toMutableMap()
        // Ensure that completed, failed, or 100% finished transfers are removed immediately
        if (isComplete || isFailed || current >= totalChunks) {
            currentMap.remove(messageId)
        } else {
            currentMap[messageId] = progress
        }
        _activeTransfers.value = currentMap
    }
}
