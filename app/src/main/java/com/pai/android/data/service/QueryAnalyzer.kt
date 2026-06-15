package com.pai.android.data.service

import com.pai.android.data.model.Message
import com.pai.android.data.model.MessageRole
import com.pai.android.data.model.QueryAnalysisResult
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.local.MemoryDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI-анализатор пользовательских запросов для семантического поиска.
 * Определяет, какие факты из памяти релевантны запросу пользователя.
 */
@Singleton
class QueryAnalyzer @Inject constructor(
    private val aiRepository: AiRepository,
    private val memoryDao: MemoryDao,
    private val defaultDispatcher: CoroutineDispatcher
) {
    
    /**
     * Промпт для анализа пользовательских запросов.
     */
    private val QUERY_ANALYSIS_PROMPT = """
        Ты — интеллектуальный анализатор запросов для системы памяти AI ассистента.
        Проанализируй запрос пользователя и определи, какие типы информации из памяти могут быть релевантны.
        
        ### КАТЕГОРИИ ФАКТОВ И КЛЮЧИ:
        
        1. **personal_info** (личная информация):
           - name: имя пользователя
           - birth_date: дата рождения
           - birth_place: место рождения
           - location: текущее местоположение
           - profession: профессия, работа
        
        2. **contacts** (контактная информация):
           - email: адрес электронной почты
           - phone: номер телефона
        
        3. **preferences** (предпочтения):
           - favorite_movie: любимый фильм
           - favorite_music: любимая музыка
           - hobby: хобби, увлечения
           - favorite_food: любимая еда
           - favorite_color: любимый цвет
        
        4. **ai_info** (информация об AI):
           - name: имя AI
           - role: роль AI
           - instructions: инструкции для AI
           - personality: характер AI
        
        5. **global** (общая информация):
           - projects: проекты пользователя
           - skills: навыки, умения
           - context: текущий контекст, задачи
        
        6. **temporal_queries** (временные запросы):
           - daily_search: поиск в дневной памяти по датам
           - temporal_context: временной контекст запроса
           - date_reference: ссылки на конкретные даты или периоды
        
        ### ПРАВИЛА АНАЛИЗА:
        
        1. **Scope определение:**
           - Запросы о пользователе ("меня", "мой", "мои") → scope="user"
           - Запросы об AI ("тебя", "твой", "ты") → scope="ai"
           - Общие запросы ("проекты", "информация") → scope="global" или "user"
           - Временные запросы ("вчера", "15 апреля", "на прошлой неделе") → scope="daily"
        
        2. **Temporal анализ:**
           - Если запрос содержит временные указатели ("вчера", "сегодня", "15 апреля", "на прошлой неделе", "в прошлом месяце") → suggested_categories должен включать "temporal_queries"
           - Добавь suggested_keys: "daily_search", "temporal_context"
           - Увеличь confidence для чётких временных указаний
           - Пример: "вчера мы обсуждали проекты" → suggested_scope="daily", suggested_categories=["temporal_queries", "global"], suggested_keys=["daily_search", "projects"]
        
        3. **Извлечение ключевых слов:**
           - Идентифицируй ключевые слова из запроса
           - Пример: "где я живу" → ["живу", "местоположение", "город"]
        
        4. **Предположение ключей фактов:**
           - Сопоставь ключевые слова с возможными ключами фактов
           - Пример: "живу" → ["location"]
           - Пример: "зовут" → ["name"]
        
        5. **Уверенность (confidence 0.0-1.0):**
           - 1.0: чёткий запрос ("как меня зовут")
           - 0.8: общий запрос ("что знаешь обо мне")
           - 0.6: неоднозначный запрос ("расскажи что-нибудь")
        
        ### ФОРМАТ ОТВЕТА (СТРОГО JSON):
        
        {
          "keywords": ["живу", "город", "местоположение"],
          "suggested_keys": ["location"],
          "suggested_categories": ["personal_info"],
          "suggested_scope": "user",
          "confidence": 0.9
        }
        
        ### ПРИМЕРЫ:
        
        Пример 1: "Как меня зовут?"
        {
          "keywords": ["зовут", "имя"],
          "suggested_keys": ["name"],
          "suggested_categories": ["personal_info"],
          "suggested_scope": "user",
          "confidence": 1.0
        }
        
        Пример 2: "Где я живу сейчас?"
        {
          "keywords": ["живу", "сейчас", "город", "местоположение"],
          "suggested_keys": ["location"],
          "suggested_categories": ["personal_info"],
          "suggested_scope": "user",
          "confidence": 0.9
        }
        
        Пример 3: "Что ты знаешь обо мне?"
        {
          "keywords": ["знаешь", "обо", "мне", "информация"],
          "suggested_keys": ["name", "location", "birth_date", "profession"],
          "suggested_categories": ["personal_info", "contacts", "preferences"],
          "suggested_scope": "user",
          "confidence": 0.8
        }
        
        Пример 4: "Какие у меня проекты?"
        {
          "keywords": ["проекты", "разрабатываю", "создаю"],
          "suggested_keys": ["projects"],
          "suggested_categories": ["global"],
          "suggested_scope": "user",
          "confidence": 0.9
        }
        
        Пример 5: "Как тебя зовут?"
        {
          "keywords": ["тебя", "зовут", "имя"],
          "suggested_keys": ["name"],
          "suggested_categories": ["ai_info"],
          "suggested_scope": "ai",
          "confidence": 1.0
        }
        
        Пример 6: "Что ты умеешь?"
        {
          "keywords": ["умеешь", "можешь", "возможности"],
          "suggested_keys": ["role", "instructions", "capabilities"],
          "suggested_categories": ["ai_info"],
          "suggested_scope": "ai",
          "confidence": 0.7
        }
        
        Пример 7: "Вчера мы обсуждали проекты, что я говорил про TradingBot?"
        {
          "keywords": ["вчера", "обсуждали", "проекты", "tradingbot", "говорил"],
          "suggested_keys": ["daily_search", "projects", "temporal_context"],
          "suggested_categories": ["temporal_queries", "global"],
          "suggested_scope": "daily",
          "confidence": 0.9
        }
        
        Пример 8: "15 апреля что ты предлагал по архитектуре памяти?"
        {
          "keywords": ["15", "апреля", "предлагал", "архитектуре", "памяти"],
          "suggested_keys": ["daily_search", "temporal_context", "memory_architecture"],
          "suggested_categories": ["temporal_queries", "global"],
          "suggested_scope": "daily",
          "confidence": 0.95
        }
        
        Пример 9: "На прошлой неделе мы что-то обсуждали про QR Code Studio?"
        {
          "keywords": ["прошлой", "неделе", "обсуждали", "qr", "code", "studio"],
          "suggested_keys": ["daily_search", "temporal_context", "projects"],
          "suggested_categories": ["temporal_queries", "global"],
          "suggested_scope": "daily",
          "confidence": 0.85
        }
        
        ### ЗАПРОС ДЛЯ АНАЛИЗА:
        
        Запрос пользователя: "%QUERY%"
    """.trimIndent()
    
    /**
     * Анализирует пользовательский запрос для определения релевантных фактов из памяти.
     * Использует кэш для уже проанализированных запросов.
     */
    suspend fun analyzeQuery(query: String): QueryAnalysisResult {
        if (query.isBlank()) {
            return QueryAnalysisResult.empty(query)
        }
        
        // 1. Проверяем кэш
        val cached = memoryDao.getQueryAnalysis(query)
        if (cached != null && !cached.isStale()) {
            // Обновляем время последнего использования
            memoryDao.updateQueryAnalysisLastUsed(query)
            println("🔍 Использован кэшированный анализ для запроса: '$query'")
            return cached.markUsed()
        }
        
        println("🧠 AI-анализ запроса: '$query'")
        
        // 2. Выполняем AI-анализ
        val analysisResult = performAiAnalysis(query)
        
        // 3. Сохраняем в кэш
        memoryDao.saveQueryAnalysis(analysisResult)
        
        println("✅ Результат анализа: ключи=${analysisResult.suggestedKeys}, scope=${analysisResult.suggestedScope}, confidence=${analysisResult.confidence}")
        
        return analysisResult
    }
    
    /**
     * Выполняет AI-анализ запроса.
     */
    private suspend fun performAiAnalysis(query: String): QueryAnalysisResult = withContext(defaultDispatcher) {
        try {
            // Подготавливаем промпт
            val prompt = QUERY_ANALYSIS_PROMPT.replace("%QUERY%", query)
            
            // Создаём системное сообщение
            val systemMessage = Message(
                id = "query_analysis_system",
                role = MessageRole.SYSTEM,
                content = prompt,
                timestamp = System.currentTimeMillis(),
                chatId = "query_analysis"
            )
            
            // Создаём пользовательское сообщение с запросом
            val userMessage = Message(
                id = "query_analysis_user",
                role = MessageRole.USER,
                content = "Проанализируй этот запрос: \"$query\"",
                timestamp = System.currentTimeMillis(),
                chatId = "query_analysis"
            )
            
            // Отправляем запрос в AI
            val result = aiRepository.sendMessage(
                messages = listOf(systemMessage, userMessage),
                systemPrompt = null, // Промпт уже в системном сообщении
                memoryContext = null
            )
            
            if (result.isSuccess) {
                val aiResponse = result.getOrThrow()
                val jsonText = aiResponse.text.trim()
                
                println("📝 Сырой ответ AI для анализа запроса:")
                println(jsonText.take(500))
                println("---")
                
                // Парсим JSON ответ
                return@withContext parseAiResponse(query, jsonText)
            } else {
                println("⚠️ Ошибка AI-анализа: ${result.exceptionOrNull()?.message}")
                return@withContext QueryAnalysisResult.empty(query)
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка при AI-анализе запроса '$query': ${e.message}")
            e.printStackTrace()
            return@withContext QueryAnalysisResult.empty(query)
        }
    }
    
    /**
     * Парсит JSON ответ от AI.
     */
    private fun parseAiResponse(query: String, jsonText: String): QueryAnalysisResult {
        try {
            // Пытаемся найти JSON в тексте (AI мог добавить пояснения)
            val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(jsonText)
            val jsonStr = jsonMatch?.value ?: jsonText
            
            val json = JSONObject(jsonStr)
            
            val keywords = mutableListOf<String>()
            val suggestedKeys = mutableListOf<String>()
            val suggestedCategories = mutableListOf<String>()
            
            // Извлекаем keywords
            val keywordsArray = json.optJSONArray("keywords")
            if (keywordsArray != null) {
                for (i in 0 until keywordsArray.length()) {
                    keywords.add(keywordsArray.getString(i))
                }
            }
            
            // Извлекаем suggested_keys
            val keysArray = json.optJSONArray("suggested_keys")
            if (keysArray != null) {
                for (i in 0 until keysArray.length()) {
                    suggestedKeys.add(keysArray.getString(i))
                }
            }
            
            // Извлекаем suggested_categories
            val categoriesArray = json.optJSONArray("suggested_categories")
            if (categoriesArray != null) {
                for (i in 0 until categoriesArray.length()) {
                    suggestedCategories.add(categoriesArray.getString(i))
                }
            }
            
            // Извлекаем остальные поля
            val suggestedScope = json.optString("suggested_scope", "user")
            val confidence = json.optDouble("confidence", 0.8).toFloat()
            
            return QueryAnalysisResult(
                query = query,
                keywords = keywords,
                suggestedKeys = suggestedKeys,
                suggestedCategories = suggestedCategories,
                suggestedScope = suggestedScope,
                confidence = confidence
            )
        } catch (e: Exception) {
            println("⚠️ Не удалось распарсить JSON ответ AI: ${e.message}")
            println("Сырой текст: ${jsonText.take(200)}")
            
            // Fallback: простой анализ на основе ключевых слов
            return createFallbackAnalysis(query)
        }
    }
    
    /**
     * Создаёт fallback-анализ на основе ключевых слов.
     */
    private fun createFallbackAnalysis(query: String): QueryAnalysisResult {
        val lowerQuery = query.lowercase()
        val keywords = mutableListOf<String>()
        val suggestedKeys = mutableListOf<String>()
        val suggestedCategories = mutableListOf<String>()
        var suggestedScope = "user"
        var confidence = 0.5f
        
        // Простая эвристика для определения scope
        when {
            lowerQuery.contains("тебя") || lowerQuery.contains("твой") || lowerQuery.contains("ты") -> {
                suggestedScope = "ai"
                confidence = 0.7f
            }
            lowerQuery.contains("меня") || lowerQuery.contains("мой") || lowerQuery.contains("мои") -> {
                suggestedScope = "user"
                confidence = 0.7f
            }
        }
        
        // Простая эвристика для ключевых слов
        val words = lowerQuery.split(" ", ",", ".", "?", "!")
            .filter { it.length >= 3 }
            .take(10)
        
        keywords.addAll(words)
        
        // Простая эвристика для suggested_keys
        val keyMapping = mapOf(
            "зовут" to "name",
            "имя" to "name",
            "живу" to "location",
            "город" to "location",
            "местоположение" to "location",
            "родился" to "birth_date",
            "день рождения" to "birth_date",
            "работаю" to "profession",
            "профессия" to "profession",
            "проекты" to "projects",
            "разрабатываю" to "projects",
            "умеешь" to "role",
            "можешь" to "role",
            "возможности" to "role"
        )
        
        for ((word, key) in keyMapping) {
            if (lowerQuery.contains(word)) {
                suggestedKeys.add(key)
                when (key) {
                    "name", "location", "birth_date", "profession" -> suggestedCategories.add("personal_info")
                    "projects" -> suggestedCategories.add("global")
                    "role" -> suggestedCategories.add("ai_info")
                }
            }
        }
        
        // Если не нашли ключей, добавляем общие категории
        if (suggestedKeys.isEmpty()) {
            when (suggestedScope) {
                "user" -> suggestedCategories.add("personal_info")
                "ai" -> suggestedCategories.add("ai_info")
                else -> suggestedCategories.add("global")
            }
            confidence = 0.3f
        }
        
        return QueryAnalysisResult(
            query = query,
            keywords = keywords.distinct(),
            suggestedKeys = suggestedKeys.distinct(),
            suggestedCategories = suggestedCategories.distinct(),
            suggestedScope = suggestedScope,
            confidence = confidence
        )
    }
    
    /**
     * Очищает устаревшие записи из кэша анализа.
     */
    suspend fun cleanupStaleAnalyses() {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        memoryDao.deleteStaleQueryAnalyses(thirtyDaysAgo)
    }
}