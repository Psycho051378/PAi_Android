package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Постоянная память — ключевые факты о пользователе, AI и общем контексте.
 * Аналог MEMORY.md + USER.md + IDENTITY.md в OpenClaw, адаптированный для гибкой схемы.
 */
@Entity(
    tableName = "permanent_memory",
    indices = [
        Index(value = ["category"]),
        Index(value = ["key"]),
        Index(value = ["confidence"]),
        Index(value = ["scope"]),
        Index(value = ["tags"]),
        Index(value = ["category", "key"], unique = true),
        Index(value = ["scope", "category", "key"], unique = true)
    ]
)
data class PermanentMemory(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    /**
     * Категория факта.
     * Примеры: "user_info", "preferences", "skills", "important_decisions", "ai_info"
     */
    val category: String,
    
    /**
     * Ключ факта (что).
     * Примеры: "name", "birth_date", "profession", "favorite_color", "role", "instructions"
     */
    val key: String,
    
    /**
     * Fact value (what exactly).
     * Examples: "John Smith", "1990-03-15", "Android Developer", "blue", "assistant"
     */
    val value: String,
    
    /**
     * Уверенность AI в факте (0.0 - 1.0).
     * 1.0 = подтверждён пользователем, 0.5 = предположение AI
     */
    val confidence: Float = 0.8f,
    
    /**
     * Область применения факта:
     * - "user": информация о пользователе (USER.md)
     * - "ai": информация об AI (IDENTITY.md, инструкции)
     * - "global": общая информация (MEMORY.md, проекты, решения)
     */
    val scope: String = "user",
    
    /**
     * Теги для семантического поиска (через запятую).
     * Примеры: "важное,личное,работа", "проекты,код,android"
     */
    val tags: String? = null,
    
    /**
     * Дополнительные метаданные в формате JSON.
     * Может содержать: источник, контекст, версию, ссылки и т.д.
     */
    val metadata: String? = null,
    
    /**
     * ID чата, из которого извлечён факт (если применимо).
     */
    val sourceChatId: String? = null,
    
    /**
     * ID сообщения, из которого извлечён факт (если применимо).
     */
    val sourceMessageId: String? = null,
    
    /**
     * Время создания записи (timestamp).
     */
    val createdAt: Long = System.currentTimeMillis(),
    
    /**
     * Время последнего обновления записи (timestamp).
     */
    val updatedAt: Long = System.currentTimeMillis()
) {
    
    companion object {
        /**
         * Создаёт факт с высокой уверенностью (подтверждённый пользователем).
         * Старая сигнатура для обратной совместимости.
         */
        fun createConfirmed(
            category: String,
            key: String,
            value: String,
            sourceChatId: String? = null,
            sourceMessageId: String? = null
        ): PermanentMemory {
            return createConfirmedFull(
                category = category,
                key = key,
                value = value,
                scope = Scopes.USER,
                tags = null,
                metadata = null,
                sourceChatId = sourceChatId,
                sourceMessageId = sourceMessageId
            )
        }
        
        /**
         * Создаёт факт с низкой уверенностью (предположение AI).
         * Старая сигнатура для обратной совместимости.
         */
        fun createAssumed(
            category: String,
            key: String,
            value: String,
            confidence: Float = 0.5f,
            sourceChatId: String? = null,
            sourceMessageId: String? = null
        ): PermanentMemory {
            require(confidence in 0.0f..1.0f) { "Confidence must be between 0.0 and 1.0" }
            return createAssumedFull(
                category = category,
                key = key,
                value = value,
                confidence = confidence,
                scope = Scopes.USER,
                tags = null,
                metadata = null,
                sourceChatId = sourceChatId,
                sourceMessageId = sourceMessageId
            )
        }
        
        /**
         * Создаёт факт с высокой уверенностью и полным набором параметров.
         */
        fun createConfirmedFull(
            category: String,
            key: String,
            value: String,
            scope: String = Scopes.USER,
            tags: String? = null,
            metadata: Map<String, Any>? = null,
            sourceChatId: String? = null,
            sourceMessageId: String? = null
        ): PermanentMemory {
            require(Scopes.isValid(scope)) { "Invalid scope: $scope. Must be one of: ${Scopes.ALL}" }
            return PermanentMemory(
                category = category,
                key = key,
                value = value,
                confidence = 1.0f,
                scope = scope,
                tags = tags,
                metadata = metadata?.let { Gson().toJson(it) },
                sourceChatId = sourceChatId,
                sourceMessageId = sourceMessageId
            )
        }
        
        /**
         * Создаёт факт с низкой уверенностью и полным набором параметров.
         */
        fun createAssumedFull(
            category: String,
            key: String,
            value: String,
            confidence: Float = 0.5f,
            scope: String = Scopes.USER,
            tags: String? = null,
            metadata: Map<String, Any>? = null,
            sourceChatId: String? = null,
            sourceMessageId: String? = null
        ): PermanentMemory {
            require(confidence in 0.0f..1.0f) { "Confidence must be between 0.0 and 1.0" }
            require(Scopes.isValid(scope)) { "Invalid scope: $scope. Must be one of: ${Scopes.ALL}" }
            return PermanentMemory(
                category = category,
                key = key,
                value = value,
                confidence = confidence,
                scope = scope,
                tags = tags,
                metadata = metadata?.let { Gson().toJson(it) },
                sourceChatId = sourceChatId,
                sourceMessageId = sourceMessageId
            )
        }
        
        /**
         * Категории для удобства использования.
         */
        object Categories {
            const val USER_INFO = "user_info"
            const val PREFERENCES = "preferences"
            const val SKILLS = "skills"
            const val IMPORTANT_DECISIONS = "important_decisions"
            const val RELATIONSHIPS = "relationships"
            const val LOCATIONS = "locations"
            const val WORK = "work"
            const val EDUCATION = "education"
            const val HEALTH = "health"
            const val OTHER = "other"
        }
        
        /**
         * Ключи для категории user_info.
         */
        object UserInfoKeys {
            const val NAME = "name"
            const val BIRTH_DATE = "birth_date"
            const val AGE = "age"
            const val GENDER = "gender"
            const val NATIONALITY = "nationality"
            const val LOCATION = "location"
            const val PROFESSION = "profession"
            const val COMPANY = "company"
        }
        
        /**
         * Ключи для категории preferences.
         */
        object PreferenceKeys {
            const val FAVORITE_COLOR = "favorite_color"
            const val FAVORITE_FOOD = "favorite_food"
            const val FAVORITE_MUSIC = "favorite_music"
            const val FAVORITE_MOVIE = "favorite_movie"
            const val PROGRAMMING_LANGUAGE = "programming_language"
            const val IDE = "ide"
            const val OS = "os"
        }
        
        /**
         * Области применения (scope) фактов.
         */
        object Scopes {
            const val USER = "user"        // Информация о пользователе (USER.md)
            const val AI = "ai"            // Информация об AI (IDENTITY.md, инструкции)
            const val GLOBAL = "global"    // Общая информация (MEMORY.md, проекты, решения)
            const val PROJECT = "project"       // Данные проектов
            
            /**
             * Все допустимые области.
             */
            val ALL = listOf(USER, AI, GLOBAL, PROJECT)
            
            /**
             * Проверяет, является ли значение допустимой областью.
             */
            fun isValid(scope: String): Boolean = ALL.contains(scope)
        }
        
        /**
         * Категории для AI информации.
         */
        object AiInfoCategories {
            const val AI_INFO = "ai_info"
            
            object Keys {
                const val NAME = "name"
                const val ROLE = "role"
                const val INSTRUCTIONS = "instructions"
                const val PERSONALITY = "personality"
                const val BEHAVIOR = "behavior"
                const val RESPONSE_STYLE = "response_style"
                const val MEMORY_PREFERENCES = "memory_preferences"
            }
        }
    }
    
    /**
     * Обновляет значение факта (увеличивает updatedAt).
     */
    fun updateValue(newValue: String, newConfidence: Float? = null): PermanentMemory {
        return this.copy(
            value = newValue,
            confidence = newConfidence ?: confidence,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Увеличивает уверенность в факте.
     */
    fun increaseConfidence(amount: Float = 0.1f): PermanentMemory {
        val newConfidence = (confidence + amount).coerceIn(0.0f, 1.0f)
        return this.copy(
            confidence = newConfidence,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Обновляет scope факта.
     */
    fun updateScope(newScope: String): PermanentMemory {
        require(Scopes.isValid(newScope)) { "Invalid scope: $newScope" }
        return this.copy(
            scope = newScope,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Обновляет теги факта.
     */
    fun updateTags(newTags: String?): PermanentMemory {
        return this.copy(
            tags = newTags,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Добавляет теги к существующим (через запятую).
     */
    fun addTags(vararg additionalTags: String): PermanentMemory {
        val currentTags = tags?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val newTagsSet = (currentTags + additionalTags).toSet()
        val newTagsStr = newTagsSet.joinToString(",")
        return updateTags(newTagsStr)
    }
    
    /**
     * Обновляет метаданные (полная замена).
     */
    fun updateMetadata(newMetadata: Map<String, Any>?): PermanentMemory {
        return this.copy(
            metadata = newMetadata?.let { Gson().toJson(it) },
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Добавляет или обновляет поле в метаданных.
     */
    fun putMetadata(key: String, value: Any): PermanentMemory {
        val currentMetadata = parseMetadata().toMutableMap()
        currentMetadata[key] = value
        return updateMetadata(currentMetadata)
    }
    
    /**
     * Парсит метаданные из JSON строки в Map.
     */
    fun parseMetadata(): Map<String, Any> {
        return if (metadata.isNullOrEmpty()) {
            emptyMap()
        } else {
            try {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                Gson().fromJson(metadata, type) ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
    
    /**
     * Проверяет, содержит ли факт указанный тег.
     */
    fun hasTag(tag: String): Boolean {
        if (tags.isNullOrEmpty()) return false
        return tags.split(",").any { it.trim().equals(tag, ignoreCase = true) }
    }
    
    /**
     * Возвращает список тегов.
     */
    fun getTagList(): List<String> {
        if (tags.isNullOrEmpty()) return emptyList()
        return tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    
    /**
     * Проверяет, соответствует ли факт области (scope).
     */
    fun isScope(scope: String): Boolean = this.scope == scope
    
    /**
     * Форматирует факт для отображения.
     */
    fun toDisplayString(): String {
        val scopePrefix = when (scope) {
            Scopes.USER -> "👤"
            Scopes.AI -> "🤖"
            Scopes.GLOBAL -> "🌍"
            else -> "•"
        }
        return "$scopePrefix $key: $value (${(confidence * 100).toInt()}% уверенности)"
    }
    
    /**
     * Форматирует факт для отладки.
     */
    fun toDebugString(): String {
        return "PermanentMemory(id=$id, category=$category, key=$key, value=$value, " +
               "confidence=$confidence, scope=$scope, tags=$tags, " +
               "metadata=${metadata?.take(50)}..., createdAt=$createdAt, updatedAt=$updatedAt)"
    }
}