package com.pai.android.agent

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Лёгкий ReAct агент для локальной модели.
 * Цикл: отправляет промпт → парсит Action → выполняет → Observation → повторяет → Final Answer.
 */
@Singleton
class LocalReActAgent @Inject constructor(
    private val localAiInteraction: LocalAiInteraction
) {
    companion object {
        private const val TAG = "LocalReActAgent"
        private const val MAX_STEPS = 5
    }

    /**
     * Запускает ReAct цикл на локальной модели.
     */
    suspend fun run(
        userPrompt: String,
        systemPrompt: String?,
        executor: suspend (String, Map<String, String>) -> String
    ): Result<String> {
        if (!localAiInteraction.isLoaded()) {
            return Result.failure(IllegalStateException("Локальная модель не загружена"))
        }

        try {
            val fullSystem = buildString {
                if (!systemPrompt.isNullOrBlank()) { append(systemPrompt.trim()); append("\n\n") }
                appendLine("User request: $userPrompt")
            }

            var conversation = fullSystem
            var finalAnswer: String? = null
            val seenActions = mutableSetOf<String>()
            var lastObservation: String = ""

            for (step in 1..MAX_STEPS) {
                println("🔧 LocalReAct: шаг $step/$MAX_STEPS")

                val result = localAiInteraction.generate(
                    prompt = conversation,
                    systemPrompt = null,
                    temperature = 0.7,
                    maxTokens = 2048
                )
                if (result.isFailure) {
                    return Result.failure(result.exceptionOrNull() ?: Exception("Ошибка генерации"))
                }

                val response = result.getOrNull()?.trim() ?: ""
                println("🔧 LocalReAct: response (${response.take(200)}...)")

                // Парсим Action и Final Answer
                val actionMatch = Regex("""Action:\s*(?!Final Answer)(\w+)\s*[(]([^)]*)[)]""", RegexOption.IGNORE_CASE).find(response)
                val finalMatch = Regex("""(?:Action:\s*)?Final Answer:\s*(.+?)(?:\n|$)""", RegexOption.DOT_MATCHES_ALL).find(response)

                if (actionMatch != null) {
                    // Есть Action — выполняем
                    val toolName = actionMatch.groupValues[1].trim()
                    val argsString = actionMatch.groupValues[2].trim()
                    val args = parseArgs(argsString)
                    println("🔧 LocalReAct: Action $toolName($args)")

                    if (toolName.equals("ask_user", ignoreCase = true)) {
                        val question = args["message"] ?: args["question"] ?: response
                        println("🔧 LocalReAct: ask_user → возвращаем вопрос")
                        finalAnswer = question
                        break
                    }

                    val observation = try { executor(toolName, args) } catch (e: Exception) { "Error: ${e.message}" }
                    lastObservation = observation
                    println("🔧 LocalReAct: Observation=$observation")

                    val actionKey = "$toolName($argsString)"
                    if (!seenActions.add(actionKey)) {
                        println("🔧 LocalReAct: повторный вызов $actionKey, прерываем")
                        finalAnswer = observation
                        break
                    }

                    conversation = buildString {
                        appendLine(conversation)
                        appendLine(response)
                        appendLine("Observation: $observation")
                        appendLine()
                    }.trimEnd()
                } else if (finalMatch != null) {
                    // Только Final Answer (без Action)
                    var fa = finalMatch.groupValues[1].trim()
                    if (lastObservation.isNotEmpty() && fa.length < 100 && !fa.contains(lastObservation.take(50))) {
                        fa = "$fa\n\n$lastObservation"
                    }
                    finalAnswer = fa
                    println("🔧 LocalReAct: Final Answer получен на шаге $step")
                    break
                } else {
                    // Ни Action, ни Final Answer — считаем что это ответ
                    println("🔧 LocalReAct: нет Action и Final Answer, используем как ответ")
                    finalAnswer = response
                    break
                }
            }

            if (finalAnswer == null) {
                finalAnswer = "I'm sorry, I couldn't complete this task in $MAX_STEPS steps. Please try a simpler request."
            }
            return Result.success(finalAnswer)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка LocalReAct", e)
            return Result.failure(e)
        }
    }

    private fun parseArgs(argsString: String): Map<String, String> {
        if (argsString.isBlank()) return emptyMap()
        val args = mutableMapOf<String, String>()
        // Поддерживает: key="value", key='value', key=value, key: "value", key: value
        val regex = """(\w+)\s*[:=]\s*(?:"([^"]*)"|'([^']*)'|(\S+))""".toRegex()
        for (match in regex.findAll(argsString)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].takeIf { it.isNotEmpty() }
                ?: match.groupValues[3].takeIf { it.isNotEmpty() }
                ?: match.groupValues[4].trimEnd(',')
            args[key] = value
        }
        return args
    }
}
