package com.pai.android.data.network.model

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Сообщение для AI API (унифицированный формат).
 * Поддерживает мультимодальные запросы через JsonElement content.
 */
data class ChatMessage(
    @SerializedName("role")
    val role: String, // "user", "assistant", "system"
    
    @SerializedName("content")
    val content: JsonElement, // JsonPrimitive для строки, JsonArray для мультимодального
    
    @SerializedName("name")
    val name: String? = null,

    /** Вызовы инструментов (только для role="assistant").
     * Заполняется моделью при нативном tool calling. */
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null
) {
    companion object {
        fun fromMessageRole(role: com.pai.android.data.model.MessageRole, content: String): ChatMessage {
            return ChatMessage(
                role = when (role) {
                    com.pai.android.data.model.MessageRole.USER -> "user"
                    com.pai.android.data.model.MessageRole.ASSISTANT -> "assistant"
                    com.pai.android.data.model.MessageRole.SYSTEM -> "system"
                },
                content = JsonPrimitive(content)
            )
        }
        
        fun fromMessageRoleWithImages(
            role: com.pai.android.data.model.MessageRole, 
            content: String,
            images: List<String>?
        ): ChatMessage {
            return createTextMessage(role, content)
        }
        
        fun createTextMessage(
            role: com.pai.android.data.model.MessageRole,
            text: String
        ): ChatMessage {
            return ChatMessage(
                role = when (role) {
                    com.pai.android.data.model.MessageRole.USER -> "user"
                    com.pai.android.data.model.MessageRole.ASSISTANT -> "assistant"
                    com.pai.android.data.model.MessageRole.SYSTEM -> "system"
                },
                content = JsonPrimitive(text)
            )
        }
        
        fun createMultimodalMessageForOpenAI(
            role: com.pai.android.data.model.MessageRole,
            text: String,
            imageBase64List: List<String>,
            mimeType: String = "image/jpeg"
        ): ChatMessage {
            val contentArray = JsonArray()
            
            if (text.isNotBlank()) {
                val textPart = JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                }
                contentArray.add(textPart)
            }
            
            imageBase64List.forEach { base64 ->
                val imagePart = JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:$mimeType;base64,$base64")
                    })
                }
                contentArray.add(imagePart)
            }
            
            if (contentArray.size() == 0) {
                contentArray.add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", "")
                })
            }
            
            return ChatMessage(
                role = when (role) {
                    com.pai.android.data.model.MessageRole.USER -> "user"
                    com.pai.android.data.model.MessageRole.ASSISTANT -> "assistant"
                    com.pai.android.data.model.MessageRole.SYSTEM -> "system"
                },
                content = contentArray
            )
        }
    }
}

/**
 * Вызов инструмента от модели (для нативного tool calling).
 */
data class ToolCall(
    @SerializedName("id")
    val id: String,

    @SerializedName("type")
    val type: String = "function",

    @SerializedName("function")
    val function: ToolCallFunction
)

data class ToolCallFunction(
    @SerializedName("name")
    val name: String,

    @SerializedName("arguments")
    val arguments: String
)
