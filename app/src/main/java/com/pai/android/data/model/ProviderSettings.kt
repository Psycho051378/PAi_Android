package com.pai.android.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Настройки AI провайдера.
 */
@Entity(tableName = "provider_settings")
data class ProviderSettings(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(), // Авто-генерация уникального ID
    
    @ColumnInfo(name = "provider")
    val provider: AiProvider,
    
    val apiKey: String? = null,
    
    val baseUrl: String? = null,
    
    val modelName: String? = null,
    
    val isDefault: Boolean = false,
    
    val isEnabled: Boolean = true,
    
    val metadata: Map<String, String> = emptyMap(),
    
    /** Максимальный лимит на ответ (токенов). null = использует дефолт 2000 */
    @ColumnInfo(name = "max_tokens")
    val maxTokens: Int? = null,
    
    /** Контекстное окно модели (токенов). null = не задано (DeepSeek: 1M) */
    @ColumnInfo(name = "model_max_context")
    val modelMaxContext: Int? = null,
    
    /** Максимальный вывод модели (токенов). null = не задано (DeepSeek: 384K) */
    @ColumnInfo(name = "model_max_output")
    val modelMaxOutput: Int? = null,
    
    /** Режим мышления (DeepSeek V4 thinking mode). false = disabled, true = enabled */
    @ColumnInfo(name = "thinking_mode_enabled")
    val thinkingModeEnabled: Boolean = false,
    
    /** Использовать пользовательские параметры (temperature, top_p) вместо умолчаний */
    @ColumnInfo(name = "use_custom_params")
    val useCustomParams: Boolean = false,
    
    /** Температура модели (0.0 - 2.0). null = использовать умолчание */
    @ColumnInfo(name = "temperature")
    val temperature: Double? = null,
    
    /** Top-p sampling (0.0 - 1.0). null = использовать умолчание */
    @ColumnInfo(name = "top_p")
    val topP: Double? = null,
    
    /** Стратегия управления контекстом при превышении лимита: truncate|summarize */
    @ColumnInfo(name = "context_management")
    val contextManagement: String = "truncate",
    
    /** Процент буфера от лимита перед срабатыванием тримминга (0-100) */
    @ColumnInfo(name = "context_buffer_percent")
    val contextBufferPercent: Int = 90,
    
    val createdAt: Long = System.currentTimeMillis(),
    
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun createDefault(provider: AiProvider): ProviderSettings {
            // Специальные настройки для OpenRouter по умолчанию
            val (apiKey, isDefault) = when (provider) {
                AiProvider.OPENROUTER -> Pair("", true) // OpenRouter по умолчанию (ключ нужно будет ввести)
                else -> Pair(null, false) // Для остальных провайдеров без ключа
            }
            
            // Дефолтные параметры для новых моделей DeepSeek
            val (maxTokens, modelMaxContext, modelMaxOutput) = when (provider) {
                AiProvider.DEEPSEEK -> Triple(8192, 1000000, 384000)
                else -> Triple(null, null, null)
            }
            
            val settings = ProviderSettings(
                id = UUID.randomUUID().toString(), // Каждый раз новый ID
                provider = provider,
                apiKey = apiKey,
                baseUrl = provider.defaultBaseUrl,
                modelName = provider.defaultModel,
                isDefault = isDefault,
                isEnabled = true,
                maxTokens = maxTokens,
                modelMaxContext = modelMaxContext,
                modelMaxOutput = modelMaxOutput,
                thinkingModeEnabled = false, // По умолчанию Non-Thinking
                contextManagement = "truncate",
                contextBufferPercent = 90
            )
            
            // Логирование для отладки
            println("🔧 ProviderSettings.createDefault: provider=${provider}, apiKey=${apiKey}, isDefault=$isDefault, model=${provider.defaultModel}")
            println("🔧 ProviderSettings.createDefault: maxTokens=$maxTokens, modelMaxContext=$modelMaxContext, modelMaxOutput=$modelMaxOutput")
            
            return settings
        }
    }
    
    fun getEffectiveBaseUrl(): String {
        val url = baseUrl ?: provider.defaultBaseUrl
        // Гарантируем, что URL заканчивается на /
        return if (url.endsWith("/")) url else "$url/"
    }
    
    fun getEffectiveModel(): String {
        return modelName ?: provider.defaultModel
    }
    
    fun resolveMaxTokens(): Int {
        return maxTokens ?: 2000
    }
    
    fun isValid(): Boolean {
        if (!isEnabled) return false
        if (provider.requiresApiKey && apiKey.isNullOrBlank()) return false
        return true
    }
}