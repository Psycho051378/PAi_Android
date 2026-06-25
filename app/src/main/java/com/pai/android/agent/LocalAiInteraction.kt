package com.pai.android.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SessionConfig
import com.pai.android.data.local.model.ModelManager
import com.pai.android.data.model.Attachment
import com.pai.android.data.model.AttachmentType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ядро инференса для локальных моделей LiteRT LM.
 *
 * Поддерживает два режима:
 * - Conversation (Gemma 4 с tf_lite_text_decoder)
 * - Session.generateContent (Qwen3 и другие c TF_LITE_PREFILL_DECODE)
 *
 * Мультимодальный режим: передача изображений через Content.ImageBytes/ImageFile.
 */
@Singleton
class LocalAiInteraction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG = "LocalAiInteraction"
        private const val DEFAULT_MAX_TOKENS = 1024
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95
    }

    private var engine: Engine? = null
    private var loadedModelId: String? = null
    private val modelLock = Mutex()

    /** Загружает скачанную модель LiteRT LM. */
    suspend fun loadModel(modelId: String, useGpuBackend: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (loadedModelId == modelId && engine?.isInitialized() == true) {
                return@withContext Result.success(Unit)
            }

            val modelFile = modelManager.getModelPath(modelId)
                ?: return@withContext Result.failure(
                    IllegalStateException("Модель $modelId не скачана. Скачайте её через настройки провайдера.")
                )

            Log.i(TAG, "Загрузка модели $modelId из ${modelFile.absolutePath}")

            modelLock.withLock {
                engine?.close()
                engine = null
                loadedModelId = null

                val isQwen = modelId.startsWith("qwen3")
                val useBackend = if (useGpuBackend) Backend.GPU() else Backend.CPU()
                Log.i(TAG, "Бекенд для $modelId: ${if (useGpuBackend) "GPU" else "CPU"}")

                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = useBackend,
                    visionBackend = useBackend,
                    audioBackend = Backend.CPU(),
                    maxNumTokens = null,
                    maxNumImages = null,
                    cacheDir = null
                )

                // Qwen3 — чистые текстовые модели, без vision и audio секций
                if (isQwen) {
                    try {
                        val visionField = EngineConfig::class.java.getDeclaredField("visionBackend")
                        visionField.isAccessible = true
                        visionField.set(config, null)
                        Log.i(TAG, "visionBackend обнулён для $modelId (нет vision-секций)")

                        val audioField = EngineConfig::class.java.getDeclaredField("audioBackend")
                        audioField.isAccessible = true
                        audioField.set(config, null)
                        Log.i(TAG, "audioBackend обнулён для $modelId (нет аудио-секций)")
                    } catch (re: Exception) {
                        Log.w(TAG, "Не удалось обнулить backend\'ы через рефлексию", re)
                    }
                }

                val newEngine = Engine(config)
                newEngine.initialize()
                engine = newEngine
                loadedModelId = modelId

                Log.i(TAG, "Модель $modelId успешно загружена")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки модели $modelId", e)
            modelLock.withLock {
                engine?.close()
                engine = null
                loadedModelId = null
            }
            Result.failure(e)
        }
    }

    /** Генерирует ответ. */
    suspend fun generate(
        prompt: String,
        systemPrompt: String? = null,
        attachments: List<Attachment> = emptyList(),
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Double = DEFAULT_TEMPERATURE,
        thinkingModeEnabled: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val currentEngine = engine
            ?: return@withContext Result.failure(
                IllegalStateException("Модель не загружена. Вызовите loadModel() сначала.")
            )

        try {
            val isQwen = loadedModelId?.startsWith("qwen3") == true

            val responseText = if (isQwen) {
                val formattedPrompt = formatPrompt(prompt, systemPrompt, loadedModelId, thinkingModeEnabled)
                generateWithSession(currentEngine, formattedPrompt, temperature, maxTokens, thinkingModeEnabled)
            } else {
                // Gemma: используем Conversation.sendMessage
                val imageAttachments = attachments.filter { it.type == AttachmentType.IMAGE }
                val formattedPrompt = formatPrompt(prompt, systemPrompt, loadedModelId)
                val hasImages = imageAttachments.isNotEmpty()

                if (hasImages) {
                    generateWithConversationMultimodal(
                        currentEngine, formattedPrompt, imageAttachments, temperature, maxTokens
                    )
                } else {
                    generateWithConversation(currentEngine, formattedPrompt, temperature, maxTokens)
                }
            }

            Result.success(responseText.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка генерации", e)
            Result.failure(e)
        }
    }

    // ─── Text-only ─────────────────────────────────────────────

    private fun generateWithConversation(
        engine: Engine,
        prompt: String,
        temperature: Double,
        maxTokens: Int
    ): String {
        val samplerConfig = SamplerConfig(
            topK = DEFAULT_TOP_K,
            topP = DEFAULT_TOP_P,
            temperature = temperature,
            seed = -1
        )
        val convConfig = ConversationConfig(
            samplerConfig = samplerConfig
        )
        engine.createConversation(convConfig).use { conversation ->
            val responseMessage = conversation.sendMessage(prompt)
            return responseMessage.contents
                .contents
                .filterIsInstance<Content.Text>()
                .firstOrNull()
                ?.text ?: ""
        }
    }

    // ─── Multimodal ────────────────────────────────────────────

    private fun generateWithConversationMultimodal(
        engine: Engine,
        prompt: String,
        imageAttachments: List<Attachment>,
        temperature: Double,
        maxTokens: Int
    ): String {
        val samplerConfig = SamplerConfig(
            topK = DEFAULT_TOP_K,
            topP = DEFAULT_TOP_P,
            temperature = temperature,
            seed = -1
        )
        val convConfig = ConversationConfig(
            samplerConfig = samplerConfig
        )
        engine.createConversation(convConfig).use { conversation ->
            val contents = mutableListOf<Content>()

            // Текст (уже отформатирован formatGemmaPrompt)
            contents.add(Content.Text(prompt))

            // Изображения
            val imageContents = imageAttachments.mapNotNull { it.toImageContent() }
            contents.addAll(imageContents)

            println("📸 Мультимодальный запрос: текст + ${imageContents.size} изображений")

            val requestMessage = Message.of(*contents.toTypedArray())
            val responseMessage = conversation.sendMessage(requestMessage)
            return responseMessage.contents
                .contents
                .filterIsInstance<Content.Text>()
                .firstOrNull()
                ?.text ?: ""
        }
    }

    /** Конвертирует Attachment в Content.ImageBytes или Content.ImageFile. */
    private fun Attachment.toImageContent(): Content? {
        return when {
            contentBase64 != null -> {
                try {
                    val bytes = Base64.decode(contentBase64, Base64.DEFAULT)
                    Content.ImageBytes(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка декодирования base64 для ${fileName}", e)
                    null
                }
            }
            localPath != null -> {
                Content.ImageFile(localPath)
            }
            else -> {
                Log.w(TAG, "Нет данных для изображения ${fileName}")
                null
            }
        }
    }

    // ─── Session (Qwen) ────────────────────────────────────────

    private fun generateWithSession(
        engine: Engine,
        prompt: String,
        temperature: Double,
        maxTokens: Int,
        thinkingModeEnabled: Boolean = false
    ): String {
        val samplerConfig = SamplerConfig(
            topK = DEFAULT_TOP_K,
            topP = DEFAULT_TOP_P,
            temperature = temperature,
            seed = -1
        )
        val sessionCfg = SessionConfig(samplerConfig)
        engine.createSession(sessionCfg).use { session ->
            val input = listOf(InputData.Text(prompt))
            val raw = session.generateContent(input)
            return if (!thinkingModeEnabled) {
                // Если thinking выключен — отфильтровываем <think> блоки (на случай если модель проигнорировала инструкцию)
                raw.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
            } else {
                raw.trim()
            }
        }
    }

    // ─── Prompt Formatting ─────────────────────────────────────

    private fun formatPrompt(prompt: String, systemPrompt: String?, modelId: String?, thinkingModeEnabled: Boolean = false): String {
        val isQwen = modelId?.startsWith("qwen3") == true
        return if (isQwen) formatQwenPrompt(prompt, systemPrompt, thinkingModeEnabled)
        else formatGemmaPrompt(prompt, systemPrompt)
    }

    private fun formatGemmaPrompt(prompt: String, systemPrompt: String?): String = buildString {
        if (!systemPrompt.isNullOrBlank()) {
            append("<start_of_turn>system\n"); append(systemPrompt.trim()); append("\n<end_of_turn>\n")
        }
        append("<start_of_turn>user\n"); append(prompt.trim()); append("\n<end_of_turn>\n<start_of_turn>model\n")
    }

    private fun formatQwenPrompt(prompt: String, systemPrompt: String?, thinkingModeEnabled: Boolean = false): String = buildString {
        // Если thinking выключен — подавляем <think> блоки, иначе разрешаем модели думать вслух
        append("<|im_start|>system\n")
        if (!systemPrompt.isNullOrBlank()) {
            append(systemPrompt.trim()); append("\n")
        }
        if (!thinkingModeEnabled) {
            append("Don't use <think> tags. Answer directly without reasoning.\n")
        }
        append("<|im_end|>\n")
        append("<|im_start|>user\n"); append(prompt.trim()); append("\n<|im_end|>\n<|im_start|>assistant\n")
    }

    fun isLoaded(): Boolean = engine?.isInitialized() == true
    fun getLoadedModelId(): String? = loadedModelId

    suspend fun unloadModel() {
        modelLock.withLock {
            try { engine?.close() } catch (e: Exception) { Log.w(TAG, "Ошибка выгрузки", e) }
            finally { engine = null; loadedModelId = null }
        }
        Log.i(TAG, "Модель выгружена")
    }
}
