package com.pai.android.data.local

import androidx.room.*
import com.pai.android.data.model.Summary
import com.pai.android.data.model.SummaryType
import kotlinx.coroutines.flow.Flow

@Dao
interface SummaryDao {
    
    @Insert
    suspend fun insert(summary: Summary)
    
    @Update
    suspend fun update(summary: Summary)
    
    @Delete
    suspend fun delete(summary: Summary)
    
    @Query("SELECT * FROM summaries WHERE id = :id")
    suspend fun getById(id: String): Summary?
    
    @Query("SELECT * FROM summaries WHERE chatId = :chatId ORDER BY createdAt DESC")
    fun observeByChat(chatId: String): Flow<List<Summary>>
    
    @Query("SELECT * FROM summaries WHERE chatId = :chatId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByChat(chatId: String, limit: Int = 20): List<Summary>
    
    @Query("SELECT * FROM summaries WHERE chatId = :chatId AND type = :type ORDER BY createdAt DESC")
    fun observeByChatAndType(chatId: String, type: SummaryType): Flow<List<Summary>>
    
    @Query("""
        SELECT * FROM summaries 
        WHERE chatId = :chatId 
        AND (:type IS NULL OR type = :type)
        ORDER BY createdAt DESC 
        LIMIT :limit
    """)
    suspend fun getRecent(
        chatId: String,
        type: SummaryType? = null,
        limit: Int = 10
    ): List<Summary>
    
    @Query("""
        SELECT COUNT(*) FROM summaries 
        WHERE chatId = :chatId 
        AND type = :type
    """)
    suspend fun countByChatAndType(chatId: String, type: SummaryType): Int
    
    @Query("DELETE FROM summaries WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: String)
    
    @Query("DELETE FROM summaries WHERE createdAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
    
    /** Получить последние суммаризации всех чатов */
    @Query("""
        SELECT s1.* FROM summaries s1
        WHERE s1.createdAt = (
            SELECT MAX(s2.createdAt) 
            FROM summaries s2 
            WHERE s2.chatId = s1.chatId
        )
        ORDER BY s1.createdAt DESC
    """)
    suspend fun getLatestPerChat(): List<Summary>
}