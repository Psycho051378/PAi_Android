package com.pai.android.data.repository

import com.pai.android.data.local.ChatDao
import com.pai.android.data.local.MessageDao
import com.pai.android.data.model.Chat
import com.pai.android.data.model.Message
import com.pai.android.data.model.MessageWithAttachments
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Репозиторий для работы с чатами и сообщениями.
 */
class ChatRepository(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
) {
    
    // ============= Чат операции =============
    
    fun observeAllChats(): Flow<List<Chat>> {
        return chatDao.observeAll()
    }
    
    fun observeActiveChats(): Flow<List<Chat>> {
        return chatDao.observeActiveChats()
    }
    
    fun observeChatWithMessages(chatId: String): Flow<Pair<Chat?, List<Message>>> {
        val chatFlow = chatDao.observeById(chatId)
        val messagesFlow = messageDao.observeByChatId(chatId)
        
        return combine(chatFlow, messagesFlow) { chat, messages ->
            chat to messages
        }
    }
    
    fun observeChatWithAttachments(chatId: String): Flow<Pair<Chat?, List<MessageWithAttachments>>> {
        val chatFlow = chatDao.observeById(chatId)
        val messagesFlow = messageDao.observeWithAttachments(chatId)
        
        return combine(chatFlow, messagesFlow) { chat, messages ->
            chat to messages
        }
    }
    
    suspend fun getChat(chatId: String): Chat? {
        return chatDao.getById(chatId)
    }
    
    suspend fun createChat(title: String = "Новый чат"): Chat {
        val chat = Chat.createNew(title)
        chatDao.insert(chat)
        return chat
    }
    
    suspend fun updateChat(chat: Chat) {
        chatDao.update(chat)
    }
    
    suspend fun deleteChat(chatId: String) {
        // Сначала удаляем сообщения (каскадно через foreign key)
        messageDao.deleteByChatId(chatId)
        // Затем удаляем чат
        val chat = chatDao.getById(chatId)
        chat?.let { chatDao.delete(it) }
    }
    
    suspend fun archiveChat(chatId: String, isArchived: Boolean) {
        chatDao.setArchived(chatId, isArchived)
    }
    
    // ============= Сообщения операции =============
    
    suspend fun addMessage(message: Message): String {
        val messageId = messageDao.insert(message).toString()
        // Обновляем счётчик сообщений в чате
        chatDao.incrementMessageCount(message.chatId)
        return message.id
    }
    
    suspend fun addMessages(messages: List<Message>) {
        messageDao.insertAll(messages)
        // Обновляем счётчик для каждого уникального чата
        messages.groupBy { it.chatId }.forEach { (chatId, _) ->
            chatDao.incrementMessageCount(chatId)
        }
    }
    
    fun observeMessages(chatId: String): Flow<List<Message>> {
        return messageDao.observeByChatId(chatId)
    }
    
    suspend fun getMessages(chatId: String): List<Message> {
        return messageDao.getByChatId(chatId)
    }
    
    suspend fun getMessages(chatId: String, limit: Int): List<Message> {
        return messageDao.getByChatIdLimited(chatId, limit)
    }
    
    suspend fun getMessageCount(chatId: String): Int {
        return messageDao.getMessageCount(chatId)
    }
    
    suspend fun deleteMessage(messageId: String) {
        val message = messageDao.getById(messageId)
        message?.let { messageDao.delete(it) }
    }
    
    suspend fun clearChatMessages(chatId: String) {
        messageDao.deleteByChatId(chatId)
        // Сбрасываем счётчик сообщений
        val chat = chatDao.getById(chatId)
        chat?.let {
            chatDao.update(it.copy(messageCount = 0))
        }
    }
    
    // ============= Комбинированные операции =============
    
    suspend fun createChatWithInitialMessage(
        title: String,
        initialMessage: String,
        systemPrompt: String? = null
    ): Pair<Chat, Message> {
        // Генерируем заголовок из первого сообщения, если заголовок по умолчанию
        val finalTitle = if (title.isEmpty() || title == "Новый чат") {
            Chat.generateTitleFromMessage(initialMessage)
        } else {
            title
        }
        
        // Создаем чат
        val chat = Chat.createNew(finalTitle).copy(systemPrompt = systemPrompt)
        chatDao.insert(chat)
        
        // Добавляем системное сообщение (если есть)
        if (!systemPrompt.isNullOrBlank()) {
            val systemMessage = Message.createSystemMessage(chat.id, systemPrompt)
            messageDao.insert(systemMessage)
        }
        
        // Добавляем первое пользовательское сообщение
        val userMessage = Message.createUserMessage(chat.id, initialMessage)
        messageDao.insert(userMessage)
        
        // Обновляем счётчик
        chatDao.incrementMessageCount(chat.id)
        
        return chat to userMessage
    }
}