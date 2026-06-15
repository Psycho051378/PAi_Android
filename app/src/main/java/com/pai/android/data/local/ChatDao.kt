package com.pai.android.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pai.android.data.model.Chat
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chat: Chat): Long
    
    @Update
    suspend fun update(chat: Chat)
    
    @Delete
    suspend fun delete(chat: Chat)
    
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Chat>>
    
    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getById(chatId: String): Chat?
    
    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun observeById(chatId: String): Flow<Chat?>
    
    @Query("SELECT * FROM chats WHERE isArchived = 0 ORDER BY updatedAt DESC")
    fun observeActiveChats(): Flow<List<Chat>>
    
    @Query("SELECT * FROM chats WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun observeArchivedChats(): Flow<List<Chat>>
    
    @Query("UPDATE chats SET isArchived = :isArchived WHERE id = :chatId")
    suspend fun setArchived(chatId: String, isArchived: Boolean)
    
    @Query("UPDATE chats SET title = :title, updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun updateTitle(chatId: String, title: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE chats SET messageCount = messageCount + 1, updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun incrementMessageCount(chatId: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("DELETE FROM chats")
    suspend fun deleteAll()
}