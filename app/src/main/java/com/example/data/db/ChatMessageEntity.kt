package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String, // UUID for message referencing and reply tracking
    val messageIdByte: Int, // 0-255 header ID
    val sender: String, // "ME" or "PEER"
    val type: Int, // 0: UDP, 1: TCP, 2: File, etc.
    val textContent: String? = null,
    val fileBytes: ByteArray? = null,
    val fileName: String? = null,
    val isImage: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val totalChunks: Int = 1,
    val currentChunk: Int = 0,
    val retriesCurrentChunk: Int = 0,
    val status: String = "PENDING", // PENDING, SENDING, SENT_UNACKED, DELIVERED, SEEN, FAILED
    val replyToId: String? = null, // UUID of the replied message
    val replyToText: String? = null, // Snippet of the replied message
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatMessageEntity) return false
        return id == other.id &&
                messageIdByte == other.messageIdByte &&
                sender == other.sender &&
                type == other.type &&
                textContent == other.textContent &&
                fileName == other.fileName &&
                isImage == other.isImage &&
                timestamp == other.timestamp &&
                totalChunks == other.totalChunks &&
                currentChunk == other.currentChunk &&
                retriesCurrentChunk == other.retriesCurrentChunk &&
                status == other.status &&
                replyToId == other.replyToId &&
                replyToText == other.replyToText &&
                isEdited == other.isEdited &&
                isDeleted == other.isDeleted
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
