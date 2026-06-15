package com.pai.android.agent

import com.pai.android.data.repository.AiRepository
import com.pai.android.data.repository.MemoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import org.json.JSONArray

/**
 * Шаг плана выполнения.
 */
data class PlanStep(
    val stepNumber: Int,
    val description: String,
    val toolName: String,
    val parameters: Map<String, Any>,
    val condition: String? = null, // Условие выполнения (например, "if previous_step_success")
    val expectedOutput: String? = null // Ожидаемый результат для проверки
)

/**
 * План выполнения задачи.
 */
data class ExecutionPlan(
    val taskDescription: String,
    val steps: List<PlanStep>,
    val estimatedComplexity: String, // "simple", "medium", "complex"
    val requiresConfirmation: Boolean = false,
    val fallbackPlan: ExecutionPlan? = null // Альтернативный план на случай ошибок
)

/**
 * Результат выполнения шага плана.
 */
data class StepExecutionResult(
    val step: PlanStep,
    val success: Boolean,
    val output: String,
    val data: Map<String, Any>? = null,
    val error: String? = null,
    val executionTimeMs: Long = 0
)

/**
 * Результат выполнения всего плана.
 */
data class PlanExecutionResult(
    val plan: ExecutionPlan,
    val stepResults: List<StepExecutionResult>,
    val overallSuccess: Boolean,
    val finalOutput: String,
    val totalExecutionTimeMs: Long = 0,
    val adaptations: List<String> = emptyList() // Адаптации, сделанные во время выполнения
)

/**
 * AI-планировщик для сложных задач.
 * Анализирует запросы, строит планы выполнения и выполняет цепочки действий.
 */
@Singleton
class PlanningAgent @Inject constructor(
    private val aiRepository: AiRepository,
    private val memoryRepository: MemoryRepository,
    private val toolRegistry: ToolRegistry,
    private val requestClassifier: RequestClassifier,
    private val defaultDispatcher: CoroutineDispatcher
) {
    
    /**
     * Обрабатывает сложный запрос с использованием AI-планирования.
     */
    suspend fun processComplexQuery(
        query: String,
        context: String = "",
        maxPlanningAttempts: Int = 3
    ): AgentResponse {
        return withContext(defaultDispatcher) {
            try {
                println("🤖 PlanningAgent: начинаю обработку сложного запроса: '$query'")
                
                // Шаг 1: Анализ задачи и построение плана
                val plan = generateExecutionPlan(query, context)
                println("📋 Сгенерирован план выполнения (${plan.steps.size} шагов): ${plan.taskDescription}")
                
                // Шаг 2: Выполнение плана
                val executionResult = executePlan(plan, maxPlanningAttempts)
                
                // Шаг 3: Формирование финального ответа
                if (executionResult.overallSuccess) {
                    AgentResponse.Success(
                        answer = buildFinalAnswer(executionResult),
                        thoughts = listOf("Задача успешно выполнена по плану"),
                        actions = executionResult.stepResults.map { convertToActionResult(it) }
                    )
                } else {
                    AgentResponse.Error(
                        error = "Не удалось выполнить задачу полностью",
                        details = buildErrorDetails(executionResult)
                    )
                }
            } catch (e: Exception) {
                println("❌ PlanningAgent ошибка: ${e.message}")
                AgentResponse.Error(
                    error = "Ошибка планирования задачи",
                    details = e.message ?: "Неизвестная ошибка"
                )
            }
        }
    }
    
    /**
     * Генерирует план выполнения задачи с помощью AI.
     */
    private suspend fun generateExecutionPlan(query: String, context: String): ExecutionPlan {
        val prompt = buildPlanningPrompt(query, context)
        
        val response = aiRepository.sendMessage(
            messages = listOf(
                com.pai.android.data.model.Message.createSystemMessage("planning_agent", prompt),
                com.pai.android.data.model.Message.createUserMessage("planning_agent", query)
            ),
            systemPrompt = "Ты планировщик задач. Анализируй запрос и создай план выполнения.",
            memoryContext = context
        )
        
        val planText = response.getOrThrow().text
        println("🧠 AI сгенерировал план:\n$planText")
        
        return parsePlanFromAiResponse(planText, query)
    }
    
    /**
     * Строит промпт для планирования задачи.
     */
    private fun buildPlanningPrompt(query: String, context: String): String {
        val toolsDescription = toolRegistry.getToolsDescription()
        
        return """
            Ты - планировщик задач для AI-ассистента. 
            Проанализируй запрос пользователя и создай детальный план выполнения.
            
            ## Контекст задачи:
            $context
            
            ## Запрос пользователя:
            "$query"
            
            ## Доступные инструменты:
            $toolsDescription
            
            ## Правила планирования:
            1. Разбей сложную задачу на простые шаги
            2. Каждый шаг должен использовать один инструмент
            3. Учитывай зависимости между шагами (некоторые шаги требуют результатов предыдущих)
            4. Предусмотри проверки и обработку ошибок
            5. Оцени сложность задачи: simple (1-2 шага), medium (3-5 шагов), complex (6+ шагов)
            6. Отметь, требуется ли подтверждение пользователя для опасных операций
            
            ## Формат плана (JSON):
            {
                "task_description": "Краткое описание задачи",
                "estimated_complexity": "simple|medium|complex",
                "requires_confirmation": true/false,
                "steps": [
                    {
                        "step_number": 1,
                        "description": "Что делаем на этом шаге",
                        "tool_name": "имя_инструмента",
                        "parameters": {
                            "param1": "value1",
                            "param2": "value2"
                        },
                        "condition": "условие выполнения (опционально)",
                        "expected_output": "ожидаемый результат (опционально)"
                    }
                ]
            }
            
            ## Примеры:
            
            1. Запрос: "найди все .md файлы и создай из них единый отчёт"
               План: {
                 "task_description": "Поиск Markdown файлов и создание сводного отчёта",
                 "estimated_complexity": "medium",
                 "requires_confirmation": false,
                 "steps": [
                   {
                     "step_number": 1,
                     "description": "Найти все файлы с расширением .md",
                     "tool_name": "file_system",
                     "parameters": {"command": "list_files", "path": "", "recursive": true, "filter_extension": ".md"}
                   },
                   {
                     "step_number": 2,
                     "description": "Прочитать содержимое каждого найденного файла",
                     "tool_name": "file_system",
                     "parameters": {"command": "read_file", "path": "[результат шага 1]"},
                     "condition": "if step1_success"
                   },
                   {
                     "step_number": 3,
                     "description": "Проанализировать содержимое файлов",
                     "tool_name": "document_analysis",
                     "parameters": {"command": "analyze_folder", "path": "[результат шага 1]"}
                   },
                   {
                     "step_number": 4,
                     "description": "Создать сводный отчёт",
                     "tool_name": "file_system",
                     "parameters": {"command": "create_report", "path": "reports/summary.md", "content": "[результат шага 3]"}
                   }
                 ]
               }
            
            2. Запрос: "добавь запись 'Купить молоко' в файл todo.txt"
               План: {
                 "task_description": "Добавление записи в файл списка дел",
                 "estimated_complexity": "simple",
                 "requires_confirmation": false,
                 "steps": [
                   {
                     "step_number": 1,
                     "description": "Добавить запись в файл",
                     "tool_name": "file_system",
                     "parameters": {"command": "append_file", "path": "todo.txt", "content": "Купить молоко"}
                   }
                 ]
               }
            
            Верни ТОЛЬКО JSON без дополнительного текста.
        """.trimIndent()
    }
    
    /**
     * Парсит план из ответа AI.
     */
    private suspend fun parsePlanFromAiResponse(responseText: String, originalQuery: String): ExecutionPlan {
        return try {
            // Извлекаем JSON из ответа
            val jsonStart = responseText.indexOf('{')
            val jsonEnd = responseText.lastIndexOf('}') + 1
            val jsonText = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                responseText.substring(jsonStart, jsonEnd)
            } else {
                responseText
            }
            
            val json = JSONObject(jsonText)
            val taskDescription = json.getString("task_description")
            val estimatedComplexity = json.getString("estimated_complexity")
            val requiresConfirmation = json.optBoolean("requires_confirmation", false)
            
            val stepsArray = json.getJSONArray("steps")
            val steps = mutableListOf<PlanStep>()
            
            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                val stepNumber = stepObj.getInt("step_number")
                val description = stepObj.getString("description")
                val toolName = stepObj.getString("tool_name")
                val parametersObj = stepObj.getJSONObject("parameters")
                
                // Конвертируем JSONObject в Map<String, Any>
                val parameters = mutableMapOf<String, Any>()
                val keysIterator: Iterator<String> = parametersObj.keys()
                while (keysIterator.hasNext()) {
                    val key = keysIterator.next()
                    parameters[key] = parametersObj.optString(key, "")
                }
                
                val conditionRaw = stepObj.optString("condition", "")
                val condition = if (conditionRaw.isNotEmpty()) conditionRaw else null
                val expectedOutputRaw = stepObj.optString("expected_output", "")
                val expectedOutput = if (expectedOutputRaw.isNotEmpty()) expectedOutputRaw else null
                
                steps.add(PlanStep(
                    stepNumber = stepNumber,
                    description = description,
                    toolName = toolName,
                    parameters = parameters,
                    condition = condition,
                    expectedOutput = expectedOutput
                ))
            }
            
            ExecutionPlan(
                taskDescription = taskDescription,
                steps = steps,
                estimatedComplexity = estimatedComplexity,
                requiresConfirmation = requiresConfirmation
            )
        } catch (e: Exception) {
            println("⚠️ Ошибка парсинга плана от AI: ${e.message}")
            // Создаём простой fallback план
            createFallbackPlan(originalQuery)
        }
    }
    
    /**
     * Создаёт простой fallback план на основе классификатора запросов.
     */
    private suspend fun createFallbackPlan(query: String): ExecutionPlan {
        println("🔄 Создаю fallback план для запроса: '$query'")
        
        try {
            // Используем существующий классификатор для определения типа запроса
            val classification = requestClassifier.classify(query)
            
            val step = PlanStep(
                stepNumber = 1,
                description = "Выполнение запроса: $query",
                toolName = classification.toolName ?: "file_system",
                parameters = classification.parameters
            )
            
            return ExecutionPlan(
                taskDescription = "Выполнение запроса: $query",
                steps = listOf(step),
                estimatedComplexity = "simple",
                requiresConfirmation = false
            )
        } catch (e: Exception) {
            println("⚠️ Классификатор не смог обработать запрос: ${e.message}")
            
            // Ручное извлечение параметров как крайний fallback
            val params = extractManualParams(query)
            
            val step = PlanStep(
                stepNumber = 1,
                description = "Выполнение запроса: $query",
                toolName = "file_system",
                parameters = params
            )
            
            return ExecutionPlan(
                taskDescription = "Выполнение запроса: $query",
                steps = listOf(step),
                estimatedComplexity = "simple",
                requiresConfirmation = false
            )
        }
    }
    
    /**
     * Ручное извлечение параметров как крайний fallback.
     */
    private fun extractManualParams(query: String): Map<String, Any> {
        val lowerQuery = query.lowercase()
        val params = mutableMapOf<String, Any>()
        
        // Определяем команду по ключевым словам
        params["command"] = when {
            "append_file" in lowerQuery || "add" in lowerQuery || "добавь" in lowerQuery || "добавить" in lowerQuery -> "append_file"
            "write_file" in lowerQuery || "write" in lowerQuery || "запиши" in lowerQuery -> "write_file"
            "create_folder" in lowerQuery || "create" in lowerQuery || "создай" in lowerQuery || "создать" in lowerQuery -> {
                if ("папк" in lowerQuery || "folder" in lowerQuery || "директор" in lowerQuery) "create_folder" else "write_file"
            }
            "read_file" in lowerQuery || "read" in lowerQuery || "прочитай" in lowerQuery || "открой" in lowerQuery || "open" in lowerQuery -> "read_file"
            "list_files" in lowerQuery || "list" in lowerQuery || "список" in lowerQuery || "покажи" in lowerQuery || "найди" in lowerQuery || "find" in lowerQuery || "show" in lowerQuery -> "list_files"
            "delete" in lowerQuery || "удали" in lowerQuery || "удалить" in lowerQuery || "remove" in lowerQuery -> "delete"
            "analyze" in lowerQuery || "анализ" in lowerQuery || "проанализируй" in lowerQuery -> "analyze_directory"
            else -> "list_files"
        }
        
        // Извлекаем путь/имя файла
        val filePatterns = listOf(
            Regex("""([\p{L}\p{N}_\-/.]+)\.(txt|md|json|xml|html|css|js|kt|java|py|csv|log|ini|cfg|yml|yaml)\b""", RegexOption.IGNORE_CASE),
            Regex("файл\\s+([\\p{L}\\p{N}_\\-/.]+)", RegexOption.IGNORE_CASE),
            Regex("file\\s+([\\p{L}\\p{N}_\\-/.]+)", RegexOption.IGNORE_CASE),
            Regex("папк\\s+([\\p{L}\\p{N}_\\-/.]+)", RegexOption.IGNORE_CASE),
            Regex("folder\\s+([\\p{L}\\p{N}_\\-/.]+)", RegexOption.IGNORE_CASE),
            Regex("в\\s+([\\p{L}\\p{N}_\\-/.]+)", RegexOption.IGNORE_CASE),
            Regex("to\\s+([\\p{L}\\p{N}_\\-/.]+)", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in filePatterns) {
            val match = pattern.find(query)
            if (match != null) {
                val path = match.groupValues[1].trim()
                if (path.isNotEmpty()) {
                    params["path"] = path
                    break
                }
            }
        }
        
        // Если путь не найден, используем todo.txt
        if (!params.containsKey("path")) {
            params["path"] = "todo.txt"
        }
        
        // Извлекаем содержимое для append/write
        if (params["command"] == "append_file") {
            val contentPatterns = listOf(
                Regex("запись\\s+(.+)", RegexOption.IGNORE_CASE),
                Regex("entry\\s+(.+)", RegexOption.IGNORE_CASE),
                Regex("добавь\\s+(.+)", RegexOption.IGNORE_CASE),
                Regex("add\\s+(.+)", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in contentPatterns) {
                val match = pattern.find(query)
                if (match != null) {
                    val content = match.groupValues[1].trim()
                    if (content.isNotEmpty()) {
                        params["content"] = content
                        break
                    }
                }
            }
            
            // Если контент не найден, ищем текст между add/добавь и to/в
            if (!params.containsKey("content")) {
                val betweenPattern = Regex("(?:add|добавь|entry|запись)\\s+(.+?)\\s+(?:to|в|the|file)", RegexOption.IGNORE_CASE)
                val betweenMatch = betweenPattern.find(query)
                if (betweenMatch != null) {
                    params["content"] = betweenMatch.groupValues[1].trim()
                } else {
                    params["content"] = "Новая запись"
                }
            }
        }
        
        return params
    }
    
    /**
     * Выполняет план шаг за шагом.
     */
    private suspend fun executePlan(
        plan: ExecutionPlan,
        maxAttempts: Int
    ): PlanExecutionResult {
        println("▶️ Начинаю выполнение плана: ${plan.taskDescription}")
        
        val stepResults = mutableListOf<StepExecutionResult>()
        val adaptations = mutableListOf<String>()
        var overallSuccess = true
        val startTime = System.currentTimeMillis()
        
        for (step in plan.steps) {
            println("🔧 Выполняю шаг ${step.stepNumber}: ${step.description}")
            
            // Проверяем условие выполнения
            if (step.condition != null && !checkCondition(step.condition, stepResults)) {
                println("⏭️ Пропускаю шаг ${step.stepNumber} (условие не выполнено): ${step.condition}")
                stepResults.add(StepExecutionResult(
                    step = step,
                    success = false,
                    output = "Шаг пропущен: условие не выполнено",
                    error = "Condition not met: ${step.condition}"
                ))
                continue
            }
            
            // Выполняем шаг с подстановкой результатов предыдущих
            val stepResult = executePlanStep(step, maxAttempts, stepResults)
            stepResults.add(stepResult)
            
            if (!stepResult.success) {
                println("❌ Шаг ${step.stepNumber} завершился с ошибкой: ${stepResult.error}")
                overallSuccess = false
                
                // Пытаемся адаптировать план
                val adaptation = attemptAdaptation(step, stepResult, plan, stepResults)
                if (adaptation != null) {
                    adaptations.add(adaptation)
                    println("🔄 Адаптация выполнена: $adaptation")
                } else {
                    break // Не удалось адаптировать, прерываем выполнение
                }
            } else {
                println("✅ Шаг ${step.stepNumber} успешно выполнен")
            }
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        
        return PlanExecutionResult(
            plan = plan,
            stepResults = stepResults,
            overallSuccess = overallSuccess,
            finalOutput = buildFinalOutput(stepResults),
            totalExecutionTimeMs = totalTime,
            adaptations = adaptations
        )
    }
    
    /**
     * Выполняет один шаг плана с подстановкой результатов предыдущих шагов.
     */
    private suspend fun executePlanStep(
        step: PlanStep,
        maxAttempts: Int,
        previousResults: List<StepExecutionResult> = emptyList()
    ): StepExecutionResult {
        val startTime = System.currentTimeMillis()
        var lastError: String? = null
        
        // Подставляем результаты предыдущих шагов в параметры
        val resolvedParams = resolveStepParameters(step.parameters, previousResults)
        
        for (attempt in 1..maxAttempts) {
            try {
                println("🔄 Попытка $attempt выполнения шага ${step.stepNumber}")
                
                val tool = toolRegistry.getTool(step.toolName)
                if (tool == null) {
                    return StepExecutionResult(
                        step = step,
                        success = false,
                        output = "Инструмент не найден",
                        error = "Инструмент '${step.toolName}' не найден",
                        executionTimeMs = System.currentTimeMillis() - startTime
                    )
                }
                
                // Выполняем инструмент с разрешёнными параметрами
                println("📋 Параметры шага ${step.stepNumber}: $resolvedParams")
                val result = tool.execute(resolvedParams)
                
                val executionTime = System.currentTimeMillis() - startTime
                
                return when (result) {
                    is ToolResult.Success -> StepExecutionResult(
                        step = step,
                        success = true,
                        output = result.output,
                        data = result.data,
                        executionTimeMs = executionTime
                    )
                    is ToolResult.Error -> StepExecutionResult(
                        step = step,
                        success = false,
                        output = result.error,
                        error = result.error,
                        executionTimeMs = executionTime
                    )
                    is ToolResult.ConfirmationRequired -> StepExecutionResult(
                        step = step,
                        success = false,
                        output = "Требуется подтверждение",
                        error = "Confirmation required: ${result.question}",
                        executionTimeMs = executionTime
                    )
                }
            } catch (e: Exception) {
                lastError = e.message
                println("⚠️ Ошибка при выполнении шага ${step.stepNumber} (попытка $attempt): ${e.message}")
                
                if (attempt < maxAttempts) {
                    // Небольшая пауза перед повторной попыткой
                    kotlinx.coroutines.delay(500L)
                }
            }
        }
        
        return StepExecutionResult(
            step = step,
            success = false,
            output = "Не удалось выполнить шаг после $maxAttempts попыток",
            error = lastError ?: "Неизвестная ошибка",
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }
    
    /**
     * Разрешает параметры шага, подставляя результаты предыдущих шагов.
     * Заменяет плейсхолдеры вида "[результат шага N]" на реальные результаты.
     */
    private fun resolveStepParameters(
        params: Map<String, Any>,
        previousResults: List<StepExecutionResult>
    ): Map<String, Any> {
        if (previousResults.isEmpty()) return params
        
        val resolved = mutableMapOf<String, Any>()
        
        params.forEach { (key, value) ->
            if (value is String) {
                // Первый этап: замена плейсхолдеров вида "[результат шага N]"
                val placeholderPattern = Regex("""\[результат шага (\d+)]""", RegexOption.IGNORE_CASE)
                var result = placeholderPattern.replace(value) { match ->
                    val stepNum = match.groupValues[1].toIntOrNull()
                    if (stepNum != null) {
                        val prevResult = previousResults.find { it.step.stepNumber == stepNum }
                        if (prevResult != null && prevResult.success) {
                            prevResult.output
                        } else {
                            match.value
                        }
                    } else {
                        match.value
                    }
                }
                
                // Второй этап: замена общих плейсхолдеров (используем цепочку replace)
                result = result
                    .replace("[результат шага 1]", previousResults.firstOrNull()?.output ?: "")
                    .replace("[результат предыдущего шага]", previousResults.lastOrNull()?.output ?: "")
                    .replace("[список файлов]", previousResults.firstOrNull { it.success }?.output ?: "")
                    .replace("[file_list]", previousResults.firstOrNull { it.success }?.output ?: "")
                    .replace("[result of step 1]", previousResults.firstOrNull()?.output ?: "")
                
                resolved[key] = result
            } else {
                resolved[key] = value
            }
        }
        
        return resolved
    }
    
    /**
     * Проверяет условие выполнения шага.
     */
    private fun checkCondition(condition: String, previousResults: List<StepExecutionResult>): Boolean {
        if (condition.isBlank()) return true // Нет условия - всегда выполняем
        
        return when (condition.lowercase()) {
            "if previous_step_success" -> {
                previousResults.lastOrNull()?.success ?: true
            }
            "if all_previous_success" -> {
                previousResults.all { it.success }
            }
            else -> {
                println("⚠️ Неизвестное условие: '$condition', считаю выполненным")
                true
            }
        }
    }
    
    /**
     * Пытается адаптировать план при ошибке выполнения.
     */
    private suspend fun attemptAdaptation(
        failedStep: PlanStep,
        failedResult: StepExecutionResult,
        originalPlan: ExecutionPlan,
        completedSteps: List<StepExecutionResult>
    ): String? {
        // Простая адаптация: пропустить шаг и продолжить
        if (failedStep.condition == null || failedStep.condition.contains("optional")) {
            return "Пропущен необязательный шаг ${failedStep.stepNumber}: ${failedStep.description}"
        }
        
        // Для файловых операций: попробовать альтернативный путь
        if (failedStep.toolName == "file_system") {
            val path = failedStep.parameters["path"] as? String
            if (path != null && failedResult.error?.contains("не найден") == true) {
                // Файл не найден - можно создать его
                val createStep = PlanStep(
                    stepNumber = failedStep.stepNumber,
                    description = "Создать файл: $path",
                    toolName = "file_system",
                    parameters = mapOf<String, Any>(
                        "command" to "write_file",
                        "path" to path,
                        "content" to ((failedStep.parameters["content"] as? String) ?: "")
                    )
                )
                
                // В реальной реализации нужно было бы изменить оставшиеся шаги плана
                return "Адаптация: файл $path не найден, требуется создание"
            }
        }
        
        return null
    }
    
    /**
     * Строит финальный вывод на основе результатов выполнения.
     */
    private fun buildFinalOutput(stepResults: List<StepExecutionResult>): String {
        val successfulSteps = stepResults.count { it.success }
        val totalSteps = stepResults.size
        
        return if (successfulSteps == totalSteps) {
            "✅ Все $totalSteps шагов успешно выполнены"
        } else {
            "⚠️ Выполнено $successfulSteps из $totalSteps шагов"
        }
    }
    
    /**
     * Строит финальный ответ для пользователя.
     */
    private fun buildFinalAnswer(executionResult: PlanExecutionResult): String {
        val builder = StringBuilder()
        
        builder.append("🎯 **Задача выполнена:** ${executionResult.plan.taskDescription}\n\n")
        
        if (executionResult.adaptations.isNotEmpty()) {
            builder.append("🔄 **Адаптации во время выполнения:**\n")
            executionResult.adaptations.forEach { adaptation ->
                builder.append("• $adaptation\n")
            }
            builder.append("\n")
        }
        
        builder.append("📊 **Результаты выполнения:**\n")
        executionResult.stepResults.forEachIndexed { index, result ->
            val emoji = if (result.success) "✅" else "❌"
            builder.append("$emoji Шаг ${index + 1}: ${result.step.description}\n")
            if (!result.success && result.error != null) {
                builder.append("   Ошибка: ${result.error}\n")
            }
        }
        
        builder.append("\n⏱️ **Общее время выполнения:** ${executionResult.totalExecutionTimeMs} мс")
        
        return builder.toString()
    }
    
    /**
     * Строит детали ошибки для отладки.
     */
    private fun buildErrorDetails(executionResult: PlanExecutionResult): String {
        val failedSteps = executionResult.stepResults.filter { !it.success }
        
        return if (failedSteps.isEmpty()) {
            "Неизвестная ошибка выполнения плана"
        } else {
            val builder = StringBuilder()
            builder.append("Не удалось выполнить следующие шаги:\n")
            
            failedSteps.forEach { stepResult ->
                builder.append("- Шаг ${stepResult.step.stepNumber}: ${stepResult.step.description}\n")
                builder.append("  Ошибка: ${stepResult.error ?: "Неизвестная ошибка"}\n")
            }
            
            builder.toString()
        }
    }
    
    /**
     * Конвертирует результат шага в ActionResult.
     */
    private fun convertToActionResult(stepResult: StepExecutionResult): ActionResult {
        return if (stepResult.success) {
            ActionResult.Success(
                toolName = stepResult.step.toolName,
                observation = stepResult.output,
                data = stepResult.data ?: emptyMap()
            )
        } else {
            ActionResult.Error(
                observation = stepResult.output,
                error = stepResult.error ?: "Неизвестная ошибка"
            )
        }
    }
}