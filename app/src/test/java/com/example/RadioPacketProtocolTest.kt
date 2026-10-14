package com.example

import com.example.data.crypto.AesSecurity
import com.example.data.model.RadioPacket
import com.example.data.protocol.PacketParser
import com.example.util.MediaCompressor
import org.junit.Assert.*
import org.junit.Test

class RadioPacketProtocolTest {

    @Test
    fun testRadioPacket32ByteStrictSerialization() {
        val payload = "Hello NRF24 Radio!".toByteArray(Charsets.UTF_8)
        val packet = RadioPacket(
            type = RadioPacket.TYPE_TCP_TEXT,
            messageId = 42,
            chunkIndex = 1,
            totalChunks = 5,
            payload = payload
        )

        val bytes = RadioPacket.toBytes(packet)
        assertEquals("Packet must be exactly 32 bytes", 32, bytes.size)

        // Verify header layout
        assertEquals("Byte 0 must be Type (1 for TCP)", 1.toByte(), bytes[0])
        assertEquals("Byte 1 must be Message ID 42", 42.toByte(), bytes[1])
        assertEquals("Byte 2 must be Chunk Index High Byte", 0.toByte(), bytes[2])
        assertEquals("Byte 3 must be Chunk Index Low Byte", 1.toByte(), bytes[3])
        assertEquals("Byte 4 must be Total Chunks High Byte", 0.toByte(), bytes[4])
        assertEquals("Byte 5 must be Total Chunks Low Byte", 5.toByte(), bytes[5])

        val parsed = RadioPacket.fromBytes(bytes)
        assertNotNull(parsed)
        assertEquals(RadioPacket.TYPE_TCP_TEXT, parsed!!.type)
        assertEquals(42, parsed.messageId)
        assertEquals(1, parsed.chunkIndex)
        assertEquals(5, parsed.totalChunks)
    }

    @Test
    fun testDeliveredAndSeenAcks() {
        val original = RadioPacket(
            type = RadioPacket.TYPE_TCP_TEXT,
            messageId = 88,
            chunkIndex = 3,
            totalChunks = 7,
            payload = ByteArray(26)
        )

        // Delivered ACK (Type 3)
        val ackDelivered = RadioPacket.buildAckDelivered(original)
        assertEquals(RadioPacket.TYPE_ACK_DELIVERED, ackDelivered.type)
        assertEquals(88, ackDelivered.messageId)
        assertEquals(3, ackDelivered.chunkIndex)

        val ackBytes = RadioPacket.toBytes(ackDelivered)
        assertEquals(32, ackBytes.size)
        assertEquals(RadioPacket.TYPE_ACK_DELIVERED.toByte(), ackBytes[0])

        // Seen ACK (Type 4)
        val ackSeen = RadioPacket.buildAckSeen(88)
        assertEquals(RadioPacket.TYPE_ACK_SEEN, ackSeen.type)
        assertEquals(88, ackSeen.messageId)
        val seenBytes = RadioPacket.toBytes(ackSeen)
        assertEquals(32, seenBytes.size)
        assertEquals(RadioPacket.TYPE_ACK_SEEN.toByte(), seenBytes[0])
    }

    @Test
    fun testHeartbeatPingAndPongPackets() {
        val ping = RadioPacket.buildHeartbeatPing(seq = 7)
        assertEquals(RadioPacket.TYPE_HEARTBEAT_PING, ping.type)
        val pingBytes = RadioPacket.toBytes(ping)
        assertEquals(32, pingBytes.size)
        assertEquals(9.toByte(), pingBytes[0])

        val pong = RadioPacket.buildHeartbeatPong(ping)
        assertEquals(RadioPacket.TYPE_HEARTBEAT_PONG, pong.type)
        val pongBytes = RadioPacket.toBytes(pong)
        assertEquals(32, pongBytes.size)
        assertEquals(10.toByte(), pongBytes[0])
        assertEquals(pong.messageId, ping.messageId)
    }

    @Test
    fun testSelectiveRepeatNackSerialization() {
        val missing = listOf(2, 5, 8, 14)
        val nack = RadioPacket.buildNack(messageId = 55, missingIndices = missing, totalChunks = 20)
        assertEquals(RadioPacket.TYPE_NACK, nack.type)
        assertEquals(55, nack.messageId)

        val nackBytes = RadioPacket.toBytes(nack)
        assertEquals(32, nackBytes.size)
        assertEquals(11.toByte(), nackBytes[0])

        val parsed = RadioPacket.fromBytes(nackBytes)
        assertNotNull(parsed)
        val unpackedIndices = PacketParser.parseNackMissingIndices(parsed!!)
        assertEquals(missing, unpackedIndices)
    }

    @Test
    fun testGzipCompressionAndAesEncryption() {
        val largeText = "Offline NRF24 long-range radio packet transmission with GZIP compression! ".repeat(20)
        val plainBytes = largeText.toByteArray(Charsets.UTF_8)

        // GZIP compression
        val gzipped = MediaCompressor.gzipCompress(plainBytes)
        assertTrue("GZIP compressed data must be smaller than original", gzipped.size < plainBytes.size)

        // AES-256 encryption
        val encrypted = AesSecurity.encrypt(gzipped)
        assertTrue("Encrypted data must include IV", encrypted.size > 16)

        // Decrypt and decompress
        val decrypted = AesSecurity.decrypt(encrypted)
        assertNotNull(decrypted)
        val decompressed = MediaCompressor.gzipDecompress(decrypted!!)
        val recoveredText = String(decompressed, Charsets.UTF_8)

        assertEquals("Recovered text must match original", largeText, recoveredText)
    }

    @Test
    fun testPriorityMessagesSilentAndCriticalAlarm() {
        val silentPacket = RadioPacket(
            type = RadioPacket.TYPE_SILENT,
            messageId = 77,
            chunkIndex = 0,
            totalChunks = 1,
            payload = "Silent".toByteArray(Charsets.UTF_8)
        )
        val silentBytes = RadioPacket.toBytes(silentPacket)
        assertEquals(7.toByte(), silentBytes[0])
        val parsedSilent = RadioPacket.fromBytes(silentBytes)
        assertEquals(RadioPacket.TYPE_SILENT, parsedSilent?.type)

        val alarmPacket = RadioPacket(
            type = RadioPacket.TYPE_CRITICAL_ALARM,
            messageId = 99,
            chunkIndex = 0,
            totalChunks = 1,
            payload = "Emergency Alert!".toByteArray(Charsets.UTF_8)
        )
        val alarmBytes = RadioPacket.toBytes(alarmPacket)
        assertEquals(8.toByte(), alarmBytes[0])
        val parsedAlarm = RadioPacket.fromBytes(alarmBytes)
        assertEquals(RadioPacket.TYPE_CRITICAL_ALARM, parsedAlarm?.type)
    }
}
