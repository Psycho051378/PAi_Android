package com.pai.android.data.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Детектор ключевого слова (wake word) на основе Vosk.
 *
 * Преимущества перед Porcupine:
 * - Полностью офлайн — не требует интернета и API-ключей
 * - Работает в РФ без VPN
 * - Русский язык из коробки
 * - Открытый исходный код (Apache 2.0)
 *
 * Минусы:
 * - Чуть выше нагрузка на CPU (но с грамматикой из 1 слова — минимально)
 * - Модель ~42 МБ (загружается один раз при первом запуске)
 */
@Singleton
class WakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000

        // Название и URL модели Vosk (скачивается один раз при первом запуске)
        private const val MODEL_NAME = "vosk-model-small-ru-0.22"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    }

    @Volatile
    private var isRunning = false
    private var detectionJob: Job? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null

    /** Колбэк при обнаружении ключевого слова */
    @Volatile
    var onWakeWordDetected: (() -> Unit)? = null

    /** Колбэк при ошибке */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /** Текущее ключевое слово (на русском) */
    @Volatile
    var wakeWord: String = "компьютер"

    /** Флаг готовности модели */
    @Volatile
    var isModelReady: Boolean = false
        private set

    /**
     * Инициализирует Vosk и загружает модель (если ещё не загружена).
     * Вызывать один раз перед start().
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Скачиваем модель Vosk (один раз, ~42 МБ)
            val modelDir = downloadModel()
            if (modelDir == null) {
                Log.e(TAG, "Failed to copy Vosk model from assets")
                return@withContext false
            }

            Log.i(TAG, "Vosk initialized, model at: $modelDir.absolutePath")
            isModelReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vosk initialization error", e)
            onError?.invoke("Ошибка инициализации Vosk: ${e.message}")
            false
        }
    }

    /**
     * Запускает прослушивание микрофона с детекцией wake word.
     * Модель Vosk должна быть инициализирована (initialize()).
     */
    fun start() {
        if (!isModelReady) {
            Log.w(TAG, "Vosk model not ready, call initialize() first")
            return
        }
        if (isRunning) return
        isRunning = true

        detectionJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                startVoskRecognition()
            } catch (e: Exception) {
                Log.e(TAG, "Wake word detection failed", e)
                onError?.invoke("Ошибка детекции: ${e.message}")
                isRunning = false
            }
        }
    }

    /**
     * Останавливает прослушивание.
     */
    fun stop() {
        isRunning = false
        detectionJob?.cancel()
        detectionJob = null
        releaseAudioCapture()
        releaseRecognizer()
    }

    /**
     * Меняет ключевое слово и перезапускает распознавание.
     * Вызывается из настроек при смене wake word.
     */
    fun restartWithWakeWord(newWord: String) {
        wakeWord = newWord
        if (isRunning) {
            stop()
            start()
        }
    }

    /**
     * Активен ли детектор.
     */
    fun isActive(): Boolean = isRunning

    override fun toString(): String = "WakeWordDetector(wakeWord='$wakeWord', running=$isRunning, modelReady=$isModelReady)"

    // ======================= Vosk Recognition =======================

    private suspend fun startVoskRecognition() {
        val modelDir = getModelDirectory() ?: throw IllegalStateException("Model directory not found")

        // Создаём модель Vosk из директории
        val voskModel = Model(modelDir.absolutePath)

        // Создаём Recognizer с грамматикой из одного слова (ускоряет детекцию)
        val grammar = org.json.JSONArray().apply {
            put(wakeWord.lowercase())
        }.toString()
        recognizer = Recognizer(voskModel, 16000.0f, grammar)

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize.coerceAtLeast(4096)
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        audioRecord?.startRecording()
        Log.i(TAG, "🎤 Vosk listening for wake word '$wakeWord'...")

        val buffer = ByteArray(bufferSize.coerceAtLeast(4096))
        val r = recognizer ?: return

        while (isRunning) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (bytesRead > 0) {
                if (r.acceptWaveForm(buffer, bytesRead)) {
                    val result = r.result
                    Log.d(TAG, "Vosk result: $result")

                    // Парсим JSON: {"text": "компьютер"}
                    if (result.lowercase().contains("\"text\"") && 
                        result.lowercase().contains(wakeWord.lowercase())) {
                        Log.i(TAG, "🎤 Wake word '$wakeWord' detected!")
                        onWakeWordDetected?.invoke()
                    }
                }
            } else if (bytesRead < 0) {
                Log.w(TAG, "AudioRecord read error: $bytesRead")
                delay(100)
            }
        }
    }

    // ======================= Model Management =======================

    /**
     * Скачивает модель Vosk из интернета (один раз при первом запуске).
     * ~42 МБ, распаковывается во внутреннее хранилище.
     */
    private suspend fun downloadModel(): File? = withContext(Dispatchers.IO) {
        val modelDir = getModelDirectory() ?: return@withContext null

        // Если модель уже есть — пропускаем
        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            Log.i(TAG, "Model already exists at: $modelDir")
            return@withContext modelDir
        }

        Log.i(TAG, "Downloading Vosk model '$MODEL_NAME' (~42 MB)...")

        try {
            modelDir.parentFile?.mkdirs()
            val zipFile = File(modelDir.parentFile, "$MODEL_NAME.zip")

            // Скачиваем через HttpURLConnection
            val url = java.net.URL(MODEL_URL)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.connect()

            if (connection.responseCode != 200) {
                Log.e(TAG, "Download failed: HTTP ${connection.responseCode}")
                return@withContext null
            }

            // Сохраняем во временный файл
            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    input.copyTo(output)
                }
            }
            connection.disconnect()

            Log.i(TAG, "Download complete, extracting...")

            // Распаковываем zip
            unzip(zipFile, modelDir.parentFile!!)
            zipFile.delete()

            Log.i(TAG, "Model installed successfully at: $modelDir")
            modelDir
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            // Удаляем мусор
            try {
                val zipFile = File(modelDir.parentFile, "$MODEL_NAME.zip")
                if (zipFile.exists()) zipFile.delete()
            } catch (_: Exception) {}
            null
        }
    }

    private fun getModelDirectory(): File? {
        return try {
            val dir = File(context.filesDir, "vosk_models/$MODEL_NAME")
            dir.parentFile?.mkdirs()
            dir
        } catch (e: Exception) {
            Log.e(TAG, "Error getting model directory", e)
            null
        }
    }

    private fun copyAssetDirectory(assetPath: String, outputDir: File) {
        val assets = context.assets
        val files = assets.list(assetPath) ?: return
        outputDir.mkdirs()

        for (file in files) {
            val subAssetPath = "$assetPath/$file"
            val subOutput = File(outputDir, file)

            if (assets.list(subAssetPath)?.isNotEmpty() == true) {
                copyAssetDirectory(subAssetPath, subOutput)
            } else {
                assets.open(subAssetPath).use { input ->
                    FileOutputStream(subOutput).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun unzip(zipFile: File, outputDir: File) {
        try {
            val process = ProcessBuilder(
                "unzip", "-o", zipFile.absolutePath, "-d", outputDir.absolutePath
            ).redirectErrorStream(true).start()
            process.waitFor()
        } catch (e: Exception) {
            // Fallback: Java ZipInputStream
            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val targetFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { output ->
                            zis.copyTo(output)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    // ======================= Resource Management =======================

    private fun releaseRecognizer() {
        try {
            recognizer?.close()
            recognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recognizer", e)
        }
    }

    private fun releaseAudioCapture() {
        try {
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
            }
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
    }
}
