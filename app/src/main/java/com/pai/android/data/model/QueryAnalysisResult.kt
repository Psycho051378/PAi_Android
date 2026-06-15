package com.pai.android.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Результат AI-анализа пользовательского запроса для семантического поиска.
 * Используется для определения, какие факты из памяти релевантны запросу.
 */
@Entity(tableName = "query_analysis_cache")
data class QueryAnalysisResult(
    @PrimaryKey
    val query: String,
    
    /**
     * Извлечённые ключевые слова из запроса.
     */
    val keywords: List<String>,
    
    /**
     * Предполагаемые ключи фактов, которые могут быть релевантны.
     * Например: ["name", "location", "birth_date"]
     */
    @ColumnInfo(name = "suggested_keys")
    val suggestedKeys: List<String>,
    
    /**
     * Предполагаемые категории фактов.
     * Например: ["personal_info", "contacts", "preferences"]
     */
    @ColumnInfo(name = "suggested_categories")
    val suggestedCategories: List<String>,
    
    /**
     * Предполагаемый scope (контекст) запроса.
     * Возможные значения: "user", "ai", "global"
     */
    @ColumnInfo(name = "suggested_scope")
    val suggestedScope: String,
    
    /**
     * Уверенность анализа (0.0 - 1.0).
     */
    val confidence: Float,
    
    /**
     * Дата и время создания анализа (timestamp в миллисекундах).
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    /**
     * Дата и время последнего использования (timestamp в миллисекундах).
     */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long = System.currentTimeMillis()
) {
    /**
     * Проверяет, является ли анализ устаревшим.
     * Анализ считается устаревшим через 30 дней после последнего использования.
     */
    fun isStale(): Boolean {
        val thirtyDaysInMillis = 30L * 24 * 60 * 60 * 1000
        return System.currentTimeMillis() - lastUsedAt > thirtyDaysInMillis
    }
    
    /**
     * Обновляет время последнего использования.
     */
    fun markUsed(): QueryAnalysisResult {
        return this.copy(lastUsedAt = System.currentTimeMillis())
    }
    
    companion object {
        /**
         * Создаёт пустой результат анализа (для случаев, когда анализ невозможен).
         */
        fun empty(query: String): QueryAnalysisResult {
            return QueryAnalysisResult(
                query = query,
                keywords = emptyList(),
                suggestedKeys = emptyList(),
                suggestedCategories = emptyList(),
                suggestedScope = "user",
                confidence = 0.0f
            )
        }
    }
}