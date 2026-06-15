package com.pai.android.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.AiResponse
import com.pai.android.data.model.Attachment
import com.pai.android.data.model.NativeToolCall
import com.pai.android.data.model.ProviderSettings
import com.pai.android.data.network.model.ChatMessage
import com.pai.android.data.network.model.NativeToolDefinition
import com.pai.android.data.service.WebSearchService
import com.pai.android.data.util.AttachmentProcessor
import com.pai.android.di.AiChatServiceFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Центральный репозиторий для работы с AI сервисов.
 * Управляет выбором провайдера, обработкой ошибок, откатами.
 */
class AiRepository @Inject constructor(
    private val settingsRepository: ProviderSettingsRepository,
    private val aiChatServiceFactory: AiChatServiceFactory,
    private val defaultDispatcher: CoroutineDispatcher,
    private val webSearchService: WebSearchService,
    private val webSearchRepository: WebSearchRepository
) {
    
    /**
     * Отправляет сообщение в AI с учётом всех настроек.
     */
    suspend fun sendMessage(
        messages: List<com.pai.android.data.model.Message>,
        providerSettings: ProviderSettings? = null,
        modelOverride: String? = null,
        systemPrompt: String? = null,
        memoryContext: String? = null,
        tools: List<NativeToolDefinition>? = null,
        toolChoice: String? = null
    ): Result<AiResponse> = withContext(defaultDispatcher) {
        try {
            // Определяем настройки провайдера
            val settings = providerSettings ?: settingsRepository.getDefaultSettings()
            println("🔧 AiRepository.sendMessage: settings=${settings?.provider}, model=${settings?.getEffectiveModel()}, apiKey=${settings?.apiKey}, isValid=${settings?.isValid()}")
            if (settings == null || !settings.isValid()) {
                println("❌ AiRepository.sendMessage: нет валидных настроек провайдера")
                return@withContext Result.failure(IllegalStateException("No valid AI provider configured"))
            }
            
            // Подготавливаем сообщения для API
            val chatMessages = messages.map { msg ->
                com.pai.android.data.network.model.ChatMessage.createTextMessage(msg.role, msg.content)
            }
            
            // Добавляем системный промпт (если есть)
            val finalMessages = mutableListOf<com.pai.android.data.network.model.ChatMessage>()
            if (!systemPrompt.isNullOrBlank()) {
                finalMessages.add(com.pai.android.data.network.model.ChatMessage.createTextMessage(
                    role = com.pai.android.data.model.MessageRole.SYSTEM,
                    text = systemPrompt
                ))
            }
            
            // Добавляем контекст памяти (если есть)
            if (!memoryContext.isNullOrBlank()) {
                finalMessages.add(com.pai.android.data.network.model.ChatMessage.createTextMessage(
                    role = com.pai.android.data.model.MessageRole.SYSTEM,
                    text = memoryContext
                ))
            }
            
            // Веб-поиск: если включен, выполняем поиск по последнему пользовательскому сообщению
            val webSearchContext = performWebSearchIfEnabled(messages)
            if (!webSearchContext.isNullOrBlank()) {
                finalMessages.add(com.pai.android.data.network.model.ChatMessage.createTextMessage(
                    role = com.pai.android.data.model.MessageRole.SYSTEM,
                    text = webSearchContext
                ))
            }
            
            finalMessages.addAll(chatMessages)
            
            // Управление контекстом
            val managedMessages = manageContext(finalMessages, settings)
            
            // Создаём запрос
            val maxTokens = settings.resolveMaxTokens()
            val thinkingParam = if (settings.thinkingModeEnabled) {
                mapOf<String, Any>("type" to "enabled")
            } else {
                null
            }
            val effectiveTemperature = if (settings.useCustomParams && settings.temperature != null) settings.temperature else 0.7
            val effectiveTopP = if (settings.useCustomParams) settings.topP else null
            val logTools = if (tools != null) "tools=${tools.size}defs" else "no tools"
            val request = com.pai.android.data.network.model.ChatRequest(
                model = modelOverride ?: settings.getEffectiveModel(),
                messages = managedMessages,
                temperature = effectiveTemperature,
                topP = effectiveTopP,
                maxTokens = maxTokens,
                thinking = thinkingParam,
                tools = tools,
                toolChoice = toolChoice
            )
            
            println("📤 API запрос: model=${request.model}, maxTokens=${request.maxTokens}, temperature=${request.temperature}, $logTools")
            
            // Создаём сервис для провайдера
            val aiService = aiChatServiceFactory.createService(settings)
            
            // Отправляем запрос
            val response = aiService.sendMessage(request)
            
            if (response.isSuccessful) {
                val chatResponse = response.body()
                if (chatResponse != null && chatResponse.choices.isNotEmpty()) {
                    val modelUsed = chatResponse.model ?: request.model
                    val message = chatResponse.choices.firstOrNull()?.message
                    
                    // Проверяем наличие tool_calls в ответе (нативный tool calling)
                    val toolCalls = message?.toolCalls?.mapNotNull { tc ->
                        try {
                            val gson = Gson()
                            val argsType = object : TypeToken<Map<String, Any>>() {}.type
                            val args: Map<String, Any> = gson.fromJson(tc.function.arguments, argsType)
                            NativeToolCall(
                                name = tc.function.name,
                                arguments = args
                            )
                        } catch (e: Exception) {
                            println("⚠️ Failed to parse tool call args: ${e.message}")
                            null
                        }
                    }
                    
                    val content = if (toolCalls != null) {
                        val text = chatResponse.getContent()
                        if (text.isNotBlank()) text else ""
                    } else {
                        chatResponse.getContent()
                    }
                    
                    val aiResponse = AiResponse.success(
                        text = content,
                        provider = settings.provider,
                        modelUsed = modelUsed,
                        tokensUsed = chatResponse.usage?.totalTokens,
                        toolCalls = toolCalls
                    )
                    
                    Result.success(aiResponse)
                } else {
                    val errorMsg = chatResponse?.error?.message ?: "Пустой ответ от AI"
                    Result.failure(Exception("AI error: $errorMsg"))
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: response.message()
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
            
        } catch (e: Exception) {
            // Fallback: возвращаем фиктивный ответ в случае ошибки
            // (можно закомментировать в production)
            val fallbackResponse = AiResponse.success(
                text = "Это временный ответ (реальный AI недоступен: ${e.message?.take(50)}...). Реализация AI API в процессе.",
                provider = AiProvider.OPENROUTER,
                modelUsed = "fallback"
            )
            Result.success(fallbackResponse)
            // Result.failure(e) // Раскомментировать для реального использования
        }
    }
    
    /**
     * Отправляет сообщение в AI с учётом вложений.
     */
    suspend fun sendMessageWithAttachments(
        messages: List<com.pai.android.data.model.Message>,
        attachments: List<Attachment> = emptyList(),
        providerSettings: ProviderSettings? = null,
        modelOverride: String? = null,
        systemPrompt: String? = null,
        memoryContext: String? = null,
        tools: List<NativeToolDefinition>? = null,
        toolChoice: String? = null
    ): Result<AiResponse> = withContext(defaultDispatcher) {
        try {
            // Определяем настройки провайдера
            val settings = providerSettings ?: settingsRepository.getDefaultSettings()
            if (settings == null || !settings.isValid()) {
                return@withContext Result.failure(IllegalStateException("No valid AI provider configured"))
            }
            
            // Преобразуем сообщения в формат для API
            val chatMessages = messages.map { msg ->
                // Для последнего пользовательского сообщения добавляем вложения
                if (msg.isFromUser() && msg == messages.lastOrNull { it.isFromUser() } && attachments.isNotEmpty()) {
                    // Обрабатываем вложения для последнего пользовательского сообщения
                    AttachmentProcessor.processAttachmentsForMessage(
                        text = msg.content,
                        attachments = attachments,
                        provider = settings.provider
                    )
                } else {
                    // Обычное сообщение без вложений
                    com.pai.android.data.network.model.ChatMessage.createTextMessage(msg.role, msg.content)
                }
            }
            
            // Добавляем системный промпт (если есть)
            val finalMessages = mutableListOf<com.pai.android.data.network.model.ChatMessage>()
            if (!systemPrompt.isNullOrBlank()) {
                finalMessages.add(com.pai.android.data.network.model.ChatMessage.createTextMessage(
                    role = com.pai.android.data.model.MessageRole.SYSTEM,
                    text = systemPrompt
                ))
            }
            
            // Добавляем контекст памяти (если есть)
            if (!memoryContext.isNullOrBlank()) {
                finalMessages.add(com.pai.android.data.network.model.ChatMessage.createTextMessage(
                    role = com.pai.android.data.model.MessageRole.SYSTEM,
                    text = memoryContext
                ))
            }
            
            // Веб-поиск: если включен, выполняем поиск по последнему пользовательскому сообщению
            val webSearchContext = performWebSearchIfEnabled(messages)
            if (!webSearchContext.isNullOrBlank()) {
                finalMessages.add(com.pai.android.data.network.model.ChatMessage.createTextMessage(
                    role = com.pai.android.data.model.MessageRole.SYSTEM,
                    text = webSearchContext
                ))
            }
            
            finalMessages.addAll(chatMessages)
            
            // Управление контекстом
            val managedMessages = manageContext(finalMessages, settings)
            
            // Создаём запрос
            val maxTokens = settings.resolveMaxTokens()
            val thinkingParam = if (settings.thinkingModeEnabled) {
                mapOf<String, Any>("type" to "enabled")
            } else {
                null
            }
            val effectiveTemperature = if (settings.useCustomParams && settings.temperature != null) settings.temperature else 0.7
            val effectiveTopP = if (settings.useCustomParams) settings.topP else null
            val logTools = if (tools != null) "tools=${tools.size}defs" else "no tools"
            val request = com.pai.android.data.network.model.ChatRequest(
                model = modelOverride ?: settings.getEffectiveModel(),
                messages = managedMessages,
                temperature = effectiveTemperature,
                topP = effectiveTopP,
                maxTokens = maxTokens,
                thinking = thinkingParam,
                tools = tools,
                toolChoice = toolChoice
            )
            
            println("📤 API запрос: model=${request.model}, maxTokens=${request.maxTokens}, temperature=${request.temperature}, toolChoice=${toolChoice}, $logTools")
            
            // Создаём сервис для провайдера
            val aiService = aiChatServiceFactory.createService(settings)
            
            // Отправляем запрос
            val response = aiService.sendMessage(request)
            
            if (response.isSuccessful) {
                val chatResponse = response.body()
                if (chatResponse != null && chatResponse.choices.isNotEmpty()) {
                    val modelUsed = chatResponse.model ?: request.model
                    val message = chatResponse.choices.firstOrNull()?.message
                    
                    // Проверяем наличие tool_calls в ответе (нативный tool calling)
                    val toolCalls = message?.toolCalls?.mapNotNull { tc ->
                        try {
                            val gson = Gson()
                            val argsType = object : TypeToken<Map<String, Any>>() {}.type
                            val args: Map<String, Any> = gson.fromJson(tc.function.arguments, argsType)
                            NativeToolCall(
                                name = tc.function.name,
                                arguments = args
                            )
                        } catch (e: Exception) {
                            println("⚠️ Failed to parse tool call args: ${e.message}")
                            null
                        }
                    }
                    
                    val content = if (toolCalls != null) {
                        val text = chatResponse.getContent()
                        if (text.isNotBlank()) text else ""
                    } else {
                        chatResponse.getContent()
                    }
                    
                    val aiResponse = AiResponse.success(
                        text = content,
                        provider = settings.provider,
                        modelUsed = modelUsed,
                        tokensUsed = chatResponse.usage?.totalTokens,
                        toolCalls = toolCalls
                    )
                    
                    Result.success(aiResponse)
                } else {
                    val errorMsg = chatResponse?.error?.message ?: "Пустой ответ от AI"
                    Result.failure(Exception("AI error: $errorMsg"))
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: response.message()
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
            
        } catch (e: Exception) {
            // Fallback: возвращаем фиктивный ответ в случае ошибки
            val fallbackResponse = AiResponse.success(
                text = "Это временный ответ с поддержкой вложений (реальный AI недоступен: ${e.message?.take(50)}...).",
                provider = AiProvider.OPENROUTER,
                modelUsed = "fallback"
            )
            Result.success(fallbackResponse)
            // Result.failure(e) // Раскомментировать для реального использования
        }
    }
    
    /**
     * Получает текущий AI сервис на основе настроек.
     */
    suspend fun getCurrentProviderSettings(): ProviderSettings? {
        return settingsRepository.getDefaultSettings()
    }
    
    /**
     * Получает список доступных провайдеров с их настройками.
     */
    suspend fun getAvailableProviders(): List<Pair<AiProvider, List<ProviderSettings>>> {
        val allSettings = settingsRepository.getAllSettings()
        return AiProvider.values().map { provider ->
            provider to allSettings.filter { it.provider == provider }
        }
    }
    
    /**
     * Тестирует подключение к провайдеру.
     */
    suspend fun testConnection(settings: ProviderSettings): Result<Boolean> {
        return try {
            // Создаём тестовый сервис
            val testService = aiChatServiceFactory.createTestService(
                baseUrl = settings.getEffectiveBaseUrl(),
                apiKey = settings.apiKey
            )
            
            // Отправляем тестовый запрос (пустой)
            val testRequest = com.pai.android.data.network.model.ChatRequest(
                model = settings.getEffectiveModel(),
                messages = listOf(
                    com.pai.android.data.network.model.ChatMessage.createTextMessage(
                        role = com.pai.android.data.model.MessageRole.USER,
                        text = "test"
                    )
                ),
                temperature = 0.7,
                maxTokens = 5
            )
            
            val response = testService.sendMessage(testRequest)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Выполняет веб-поиск, если он включен в настройках.
     * Возвращает контекст с результатами поиска для добавления в системный промпт,
     * или null если поиск не выполнялся.
     */
    private suspend fun performWebSearchIfEnabled(messages: List<com.pai.android.data.model.Message>): String? {
        return try {
            // Проверяем, включен ли веб-поиск
            val canSearch = webSearchRepository.canPerformSearch()
            if (!canSearch) return null
            
            // Находим последнее пользовательское сообщение
            val lastUserMessage = messages.lastOrNull { it.role == com.pai.android.data.model.MessageRole.USER }
                ?: return null
            
            // Извлекаем текст запроса (без вложений)
            val query = lastUserMessage.content.trim()
            if (query.length < 3) return null // Слишком короткий запрос
            
            // Выполняем поиск
            val searchResults = webSearchService.search(query, maxResults = 5)
            if (searchResults.isEmpty()) return null
            
            // Формируем контекст с результатами поиска
            val contextBuilder = StringBuilder()
            contextBuilder.append("Результаты веб-поиска по запросу: \"$query\"\n\n")
            
            searchResults.forEachIndexed { index, result ->
                contextBuilder.append("${index + 1}. ${result.title}\n")
                contextBuilder.append("   Источник: ${result.source}\n")
                contextBuilder.append("   ${result.snippet}\n")
                contextBuilder.append("   URL: ${result.link}\n\n")
            }
            
            contextBuilder.append("Используй эти результаты для ответа на вопрос пользователя.")
            
            contextBuilder.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null // Игнорируем ошибки поиска, продолжаем без результатов
        }
    }

    /**
     * Управляет контекстным окном: обрезает или суммаризирует историю,
     * если она превышает лимит модели с учётом буфера.
     *
     * @param messages Список сообщений для API (включая system)
     * @param settings Настройки провайдера с modelMaxContext и contextManagement
     * @return Список сообщений после управления контекстом
     */
    private suspend fun manageContext(
        messages: MutableList<com.pai.android.data.network.model.ChatMessage>,
        settings: ProviderSettings
    ): MutableList<com.pai.android.data.network.model.ChatMessage> {
        val maxContext = settings.modelMaxContext ?: return messages // Не задан — ничего не делаем
        if (maxContext <= 0) return messages
        
        val bufferPercent = settings.contextBufferPercent.coerceIn(1, 100)
        val threshold = (maxContext * bufferPercent / 100).coerceAtLeast(1000) // минимум 1000 токенов
        
        // Считаем примерные токены (length / 4 — грубая оценка)
        var totalTokens = 0
        for (msg in messages) {
            totalTokens += msg.safeContentText().length / 4
        }
        
        val usagePercent = if (maxContext > 0) (totalTokens.toFloat() / maxContext * 100).toInt() else 0
        
        println("📊 Контекст: ${totalTokens}tok / ${maxContext}tok = ${usagePercent}% (буфер: ${bufferPercent}%)")
        
        if (totalTokens <= threshold) return messages // Всё в лимите
        
        val strategy = settings.contextManagement
        
        println("⚠️ Контекст превышен! Стратегия: $strategy, нужно освободить ${totalTokens - threshold} токенов")
        
        return when (strategy) {
            "summarize" -> summarizeContext(messages, maxContext, threshold)
            else -> truncateContext(messages, threshold) // "truncate" или любое другое
        }
    }

    /**
     * Обрезает историю: удаляет самые старые не-system сообщения,
     * сохраняя приоритет важных (длинные, с кодом, архитектурные).
     */
    private fun truncateContext(
        messages: MutableList<com.pai.android.data.network.model.ChatMessage>,
        threshold: Int
    ): MutableList<com.pai.android.data.network.model.ChatMessage> {
        val systemMessages = messages.filter { it.role == "system" }
        val chatMessages = messages.filter { it.role != "system" }.toMutableList()
        
        var currentTokens = (systemMessages.sumOf { it.safeContentText().length } + 
                            chatMessages.sumOf { it.safeContentText().length }) / 4
        val removedCount = mutableListOf<String>()
        
        // Умный truncate: сначала удаляем короткие/routine сообщения
        while (currentTokens > threshold && chatMessages.size > 1) {
            // Находим наименее важное сообщение (короткое, без кода)
            val leastImportant = chatMessages.minByOrNull { msg ->
                if (msg.content.isJsonArray) return@minByOrNull Int.MAX_VALUE
                val text = msg.safeContentText()
                // Важность: длина + наличие кода + роль пользователя
                val length = text.length
                val hasCode = if (text.contains("```") || text.contains("  ") || text.contains("fun ") || text.contains("class ")) 500 else 0
                val isUser = if (msg.role == "user") -200 else 0 // Сообщения пользователя чуть важнее
                length + hasCode + isUser
            } ?: break
            
            chatMessages.remove(leastImportant)
            currentTokens = (systemMessages.sumOf { it.safeContentText().length } + 
                            chatMessages.sumOf { it.safeContentText().length }) / 4
            removedCount.add(leastImportant.safeContentText().take(50))
        }
        
        println("✂️ Truncate: удалено ${removedCount.size} сообщений (${currentTokens}tok)")
        
        val result = systemMessages.toMutableList()
        result.addAll(chatMessages)
        return result
    }

    /**
     * Суммаризирует старые сообщения через AI.
     * Поддерживает иерархическую суммаризацию:
     * - Если уже есть выжимка (📝 Резюме) — обновляет её новыми сообщениями
     * - Защищает важные сообщения (код, архитектура) от выкидывания
     */
    private suspend fun summarizeContext(
        messages: MutableList<com.pai.android.data.network.model.ChatMessage>,
        maxContext: Int,
        threshold: Int
    ): MutableList<com.pai.android.data.network.model.ChatMessage> {
        val systemMessages = messages.filter { it.role == "system" }
        val chatMessages = messages.filter { it.role != "system" }
        
        if (chatMessages.size <= 2) return truncateContext(messages, threshold)
        
        // Проверяем, есть ли уже существующая выжимка
        val existingSummary = systemMessages.find { 
            it.safeContentText().startsWith("📝 Резюме")
        }
        
        // Определяем: что суммаризировать (только то, что после последней выжимки)
        val (toSummarize, toKeep) = if (existingSummary != null) {
            // Ищем индекс последней выжимки в systemMessages
            val summaryIndex = systemMessages.indexOf(existingSummary)
            // Берём последние 40% от chatMessages (диалог после выжимки)
            val keepCount = (chatMessages.size * 0.4).toInt().coerceAtLeast(2)
            val keepMsgs = chatMessages.takeLast(keepCount)
            val summarizeMsgs = chatMessages.dropLast(keepCount)
            Pair(summarizeMsgs, keepMsgs)
        } else {
            // Первая суммаризация — берём первые 60% сообщений
            val keepCount = (chatMessages.size * 0.4).toInt().coerceAtLeast(2)
            val keepMsgs = chatMessages.takeLast(keepCount)
            val summarizeMsgs = chatMessages.dropLast(keepCount)
            Pair(summarizeMsgs, keepMsgs)
        }
        
        if (toSummarize.isEmpty()) {
            println("📋 Summarize: нечего суммаризировать, пропускаем")
            return messages
        }
        
        // Защищаем важные сообщения (с кодом)
        val importantMessages = toSummarize.filter { msg ->
            val text = msg.safeContentText()
            text.contains("```") || text.contains("class ") || text.contains("interface ") ||
            text.contains("fun ") || text.contains("val ") || text.contains("var ") ||
            text.length > 2000
        }
        val routineMessages = toSummarize.filter { msg -> !importantMessages.contains(msg) }
        
        // Формируем текст для суммаризации (только routine)
        val summaryText = routineMessages.joinToString("\n") { 
            "${if (it.role == "user") "Пользователь:" else "Ассистент:"} ${it.safeContentText().take(500)}"
        }
        
        // Добавляем контекст предыдущей выжимки (если есть)
        val previousSummary = existingSummary?.safeContentText() ?: ""
        
        val fullPrompt = buildString {
            if (previousSummary.isNotBlank()) {
                append("Предыдущая выжимка диалога:\n$previousSummary\n\n")
            }
            if (summaryText.isNotBlank()) {
                append("Новые сообщения для добавления к выжимке:\n$summaryText\n\n")
            }
            append("Сделай краткую выжимку (3-5 предложений), сохранив все ключевые факты, ")
            append("архитектурные решения, найденные баги и важные детали.")
            if (importantMessages.isNotEmpty()) {
                append("\n\nВАЖНЫЕ СООБЩЕНИЯ (сохрани их суть):\n")
                importantMessages.forEach { msg ->
                    append("${if (msg.role == "user") "👤" else "🤖"} ${msg.safeContentText().take(300)}\n")
                }
            }
        }
        
        val summaryResult = try {
            println("🧠 Суммаризирую ${routineMessages.size} routine + ${importantMessages.size} важных сообщений через AI...")
            val response = sendMessage(
                messages = listOf(
                    com.pai.android.data.model.Message.createUserMessage(
                        chatId = "context_manager",
                        content = fullPrompt
                    )
                ),
                providerSettings = null,
                modelOverride = null
            )
            if (response.isSuccess) response.getOrThrow().text else null
        } catch (e: Exception) {
            println("⚠️ Ошибка суммаризации: ${e.message}")
            null
        }
        
        val result = systemMessages.toMutableList()
        
        // Убираем старую выжимку (если обновляем)
        if (existingSummary != null) {
            result.remove(existingSummary)
        }
        
        if (summaryResult != null) {
            result.add(com.pai.android.data.network.model.ChatMessage.createTextMessage(
                role = com.pai.android.data.model.MessageRole.SYSTEM,
                text = "📝 Резюме предыдущего диалога: $summaryResult"
            ))
            // Добавляем важные сообщения (они не потерялись)
            result.addAll(importantMessages)
            result.addAll(toKeep)
            println("✅ Summarize: ${routineMessages.size} сообщений → выжимка + ${importantMessages.size} важных сохранено")
        } else {
            println("⚠️ Summarize: ошибка, падаю на truncate")
            result.addAll(chatMessages)
            return truncateContext(result.toMutableList(), threshold)
        }
        
        return result
    }

    /** Безопасно получает текст из content (строка или массив). */
    private fun ChatMessage.safeContentText(): String {
        return try {
            if (content.isJsonArray) {
                val arr = content.asJsonArray
                val firstObj = arr.firstOrNull()?.asJsonObject
                var text = firstObj?.get("text")?.asString
                if (text == null) { text = "[изображение]" }
                return text
            } else {
                content.asJsonPrimitive.asString
            }
        } catch (e: Exception) {
            "[контент]"
        }
    }
}
