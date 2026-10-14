package com.example.data.model

import com.example.data.protocol.PacketParser

/**
 * Strict 32-byte packet structure for ESP32 + NRF24L01 radio bridge.
 *
 * Byte 0:    Message Type:
 *            0: UDP Text (Normal Sound)
 *            1: TCP Text (Normal Sound)
 *            2: File/Image Chunk
 *            3: ACK (Delivered - 2 ticks)
 *            4: ACK (Read/Seen - Blue ticks)
 *            5: Delete Message command
 *            6: Edit Message command
 *            7: TCP/UDP Text - Silent (No sound, no vibration)
 *            8: TCP/UDP Text - Critical Alarm (Continuous loud alarm on receiver)
 *            9: Heartbeat Ping (E2E Link Monitoring)
 *           10: Heartbeat Pong (E2E Link Confirmation)
 *           11: NACK (Selective Repeat Missing Chunks)
 * Byte 1:    Message ID (0 - 255)
 * Bytes 2-3: Current Chunk Index / Ping Seq / Missing Count (uint16, big-endian)
 * Bytes 4-5: Total Chunks (uint16, big-endian)
 * Bytes 6-31: Data Payload (up to 26 bytes)
 */
data class RadioPacket(
    val type: Int,          // 0 to 11
    val messageId: Int,     // 0-255
    val chunkIndex: Int,    // 0-65535
    val totalChunks: Int,   // 1-65535
    val payload: ByteArray  // up to 26 bytes
) {
    companion object {
        const val PACKET_SIZE = 32
        const val MAX_PAYLOAD_SIZE = 26

        const val TYPE_UDP_TEXT = 0
        const val TYPE_TCP_TEXT = 1
        const val TYPE_FILE_CHUNK = 2
        const val TYPE_ACK_DELIVERED = 3   // 2 ticks
        const val TYPE_ACK_SEEN = 4        // Blue ticks
        const val TYPE_CMD_DELETE = 5      // Delete message
        const val TYPE_CMD_EDIT = 6        // Edit message
        const val TYPE_SILENT = 7          // Silent message
        const val TYPE_CRITICAL_ALARM = 8  // Emergency Alarm message
        const val TYPE_HEARTBEAT_PING = 9  // E2E Ping
        const val TYPE_HEARTBEAT_PONG = 10 // E2E Pong
        const val TYPE_NACK = 11           // Selective Repeat NACK

        fun toBytes(packet: RadioPacket): ByteArray = PacketParser.serialize(packet)

        fun fromBytes(bytes: ByteArray): RadioPacket? = PacketParser.parse(bytes)

        fun buildAckDelivered(targetPacket: RadioPacket): RadioPacket =
            PacketParser.buildAckDelivered(targetPacket)

        fun buildAckSeen(messageId: Int): RadioPacket =
            PacketParser.buildAckSeen(messageId)

        fun buildHeartbeatPing(seq: Int): RadioPacket =
            PacketParser.buildHeartbeatPing(seq)

        fun buildHeartbeatPong(pingPacket: RadioPacket): RadioPacket =
            PacketParser.buildHeartbeatPong(pingPacket)

        fun buildNack(messageId: Int, missingIndices: List<Int>, totalChunks: Int): RadioPacket =
            PacketParser.buildNack(messageId, missingIndices, totalChunks)

        fun typeName(type: Int): String = PacketParser.typeName(type)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RadioPacket) return false

        if (type != other.type) return false
        if (messageId != other.messageId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (totalChunks != other.totalChunks) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + messageId
        result = 31 * result + chunkIndex
        result = 31 * result + totalChunks
        result = 31 * result + payload.contentHashCode()
        return result
    }

    fun toHexString(): String {
        val bytes = toBytes(this)
        return bytes.joinToString(" ") { String.format("%02X", it) }
    }
}
