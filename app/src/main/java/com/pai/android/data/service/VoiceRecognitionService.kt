package com.pai.android.data.service

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сервис для распознавания речи через Android SpeechRecognizer API.
 * Поддерживает русский язык и возвращает результаты через Flow.
 */
@Singleton
class VoiceRecognitionService @Inject constructor(
    private val context: Context
) {
    private var speechRecognizer: SpeechRecognizer? = null
    
    /**
     * Запускает распознавание речи и возвращает Flow с результатами.
     * @param language Язык распознавания (по умолчанию русский)
     * @return Flow с результатами распознавания или ошибками
     */
    fun startListening(language: String = "ru-RU"): Flow<RecognitionResult> = callbackFlow {
        // Проверяем доступность SpeechRecognizer
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(RecognitionResult.Error("Распознавание речи недоступно на этом устройстве"))
            close()
            return@callbackFlow
        }
        
        // Создаём SpeechRecognizer
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    trySend(RecognitionResult.Ready)
                }
                
                override fun onBeginningOfSpeech() {
                    trySend(RecognitionResult.SpeechStarted)
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    trySend(RecognitionResult.RmsChanged(rmsdB))
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {
                    // Не используется
                }
                
                override fun onEndOfSpeech() {
                    trySend(RecognitionResult.SpeechEnded)
                }
                
                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Ошибка аудио"
                        SpeechRecognizer.ERROR_CLIENT -> "Ошибка клиента"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Недостаточно разрешений"
                        SpeechRecognizer.ERROR_NETWORK -> "Ошибка сети"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Таймаут сети"
                        SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят"
                        SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Таймаут речи"
                        else -> "Неизвестная ошибка: $error"
                    }
                    trySend(RecognitionResult.Error(errorMessage))
                    close()
                }
                
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val bestMatch = matches?.firstOrNull()
                    if (bestMatch != null) {
                        trySend(RecognitionResult.Success(bestMatch))
                    } else {
                        trySend(RecognitionResult.Error("Речь не распознана"))
                    }
                    close()
                }
                
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialMatch = matches?.firstOrNull()
                    if (partialMatch != null) {
                        trySend(RecognitionResult.PartialResult(partialMatch))
                    }
                }
                
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {
                    // Не используется
                }
            })
        }
        
        // Настраиваем Intent для распознавания
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите...")
        }
        
        // Запускаем распознавание
        val recognizer = speechRecognizer
        if (recognizer == null) {
            trySend(RecognitionResult.Error("Распознаватель речи не создан"))
            close()
            return@callbackFlow
        }
        recognizer.startListening(intent)
        
        // Очистка при завершении Flow
        awaitClose {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }
    
    /**
     * Останавливает текущее распознавание.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
    }
    
    /**
     * Отменяет текущее распознавание.
     */
    fun cancelListening() {
        speechRecognizer?.cancel()
    }
    
    /**
     * Освобождает ресурсы SpeechRecognizer.
     */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}

/**
 * Результаты распознавания речи.
 */
sealed class RecognitionResult {
    /** Распознаватель готов к приёму речи */
    object Ready : RecognitionResult()
    
    /** Начало речи обнаружено */
    object SpeechStarted : RecognitionResult()
    
    /** Изменение уровня громкости (в dB) */
    data class RmsChanged(val rmsdB: Float) : RecognitionResult()
    
    /** Речь завершилась */
    object SpeechEnded : RecognitionResult()
    
    /** Частичный результат распознавания */
    data class PartialResult(val text: String) : RecognitionResult()
    
    /** Финальный результат распознавания */
    data class Success(val text: String) : RecognitionResult()
    
    /** Ошибка распознавания */
    data class Error(val message: String) : RecognitionResult()
}