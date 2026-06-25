package com.pai.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.ProviderSettings
import com.pai.android.data.model.SmartRouterConfig
import com.pai.android.data.repository.ProviderSettingsRepository
import com.pai.android.data.repository.SmartRouterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SmartRouterUiState(
    val enabled: Boolean = false,
    val networkProviderSettingsId: String = "",
    val networkProviderName: String = "",
    val networkModelName: String = "",
    val availableProviders: List<Pair<AiProvider, List<ProviderSettings>>> = emptyList(),
    val availableModelsForSelectedProvider: List<String> = emptyList(),
    val selectedProviderSettingsId: String = "",
    val complexityThreshold: Float = 0.5f,
    val maxLocalTokens: Int = 512,
    val routeMultimodalToLocal: Boolean = true,
    val enableFallback: Boolean = true,
    val enableHybrid: Boolean = false,
    val saved: Boolean = false
)

@HiltViewModel
class SmartRouterSettingsViewModel @Inject constructor(
    private val repository: SmartRouterRepository,
    private val providerSettingsRepository: ProviderSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmartRouterUiState())
    val uiState: StateFlow<SmartRouterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val allSettings = providerSettingsRepository.getAllSettings()
            // Только сетевые провайдеры (не LITE_RT)
            val providers = AiProvider.values()
                .filter { it != AiProvider.LITE_RT && it != AiProvider.CUSTOM }
                .map { provider ->
                    provider to allSettings.filter { it.provider == provider }
                }
                .filter { it.second.isNotEmpty() }

            val routerConfig = repository.get()
            val selectedSettings = allSettings.find { it.id == routerConfig.networkProviderSettingsId }

            _uiState.value = SmartRouterUiState(
                enabled = routerConfig.enabled,
                networkProviderSettingsId = routerConfig.networkProviderSettingsId,
                networkProviderName = selectedSettings?.provider?.displayName ?: "",
                networkModelName = selectedSettings?.getEffectiveModel() ?: "",
                availableProviders = providers,
                // Модели для выбранного провайдера
                availableModelsForSelectedProvider = selectedSettings?.let { s ->
                    providers.firstOrNull { it.first == s.provider }?.second?.mapNotNull { it.getEffectiveModel() }?.distinct() ?: emptyList()
                } ?: emptyList(),
                complexityThreshold = routerConfig.complexityThreshold,
                maxLocalTokens = routerConfig.maxLocalTokens,
                routeMultimodalToLocal = routerConfig.routeMultimodalToLocal,
                enableFallback = routerConfig.enableFallback,
                enableHybrid = routerConfig.enableHybrid
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = enabled)
        viewModelScope.launch { repository.setEnabled(enabled) }
    }

    /** Выбрать провайдера (первую его настройку). */
    fun selectProvider(provider: AiProvider) {
        viewModelScope.launch {
            val allSettings = providerSettingsRepository.getAllSettings()
            val providerSettings = allSettings.filter { it.provider == provider }
            if (providerSettings.isNotEmpty()) {
                val first = providerSettings.first()
                val models = providerSettings.mapNotNull { it.getEffectiveModel() }.distinct()
                _uiState.value = _uiState.value.copy(
                    selectedProviderSettingsId = first.id,
                    networkProviderSettingsId = first.id,
                    networkProviderName = provider.displayName,
                    networkModelName = first.getEffectiveModel(),
                    availableModelsForSelectedProvider = models
                )
                repository.setNetworkProviderSettingsId(first.id)
            }
        }
    }

    /** Выбрать конкретную модель (настройку) внутри провайдера. */
    fun selectProviderSettings(settingsId: String) {
        viewModelScope.launch {
            val allSettings = providerSettingsRepository.getAllSettings()
            val selected = allSettings.find { it.id == settingsId }
            if (selected != null) {
                _uiState.value = _uiState.value.copy(
                    selectedProviderSettingsId = settingsId,
                    networkProviderSettingsId = settingsId,
                    networkModelName = selected.getEffectiveModel(),
                    networkProviderName = selected.provider.displayName
                )
                repository.setNetworkProviderSettingsId(settingsId)
            }
        }
    }

    fun setComplexityThreshold(threshold: Float) {
        _uiState.value = _uiState.value.copy(complexityThreshold = threshold)
        viewModelScope.launch { repository.setComplexityThreshold(threshold) }
    }

    fun setMaxLocalTokens(tokens: Int) {
        _uiState.value = _uiState.value.copy(maxLocalTokens = tokens)
        viewModelScope.launch {
            val cfg = repository.get().copy(maxLocalTokens = tokens)
            repository.save(cfg)
        }
    }

    fun setRouteMultimodalToLocal(value: Boolean) {
        _uiState.value = _uiState.value.copy(routeMultimodalToLocal = value)
        viewModelScope.launch {
            val cfg = repository.get().copy(routeMultimodalToLocal = value)
            repository.save(cfg)
        }
    }

    fun setEnableFallback(value: Boolean) {
        _uiState.value = _uiState.value.copy(enableFallback = value)
        viewModelScope.launch {
            val cfg = repository.get().copy(enableFallback = value)
            repository.save(cfg)
        }
    }

    fun setEnableHybrid(value: Boolean) {
        _uiState.value = _uiState.value.copy(enableHybrid = value)
        viewModelScope.launch {
            val cfg = repository.get().copy(enableHybrid = value)
            repository.save(cfg)
        }
    }

    fun save() {
        viewModelScope.launch {
            val s = _uiState.value
            repository.save(
                SmartRouterConfig(
                    enabled = s.enabled,
                    networkProviderSettingsId = s.networkProviderSettingsId,
                    complexityThreshold = s.complexityThreshold,
                    maxLocalTokens = s.maxLocalTokens,
                    routeMultimodalToLocal = s.routeMultimodalToLocal,
                    enableFallback = s.enableFallback,
                    enableHybrid = s.enableHybrid
                )
            )
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }

    fun clearSavedFlag() {
        _uiState.value = _uiState.value.copy(saved = false)
    }
}
