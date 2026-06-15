package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Роль для AI модели, определяющая поведение и характер ответов через системный промпт.
 * Роли можно добавлять, редактировать, удалять и активировать через выпадающее меню в чате.
 */
@Entity(tableName = "roles")
data class Role(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    /** Название роли (например, "Ассистент", "Разработчик", "Аналитик") */
    val name: String,
    
    /** Описание роли (необязательное) */
    val description: String? = null,
    
    /** Системный промпт, определяющий поведение и характер ответов AI */
    val systemPrompt: String,
    
    /** Креативность/случайность ответов (0.0 - детерминировано, 2.0 - очень креативно) */
    val temperature: Float? = null,
    
    /** Максимальное количество токенов в ответе */
    val maxTokens: Int? = null,
    
    /** Дата создания роли */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Роль по умолчанию (используется для новых чатов) */
    val isDefault: Boolean = false
) {
    companion object {
        /** Создаёт стандартные роли для приложения */
        fun createDefaultRoles(): List<Role> {
            return listOf(
                Role(
                    name = "Ассистент",
                    description = "Помощник для общих вопросов и задач",
                    systemPrompt = "Ты полезный AI ассистент. Отвечай на вопросы ясно и по делу.",
                    temperature = 0.7f,
                    maxTokens = 2000,
                    isDefault = true
                ),
                Role(
                    name = "Разработчик",
                    description = "Помощь в программировании и технических вопросах",
                    systemPrompt = "Ты опытный разработчик. Помогай с кодом, архитектурой и решением технических проблем. Предоставляй примеры кода и лучшие практики.",
                    temperature = 0.3f,
                    maxTokens = 4000
                ),
                Role(
                    name = "Аналитик",
                    description = "Анализ данных, стратегии и принятие решений",
                    systemPrompt = "Ты аналитик. Помогай анализировать данные, выявлять тренды, предлагать стратегии. Будь точным и логичным.",
                    temperature = 0.5f,
                    maxTokens = 3000
                ),
                Role(
                    name = "Креативщик",
                    description = "Генерация креативного контента, идей и текстов",
                    systemPrompt = "Ты креативный помощник. Помогай генерировать идеи, писать тексты, создавать контент. Будь оригинальным и вдохновляющим.",
                    temperature = 0.9f,
                    maxTokens = 2500
                )
            )
        }
    }
    
    /** Обновляет роль с новыми значениями */
    fun update(
        name: String? = null,
        description: String? = null,
        systemPrompt: String? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        isDefault: Boolean? = null
    ): Role {
        return this.copy(
            name = name ?: this.name,
            description = description ?: this.description,
            systemPrompt = systemPrompt ?: this.systemPrompt,
            temperature = temperature ?: this.temperature,
            maxTokens = maxTokens ?: this.maxTokens,
            isDefault = isDefault ?: this.isDefault
        )
    }
}