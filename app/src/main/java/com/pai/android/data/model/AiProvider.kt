package com.pai.android.data.model

/**
 * Поддерживаемые AI провайдеры.
 */
enum class AiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean = true,
    val availableModels: List<String> = emptyList()
) {
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1/",
        defaultModel = "openrouter/free",
        requiresApiKey = true,
        availableModels = listOf(
            "openrouter/auto",
            "openai/gpt-4-turbo-preview",
            "openai/gpt-4",
            "openai/gpt-3.5-turbo",
            "anthropic/claude-3-opus",
            "google/gemini-pro"
        )
    ),
    
    DEEPSEEK(
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/",
        defaultModel = "deepseek-v4-flash",
        requiresApiKey = true,
        availableModels = listOf(
            "deepseek-chat",
            "deepseek-coder",
            "deepseek-reasoner"
        )
    ),
    
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-3.5-turbo",
        requiresApiKey = true,
        availableModels = listOf(
            "gpt-4-turbo-preview",
            "gpt-4",
            "gpt-3.5-turbo",
            "dall-e-3",
            "whisper-1"
        )
    ),
    
    LITE_RT(
        displayName = "Локально (LiteRT)",
        defaultBaseUrl = "",
        defaultModel = "gemma-4-e2b",
        requiresApiKey = false,
        availableModels = listOf(
            "gemma-4-e2b",
            "gemma-4-e4b"
        )
    ),

    OLLAMA(
        displayName = "Ollama",
        defaultBaseUrl = "http://10.0.2.2:11434/", // Для эмулятора Android
        defaultModel = "llama2",
        requiresApiKey = false,
        availableModels = listOf(
            "llama2",
            "llama2:13b",
            "llama2:70b",
            "mistral",
            "codellama",
            "phi"
        )
    ),
    
    CUSTOM(
        displayName = "Другой провайдер",
        defaultBaseUrl = "",
        defaultModel = "",
        requiresApiKey = false,
        availableModels = emptyList()
    );
    
    companion object {
        fun fromName(name: String): AiProvider? {
            return values().find { it.name.equals(name, ignoreCase = true) }
        }
    }
}