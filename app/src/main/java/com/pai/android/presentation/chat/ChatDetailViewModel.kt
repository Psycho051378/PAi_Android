package com.pai.android.presentation.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.model.Attachment
import com.pai.android.data.model.Chat
import com.pai.android.data.model.DailyMemory
import com.pai.android.data.model.Message
import com.pai.android.data.model.MessageRole
import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.model.ProviderSettings
import com.pai.android.data.model.Role
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.repository.AttachmentRepository
import com.pai.android.data.repository.ChatRepository
import com.pai.android.data.repository.MemoryRepository
import com.pai.android.data.repository.RoleRepository
import com.pai.android.data.repository.SummaryRepository
import com.pai.android.data.model.SummaryType
import com.pai.android.data.processor.MessageProcessor
import com.pai.android.agent.DecisionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import kotlin.text.RegexOption
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Состояние подтверждения факта.
 */
data class FactConfirmationState(
    val showDialog: Boolean = false,
    val category: String = "",
    val key: String = "",
    val value: String = "",
    val confidence: Float = 0.8f,
    val scope: String = "user",
    val tags: String? = null,
    val metadata: Map<String, Any>? = null,
    val correctionText: String = "",
    val sourceMessageId: String? = null
)

/**
 * Состояние экрана деталей чата.
 */
data class ChatDetailState(
    val chat: Chat? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val isRolesLoading: Boolean = false,
    val errorMessage: String? = null,
    val inputText: String = "",
    val availableRoles: List<Role> = emptyList(),
    val selectedRole: Role? = null,
    val attachments: List<com.pai.android.data.model.Attachment> = emptyList(),
    val messageAttachments: Map<String, List<com.pai.android.data.model.Attachment>> = emptyMap(),
    val factConfirmation: FactConfirmationState = FactConfirmationState(),
    val contextUsagePercent: Int? = null, // % заполнения контекстного окна (null = неизвестно)
    val contextLabel: String = "", // Краткая подпись, напр. "45K/1000K"
    val workStatus: String = "", // Текущее действие агента (для UI)
    val activeModelName: String = "", // Название модели, обрабатывающей запрос
    val smartRouterEnabled: Boolean = false, // Включён ли Smart Router
    val isProcessingVoice: Boolean = false // Обработка голосового запроса (для кнопки стоп)
)

/**
 * ViewModel для экрана деталей чата.
 */
@HiltViewModel
class ChatDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val aiRepository: AiRepository,
    private val roleRepository: RoleRepository,
    private val attachmentRepository: AttachmentRepository,
    private val memoryRepository: MemoryRepository,
    private val summaryRepository: SummaryRepository,
    private val messageProcessor: MessageProcessor,
    private val decisionEngine: DecisionEngine
) : ViewModel() {

    private val _state = MutableStateFlow(ChatDetailState())
    val state: StateFlow<ChatDetailState> = _state.asStateFlow()

    private val chatId: String = savedStateHandle.get<String>("chatId") ?: ""
    
    private var notificationObserverJob: Job? = null
    private var sendMessageJob: Job? = null
    private var sendJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    init {
        if (chatId.isNotEmpty()) {
            com.pai.android.agent.DecisionEngine.lastChatId = chatId
            loadChat()
            // Восстанавливаем статус обработки (если вернулись в чат во время работы)
            val savedStatus = com.pai.android.agent.DecisionEngine.processingWorkStatus
            if (savedStatus != null) {
                _state.update { it.copy(isSending = true, workStatus = savedStatus) }
            }
            // Проверяем отложенные уведомления от планировщика (фоновые задачи)
            deliverPendingNotification()
            // Наблюдаем за новыми отложенными уведомлениями в реальном времени
            observePendingNotifications()
            // Следим за голосовой обработкой (для кнопки стоп)
            observeVoiceProcessing()
        }
    }

    /**
     * Наблюдает StateFlow отложенных уведомлений в реальном времени.
     * Срабатывает сразу при установке значения из любого источника.
     */
    private var voiceProcessingObserverJob: Job? = null

    private fun observeVoiceProcessing() {
        voiceProcessingObserverJob?.cancel()
        voiceProcessingObserverJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val isVoice = com.pai.android.agent.DecisionEngine.isProcessingVoice
                _state.update { it.copy(isProcessingVoice = isVoice) }
                // Автоочистка статуса обработки — если processQuery завершился,
                // processingWorkStatus станет null, кнопка стоп пропадёт
                val status = com.pai.android.agent.DecisionEngine.processingWorkStatus
                _state.update { it.copy(
                    isSending = status != null,
                    workStatus = status ?: ""
                ) }
            }
        }
    }

    private fun observePendingNotifications() {
        notificationObserverJob?.cancel()
        notificationObserverJob = viewModelScope.launch {
            com.pai.android.agent.DecisionEngine.pendingNotificationFlow.collect { notification ->
                if (notification != null) {
                    // Сбрасываем, чтобы одинаковые уведомления могли прийти повторно
                    com.pai.android.agent.DecisionEngine.Companion.pendingNotificationResult = null
                    sendNotificationToChat(notification)
                }
            }
        }
    }

    private fun deliverPendingNotification() {
        // Only deliver in existing chats (skip new chats)
        val existingCount = try { kotlinx.coroutines.runBlocking { chatRepository.getMessages(chatId).size } } catch (e: Exception) { 0 }
        if (existingCount < 2) {
            com.pai.android.agent.DecisionEngine.pendingNotificationResult = null
            return
        }
        // Check companion variable first (set by processQuery)
        val pendingBg = com.pai.android.agent.DecisionEngine.pendingNotificationResult
        if (pendingBg != null) {
            com.pai.android.agent.DecisionEngine.pendingNotificationResult = null
            sendNotificationToChat(pendingBg)
            return
        }
        // Direct PC read via memoryRepository (for when chat opens without prior processQuery)
        viewModelScope.launch {
            try {
                val fact = memoryRepository.getFactByCategoryAndKey("agent_state", "agent_state") ?: return@launch
                val parts = fact.value.split("|", limit = 12).toMutableList()
                if (parts.size >= 9 && parts[8].isNotBlank()) {
                    val notif = parts[8]
                    parts[8] = ""
                    memoryRepository.savePermanentFactFull(
                        category = "agent_state", key = "agent_state",
                        value = parts.joinToString("|"),
                        confidence = 0.5f, scope = "user", tags = null
                    )
                    sendNotificationToChat(notif)
                }
            } catch (e: Exception) {
                println("❌ PC read error: ${e.message}")
            }
        }
    }

    private fun sendNotificationToChat(text: String) {
        viewModelScope.launch {
            try {
                val msg = Message.createAssistantMessage(chatId = chatId, content = text)
                chatRepository.addMessage(msg)
                println("📨 Отложенное уведомление scheduler добавлено в чат $chatId")
            } catch (e: Exception) {
                println("❌ Ошибка доставки отложенного уведомления: ${e.message}")
            }
        }
    }

    /**
     * Загружает чат и сообщения.
     */
    private fun loadChat() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // Комбинируем Flow чата и сообщений для наблюдения в реальном времени
                chatRepository.observeChatWithMessages(chatId).collect { (chat, messages) ->
                    // Считаем заполнение контекста
                    val settings = aiRepository.getCurrentProviderSettings()
                    val contextInfo = calculateContextUsage(messages, settings)
                    println("📊 Контекст: messages=${messages.size}, chars=${messages.sumOf { it.content.length }}, percent=${contextInfo.first}, label='${contextInfo.second}'")
                    
                    // Load attachments for all visible messages
                    val msgAttachments = mutableMapOf<String, List<com.pai.android.data.model.Attachment>>()
                    for (msg in messages) {
                        try {
                            val atts = attachmentRepository.getByMessageId(msg.id)
                            if (atts.isNotEmpty()) msgAttachments[msg.id] = atts
                        } catch (_: Exception) {}
                    }
                    
                    _state.update { it.copy(
                        chat = chat,
                        messages = messages,
                        messageAttachments = msgAttachments,
                        isLoading = false,
                        contextUsagePercent = contextInfo.first,
                        contextLabel = contextInfo.second
                    ) }
                    // Загружаем роли после загрузки чата
                    loadRoles(chat)
                }
            } catch (e: Exception) {
                _state.update { it.copy(
                    isLoading = false,
                    errorMessage = "Не удалось загрузить чат: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Загружает роли и устанавливает выбранную роль на основе чата.
     */
    private suspend fun loadRoles(chat: Chat?) {
        _state.update { it.copy(isRolesLoading = true) }
        try {
            val roles = roleRepository.observeAllRoles().first()
            val selectedRole = chat?.roleId?.let { roleId ->
                roles.find { it.id == roleId }
            } ?: roles.find { it.isDefault }
            
            _state.update { it.copy(
                availableRoles = roles,
                selectedRole = selectedRole,
                isRolesLoading = false
            ) }
        } catch (e: Exception) {
            _state.update { it.copy(
                isRolesLoading = false,
                errorMessage = "Не удалось загрузить роли: ${e.message}"
            ) }
        }
    }
    
    /**
     * Выбирает роль для текущего чата.
     */
    fun selectRole(role: Role?) {
        val chat = _state.value.chat ?: return
        viewModelScope.launch {
            try {
                val updatedChat = chat.updateRoleId(role?.id)
                chatRepository.updateChat(updatedChat)
                _state.update { it.copy(selectedRole = role) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось изменить роль: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Обновляет текст ввода.
     */
    fun updateInputText(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /**
     * Отправляет сообщение от пользователя.
     * @param text Текст сообщения (если null, берётся из поля ввода)
     * @param attachments Вложения (если null, берутся из состояния)
     */
    /**
     * Отменяет отправку и генерацию ответа.
     */
    fun cancelSending() {
        sendMessageJob?.cancel()
        sendMessageJob = null
        sendJobScope.cancel()
        sendJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Сбрасываем флаг голосовой обработки сразу
        com.pai.android.agent.DecisionEngine.isProcessingVoice = false
        com.pai.android.agent.DecisionEngine.processingWorkStatus = null
        val cancelIntent = android.content.Intent(context, com.pai.android.service.WakeWordService::class.java).apply {
            action = com.pai.android.service.WakeWordService.ACTION_CANCEL
        }
        // Для интента отмены используем startService — не требует startForeground()
        try { context.startService(cancelIntent) } catch (_: Exception) {}
        _state.update { it.copy(isSending = false, workStatus = "") }
    }

    fun sendMessage(text: String? = null, attachments: List<Attachment>? = null) {
        if (chatId.isEmpty()) return
        val messageText = text?.trim() ?: _state.value.inputText.trim()
        val messageAttachments = attachments ?: _state.value.attachments
        
        if (messageText.isEmpty() && messageAttachments.isEmpty()) return

        sendMessageJob?.cancel()
        sendMessageJob = sendJobScope.launch {
            _state.update { it.copy(isSending = true, workStatus = "🤔 Анализирую запрос...") }
            try {
                // Сохраняем chatId для отправки уведомлений scheduler
                com.pai.android.agent.DecisionEngine.lastChatId = chatId
                // Создаём пользовательское сообщение
                val userMessage = Message.createUserMessage(chatId, messageText)
                val messageId = chatRepository.addMessage(userMessage)
                
                // Сохраняем вложения для этого сообщения (если есть)
                if (messageAttachments.isNotEmpty()) {
                    attachmentRepository.saveAttachmentsForMessage(userMessage.id, messageAttachments)
                }
                
                // Автоматическая обработка сообщения для памяти
                messageProcessor.processUserMessage(chatId, userMessage)
                
                // Если чат имеет заголовок по умолчанию, генерируем новый на основе сообщения
                val currentChat = _state.value.chat
                if (currentChat != null && currentChat.isDefaultTitle() && messageText.isNotBlank()) {
                    val newTitle = Chat.generateTitleFromMessage(messageText)
                    if (newTitle.isNotBlank() && newTitle != "Новый чат") {
                        val updatedChat = currentChat.updateTitle(newTitle)
                        chatRepository.updateChat(updatedChat)
                    }
                }
                
                // Очищаем поле ввода (но НЕ isSending — он сбросится после ответа AI)
                if (text == null) {
                    _state.update { it.copy(inputText = "") }
                }
                if (attachments == null) {
                    clearAttachments()
                }
                
                // ВСЕ запросы отправляем в DecisionEngine (без NonCancellable — чтобы кнопка стоп работала)
                processWithAgent(userMessage, messageText, messageAttachments)
                
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Отмена пользователем — не показываем как ошибку
                _state.update { it.copy(isSending = false, workStatus = "") }
            } catch (e: Exception) {
                _state.update { it.copy(
                    isSending = false,
                    errorMessage = "Не удалось отправить сообщение: ${e.message}"
                ) }
            } finally {
                com.pai.android.agent.DecisionEngine.processingWorkStatus = null
            }
        }
    }

    /**
     * Запрашивает ответ от AI.
     */
    private suspend fun fetchAiResponse(previousMessages: List<Message>) {
        fetchAiResponseWithAttachments(previousMessages.lastOrNull(), emptyList())
    }
    
    /**
     * Запрашивает ответ от AI с учётом вложений.
     */
    private suspend fun fetchAiResponseWithAttachments(
        userMessage: Message?,
        attachments: List<Attachment>
    ) {
        try {
            // Получаем все сообщения чата (включая предыдущие)
            val allMessages = chatRepository.getMessages(chatId)
            
            // Получаем системный промпт из выбранной роли или чата
            val systemPrompt = _state.value.selectedRole?.systemPrompt 
                ?: _state.value.chat?.systemPrompt
            
            // Получаем расширенный контекст (факты + суммаризации)
            val query = userMessage?.content ?: allMessages.lastOrNull { it.isFromUser() }?.content ?: ""
            updateWorkStatus("🔍 Ищу информацию...")
            val enhancedContext = buildEnhancedContext(chatId, query)
            
            // Отправляем запрос к AI с системным промптом, расширенным контекстом и вложениями
            updateWorkStatus("🧠 Думаю...")
            val result = aiRepository.sendMessageWithAttachments(
                messages = allMessages,
                attachments = attachments,
                systemPrompt = systemPrompt,
                memoryContext = if (enhancedContext.isNotBlank()) enhancedContext else null
            )
            
            if (result.isSuccess) {
                val aiResponse = result.getOrThrow()
                // Создаём сообщение от ассистента
                val assistantMessage = Message.createAssistantMessage(
                    chatId = chatId,
                    content = aiResponse.text,
                    providerModel = aiResponse.modelUsed
                )
                chatRepository.addMessage(assistantMessage)
                clearWorkStatus()
                
                // Асинхронно извлекаем факты из диалога
                viewModelScope.launch {
                    extractFactsFromDialogue(userMessage, assistantMessage)
                }
            } else {
                throw result.exceptionOrNull() ?: Exception("Неизвестная ошибка AI")
            }
        } catch (e: Exception) {
            _state.update { it.copy(
                errorMessage = "Ошибка AI: ${e.message}"
            ) }
        }
    }

    /**
     * Удаляет сообщение.
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                chatRepository.deleteMessage(messageId)
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось удалить сообщение: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Очищает историю чата.
     */
    fun clearChatHistory() {
        if (chatId.isEmpty()) return
        viewModelScope.launch {
            try {
                chatRepository.clearChatMessages(chatId)
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось очистить историю: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Обновляет системный промпт чата.
     */
    fun updateSystemPrompt(prompt: String) {
        val chat = _state.value.chat ?: return
        viewModelScope.launch {
            try {
                chatRepository.updateChat(chat.updateSystemPrompt(prompt))
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось обновить системный промпт: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Рассчитывает процент использования контекстного окна и подпись.
     */

    fun updateWorkStatus(status: String) {
        com.pai.android.agent.DecisionEngine.processingWorkStatus = status
        _state.update {
            it.copy(
                workStatus = status,
                activeModelName = com.pai.android.agent.DecisionEngine.processingModelName ?: "",
                smartRouterEnabled = com.pai.android.agent.DecisionEngine.processingSmartRouterEnabled
            )
        }
    }

    fun clearWorkStatus() {
        com.pai.android.agent.DecisionEngine.processingWorkStatus = null
        _state.update {
            it.copy(
                workStatus = "",
                activeModelName = com.pai.android.agent.DecisionEngine.processingModelName ?: "",
                smartRouterEnabled = com.pai.android.agent.DecisionEngine.processingSmartRouterEnabled
            )
        }
    }

    private suspend fun <T> withWorkStatus(status: String, block: suspend () -> T): T {
        updateWorkStatus(status)
        try {
            return block()
        } finally {
            clearWorkStatus()
        }
    }
    private fun calculateContextUsage(
        messages: List<Message>,
        settings: ProviderSettings?
    ): Pair<Int?, String> {
        val maxContextValue = settings?.modelMaxContext ?: return Pair(null, "")
        val maxContext = maxContextValue.toInt()
        if (maxContext <= 0) return Pair(null, "")
        
        val totalChars = messages.sumOf { it.content.length }
        val estimatedTokens = totalChars / 4
        val contextFloat = maxContext.toFloat()
        val percent = ((estimatedTokens.toFloat() / contextFloat) * 100f).toInt().coerceIn(0, 100)
        
        val label = when {
            maxContext >= 1_000_000 && estimatedTokens >= 1000 -> "${estimatedTokens / 1000}K / ${maxContext / 1000}K"
            maxContext >= 1000 && estimatedTokens >= 1000 -> "${estimatedTokens / 1000}K / ${maxContext / 1000}K"
            else -> "$estimatedTokens / ${if (maxContext >= 1000) "${maxContext / 1000}K" else "$maxContext"}"
        }
        
        return Pair(percent, label)
    }

    /**
     * Очищает ошибку.
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Добавляет вложение к текущему сообщению.
     */
    fun addAttachment(attachment: Attachment) {
        _state.update { state ->
            state.copy(attachments = state.attachments + attachment)
        }
    }

    /**
     * Удаляет вложение по ID.
     */
    fun removeAttachment(attachmentId: String) {
        _state.update { state ->
            state.copy(attachments = state.attachments.filter { it.id != attachmentId })
        }
    }

    /**
     * Очищает все временные вложения.
     */
    fun clearAttachments() {
        _state.update { state ->
            state.copy(attachments = emptyList())
        }
    }

    /**
     * Повторно отправляет сообщение пользователя.
     */
    fun resendMessage(message: Message) {
        if (chatId.isEmpty()) return
        
        viewModelScope.launch {
            _state.update { it.copy(isSending = true) }
            try {
                // Получаем вложения для этого сообщения (если есть)
                val attachments = attachmentRepository.getByMessageId(message.id)
                
                // Отправляем сообщение с текстом и вложениями
                sendMessage(text = message.content, attachments = attachments)
                
            } catch (e: Exception) {
                _state.update { it.copy(
                    isSending = false,
                    errorMessage = "Не удалось повторно отправить сообщение: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Регенерирует ответ AI на указанное сообщение пользователя.
     */
    fun regenerateResponse(message: Message) {
        if (chatId.isEmpty()) return
        
        viewModelScope.launch {
            _state.update { it.copy(isSending = true) }
            try {
                // Получаем все сообщения чата
                val allMessages = chatRepository.getMessages(chatId)
                val messageIndex = allMessages.indexOfFirst { it.id == message.id }
                if (messageIndex == -1) {
                    throw Exception("Сообщение не найдено")
                }
                
                // Определяем, какое сообщение нужно удалить
                val messageToDeleteId = if (message.role == MessageRole.ASSISTANT) {
                    // Если нажали на ответ ассистента - удаляем его
                    message.id
                } else {
                    // Если нажали на сообщение пользователя - ищем следующий ответ ассистента
                    val assistantMessageIndex = allMessages.subList(messageIndex + 1, allMessages.size)
                        .indexOfFirst { it.role == MessageRole.ASSISTANT }
                    
                    if (assistantMessageIndex != -1) {
                        allMessages[messageIndex + 1 + assistantMessageIndex].id
                    } else {
                        null
                    }
                }
                
                // Удаляем сообщение, если нашли
                if (messageToDeleteId != null) {
                    chatRepository.deleteMessage(messageToDeleteId)
                    // Обновляем состояние после удаления, чтобы UI увидел изменение
                    _state.update { it.copy(messages = chatRepository.getMessages(chatId)) }
                    // Задержка, чтобы пользователь увидел удаление перед генерацией нового ответа
                    delay(200)
                }
                
                // Определяем, на какое сообщение регенерировать ответ
                val (messageToRegenerate, attachmentsToUse) = if (message.role == MessageRole.ASSISTANT) {
                    // Если нажали на ответ ассистента - ищем предыдущее пользовательское сообщение
                    val userMessageIndex = allMessages.subList(0, messageIndex + 1)
                        .indexOfLast { it.role == MessageRole.USER }
                    
                    if (userMessageIndex != -1) {
                        val userMessage = allMessages[userMessageIndex]
                        val userAttachments = attachmentRepository.getByMessageIdWithoutContent(userMessage.id)
                    println("📎 Found ${userAttachments.size} attachments for user message ${userMessage.id}")
                    val enrichedAtts = userAttachments.map { att ->
                        if (att.isImage && att.contentBase64 == null && att.localPath == null) {
                            val content = try { attachmentRepository.getContentById(att.id) } catch (e: Exception) { null }
                            if (content != null) att.copy(contentBase64 = content) else att
                        } else att
                    }

                        userMessage to enrichedAtts
                    } else {
                        // Если пользовательское сообщение не найдено, используем исходное сообщение
                        // (хотя это не идеально, но лучше, чем ошибка)
                        message to attachmentRepository.getByMessageIdWithoutContent(message.id)
                    }
                } else {
                    // Если нажали на пользовательское сообщение - используем его
                    message to attachmentRepository.getByMessageIdWithoutContent(message.id)
                }
                
                // Отправляем запрос на регенерацию ответа
                fetchAiResponseWithAttachments(messageToRegenerate, attachmentsToUse)
                
                _state.update { it.copy(isSending = false) }
                
            } catch (e: Exception) {
                _state.update { it.copy(
                    isSending = false,
                    errorMessage = "Не удалось регенерировать ответ: ${e.message}"
                ) }
            }
        }
    }
    
    /**
     * Сохраняет факт из сообщения в постоянную память.
     * @param message Сообщение, из которого извлекается факт
     * @param category Категория факта (user_info, preferences, skills и т.д.)
     * @param key Ключ факта (name, birth_date, profession и т.д.)
     * @param value Значение факта
     * @param confidence Уверенность в факте (0.0-1.0, по умолчанию 0.8)
     */
    private suspend fun saveFactToMemory(
        message: Message,
        category: String,
        key: String,
        value: String,
        confidence: Float = 0.8f,
        scope: String? = null,
        tags: String? = null,
        metadata: Map<String, Any>? = null
    ) {
        try {
            // Определяем scope: если указан в JSON, используем его, иначе на основе категории
            val resolvedScope = scope ?: when (category.lowercase()) {
                "ai_info" -> "ai"
                "global" -> "global"
                else -> "user"  // personal_info, contacts, preferences и другие
            }
            
            // Нормализуем категорию (приводим к стандартным значениям)
            val normalizedCategory = when (category.lowercase()) {
                "user_info" -> "personal_info"  // Совместимость со старым промптом
                else -> category.lowercase()
            }
            
            // Дедупликация: если факт с таким category+key уже существует — не сохраняем дубликат
            val existingFact = memoryRepository.getFactByCategoryAndKey(normalizedCategory, key)
            if (existingFact != null) {
                if (existingFact.value == value && existingFact.scope == resolvedScope) {
                    println("  ⏭ Дубликат: $normalizedCategory/$key = $value (уже есть)")
                    return
                }
                println("  ⏭ Пропущен: $normalizedCategory/$key (другое значение: \"${existingFact.value}\" вместо \"$value\")")
                return
            }
            
            memoryRepository.savePermanentFactFull(
                category = normalizedCategory,
                key = key,
                value = value,
                confidence = confidence,
                scope = resolvedScope,
                tags = tags,
                metadata = metadata,
                sourceChatId = chatId,
                sourceMessageId = message.id
            )
            
            println("💾 Сохранён факт: $category/$key = $value (confidence=$confidence, scope=$resolvedScope, tags=$tags)")
        } catch (e: Exception) {
            _state.update { it.copy(
                errorMessage = "Не удалось сохранить факт в память: ${e.message}"
            ) }
        }
    }
    
    /**
     * Сохраняет подтверждённый пользователем факт (confidence = 1.0).
     */
    suspend fun saveConfirmedFactToMemory(
        message: Message,
        category: String,
        key: String,
        value: String
    ) {
        saveFactToMemory(message, category, key, value, 1.0f)
    }
    
    /**
     * Сохраняет сырую запись в дневную память.
     */
    fun saveToDailyMemory(content: String, tags: List<String> = emptyList()) {
        viewModelScope.launch {
            try {
                memoryRepository.saveDailyEntry(content, tags)
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось сохранить в дневную память: ${e.message}"
                ) }
            }
        }
    }
    
    /**
     * Суммаризирует текущий разговор и сохраняет в дневную память.
     */
    fun summarizeConversation() {
        viewModelScope.launch {
            try {
                val messages = state.value.messages
                if (messages.isEmpty()) {
                    _state.update { it.copy(
                        errorMessage = "Нет сообщений для суммаризации"
                    ) }
                    return@launch
                }
                
                memoryRepository.summarizeConversation(messages, listOf("chat_summary"))
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось суммаризировать разговор: ${e.message}"
                ) }
            }
        }
    }
    
    /**
     * Получает факты из памяти, релевантные текущему контексту.
     * Использует семантический поиск для лучшей релевантности.
     */
    suspend fun getRelevantFacts(context: String = ""): List<PermanentMemory> {
        return try {
            memoryRepository.semanticSearchAI(context, limit = 10)
        } catch (e: Exception) {
            println("⚠️ Ошибка семантического поиска: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Форматирует релевантные факты для вставки в промпт AI.
     * Использует семантический поиск для лучшей релевантности.
     */
    suspend fun formatRelevantFactsForPrompt(context: String = ""): String {
        return memoryRepository.formatSemanticFactsForPrompt(context)
    }
    
    /**
     * Константа для автоматического извлечения фактов.
     */
    private val AUTO_EXTRACT_FACTS = true
    
    /**
     * Prompt for extracting facts from dialogue.
     */
    private val FACT_EXTRACTION_PROMPT = """
        Extract structured facts from the dialogue between user and AI assistant.
        
        CATEGORIES AND KEYS:
        1. personal_info (scope="user"): name, birth_date (DD.MM.YYYY), birth_place, location, profession, marital_status
        2. preferences (scope="user"): favorite_food, favorite_movie, favorite_music, hobby, favorite_color
        3. contacts (scope="user"): email, phone
        4. ai_info (scope="ai"): name, role, instructions, personality
        5. global (scope="global"): projects, skills, context
        
        RULES:
        - Keys in ENGLISH snake_case only
        - Values in original user language
        - confidence: 1.0=explicit, 0.9=clear context, 0.8=assumed, 0.7=weak
        - Differentiate birth_place vs location (current residence)
        - Extract ALL facts from the message, don't skip any
        - CRITICAL: if user says "born in X, now living in Y" → BOTH birth_place AND location
        
        RESPOND WITH EXACT JSON (no extra text):
        {"facts":[{"category":"...","key":"...","value":"...","confidence":0.9,"tags":"..."}]}
        
        EXAMPLES:
        Input: "My name is John Smith, born January 15 1985 in Chicago, now living in New York, I love sushi"
        Output: {"facts":[
          {"category":"personal_info","key":"name","value":"John Smith","confidence":1.0,"tags":"personal,name"},
          {"category":"personal_info","key":"birth_date","value":"1985-01-15","confidence":0.9,"tags":"personal,birth_date"},
          {"category":"personal_info","key":"birth_place","value":"Chicago","confidence":0.9,"tags":"personal,birth_place"},
          {"category":"personal_info","key":"location","value":"New York","confidence":0.9,"tags":"personal,location"},
          {"category":"preferences","key":"favorite_food","value":"sushi","confidence":0.9,"tags":"preferences,food"}
        ]}
        
        Input: "your name is Nova, you are my assistant"
        Output: {"facts":[
          {"category":"ai_info","key":"name","value":"Nova","confidence":1.0,"tags":"ai,name"},
          {"category":"ai_info","key":"role","value":"assistant","confidence":0.9,"tags":"ai,role"}
        ]}
        
        DIALOGUE:
        User: %USER_MESSAGE%
        AI: %ASSISTANT_MESSAGE%
    """.trimIndent()
    
    /**
     * Extracts facts from user and assistant dialogue.
     */
    private suspend fun extractFactsFromDialogue(userMessage: Message?, assistantMessage: Message) {
        if (!AUTO_EXTRACT_FACTS) return
        if (userMessage == null) return
        
        try {
            // Build prompt for fact extraction
            val prompt = FACT_EXTRACTION_PROMPT
                .replace("%USER_MESSAGE%", userMessage.content)
                .replace("%ASSISTANT_MESSAGE%", assistantMessage.content)
            
            // Create message for AI
            val extractionMessage = Message.createUserMessage(
                chatId = "fact_extraction",
                content = prompt
            )
            
            // Send request to AI for fact extraction
            val result = aiRepository.sendMessage(
                messages = listOf(extractionMessage),
                systemPrompt = "You are a fact extraction system. Return only JSON."
            )
            
            if (result.isSuccess) {
                val aiResponse = result.getOrThrow()
                val jsonText = aiResponse.text
                
                // Parse JSON and save facts
                parseAndSaveFacts(jsonText, userMessage)
            }
        } catch (e: Exception) {
            // Ignore fact extraction errors, do not interrupt main dialogue
            println("⚠️ Fact extraction error: ${e.message}")
        }
    }
    
    /**
     * Parses JSON facts and saves them to memory.
     */
    private suspend fun parseAndSaveFacts(jsonText: String, sourceMessage: Message) {
        try {
            // Log raw AI response for debugging
            println("🔍 Raw AI response for fact extraction:")
            println(jsonText.take(500))
            println("---")
            
            // Вспомогательная функция для конвертации JSONObject в Map<String, Any>
            fun jsonObjectToMap(jsonObj: JSONObject?): Map<String, Any>? {
                if (jsonObj == null) return null
                try {
                    val jsonString = jsonObj.toString()
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    return Gson().fromJson(jsonString, type)
                } catch (e: Exception) {
                    println("⚠️ Failed to convert JSONObject to Map: ${e.message}")
                    return null
                }
            }
            
            // First try to parse JSON
            val facts = mutableListOf<Fact>()
            
            fun parseFactObject(factObj: JSONObject): Fact? {
                val category = factObj.optString("category", "")
                val key = factObj.optString("key", "")
                val value = factObj.optString("value", "")
                val confidence = factObj.optDouble("confidence", 0.8).toFloat()
                val scope = factObj.optString("scope", "")
                val tags = factObj.optString("tags", "")
                val metadataObj = factObj.optJSONObject("metadata")
                val metadata = jsonObjectToMap(metadataObj)
                
                if (category.isNotEmpty() && key.isNotEmpty() && value.isNotEmpty()) {
                    return Fact(
                        category = category,
                        key = key,
                        value = value,
                        confidence = confidence,
                        scope = if (scope.isNotEmpty()) scope else null,
                        tags = if (tags.isNotEmpty()) tags else null,
                        metadata = metadata
                    )
                }
                return null
            }
            
            try {
                val json = JSONObject(jsonText)
                val factsArray = json.optJSONArray("facts")
                if (factsArray != null) {
                    for (i in 0 until factsArray.length()) {
                        val factObj = factsArray.getJSONObject(i)
                        parseFactObject(factObj)?.let { facts.add(it) }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ Не удалось распарсить JSON, пробуем найти JSON в тексте")
                // Попробуем найти JSON в тексте (модель могла добавить пояснения)
                val jsonMatch = Regex("\\{.*\"facts\".*\\}", RegexOption.DOT_MATCHES_ALL).find(jsonText)
                if (jsonMatch != null) {
                    try {
                        val json = JSONObject(jsonMatch.value)
                        val factsArray = json.optJSONArray("facts")
                        if (factsArray != null) {
                            for (i in 0 until factsArray.length()) {
                                val factObj = factsArray.getJSONObject(i)
                                parseFactObject(factObj)?.let { facts.add(it) }
                            }
                        }
                    } catch (e2: Exception) {
                        println("⚠️ Не удалось извлечь JSON из текста: ${e2.message}")
                    }
                }
            }
            
            // Фильтрация некорректных фактов (особенно имён)
            val filteredFacts = facts.filterNot { fact ->
                // Фильтр для некорректных имён
                if (fact.key == "name") {
                    val lowerValue = fact.value.lowercase()
                    val incorrectValues = listOf("зовут", "родился", "родилась", "я", "живу", "работаю", "запомни", "меня", "мое", "моё")
                    
                    // Проверяем на запрещённые значения и слишком короткие имена
                    if (lowerValue in incorrectValues || fact.value.length < 2) {
                        println("❌ Отфильтрован некорректный факт имени: ${fact.value}")
                        return@filterNot true
                    }
                    
                    // Проверяем, что значение не является глаголом (оканчивается на "лся", "лась")
                    if (lowerValue.endsWith("лся") || lowerValue.endsWith("лась")) {
                        println("❌ Отфильтрован факт имени-глагола: ${fact.value}")
                        return@filterNot true
                    }
                }
                false
            }
            
            // Обрабатываем извлечённые факты
            if (filteredFacts.isNotEmpty()) {
                println("✅ Найдено ${filteredFacts.size} фактов в JSON (после фильтрации из ${facts.size})")
                
                val pendingFacts = mutableListOf<Fact>()
                
                for (fact in filteredFacts) {
                    if (fact.confidence >= 0.9f) {
                        // Высокая уверенность - сохраняем сразу
                        saveFactToMemory(
                            message = sourceMessage,
                            category = fact.category,
                            key = fact.key,
                            value = fact.value,
                            confidence = fact.confidence,
                            scope = fact.scope,
                            tags = fact.tags,
                            metadata = fact.metadata
                        )
                        println("  💾 Сохранён (высокая уверенность): ${fact.category}/${fact.key} = ${fact.value} (${fact.confidence})")
                    } else {
                        // Низкая уверенность - добавляем в очередь на подтверждение
                        pendingFacts.add(fact)
                        println("  ⏳ Требует подтверждения: ${fact.category}/${fact.key} = ${fact.value} (${fact.confidence})")
                    }
                }
                
                // Показываем диалог подтверждения для первого факта с низкой уверенностью
                if (pendingFacts.isNotEmpty()) {
                    val firstFact = pendingFacts.first()
                    showFactConfirmation(
                        category = firstFact.category,
                        key = firstFact.key,
                        value = firstFact.value,
                        confidence = firstFact.confidence,
                        scope = firstFact.scope,
                        tags = firstFact.tags,
                        metadata = firstFact.metadata,
                        sourceMessageId = sourceMessage.id
                    )
                }
                
                return
            }
            
            // Fallback: простая эвристика для имени
            val userContent = sourceMessage.content
            val namePatterns = listOf(
                "меня зовут\\s+([А-Яа-яA-Za-z]+)",
                "я\\s+-\\s+([А-Яа-яA-Za-z]+)",
                "мое имя\\s+([А-Яа-яA-Za-z]+)",
                "меня\\s+зовут\\s+([А-Яа-яA-Za-z]+)",
                "я\\s+([А-Яа-яA-Za-z]+)\\s+по имени"
            )
            
            for (pattern in namePatterns) {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                val match = regex.find(userContent)
                if (match != null) {
                    val name = match.groupValues[1]
                    if (name.length >= 2) {
                        saveFactToMemory(
                            message = sourceMessage,
                            category = "personal_info",
                            key = "name",
                            value = name,
                            confidence = 0.9f,
                            tags = "личное,имя,эвристика"
                        )
                        println("✅ Сохранён факт (эвристика): имя = $name")
                        break
                    }
                }
            }
            
        } catch (e: Exception) {
            println("⚠️ Ошибка парсинга фактов: ${e.message}")
        }
    }
    
    /**
     * Вспомогательный класс для хранения фактов.
     */
    private data class Fact(
        val category: String,
        val key: String,
        val value: String,
        val confidence: Float,
        val scope: String? = null,
        val tags: String? = null,
        val metadata: Map<String, Any>? = null
    )
    
    /**
     * Показывает диалог подтверждения для извлечённого факта.
     */
    fun showFactConfirmation(
        category: String,
        key: String,
        value: String,
        confidence: Float,
        scope: String? = null,
        tags: String? = null,
        metadata: Map<String, Any>? = null,
        sourceMessageId: String? = null
    ) {
        _state.update { currentState ->
            currentState.copy(
                factConfirmation = FactConfirmationState(
                    showDialog = true,
                    category = category,
                    key = key,
                    value = value,
                    confidence = confidence,
                    scope = scope ?: when (category.lowercase()) {
                        "ai_info" -> "ai"
                        "global" -> "global"
                        else -> "user"
                    },
                    tags = tags,
                    metadata = metadata,
                    correctionText = value,  // Начальное значение для редактирования
                    sourceMessageId = sourceMessageId
                )
            )
        }
        println("📋 Показан диалог подтверждения факта: $category/$key = $value")
    }
    
    /**
     * Обновляет текст исправления факта.
     */
    fun updateFactCorrectionText(text: String) {
        _state.update { currentState ->
            currentState.copy(
                factConfirmation = currentState.factConfirmation.copy(
                    correctionText = text
                )
            )
        }
    }
    
    /**
     * Подтверждает факт (сохраняет с confidence=1.0).
     */
    fun confirmFact() {
        val confirmation = _state.value.factConfirmation
        if (!confirmation.showDialog) return
        
        viewModelScope.launch {
            try {
                memoryRepository.savePermanentFactFull(
                    category = confirmation.category,
                    key = confirmation.key,
                    value = confirmation.value,  // Используем оригинальное значение
                    confidence = 1.0f,  // Подтверждённый факт
                    scope = confirmation.scope,
                    tags = confirmation.tags,
                    metadata = confirmation.metadata,
                    sourceChatId = chatId,
                    sourceMessageId = confirmation.sourceMessageId
                )
                println("✅ Факт подтверждён: ${confirmation.category}/${confirmation.key} = ${confirmation.value}")
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось подтвердить факт: ${e.message}"
                ) }
            } finally {
                // Закрываем диалог
                closeFactConfirmationDialog()
            }
        }
    }
    
    /**
     * Исправляет факт (сохраняет с исправленным значением и confidence=1.0).
     */
    fun correctFact() {
        val confirmation = _state.value.factConfirmation
        if (!confirmation.showDialog) return
        
        val correctedValue = confirmation.correctionText.trim()
        if (correctedValue.isEmpty()) {
            _state.update { it.copy(
                errorMessage = "Исправленное значение не может быть пустым"
            ) }
            return
        }
        
        viewModelScope.launch {
            try {
                memoryRepository.savePermanentFactFull(
                    category = confirmation.category,
                    key = confirmation.key,
                    value = correctedValue,  // Используем исправленное значение
                    confidence = 1.0f,  // Подтверждённый факт
                    scope = confirmation.scope,
                    tags = confirmation.tags,
                    metadata = confirmation.metadata,
                    sourceChatId = chatId,
                    sourceMessageId = confirmation.sourceMessageId
                )
                println("✅ Факт исправлен: ${confirmation.category}/${confirmation.key} = $correctedValue (было: ${confirmation.value})")
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось исправить факт: ${e.message}"
                ) }
            } finally {
                // Закрываем диалог
                closeFactConfirmationDialog()
            }
        }
    }
    
    /**
     * Отклоняет факт (не сохраняет).
     */
    fun rejectFact() {
        val confirmation = _state.value.factConfirmation
        if (!confirmation.showDialog) return
        
        println("❌ Факт отклонён: ${confirmation.category}/${confirmation.key} = ${confirmation.value}")
        closeFactConfirmationDialog()
    }
    
    /**
     * Закрывает диалог подтверждения факта.
     */
    fun closeFactConfirmationDialog() {
        _state.update { currentState ->
            currentState.copy(
                factConfirmation = FactConfirmationState()  // Сброс состояния
            )
        }
    }
    
    /**
     * Получает релевантные суммаризации для промпта.
     */
    private suspend fun getRelevantSummariesForPrompt(
        chatId: String,
        query: String = "",
        limit: Int = 3
    ): String {
        if (chatId.isEmpty()) return ""
        
        try {
            val summaries = summaryRepository.getRelevantForContext(chatId, limit)
            if (summaries.isEmpty()) return ""
            
            val builder = StringBuilder()
            builder.append("\n\n## 📚 Контекст из истории (сжатые суммаризации):\n")
            
            summaries.forEachIndexed { index, summary ->
                val typeEmoji = when (summary.type) {
                    com.pai.android.data.model.SummaryType.CLUSTER -> "🧩"
                    com.pai.android.data.model.SummaryType.DAILY -> "📅"
                    com.pai.android.data.model.SummaryType.WEEKLY -> "📊"
                    com.pai.android.data.model.SummaryType.TOPIC -> "🗂️"
                }
                
                val date = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(summary.createdAt))
                
                builder.append("\n**$typeEmoji Суммаризация ($date):**\n")
                builder.append("${summary.content}\n")
                
                if (summary.compressionRatio > 1.0f) {
                    builder.append("_(сжатие ${summary.compressionRatio.toInt()}:1)_\n")
                }
            }
            
            return builder.toString()
        } catch (e: Exception) {
            println("⚠️ Ошибка получения суммаризаций: ${e.message}")
            return ""
        }
    }
    
    /**
     * Формирует расширенный контекст для AI.
     * Включает факты из памяти и суммаризации.
     */
    private suspend fun buildEnhancedContext(
        chatId: String,
        query: String = ""
    ): String {
        val contextBuilder = StringBuilder()
        
        // 1. Последние сообщения в чате (для понимания контекста диалога)
        try {
            val chatHistory = chatRepository.getMessages(chatId)
            val recentMessages = chatHistory.takeLast(5) // Последние 5 сообщений
            
            if (recentMessages.isNotEmpty()) {
                contextBuilder.append("💬 **Последние сообщения в чате:**\n")
                recentMessages.forEach { message ->
                    val role = when (message.role) {
                        com.pai.android.data.model.MessageRole.USER -> "👤 Пользователь"
                        com.pai.android.data.model.MessageRole.ASSISTANT -> "🤖 Ассистент"
                        com.pai.android.data.model.MessageRole.SYSTEM -> "⚙️ Система"
                        else -> "❓ Неизвестно"
                    }
                    contextBuilder.append("$role: ${message.content}\n")
                }
                contextBuilder.append("\n")
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка получения истории чата: ${e.message}")
        }
        
        // 2. Поиск в дневной памяти (temporal queries)
        val temporalContext = memoryRepository.searchTemporalMemory(query)
        println("📅 Результат searchTemporalMemory: '${if (temporalContext.isNotBlank()) "${temporalContext.length} символов" else "пусто"}'")
        if (temporalContext.isNotBlank()) {
            contextBuilder.append("📅 **Информация из дневной памяти:**\n")
            contextBuilder.append(temporalContext)
            contextBuilder.append("\n\n")
        }
        
        // 3. Факты из постоянной памяти
        val factsContext = formatRelevantFactsForPrompt(query)
        println("📊 Результат formatRelevantFactsForPrompt: '${if (factsContext.isNotBlank()) "${factsContext.length} символов" else "пусто"}'")
        if (factsContext.isNotBlank()) {
            contextBuilder.append(factsContext)
        }
        
        // 4. Суммаризации из истории
        val summariesContext = getRelevantSummariesForPrompt(chatId, query)
        println("📝 Результат getRelevantSummariesForPrompt: '${if (summariesContext.isNotBlank()) "${summariesContext.length} символов" else "пусто"}'")
        if (summariesContext.isNotBlank()) {
            contextBuilder.append(summariesContext)
        }
        
        val result = contextBuilder.toString()
        
        // Логируем размер контекста (примерная оценка в токенах)
        val estimatedTokens = result.length / 4  // Примерная оценка: 1 токен ~ 4 символа
        println("📊 Расширенный контекст: ${estimatedTokens} токенов, ${result.length} символов")
        println("📋 Контекст (первые 500 символов):")
        println(result.take(500))
        
        return result
    }
    

    
    /**
     * Обрабатывает запрос через ReAct агента.
     */
    private suspend fun processWithAgent(userMessage: Message, query: String, directAttachments: List<Attachment> = emptyList()) {
        try {
            // Получаем контекст чата
            val chatHistory = chatRepository.getMessages(chatId)
            val context = buildEnhancedContext(chatId, query)
            
            println("🧠 DecisionEngine: обрабатываю запрос через AI")
            
            updateWorkStatus("🤔 Анализирую запрос...")
            
            // Get user's file attachments
            val userAttachments = if (directAttachments.isNotEmpty()) {
                    println("📎 Using ${directAttachments.size} direct attachments from memory")
                    directAttachments
                } else {
                try {
                    val atts = attachmentRepository.getByMessageIdWithoutContent(userMessage.id)
                    println("📎 Found ${atts.size} attachments for user message ${userMessage.id}")
                // Copy attached files to workspace so file_system skill can find them
                atts.map { att ->
                    println("📎 Processing attachment: localPath=${att.localPath}, fileName=${att.fileName}, fileSize=${att.fileSize}")
                    // Find the actual file — try localPath first, then search workspace
                    val file = att.localPath?.let { java.io.File(it) }?.takeIf { it.isFile() }
                    if (file != null) {
                        println("📎 Found file at localPath: ${file.absolutePath}")
                    } else {
                        println("📎 localPath is null or file not found, trying workspace search for '${att.fileName}'")
                    }
                    // Determine workspace root
                    val internal = java.io.File("/data/data/com.pai.android/files/workspace")
                    val external = java.io.File("/storage/emulated/0/Android/data/com.pai.android/files/workspace")
                    val wsRoot = if (external.isDirectory()) external else internal
                    println("📎 Workspace root: ${wsRoot.absolutePath}, isDir=${wsRoot.isDirectory()}")
                    val incoming = java.io.File(wsRoot, "incoming")
                    incoming.mkdirs()
                    val dest = java.io.File(incoming, att.fileName)
                    
                    if (!dest.exists()) {
                        // Try to find and copy the file
                        val sourceFile = file ?: findFileInWorkspace(wsRoot, att.fileName)
                        if (sourceFile != null) {
                            try {
                                sourceFile.copyTo(dest, overwrite = true)
                                println("📎 Copied ${att.fileName} to ${dest.absolutePath}")
                            } catch (e: Exception) {
                                println("📎 Copy FAILED: ${e.message}")
                            }
                        } else {
                            println("📎 Cannot find source file for ${att.fileName}")
                        }
                    } else {
                        println("📎 Already exists: ${dest.absolutePath}")
                    }
                    att
                }
                            } catch (_: Exception) { emptyList() }
                }

            // Используем DecisionEngine для обработки запроса
            val response = decisionEngine.processQuery(
                query = query,
                context = context,
                fileAttachments = userAttachments
            )
            updateWorkStatus("🧠 Думаю...")
            
            when (response) {
                is com.pai.android.agent.AgentResponse.Success -> {
                    // Добавляем ответ агента как сообщение AI (с возможным уведомлением)
                    val pendingBg = com.pai.android.agent.DecisionEngine.pendingNotificationResult
                    if (pendingBg != null) {
                        com.pai.android.agent.DecisionEngine.pendingNotificationResult = null
                    }
                    val finalAnswer = if (pendingBg != null) pendingBg + "\n\n---\n\n" + response.answer else response.answer
                    val aiMessage = Message.createAssistantMessage(
                        chatId = chatId,
                        content = finalAnswer
                    )
                    chatRepository.addMessage(aiMessage)
                    // Save agent-generated file attachments
                    if (response.attachments.isNotEmpty()) {
                        attachmentRepository.saveAttachmentsForMessage(aiMessage.id, response.attachments)
                    } else {
                        // Auto-detect file references (📄 marker) in agent's answer
                        val fileRefs = extractFileReferences(finalAnswer)
                        if (fileRefs.isNotEmpty()) {
                            // Store relative file paths as attachments
                            val detected = fileRefs.mapNotNull { filePath ->
                                val names = filePath.split("/", "\\\\").filter { it.isNotBlank() }
                                val fileName = names.lastOrNull() ?: filePath
                                try {
                                    com.pai.android.data.model.Attachment.createLocalFileAttachment(
                                        messageId = aiMessage.id,
                                        fileName = fileName,
                                        mimeType = guessMimeType(filePath),
                                        localPath = filePath,  // stored as relative path
                                        fileSize = (resolveWorkspaceFile(filePath)?.length() ?: 0L),
                                        type = getAttachType(filePath)
                                    )
                                } catch (_: Exception) { null }
                            }
                            if (detected.isNotEmpty()) {
                                attachmentRepository.saveAttachmentsForMessage(aiMessage.id, detected)
                        }

                        }
                    }
                    clearWorkStatus()
                    
                    // Асинхронно извлекаем факты из диалога
                    
                    // Асинхронно извлекаем факты из диалога
                    viewModelScope.launch {
                        extractFactsFromDialogue(userMessage, aiMessage)
                    }
                }
                is com.pai.android.agent.AgentResponse.Error -> {
                    clearWorkStatus()
                    // Показываем ошибку пользователю (не переключаемся на обычный AI)
                    println("⚠️ Ошибка DecisionEngine: ${response.error}")
                    if (response.details != null) {
                        println("📋 Детали ошибки: ${response.details}")
                    }
                    
                    // Создаём сообщение об ошибке для пользователя
                    val errorMessage = if (response.details != null) {
                        "❌ ${response.error}\n\n${response.details}"
                    } else {
                        "❌ ${response.error}"
                    }
                    
                    val errorAiMessage = Message.createAssistantMessage(
                        chatId = chatId,
                        content = errorMessage
                    )
                    chatRepository.addMessage(errorAiMessage)
                }
            }
        } catch (e: Exception) {
            clearWorkStatus()
            println("⚠️ Исключение в processWithAgent: ${e.message}")
            e.printStackTrace()
            
            // В случае исключения тоже показываем ошибку
            val errorMessage = "❌ Произошла ошибка при обработке запроса: ${e.message}"
            val errorAiMessage = Message.createAssistantMessage(
                chatId = chatId,
                content = errorMessage
            )
            chatRepository.addMessage(errorAiMessage)
        } finally {
            // Сбрасываем флаг отправки — кнопка остановки исчезнет, вернётся кнопка отправки
            _state.update { it.copy(isSending = false) }
        }
    }


    /**
     * Extracts file paths from agent answer (looks for  markers).
     */
                        private fun extractFileReferences(text: String): List<String> {
        val refs = mutableListOf<String>()
        val docEmoji = "\uD83D\uDCC4"
        val folderEmoji = "\uD83D\uDCC2"
        var inCodeBlock = false
        for (line in text.lines()) {
            if (line.trimStart().startsWith("```")) { inCodeBlock = !inCodeBlock; continue }
            if (inCodeBlock) continue
            val trimmed = line.trimStart()
            // Pattern 1: Lines with 📄/📂 markers (file listings)
            if (trimmed.contains(docEmoji) || trimmed.contains(folderEmoji)) {
                var s = trimmed
                s = s.replace(Regex("^\\d+[.)]\\s*"), "")
                s = s.replace(Regex("^[^\\x00-\\x7F]+"), "")
                s = s.trim()
                s = s.replace(Regex("[(][^)]+[)]\\s*$"), "").trim()
                s = s.split(" ")[0].trim()
                if (isValidFilePath(s)) refs.add(s)
            }
            // Pattern 2: "Содержимое файла 'filename.ext'" format
            if (trimmed.contains("\u0444\u0430\u0439\u043B\u0430")) {
                // Russian "файла" — extract filename from single quotes
                val match = Regex("\u0027([^\u0027]+\\.[a-zA-Z0-9]+)\u0027").find(trimmed)
                if (match != null) {
                    val fileName = match.groupValues[1]
                    if (isValidFilePath(fileName)) refs.add(fileName)
                }
            }
        }
        return refs.distinct()
    }

    private fun isValidFilePath(s: String): Boolean {
        val knownExts = listOf("html","htm","txt","md","py","js","css",
            "json","xml","csv","pdf","doc","docx","xls","xlsx",
            "png","jpg","jpeg","gif","webp","svg","ico",
            "mp3","mp4","wav","ogg","zip","tar","gz")
        val ext = s.substringAfterLast(".", "").lowercase()
        if (s.isNotBlank() && (s.contains("/") || s.contains("\\") || ext in knownExts)) {
            return true
        }
        return false
    }

private fun resolveWorkspaceFile(relativePath: String): java.io.File? {
        try {
            // Try getting workspace root via multiple methods
            val root = context.getFilesDir()
            val ws = java.io.File(root, "workspace")
            val file = java.io.File(ws, relativePath)
            if (file.isFile()) return file
            // Try external files dir
            val ext = context.getExternalFilesDir(null)
            if (ext != null) {
                val extFile = java.io.File(java.io.File(ext, "workspace"), relativePath)
                if (extFile.isFile()) return extFile
            }
        } catch (_: Exception) {}
        return null
    }


    private fun findFileInWorkspace(wsRoot: java.io.File, fileName: String): java.io.File? {
        try {
            return wsRoot.walkTopDown().filter { it.isFile() && it.name == fileName }.firstOrNull()
        } catch (_: Exception) { return null }
    }

private fun getAttachType(fileName: String): com.pai.android.data.model.AttachmentType {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "png","jpg","jpeg","gif","webp","bmp" -> com.pai.android.data.model.AttachmentType.IMAGE
            "mp3","wav","ogg","m4a","flac" -> com.pai.android.data.model.AttachmentType.AUDIO
            "txt","md","html","htm","xml","json","css","js" -> com.pai.android.data.model.AttachmentType.TEXT
            "pdf","doc","docx","xls","xlsx","ppt","pptx" -> com.pai.android.data.model.AttachmentType.DOCUMENT
            else -> com.pai.android.data.model.AttachmentType.OTHER
        }
    }

private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "xml" -> "application/xml"
            "py" -> "text/x-python"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}