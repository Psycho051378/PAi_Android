package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import java.util.UUID

/**
 * Тип суммаризации.
 */
enum class SummaryType {
    CLUSTER,    // Суммаризация кластера сообщений (20-30 сообщений)
    DAILY,      // Ежедневная суммаризация чата
    WEEKLY,     // Еженедельная суммаризация
    TOPIC       // Тематическая суммаризация
}

/**
 * Суммаризация диалога.
 * Представляет собой сжатое представление группы сообщений.
 */
@Entity(
    tableName = "summaries",
    foreignKeys = [
        ForeignKey(
            entity = Chat::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["type"]),
        Index(value = ["createdAt"])
    ]
)
data class Summary(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    val chatId: String,
    
    val type: SummaryType,
    
    val content: String,
    
    /** ID сообщений, которые вошли в эту суммаризацию */
    val messageIds: List<String> = emptyList(),
    
    /** Коэффициент сжатия (сообщений : суммаризация) */
    val compressionRatio: Float = 1.0f,
    
    /** Дата создания суммаризации */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Теги для категоризации (например, ["project_discussion", "planning"]) */
    val tags: List<String> = emptyList(),
    
    /** Оценка качества суммаризации (если доступно) */
    val qualityScore: Float? = null
) {
    companion object {
        fun createClusterSummary(
            chatId: String,
            content: String,
            messageIds: List<String>,
            tags: List<String> = emptyList()
        ): Summary {
            val ratio = if (messageIds.isNotEmpty()) messageIds.size.toFloat() else 1.0f
            return Summary(
                chatId = chatId,
                type = SummaryType.CLUSTER,
                content = content,
                messageIds = messageIds,
                compressionRatio = ratio,
                tags = tags
            )
        }
        
        fun createDailySummary(
            chatId: String,
            content: String,
            messageIds: List<String>,
            date: String
        ): Summary {
            return Summary(
                chatId = chatId,
                type = SummaryType.DAILY,
                content = content,
                messageIds = messageIds,
                tags = listOf("daily", date)
            )
        }
    }
}