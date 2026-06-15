package com.pai.android.data.service

import com.pai.android.data.detector.FactExtractor
import com.pai.android.data.detector.SignificanceDetector
import com.pai.android.data.local.MemoryDao
import com.pai.android.data.model.DailyMemory
import com.pai.android.data.model.Message
import com.pai.android.data.summarizer.SmartSummarizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сервис для управления дневной памятью (аналог memory/YYYY-MM-DD.md в OpenClaw).
 * Автоматически сохраняет значимые события, защищает от переполнения контекста.
 */
@Singleton
class DailyMemoryService @Inject constructor(
    private val memoryDao: MemoryDao,
    private val significanceDetector: SignificanceDetector,
    private val smartSummarizer: SmartSummarizer,
    private val defaultDispatcher: CoroutineDispatcher
) {
    
    private val serviceScope = CoroutineScope(defaultDispatcher)
    
    companion object {
        // Конфигурация защиты от переполнения
        const val MAX_DAILY_CHARS = 5000  // Максимум символов в дневной записи
        const val SUMMARIZATION_THRESHOLD = 2000  // При достижении этого порога активируем суммаризацию
        const val RETENTION_DAYS = 30  // Хранить записи за последние 30 дней
        
        // Типы записей для тегов
        const val TAG_EXTRACTED_FACT = "extracted_fact"
        const val TAG_SIGNIFICANT_EVENT = "significant_event"
        const val TAG_USER_REQUEST = "user_request"
        const val TAG_SUMMARY = "summary"
        const val TAG_SYSTEM = "system"
    }
    
    /**
     * Обрабатывает новое сообщение для сохранения в дневную память.
     * Выполняется асинхронно.
     */
    fun processMessageForDailyMemory(message: Message) {
        serviceScope.launch {
            try {
                // 1. Проверяем значимость
                val significanceScore = significanceDetector.evaluateSignificanceScore(message)
                val shouldSave = shouldSaveToDailyMemory(message, significanceScore)
                
                if (!shouldSave) {
                    return@launch
                }
                
                // 2. Определяем тип записи и готовим контент
                val (content, tags) = prepareDailyEntry(message, significanceScore)
                
                // 3. Сохраняем в дневную память
                saveToDailyMemory(content, tags)
                
                // 4. Проверяем на переполнение и активируем суммаризацию если нужно
                checkAndSummarizeIfNeeded()
                
                println("📝 Сохранено в дневную память: '${message.content.take(30)}...' (score=$significanceScore)")
                
            } catch (e: Exception) {
                println("⚠️ Ошибка обработки для дневной памяти: ${e.message}")
            }
        }
    }
    
    /**
     * Сохраняет извлечённые факты в дневную память.
     */
    suspend fun saveExtractedFacts(facts: List<com.pai.android.data.detector.FactExtractor.Fact>) {
        if (facts.isEmpty()) return
        
        val factDescriptions = facts.joinToString("\n") { fact ->
            "• ${fact.key}: ${fact.value} (${(fact.confidence * 100).toInt()}%)"
        }
        
        val content = "**Извлечены факты:**\n$factDescriptions"
        saveToDailyMemory(content, listOf(TAG_EXTRACTED_FACT))
        
        println("📊 Сохранено ${facts.size} фактов в дневную память")
    }
    
    /**
     * Сохраняет значимое событие (вручную вызванное).
     */
    suspend fun saveSignificantEvent(description: String, tags: List<String> = emptyList()) {
        val allTags = tags + TAG_SIGNIFICANT_EVENT
        val content = "**📌 Значимое событие:** $description"
        
        saveToDailyMemory(content, allTags)
        
        println("🎯 Сохранено значимое событие: '$description'")
    }
    
    /**
     * Сохраняет запрос пользователя на запоминание.
     */
    suspend fun saveUserMemoryRequest(content: String, requestedBy: String) {
        val formattedContent = """
            |**🧠 Запрос на запоминание:**
            |Пользователь: $requestedBy
            |Запрос: "$content"
        """.trimMargin()
        
        saveToDailyMemory(formattedContent, listOf(TAG_USER_REQUEST))
        
        println("🧠 Сохранён запрос на запоминание от $requestedBy")
    }
    
    /**
     * Создаёт автоматическую суммаризацию дня.
     * Вызывается в конце дня или при переполнении.
     */
    suspend fun createDailySummary(date: String? = null): Boolean {
        val targetDate = date ?: getCurrentDate()
        
        try {
            // Получаем дневную запись
            val dailyMemory = memoryDao.getDailyByDate(targetDate)
            if (dailyMemory == null || dailyMemory.content.length < 100) {
                println("ℹ️ Недостаточно данных для суммаризации дня $targetDate")
                return false
            }
            
            // Создаём промпт для AI суммаризации
            val summary = smartSummarizer.createDailySummary(
                chatId = "system_daily_summary",
                messages = emptyList(), // В этом методе мы суммируем не сообщения, а дневную запись
                date = targetDate
            )
            
            if (summary != null) {
                // Сохраняем суммаризацию в дневную память
                val summaryContent = """
                    |**📊 Автоматическая суммаризация дня:**
                    |
                    |${summary.content}
                    |
                    |_Создано: ${SimpleDateFormat("HH:mm").format(Date())}_
                """.trimMargin()
                
                saveToDailyMemory(summaryContent, listOf(TAG_SUMMARY, TAG_SYSTEM))
                
                // Очищаем старую запись, оставляя только суммаризацию
                memoryDao.updateDaily(dailyMemory.copy(content = summaryContent))
                
                println("✅ Создана суммаризация дня $targetDate")
                return true
            }
            
        } catch (e: Exception) {
            println("⚠️ Ошибка создания суммаризации дня: ${e.message}")
        }
        
        return false
    }
    
    /**
     * Очищает устаревшие дневные записи (сохраняет только последние N дней).
     */
    suspend fun cleanupOldEntries() {
        try {
            val cutoffDate = getDateDaysAgo(RETENTION_DAYS)
            memoryDao.deleteOlderThan(cutoffDate)
            
            println("🧹 Очищены дневные записи старше $RETENTION_DAYS дней (до $cutoffDate)")
        } catch (e: Exception) {
            println("⚠️ Ошибка очистки старых записей: ${e.message}")
        }
    }
    
    /**
     * Получает дневную запись за указанную дату.
     */
    suspend fun getDailyMemory(date: String): DailyMemory? {
        return memoryDao.getDailyByDate(date)
    }
    
    /**
     * Получает последние N дневных записей.
     */
    suspend fun getRecentDailyEntries(limit: Int = 30): List<DailyMemory> {
        return memoryDao.getRecentDaily(limit)
    }
    
    /**
     * Экспортирует дневную память в текстовый формат (аналог OpenClaw Markdown).
     */
    suspend fun exportToMarkdown(days: Int = 7): String {
        val entries = getRecentDailyEntries(days)
        
        val markdown = StringBuilder("# Дневная память\n\n")
        
        entries.sortedByDescending { it.date }.forEach { entry ->
            markdown.append("## ${formatDateForDisplay(entry.date)}\n\n")
            markdown.append(entry.content)
            markdown.append("\n\n")
            
            if (entry.tags.isNotBlank()) {
                markdown.append("**Теги:** ${entry.tags}\n\n")
            }
            
            markdown.append("---\n\n")
        }
        
        return markdown.toString()
    }
    
    /**
     * Проверяет, нужно ли сохранять сообщение в дневную память.
     */
    private fun shouldSaveToDailyMemory(message: Message, significanceScore: Int): Boolean {
        // Не сохраняем сообщения ассистента (кроме исключительных случаев)
        if (!message.isFromUser()) return false
        
        // Не сохраняем шум
        if (significanceDetector.isNoiseMessage(message)) return false
        
        // Сохраняем critical и high significance сообщения
        if (significanceDetector.isCriticalMessage(message)) return true
        if (significanceDetector.isHighSignificanceMessage(message)) return true
        
        // Сохраняем среднюю значимость при наличии ключевых слов
        if (significanceDetector.isMediumSignificanceMessage(message) && significanceScore >= 5) {
            return true
        }
        
        // Сохраняем запросы на запоминание
        if (message.content.contains("запомни", ignoreCase = true)) {
            return true
        }
        
        return false
    }
    
    /**
     * Подготавливает контент для дневной записи.
     */
    private fun prepareDailyEntry(message: Message, significanceScore: Int): Pair<String, List<String>> {
        val timestamp = SimpleDateFormat("HH:mm").format(Date(message.timestamp))
        val tags = mutableListOf<String>()
        
        val contentBuilder = StringBuilder()
        
        // Определяем тип записи
        when {
            significanceDetector.isCriticalMessage(message) -> {
                contentBuilder.append("**🚨 КРИТИЧЕСКАЯ ИНФОРМАЦИЯ**\n")
                tags.add("critical")
            }
            
            significanceDetector.isHighSignificanceMessage(message) -> {
                contentBuilder.append("**🎯 ВЫСОКАЯ ЗНАЧИМОСТЬ**\n")
                tags.add("high_significance")
            }
            
            significanceDetector.isMediumSignificanceMessage(message) -> {
                contentBuilder.append("**📝 СРЕДНЯЯ ЗНАЧИМОСТЬ**\n")
                tags.add("medium_significance")
            }
            
            message.content.contains("запомни", ignoreCase = true) -> {
                contentBuilder.append("**🧠 ЗАПРОС НА ЗАПОМИНАНИЕ**\n")
                tags.add(TAG_USER_REQUEST)
            }
        }
        
        // Добавляем тег значимости
        tags.add("significance_$significanceScore")
        
        // Форматируем содержимое
        contentBuilder.append("Время: $timestamp\n")
        contentBuilder.append("Сообщение: \"${message.content}\"\n")
        contentBuilder.append("Оценка значимости: $significanceScore/10\n")
        
        return contentBuilder.toString() to tags
    }
    
    /**
     * Сохраняет контент в дневную память.
     */
    private suspend fun saveToDailyMemory(content: String, tags: List<String>) {
        val today = getCurrentDate()
        
        // Получаем существующую запись или создаём новую
        val existing = memoryDao.getDailyByDate(today)
        
        if (existing != null) {
            // Проверяем на переполнение перед добавлением
            val newContent = if (existing.content.length + content.length > MAX_DAILY_CHARS) {
                // Активируем суммаризацию перед добавлением
                createDailySummary(today)
                "$content\n\n[Запись обрезана из-за переполнения]"
            } else {
                existing.appendContent(content).content
            }
            
            // Обновляем теги
            val updatedTags = tags.fold(existing) { daily, tag -> daily.addTag(tag) }.tags
            
            memoryDao.updateDaily(existing.copy(content = newContent, tags = updatedTags))
        } else {
            // Создаём новую запись
            val dailyMemory = DailyMemory.createForToday(content, tags)
            memoryDao.insertDaily(dailyMemory)
        }
    }
    
    /**
     * Проверяет размер дневной записи и активирует суммаризацию при необходимости.
     */
    private suspend fun checkAndSummarizeIfNeeded() {
        val today = getCurrentDate()
        val dailyMemory = memoryDao.getDailyByDate(today) ?: return
        
        if (dailyMemory.content.length >= SUMMARIZATION_THRESHOLD) {
            println("⚠️ Дневная запись превышает порог суммаризации (${dailyMemory.content.length}/$SUMMARIZATION_THRESHOLD)")
            
            // Пробуем создать суммаризацию
            val success = createDailySummary(today)
            
            if (success) {
                println("✅ Активирована автоматическая суммаризация из-за переполнения")
            }
        }
    }
    
    /**
     * Получает текущую дату в формате YYYY-MM-DD.
     */
    private fun getCurrentDate(): String {
        return SimpleDateFormat("yyyy-MM-dd").format(Date())
    }
    
    /**
     * Получает дату N дней назад.
     */
    private fun getDateDaysAgo(days: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        return SimpleDateFormat("yyyy-MM-dd").format(calendar.time)
    }
    
    /**
     * Форматирует дату для отображения.
     */
    private fun formatDateForDisplay(date: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd")
            val outputFormat = SimpleDateFormat("dd MMMM yyyy", Locale("ru"))
            outputFormat.format(inputFormat.parse(date) ?: Date())
        } catch (e: Exception) {
            date
        }
    }
}