package com.pai.android.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pai.android.agent.LocalAiInteraction
import com.pai.android.agent.LocalReActAgent
import com.pai.android.agent.LocalToolDescriptions
import com.pai.android.agent.SmartRouter
import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.AiResponse
import com.pai.android.data.model.Attachment
import com.pai.android.data.model.AttachmentType
import com.pai.android.data.model.MessageRole
import com.pai.android.data.model.NativeToolCall
import com.pai.android.data.model.ProviderSettings
import com.pai.android.data.model.SmartRouterConfig
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
    private val webSearchRepository: WebSearchRepository,
    private val localAiInteraction: LocalAiInteraction,
    private val smartRouter: SmartRouter,
    private val smartRouterRepository: SmartRouterRepository,
    private val localReActAgent: LocalReActAgent,
    private val toolRegistry: com.pai.android.agent.ToolRegistry
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

            // Обновляем информацию о текущей модели для UI
            com.pai.android.agent.DecisionEngine.processingModelName = modelOverride ?: settings.getEffectiveModel()
            
            // === Smart Router: только для запросов без явного провайдера ===
            val routerConfig = smartRouterRepository.get()
            com.pai.android.agent.DecisionEngine.processingSmartRouterEnabled = routerConfig.enabled
            if (providerSettings == null && routerConfig.enabled) {
                // Извлекаем чистый запрос пользователя (без технического контекста)
                val lastMsg = messages.lastOrNull { it.isFromUser() }
                val userPromptRaw = lastMsg?.content ?: ""
                val cleanUserQuery = userPromptRaw
                    .substringBefore("\nContext:")
                    .substringBefore("\nКонтекст:")
                    .removePrefix("User query: ")
                    .removePrefix("Запрос пользователя: ")
                    .trim()
                val contextTokens = messages.fold(0) { acc, msg -> acc + msg.content.length / 4 }
                println("🔧 SmartRouter: AiRepository cleanPrompt='${cleanUserQuery.take(100)}' (len=${cleanUserQuery.length}), fullLen=${userPromptRaw.length}")
                val decision = smartRouter.route(
                    prompt = cleanUserQuery,
                    attachments = emptyList(),
                    contextTokens = contextTokens,
                    config = routerConfig
                )
                when (decision) {
                    is com.pai.android.agent.RouteDecision.Local -> {
                        println("🔧 SmartRouter: LOCAL decision - redirecting to LiteRT")
                        val localSettings = settingsRepository.getSettingsForProvider(AiProvider.LITE_RT).firstOrNull()
                        if (localSettings != null && localSettings.isValid()) {
                            val fullSystemContext = buildString {
                                if (!systemPrompt.isNullOrBlank()) { append(systemPrompt.trim()); append("\n\n") }
                                if (!memoryContext.isNullOrBlank()) { append(memoryContext.trim()); append("\n\n") }
                            }.trimEnd()
                            return@withContext handleLocalInference(localSettings, messages, fullSystemContext.ifEmpty { null })
                        } else {
                            println("⚠️ SmartRouter: LOCAL decision but LiteRT not configured, falling back to network")
                        }
                    }
                    is com.pai.android.agent.RouteDecision.Network -> {
                        println("🔧 SmartRouter: NETWORK decision (settingsId=${decision.providerSettingsId})")
                        if (decision.providerSettingsId.isNotBlank()) {
                            val networkSettings = settingsRepository.getSettings(decision.providerSettingsId)
                            if (networkSettings != null && networkSettings.isValid()) {
                                // Переключаем на сетевой провайдер без Router (providerSettings != null)
                                return@withContext sendMessage(
                                    messages = messages,
                                    providerSettings = networkSettings,
                                    systemPrompt = systemPrompt,
                                    memoryContext = memoryContext,
                                    tools = tools,
                                    toolChoice = toolChoice
                                )
                            } else {
                                println("⚠️ SmartRouter: NETWORK provider settings невалидны, fallback на текущего провайдера")
                            }
                        } else {
                            println("⚠️ SmartRouter: NETWORK без settingsId, продолжаем с текущим провайдером")
                        }
                    }
                    is com.pai.android.agent.RouteDecision.Fallback -> {
                        println("🔧 SmartRouter: FALLBACK - ${decision.reason}")
                        return@withContext Result.failure(Exception(decision.reason))
                    }
                    is com.pai.android.agent.RouteDecision.Hybrid -> {
                        println("🔧 SmartRouter: HYBRID - decomposing query")
                        return@withContext handleHybrid(
                            messages = messages,
                            systemPrompt = systemPrompt,
                            memoryContext = memoryContext,
                            settings = settings
                        )
                    }
                    else -> {}
                }
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
            
            // LITE_RT: реальный вызов локальной модели
            if (settings.provider == AiProvider.LITE_RT) {
                val localResult = handleLocalInference(settings, messages, systemPrompt)
                return@withContext localResult
            }
            
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

            // === Smart Router: только для запросов без явного провайдера ===
            val routerConfig = smartRouterRepository.get()
            if (providerSettings == null && routerConfig.enabled) {
                val lastMsg = messages.lastOrNull { it.isFromUser() }
                    val userPromptRaw = lastMsg?.content ?: ""
                    val cleanUserQuery = userPromptRaw
                        .substringBefore("\nContext:")
                        .substringBefore("\nКонтекст:")
                        .removePrefix("User query: ")
                        .removePrefix("Запрос пользователя: ")
                        .trim()
                    val contextTokens = messages.fold(0) { acc, msg -> acc + msg.content.length / 4 }
                    println("🔧 SmartRouter (attachments): cleanPrompt='${cleanUserQuery.take(80)}' (len=${cleanUserQuery.length}), attachments=${attachments.size}")
                    val decision = smartRouter.route(
                        prompt = cleanUserQuery,
                        attachments = attachments,
                        contextTokens = contextTokens,
                        config = routerConfig
                    )
                    when (decision) {
                        is com.pai.android.agent.RouteDecision.Local -> {
                            println("🔧 SmartRouter: LOCAL decision with attachments - redirecting to LiteRT")
                            val localSettings = settingsRepository.getSettingsForProvider(AiProvider.LITE_RT).firstOrNull()
                            if (localSettings != null && localSettings.isValid()) {
                                val fullSystemContext = buildString {
                                    if (!systemPrompt.isNullOrBlank()) { append(systemPrompt.trim()); append("\n\n") }
                                    if (!memoryContext.isNullOrBlank()) { append(memoryContext.trim()); append("\n\n") }
                                }.trimEnd()
                                return@withContext handleLocalInference(localSettings, messages, fullSystemContext.ifEmpty { null }, attachments = attachments)
                            } else {
                                println("⚠️ SmartRouter: LOCAL but LiteRT not configured")
                            }
                        }
                        is com.pai.android.agent.RouteDecision.Network -> {
                            println("🔧 SmartRouter (with attachments): NETWORK decision (settingsId=${decision.providerSettingsId})")
                            if (decision.providerSettingsId.isNotBlank()) {
                                val networkSettings = settingsRepository.getSettings(decision.providerSettingsId)
                                if (networkSettings != null && networkSettings.isValid()) {
                                    return@withContext sendMessageWithAttachments(
                                        messages = messages,
                                        attachments = attachments,
                                        providerSettings = networkSettings,
                                        systemPrompt = systemPrompt,
                                        memoryContext = memoryContext,
                                        tools = tools,
                                        toolChoice = toolChoice
                                    )
                                } else {
                                    println("⚠️ SmartRouter (attachments): NETWORK provider невалиден, fallback")
                                }
                            } else {
                                println("⚠️ SmartRouter (attachments): NETWORK без settingsId")
                            }
                        }
                        is com.pai.android.agent.RouteDecision.Fallback -> {
                            println("🔧 SmartRouter: FALLBACK - ${decision.reason}, пробуем локальную модель")
                            val localFallbackResult = trySendToLocal()
                            if (localFallbackResult != null) {
                                return@withContext localFallbackResult
                            }
                            return@withContext Result.failure(Exception(decision.reason))
                        }
                        else -> {}
                    }
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
            
            // LITE_RT: реальный вызов локальной модели
            if (settings.provider == AiProvider.LITE_RT) {
                val localResult = handleLocalInference(settings, messages, systemPrompt, attachments = attachments)
                return@withContext localResult
            }
            
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
     * Выполняет инференс на локальной модели LiteRT LM.
     * Автоматически загружает модель, если она ещё не загружена.
     */
    private suspend fun handleLocalInference(
        settings: ProviderSettings,
        messages: List<com.pai.android.data.model.Message>,
        systemPrompt: String?,
        attachments: List<Attachment> = emptyList()
    ): Result<AiResponse> {
        val modelName = settings.localModelName.ifBlank { settings.modelName ?: "gemma-4-e2b" }
        com.pai.android.agent.DecisionEngine.processingModelName = modelName
        
        try {
            // Загружаем модель, если ещё не загружена
            if (!localAiInteraction.isLoaded() || localAiInteraction.getLoadedModelId() != modelName) {
                println("Загрузка локальной модели $modelName...")
                val loadResult: Result<Unit> = localAiInteraction.loadModel(modelName, useGpuBackend = settings.useGpuBackend)
                if (loadResult.isFailure) {
                    return Result.failure<AiResponse>(Exception(
                        "Ошибка загрузки модели $modelName: ${loadResult.exceptionOrNull()?.message}"
                    ))
                }
                println("Модель $modelName загружена")
            }
            
            val userMessage = messages.lastOrNull { it.role == MessageRole.USER }
            val prompt = userMessage?.content ?: "..."
            val imageCount = attachments.count { it.type == AttachmentType.IMAGE }
            
            // ReAct-режим: только текст, с инструментами
            val useReAct = imageCount == 0 && localAiInteraction.isLoaded()
            
            val result: Result<String> = if (useReAct) {
                println("🔧 LocalReAct: запуск с инструментами для '$modelName'...")
                // Извлекаем чистый запрос (обрезаем контекст, который мог добавить DecisionEngine)
                val cleanQuery = prompt
                    .substringBefore("\nContext:")
                    .substringBefore("\nКонтекст:")
                    .removePrefix("User query: ")
                    .removePrefix("Запрос пользователя: ")
                    .trim()

                val systemWithTools = buildString {
                    if (!systemPrompt.isNullOrBlank()) { append(systemPrompt.trim()); append("\n\n") }
                    append(LocalToolDescriptions.SYSTEM_PROMPT)
                }

                localReActAgent.run(
                    userPrompt = cleanQuery.ifEmpty { prompt },
                    systemPrompt = systemWithTools,
                    executor = { name, args ->
                        val toolCall = toolRegistry.getTool(name)
                        if (toolCall != null) {
                            try {
                                // Конвертируем Map<String,String> в Map<String,Any>
                                val anyArgs: Map<String, Any> = args
                                val result = toolCall.execute(anyArgs)
                                when (result) {
                                    is com.pai.android.agent.ToolResult.Success -> result.output
                                    is com.pai.android.agent.ToolResult.Error -> "Error: ${result.error}"
                                    is com.pai.android.agent.ToolResult.ConfirmationRequired ->
                                        "Confirmation needed: ${result.question}"
                                }
                            } catch (e: Exception) {
                                "Error: ${e.message}"
                            }
                        } else {
                            "Error: unknown tool '$name'. Available: ${LocalToolDescriptions.ALLOWED_TOOL_NAMES.joinToString(", ")}"
                        }
                    }
                )
            } else {
                println("Генерация на локальной $modelName (изображений: $imageCount)...")
                localAiInteraction.generate(
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    attachments = attachments,
                    thinkingModeEnabled = settings.thinkingModeEnabled
                )
            }
            
            return if (result.isSuccess) {
                Result.success<AiResponse>(AiResponse.success(
                    text = result.getOrThrow(),
                    provider = AiProvider.LITE_RT,
                    modelUsed = modelName
                ))
            } else {
                Result.failure<AiResponse>(Exception(
                    "Ошибка локальной генерации: ${result.exceptionOrNull()?.message}"
                ))
            }
        } catch (e: Exception) {
            println("LITE_RT ошибка: ${e.message}")
            return Result.failure<AiResponse>(e)
        }
    }

    // ======================= Hybrid Router =======================

    // ======================= Hybrid Router =======================

    /**
     * Гибридный режим: **текущая модель** (любая — DeepSeek, GPT, Gemma, Ollama)
     * получает запрос, разбивает его на шаги, оценивает сложность каждого (1-10),
     * простые (1-4) отдаёт локальной модели LiteRT, сложные (5-10) — сетевой.
     */
    private suspend fun handleHybrid(
        messages: List<com.pai.android.data.model.Message>,
        systemPrompt: String?,
        memoryContext: String?,
        settings: ProviderSettings
    ): Result<AiResponse> {
        // Сетевая модель — из конфига Smart Router (куда отправлять сложные шаги)
        val routerConfig = smartRouterRepository.get()
        val networkSettings = if (routerConfig.networkProviderSettingsId.isNotBlank()) {
            settingsRepository.getSettings(routerConfig.networkProviderSettingsId)
        } else {
            settings
        }
        val effectiveNetworkSettings = networkSettings ?: settings

        val lastUserMsg = messages.lastOrNull { it.isFromUser() }?.content ?: ""
        val cleanQuery = lastUserMsg
            .substringBefore("\nContext:")
            .substringBefore("\nКонтекст:")
            .removePrefix("User query: ")
            .removePrefix("Запрос пользователя: ")
            .trim()

        println("🔧 Hybrid: plan model=${settings.getEffectiveModel()}, network model=${effectiveNetworkSettings.getEffectiveModel()}")
        println("🔧 Hybrid: query='${cleanQuery.take(80)}'")

        // ======================= ШАГ 1: Текущая модель составляет план =======================

        val plannerPrompt = buildString {
            appendLine("Ты — планировщик. Пользователь задал задачу. Разбей её на 2-4 шага.")
            appendLine()
            appendLine("Для каждого шага укажи сложность от 1 до 10 в формате:")
            appendLine("  [сложность] Описание шага")
            appendLine()
            appendLine("Где:")
            appendLine("  1-4 — простой шаг (короткий ответ, факт, определение)")
            appendLine("  5-10 — сложный/творческий шаг (анализ, стихи, код, сравнение)")
            appendLine()
            appendLine("Формат ответа — только нумерованный список:")
            appendLine("1. [3] Краткое определение чёрной дыры")
            appendLine("2. [8] Стихотворение про космос")
            appendLine()
            append("Запрос пользователя: $cleanQuery")
            append("\nПлан:")
        }

        // Определяем, кто будет планировать:
        // - Если текущая модель — LiteRT и она загружена → локальное планирование
        // - Если текущая модель — LiteRT, но не загружена → используем сетевую модель для планирования
        // - Если текущая модель — сетевая (DeepSeek/GPT/...) → она и планирует
        val planModelSettings = if (settings.provider == AiProvider.LITE_RT) {
            if (localAiInteraction.isLoaded()) settings else effectiveNetworkSettings
        } else {
            settings
        }

        val planText = if (planModelSettings.provider == AiProvider.LITE_RT && localAiInteraction.isLoaded()) {
            println("🔧 Hybrid: локальное планирование (${planModelSettings.getEffectiveModel()})...")
            val result = localAiInteraction.generate(plannerPrompt, maxTokens = 512)
            if (result.isSuccess) {
                result.getOrThrow()
            } else {
                println("🔧 Hybrid: локальное планирование не удалось: ${result.exceptionOrNull()?.message}")
                return sendMessage(
                    messages = messages,
                    providerSettings = settings,
                    systemPrompt = systemPrompt,
                    memoryContext = memoryContext
                )
            }
        } else {
            println("🔧 Hybrid: запрос плана к ${planModelSettings.getEffectiveModel()}...")
            val aiService = aiChatServiceFactory.createService(planModelSettings)
            val planResponse = aiService.sendMessage(
                com.pai.android.data.network.model.ChatRequest(
                    model = planModelSettings.getEffectiveModel(),
                    messages = listOf(
                        com.pai.android.data.network.model.ChatMessage.createTextMessage(
                            role = com.pai.android.data.model.MessageRole.USER,
                            text = plannerPrompt
                        )
                    ),
                    temperature = 0.3,
                    maxTokens = 1024
                )
            )
            if (planResponse.isSuccessful) {
                planResponse.body()?.getContent() ?: ""
            } else {
                val errorBody = planResponse.errorBody()?.string() ?: planResponse.message()
                println("🔧 Hybrid: планирование не удалось (HTTP ${planResponse.code()}: $errorBody), пробуем локалку")
                val localPlan = trySendToLocal()
                if (localPlan != null) {
                    return localPlan
                }
                return sendMessage(
                    messages = messages,
                    providerSettings = settings,
                    systemPrompt = systemPrompt,
                    memoryContext = memoryContext
                )
            }
        }
        println("🔧 Hybrid: план получен от ${planModelSettings.getEffectiveModel()}:\n$planText")

        // ======================= ШАГ 2: Парсинг плана =======================

        data class Step(val description: String, val complexity: Int)

        val steps = mutableListOf<Step>()
        for (line in planText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            // Убираем нумерацию: "1. " или "1)" или "- "
            val content = trimmed
                .replaceFirst(Regex("^\\d+[\\.\\)]\\s*"), "")
                .replaceFirst(Regex("^[-\\*]\\s*"), "")
                .trim()
            // Ищем [число] — сложность
            val complexityMatch = Regex("\\[(\\d+)\\]").find(content)
            val complexity = complexityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val desc = content
                .replaceFirst(Regex("\\[\\d+\\]"), "")
                .replaceFirst(Regex("\\[local\\]", RegexOption.IGNORE_CASE), "")
                .replaceFirst(Regex("\\[network\\]", RegexOption.IGNORE_CASE), "")
                .replaceFirst(Regex("сложность\\s*\\d+", RegexOption.IGNORE_CASE), "")
                .trim()
            if (desc.isNotBlank()) {
                steps.add(Step(desc, complexity.coerceIn(1, 10)))
            }
        }

        if (steps.isEmpty()) {
            println("🔧 Hybrid: ни одного шага не распарсено, fallback")
            return sendMessage(
                messages = messages,
                providerSettings = settings,
                systemPrompt = systemPrompt,
                memoryContext = memoryContext
            )
        }

        println("🔧 Hybrid: распарсено ${steps.size} шагов: ${steps.map { "[${it.complexity}]${if (it.complexity <= 4) "L" else "N"}" }}")

        // ======================= ШАГ 3: Выполнение шагов =======================

        val localThreshold = routerConfig.hybridThreshold.coerceIn(1, 9) // сложность 1-N → local, N+1-10 → network
        val results = mutableListOf<String>()

        for ((index, step) in steps.withIndex()) {
            val stepNum = index + 1
            val useLocal = step.complexity <= localThreshold
            println("🔧 Hybrid: шаг $stepNum/${steps.size} — ${if (useLocal) "LOCAL" else "NETWORK"} (сложность ${step.complexity}): '${step.description.take(60)}'")

            if (useLocal) {
                // Пытаемся выполнить на LiteRT, если недоступен — фолбэк на сеть
                try {
                    val localSettings = settingsRepository.getSettingsForProvider(AiProvider.LITE_RT).firstOrNull()
                    if (localSettings != null && localSettings.isValid()) {
                        val localResult = handleLocalInference(
                            settings = localSettings,
                            messages = listOf(com.pai.android.data.model.Message.createAssistantMessage("hybrid", "")),
                            systemPrompt = "Ответь на следующий запрос кратко (1-3 предложения): " + step.description
                        )
                        if (localResult.isSuccess) {
                            results.add("Шаг $stepNum: ${localResult.getOrThrow().text}")
                        } else {
                            println("🔧 Hybrid: локалка ошибка, фолбэк на сеть")
                            fallbackToNetwork(step.description, stepNum, results, effectiveNetworkSettings)
                        }
                    } else {
                        println("🔧 Hybrid: LiteRT не настроен, фолбэк на сеть")
                        fallbackToNetwork(step.description, stepNum, results, effectiveNetworkSettings)
                    }
                } catch (e: Exception) {
                    println("🔧 Hybrid: ошибка локалки: ${e.message}, фолбэк на сеть")
                    fallbackToNetwork(step.description, stepNum, results, effectiveNetworkSettings)
                }
            } else {
                // Выполняем на сетевой модели
                try {
                    val netResult = sendMessage(
                        messages = listOf(com.pai.android.data.model.Message.createUserMessage("hybrid", step.description)),
                        providerSettings = effectiveNetworkSettings,
                        systemPrompt = "Ответь на запрос (без лишних пояснений)."
                    )
                    if (netResult.isSuccess) {
                        results.add("Шаг $stepNum: ${netResult.getOrThrow().text}")
                    } else {
                        results.add("Шаг $stepNum: [ошибка сети: ${netResult.exceptionOrNull()?.message}]")
                    }
                } catch (e: Exception) {
                    results.add("Шаг $stepNum: [ошибка: ${e.message}]")
                }
            }
        }

        // ======================= ШАГ 4: Сборка ответа =======================

        val finalText = results.joinToString("\n\n")
        println("🔧 Hybrid: финальный ответ собран (${finalText.length} символов)")

        return Result.success(AiResponse.success(
            text = finalText,
            provider = settings.provider,
            modelUsed = "hybrid(${settings.getEffectiveModel()}/${effectiveNetworkSettings.getEffectiveModel()})"
        ))
    }

    /**
     * Фолбэк: выполняет шаг гибрида на сетевой модели (если локалка недоступна).
     */
    private suspend fun fallbackToNetwork(
        description: String,
        stepNum: Int,
        results: MutableList<String>,
        networkSettings: ProviderSettings
    ) {
        try {
            val netResult = sendMessage(
                messages = listOf(com.pai.android.data.model.Message.createUserMessage("hybrid", description)),
                providerSettings = networkSettings,
                systemPrompt = "Ответь на запрос (без лишних пояснений)."
            )
            if (netResult.isSuccess) {
                results.add("Шаг $stepNum: ${netResult.getOrThrow().text}")
            } else {
                results.add("Шаг $stepNum: [ошибка: ${netResult.exceptionOrNull()?.message}]")
            }
        } catch (e: Exception) {
            results.add("Шаг $stepNum: [ошибка: ${e.message}]")
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

    /**
     * Пытается отправить запрос на локальную модель (LiteRT).
     * Используется как fallback при недоступности сети.
     */
    private suspend fun trySendToLocal(): Result<AiResponse>? {
        try {
            val local = settingsRepository.getSettingsForProvider(AiProvider.LITE_RT).firstOrNull()
            if (local == null || !local.isValid()) {
                println("🔧 Fallback: LiteRT не настроен")
                return null
            }
            if (!localAiInteraction.isLoaded()) {
                println("🔧 Fallback: LiteRT не загружена")
                return null
            }
            println("🔧 Fallback: отправляем на LiteRT (${local.getEffectiveModel()})")
            com.pai.android.agent.DecisionEngine.processingModelName = local.getEffectiveModel()
            return handleLocalInference(
                settings = local,
                messages = listOf(com.pai.android.data.model.Message.createAssistantMessage("fallback", "")),
                systemPrompt = null
            )
        } catch (e: Exception) {
            println("🔧 Fallback: ошибка: ${e.message}")
            return null
        }
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
