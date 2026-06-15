package com.pai.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pai.android.data.service.WakeWordDetector
import com.pai.android.agent.AgentResponse
import com.pai.android.agent.DecisionEngine
import com.pai.android.data.model.Message
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.repository.ChatRepository
import com.pai.android.data.repository.MemoryRepository
import com.pai.android.data.repository.VoiceSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject

/**
 * Фоновый сервис для голосового управления.
 *
 * Режимы работы:
 * 1. SLEEP — слушает wake word (Vosk)
 * 2. LISTEN — активное распознавание речи (SpeechRecognizer с Service Context)
 * 3. RESPOND — AI обрабатывает и отвечает (TTS)
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word_service"
        private const val NOTIFICATION_ID = 1003

        const val ACTION_START = "com.pai.android.action.WAKE_WORD_START"
        const val ACTION_STOP = "com.pai.android.action.WAKE_WORD_STOP"
        const val ACTION_CANCEL = "com.pai.android.action.WAKE_WORD_CANCEL"
        const val ACTION_MANUAL_LISTEN = "com.pai.android.action.MANUAL_LISTEN"
    }

    @Inject
    lateinit var wakeWordDetector: WakeWordDetector

    @Inject
    lateinit var decisionEngine: DecisionEngine

    @Inject
    lateinit var aiRepository: AiRepository

    @Inject
    lateinit var voiceSettingsRepository: VoiceSettingsRepository

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var memoryRepository: MemoryRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    // SpeechRecognizer создаётся с Service Context (не ApplicationContext!)
    private var speechRecognizer: SpeechRecognizer? = null

    // ======================= Lifecycle =======================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initTts()
        Log.i(TAG, "WakeWordService created")
    }

    /**
     * Отменяет текущую обработку: останавливает TTS, прерывает AI, возвращает к wake word.
     */
    private var currentProcessJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWakeWordDetection()
            ACTION_STOP -> stopSelf()
            ACTION_CANCEL -> cancelCurrentOperation()
            ACTION_MANUAL_LISTEN -> startVoiceRecognition()
        }
        return START_STICKY
    }

    private fun cancelCurrentOperation() {
        Log.i(TAG, "⏹️ Cancel requested")
        stopSpeaking()
        currentProcessJob?.cancel()
        currentProcessJob = null
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        updateNotification("⏹️ Прервано")
        scope.launch {
            delay(500)
            resumeWakeWord()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopWakeWordDetection()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.shutdown()
        scope.cancel()
        Log.i(TAG, "WakeWordService destroyed")
        super.onDestroy()
    }

    // ======================= Wake Word Detection =======================

    private fun startWakeWordDetection() {
        startForeground(NOTIFICATION_ID, buildNotification("🎤 PAI слушает..."))

        scope.launch {
            // Загружаем сохранённое ключевое слово
            try {
                val savedSettings = voiceSettingsRepository.settings.first()
                wakeWordDetector.wakeWord = savedSettings.wakeWord
                Log.i(TAG, "Wake word loaded from settings: '${savedSettings.wakeWord}'")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load wake word settings", e)
            }

            // Быстрая проверка: модель уже скачана?
            val modelDir = java.io.File(filesDir, "vosk_models/vosk-model-small-ru-0.22")
            val modelExists = modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true

            if (!modelExists) {
                updateNotification("⏳ Загрузка модели...")
            }

            val ready = wakeWordDetector.initialize()
            if (!ready) {
                updateNotification("❌ Модель не найдена")
                Log.e(TAG, "Vosk model not available. Download failed.")
                return@launch
            }

            wakeWordDetector.onWakeWordDetected = {
                Log.i(TAG, "🎤 Wake word detected!")
                // Прерываем TTS если говорит
                stopSpeaking()
                updateNotification("🎧 Слушаю...")
                startVoiceRecognition()
            }

            wakeWordDetector.onError = { error ->
                Log.w(TAG, "WakeWord error: $error")
            }

            wakeWordDetector.start()
            Log.i(TAG, "Wake word detection started with Vosk")
        }
    }

    private fun stopWakeWordDetection() {
        wakeWordDetector.stop()
        wakeWordDetector.onWakeWordDetected = null
        wakeWordDetector.onError = null
    }

    // ======================= Voice Recognition =======================

    private fun startVoiceRecognition() {
        wakeWordDetector.stop()
        updateNotification("🎧 Распознаю речь...")

        // Ждём 300мс, чтобы микрофон полностью освободился от Vosk
        // SpeechRecognizer обязательно должен работать на main thread!
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            delay(300)
            launchRecognizer()
        }
    }

    private fun launchRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.w(TAG, "SpeechRecognizer not available")
                speakResponse("Голосовое распознавание недоступно.")
                scope.launch { delay(2000); resumeWakeWord() }
                return
            }

            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@WakeWordService)

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "SpeechRecognizer ready")
                    updateNotification("🎧 Слушаю...")
                }
                override fun onBeginningOfSpeech() {
                    updateNotification("🎤 Говорите...")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    updateNotification("⏳ Обрабатываю...")
                }
                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                        SpeechRecognizer.ERROR_NETWORK -> "network"
                        SpeechRecognizer.ERROR_CLIENT -> "client"
                        else -> "code $error"
                    }
                    Log.w(TAG, "SpeechRecognizer error: $msg")
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    speakResponse("Не расслышал. Попробуйте ещё раз.")
                    scope.launch { delay(2000); resumeWakeWord() }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text != null) {
                        Log.i(TAG, "✅ Recognized: $text")
                        updateNotification("💬 $text")
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        currentProcessJob = scope.launch { processWithAI(text) }
                    } else {
                        Log.w(TAG, "Empty recognition result")
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        resumeWakeWord()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите...")
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Voice recognition failed", e)
            speechRecognizer?.destroy()
            speechRecognizer = null
            speakResponse("Произошла ошибка при распознавании.")
            scope.launch { delay(2000); resumeWakeWord() }
        }
    }

    // ======================= AI Processing =======================

    private suspend fun processWithAI(text: String) {
        updateNotification("🤖 Думаю...")
        DecisionEngine.isProcessingVoice = true

        try {
            // Получаем контекст последних сообщений из чата
            val chatContext = buildVoiceContext()

            // Полный ReAct-цикл через DecisionEngine с контекстом чата
            val response = decisionEngine.processQuery(
                query = text,
                context = chatContext,
                autoApprove = true
            )

            when (response) {
                is AgentResponse.Success -> {
                    val rawAnswer = response.answer
                    Log.i(TAG, "🤖 AI raw response (${rawAnswer.length} chars)")

                    // Отправляем полный ответ в чат
                    if (DecisionEngine.lastChatId != null) {
                        DecisionEngine.Companion.pendingNotificationResult = "🎤 **$text**\n\n$rawAnswer"
                    }

                    // Проверяем настройку TTS
                    val currentSettings = voiceSettingsRepository.settings.first()
                    if (currentSettings.ttsEnabled) {
                        val cleanAnswer = cleanForTts(rawAnswer)
                        Log.i(TAG, "🗣️ Clean for TTS: ${cleanAnswer.take(100)}...")
                        speakResponse(cleanAnswer)
                    } else {
                        Log.i(TAG, "🔇 TTS disabled, skipping voice")
                        resumeWakeWord()
                    }
                }
                is AgentResponse.Error -> {
                    Log.w(TAG, "AI error: ${response.error}")
                    speakResponse("Произошла ошибка: ${response.error.take(100)}")
                    delay(2000)
                    resumeWakeWord()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI processing error", e)
            speakResponse("Произошла ошибка. Попробуйте ещё раз.")
            delay(2000)
            resumeWakeWord()
        } finally {
            DecisionEngine.isProcessingVoice = false
        }
    }

    /**
     * Adapts AI response for natural TTS output.
     * LLM rewrites numbers as words, removes code/logs, formats for speech.
     * Prompt is English, output in user's language.
     */
    private suspend fun cleanForTts(rawText: String): String {
        if (rawText.length < 200) return stripFormatting(rawText)

        return try {
            val resp = aiRepository.sendMessage(
                messages = listOf(Message.createUserMessage("tts_clean",
                    """Adapt this AI response for text-to-speech output.

RULES:
- Remove: code blocks, execution logs, improvement suggestions, technical details, duplicates
- Convert ALL numbers to words (25 → "twenty-five", 2026 → "two thousand twenty-six")
- Expand abbreviations: "°C" → "degrees", "%" → "percent", "m/s" → "meters per second", "km/h" → "kilometers per hour"
- Remove emojis, markdown, brackets, special characters — plain text only
- Keep 2-3 short sentences max, one paragraph
- Output ONLY the cleaned text, no explanations

Source text (reply in the SAME LANGUAGE as this source text):
$rawText""".trimIndent())),
                systemPrompt = "You are a TTS text editor. Output ONLY cleaned spoken text. Numbers as words, no symbols, no formatting. Reply in the SAME LANGUAGE as the source text below.",
                memoryContext = ""
            )
            val cleaned = if (resp.isSuccess) resp.getOrThrow().text.trim() else rawText
            stripFormatting(cleaned).ifBlank { stripFormatting(rawText) }
        } catch (e: Exception) {
            Log.w(TAG, "cleanForTts failed: ${e.message}")
            stripFormatting(rawText)
        }
    }

    // ======================= TTS =======================

    private fun initTts() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale("ru"))
                isTtsReady = result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                             result == TextToSpeech.LANG_AVAILABLE
                Log.i(TAG, "TTS initialized: available=$isTtsReady")
            }
        }
    }

    /**
     * Формирует полный контекст для голосового запроса (как в текстовом чате).
     * Включает: последние 5 сообщений, дневную память, факты, суммаризации.
     */
    private suspend fun buildVoiceContext(): String {
        val lastChatId = DecisionEngine.lastChatId ?: return ""
        val contextBuilder = StringBuilder()

        try {
            // 1. Последние 5 сообщений из чата
            val messages = chatRepository.getMessages(lastChatId)
            val recentMessages = messages.takeLast(5)
            if (recentMessages.isNotEmpty()) {
                contextBuilder.appendLine("💬 **Last messages in chat:**")
                recentMessages.forEach { msg ->
                    val role = if (msg.role == com.pai.android.data.model.MessageRole.USER) "👤 User" else "🤖 Assistant"
                    contextBuilder.appendLine("$role: ${msg.content.take(500)}")
                }
                contextBuilder.appendLine()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Context chat history error: ${e.message}")
        }

        try {
            // 2. Поиск в дневной памяти (по последнему запросу)
            val lastMsg = chatRepository.getMessages(lastChatId).lastOrNull()
            val query = lastMsg?.content?.take(100) ?: ""
            val temporalMemory = memoryRepository.searchTemporalMemory(query)
            if (temporalMemory.isNotBlank()) {
                contextBuilder.appendLine("📅 **Daily memory:**")
                contextBuilder.appendLine(temporalMemory)
                contextBuilder.appendLine()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Context temporal memory error: ${e.message}")
        }

        try {
            // 3. Факты из постоянной памяти
            val factsText = memoryRepository.formatFactsForPrompt("")
            if (factsText.isNotBlank()) {
                contextBuilder.appendLine(factsText)
                contextBuilder.appendLine()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Context facts error: ${e.message}")
        }

        return contextBuilder.toString()
    }

    /**
     * Прерывает текущее озвучивание.
     * Вызывается при обнаружении wake word во время TTS.
     */
    private fun stopSpeaking() {
        textToSpeech?.stop()
        Log.d(TAG, "TTS stopped")
    }

    private fun speakResponse(text: String) {
        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready")
            resumeWakeWord()
            return
        }
        val shortText = if (text.length > 2000) text.take(2000) + "..." else text
        updateNotification("🔊 $shortText")

        // TTS начинается — Vosk НЕ слушает (чтобы избежать ложных срабатываний от динамика)
        val utteranceId = "tts_${System.currentTimeMillis()}"

        // Используем слушатель, чтобы узнать когда TTS закончился
        textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS completed, resuming wake word in 500ms")
                // После завершения TTS ждём 500мс и возвращаем Vosk
                scope.launch {
                    delay(500)
                    resumeWakeWord()
                }
            }
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS error")
                scope.launch { resumeWakeWord() }
            }
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started")
            }
        })

        textToSpeech?.speak(shortText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    // ======================= Helpers =======================

    /**
     * Удаляет markdown-разметку и эмодзи для TTS.
     * Оставляет только текст, базовую пунктуацию и пробелы.
     */
    private fun stripFormatting(text: String): String {
        return text
            // Markdown: **bold**, *italic*, __underline__, ~~strikethrough~~, `code`, ```block```
            .replace(Regex("""[*_~`#]{1,3}"""), "")
            // Ссылки [text](url) → text
            .replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
            // URL bare
            .replace(Regex("""https?://\S+"""), "")
            // Всё кроме букв, цифр, пробелов и . , ! ? - : ; '
            .filter { c ->
                c.isLetterOrDigit() || c.isWhitespace() ||
                c in ".!?,-:;'\"()"
            }
            // Множественные пробелы → один
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun resumeWakeWord() {
        updateNotification("🎤 PAI слушает...")
        wakeWordDetector.start()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PAI Voice")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PAI Voice Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновый сервис голосового управления"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        // PendingIntent для кнопки отмены
        val cancelIntent = android.app.PendingIntent.getService(
            this,
            0,
            Intent(this, WakeWordService::class.java).apply { action = ACTION_CANCEL },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PAI Voice")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "⏹ Стоп",
                cancelIntent
            )
            .build()
    }
}
