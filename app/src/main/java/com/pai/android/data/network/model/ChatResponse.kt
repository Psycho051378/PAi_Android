package com.pai.android.data.network.model

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Универсальный ответ от AI API.
 */
data class ChatResponse(
    @SerializedName("id")
    val id: String? = null,
    
    @SerializedName("object")
    val objectType: String? = null,
    
    @SerializedName("created")
    val created: Long? = null,
    
    @SerializedName("model")
    val model: String? = null,
    
    @SerializedName("choices")
    val choices: List<Choice> = emptyList(),
    
    @SerializedName("usage")
    val usage: Usage? = null,
    
    @SerializedName("error")
    val error: Error? = null
) {
    data class Choice(
        @SerializedName("index")
        val index: Int? = null,
        
        @SerializedName("message")
        val message: ChatMessage? = null,
        
        @SerializedName("delta")
        val delta: ChatMessage? = null,
        
        @SerializedName("finish_reason")
        val finishReason: String? = null
    )
    
    data class Usage(
        @SerializedName("prompt_tokens")
        val promptTokens: Int? = null,
        
        @SerializedName("completion_tokens")
        val completionTokens: Int? = null,
        
        @SerializedName("total_tokens")
        val totalTokens: Int? = null
    )
    
    data class Error(
        @SerializedName("message")
        val message: String? = null,
        
        @SerializedName("type")
        val type: String? = null,
        
        @SerializedName("code")
        val code: String? = null
    )
    
    fun getContent(): String {
        val contentElement = choices.firstOrNull()?.message?.content ?: return ""
        return extractTextFromJsonElement(contentElement)
    }
    
    private fun extractTextFromJsonElement(element: JsonElement): String {
        return when {
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                if (primitive.isString) primitive.asString else primitive.toString()
            }
            element.isJsonArray -> {
                val array = element.asJsonArray
                // Ищем текстовую часть в мультимодальном сообщении
                for (item in array) {
                    if (item.isJsonObject) {
                        val obj = item.asJsonObject
                        val type = obj.get("type")?.asString
                        if (type == "text") {
                            val text = obj.get("text")?.asString
                            if (!text.isNullOrBlank()) return text
                        }
                    }
                }
                // Если не нашли текстовую часть, преобразуем весь массив в строку
                array.toString()
            }
            else -> element.toString()
        }
    }
    
    fun hasError(): Boolean = error != null
}