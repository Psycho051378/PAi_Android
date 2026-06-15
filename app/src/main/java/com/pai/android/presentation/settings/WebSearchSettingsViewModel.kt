package com.pai.android.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.model.WebSearchProvider
import com.pai.android.data.model.WebSearchSettings
import com.pai.android.data.repository.WebSearchRepository
import com.pai.android.data.service.WebSearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel для экрана настроек веб-поиска.
 */
@HiltViewModel
class WebSearchSettingsViewModel @Inject constructor(
    private val webSearchRepository: WebSearchRepository,
    private val webSearchService: WebSearchService
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(WebSearchSettingsUiState())
    val uiState: StateFlow<WebSearchSettingsUiState> = _uiState.asStateFlow()
    
    private val _testConnectionResult = MutableStateFlow<ConnectionTestResult?>(null)
    val testConnectionResult: StateFlow<ConnectionTestResult?> = _testConnectionResult.asStateFlow()
    
    init {
        loadSettings()
    }
    
    /**
     * Загружает текущие настройки из репозитория.
     */
    private fun loadSettings() {
        viewModelScope.launch {
            // Сначала получаем настройки синхронно, чтобы гарантировать наличие записи
            val initialSettings = webSearchRepository.getSettings()
            _uiState.update { currentState ->
                currentState.copy(
                    enabled = initialSettings.enabled,
                    provider = initialSettings.provider,
                    googleApiKey = initialSettings.googleApiKey ?: "",
                    googleSearchEngineId = initialSettings.googleSearchEngineId ?: "",
                    tavilyApiKey = initialSettings.tavilyApiKey ?: "",
                    isLoading = false
                )
            }
            
            // Затем подписываемся на изменения
            webSearchRepository.observeSettings().collect { settings ->
                settings?.let {
                    _uiState.update { currentState ->
                        currentState.copy(
                            enabled = it.enabled,
                            provider = it.provider,
                            googleApiKey = it.googleApiKey ?: "",
                            googleSearchEngineId = it.googleSearchEngineId ?: "",
                            tavilyApiKey = it.tavilyApiKey ?: "",
                            isLoading = false
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Обновляет глобальное включение модуля.
     */
    fun updateEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        viewModelScope.launch {
            webSearchRepository.updateEnabled(enabled)
        }
    }
    
    /**
     * Выбранный провайдер.
     */
    fun updateProvider(provider: WebSearchProvider) {
        _uiState.update { it.copy(provider = provider) }
        viewModelScope.launch {
            webSearchRepository.updateProvider(provider)
        }
    }
    
    /**
     * Обновляет Google API ключ.
     */
    fun updateGoogleApiKey(apiKey: String) {
        _uiState.update { it.copy(googleApiKey = apiKey) }
        viewModelScope.launch {
            webSearchRepository.updateGoogleApiKey(apiKey.takeIf { it.isNotBlank() })
        }
    }
    
    /**
     * Обновляет Google Search Engine ID.
     */
    fun updateGoogleSearchEngineId(engineId: String) {
        _uiState.update { it.copy(googleSearchEngineId = engineId) }
        viewModelScope.launch {
            webSearchRepository.updateGoogleSearchEngineId(engineId.takeIf { it.isNotBlank() })
        }
    }
    
    /**
     * Обновляет Tavily API ключ.
     */
    fun updateTavilyApiKey(apiKey: String) {
        _uiState.update { it.copy(tavilyApiKey = apiKey) }
        viewModelScope.launch {
            webSearchRepository.updateTavilyApiKey(apiKey.takeIf { it.isNotBlank() })
        }
    }
    
    /**
     * Сохраняет все текущие настройки.
     */
    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            webSearchRepository.updateSettings(
                enabled = state.enabled,
                provider = state.provider,
                googleApiKey = state.googleApiKey.takeIf { it.isNotBlank() },
                googleSearchEngineId = state.googleSearchEngineId.takeIf { it.isNotBlank() },
                tavilyApiKey = state.tavilyApiKey.takeIf { it.isNotBlank() }
            )
        }
    }
    
    /**
     * Тестирует соединение с текущим провайдером.
     */
    fun testConnection() {
        viewModelScope.launch {
            _testConnectionResult.value = ConnectionTestResult.Testing
            try {
                val success = webSearchService.testConnection()
                _testConnectionResult.value = if (success) {
                    ConnectionTestResult.Success("Соединение с провайдером установлено успешно")
                } else {
                    ConnectionTestResult.Error("Не удалось установить соединение. Проверьте настройки.")
                }
            } catch (e: Exception) {
                _testConnectionResult.value = ConnectionTestResult.Error("Ошибка: ${e.message}")
            }
        }
    }
    
    /**
     * Сбрасывает результат тестирования соединения.
     */
    fun clearTestResult() {
        _testConnectionResult.value = null
    }
    
    /**
     * Проверяет, можно ли выполнять поиск через внешние API с текущими настройками.
     */
    fun canPerformSearch(): Boolean {
        val state = _uiState.value
        if (!state.enabled) return false
        
        return when (state.provider) {
            WebSearchProvider.GOOGLE -> 
                state.googleApiKey.isNotBlank() && state.googleSearchEngineId.isNotBlank()
            WebSearchProvider.TAVILY -> 
                state.tavilyApiKey.isNotBlank()
            WebSearchProvider.DUCKDUCKGO -> 
                false
        }
    }
}

/**
 * Состояние UI экрана настроек веб-поиска.
 */
data class WebSearchSettingsUiState(
    val enabled: Boolean = false,
    val provider: WebSearchProvider = WebSearchProvider.GOOGLE,
    val googleApiKey: String = "",
    val googleSearchEngineId: String = "",
    val tavilyApiKey: String = "",
    val isLoading: Boolean = true
)

/**
 * Результат тестирования соединения.
 */
sealed class ConnectionTestResult {
    object Testing : ConnectionTestResult()
    data class Success(val message: String) : ConnectionTestResult()
    data class Error(val message: String) : ConnectionTestResult()
}