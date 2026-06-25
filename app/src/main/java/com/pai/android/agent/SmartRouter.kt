package com.pai.android.agent

import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.Attachment
import com.pai.android.data.model.SmartRouterConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат роутинга — решение, куда направить запрос.
 */
sealed class RouteDecision {
    /** Отправить на локальную модель (LiteRT). */
    data class Local(val prompt: String) : RouteDecision()

    /** Отправить на сетевую модель. */
    data class Network(val providerSettingsId: String) : RouteDecision()

    /** Сеть недоступна → фолбэк на локалку. */
    data class Fallback(val prompt: String, val reason: String) : RouteDecision()

    /** Гибрид: основная задача на сети, простые подшаги делегируются локалке. */
    data class Hybrid(val prompt: String) : RouteDecision()
}

/**
 * Smart Router — распределяет запросы между локальной и сетевой моделью.
 *
 * Два слоя принятия решения:
 * 1. Быстрые правила (детерминированно, 0 токенов)
 * 2. LLM-классификатор на локалке (~100-150 токенов)
 */
@Singleton
class SmartRouter @Inject constructor(
    private val localAiInteraction: LocalAiInteraction
) {
    companion object {
        private const val TAG = "SmartRouter"

        // Ключевые слова — триггеры отправки в сеть
        private val NETWORK_TRIGGERS = setOf(
            // Русский
            "анализ", "анализируй", "проанализируй",
            "отчёт", "отчет", "сгенерируй отчёт", "сгенерируй отчет",
            "резюме", "сравни", "сравнение",
            "конфликт", "прогноз", "прогнозируй",
            "статья", "эссе", "сочинение",
            "код", "напиши код", "напиши программу",
            "исследование", "исследуй",
            "многошаговый", "многошаговое",
            "план", "распиши план",
            // Английский
            "analyze", "analysis", "report",
            "compare", "comparison", "summarize",
            "conflict", "forecast", "predict",
            "essay", "article",
            "write code", "implement",
            "research", "investigate",
            "multi-step", "multistep"
        )

        private const val SHORT_PROMPT_THRESHOLD = 50       // символов
        private const val LONG_CONTEXT_THRESHOLD = 2000     // токенов
        private const val LLM_CLASSIFIER_CACHE_SIZE = 50
    }

    /**
     * Принимает решение о роутинге запроса.
     *
     * @param prompt текст запроса пользователя
     * @param attachments вложения (изображения, аудио)
     * @param contextTokens количество токенов в контексте
     * @param config настройки Smart Router
     * @return RouteDecision — куда направить запрос
     */
    suspend fun route(
        prompt: String,
        attachments: List<Attachment>,
        contextTokens: Int,
        config: SmartRouterConfig
    ): RouteDecision {
        if (!config.enabled) {
            println("🔧 SmartRouter: disabled, sending to network")
            return RouteDecision.Network(config.networkProviderSettingsId)
        }

        println("🔧 SmartRouter.route: prompt='${prompt.take(50)}', len=${prompt.length}, contextTokens=$contextTokens, attachments=${attachments.size}")

        // === ШАГ 1: Быстрые правила (0 токенов) ===

        // 1a. Медиа (изображения, аудио) → локалка (Gemma мультимодальная)
        //     Документы/текст (большие) → сеть
        //     Смешанные (фото + PDF) → сеть
        if (attachments.isNotEmpty() && config.routeMultimodalToLocal) {
            val hasImage = attachments.any { it.isImage }
            val hasAudio = attachments.any { it.isAudio }
            val hasLargeDoc = attachments.any { (it.isDocument || it.isText) && it.fileSize > 1024 * 1024 }
            val hasOther = attachments.any { it.type.name == "OTHER" }
            // Если только медиа (без документов) → локалка
            if ((hasImage || hasAudio) && !hasLargeDoc && !hasOther) {
                println("🔧 SmartRouter: 1a → LOCAL (image/audio only)")
                return RouteDecision.Local(prompt)
            }
            // Только документы/текст без медиа → пропускаем (другие правила решат)
            if (!hasImage && !hasAudio && !hasOther) {
                println("🔧 SmartRouter: 1a → skip (docs only, other rules decide)")
            }
            // Смешанные (медиа + документы) или OTHER → сеть
            if (hasImage && hasLargeDoc) {
                println("🔧 SmartRouter: 1a → NETWORK (mixed media+docs)")
                return RouteDecision.Network(config.networkProviderSettingsId)
            }
            if (hasOther) {
                println("🔧 SmartRouter: 1a → NETWORK (OTHER type)")
                return RouteDecision.Network(config.networkProviderSettingsId)
            }
        }

        // 1b. Очень короткий запрос → локалка
        if (prompt.length < SHORT_PROMPT_THRESHOLD) {
            println("🔧 SmartRouter: 1b → LOCAL (short prompt: ${prompt.length} < $SHORT_PROMPT_THRESHOLD)")
            return RouteDecision.Local(prompt)
        }

        // 1c. Длинный контекст → сеть (или гибрид, если включён)
        if (contextTokens > LONG_CONTEXT_THRESHOLD) {
            if (config.enableHybrid) {
                println("🔧 SmartRouter: 1c → HYBRID (long context: $contextTokens)")
                return RouteDecision.Hybrid(prompt)
            } else {
                println("🔧 SmartRouter: 1c → NETWORK (long context: $contextTokens)")
                return RouteDecision.Network(config.networkProviderSettingsId)
            }
        }

        // 1d. Ключевые слова-триггеры → сеть (или гибрид, если включён)
        val lowerPrompt = prompt.lowercase()
        if (NETWORK_TRIGGERS.any { lowerPrompt.contains(it) }) {
            if (config.enableHybrid) {
                println("🔧 SmartRouter: 1d → HYBRID (keyword trigger, hybrid mode on)")
                return RouteDecision.Hybrid(prompt)
            } else {
                println("🔧 SmartRouter: 1d → NETWORK (keyword trigger)")
                return RouteDecision.Network(config.networkProviderSettingsId)
            }
        }

        // === ШАГ 2: LLM-классификация (~100-150 токенов к локалке) ===

        if (localAiInteraction.isLoaded()) {
            println("🔧 SmartRouter: 2 → LLM classifier")
            val complexity = classifyComplexity(prompt)
            println("🔧 SmartRouter: complexity=$complexity, threshold=${config.complexityThreshold}")
            return when {
                complexity <= config.complexityThreshold -> {
                    println("🔧 SmartRouter: 2 → LOCAL (complexity $complexity <= ${config.complexityThreshold})")
                    RouteDecision.Local(prompt)
                }
                else -> {
                    println("🔧 SmartRouter: 2 → NETWORK (complexity $complexity > ${config.complexityThreshold})")
                    if (config.enableHybrid) {
                        println("🔧 SmartRouter: 2 → HYBRID (complex $complexity > ${config.complexityThreshold})")
                        RouteDecision.Hybrid(prompt)
                    } else {
                        RouteDecision.Network(config.networkProviderSettingsId)
                    }
                }
            }
        }

        // Если не удалось выполнить LLM-классификацию (модель не загружена)
        if (config.enableHybrid) {
            println("🔧 SmartRouter: local not loaded, but hybrid enabled → HYBRID")
            return RouteDecision.Hybrid(prompt)
        }
        println("🔧 SmartRouter: local not loaded → NETWORK")
        return RouteDecision.Network(config.networkProviderSettingsId)
    }

    /**
     * LLM-классификатор — оценивает сложность запроса через локалку.
     *
     * @param prompt текст запроса
     * @return число 0.0 (очень просто) — 1.0 (очень сложно)
     */
    private suspend fun classifyComplexity(prompt: String): Float {
        val classifierPrompt = buildString {
            appendLine("Оцени сложность запроса от 0 до 3.")
            appendLine("0 = приветствие / базовая справка / бытовуха")
            appendLine("1 = простой (1 шаг, 1 инструмент, короткий контекст)")
            appendLine("2 = средний (2-3 шага, несколько инструментов)")
            appendLine("3 = сложный (много шагов, анализ, длинный контекст)")
            appendLine()
            appendLine("Запрос: $prompt")
            append("Оценка (только цифра 0-3):")
        }

        return try {
            val result = localAiInteraction.generate(classifierPrompt)
            if (result.isSuccess) {
                val text = result.getOrNull()?.trim() ?: ""
                val digit = text.firstOrNull { it.isDigit() }?.digitToIntOrNull() ?: 1
                (digit.coerceIn(0, 3)) / 3.0f
            } else {
                0.5f // По умолчанию — средняя сложность
            }
        } catch (e: Exception) {
            println("⚠️ SmartRouter.classifyComplexity error: ${e.message}")
            0.5f
        }
    }

    /**
     * Решение для фолбэка — при недоступности сети.
     */
    fun fallbackDecision(
        prompt: String,
        contextTokens: Int,
        config: SmartRouterConfig
    ): RouteDecision {
        if (!config.enableFallback) {
            return RouteDecision.Network(config.networkProviderSettingsId)
        }
        if (contextTokens > config.maxLocalTokens) {
            return RouteDecision.Fallback(
                prompt = prompt,
                reason = "Сеть недоступна, а запрос слишком длинный для локальной модели"
            )
        }
        return RouteDecision.Local(prompt)
    }
}
