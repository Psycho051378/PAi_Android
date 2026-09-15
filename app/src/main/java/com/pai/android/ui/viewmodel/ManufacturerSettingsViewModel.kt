package com.pai.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.local.SmartHomeDao
import com.pai.android.data.model.DeviceProtocol
import com.pai.android.data.model.ManufacturerAuth
import com.pai.android.data.model.SmartHomeDevice
import com.pai.android.data.repository.ManufacturerRepository
import com.pai.android.data.repository.SmartHomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

data class MiioDeviceEntry(
    val device: SmartHomeDevice,
    val token: String = ""
)

data class TpLinkCredentials(
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = false
)

data class ManufacturerUiState(
    val devices: List<MiioDeviceEntry> = emptyList(),
    val tpLink: TpLinkCredentials = TpLinkCredentials()
)

@HiltViewModel
class ManufacturerSettingsViewModel @Inject constructor(
    private val smartHomeRepository: SmartHomeRepository,
    private val smartHomeDao: SmartHomeDao,
    private val manufacturerRepository: ManufacturerRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ManufacturerUiState())
    val state: StateFlow<ManufacturerUiState> = _state.asStateFlow()

    init {
        loadDevices()
        loadTpLink()
    }

    fun loadDevices() {
        viewModelScope.launch {
            try {
                val allNets = smartHomeRepository.getAllNetworks()
                if (allNets.isEmpty()) {
                    _state.value = _state.value.copy(devices = emptyList())
                    return@launch
                }

                val miioDevices = allNets.flatMap { net ->
                    smartHomeRepository.getDevices(net.id)
                        .filter { it.protocol == DeviceProtocol.MIIO.name || it.protocol == DeviceProtocol.ROBOROCK.name }
                }

                val entries = miioDevices.map { device ->
                    val token = try {
                        JSONObject(device.deviceConfig).optString("miio_token", "")
                    } catch (e: Exception) { "" }
                    MiioDeviceEntry(device = device, token = token)
                }

                _state.value = _state.value.copy(devices = entries)
            } catch (e: Exception) {
                println("ManufacturerSettingsVM: loadDevices error: ${e.message}")
            }
        }
    }

    /** Загрузить настройки TP-Link из ManufacturerRepository. */
    fun loadTpLink() {
        viewModelScope.launch {
            try {
                val auth = manufacturerRepository.get("tplink")
                val current = _state.value
                if (auth != null) {
                    val creds = try {
                        JSONObject(auth.credentials)
                    } catch (e: Exception) { JSONObject() }
                    _state.value = current.copy(
                        tpLink = TpLinkCredentials(
                            username = creds.optString("username", ""),
                            password = creds.optString("password", ""),
                            enabled = auth.enabled
                        )
                    )
                } else {
                    _state.value = current.copy(tpLink = TpLinkCredentials())
                }
            } catch (e: Exception) {
                println("ManufacturerSettingsVM: loadTpLink error: ${e.message}")
            }
        }
    }

    /** Обновить логин TP-Link (в UI). */
    fun updateTpLinkUsername(username: String) {
        _state.value = _state.value.copy(
            tpLink = _state.value.tpLink.copy(username = username)
        )
    }

    /** Обновить пароль TP-Link (в UI). */
    fun updateTpLinkPassword(password: String) {
        _state.value = _state.value.copy(
            tpLink = _state.value.tpLink.copy(password = password)
        )
    }

    /** Сохранить настройки TP-Link. */
    fun saveTpLink() {
        viewModelScope.launch {
            val tp = _state.value.tpLink
            val creds = JSONObject().apply {
                put("username", tp.username)
                put("password", tp.password)
            }.toString()

            val auth = ManufacturerAuth(
                manufacturer = "tplink",
                displayName = "TP-Link Kasa/Tapo",
                authType = "login_password",
                credentials = creds,
                enabled = tp.username.isNotBlank(),
                lastSynced = System.currentTimeMillis()
            )
            manufacturerRepository.save(auth)
        }
    }

    /** Очистить настройки TP-Link. */
    fun clearTpLink() {
        viewModelScope.launch {
            manufacturerRepository.delete("tplink")
            _state.value = _state.value.copy(tpLink = TpLinkCredentials())
        }
    }

    fun updateToken(deviceId: String, token: String) {
        val updated = _state.value.devices.map {
            if (it.device.id == deviceId) it.copy(token = token) else it
        }
        _state.value = _state.value.copy(devices = updated)
    }

    fun saveToken(deviceId: String) {
        viewModelScope.launch {
            val entry = _state.value.devices.find { it.device.id == deviceId } ?: return@launch
            val device = entry.device

            val config = try {
                JSONObject(device.deviceConfig)
            } catch (e: Exception) { JSONObject() }

            config.put("miio_token", entry.token)
            val updated = device.copy(deviceConfig = config.toString())
            smartHomeDao.upsertDevice(updated)
            loadDevices()
        }
    }

    fun clearToken(deviceId: String) {
        viewModelScope.launch {
            val device = _state.value.devices.find { it.device.id == deviceId }?.device ?: return@launch
            val config = try {
                JSONObject(device.deviceConfig)
            } catch (e: Exception) { JSONObject() }

            config.remove("miio_token")
            val updated = device.copy(deviceConfig = config.toString())
            smartHomeDao.upsertDevice(updated)
            loadDevices()
        }
    }
}
