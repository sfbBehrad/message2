package com.example.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE messageIdByte = :byteId AND sender = 'ME' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestOutgoingByByteId(byteId: Int): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE messageIdByte = :byteId AND sender = 'PEER' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestIncomingByByteId(byteId: Int): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE sender = 'PEER' AND status != 'SEEN'")
    suspend fun getUnseenIncomingMessages(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query("UPDATE chat_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE chat_messages SET status = :status WHERE messageIdByte = :byteId AND sender = 'ME'")
    suspend fun updateStatusByByteId(byteId: Int, status: String)

    @Query("UPDATE chat_messages SET currentChunk = :chunk, retriesCurrentChunk = :retries, status = :status WHERE id = :id")
    suspend fun updateProgress(id: String, chunk: Int, retries: Int, status: String)

    @Query("UPDATE chat_messages SET status = 'SEEN' WHERE sender = 'PEER' AND status != 'SEEN'")
    suspend fun markAllIncomingAsSeen()

    @Query("UPDATE chat_messages SET textContent = :newText, isEdited = 1 WHERE id = :id")
    suspend fun updateTextContent(id: String, newText: String)

    @Query("UPDATE chat_messages SET isDeleted = 1, textContent = 'This message was deleted' WHERE id = :id")
    suspend fun markAsDeleted(id: String)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}
