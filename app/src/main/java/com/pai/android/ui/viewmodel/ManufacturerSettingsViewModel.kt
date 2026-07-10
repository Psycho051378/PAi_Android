package com.pai.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.local.SmartHomeDao
import com.pai.android.data.model.DeviceProtocol
import com.pai.android.data.model.SmartHomeDevice
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

data class ManufacturerUiState(
    val devices: List<MiioDeviceEntry> = emptyList()
)

@HiltViewModel
class ManufacturerSettingsViewModel @Inject constructor(
    private val smartHomeRepository: SmartHomeRepository,
    private val smartHomeDao: SmartHomeDao
) : ViewModel() {

    private val _state = MutableStateFlow(ManufacturerUiState())
    val state: StateFlow<ManufacturerUiState> = _state.asStateFlow()

    init {
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            try {
                val allNets = smartHomeRepository.getAllNetworks()
                if (allNets.isEmpty()) {
                    _state.value = ManufacturerUiState(emptyList())
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

                _state.value = ManufacturerUiState(devices = entries)
            } catch (e: Exception) {
                println("ManufacturerSettingsVM: loadDevices error: ${e.message}")
            }
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
