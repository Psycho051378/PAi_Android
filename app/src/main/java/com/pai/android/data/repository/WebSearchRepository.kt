package com.pai.android.data.repository

import com.pai.android.data.local.WebSearchSettingsDao
import com.pai.android.data.model.WebSearchProvider
import com.pai.android.data.model.WebSearchSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Репозиторий для работы с настройками веб-поиска.
 */
class WebSearchRepository @Inject constructor(
    private val webSearchSettingsDao: WebSearchSettingsDao
) {
    
    /**
     * Получает текущие настройки веб-поиска.
     * Если настройки ещё не сохранены, создаёт запись по умолчанию.
     */
    suspend fun getSettings(): WebSearchSettings {
        val settings = webSearchSettingsDao.getSettings()
        return settings ?: createDefaultSettings()
    }
    
    /**
     * Наблюдает за изменениями настроек веб-поиска.
     */
    fun observeSettings(): Flow<WebSearchSettings?> {
        return webSearchSettingsDao.observeSettings()
    }
    
    /**
     * Сохраняет настройки веб-поиска.
     */
    suspend fun saveSettings(settings: WebSearchSettings) {
        webSearchSettingsDao.insertOrReplace(settings)
    }
    
    /**
     * Обновляет глобальное включение модуля веб-поиска.
     */
    suspend fun updateEnabled(enabled: Boolean) {
        webSearchSettingsDao.updateEnabled(enabled)
    }
    
    /**
     * Обновляет провайдера поиска.
     */
    suspend fun updateProvider(provider: WebSearchProvider) {
        webSearchSettingsDao.updateProvider(provider.name)
    }
    
    /**
     * Обновляет Google API ключ.
     */
    suspend fun updateGoogleApiKey(apiKey: String?) {
        webSearchSettingsDao.updateGoogleApiKey(apiKey)
    }
    
    /**
     * Обновляет Google Search Engine ID.
     */
    suspend fun updateGoogleSearchEngineId(engineId: String?) {
        webSearchSettingsDao.updateGoogleSearchEngineId(engineId)
    }
    
    /**
     * Обновляет Tavily API ключ.
     */
    suspend fun updateTavilyApiKey(apiKey: String?) {
        webSearchSettingsDao.updateTavilyApiKey(apiKey)
    }
    
    /**
     * Создаёт настройки по умолчанию и сохраняет их в БД.
     */
    private suspend fun createDefaultSettings(): WebSearchSettings {
        val defaultSettings = WebSearchSettings.createDefault()
        webSearchSettingsDao.insertOrReplace(defaultSettings)
        return defaultSettings
    }
    
    /**
     * Проверяет, можно ли выполнять веб-поиск с текущими настройками.
     */
    suspend fun canPerformSearch(): Boolean {
        val settings = getSettings()
        return settings.canPerformSearch()
    }
    
    /**
     * Возвращает API ключ для текущего провайдера.
     */
    suspend fun getApiKeyForCurrentProvider(): String? {
        val settings = getSettings()
        return settings.getApiKeyForProvider()
    }
    
    /**
     * Обновляет несколько полей настроек одновременно.
     */
    suspend fun updateSettings(
        enabled: Boolean? = null,
        provider: WebSearchProvider? = null,
        googleApiKey: String? = null,
        googleSearchEngineId: String? = null,
        tavilyApiKey: String? = null
    ) {
        val currentSettings = getSettings()
        val updatedSettings = currentSettings.copyWithUpdates(
            enabled = enabled,
            provider = provider,
            googleApiKey = googleApiKey,
            googleSearchEngineId = googleSearchEngineId,
            tavilyApiKey = tavilyApiKey
        )
        saveSettings(updatedSettings)
    }
}