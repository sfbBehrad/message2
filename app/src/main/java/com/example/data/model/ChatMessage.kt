package com.example.data.model

import com.example.data.db.ChatMessageEntity

enum class SenderType {
    ME,
    PEER
}

enum class MessageStatus {
    PENDING,        // Clock 🕒 (Attempting)
    SENDING,        // Clock 🕒 (Transmitting chunks)
    SENT_UNACKED,   // 1 Black/Grey Tick ✓ (Sent to local ESP32 / UDP)
    DELIVERED,      // 2 Black/Grey Ticks ✓✓ (Type 3 ACK received from remote node)
    SEEN,           // 2 Blue Ticks ✓✓ (Type 4 ACK Seen received from peer user)
    READ,           // 2 Blue Ticks ✓✓ (Type 4 ACK Read alias)
    FAILED,         // Red exclamation ✕
    COMPLETED,      // Media download completed
    DELETED         // This message was deleted
}

data class ChatMessage(
    val id: String, // UUID
    val messageIdByte: Int, // 0-255 header ID
    val sender: SenderType,
    val type: Int,
    val textContent: String? = null,
    val fileBytes: ByteArray? = null,
    val filePath: String? = null, // Absolute path to cached JPG for Coil
    val fileName: String? = null,
    val isImage: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val totalChunks: Int = 1,
    val currentChunk: Int = 0,
    val retriesCurrentChunk: Int = 0,
    val status: MessageStatus = MessageStatus.PENDING,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false
) {
    fun toEntity(): ChatMessageEntity = ChatMessageEntity(
        id = id,
        messageIdByte = messageIdByte,
        sender = sender.name,
        type = type,
        textContent = textContent,
        fileBytes = fileBytes,
        filePath = filePath,
        fileName = fileName,
        isImage = isImage,
        timestamp = timestamp,
        totalChunks = totalChunks,
        currentChunk = currentChunk,
        retriesCurrentChunk = retriesCurrentChunk,
        status = status.name,
        replyToId = replyToId,
        replyToText = replyToText,
        isEdited = isEdited,
        isDeleted = isDeleted
    )

    companion object {
        fun fromEntity(entity: ChatMessageEntity): ChatMessage = ChatMessage(
            id = entity.id,
            messageIdByte = entity.messageIdByte,
            sender = if (entity.sender == "ME") SenderType.ME else SenderType.PEER,
            type = entity.type,
            textContent = entity.textContent,
            fileBytes = entity.fileBytes,
            filePath = entity.filePath,
            fileName = entity.fileName,
            isImage = entity.isImage,
            timestamp = entity.timestamp,
            totalChunks = entity.totalChunks,
            currentChunk = entity.currentChunk,
            retriesCurrentChunk = entity.retriesCurrentChunk,
            status = try {
                if (entity.isDeleted || entity.status.equals("DELETED", ignoreCase = true)) {
                    MessageStatus.DELETED
                } else {
                    MessageStatus.valueOf(entity.status)
                }
            } catch (_: Exception) {
                if (entity.isDeleted) MessageStatus.DELETED else MessageStatus.DELIVERED
            },
            replyToId = entity.replyToId,
            replyToText = entity.replyToText,
            isEdited = entity.isEdited,
            isDeleted = entity.isDeleted || entity.status.equals("DELETED", ignoreCase = true)
        )
    }
}

data class PacketLogEntry(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val direction: Direction,
    val typeName: String,
    val messageId: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    val hexDump: String,
    val isAck: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Direction { TX, RX, SYS }
}

data class AnalyticsSnapshot(
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val packetsFailed: Int = 0,
    val packetsRetried: Int = 0,
    val totalBytesTransferred: Long = 0,
    val bytesSavedGzip: Long = 0,
    val timePoints: List<AnalyticsPoint> = emptyList()
)

data class AnalyticsPoint(
    val minuteOffset: Int, // 0 to 60 minutes
    val sentCount: Int,
    val receivedCount: Int,
    val failedCount: Int
)
