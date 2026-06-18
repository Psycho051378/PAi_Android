package com.pai.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pai.android.data.model.DailyMemory
import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.model.QueryAnalysisResult
import kotlinx.coroutines.flow.Flow
import java.util.Date

/**
 * Статистика по категориям для запроса GROUP BY.
 */
data class CategoryStats(
    val category: String,
    val count: Int
)

/**
 * Data Access Object для работы с памятью (дневной и постоянной).
 */
@Dao
interface MemoryDao {
    
    // ==================== DAILY MEMORY ====================
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDaily(dailyMemory: DailyMemory): Long
    
    @Update
    suspend fun updateDaily(dailyMemory: DailyMemory)
    
    /**
     * Вставляет или обновляет дневную запись для указанной даты.
     * Если запись существует, добавляет новый контент к существующему.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDaily(dailyMemory: DailyMemory)
    
    @Query("SELECT * FROM daily_memory WHERE date = :date")
    suspend fun getDailyByDate(date: String): DailyMemory?
    
    @Query("SELECT * FROM daily_memory WHERE date = :date")
    fun observeDailyByDate(date: String): Flow<DailyMemory?>
    
    @Query("SELECT * FROM daily_memory ORDER BY date DESC")
    fun observeAllDaily(): Flow<List<DailyMemory>>
    
    @Query("SELECT * FROM daily_memory ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentDaily(limit: Int = 30): List<DailyMemory>
    
    @Query("SELECT * FROM daily_memory WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    suspend fun getDailyInRange(startDate: String, endDate: String): List<DailyMemory>
    
    @Query("DELETE FROM daily_memory WHERE date < :date")
    suspend fun deleteOlderThan(date: String)
    
    @Query("DELETE FROM daily_memory WHERE date = :date")
    suspend fun deleteDailyByDate(date: String)
    
    /**
     * Ищет дневные записи по содержимому (регистронезависимый LIKE).
     */
    @Query("SELECT * FROM daily_memory WHERE content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY date DESC LIMIT :limit")
    suspend fun searchDailyContent(query: String, limit: Int = 20): List<DailyMemory>
    
    /**
     * Добавляет текст к существующей дневной записи.
     * Если записи нет, создаёт новую.
     */
    @Query("""
        INSERT INTO daily_memory (id, date, content, createdAt, tags)
        VALUES (:id, :date, :content, :createdAt, :tags)
        ON CONFLICT(date) DO UPDATE SET 
            content = content || '\n\n' || :content,
            tags = CASE 
                WHEN tags LIKE '%' || :tag || '%' THEN tags 
                ELSE COALESCE(NULLIF(tags, ''), '') || CASE WHEN tags != '' THEN ',' ELSE '' END || :tag 
            END
    """)
    suspend fun appendToDaily(
        id: String,
        date: String,
        content: String,
        createdAt: Long,
        tags: String,
        tag: String = ""
    )
    
    // ==================== PERMANENT MEMORY ====================
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPermanent(permanentMemory: PermanentMemory): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPermanent(permanentMemory: PermanentMemory)
    
    @Update
    suspend fun updatePermanent(permanentMemory: PermanentMemory)
    
    @Query("DELETE FROM permanent_memory WHERE id = :id")
    suspend fun deletePermanent(id: String)
    
    @Query("SELECT * FROM permanent_memory WHERE id = :id")
    suspend fun getPermanentById(id: String): PermanentMemory?
    
    @Query("SELECT * FROM permanent_memory ORDER BY updatedAt DESC")
    fun observeAllPermanent(): Flow<List<PermanentMemory>>
    
    @Query("SELECT * FROM permanent_memory ORDER BY scope, category, key")
    suspend fun getAllPermanent(): List<PermanentMemory>
    
    @Query("SELECT * FROM permanent_memory WHERE category = :category ORDER BY key ASC")
    fun observeByCategory(category: String): Flow<List<PermanentMemory>>
    
    @Query("SELECT * FROM permanent_memory WHERE category = :category AND key = :key")
    suspend fun getByCategoryAndKey(category: String, key: String): PermanentMemory?
    
    @Query("SELECT * FROM permanent_memory WHERE category = :category AND key = :key")
    fun observeByCategoryAndKey(category: String, key: String): Flow<PermanentMemory?>
    
    @Query("SELECT * FROM permanent_memory WHERE key = :key AND scope = :scope")
    suspend fun getByKeyAndScope(key: String, scope: String): List<PermanentMemory>
    
    /**
     * Поиск по ключу и значению (регистронезависимый LIKE).
     */
    @Query("""
        SELECT * FROM permanent_memory 
        WHERE key LIKE '%' || :query || '%' 
           OR value LIKE '%' || :query || '%'
        ORDER BY 
            CASE 
                WHEN key LIKE :query THEN 1
                WHEN value LIKE :query THEN 2
                ELSE 3
            END,
            confidence DESC,
            updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchPermanent(query: String, limit: Int = 20): List<PermanentMemory>
    
    @Query("""
        SELECT * FROM permanent_memory 
        WHERE scope = :scope 
          AND (key LIKE '%' || :query || '%' 
               OR value LIKE '%' || :query || '%')
        ORDER BY 
            CASE 
                WHEN key LIKE :query THEN 1
                WHEN value LIKE :query THEN 2
                ELSE 3
            END,
            confidence DESC,
            updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchPermanentInScope(scope: String, query: String, limit: Int = 20): List<PermanentMemory>
    
    /**
     * Получает факты с высокой уверенностью (confidence >= 0.8).
     */
    @Query("SELECT * FROM permanent_memory WHERE confidence >= 0.8 ORDER BY category, key")
    suspend fun getHighConfidenceFacts(): List<PermanentMemory>
    
    /**
     * Получает факты для обогащения промпта AI.
     * Возвращает наиболее релевантные факты (высокая уверенность + свежие).
     */
    @Query("""
        SELECT * FROM permanent_memory 
        WHERE confidence >= 0.7 AND scope != 'project' 
        ORDER BY confidence DESC, updatedAt DESC 
        LIMIT :limit
    """)
    suspend fun getFactsForPrompt(limit: Int = 25): List<PermanentMemory>
    
    /**
     * Увеличивает уверенность факта.
     */
    @Query("UPDATE permanent_memory SET confidence = MIN(confidence + :amount, 1.0), updatedAt = :updatedAt WHERE id = :id")
    suspend fun increaseConfidence(id: String, amount: Float = 0.1f, updatedAt: Long = System.currentTimeMillis())
    
    /**
     * Обновляет значение и уверенность факта.
     */
    @Query("UPDATE permanent_memory SET value = :value, confidence = :confidence, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePermanentValue(id: String, value: String, confidence: Float, updatedAt: Long = System.currentTimeMillis())
    
    /**
     * Получает все уникальные категории.
     */
    @Query("SELECT DISTINCT category FROM permanent_memory ORDER BY category")
    suspend fun getAllCategories(): List<String>
    
    /**
     * Получает количество фактов по категориям.
     */
    @Query("SELECT category, COUNT(*) as count FROM permanent_memory GROUP BY category ORDER BY count DESC")
    suspend fun getCategoryStats(): List<CategoryStats>
    
    /**
     * Ищет факты, связанные с темой (похожие ключи или значения).
     */
    @Query("""
        SELECT * FROM permanent_memory 
        WHERE key LIKE '%' || :topic || '%' 
           OR value LIKE '%' || :topic || '%'
           OR category LIKE '%' || :topic || '%'
        ORDER BY confidence DESC
        LIMIT :limit
    """)
    suspend fun findRelatedFacts(topic: String, limit: Int = 10): List<PermanentMemory>
    
    // ==================== НОВЫЕ ЗАПРОСЫ ДЛЯ ГИБКОЙ СХЕМЫ ====================
    
    /**
     * Получает факт по scope, категории и ключу (учитывает уникальный индекс).
     */
    @Query("SELECT * FROM permanent_memory WHERE scope = :scope AND category = :category AND key = :key")
    suspend fun getByScopeCategoryAndKey(scope: String, category: String, key: String): PermanentMemory?
    
    /**
     * Получает все факты для указанного scope.
     */
    @Query("SELECT * FROM permanent_memory WHERE scope = :scope ORDER BY category, key")
    suspend fun getByScope(scope: String): List<PermanentMemory>
    
    /**
     * Получает все факты для указанного scope и категории.
     */
    @Query("SELECT * FROM permanent_memory WHERE scope = :scope AND category = :category ORDER BY key")
    suspend fun getByScopeAndCategory(scope: String, category: String): List<PermanentMemory>
    
    /**
     * Получает факты, содержащие указанный тег.
     */
    @Query("""
        SELECT * FROM permanent_memory 
        WHERE tags LIKE '%' || :tag || '%'
        ORDER BY confidence DESC, updatedAt DESC
    """)
    suspend fun findByTag(tag: String): List<PermanentMemory>
    
    /**
     * Получает факты, содержащие любой из указанных тегов.
     */
    @Query("""
        SELECT * FROM permanent_memory 
        WHERE tags LIKE '%' || :tag1 || '%'
           OR tags LIKE '%' || :tag2 || '%'
           OR tags LIKE '%' || :tag3 || '%'
        ORDER BY confidence DESC, updatedAt DESC
        LIMIT :limit
    """)
    suspend fun findByAnyTag(tag1: String, tag2: String? = null, tag3: String? = null, limit: Int = 20): List<PermanentMemory>
    
    /**
     * Поиск фактов по ключу, значению или тегам с учётом scope.
     */
    @Query("""
        SELECT * FROM permanent_memory 
        WHERE scope = :scope
          AND (key LIKE '%' || :query || '%' 
           OR value LIKE '%' || :query || '%'
           OR tags LIKE '%' || :query || '%')
        ORDER BY confidence DESC, updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchInScope(scope: String, query: String, limit: Int = 20): List<PermanentMemory>
    
    /**
     * Получает факты для обогащения промпта AI с учётом scope.
     * Приоритет: факты AI (scope='ai'), затем пользовательские (scope='user'), затем глобальные (scope='global').
     */
    @Query("""
        SELECT * FROM permanent_memory 
        WHERE confidence >= 0.7 AND scope != 'project'
        ORDER BY 
            CASE scope
                WHEN 'ai' THEN 1
                WHEN 'user' THEN 2
                WHEN 'global' THEN 3
                ELSE 4
            END,
            confidence DESC,
            updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getFactsForPromptWithScope(limit: Int = 30): List<PermanentMemory>
    
    /**
     * Получает уникальные теги из всех фактов.
     */
    @Query("""
        WITH RECURSIVE split_tags AS (
            SELECT DISTINCT tags FROM permanent_memory WHERE tags IS NOT NULL AND tags != ''
        ),
        tag_list AS (
            SELECT trim(t.value) as tag
            FROM split_tags, json_each('["' || replace(tags, ',', '","') || '"]') t
        )
        SELECT DISTINCT tag FROM tag_list WHERE tag != '' ORDER BY tag
    """)
    suspend fun getAllTags(): List<String>
    
    /**
     * Получает статистику по scope.
     */
    @Query("SELECT scope, COUNT(*) as count FROM permanent_memory GROUP BY scope ORDER BY scope")
    suspend fun getScopeStats(): List<ScopeStats>
    
    // ==================== ДОПОЛНИТЕЛЬНЫЕ МЕТОДЫ ДЛЯ СЕМАНТИЧЕСКОГО ПОИСКА ====================
    
    /**
     * Получает факты по точному совпадению ключа.
     */
    @Query("SELECT * FROM permanent_memory WHERE key = :key ORDER BY confidence DESC, updatedAt DESC")
    suspend fun getByKey(key: String): List<PermanentMemory>
    
    /**
     * Получает факты по категории.
     */
    @Query("SELECT * FROM permanent_memory WHERE category = :category ORDER BY confidence DESC, updatedAt DESC")
    suspend fun getByCategory(category: String): List<PermanentMemory>
    
    /**
     * Ищет факты по ключу (регистронезависимый LIKE).
     */
    @Query("SELECT * FROM permanent_memory WHERE key LIKE '%' || :keyword || '%' ORDER BY confidence DESC LIMIT :limit")
    suspend fun searchByKey(keyword: String, limit: Int = 10): List<PermanentMemory>
    
    /**
     * Ищет факты по значению (регистронезависимый LIKE).
     */
    @Query("SELECT * FROM permanent_memory WHERE value LIKE '%' || :keyword || '%' ORDER BY confidence DESC LIMIT :limit")
    suspend fun searchByValue(keyword: String, limit: Int = 10): List<PermanentMemory>


/**
 * Статистика по scope.
 */
data class ScopeStats(
    val scope: String,
    val count: Int
)

// ==================== МЕТОДЫ ДЛЯ КЭША AI-АНАЛИЗА ЗАПРОСОВ ====================

/**
 * Получает результат анализа запроса из кэша.
 */
@Query("SELECT * FROM query_analysis_cache WHERE query = :query")
suspend fun getQueryAnalysis(query: String): QueryAnalysisResult?

/**
 * Сохраняет результат анализа запроса в кэш.
 */
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun saveQueryAnalysis(analysis: QueryAnalysisResult)

/**
 * Удаляет устаревшие результаты анализа (старше 30 дней).
 */
@Query("DELETE FROM query_analysis_cache WHERE last_used_at < :cutoffDate")
suspend fun deleteStaleQueryAnalyses(cutoffDate: Long)

/**
 * Обновляет время последнего использования анализа.
 */
@Query("UPDATE query_analysis_cache SET last_used_at = :timestamp WHERE query = :query")
suspend fun updateQueryAnalysisLastUsed(query: String, timestamp: Long = System.currentTimeMillis())

/**
 * Получает факты с высокой уверенностью по scope.
 * Используется для общих запросов типа "что знаешь обо мне".
 */
@Query("SELECT * FROM permanent_memory WHERE scope = :scope AND confidence >= 0.8 ORDER BY confidence DESC, updatedAt DESC LIMIT :limit")
suspend fun getHighConfidenceFactsByScope(scope: String, limit: Int = 10): List<PermanentMemory>
    
    // ==================== РАСШИРЕННЫЕ МЕТОДЫ ДЛЯ УПРАВЛЕНИЯ ПАМЯТЬЮ ====================
    
    /**
     * Получает факты с фильтрами для экрана управления.
     */
    @Query("""
        SELECT * FROM permanent_memory 
        WHERE (:scope IS NULL OR scope = :scope)
          AND (:category IS NULL OR category = :category)
          AND confidence >= :minConfidence
          AND (:searchQuery IS NULL OR key LIKE '%' || :searchQuery || '%' OR value LIKE '%' || :searchQuery || '%')
        ORDER BY 
            CASE :sortBy
                WHEN 'key_asc' THEN key
                WHEN 'confidence_desc' THEN confidence
                WHEN 'updated_desc' THEN updatedAt
                WHEN 'category_asc' THEN category
                ELSE updatedAt
            END
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFactsWithFilters(
        scope: String? = null,
        category: String? = null,
        minConfidence: Float = 0.0f,
        searchQuery: String? = null,
        sortBy: String = "updated_desc",
        limit: Int = 100,
        offset: Int = 0
    ): List<PermanentMemory>
    
    /**
     * Получает статистику по постоянной памяти.
     */
    @Query("""
        SELECT 
            COUNT(*) as total,
            AVG(confidence) as avgConfidence,
            COUNT(DISTINCT scope) as scopeCount,
            COUNT(DISTINCT category) as categoryCount
        FROM permanent_memory
    """)
    suspend fun getPermanentMemoryStats(): PermanentMemoryStats
    
    /**
     * Обновляет несколько фактов за одну транзакцию.
     */
    @Update
    suspend fun updateMultipleFacts(facts: List<PermanentMemory>)
    
    /**
     * Удаляет несколько фактов по IDs.
     */
    @Query("DELETE FROM permanent_memory WHERE id IN (:ids)")
    suspend fun deleteMultipleFacts(ids: List<String>)
    
    // ==================== МЕТОДЫ ДЛЯ ДНЕВНОЙ ПАМЯТИ (УПРАВЛЕНИЕ) ====================
    
    /**
     * Получает дневные записи с фильтрами.
     */
    @Query("""
        SELECT * FROM daily_memory 
        WHERE (:date IS NULL OR date = :date)
          AND (:tag IS NULL OR tags LIKE '%' || :tag || '%')
          AND (:searchQuery IS NULL OR content LIKE '%' || :searchQuery || '%')
        ORDER BY date DESC, createdAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getDailyWithFilters(
        date: String? = null,
        tag: String? = null,
        searchQuery: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<DailyMemory>
    
    /**
     * Получает статистику по дневной памяти.
     */
    @Query("""
        SELECT 
            COUNT(*) as totalEntries,
            MIN(date) as earliestDate,
            MAX(date) as latestDate,
            SUM(LENGTH(content)) as totalChars
        FROM daily_memory
    """)
    suspend fun getDailyMemoryStats(): DailyMemoryStats
    
    /**
     * Обновляет несколько дневных записей.
     */
    @Update
    suspend fun updateMultipleDaily(entries: List<DailyMemory>)
    
    /**
     * Удаляет несколько дневных записей по IDs.
     */
    @Query("DELETE FROM daily_memory WHERE id IN (:ids)")
    suspend fun deleteMultipleDaily(ids: List<String>)
    
    /**
     * Экспортирует дневные записи в виде текста.
     */
    @Query("SELECT date, content, tags FROM daily_memory ORDER BY date DESC, createdAt DESC")
    suspend fun exportDailyMemory(): List<DailyExportItem>
}

// ==================== DATA CLASSES ДЛЯ СТАТИСТИКИ ====================

/**
 * Статистика постоянной памяти.
 */
data class PermanentMemoryStats(
    val total: Int,
    val avgConfidence: Float?,
    val scopeCount: Int,
    val categoryCount: Int
)

/**
 * Статистика дневной памяти.
 */
data class DailyMemoryStats(
    val totalEntries: Int,
    val earliestDate: String?,
    val latestDate: String?,
    val totalChars: Long?
)

/**
 * Элемент экспорта дневной памяти.
 */
data class DailyExportItem(
    val date: String,
    val content: String,
    val tags: String
)
