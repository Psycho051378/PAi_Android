package com.pai.android.data.service

import com.pai.android.data.local.MemoryDao
import com.pai.android.data.model.DailyMemory
import com.pai.android.data.model.Message
import com.pai.android.data.model.MessageRole
import com.pai.android.data.repository.AiRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сервис для поиска и анализа дневной памяти.
 * Поддерживает временные запросы с AI-анализом содержимого.
 */
@Singleton
class DailyMemorySearchService @Inject constructor(
    private val memoryDao: MemoryDao,
    private val temporalQueryParser: TemporalQueryParser,
    private val aiRepository: AiRepository,
    private val defaultDispatcher: CoroutineDispatcher
) {
    
    companion object {
        // Максимальное количество записей для анализа AI
        private const val MAX_ENTRIES_FOR_AI_ANALYSIS = 5
        
        // Промпт для AI-анализа дневных записей
        private val DAILY_MEMORY_ANALYSIS_PROMPT = """
            Ты — аналитик дневной памяти AI ассистента.
            Проанализируй дневные записи за указанный период и ответь на вопрос пользователя.
            
            ### КОНТЕКСТ:
            - Дневная память содержит сырые записи событий, фактов и обсуждений
            - Записи структурированы по датам и могут содержать теги
            - Твоя задача — найти релевантную информацию и дать точный ответ
            
            ### ПРАВИЛА АНАЛИЗА:
            
            1. **Понимание запроса:**
               - Определи, о чём именно спрашивает пользователь
               - Учти временной контекст (дата, период)
               - Выдели ключевые темы и понятия из запроса
            
            2. **Анализ дневных записей:**
               - Ищи упоминания ключевых слов из запроса
               - Обращай внимание на теги (например, "extracted_fact", "significant_event")
               - Сопоставляй даты и временные рамки
            
            3. **Формирование ответа:**
               - Будь конкретным — ссылайся на факты из записей
               - Если информации мало, честно скажи об этом
               - Если записей нет за указанный период, сообщи об этом
               - Используй даты и контекст для уточнения
            
            4. **Структура ответа (если информация найдена):**
               - **Дата/период**: Укажи, за какой период найдена информация
               - **Основная информация**: Кратко изложи найденные факты
               - **Детали**: При необходимости приведи цитаты или конкретные детали
               - **Контекст**: Объясни, как это связано с запросом пользователя
            
            5. **Стиль ответа:**
               - Будь дружелюбным, но профессиональным
               - Используй эмодзи для наглядности (📅, 📝, 🔍)
               - Reply in user language
               - Будь кратким, но информативным
            
            ### ПРИМЕРЫ:
            
            Пример 1:
            Запрос: "Вчера мы обсуждали проекты, что я говорил про TradingBot?"
            Ответ: "📅 Вчера (2026-04-19) мы действительно обсуждали проекты. В дневной записи есть упоминание TradingBot: ты говорил о исправлении Alor API и гибридном подходе для динамических лотов. Также обсуждался GUI и тестирование системы."
            
            Пример 2:
            Запрос: "15 апреля что ты предлагал по архитектуре памяти?"
            Ответ: "📝 15 апреля 2026 года я предлагал трёхэтапный план улучшения архитектуры памяти: 1) Улучшить AI-извлекатель фактов, 2) Добавить систему подтверждения фактов, 3) Реализовать семантический поиск. Этот план был утверждён и мы сейчас его реализуем."
            
            Пример 3:
            Запрос: "На прошлой неделе мы что-то обсуждали про QR Code Studio?"
            Ответ: "🔍 На прошлой неделе (с 14 по 20 апреля) мы обсуждали локализацию пакетной генерации в QR Code Studio. Были исправлены проблемы с миграцией старых значений и добавлена поддержка мультиязычных ресурсов."
            
            Пример 4 (информация не найдена):
            Запрос: "Вчера мы обсуждали полёт на Марс?"
            Ответ: "📅 В дневных записях за вчерашний день (2026-04-19) не найдено упоминаний о полёте на Марс. Возможно, мы обсуждали это в другой день или тема не была зафиксирована в памяти."
            
            ### ФОРМАТ ОТВЕТА (JSON для структурирования):
            
            {
              "found": true,                      // Найдена ли информация
              "date_period": "вчера (2026-04-19)", // Период поиска
              "summary": "Краткая сводка...",      // Основная информация
              "details": ["Деталь 1", "Деталь 2"], // Конкретные детали
              "confidence": 0.95,                  // Уверенность в ответе (0.0-1.0)
              "suggested_followup": "Хочешь узнать больше деталей?" // Предложение для продолжения
            }
            
            ### ДНЕВНЫЕ ЗАПИСИ ДЛЯ АНАЛИЗА:
            
            ПЕРИОД: %PERIOD%
            
            ЗАПИСИ:
            %CONTENT%
            
            ### ЗАПРОС ПОЛЬЗОВАТЕЛЯ:
            
            "%QUERY%"
            
            ### ТВОЙ АНАЛИЗ И ОТВЕТ:
        """.trimIndent()
    }
    
    /**
     * Выполняет поиск в дневной памяти с учётом временного контекста.
     */
    suspend fun searchDailyMemory(query: String): DailyMemorySearchResult {
        println("\n" + "=".repeat(60))
        println("🔍 ПОИСК В ДНЕВНОЙ ПАМЯТИ")
        println("Запрос: '$query'")
        println("=".repeat(60))
        
        // 1. Парсим временные указания
        val temporalResult = temporalQueryParser.parseQuery(query)
        println("📅 Временной анализ:")
        println("  • Тип: ${temporalResult.temporalType}")
        println("  • Дата: ${temporalResult.date ?: "нет"}")
        println("  • Диапазон: ${if (temporalResult.isRange) "${temporalResult.startDate} - ${temporalResult.endDate}" else "нет"}")
        println("  • Поисковый термин: '${temporalResult.searchTerm}'")
        println("  • Уверенность: ${temporalResult.confidence}")
        
        // 2. Если нет временных указаний или низкая уверенность, возвращаем пустой результат
        if (temporalResult.temporalType == TemporalType.NONE || temporalResult.confidence < 0.3) {
            println("⚠️ Недостаточно временных указаний для поиска в дневной памяти")
            return DailyMemorySearchResult.empty(query)
        }
        
        // 3. Ищем дневные записи
        val dailyEntries = findDailyEntries(temporalResult)
        
        if (dailyEntries.isEmpty()) {
            println("📭 Дневных записей за указанный период не найдено")
            return DailyMemorySearchResult.notFound(query, temporalResult)
        }
        
        println("📄 Найдено дневных записей: ${dailyEntries.size}")
        dailyEntries.forEachIndexed { index, entry ->
            println("  ${index + 1}. ${entry.date}: ${entry.content.take(50)}...")
        }
        
        // 4. Если найдены записи, анализируем с помощью AI
        return if (dailyEntries.size <= MAX_ENTRIES_FOR_AI_ANALYSIS) {
            println("🧠 Запускаю AI-анализ дневных записей...")
            analyzeWithAi(dailyEntries, query, temporalResult)
        } else {
            println("⚠️ Слишком много записей для AI-анализа (${dailyEntries.size} > $MAX_ENTRIES_FOR_AI_ANALYSIS)")
            createSimpleResult(dailyEntries, query, temporalResult)
        }
    }
    
    /**
     * Ищет дневные записи на основе временного запроса.
     */
    private suspend fun findDailyEntries(temporalResult: TemporalQueryResult): List<DailyMemory> {
        return withContext(defaultDispatcher) {
            try {
                when {
                    // Конкретная дата
                    temporalResult.date != null -> {
                        val entry = memoryDao.getDailyByDate(temporalResult.date)
                        if (entry != null) listOf(entry) else emptyList()
                    }
                    
                    // Диапазон дат
                    temporalResult.isRange && temporalResult.startDate != null && temporalResult.endDate != null -> {
                        memoryDao.getDailyInRange(temporalResult.startDate, temporalResult.endDate)
                    }
                    
                    // Поиск по содержанию (если есть поисковый термин)
                    temporalResult.searchTerm.isNotBlank() -> {
                        // TODO: Добавить метод searchDailyContent в MemoryDao
                        // Пока используем простую фильтрацию
                        val allRecent = memoryDao.getRecentDaily(30)
                        allRecent.filter { entry ->
                            entry.content.contains(temporalResult.searchTerm, ignoreCase = true) ||
                            entry.tags.contains(temporalResult.searchTerm, ignoreCase = true)
                        }
                    }
                    
                    else -> emptyList()
                }
            } catch (e: Exception) {
                println("⚠️ Ошибка поиска дневных записей: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * Анализирует дневные записи с помощью AI.
     */
    private suspend fun analyzeWithAi(
        dailyEntries: List<DailyMemory>,
        originalQuery: String,
        temporalResult: TemporalQueryResult
    ): DailyMemorySearchResult {
        return withContext(defaultDispatcher) {
            try {
                // Подготавливаем контент для анализа
                val periodDescription = buildPeriodDescription(temporalResult)
                val combinedContent = dailyEntries.joinToString("\n\n---\n\n") { entry ->
                    """
                    |Дата: ${entry.date}
                    |Теги: ${entry.tags}
                    |Содержимое:
                    |${entry.content}
                    """.trimMargin()
                }
                
                // Формируем промпт
                val prompt = DAILY_MEMORY_ANALYSIS_PROMPT
                    .replace("%PERIOD%", periodDescription)
                    .replace("%CONTENT%", combinedContent)
                    .replace("%QUERY%", originalQuery)
                
                // Создаём системное сообщение
                val systemMessage = Message(
                    id = "daily_memory_analysis_system",
                    role = MessageRole.SYSTEM,
                    content = prompt,
                    timestamp = System.currentTimeMillis(),
                    chatId = "daily_memory_search"
                )
                
                // Создаём пользовательское сообщение
                val userMessage = Message(
                    id = "daily_memory_analysis_user",
                    role = MessageRole.USER,
                    content = "Проанализируй дневные записи и ответь на мой запрос: \"$originalQuery\"",
                    timestamp = System.currentTimeMillis(),
                    chatId = "daily_memory_search"
                )
                
                // Отправляем запрос в AI
                val result = aiRepository.sendMessage(
                    messages = listOf(systemMessage, userMessage),
                    systemPrompt = null, // Промпт уже в системном сообщении
                    memoryContext = null
                )
                
                if (result.isSuccess) {
                    val aiResponse = result.getOrThrow()
                    val responseText = aiResponse.text.trim()
                    
                    println("🤖 AI-ответ получен (${responseText.length} символов)")
                    println("Первые 200 символов: ${responseText.take(200)}...")
                    
                    // Пытаемся распарсить JSON ответ
                    val parsedResult = tryParseJsonResponse(responseText, originalQuery, temporalResult)
                    
                    if (parsedResult != null) {
                        return@withContext parsedResult
                    } else {
                        // Если не удалось распарсить JSON, используем текстовый ответ
                        return@withContext DailyMemorySearchResult(
                            query = originalQuery,
                            found = true,
                            datePeriod = periodDescription,
                            summary = responseText,
                            details = emptyList(),
                            confidence = 0.8f,
                            sourceEntries = dailyEntries.map { it.date },
                            temporalResult = temporalResult,
                            rawAiResponse = responseText
                        )
                    }
                    
                } else {
                    println("⚠️ Ошибка AI-анализа: ${result.exceptionOrNull()?.message}")
                    return@withContext createSimpleResult(dailyEntries, originalQuery, temporalResult)
                }
                
            } catch (e: Exception) {
                println("⚠️ Ошибка при AI-анализе дневной памяти: ${e.message}")
                e.printStackTrace()
                return@withContext createSimpleResult(dailyEntries, originalQuery, temporalResult)
            }
        }
    }
    
    /**
     * Пытается распарсить JSON-ответ от AI.
     */
    private fun tryParseJsonResponse(
        responseText: String,
        originalQuery: String,
        temporalResult: TemporalQueryResult
    ): DailyMemorySearchResult? {
        try {
            // Ищем JSON в тексте ответа
            val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(responseText)
            val jsonStr = jsonMatch?.value ?: responseText
            
            val json = JSONObject(jsonStr)
            
            val found = json.optBoolean("found", true)
            val datePeriod = json.optString("date_period", buildPeriodDescription(temporalResult))
            val summary = json.optString("summary", "")
            val confidence = json.optDouble("confidence", 0.8).toFloat()
            
            val detailsList = mutableListOf<String>()
            val detailsArray = json.optJSONArray("details")
            if (detailsArray != null) {
                for (i in 0 until detailsArray.length()) {
                    detailsList.add(detailsArray.getString(i))
                }
            }
            
            val suggestedFollowup = json.optString("suggested_followup", "")
            
            return DailyMemorySearchResult(
                query = originalQuery,
                found = found,
                datePeriod = datePeriod,
                summary = summary,
                details = detailsList,
                confidence = confidence,
                sourceEntries = emptyList(), // Будет заполнено позже
                temporalResult = temporalResult,
                rawAiResponse = responseText,
                suggestedFollowup = suggestedFollowup.takeIf { it.isNotBlank() }
            )
            
        } catch (e: Exception) {
            println("⚠️ Не удалось распарсить JSON-ответ AI: ${e.message}")
            return null
        }
    }
    
    /**
     * Создаёт простой результат без AI-анализа.
     */
    private fun createSimpleResult(
        dailyEntries: List<DailyMemory>,
        originalQuery: String,
        temporalResult: TemporalQueryResult
    ): DailyMemorySearchResult {
        val periodDescription = buildPeriodDescription(temporalResult)
        val entryDates = dailyEntries.map { it.date }
        
        val summary = if (dailyEntries.isNotEmpty()) {
            "Найдено ${dailyEntries.size} дневных записей за $periodDescription. " +
            "Первая запись: \"${dailyEntries.first().content.take(100)}...\""
        } else {
            "Записей за указанный период не найдено."
        }
        
        return DailyMemorySearchResult(
            query = originalQuery,
            found = dailyEntries.isNotEmpty(),
            datePeriod = periodDescription,
            summary = summary,
            details = emptyList(),
            confidence = temporalResult.confidence * 0.7f, // Понижаем уверенность без AI
            sourceEntries = entryDates,
            temporalResult = temporalResult,
            rawAiResponse = null
        )
    }
    
    /**
     * Формирует описание периода для отображения.
     */
    private fun buildPeriodDescription(temporalResult: TemporalQueryResult): String {
        return when {
            temporalResult.date != null -> temporalResult.dateDisplay ?: temporalResult.date
            temporalResult.isRange && temporalResult.startDate != null && temporalResult.endDate != null -> 
                "${temporalResult.startDate} - ${temporalResult.endDate}"
            temporalResult.dateDisplay != null -> temporalResult.dateDisplay
            else -> "указанный период"
        }
    }
    
    /**
     * Экспортирует дневные записи в читаемом формате.
     */
    suspend fun exportDailyEntries(dates: List<String>): String {
        val entries = dates.mapNotNull { date -> memoryDao.getDailyByDate(date) }
        
        return if (entries.isEmpty()) {
            "Нет дневных записей за указанные даты."
        } else {
            entries.joinToString("\n\n${"=".repeat(50)}\n\n") { entry ->
                """
                |📅 Дата: ${entry.date}
                |🏷️ Теги: ${if (entry.tags.isBlank()) "нет" else entry.tags}
                |
                |${entry.content}
                """.trimMargin()
            }
        }
    }
    
    /**
     * Получает статистику по дневной памяти для указанного периода.
     */
    suspend fun getDailyMemoryStats(startDate: String? = null, endDate: String? = null): DailyMemoryStats {
        return withContext(defaultDispatcher) {
            val allEntries = if (startDate != null && endDate != null) {
                memoryDao.getDailyInRange(startDate, endDate)
            } else {
                memoryDao.getRecentDaily(365) // За последний год
            }
            
            val totalEntries = allEntries.size
            val totalChars = allEntries.sumOf { it.content.length.toLong() }
            val uniqueDates = allEntries.map { it.date }.distinct().size
            
            // Анализ тегов
            val allTags = allEntries.flatMap { it.getTagsList() }
            val tagStats = allTags.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(10)
                .associate { it.key to it.value }
            
            DailyMemoryStats(
                totalEntries = totalEntries,
                totalChars = totalChars,
                uniqueDates = uniqueDates,
                dateRange = if (allEntries.isNotEmpty()) {
                    "${allEntries.last().date} - ${allEntries.first().date}"
                } else {
                    "нет записей"
                },
                tagStatistics = tagStats,
                avgEntryLength = if (totalEntries > 0) totalChars / totalEntries else 0L
            )
        }
    }
}

/**
 * Результат поиска в дневной памяти.
 */
data class DailyMemorySearchResult(
    val query: String,
    val found: Boolean,
    val datePeriod: String,
    val summary: String,
    val details: List<String>,
    val confidence: Float,
    val sourceEntries: List<String>, // Даты найденных записей
    val temporalResult: TemporalQueryResult,
    val rawAiResponse: String? = null,
    val suggestedFollowup: String? = null
) {
    companion object {
        fun empty(query: String): DailyMemorySearchResult {
            return DailyMemorySearchResult(
                query = query,
                found = false,
                datePeriod = "не определён",
                summary = "Не удалось распознать временные указания в запросе.",
                details = emptyList(),
                confidence = 0.1f,
                sourceEntries = emptyList(),
                temporalResult = TemporalQueryParser().parseQuery(query)
            )
        }
        
        fun notFound(query: String, temporalResult: TemporalQueryResult): DailyMemorySearchResult {
            val periodDesc = if (temporalResult.date != null) {
                temporalResult.dateDisplay ?: temporalResult.date
            } else if (temporalResult.isRange && temporalResult.startDate != null && temporalResult.endDate != null) {
                "${temporalResult.startDate} - ${temporalResult.endDate}"
            } else {
                "указанный период"
            }
            
            return DailyMemorySearchResult(
                query = query,
                found = false,
                datePeriod = periodDesc,
                summary = "📭 В дневной памяти нет записей за $periodDesc.",
                details = emptyList(),
                confidence = 0.3f,
                sourceEntries = emptyList(),
                temporalResult = temporalResult
            )
        }
    }
    
    /**
     * Форматирует результат для отображения пользователю.
     */
    fun formatForDisplay(): String {
        val builder = StringBuilder()
        
        builder.append("🔍 **Результат поиска:**\n\n")
        
        if (found) {
            builder.append("✅ **Найдено в дневной памяти**\n")
            builder.append("📅 **Период:** $datePeriod\n\n")
            
            builder.append("**📋 Сводка:**\n")
            builder.append(summary)
            builder.append("\n\n")
            
            if (details.isNotEmpty()) {
                builder.append("**🔎 Детали:**\n")
                details.forEachIndexed { index, detail ->
                    builder.append("${index + 1}. $detail\n")
                }
                builder.append("\n")
            }
            
            if (sourceEntries.isNotEmpty()) {
                builder.append("**📄 Источники:** записи за ${sourceEntries.joinToString(", ")}\n\n")
            }
        } else {
            builder.append("❌ **Информация не найдена**\n")
            builder.append("📅 **Период поиска:** $datePeriod\n\n")
            builder.append(summary)
            builder.append("\n\n")
        }
        
        if (suggestedFollowup != null) {
            builder.append("💡 $suggestedFollowup\n")
        }
        
        if (confidence < 0.7) {
            builder.append("\n⚠️ *Уверенность в ответе: ${(confidence * 100).toInt()}%*\n")
        }
        
        return builder.toString()
    }
}

/**
 * Статистика дневной памяти.
 */
data class DailyMemoryStats(
    val totalEntries: Int,
    val totalChars: Long,
    val uniqueDates: Int,
    val dateRange: String,
    val tagStatistics: Map<String, Int>,
    val avgEntryLength: Long
)