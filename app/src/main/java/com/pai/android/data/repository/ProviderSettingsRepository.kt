package com.pai.android.data.repository

import com.pai.android.data.local.ProviderSettingsDao
import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.ProviderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Репозиторий для работы с настройками AI провайдеров.
 */
class ProviderSettingsRepository(
    private val providerSettingsDao: ProviderSettingsDao
) {
    
    fun observeAllSettings(): Flow<List<ProviderSettings>> {
        return providerSettingsDao.observeAll()
    }
    
    suspend fun getAllSettings(): List<ProviderSettings> {
        return providerSettingsDao.observeAll().first()
    }
    
    suspend fun getSettingsForProvider(provider: AiProvider): List<ProviderSettings> {
        return providerSettingsDao.observeAll().first().filter { it.provider == provider }
    }
    
    suspend fun saveSettings(settings: ProviderSettings): String {
        val existing = providerSettingsDao.getById(settings.id)
        return if (existing == null) {
            providerSettingsDao.insert(settings)
            settings.id
        } else {
            providerSettingsDao.update(settings)
            settings.id
        }
    }
    
    suspend fun getSettings(settingsId: String): ProviderSettings? {
        return providerSettingsDao.getById(settingsId)
    }
    
    suspend fun getDefaultSettings(): ProviderSettings? {
        return providerSettingsDao.getDefaultSettings()
    }
    
    fun observeDefaultSettings(): Flow<ProviderSettings?> {
        return providerSettingsDao.observeDefaultSettings()
    }
    
    suspend fun createSettings(settings: ProviderSettings): String {
        val rowId = providerSettingsDao.insert(settings)
        return settings.id
    }
    
    suspend fun updateSettings(settings: ProviderSettings) {
        providerSettingsDao.update(settings)
    }
    
    suspend fun deleteSettings(settingsId: String) {
        val settings = providerSettingsDao.getById(settingsId)
        settings?.let { providerSettingsDao.delete(it) }
    }
    
    suspend fun setAsDefault(settingsId: String) {
        // Сначала сбрасываем все default
        providerSettingsDao.clearDefault()
        // Устанавливаем новый default
        providerSettingsDao.setAsDefault(settingsId)
    }
    
    suspend fun createDefaultSettingsForProvider(provider: AiProvider): ProviderSettings {
        val defaultSettings = ProviderSettings.createDefault(provider)
        providerSettingsDao.insert(defaultSettings)
        return defaultSettings
    }
    
    suspend fun getOrCreateDefaultSettingsForProvider(provider: AiProvider): ProviderSettings {
        val existing = providerSettingsDao.getEnabledByProvider(provider).firstOrNull()
        return existing ?: createDefaultSettingsForProvider(provider)
    }
    
    suspend fun initializeDefaultProviders() {
        println("🔧 ProviderSettingsRepository.initializeDefaultProviders: начало")
        
        // Создаём настройки по умолчанию для всех провайдеров, кроме CUSTOM
        val providers = AiProvider.values().filter { it != AiProvider.CUSTOM }
        val existingSettings = getAllSettings()
        
        println("🔧 Существующие настройки: ${existingSettings.size}")
        existingSettings.forEach { println("  - ${it.provider}: id=${it.id}, isDefault=${it.isDefault}") }
        
        for (provider in providers) {
            val hasSettings = existingSettings.any { it.provider == provider }
            println("🔧 Проверка провайдера ${provider}: hasSettings=$hasSettings")
            if (!hasSettings) {
                println("🔧 Создание настроек для ${provider}")
                createDefaultSettingsForProvider(provider)
            }
        }
        
        // Убедимся, что есть default настройки
        val currentDefault = getDefaultSettings()
        println("🔧 Текущие default настройки: ${currentDefault?.provider}")
        
        if (currentDefault == null) {
            println("🔧 Default настроек нет, устанавливаем OpenRouter как fallback")
            val openRouterSettings = getAllSettings()
                .firstOrNull { it.provider == AiProvider.OPENROUTER }
                ?: createDefaultSettingsForProvider(AiProvider.OPENROUTER)
            
            println("🔧 Устанавливаем OpenRouter как fallback default: id=${openRouterSettings.id}")
            setAsDefault(openRouterSettings.id)
        }
        // Если default уже есть (любой провайдер), оставляем его как есть
        // Не перезаписываем выбор пользователя!
        
        println("🔧 ProviderSettingsRepository.initializeDefaultProviders: завершено")
    }
}