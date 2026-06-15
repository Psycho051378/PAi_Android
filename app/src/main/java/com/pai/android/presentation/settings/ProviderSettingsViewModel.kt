package com.pai.android.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.ProviderSettings
import com.pai.android.data.repository.ProviderSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние экрана настроек провайдеров.
 */
data class ProviderSettingsState(
    val providers: List<AiProvider> = emptyList(),
    val settings: Map<AiProvider, List<ProviderSettings>> = emptyMap(),
    val defaultSettings: ProviderSettings? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedProvider: AiProvider? = null,
    val editingSettings: ProviderSettings? = null
)

/**
 * ViewModel для экрана настроек провайдеров.
 */
@HiltViewModel
class ProviderSettingsViewModel @Inject constructor(
    private val settingsRepository: ProviderSettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ProviderSettingsState())
    val state: StateFlow<ProviderSettingsState> = _state.asStateFlow()

    init {
        loadProviders()
    }

    /**
     * Загружает все провайдеры и их настройки.
     */
    fun loadProviders() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // Инициализируем провайдеры по умолчанию (если ещё не инициализированы)
                settingsRepository.initializeDefaultProviders()
                
                val providers = AiProvider.values().toList()
                val settingsMap = mutableMapOf<AiProvider, List<ProviderSettings>>()
                val defaultSettings = settingsRepository.getDefaultSettings()
                
                providers.forEach { provider ->
                    val providerSettings = settingsRepository.getSettingsForProvider(provider)
                    settingsMap[provider] = providerSettings
                }
                
                _state.update { it.copy(
                    providers = providers,
                    settings = settingsMap,
                    defaultSettings = defaultSettings,
                    isLoading = false
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    isLoading = false,
                    errorMessage = "Не удалось загрузить настройки: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Выбирает провайдера для просмотра/редактирования.
     * Начинает редактирование новых настроек для выбранного провайдера.
     */
    fun selectProvider(provider: AiProvider) {
        println("🔧 selectProvider вызван для: ${provider.name}")
        _state.update { it.copy(selectedProvider = provider) }
        // Создаём новые настройки для этого провайдера
        startEditingSettings(ProviderSettings(provider = provider))
    }

    /**
     * Начинает редактирование настроек.
     */
    fun startEditingSettings(settings: ProviderSettings? = null) {
        _state.update { it.copy(editingSettings = settings) }
    }

    /**
     * Отменяет редактирование.
     */
    fun cancelEditing() {
        _state.update { it.copy(editingSettings = null) }
    }

    /**
     * Сохраняет или обновляет настройки провайдера.
     */
    fun saveSettings(
        provider: AiProvider,
        apiKey: String?,
        baseUrl: String?,
        modelName: String?,
        isDefault: Boolean,
        maxTokens: Int? = null,
        thinkingModeEnabled: Boolean = false,
        contextManagement: String = "truncate",
        modelMaxContext: Int? = null,
        modelMaxOutput: Int? = null,
        contextBufferPercent: Int = 90,
        useCustomParams: Boolean = false,
        temperature: Double? = null,
        topP: Double? = null
    ) {
        viewModelScope.launch {
            try {
                println("🔧 saveSettings начато для провайдера: ${provider.name}")
                val settings = _state.value.editingSettings?.copy(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    modelName = modelName,
                    isDefault = isDefault,
                    maxTokens = maxTokens,
                    thinkingModeEnabled = thinkingModeEnabled,
                    contextManagement = contextManagement,
                    modelMaxContext = modelMaxContext,
                    modelMaxOutput = modelMaxOutput,
                    contextBufferPercent = contextBufferPercent,
                    useCustomParams = useCustomParams,
                    temperature = temperature,
                    topP = topP,
                    updatedAt = System.currentTimeMillis()
                ) ?: ProviderSettings(
                    provider = provider,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    modelName = modelName,
                    isDefault = isDefault,
                    isEnabled = true,
                    maxTokens = maxTokens,
                    thinkingModeEnabled = thinkingModeEnabled,
                    contextManagement = contextManagement,
                    modelMaxContext = modelMaxContext,
                    modelMaxOutput = modelMaxOutput,
                    contextBufferPercent = contextBufferPercent
                )
                
                settingsRepository.saveSettings(settings)
                println("🔧 Сохранение настроек с ID: ${settings.id}")
                
                // Если эти настройки сделаны дефолтными, снимаем флаг с других
                if (isDefault) {
                    settingsRepository.setAsDefault(settings.id)
                }
                
                // Перезагружаем данные
                loadProviders()
                cancelEditing()
                
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось сохранить настройки: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Удаляет настройки провайдера.
     */
    fun deleteSettings(settingsId: String) {
        viewModelScope.launch {
            try {
                settingsRepository.deleteSettings(settingsId)
                loadProviders()
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось удалить настройки: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Переключает статус "по умолчанию" для настроек.
     */
    fun toggleDefaultSettings(settingsId: String, isDefault: Boolean) {
        viewModelScope.launch {
            try {
                if (isDefault) {
                    settingsRepository.setAsDefault(settingsId)
                } else {
                    // Снимаем флаг "по умолчанию" - устанавливаем другую настройку как дефолтную
                    val otherSettings = settingsRepository.getAllSettings().firstOrNull { it.id != settingsId }
                    otherSettings?.let { settingsRepository.setAsDefault(it.id) }
                }
                loadProviders()
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось изменить настройки по умолчанию: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Тестирует подключение к провайдеру.
     */
    fun testConnection(settings: ProviderSettings) {
        viewModelScope.launch {
            try {
                // TODO: Реализовать тестовый запрос
                // Для временной реализации просто возвращаем успех
                _state.update { it.copy(
                    errorMessage = "Тестирование подключения: функция в разработке"
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Ошибка тестирования подключения: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Очищает ошибку.
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}