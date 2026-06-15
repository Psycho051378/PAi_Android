package com.pai.android.data.model

/**
 * Ответ от AI системы.
 */
data class AiResponse(
    val text: String,
    val provider: AiProvider,
    val modelUsed: String,
    val tokensUsed: Int? = null,
    val error: Throwable? = null,
    /** Вызовы инструментов от модели (нативный tool calling). */
    val toolCalls: List<NativeToolCall>? = null
) {
    companion object {
        fun success(
            text: String,
            provider: AiProvider,
            modelUsed: String,
            tokensUsed: Int? = null,
            toolCalls: List<NativeToolCall>? = null
        ): AiResponse {
            return AiResponse(
                text = text,
                provider = provider,
                modelUsed = modelUsed,
                tokensUsed = tokensUsed,
                toolCalls = toolCalls
            )
        }
        
        fun error(
            error: Throwable,
            provider: AiProvider,
            modelUsed: String = "unknown"
        ): AiResponse {
            return AiResponse(
                text = error.message ?: "Unknown error",
                provider = provider,
                modelUsed = modelUsed,
                error = error
            )
        }
    }
    
    val isSuccess: Boolean get() = error == null
    val isError: Boolean get() = error != null
    val hasToolCalls: Boolean get() = !toolCalls.isNullOrEmpty()
}

/**
 * Вызов инструмента от модели (нативный tool calling).
 */
data class NativeToolCall(
    val name: String,
    val arguments: Map<String, Any>
)
