package com.pai.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.agent.skills.HomeSkill
import com.pai.android.agent.skills.RouterConfigData
import com.pai.android.agent.skills.home.router.ProtocolType
import com.pai.android.agent.skills.home.router.RouterConfig
import com.pai.android.agent.skills.home.router.RouterScanner
import com.pai.android.agent.skills.home.router.TestResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние UI экрана настроек роутера.
 */
data class RouterSettingsUiState(
    val enabled: Boolean = false,
    val ip: String = "192.168.0.1",
    val port: String = "80",
    val protocol: ProtocolType = ProtocolType.HTTP,
    val username: String = "",
    val password: String = "",
    val community: String = "public",
    val loading: Boolean = false,
    val testResult: TestResult? = null,
    val saved: Boolean = false,
    val currentSsid: String? = null,
    val error: String? = null
)

/**
 * ViewModel для экрана настроек роутера.
 * Использует HomeSkill для хранения конфигурации и RouterScanner для проверки соединения.
 */
@HiltViewModel
class RouterSettingsViewModel @Inject constructor(
    private val homeSkill: HomeSkill,
    private val routerScanner: RouterScanner
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouterSettingsUiState())
    val uiState: StateFlow<RouterSettingsUiState> = _uiState.asStateFlow()

    init {
        loadCurrentSettings()
    }

    /**
     * Загружает текущие настройки из HomeSkill.
     */
    private fun loadCurrentSettings() {
        viewModelScope.launch {
            try {
                val ssid = routerScanner.getCurrentSsid()
                val gatewayIp = routerScanner.getGatewayIp()
                val savedConfig = homeSkill.getRouterConfig()

                _uiState.update { state ->
                    state.copy(
                        currentSsid = ssid,
                        ip = savedConfig?.ip ?: gatewayIp ?: state.ip,
                        port = (savedConfig?.port ?: 80).toString(),
                        protocol = savedConfig?.let {
                            try { ProtocolType.valueOf(it.protocol) } catch (_: Exception) { ProtocolType.HTTP }
                        } ?: ProtocolType.HTTP,
                        username = savedConfig?.username ?: "",
                        password = savedConfig?.password ?: "",
                        community = savedConfig?.community ?: "public",
                        enabled = savedConfig?.enabled ?: false
                    )
                }
            } catch (e: Exception) {
                println("RouterSettingsVM: load error: ${e.message}")
            }
        }
    }

    /** Переключает enabled (использовать роутер). */
    fun toggleEnabled() {
        _uiState.update { it.copy(enabled = !it.enabled) }
    }

    /** Обновляет IP адрес роутера. */
    fun updateIp(ip: String) {
        _uiState.update { it.copy(ip = ip, saved = false) }
    }

    /** Обновляет порт. */
    fun updatePort(port: String) {
        _uiState.update { it.copy(port = port, saved = false) }
    }

    /** Обновляет протокол. */
    fun updateProtocol(protocol: ProtocolType) {
        val defaultPort = when (protocol) {
            ProtocolType.HTTP -> "80"
            ProtocolType.SSH -> "22"
            ProtocolType.SNMP -> "161"
        }
        _uiState.update {
            it.copy(
                protocol = protocol,
                port = defaultPort,
                saved = false
            )
        }
    }

    /** Обновляет имя пользователя. */
    fun updateUsername(username: String) {
        _uiState.update { it.copy(username = username, saved = false) }
    }

    /** Обновляет пароль. */
    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password, saved = false) }
    }

    /** Обновляет SNMP community. */
    fun updateCommunity(community: String) {
        _uiState.update { it.copy(community = community, saved = false) }
    }

    /** Проверяет соединение с роутером. */
    fun testConnection() {
        val state = _uiState.value
        if (state.loading) return

        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, testResult = null, error = null) }

            try {
                val config = RouterConfig(
                    ip = state.ip,
                    port = state.port.toIntOrNull() ?: 80,
                    username = state.username,
                    password = state.password,
                    community = state.community,
                    protocol = state.protocol,
                    ssid = state.currentSsid ?: ""
                )

                val result = routerScanner.testConnection(config)
                _uiState.update { it.copy(loading = false, testResult = result) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        testResult = TestResult(false, error = "Ошибка: ${e.message}")
                    )
                }
            }
        }
    }

    /** Сохраняет настройки роутера в HomeSkill. */
    fun save() {
        val state = _uiState.value
        if (state.loading) return

        viewModelScope.launch {
            try {
                val config = RouterConfigData(
                    enabled = state.enabled,
                    ip = state.ip,
                    port = state.port.toIntOrNull() ?: 80,
                    username = state.username,
                    password = state.password,
                    community = state.community,
                    protocol = state.protocol.name
                )

                homeSkill.saveRouterConfig(config)
                _uiState.update { it.copy(saved = true, error = null) }
                println("RouterSettingsVM: config saved")
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Ошибка сохранения: ${e.message}") }
            }
        }
    }

    /** Сбрасывает флаг saved. */
    fun clearSaved() {
        _uiState.update { it.copy(saved = false) }
    }

    /** Сбрасывает ошибку. */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
