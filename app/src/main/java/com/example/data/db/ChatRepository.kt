package com.example.data.db

import com.example.data.model.ChatMessage
import com.example.data.model.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(private val dao: ChatMessageDao) {

    val allMessages: Flow<List<ChatMessage>> = dao.getAllMessages().map { list ->
        list.map { ChatMessage.fromEntity(it) }
    }

    suspend fun getMessageById(id: String): ChatMessage? {
        return dao.getMessageById(id)?.let { ChatMessage.fromEntity(it) }
    }

    suspend fun getLatestOutgoingByByteId(byteId: Int): ChatMessage? {
        return dao.getLatestOutgoingByByteId(byteId)?.let { ChatMessage.fromEntity(it) }
    }

    suspend fun getLatestIncomingByByteId(byteId: Int): ChatMessage? {
        return dao.getLatestIncomingByByteId(byteId)?.let { ChatMessage.fromEntity(it) }
    }

    suspend fun markIncomingAsDeletedByByteId(byteId: Int) {
        dao.markIncomingAsDeletedByByteId(byteId)
    }

    suspend fun getUnseenIncomingMessages(): List<ChatMessage> {
        return dao.getUnseenIncomingMessages().map { ChatMessage.fromEntity(it) }
    }

    suspend fun getUnackedOutgoingMessages(): List<ChatMessage> {
        return dao.getUnackedOutgoingMessages().map { ChatMessage.fromEntity(it) }
    }

    suspend fun insertMessage(message: ChatMessage) {
        dao.insertMessage(message.toEntity())
    }

    suspend fun updateMessage(message: ChatMessage) {
        dao.updateMessage(message.toEntity())
    }

    suspend fun updateStatus(id: String, status: MessageStatus) {
        dao.updateStatus(id, status.name)
    }

    suspend fun updateStatusByByteId(byteId: Int, status: MessageStatus) {
        dao.updateStatusByByteId(byteId, status.name)
    }

    suspend fun updateProgress(id: String, currentChunk: Int, retries: Int, status: MessageStatus) {
        dao.updateProgress(id, currentChunk, retries, status.name)
    }

    suspend fun markAllIncomingAsSeen() {
        dao.markAllIncomingAsSeen()
    }

    suspend fun updateTextContent(id: String, newText: String) {
        dao.updateTextContent(id, newText)
    }

    suspend fun markAsDeleted(id: String) {
        dao.markAsDeleted(id)
    }

    suspend fun deleteMessageById(id: String) {
        dao.deleteMessageById(id)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
