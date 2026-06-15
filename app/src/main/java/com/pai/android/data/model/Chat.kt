package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Чат с историей сообщений.
 */
@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    val title: String = "",
    
    val providerSettingsId: String? = null,
    
    val systemPrompt: String? = null,
    
    /** ID активной роли (ссылка на таблицу roles) */
    val roleId: String? = null,
    
    val metadata: Map<String, String> = emptyMap(),
    
    val createdAt: Long = System.currentTimeMillis(),
    
    val updatedAt: Long = System.currentTimeMillis(),
    
    val messageCount: Int = 0,
    
    val isArchived: Boolean = false
) {
    companion object {
        fun createNew(title: String = "Новый чат"): Chat {
            return Chat(title = title)
        }
        
        /**
         * Генерирует осмысленный заголовок чата на основе текста сообщения.
         * @param messageText Текст сообщения для анализа
         * @param maxWords Максимальное количество слов в заголовке (по умолчанию 6)
         * @param maxLength Максимальная длина заголовка в символах (по умолчанию 40)
         */
        fun generateTitleFromMessage(messageText: String, maxWords: Int = 6, maxLength: Int = 40): String {
            if (messageText.isBlank()) return "Новый чат"
            
            // Очищаем текст: удаляем лишние пробелы, переносы строк
            val cleanedText = messageText.trim().replace("\\s+".toRegex(), " ")
            
            // Разбиваем на слова
            val words = cleanedText.split(" ").filter { it.isNotBlank() }
            
            // Берем первые maxWords слов
            val titleWords = if (words.size > maxWords) {
                words.take(maxWords)
            } else {
                words
            }
            
            // Собираем обратно в строку
            var title = titleWords.joinToString(" ")
            
            // Обрезаем до максимальной длины, если нужно
            if (title.length > maxLength) {
                title = title.take(maxLength).trim()
                // Убираем обрезанное слово в конце (если обрезали посередине слова)
                val lastSpace = title.lastIndexOf(' ')
                if (lastSpace > 0) {
                    title = title.take(lastSpace)
                }
                title += "…"
            }
            
            // Убираем лишние знаки препинания в конце
            title = title.trimEnd { it in ".,;:!?" }
            
            // Если в итоге пустая строка, возвращаем заголовок по умолчанию
            return if (title.isBlank()) "Новый чат" else title
        }
    }
    
    fun updateTitle(newTitle: String): Chat {
        return this.copy(title = newTitle, updatedAt = System.currentTimeMillis())
    }
    
    fun incrementMessageCount(): Chat {
        return this.copy(messageCount = messageCount + 1, updatedAt = System.currentTimeMillis())
    }
    
    fun updateSystemPrompt(prompt: String?): Chat {
        return this.copy(systemPrompt = prompt, updatedAt = System.currentTimeMillis())
    }
    
    fun updateRoleId(roleId: String?): Chat {
        return this.copy(roleId = roleId, updatedAt = System.currentTimeMillis())
    }
    
    /**
     * Проверяет, является ли чат "новым" (имеет заголовок по умолчанию).
     */
    fun isDefaultTitle(): Boolean {
        return title.isEmpty() || title == "Новый чат"
    }
}