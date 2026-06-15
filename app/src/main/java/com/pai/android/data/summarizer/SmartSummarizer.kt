package com.pai.android.data.summarizer

import com.pai.android.data.model.Message
import com.pai.android.data.model.Summary
import com.pai.android.data.model.SummaryType
import com.pai.android.data.repository.AiRepository
import javax.inject.Inject

/**
 * Умный суммаризатор диалогов с использованием AI.
 */
class SmartSummarizer @Inject constructor(
    private val aiRepository: AiRepository
) {
    
    /**
     * Суммаризирует кластер сообщений.
     * @param messages Сообщения для суммаризации (15-30 сообщений оптимально)
     * @param chatId ID чата
     * @param contextTags Дополнительные теги контекста
     * @return Summary объект или null, если суммаризация не удалась
     */
    suspend fun summarizeCluster(
        messages: List<Message>,
        chatId: String,
        contextTags: List<String> = emptyList()
    ): Summary? {
        if (messages.size < 10) {
            // Слишком мало сообщений для качественной суммаризации
            return null
        }
        
        try {
            // Формируем промпт для AI-суммаризации
            val prompt = buildSummarizationPrompt(messages)
            
            // Отправляем запрос к AI
            val result = aiRepository.sendMessage(
                messages = listOf(Message.createUserMessage("summarization", prompt)),
                systemPrompt = SYSTEM_PROMPT,
                modelOverride = "gpt-3.5-turbo"  // Используем более дешёвую модель для суммаризации
            )
            
            if (result.isSuccess) {
                val summaryContent = result.getOrThrow().text
                
                return Summary.createClusterSummary(
                    chatId = chatId,
                    content = summaryContent,
                    messageIds = messages.map { it.id },
                    tags = contextTags
                )
            }
        } catch (e: Exception) {
            // Логируем ошибку, но не прерываем работу
            println("⚠️ Ошибка суммаризации кластера: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Создаёт ежедневную суммаризацию чата.
     */
    suspend fun createDailySummary(
        chatId: String,
        messages: List<Message>,
        date: String
    ): Summary? {
        if (messages.isEmpty()) return null
        
        try {
            val prompt = buildDailySummaryPrompt(messages, date)
            
            val result = aiRepository.sendMessage(
                messages = listOf(Message.createUserMessage("summarization", prompt)),
                systemPrompt = SYSTEM_PROMPT,
                modelOverride = "gpt-3.5-turbo"
            )
            
            if (result.isSuccess) {
                val summaryContent = result.getOrThrow().text
                
                return Summary.createDailySummary(
                    chatId = chatId,
                    content = summaryContent,
                    messageIds = messages.map { it.id },
                    date = date
                )
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка создания ежедневной суммаризации: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Извлекает ключевые факты из суммаризации.
     * Может использоваться для обогащения постоянной памяти.
     */
    suspend fun extractFactsFromSummary(summary: Summary): List<Fact> {
        // TODO: Реализовать извлечение фактов из текста суммаризации
        // Например, с помощью дополнительного AI-запроса
        return emptyList()
    }
    
    /**
     * Оценивает качество суммаризации.
     * @return Оценка от 0.0 до 1.0 или null, если оценка невозможна
     */
    suspend fun evaluateSummaryQuality(summary: Summary): Float? {
        // TODO: Реализовать оценку качества (например, через сравнение с оригиналом)
        return null
    }
    
    /**
     * Создаёт промпт для суммаризации кластера.
     */
    private fun buildSummarizationPrompt(messages: List<Message>): String {
        val dialogText = messages.joinToString("\n") { msg ->
            val role = when (msg.role) {
                com.pai.android.data.model.MessageRole.USER -> "👤 Пользователь"
                com.pai.android.data.model.MessageRole.ASSISTANT -> "🤖 Ассистент"
                com.pai.android.data.model.MessageRole.SYSTEM -> "⚙️ Система"
            }
            "$role: ${msg.content}"
        }
        
        return """
            Проанализируй этот диалог и создай краткую суммаризацию.
            
            **Структура суммаризации:**
            
            1. **Основные темы** (2-3 ключевые темы обсуждения)
            2. **Ключевые выводы/решения** (что было решено или выяснено)
            3. **Действия к выполнению** (если есть конкретные задачи)
            4. **Контекст для будущих бесед** (важная информация о пользователе или ситуации)
            
            **Требования:**
            - Будь максимально кратким, но информативным
            - Используй маркированные списки для наглядности
            - Сохрани важные детали (имена, даты, цифры)
            - Избегай общих фраз
            - Пиши на русском языке
            
            **Диалог для анализа:**
            $dialogText
            
            **Твоя суммаризация:**
        """.trimIndent()
    }
    
    /**
     * Создаёт промпт для ежедневной суммаризации.
     */
    private fun buildDailySummaryPrompt(messages: List<Message>, date: String): String {
        // Группируем сообщения по темам (простая эвристика)
        val topics = identifyTopics(messages)
        
        return """
            Создай ежедневную суммаризацию чата за $date.
            
            Всего сообщений: ${messages.size}
            Примерные темы: ${topics.joinToString(", ")}
            
            **Структура суммаризации:**
            
            ## 📊 Обзор дня
            - Активность: количество сообщений, время первой/последней активности
            - Настроение: общий тон обсуждения
            - Продуктивность: были ли приняты решения, выполнены задачи
            
            ## 🎯 Ключевые моменты
            (3-5 самых важных моментов дня)
            
            ## 📝 Контекст для будущего
            (Что стоит запомнить о пользователе или проектах)
            
            **Требования:**
            - Будь профессиональным, но дружелюбным
            - Выдели действительно важное
            - Используй эмодзи для наглядности
            - Пиши на русском языке
            
            **Твоя суммаризация:**
        """.trimIndent()
    }
    
    /**
     * Простая идентификация тем в сообщениях.
     */
    private fun identifyTopics(messages: List<Message>): List<String> {
        val topicKeywords = mapOf(
            "проект" to "Проекты",
            "задача" to "Задачи",
            "код" to "Разработка",
            "ошибка" to "Проблемы",
            "идея" to "Идеи",
            "плани" to "Планирование",
            "вопрос" to "Вопросы",
            "ответ" to "Обсуждения"
        )
        
        val foundTopics = mutableSetOf<String>()
        val content = messages.joinToString(" ") { it.content.lowercase() }
        
        topicKeywords.forEach { (keyword, topic) ->
            if (content.contains(keyword)) {
                foundTopics.add(topic)
            }
        }
        
        return foundTopics.toList()
    }
    
    companion object {
        /** Системный промпт для суммаризатора */
        private val SYSTEM_PROMPT = """
            Ты — профессиональный суммаризатор диалогов.
            Твоя задача — анализировать беседы и выделять самое важное.
            
            Правила:
            1. Будь объективным — не добавляй свои интерпретации
            2. Будь краткими — используй 150-300 слов
            3. Будь структурированным — используй заголовки и списки
            4. Сохраняй факты — имена, даты, цифры, решения
            5. Игнорируй шум — приветствия, прощания, технические детали
            
            Формат ответа — разметка Markdown с заголовками уровня 2-3.
        """.trimIndent()
    }
    
    /** Вспомогательный класс для фактов */
    data class Fact(
        val category: String,
        val key: String,
        val value: String,
        val confidence: Float
    )
}