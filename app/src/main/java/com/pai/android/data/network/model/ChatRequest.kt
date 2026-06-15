package com.pai.android.data.network.model

import com.google.gson.annotations.SerializedName

/**
 * Универсальный запрос к AI API.
 */
data class ChatRequest(
    @SerializedName("model")
    val model: String,
    
    @SerializedName("messages")
    val messages: List<ChatMessage>,
    
    @SerializedName("temperature")
    val temperature: Double? = 0.7,
    
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    
    @SerializedName("stream")
    val stream: Boolean? = false,
    
    @SerializedName("top_p")
    val topP: Double? = null,
    
    @SerializedName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    
    @SerializedName("presence_penalty")
    val presencePenalty: Double? = null,
    
    /** Режим мышления (DeepSeek V4: thinking mode). 
     * Передаётся как extra_body: {"thinking": {"type": "enabled"|"disabled"}} */
    @SerializedName("thinking")
    val thinking: Map<String, Any>? = null,

    /** Нативный tool calling DeepSeek/OpenAI.
     * Список инструментов в формате JSON Schema. */
    @SerializedName("tools")
    val tools: List<NativeToolDefinition>? = null,

    /** Auto = модель сама выбирает, none = отключить,
     * или жёсткое "specific" с function name. */
    @SerializedName("tool_choice")
    val toolChoice: String? = null
)

/**
 * Определение инструмента для нативного tool calling (DeepSeek/OpenAI API).
 */
data class NativeToolDefinition(
    @SerializedName("type")
    val type: String = "function",

    @SerializedName("function")
    val function: NativeFunctionDefinition
)

data class NativeFunctionDefinition(
    @SerializedName("name")
    val name: String,

    @SerializedName("description")
    val description: String,

    @SerializedName("parameters")
    val parameters: Map<String, Any>? = null
)