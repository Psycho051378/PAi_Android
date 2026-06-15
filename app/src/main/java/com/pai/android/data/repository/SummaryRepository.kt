package com.pai.android.data.repository

import com.pai.android.data.local.SummaryDao
import com.pai.android.data.model.Summary
import com.pai.android.data.model.SummaryType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Репозиторий для работы с суммаризациями.
 */
class SummaryRepository @Inject constructor(
    private val summaryDao: SummaryDao
) {
    
    suspend fun insert(summary: Summary) {
        summaryDao.insert(summary)
    }
    
    suspend fun update(summary: Summary) {
        summaryDao.update(summary)
    }
    
    suspend fun delete(summary: Summary) {
        summaryDao.delete(summary)
    }
    
    suspend fun getById(id: String): Summary? {
        return summaryDao.getById(id)
    }
    
    fun observeByChat(chatId: String): Flow<List<Summary>> {
        return summaryDao.observeByChat(chatId)
    }
    
    suspend fun getByChat(chatId: String, limit: Int = 20): List<Summary> {
        return summaryDao.getByChat(chatId, limit)
    }
    
    fun observeByChatAndType(chatId: String, type: SummaryType): Flow<List<Summary>> {
        return summaryDao.observeByChatAndType(chatId, type)
    }
    
    suspend fun getRecent(
        chatId: String,
        type: SummaryType? = null,
        limit: Int = 10
    ): List<Summary> {
        return summaryDao.getRecent(chatId, type, limit)
    }
    
    suspend fun countByChatAndType(chatId: String, type: SummaryType): Int {
        return summaryDao.countByChatAndType(chatId, type)
    }
    
    suspend fun deleteByChat(chatId: String) {
        summaryDao.deleteByChat(chatId)
    }
    
    suspend fun deleteOlderThan(timestamp: Long) {
        summaryDao.deleteOlderThan(timestamp)
    }
    

    
    suspend fun getLatestPerChat(): List<Summary> {
        return summaryDao.getLatestPerChat()
    }
    
    /**
     * Получить последние N суммаризаций для чата, отсортированные по релевантности.
     * Сначала кластерные суммаризации, затем ежедневные.
     */
    suspend fun getRelevantForContext(chatId: String, limit: Int = 5): List<Summary> {
        val allSummaries = getByChat(chatId, limit * 2)
        
        // Приоритет: CLUSTER > DAILY > WEEKLY > TOPIC
        return allSummaries.sortedBy { summary ->
            when (summary.type) {
                SummaryType.CLUSTER -> 1
                SummaryType.DAILY -> 2
                SummaryType.WEEKLY -> 3
                SummaryType.TOPIC -> 4
            }
        }.take(limit)
    }
    
    /**
     * Проверяет, нужно ли создавать новую суммаризацию для чата.
     * @param chatId ID чата
     * @param minMessages Минимальное количество сообщений для суммаризации
     * @param maxAgeDays Максимальный возраст последней суммаризации (в днях)
     */
    suspend fun needsNewSummary(
        chatId: String,
        minMessages: Int = 20,
        maxAgeDays: Int = 1
    ): Boolean {
        // Проверяем, когда была создана последняя кластерная суммаризация
        val lastClusterSummary = getRecent(chatId, SummaryType.CLUSTER, 1).firstOrNull()
        
        if (lastClusterSummary == null) {
            // Нет ни одной суммаризации - нужна первая
            return true
        }
        
        val ageMillis = System.currentTimeMillis() - lastClusterSummary.createdAt
        val ageDays = ageMillis / (1000 * 60 * 60 * 24)
        
        // Нужна новая суммаризация, если старая старше maxAgeDays дней
        return ageDays >= maxAgeDays
    }
    
    /**
     * Получает суммаризации, которые ещё не были использованы в текущем контексте.
     */
    suspend fun getUnusedSummaries(chatId: String, usedSummaryIds: List<String>): List<Summary> {
        val allSummaries = getByChat(chatId, 50)
        return allSummaries.filterNot { it.id in usedSummaryIds }
    }
    
    /**
     * Обновляет оценку качества суммаризации.
     */
    suspend fun updateQualityScore(summaryId: String, score: Float) {
        val summary = getById(summaryId)
        summary?.let {
            val updated = it.copy(qualityScore = score)
            update(updated)
        }
    }
}