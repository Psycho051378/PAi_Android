package com.pai.android.presentation.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.service.VoiceRecognitionService
import com.pai.android.data.service.RecognitionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel для управления голосовым вводом.
 * Обрабатывает состояние распознавания, ошибки и результаты.
 */
@HiltViewModel
class VoiceRecognitionViewModel @Inject constructor(
    private val voiceRecognitionService: VoiceRecognitionService
) : ViewModel() {
    
    private val _state = MutableStateFlow(VoiceRecognitionState())
    val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()
    
    /**
     * Начинает прослушивание микрофона и распознавание речи.
     */
    fun startListening() {
        if (_state.value.isListening) return
        
        _state.update { it.copy(isListening = true, error = null) }
        
        viewModelScope.launch {
            voiceRecognitionService.startListening()
                .onEach { result ->
                    when (result) {
                        is RecognitionResult.Ready -> {
                            _state.update { it.copy(status = "Готов к приёму речи...") }
                        }
                        is RecognitionResult.SpeechStarted -> {
                            _state.update { it.copy(status = "Речь обнаружена, распознаю...") }
                        }
                        is RecognitionResult.RmsChanged -> {
                            // Можно обновлять индикатор громкости
                            _state.update { it.copy(rmsValue = result.rmsdB) }
                        }
                        is RecognitionResult.SpeechEnded -> {
                            _state.update { it.copy(status = "Речь завершена, обрабатываю...") }
                        }
                        is RecognitionResult.PartialResult -> {
                            _state.update { it.copy(
                                partialText = result.text,
                                status = "Распознаю..."
                            ) }
                        }
                        is RecognitionResult.Success -> {
                            _state.update { it.copy(
                                recognizedText = result.text,
                                partialText = "",
                                isListening = false,
                                status = "Распознано успешно"
                            ) }
                        }
                        is RecognitionResult.Error -> {
                            _state.update { it.copy(
                                error = result.message,
                                isListening = false,
                                status = "Ошибка"
                            ) }
                        }
                    }
                }
                .catch { error ->
                    _state.update { it.copy(
                        error = "Ошибка распознавания: ${error.message}",
                        isListening = false,
                        status = "Ошибка"
                    ) }
                }
                .launchIn(this)
        }
    }
    
    /**
     * Останавливает прослушивание.
     */
    fun stopListening() {
        if (_state.value.isListening) {
            voiceRecognitionService.stopListening()
            _state.update { it.copy(isListening = false, status = "Остановлено") }
        }
    }
    
    /**
     * Отменяет текущее распознавание.
     */
    fun cancelListening() {
        voiceRecognitionService.cancelListening()
        _state.update { it.copy(
            isListening = false,
            partialText = "",
            recognizedText = "",
            status = "Отменено"
        ) }
    }
    
    /**
     * Очищает распознанный текст и ошибки.
     */
    fun clearResult() {
        _state.update { it.copy(
            recognizedText = "",
            partialText = "",
            error = null,
            status = "Готов"
        ) }
    }
    
    /**
     * Устанавливает распознанный текст (например, при вставке из другого источника).
     */
    fun setRecognizedText(text: String) {
        _state.update { it.copy(recognizedText = text) }
    }
    
    override fun onCleared() {
        super.onCleared()
        voiceRecognitionService.destroy()
    }
}

/**
 * Состояние голосового распознавания.
 */
data class VoiceRecognitionState(
    val isListening: Boolean = false,
    val recognizedText: String = "",
    val partialText: String = "",
    val error: String? = null,
    val status: String = "Готов",
    val rmsValue: Float = 0f
) {
    /** Текущий отображаемый текст (частичный или финальный) */
    val displayText: String
        get() = partialText.ifEmpty { recognizedText }
    
    /** Идёт ли процесс распознавания */
    val isProcessing: Boolean
        get() = isListening || partialText.isNotEmpty()
}