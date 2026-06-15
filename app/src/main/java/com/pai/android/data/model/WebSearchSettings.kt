package com.pai.android.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Провайдеры веб-поиска для внешних API.
 *
 * DUCKDUCKGO — встроенный fallback в WebSearchSkill, не отображается в UI.
 * Выбор DuckDuckGo в меню означал бы "не использовать внешние API",
 * поэтому он убран как опция выбора. Вместо этого, если enabled=false
 * или API-ключи не настроены — навык автоматически использует встроенный DuckDuckGo.
 */
enum class WebSearchProvider(val displayName: String, val requiresApiKey: Boolean) {
    GOOGLE("Google Custom Search", true),
    TAVILY("Tavily Search", true),
    /** Встроенный fallback, не отображается в UI */
    DUCKDUCKGO("DuckDuckGo (встроенный)", false);
    
    companion object {
        fun fromString(value: String): WebSearchProvider {
            return when (value.lowercase()) {
                "google" -> GOOGLE
                "tavily" -> TAVILY
                "duckduckgo", "ddg" -> DUCKDUCKGO
                else -> GOOGLE // По умолчанию Google
            }
        }
    }
}

/**
 * Настройки веб-поиска.
 * Хранит глобальные настройки и пользовательские предпочтения.
 * Поскольку приложение автономное и для одного пользователя,
 * используется одна запись с фиксированным ID.
 */
@Entity(tableName = "web_search_settings")
data class WebSearchSettings(
    @PrimaryKey
    val id: String = "web_search_settings", // Фиксированный ID для единственной записи
    
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = false, // Включение внешних API для WebSearchSkill. Если false — DuckDuckGo built-in.
    
    @ColumnInfo(name = "provider")
    val provider: WebSearchProvider = WebSearchProvider.GOOGLE,
    
    @ColumnInfo(name = "google_api_key")
    val googleApiKey: String? = null,
    
    @ColumnInfo(name = "google_search_engine_id")
    val googleSearchEngineId: String? = null,
    
    @ColumnInfo(name = "tavily_api_key")
    val tavilyApiKey: String? = null,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun createDefault(): WebSearchSettings {
            return WebSearchSettings(
                id = "web_search_settings",
                enabled = false,
                provider = WebSearchProvider.GOOGLE,
                googleApiKey = null,
                googleSearchEngineId = null,
                tavilyApiKey = null
            )
        }
    }
    
    /**
     * Проверяет, настроены ли внешние API для поиска.
     * Если внешний API не настроен — WebSearchSkill автоматически использует встроенный DuckDuckGo.
     */
    fun canPerformSearch(): Boolean {
        if (!enabled) return false
        
        return when (provider) {
            WebSearchProvider.GOOGLE -> 
                !googleApiKey.isNullOrBlank() && !googleSearchEngineId.isNullOrBlank()
            WebSearchProvider.TAVILY -> 
                !tavilyApiKey.isNullOrBlank()
            WebSearchProvider.DUCKDUCKGO -> 
                false // DuckDuckGo — встроенный fallback, не требует внешних настроек
        }
    }
    
    /**
     * Возвращает API ключ для текущего провайдера.
     */
    fun getApiKeyForProvider(): String? {
        return when (provider) {
            WebSearchProvider.GOOGLE -> googleApiKey
            WebSearchProvider.TAVILY -> tavilyApiKey
            WebSearchProvider.DUCKDUCKGO -> null
        }
    }
    
    /**
     * Создаёт копию настроек с обновлёнными значениями.
     */
    fun copyWithUpdates(
        enabled: Boolean? = null,
        provider: WebSearchProvider? = null,
        googleApiKey: String? = null,
        googleSearchEngineId: String? = null,
        tavilyApiKey: String? = null
    ): WebSearchSettings {
        return this.copy(
            enabled = enabled ?: this.enabled,
            provider = provider ?: this.provider,
            googleApiKey = googleApiKey ?: this.googleApiKey,
            googleSearchEngineId = googleSearchEngineId ?: this.googleSearchEngineId,
            tavilyApiKey = tavilyApiKey ?: this.tavilyApiKey,
            updatedAt = System.currentTimeMillis()
        )
    }
}