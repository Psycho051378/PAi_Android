package com.pai.android.presentation.voice

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.repository.VoiceSettingsRepository
import com.pai.android.data.service.WakeWordDetector
import com.pai.android.service.WakeWordService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceSettingsState(
    val enabled: Boolean = false,
    val wakeWord: String = "компьютер",
    val ttsEnabled: Boolean = true,
    val isServiceRunning: Boolean = false,
    val isDownloading: Boolean = false,
    val statusMessage: String = ""
)

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    private val application: Application,
    private val settingsRepository: VoiceSettingsRepository,
    private val wakeWordDetector: WakeWordDetector
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(VoiceSettingsState())
    val state: StateFlow<VoiceSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val wasEnabled = _state.value.enabled
                _state.update {
                    it.copy(
                        enabled = settings.enabled,
                        wakeWord = settings.wakeWord,
                        ttsEnabled = settings.ttsEnabled
                    )
                }
                // Автостарт при загрузке настроек (после рестарта приложения)
                if (settings.enabled && !wasEnabled && !_state.value.isServiceRunning) {
                    startService()
                }
            }
        }

        // Периодическая проверка состояния детектора
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3000)
                val detectorActive = wakeWordDetector.isActive()
                _state.update { it.copy(isServiceRunning = detectorActive) }
            }
        }
    }

    /** Включить/выключить голосовое управление */
    fun toggleEnabled() {
        val newEnabled = !_state.value.enabled
        viewModelScope.launch {
            settingsRepository.setEnabled(newEnabled)
            if (newEnabled) {
                startService()
            } else {
                stopService()
            }
        }
    }

    /** Включить/выключить TTS */
    fun toggleTts() {
        val newValue = !_state.value.ttsEnabled
        viewModelScope.launch {
            settingsRepository.setTtsEnabled(newValue)
            _state.update { it.copy(
                ttsEnabled = newValue,
                statusMessage = if (newValue) "🔊 TTS включён" else "🔇 TTS выключен"
            ) }
        }
    }

    /** Изменить ключевое слово */
    fun setWakeWord(word: String) {
        if (word.isBlank()) return
        viewModelScope.launch {
            settingsRepository.setWakeWord(word.lowercase())
            // Перезапускаем Vosk с новым словом (меняет грамматику в Recognizer)
            wakeWordDetector.restartWithWakeWord(word.lowercase())
            _state.update { it.copy(statusMessage = "Ключевое слово: \"$word\"") }
        }
    }

    /** Очистить статус */
    fun clearStatus() {
        _state.update { it.copy(statusMessage = "") }
    }

    private fun startService() {
        val intent = Intent(application, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        try {
            application.startForegroundService(intent)
            _state.update { it.copy(
                isServiceRunning = true,
                statusMessage = "🎤 Сервис запущен"
            ) }
        } catch (e: Exception) {
            _state.update { it.copy(
                statusMessage = "❌ Ошибка: ${e.message}"
            ) }
        }
    }

    private fun stopService() {
        val intent = Intent(application, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_STOP
        }
        application.startService(intent)
        _state.update { it.copy(
            isServiceRunning = false,
            statusMessage = "⏹ Сервис остановлен"
        ) }
    }

    override fun onCleared() {
        super.onCleared()
        // Не останавливаем сервис при закрытии экрана
    }
}
