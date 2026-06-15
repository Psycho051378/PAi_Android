package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * Дневная память — сырые записи за день в стиле OpenClaw.
 * Аналог файла memory/YYYY-MM-DD.md
 */
@Entity(
    tableName = "daily_memory",
    indices = [
        Index(value = ["date"], unique = true)
    ]
)
data class DailyMemory(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    /**
     * Дата в формате YYYY-MM-DD.
     * Пример: "2026-04-19"
     */
    val date: String,
    
    /**
     * Содержимое дневной записи (сырой текст).
     * Может содержать markdown-разметку.
     */
    val content: String,
    
    /**
     * Время создания записи (timestamp).
     */
    val createdAt: Long = System.currentTimeMillis(),
    
    /**
     * Теги для категоризации (через запятую).
     * Пример: "work,personal,ideas"
     */
    val tags: String = ""
) {
    
    companion object {
        /**
         * Создаёт новую дневную запись для текущей даты.
         */
        fun createForToday(content: String, tags: List<String> = emptyList()): DailyMemory {
            val today = SimpleDateFormat("yyyy-MM-dd").format(Date())
            return DailyMemory(
                date = today,
                content = content,
                tags = tags.joinToString(",")
            )
        }
        
        /**
         * Проверяет, является ли запись сегодняшней.
         */
        fun isToday(dailyMemory: DailyMemory): Boolean {
            val today = SimpleDateFormat("yyyy-MM-dd").format(Date())
            return dailyMemory.date == today
        }
    }
    
    /**
     * Получает теги как список строк.
     */
    fun getTagsList(): List<String> {
        return if (tags.isBlank()) emptyList()
        else tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    
    /**
     * Добавляет новый тег.
     */
    fun addTag(tag: String): DailyMemory {
        val currentTags = getTagsList().toMutableSet()
        currentTags.add(tag.trim())
        return this.copy(tags = currentTags.joinToString(","))
    }
    
    /**
     * Добавляет текст к существующему содержимому.
     */
    fun appendContent(newContent: String): DailyMemory {
        val separator = if (content.isNotBlank() && !content.endsWith("\n")) "\n\n" else ""
        return this.copy(content = content + separator + newContent)
    }
}