package com.pai.android.data.processor

import com.pai.android.data.detector.FactExtractor
import com.pai.android.data.detector.SignificanceDetector
import com.pai.android.data.model.Message
import com.pai.android.data.model.Summary
import com.pai.android.data.repository.ChatRepository
import com.pai.android.data.repository.MemoryRepository
import com.pai.android.data.repository.SummaryRepository
import com.pai.android.data.service.DailyMemoryService
import com.pai.android.data.summarizer.SmartSummarizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Процессор сообщений для автоматической обработки памяти.
 * 
 * Обрабатывает каждое сообщение пользователя:
 * 1. Детектирует значимость
 * 2. Извлекает факты (при необходимости)
 * 3. Проверяет триггеры суммаризации
 * 4. Обновляет контекстное окно
 */
@Singleton
class MessageProcessor @Inject constructor(
    private val significanceDetector: SignificanceDetector,
    private val factExtractor: FactExtractor,
    private val memoryRepository: MemoryRepository,
    private val chatRepository: ChatRepository,
    private val summaryRepository: SummaryRepository,
    private val summarizer: SmartSummarizer,
    private val dailyMemoryService: DailyMemoryService
) {
    
    private val processorScope = CoroutineScope(Dispatchers.IO)
    
    // Конфигурация
    private companion object {
        const val CLUSTER_SIZE_THRESHOLD = 20  // Минимум сообщений для кластерной суммаризации
        const val MAX_DAYS_FOR_DAILY_SUMMARY = 1  // Максимальный возраст для ежедневной суммаризации
    }
    
    /**
     * Обрабатывает новое сообщение пользователя.
     * Выполняется асинхронно в фоновом потоке.
     */
    fun processUserMessage(chatId: String, message: Message) {
        processorScope.launch {
            try {
                // 1. Сохраняем сообщение (уже сохранено через ChatRepository, но можем обновить метаданные)
                updateMessageMetadata(message)
                
                // 2. Сохраняем значимые события в дневную память
                dailyMemoryService.processMessageForDailyMemory(message)
                
                // 3. Проверяем на critical/high significance
                if (significanceDetector.shouldExtractFactsImmediately(message)) {
                    extractFactsImmediately(message)
                }
                
                // 4. Проверяем триггеры суммаризации
                checkSummarizationTriggers(chatId)
                
                // 5. Проверяем необходимость ежедневной суммаризации
                checkDailySummary(chatId)
                
                // 6. Логируем обработку
                println("✅ Сообщение обработано: chat=$chatId, significance=${significanceDetector.evaluateSignificanceScore(message)}")
                
            } catch (e: Exception) {
                // Не прерываем основной поток при ошибках обработки памяти
                println("⚠️ Ошибка обработки сообщения: ${e.message}")
            }
        }
    }
    
    /**
     * Обновляет метаданные сообщения (например, флаг processedForMemory).
     */
    private suspend fun updateMessageMetadata(message: Message) {
        // TODO: Реализовать обновление метаданных в базе данных
        // Пока что просто логируем
        println("📝 Обновление метаданных сообщения: ${message.id}")
    }
    
    /**
     * Немедленное извлечение фактов из high-significance сообщения.
     */
    private suspend fun extractFactsImmediately(message: Message) {
        println("🔍 Немедленное извлечение фактов из сообщения: ${message.content.take(50)}...")
        
        val significanceScore = significanceDetector.evaluateSignificanceScore(message)
        println("   Оценка значимости: $significanceScore/10")
        
        if (significanceScore >= 7) {
            // Извлекаем факты через FactExtractor
            val facts = factExtractor.extractFacts(message)
            
            if (facts.isNotEmpty()) {
                println("   📊 Найдено фактов: ${facts.size}")
                
                // Фильтрация некорректных фактов (особенно имён)
                val filteredFacts = facts.filterNot { fact ->
                    // Фильтр для некорректных имён
                    if (fact.key == "name") {
                        val lowerValue = fact.value.lowercase()
                        val incorrectValues = listOf("зовут", "родился", "родилась", "я", "живу", "работаю", "запомни", "меня", "мое", "моё", "своё", "свое")
                        
                        // Проверяем на запрещённые значения и слишком короткие имена
                        if (lowerValue in incorrectValues || fact.value.length < 2) {
                            println("   ❌ Отфильтрован некорректный факт имени: ${fact.value}")
                            return@filterNot true
                        }
                        
                        // Проверяем, что значение не является глаголом (оканчивается на "лся", "лась")
                        if (lowerValue.endsWith("лся") || lowerValue.endsWith("лась")) {
                            println("   ❌ Отфильтрован факт имени-глагола: ${fact.value}")
                            return@filterNot true
                        }
                    }
                    false
                }
                
                // Сохраняем извлечённые факты в дневную память
                dailyMemoryService.saveExtractedFacts(filteredFacts)
                
                // Факты уже сохранены в дневную память выше — permanent не дублируем
            } else {
                println("   ℹ️ Факты не обнаружены")
            }
        } else {
            println("   ⏭️ Пропуск: недостаточная значимость ($significanceScore < 7)")
        }
    }
    
    /**
     * Проверяет триггеры для кластерной суммаризации.
     */
    private suspend fun checkSummarizationTriggers(chatId: String) {
        // 1. Проверяем, нужно ли создавать новую суммаризацию по времени
        val needsByTime = summaryRepository.needsNewSummary(
            chatId = chatId,
            minMessages = CLUSTER_SIZE_THRESHOLD,
            maxAgeDays = 1
        )
        
        if (!needsByTime) {
            return
        }
        
        // 2. Получаем непросуммаризированные сообщения
        val unsummarizedMessages = getUnsummarizedMessages(chatId, CLUSTER_SIZE_THRESHOLD)
        
        if (unsummarizedMessages.size >= CLUSTER_SIZE_THRESHOLD) {
            // 3. Создаём кластерную суммаризацию
            createClusterSummary(chatId, unsummarizedMessages)
        }
    }
    
    /**
     * Получает сообщения, которые ещё не были суммаризированы.
     */
    private suspend fun getUnsummarizedMessages(chatId: String, limit: Int): List<Message> {
        // TODO: Реализовать логику получения непросуммаризированных сообщений
        // Пока что возвращаем последние N сообщений
        return chatRepository.getMessages(chatId, limit)
            .filter { it.isFromUser() || it.isFromAssistant() }
            .take(limit)
    }
    
    /**
     * Создаёт кластерную суммаризацию.
     */
    private suspend fun createClusterSummary(chatId: String, messages: List<Message>) {
        println("🧠 Создание кластерной суммаризации для чата $chatId (${messages.size} сообщений)")
        
        val summary = summarizer.summarizeCluster(messages, chatId)
        
        if (summary != null) {
            summaryRepository.insert(summary)
            println("✅ Кластерная суммаризация создана: ${summary.content.take(100)}...")
            
            // Помечаем сообщения как суммаризированные
            markMessagesAsSummarized(messages, summary.id)
            
            // Извлекаем факты из суммаризации
            extractFactsFromSummary(summary)
        } else {
            println("⚠️ Не удалось создать кластерную суммаризацию")
        }
    }
    
    /**
     * Помечает сообщения как суммаризированные.
     */
    private suspend fun markMessagesAsSummarized(messages: List<Message>, summaryId: String) {
        // TODO: Реализовать обновление сообщений в базе данных
        // Например, добавить поле clusterId или summaryId
        println("   Помечено как суммаризированные: ${messages.size} сообщений")
    }
    
    /**
     * Извлекает факты из суммаризации.
     */
    private suspend fun extractFactsFromSummary(summary: Summary) {
        // TODO: Реализовать извлечение фактов с помощью SmartSummarizer
        val facts = summarizer.extractFactsFromSummary(summary)
        if (facts.isNotEmpty()) {
            println("   Извлечено фактов из суммаризации: ${facts.size}")
        }
    }
    
    /**
     * Проверяет необходимость создания ежедневной суммаризации.
     */
    private suspend fun checkDailySummary(chatId: String) {
        // TODO: Реализовать проверку по времени (например, в 02:00)
        // Пока что просто логируем
        val today = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())
        println("📅 Проверка ежедневной суммаризации для $chatId ($today)")
    }
    
    /**
     * Получает статистику по обработке сообщений.
     */
    suspend fun getProcessingStats(chatId: String): ProcessingStats {
        val totalMessages = chatRepository.getMessageCount(chatId)
        val summaries = summaryRepository.countByChatAndType(chatId, com.pai.android.data.model.SummaryType.CLUSTER)
        
        return ProcessingStats(
            totalMessages = totalMessages,
            clusterSummaries = summaries,
            compressionRatio = if (summaries > 0) totalMessages.toFloat() / summaries else 1.0f
        )
    }
    
    /**
     * Очищает старые данные (например, сообщения старше 30 дней).
     */
    suspend fun cleanupOldData(daysToKeep: Int = 30) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        
        // TODO: Реализовать очистку старых сообщений
        // chatRepository.deleteOlderThan(cutoffTime)
        
        println("🧹 Очистка данных старше $daysToKeep дней")
    }
    
    /**
     * Статистика обработки.
     */
    data class ProcessingStats(
        val totalMessages: Int,
        val clusterSummaries: Int,
        val compressionRatio: Float
    )
}