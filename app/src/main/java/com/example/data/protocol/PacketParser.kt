package com.example.data.protocol

import com.example.data.model.RadioPacket

/**
 * Strict 32-byte protocol parser for ESP32 + NRF24L01 radio bridge.
 *
 * Byte 0: Header Type
 *   0: UDP Text (Normal Sound)
 *   1: TCP Text (Normal Sound)
 *   2: File/Image Chunk
 *   3: ACK Delivered (Double Tick ✓✓)
 *   4: ACK Read / Seen (Blue Ticks ✓✓)
 *   5: Delete Message command
 *   6: Edit Message command
 *   7: TCP/UDP Text - Silent (No sound, no vibration)
 *   8: TCP/UDP Text - Critical Alarm (Continuous loud alarm on receiver)
 *
 * Byte 1: Message ID (0 - 255)
 * Bytes 2-3: Current Chunk Index (uint16 big-endian)
 * Bytes 4-5: Total Chunks (uint16 big-endian)
 * Bytes 6-31: Data Payload (up to 26 bytes)
 */
object PacketParser {
    const val PACKET_SIZE = 32
    const val MAX_PAYLOAD_SIZE = 26

    // Header Type definitions
    const val TYPE_UDP_NORMAL = 0
    const val TYPE_TCP_NORMAL = 1
    const val TYPE_FILE_CHUNK = 2
    const val TYPE_ACK_DELIVERED = 3
    const val TYPE_ACK_SEEN = 4
    const val TYPE_CMD_DELETE = 5
    const val TYPE_CMD_EDIT = 6
    const val TYPE_SILENT = 7
    const val TYPE_CRITICAL_ALARM = 8

    /**
     * Parses a raw 32-byte array into a RadioPacket.
     */
    fun parse(bytes: ByteArray): RadioPacket? {
        if (bytes.size < PACKET_SIZE) return null

        val type = bytes[0].toInt() and 0xFF
        val messageId = bytes[1].toInt() and 0xFF
        val chunkIndex = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val totalChunks = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)

        val payload = ByteArray(MAX_PAYLOAD_SIZE)
        System.arraycopy(bytes, 6, payload, 0, MAX_PAYLOAD_SIZE)

        return RadioPacket(
            type = type,
            messageId = messageId,
            chunkIndex = chunkIndex,
            totalChunks = maxOf(1, totalChunks),
            payload = payload
        )
    }

    /**
     * Serializes a RadioPacket into an exact 32-byte array.
     */
    fun serialize(packet: RadioPacket): ByteArray {
        val buffer = ByteArray(PACKET_SIZE)
        buffer[0] = (packet.type and 0xFF).toByte()
        buffer[1] = (packet.messageId and 0xFF).toByte()
        buffer[2] = ((packet.chunkIndex shr 8) and 0xFF).toByte()
        buffer[3] = (packet.chunkIndex and 0xFF).toByte()
        buffer[4] = ((packet.totalChunks shr 8) and 0xFF).toByte()
        buffer[5] = (packet.totalChunks and 0xFF).toByte()

        val copyLen = minOf(packet.payload.size, MAX_PAYLOAD_SIZE)
        System.arraycopy(packet.payload, 0, buffer, 6, copyLen)
        return buffer
    }

    /**
     * Builds a Type 3 (Delivered ACK) packet for any incoming packet.
     */
    fun buildAckDelivered(target: RadioPacket): RadioPacket {
        return RadioPacket(
            type = TYPE_ACK_DELIVERED,
            messageId = target.messageId,
            chunkIndex = target.chunkIndex,
            totalChunks = target.totalChunks,
            payload = ByteArray(MAX_PAYLOAD_SIZE)
        )
    }

    /**
     * Builds a Type 4 (Seen ACK) packet.
     */
    fun buildAckSeen(messageId: Int): RadioPacket {
        return RadioPacket(
            type = TYPE_ACK_SEEN,
            messageId = messageId,
            chunkIndex = 0,
            totalChunks = 1,
            payload = ByteArray(MAX_PAYLOAD_SIZE)
        )
    }

    /**
     * Returns true if this packet requires TCP ACK looping.
     */
    fun requiresAck(type: Int): Boolean {
        return type == TYPE_TCP_NORMAL ||
                type == TYPE_FILE_CHUNK ||
                type == TYPE_SILENT ||
                type == TYPE_CRITICAL_ALARM
    }

    fun typeName(type: Int): String = when (type) {
        TYPE_UDP_NORMAL -> "UDP_TEXT"
        TYPE_TCP_NORMAL -> "TCP_TEXT"
        TYPE_FILE_CHUNK -> "FILE_CHUNK"
        TYPE_ACK_DELIVERED -> "ACK_DELIVERED"
        TYPE_ACK_SEEN -> "ACK_SEEN"
        TYPE_CMD_DELETE -> "CMD_DELETE"
        TYPE_CMD_EDIT -> "CMD_EDIT"
        TYPE_SILENT -> "TEXT_SILENT"
        TYPE_CRITICAL_ALARM -> "TEXT_ALARM"
        else -> "UNKNOWN($type)"
    }
}
