package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import java.util.UUID

/**
 * Роль отправителя сообщения.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Сообщение в чате.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Chat::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["chatId"])]
)
data class Message(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    val chatId: String,
    
    val role: MessageRole,
    
    val content: String,
    
    val timestamp: Long = System.currentTimeMillis(),
    
    val metadata: Map<String, String> = emptyMap(),
    
    val tokensUsed: Int? = null,
    
    val providerModel: String? = null
) {
    companion object {
        fun createUserMessage(chatId: String, content: String): Message {
            return Message(
                chatId = chatId,
                role = MessageRole.USER,
                content = content
            )
        }
        
        fun createAssistantMessage(chatId: String, content: String, providerModel: String? = null): Message {
            return Message(
                chatId = chatId,
                role = MessageRole.ASSISTANT,
                content = content,
                providerModel = providerModel
            )
        }
        
        fun createSystemMessage(chatId: String, content: String): Message {
            return Message(
                chatId = chatId,
                role = MessageRole.SYSTEM,
                content = content
            )
        }
    }
    
    fun isFromUser(): Boolean = role == MessageRole.USER
    fun isFromAssistant(): Boolean = role == MessageRole.ASSISTANT
    fun isFromSystem(): Boolean = role == MessageRole.SYSTEM
}