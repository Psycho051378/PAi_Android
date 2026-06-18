package com.pai.android.agent

import com.pai.android.data.model.Message
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.repository.MemoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pai.android.data.network.model.NativeFunctionDefinition
import com.pai.android.data.network.model.NativeToolDefinition
import org.json.JSONObject
import com.pai.android.agent.tools.WebFetchTool
import com.pai.android.agent.skills.HomeSkill

/**
 * Движок принятия решений для AI-ассистента.
 *
 * Процесс:
 * 1. AI распознаёт намерение пользователя (Intent) - без паттернов
 * 2. DecisionEngine находит подходящий навык (Skill)
 * 3. Навык выполняет действие и возвращает результат
 * 4. Результат форматируется как ответ пользователю
 *
 * Ключевая идея: ВСЁ распознавание и извлечение параметров - через AI.
 * Никаких жёстких паттернов, регулярных выражений или keyword matching.
 * AI сам понимает запрос и возвращает структурированный JSON.
 */
@Singleton
class DecisionEngine @Inject constructor(
    private val aiRepository: AiRepository,
    private val memoryRepository: MemoryRepository,
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: ToolRegistry,
    private val intentRecognizer: IntentRecognizer,
    private val agentPlanner: AgentPlanner,
    private val reactAgent: ReActAgent,
    private val taskQueue: TaskQueue,
    private val persistentContext: PersistentContext,
    private val projectManager: ProjectManager,
    private val contextEngine: ContextEngine,
    private val taskScheduler: TaskScheduler,
    private val homeSkill: HomeSkill,
    private val defaultDispatcher: CoroutineDispatcher
) {
    var skillsDirectory: String = ""

    /**
     * Обрабатывает детерминированные запросы (early exit).
     * Если запрос совпадает с простым паттерном — выполняет сразу, не вызывая AI.
     * @return AgentResponse или null, если запрос не детерминированный
     */
    private suspend fun handleDeterministicQuery(query: String): AgentResponse? {
        val lower = query.lowercase().trim()

        // Паттерны для детерминированных запросов (мультиязычные)
        val patterns = mapOf(
            // Показать структуру папок / Show folder structure
            listOf(
                "покажи структуру папок", "структуру папок", "дерево папок", "древовидную структуру",
                "show folder structure", "display directory tree", "show tree structure",
                "folder structure", "directory tree", "tree view"
            ) to mapOf("command" to "list_files", "tree" to "true", "recursive" to "true"),

            // Найти все .txt файлы / Find all .txt files
            listOf(
                "найди все .txt файлы", "найди все txt файлы", "найди txt файлы", "покажи все .txt",
                "find all .txt files", "show all txt files", "list all text files",
                "find txt files", "show text files"
            ) to mapOf("command" to "list_files", "filter_extension" to "txt", "recursive" to "true"),

            // Найти все файлы / Find all files
            listOf(
                "найди все файлы", "покажи все файлы", "список всех файлов",
                "find all files", "show all files", "list all files"
            ) to mapOf("command" to "list_files", "recursive" to "true"),

            // Переместить все текстовые файлы / Move all text files
            listOf(
                "перемести все текстовые файлы", "перемести все .txt файлы", "перемести txt файлы",
                "move all text files", "move all .txt files", "move txt files"
            ) to mapOf("command" to "move", "source" to "*.txt", "recursive" to "true"),

            // Когда создана папка / When was folder created
            listOf(
                "когда была создана папка", "когда создана папка", "дата создания папки",
                "when was folder created", "folder creation date", "when folder created",
                "when was directory created", "directory creation date"
            ) to mapOf("command" to "get_file_info", "attribute" to "creation_time"),

            // Размер файла / File size
            listOf(
                "какой размер файла", "размер файла", "сколько весит файл",
                "what is file size", "file size", "size of file", "how big is file"
            ) to mapOf("command" to "get_file_info", "attribute" to "size"),

            // Информация о файле / File info
            listOf(
                "покажи информацию о файле", "информация о файле", "свойства файла",
                "show file info", "file information", "file properties",
                "file details", "file attributes"
            ) to mapOf("command" to "get_file_info", "attribute" to "all"),

            // Сканирование сети / Network scan
            listOf(
                "сканируй сеть", "просканируй сеть", "найди устройства в сети",
                "что в сети", "какие устройства в сети", "найди устройства",
                "scan network", "network scan", "scan wifi", "list network devices"
            ) to mapOf("command" to "home_scan"),



            // Открыть/запустить приложение / Launch app
            listOf(
                "запусти приложение", "открой приложение", "запусти программу",
                "открой программу", "запусти игру", "открой игру",
                "open app", "launch app", "start app", "open application",
                "open settings", "launch settings",
                "запусти мессенджер", "запусти браузер", "запусти плеер",
                "запусти калькулятор", "открой калькулятор",
                "запусти камеру", "открой камеру",
                "запусти настройки", "открой настройки",
                "запусти часы", "открой часы",
                "запусти контакты", "открой контакты",
                "запусти телефон", "открой телефон"
            ) to mapOf("command" to "app_launch"),

            // Открыть файл / Open file
            listOf(
                "открой файл", "открыть файл", "запусти файл",
                "open file", "open document", "launch file", "run file"
            ) to mapOf("command" to "open_file"),

            // Веб-поиск / Web search — REMOVED from deterministic handler.
            // Goes through reactLoop (IntentRecognizer merged).
            // listOf(...) to mapOf("command" to "web_search"),

            // Погода / Weather — только простые запросы (остальное через AI)
            listOf(
                "какая погода", "температура сейчас", "сколько градусов",
                "холодно на улице", "тепло на улице", "что за окном",
                "weather now", "current weather", "temperature now",
                "what's the temperature", "what is the temperature",
                "погода сейчас"
            ) to mapOf("command" to "weather")
        )

        // Проверяем каждый паттерн
        for ((patternList, params) in patterns) {
            if (patternList.any { lower.contains(it) }) {
                println("🚀 Детерминированный запрос: '$query' → $params")

                // Для get_file_info запросов нужно извлечь путь
                val mutableParams: MutableMap<String, Any> = params.toMutableMap()
                val command = mutableParams["command"] as? String ?: ""

                when (command) {
                    "get_file_info" -> {
                        val path = intentRecognizer.extractPathFromQuery(query)
                        println("🔍 Извлечённый путь из запроса '$query': '$path'")

                        if (path.isNotBlank()) {
                            mutableParams["path"] = path
                            println("✅ Использую путь: '$path' для команды get_file_info")
                        } else {
                            println("⚠️ Не удалось извлечь путь из запроса '$query', передаём AI")
                            continue
                        }
                    }
                    "open_file" -> {
                        val path = intentRecognizer.extractPathFromQuery(query)
                        println("🔍 Извлечённый путь для open_file: '$path'")

                        if (path.isNotBlank()) {
                            mutableParams["path"] = path
                            println("✅ Использую путь: '$path' для команды open_file")
                        } else {
                            println("⚠️ Не удалось извлечь путь для open_file, передаём AI")
                            continue
                        }
                    }
                    "web_search" -> {
                        val searchQuery = extractSearchQuery(query, patternList)
                        println("🔍 Извлечённый поисковый запрос: '$searchQuery'")

                        if (searchQuery.isNotBlank()) {
                            mutableParams["query"] = searchQuery
                            println("✅ Использую запрос: '$searchQuery' для web_search")
                        } else {
                            println("⚠️ Не удалось извлечь поисковый запрос, передаём AI")
                            continue
                        }
                    }
                    "weather" -> {
                        // Сложные прогнозы (неделя, месяц, выходные) — передаём AI
                        val forecastWords = listOf("недел", "месяц", "прогноз", "выходн",
                            "forecast", "week", "month", "weekend")
                        if (forecastWords.any { lower.contains(it) }) {
                            println("🚀 Сложный погодный запрос, передаю AI")
                            continue
                        }

                        // Извлекаем название города
                        val city = extractCityFromWeatherQuery(query, patternList)
                        println("🔍 Извлечённый город: '$city'")

                        if (city.isNotBlank()) {
                            mutableParams["city"] = city
                            // Определяем количество дней
                            val days = extractDaysFromQuery(query)
                            if (days > 0) mutableParams["days"] = days
                            println("✅ Использую город: '$city', дней: $days для weather")
                        } else {
                            println("⚠️ Не удалось извлечь город, передаём AI")
                            continue
                        }
                    }
                }

                // Определяем навык по типу команды
                val skill = when (command) {
                    "open_file" -> skillRegistry.getSkill("open_file")
                    "web_search" -> skillRegistry.getSkill("web_search")
                    "weather" -> skillRegistry.getSkill("weather")
                    "execute_python", "run_python" -> skillRegistry.getSkill("python")
                    "app_launch" -> {
                        // Передаём оригинальный запрос для AI-анализа названия приложения
                        mutableParams["query"] = query
                        println("📱 AppLaunchSkill: передаю запрос '$query'")
                        skillRegistry.getSkill("app_launch")
                    }
                    "home_scan" -> null // handled below
                    else -> skillRegistry.findSkill(Intent.FILE_OPERATION, query, mutableParams)
                }
                mutableParams["query"] = query
                // Handle home_scan directly (skill not in registry yet)
                if (command == "home_scan") {
                    println("🔥 home_scan: calling HomeSkill.execute()")
                    println("🔥 home_scan: params=$mutableParams")
                    val result = homeSkill.execute(mutableParams)
                    println("🔥 home_scan: result=${(result as? SkillResult.Success)?.message?.take(60) ?: result}")
                    if (result is SkillResult.Error) {
                        println("🔥 home_scan: ERROR: ${result.message} ${result.details}")
                    }
                    return when (result) {
                        is SkillResult.Success -> AgentResponse.Success(
                            answer = result.message,
                            thoughts = listOf("Детерминированный запрос: сканирование сети"),
                            actions = emptyList()
                        )
                        is SkillResult.Error -> AgentResponse.Error(
                            error = result.message,
                            details = result.details
                        )
                        is SkillResult.ConfirmationRequired -> AgentResponse.Error(
                            error = result.question,
                            details = "Требуется подтверждение пользователя"
                        )
                    }
                }
                if (skill != null) {
                    val result = skill.execute(mutableParams)
                    return when (result) {
                        is SkillResult.Success -> AgentResponse.Success(
                            answer = result.message,
                            thoughts = listOf("Детерминированный запрос: ${patternList.first()}"),
                            actions = emptyList()
                        )
                        is SkillResult.Error -> AgentResponse.Error(
                            error = result.message,
                            details = result.details
                        )
                        is SkillResult.ConfirmationRequired -> AgentResponse.Error(
                            error = result.question,
                            details = "Требуется подтверждение пользователя"
                        )
                    }
                }
            }
        }

        return null
    }

    /**
     * Обрабатывает запрос пользователя.
     */
    suspend fun processQuery(
        query: String,
        context: String = "",
        fileAttachments: List<com.pai.android.data.model.Attachment> = emptyList(),
        autoApprove: Boolean = false
    ): AgentResponse {
        return withContext(defaultDispatcher) {
            try {
                println("🧠 DecisionEngine: обрабатываю запрос: '$query'")

                // Add user file attachments info to the query
                val fileAttachInfo = if (fileAttachments.isNotEmpty()) {
                    val names = fileAttachments.joinToString(", ") { a ->
                        a.fileName + if (a.fileSize > 0) " (${a.fileSize / 1024} KB)" else ""
                    }
                    println("📎 User attached files: $names")
                    names
                } else null

                                // Check for pending task notification
                val notifCtx = persistentContext.load()
                if (notifCtx != null && notifCtx.pendingTaskNotification != null) {
                    val notifText = notifCtx.pendingTaskNotification!!
                    // Skip stale/error notifications (when AI was unavailable)
                    val isErrorNotif = notifText.contains("временный ответ") || notifText.contains("AI недоступен") ||
                        notifText.contains("Failed to connect") || notifText.length < 20
                    if (isErrorNotif) {
                        println("Skipping stale/error notification: " + notifText.take(60))
                        persistentContext.save(notifCtx.copy(pendingTaskNotification = null))
                    } else if (com.pai.android.agent.DecisionEngine.notificationDelivered) {
                        println("Clearing already delivered notification")
                        persistentContext.save(notifCtx.copy(pendingTaskNotification = null))
                    } else {
                        println("Pending notification found: " + notifText.take(80))
                        persistentContext.save(notifCtx.copy(pendingTaskNotification = null))
                        com.pai.android.agent.DecisionEngine.Companion.pendingNotificationResult = notifText
                    }
                }
                com.pai.android.agent.DecisionEngine.notificationDelivered = false

                // Получаем контекст устройства (локация, батарея, уведомления, задачи)
                val deviceContext = try {
                    contextEngine.formatForPrompt()
                } catch (e: Exception) {
                    println("⚠️ ContextEngine error: ${e.message}")
                    ""
                }

                // Добавляем сохранённые факты из памяти (адреса, имя, предпочтения — всё, что пользователь просил запомнить)
                val memoryFactsContext = try {
                    memoryRepository.formatFactsForPromptWithScope(query)
                } catch (e: Exception) {
                    println("⚠️ Memory facts error: ${e.message}")
                    ""
                }

                // Склеиваем контекст устройства с пользовательским контекстом и фактами
                val combinedContext = buildString {
                    if (deviceContext.isNotBlank()) appendLine(deviceContext)
                    if (memoryFactsContext.isNotBlank()) appendLine(memoryFactsContext)
                    if (context.isNotBlank()) appendLine(context)
                }.trimEnd()

                // ── Vision: если есть изображения — отправляем напрямую, минуя ReAct ──
                val imageAttachments = fileAttachments.filter {
                    it.type == com.pai.android.data.model.AttachmentType.IMAGE
                }
                if (imageAttachments.isNotEmpty()) {
                    println("📸 Vision: ${imageAttachments.size} image(s), sending to vision model")
                    val visionQuery = if (query.isNotBlank()) query else "Опиши, что изображено на картинке"
                    try {
                        val visionResult = aiRepository.sendMessageWithAttachments(
                            messages = listOf(
                                com.pai.android.data.model.Message(
                                    chatId = "",
                                    role = com.pai.android.data.model.MessageRole.USER,
                                    content = visionQuery
                                )
                            ),
                            attachments = imageAttachments,
                            systemPrompt = "Ты — AI с поддержкой зрения. Ответь на русском языке."
                        )
                        if (visionResult.isSuccess) {
                            val text = visionResult.getOrThrow().text
                            println("📸 Vision response: ${text.take(200)}")
                            persistentContext.saveInteraction(query, text)
                            return@withContext AgentResponse.Success(
                                answer = text,
                                thoughts = listOf("Vision processing"),
                                actions = emptyList()
                            )
                        }
                    } catch (e: Exception) {
                        println("📸 Vision error, falling back: ${e.message}")
                    }
                }

                // Эвристика: запросы на создание проектов → сразу в TaskQueue
                val projectKeywords = listOf("создай проект", "разработай проект", "напиши проект", "создай веб-приложение", "create project", "build project", "start project", "make project", "new project", "внеси", "исправь", "почини", "доделай", "дополни", "обнови", "fix", "update", "correct", "improve", "создай скрипт", "напиши скрипт")
                val isProjectRequest = projectKeywords.any { query.lowercase().contains(it) }
                if (isProjectRequest) {
                    // Check for incomplete project first
                    val existingCtx = persistentContext.load()
                    if (existingCtx?.activeProjectId != null) {
                        val existingProject = projectManager.getProject(existingCtx.activeProjectId!!)
                        if (existingProject != null && existingProject.status != ProjectManager.ProjectStatus.COMPLETED) {
                            val wantsResume = classifyContinuationIntent(query, existingProject.name, existingProject.currentStepIndex, existingProject.steps.size)
                            if (wantsResume) {
                                println("🔄 Продолжаем проект: ${existingProject.name}")
                                val result = taskQueue.execute(existingCtx.activeProjectId!!, "Resume: ${existingProject.name}")
                                when (result) {
                                    is AgentResponse.Success -> persistentContext.saveInteraction(query, result.answer)
                                    is AgentResponse.Error -> {
                                        val errCtx = (persistentContext.load() ?: PersistentContext.AgentContext()).copy(pendingResumeProjectId = existingProject.id)
                                        persistentContext.save(errCtx)
                                        persistentContext.saveInteraction(query, "Error: ${result.error}")
                                    }
                                }
                                return@withContext result
                            }
                        }
                    }
                    // If it is a modify request, use last project as base
                    val ctx = persistentContext.load()
                    val activeId = ctx?.activeProjectId
                    if (activeId != null && (query.lowercase().contains("внеси") || query.lowercase().contains("исправь") || query.lowercase().contains("fix") || query.lowercase().contains("update"))) {
                        val existing = projectManager.getProject(activeId)
                        if (existing != null) {
                            println("📋 Модификация проекта " + existing.name)
                            val modQuery = "Continue " + existing.name + ". Existing steps: " + existing.steps.joinToString("; ") { it.description.take(30) + (if (it.status == ProjectManager.StepStatus.DONE) " ✅" else "") } + ". User: " + query
                            val result = taskQueue.planAndExecute(modQuery)
                            when (result) {
                                is AgentResponse.Success -> persistentContext.saveInteraction(query, result.answer)
                                is AgentResponse.Error -> persistentContext.saveInteraction(query, "Error: " + result.error)
                            }
                            return@withContext result
                        }
                    }
                    println("📋 Новый запрос на проект, создаю")
                    val result = taskQueue.planAndExecute(query, autoApprove)
                    when (result) {
                        is AgentResponse.Success -> {
                            persistentContext.saveInteraction(query, result.answer)
                            // Save project ID for resumption
                            val projects = projectManager.listProjects()
                            val latest = projects.maxByOrNull { it.createdAt }
                            if (latest != null) {
                                // If plan was shown (not executed yet), set pending approval
                                val isPlan = result.answer.contains("План") && result.answer.contains("Утверждаешь")
                                val cur = persistentContext.load() ?: PersistentContext.AgentContext()
                                if (isPlan) {
                                    persistentContext.save(cur.copy(activeProjectId = latest.id, pendingApprovalProjectId = latest.id, pendingApprovalPlan = result.answer))
                                } else {
                                    persistentContext.save(cur.copy(activeProjectId = latest.id))
                                }
                            }
                        }
                        is AgentResponse.Error -> {
                            val projects = projectManager.listProjects()
                            val latest = projects.maxByOrNull { it.createdAt }
                            if (latest != null) {
                                val errCtx = (persistentContext.load() ?: PersistentContext.AgentContext()).copy(activeProjectId = latest.id, pendingResumeProjectId = latest.id)
                                persistentContext.save(errCtx)
                            }
                            persistentContext.saveInteraction(query, "Error: ${result.error}")
                        }
                    }
                    return@withContext result
                }

                // Эвристика сложности: длинные запросы → TaskQueue
                val isComplex = query.length > 150

                if (isComplex) {
                    // Проверяем активный проект
                    val ctx = persistentContext.load()
                    if (ctx?.activeProjectId != null) {
                        val activeProject = projectManager.getProject(ctx.activeProjectId!!)
                        if (activeProject != null && activeProject.status != ProjectManager.ProjectStatus.COMPLETED) {
                            val wantsResume = classifyContinuationIntent(query, activeProject.name, activeProject.currentStepIndex, activeProject.steps.size)
                            if (wantsResume) {
                                println("🔄 Продолжаем проект: ${activeProject.name} (${activeProject.currentStepIndex}/${activeProject.steps.size})")
                                val result = taskQueue.execute(ctx.activeProjectId!!, "Continue project: ${activeProject.name}")
                                when (result) {
                                    is AgentResponse.Success -> persistentContext.saveInteraction(query, result.answer)
                                    is AgentResponse.Error -> persistentContext.saveInteraction(query, "Error: ${result.error}")
                                }
                                return@withContext result
                            }
                        }
                    }
                    println("📋 Сложный запрос, передаю в TaskQueue")
                    val result = taskQueue.planAndExecute(query, autoApprove)
                    when (result) {
                        is AgentResponse.Success -> persistentContext.saveInteraction(query, result.answer)
                        is AgentResponse.Error -> persistentContext.saveInteraction(query, "Error: ${result.error}")
                    }
                    return@withContext result
                }


                // Early exit для детерминированных запросов
                val deterministicResult = handleDeterministicQuery(query)
                if (deterministicResult != null) {
                    return@withContext deterministicResult
                }

                // Handle adding contacts (e.g., "добавь контакт", "создай контакт")
                val addContactMatch = Regex("(?:добавь|создай|сохрани).*(?:контакт|номер).*?(?:телефон|номер)?\\s*([\\+\\d][\\d\\s\\(\\)\\-]{5,})", RegexOption.IGNORE_CASE).find(query)
                if (addContactMatch != null) {
                    val number = addContactMatch.groupValues[1].trim()
                    val nameMatch = Regex("(?:контакт|номер|телефон)\\s*([а-яА-ЯЁёa-zA-Z]+)", RegexOption.IGNORE_CASE).find(query)
                    val name = nameMatch?.groupValues?.get(1)?.trim() ?: "Контакт"
                    val contactsSkill = skillRegistry.getSkill("contacts")
                    if (contactsSkill != null) {
                        val result = contactsSkill.execute(mapOf("command" to "contacts_add", "name" to name, "phone" to number))
                        if (result is SkillResult.Success) {
                            return@withContext AgentResponse.Success(
                                answer = result.message,
                                thoughts = listOf("contact add"), actions = emptyList()
                            )
                        }
                    }
                }
                // Handle skill editing via LLM (triggers, logic, etc.)
                val editSkillMatch = Regex("(?:добавь|измени|удали|обнови|отредактируй).*?(?:навык|скилл)\\s*(\\w+)", RegexOption.IGNORE_CASE).find(query)
                if (editSkillMatch != null && skillsDirectory.isNotBlank()) {
                    val skillId = editSkillMatch.groupValues[1].lowercase()
                    try {
                        val manifestFile = java.io.File(skillsDirectory, skillId + ".json")
                        val scriptFile = java.io.File(skillsDirectory, skillId + ".py")
                        if (manifestFile.exists()) {
                            val currentManifest = manifestFile.readText()
                            val currentScript = if (scriptFile.exists()) scriptFile.readText() else ""
                            // Ask LLM to generate the updated version
                            val editPrompt = "Edit this Python skill. Skill name: " + skillId + ". User request: " + query + "\n\n" +
                                "Current manifest JSON:\n" + currentManifest + "\n\n" +
                                "Current Python script:\n" + currentScript + "\n\n" +
                                "Output EXACTLY this format:\n" +
                                "---MANIFEST---\n{updated JSON, keep all existing fields, only modify what user asked}\n" +
                                "---SCRIPT---\n{updated Python code, same variable \"query\", print result to stdout}\n" +
                                "Keep ALL existing functionality. Only add/modify what user requested."
                            println("editSkill: sending to LLM...")
                            val editResp = aiRepository.sendMessage(
                                messages = listOf(com.pai.android.data.model.Message.createUserMessage("edit", editPrompt)),
                                systemPrompt = "You edit Python skills. Keep all existing functionality. Output ONLY ---MANIFEST--- and ---SCRIPT---.",
                                memoryContext = ""
                            )
                            val editText = editResp.getOrNull()?.text ?: ""
                            val m = Regex("---MANIFEST---\\s*(\\{.+?\\})\\s*---SCRIPT---", setOf(RegexOption.DOT_MATCHES_ALL)).find(editText)
                            val s = Regex("---SCRIPT---\\s*(.+)", setOf(RegexOption.DOT_MATCHES_ALL)).find(editText)
                            if (m != null && s != null) {
                                val newManifest = m.groupValues[1]
                                val newScript = s.groupValues[1].trim()
                                manifestFile.writeText(newManifest)
                                if (newScript.isNotBlank()) scriptFile.writeText(newScript)
                                // Re-register
                                val json = org.json.JSONObject(newManifest)
                                val manifest = com.pai.android.agent.skills.SkillManifest(
                                    id = json.getString("name"), name = json.optString("name", skillId),
                                    version = json.optString("version", "1.0"), description = json.optString("description", ""),
                                    instructions = "", endpoint = "", type = "python",
                                    mainScript = json.optString("mainScript", skillId + ".py"),
                                    triggers = json.optJSONArray("triggers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                                    timeout = json.optInt("timeout", 30), enabled = true
                                )
                                val old = skillRegistry.getSkill("ext_" + skillId)
                                if (old != null) skillRegistry.unregister(old.name)
                                skillRegistry.register(com.pai.android.agent.skills.ExternalSkillAdapter(manifest, skillRegistry.getSkill("python"), skillsDirectory))
                                println("editSkill: updated " + skillId)
                                return@withContext AgentResponse.Success(
                                    answer = "Skill \"" + skillId + "\" updated! Triggers: " + manifest.triggers.joinToString(", "),
                                    thoughts = listOf("skill edited"), actions = emptyList()
                                )
                            }
                        } else {
                            println("editSkill: manifest not found: " + manifestFile.absolutePath)
                        }
                    } catch (e: Exception) {
                        println("editSkill error: " + e.message)
                    }
                }
                // Try creating a local skill
                val createSkillMatch = Regex("(создай|обнови|исправь|пересоздай) навык [\"']?(\\w+)[\"']?", RegexOption.IGNORE_CASE).find(query)
                if (createSkillMatch != null) {
                    val skillName = createSkillMatch.groupValues[2].lowercase()
                    val userDesc = query.substring(query.indexOf(skillName) + skillName.length).trim()
                    println("createSkill: generating '" + skillName + "' via LLM (desc: " + userDesc.take(60) + ")")
                    if (skillsDirectory.isNotBlank()) {
                        try {
                            val genPrompt = "Generate a local Python skill named \"" + skillName + "\". User request: " + query + "\n\n" +
                                "Output EXACTLY this format:\n" +
                                "---MANIFEST---\n" +
                                "{JSON with: name, description, version, type=\"python\", mainScript=\"" + skillName + ".py\", triggers=[words that activate this skill], timeout=30, enabled=true}\n" +
                                "---SCRIPT---\n" +
                                "Python code that uses variable \"query\" (user input) and prints result.\n\n" +
                                "Example for weather check:\n" +
                                "---MANIFEST---\n" +
                                "{\"name\": \"weather\", \"description\": \"Shows weather\", \"version\": \"1.0\", \"type\": \"python\", \"mainScript\": \"weather.py\", \"triggers\": [\"weather\", \"temp\"], \"timeout\": 30, \"enabled\": true}\n" +
                                "---SCRIPT---\n" +
                                "import requests\\n" +
                                "url = f\"https://wttr.in/{query}?format=%25C+%25t\"\\n" +
                                "print(requests.get(url, timeout=5).text)"
                            println("createSkill: sending to LLM...")
                            val genResp = aiRepository.sendMessage(
                                messages = listOf(com.pai.android.data.model.Message.createUserMessage("gen", genPrompt)),
                                systemPrompt = "You generate Python skills for Android assistant. Python 3.12 with requests, json, datetime, re, math, random. CRITICAL: The script MUST call functions and print results to stdout. Do NOT just define functions. Output ONLY ---MANIFEST--- and ---SCRIPT---. No markdown.",
                                memoryContext = ""
                            )
                            val genText = genResp.getOrNull()?.text ?: ""
                            if (genText.isBlank()) throw Exception("Empty LLM response")
                            println("createSkill: LLM responded (" + genText.length + " chars)")
                            val manifestMatch = Regex("---MANIFEST---\\s*(\\{.+?\\})\\s*---SCRIPT---", setOf(RegexOption.DOT_MATCHES_ALL)).find(genText)
                            val scriptMatch = Regex("---SCRIPT---\\s*(.+)", setOf(RegexOption.DOT_MATCHES_ALL)).find(genText)
                            if (manifestMatch == null || scriptMatch == null) throw Exception("Cannot parse LLM output")
                            var manifestJson = manifestMatch.groupValues[1]
                            val scriptCode = scriptMatch.groupValues[1].trim()
                            manifestJson = manifestJson.replace(Regex("\"name\"\\s*:\\s*\"[^\\\\\"]+\""), "\"name\": \"" + skillName + "\"")
                            manifestJson = manifestJson.replace(Regex("\"mainScript\"\\s*:\\s*\"[^\\\\\"]+\""), "\"mainScript\": \"" + skillName + ".py\"")
                            manifestJson = manifestJson.replace(Regex("\"type\"\\s*:\\s*\"[^\\\\\"]+\""), "\"type\": \"python\"")
                            if (!manifestJson.contains("\"enabled\"")) manifestJson = manifestJson.trimEnd('}') + ",\"enabled\": true}"
                            val skillsDir = java.io.File(skillsDirectory); skillsDir.mkdirs()
                            java.io.File(skillsDir, skillName + ".json").writeText(manifestJson)
                            java.io.File(skillsDir, skillName + ".py").writeText(scriptCode)
                            println("createSkill: files saved")
                            val json = org.json.JSONObject(manifestJson)
                            val manifest = com.pai.android.agent.skills.SkillManifest(
                                id = json.getString("name"), name = json.optString("name", skillName),
                                version = json.optString("version", "1.0"), description = json.optString("description", ""),
                                instructions = "", endpoint = "", type = "python",
                                mainScript = json.optString("mainScript", skillName + ".py"),
                                triggers = json.optJSONArray("triggers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                                timeout = json.optInt("timeout", 30), enabled = true
                            )
                            skillRegistry.register(com.pai.android.agent.skills.ExternalSkillAdapter(manifest, skillRegistry.getSkill("python"), skillsDirectory))
                            println("createSkill: registered")
                            return@withContext AgentResponse.Success(
                                answer = "✅ Skill \"" + skillName + "\" created! Triggers: " + manifest.triggers.joinToString(", "),
                                thoughts = listOf("local skill created"), actions = emptyList()
                            )
                        } catch (e: Exception) {
                            println("createSkill error: " + e.message)
                        }
                    }
                }// Try external skills before reactLoop (news_digest, youtube_watcher, etc.)
                val extParams = mapOf("query" to query, "q" to query)
                val extSkill = skillRegistry.findSkill(Intent.SEARCH, query, extParams)
                if (extSkill != null && extSkill.name.startsWith("ext_")) {
                    val extResult = extSkill.execute(extParams)
                    if (extResult is SkillResult.Success) {
                        return@withContext AgentResponse.Success(
                            answer = extResult.message,
                            thoughts = listOf("via: " + extSkill.name),
                            actions = emptyList()
                        )
                    }
                }

                // ── Phase 1: Universal planning (replaces Intent Router) ──
                // For every non-trivial query, check if planning is needed via agentPlanner.
                // This handles both single-step immediate actions AND multi-step tool sequences.

                // ── Интервальный мониторинг (watch task) ──
                // Универсальный: любой запрос с "каждые N минут/час" + действие
                val queryLower = query.lowercase()
                
                // Паттерны интервала (мультиязычные) — определяем ЧТО создаём
                val intervalPattern = Regex(
                    "(?:кажд(?:ые?|ый)\\s*|раз\\s*в\\s*|every|each)\\s*(\\d+)?\\s*" +
                    "(?:мин(?:ут)?(?:ы|а)?|час(?:ов)?|полчас(?:а)?|раз|" +
                    "min(?:ute)?s?|hour(?:s)?|half.?hour)",
                    RegexOption.IGNORE_CASE
                )
                val intervalMatch = intervalPattern.find(query)
                val hasIntervalIntent = intervalMatch != null || 
                    Regex("\\d+\\s*min", RegexOption.IGNORE_CASE).containsMatchIn(query) ||
                    query.contains("полчас") || query.contains("half")
                
                // Если запрос про повторение — создаём interval-задачу
                if (hasIntervalIntent) {
                    val minutes = intervalMatch?.groupValues?.getOrNull(1)?.toIntOrNull() 
                        ?: Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE).find(query)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: if (queryLower.contains("полчас") || queryLower.contains("half") || query.contains("30")) 30
                        else if (queryLower.contains("час") && !queryLower.contains("полчас")) 60
                        else if (queryLower.contains("hour") && !queryLower.contains("half")) 60
                        else 5
                    
                    // Интервальная задача — используем исходный запрос как prompt для LLM
                    // LLM сама разберётся с падежами, MIME и форматированием
                    val cleanQuery = query.replace(intervalPattern, "").trim()
                        .removePrefix(",").removePrefix(".").trim()
                    val taskPrompt = if (cleanQuery.length > 10) cleanQuery else query
                    
                    val fullTaskId = "int_${System.currentTimeMillis()}"
                    val taskName = taskPrompt.take(50).replace("\n", " ")
                    val task = ScheduledTask(
                        id = fullTaskId,
                        name = taskName,
                        prompt = taskPrompt,
                        cronExpression = "",
                        enabled = true,
                        intervalMinutes = minutes
                    )
                    taskScheduler.addTask(task)
                    println("📌 Интервальная задача: interval=${minutes}min prompt=${taskPrompt.take(60)}")
                    
                    return@withContext AgentResponse.Success(
                        answer = "✅ Задача создана: повтор каждые $minutes минут.",
                        thoughts = listOf("interval task created"),
                        actions = emptyList()
                    )
                }

                // ── Universal planning: detect multi-step tasks ──
                // Check if the query requires multiple tools (e.g. "create doc and send email")
                // Skip planning for trivial queries (greetings, short chit-chat)
                val isTrivial = query.length < 15 || 
                    Regex("^(привет|здравствуй|пока|ок|ok|да|нет|hi|hello|bye|thanks|спасиб)", RegexOption.IGNORE_CASE).containsMatchIn(query.trim())
                val plan = if (isTrivial) {
                    AgentPlan(requiresPlanning = false, steps = emptyList(), reasoning = "Trivial query, no plan needed")
                } else {
                    agentPlanner.plan(query, combinedContext)
                }
                if (plan.steps.isNotEmpty()) {
                    val planLabel = if (plan.steps.size > 1) "Multi-step" else "Single-step"
                    println("📋 $planLabel plan: ${plan.steps.size} steps — ${plan.reasoning}")
                    val results = mutableMapOf<String, String>()
                    var planError: String? = null
                    var planAnswer = StringBuilder()
                    var mainContent: String? = null  // main response content (e.g. from ai_chat)

                    for ((stepIdx, step) in plan.steps.withIndex()) {
                        println("▶️ Plan step ${stepIdx + 1}/${plan.steps.size}: ${step.description}")
                        
                        // Resolve {variable}, {var.field}, {var[N].field} references in params
                        val resolvedParams = step.params.mapValues { (_, v) ->
                            var value = v.toString()
                            // Replace {var[N].field} (bracket notation) — AI часто генерирует такой формат
                            val bracketPattern = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\[([0-9]+)\\]\\.([a-zA-Z_][a-zA-Z0-9_]+)\\}")
                            value = bracketPattern.replace(value) { match ->
                                val varName = match.groupValues[1]
                                val fieldName = match.groupValues[3]
                                // Try original field name first, then aliases (lat↔latitude, lon↔longitude)
                                val key = "${varName}.${fieldName}"
                                val shortKey = "${varName}.${fieldName.replace("latitude", "lat").replace("longitude", "lon")}"
                                val longKey = "${varName}.${fieldName.replace("lat", "latitude").replace("lon", "longitude")}"
                                results[key] ?: results[shortKey] ?: results[longKey] ?: match.value
                            }
                            // Replace {var.field} and {var.0.field} (multi-level dot notation)
                            val dotPattern = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\.([0-9]+\\.)*([a-zA-Z_][a-zA-Z0-9_]*)\\}")
                            value = dotPattern.replace(value) { match ->
                                val varName = match.groupValues[1]
                                val fieldName = match.groupValues.last()
                                val key = "${varName}.${fieldName}"
                                val shortKey = "${varName}.${fieldName.replace("latitude", "lat").replace("longitude", "lon")}"
                                val longKey = "${varName}.${fieldName.replace("lat", "latitude").replace("lon", "longitude")}"
                                results[key] ?: results[shortKey] ?: results[longKey] ?: match.value
                            }
                            // Replace {variable_name} with actual results
                            val varPattern = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")
                            varPattern.replace(value) { match ->
                                val varName = match.groupValues[1]
                                results[varName] ?: match.value
                            }
                        }
                        
                        // Find skill by name
                        val skillName = step.skillName
                        val skill = when (skillName) {
                            "weather" -> skillRegistry.getSkill("weather")
                            "web_search" -> skillRegistry.getSkill("web_search")
                            "web_fetch" -> skillRegistry.getSkill("web_fetch")
                            "file_system" -> skillRegistry.getSkill("file_system")
                            "email" -> skillRegistry.getSkill("email")
                            "office" -> skillRegistry.getSkill("office")
                            "contacts" -> skillRegistry.getSkill("contacts")
                            "sms" -> skillRegistry.getSkill("sms")
                            "call" -> skillRegistry.getSkill("call")
                            "calendar" -> skillRegistry.getSkill("calendar")
                            "geo" -> skillRegistry.getSkill("geo")
                            "clipboard" -> skillRegistry.getSkill("clipboard")
                            "task_scheduler" -> skillRegistry.getSkill("task_scheduler")
                            "location" -> skillRegistry.getSkill("location") ?: toolRegistry.getTool("location")?.let { ToolSkillAdapter(it) }
                            "home" -> skillRegistry.getSkill("home")
                            "ai_chat" -> null // handled below
                            else -> skillRegistry.getSkill(skillName) ?: toolRegistry.getTool(skillName)?.let { ToolSkillAdapter(it) }
                        }
                        
                        if (skillName == "ai_chat" || skill == null) {
                            // Chat: just pass to AI directly
                            try {
                                val chatPrompt = resolvedParams["query"]?.toString() ?: query
                                val chatResp = aiRepository.sendMessage(
                                    messages = listOf(Message.createUserMessage("plan_step_$stepIdx", chatPrompt)),
                                    systemPrompt = "You are executing a step in a multi-step plan. Reply helpfully.",
                                    memoryContext = context
                                )
                                val chatText = chatResp.getOrThrow().text
                                if (step.outputVariable != null) results[step.outputVariable!!] = chatText
                                // Save ai_chat result as main content; don't add step markers to final answer
                                mainContent = chatText
                            } catch (e: Exception) {
                                planError = "AI chat error: ${e.message}"
                                break
                            }
                        } else {
                            // Execute skill
                            // Map generic AgentPlanner params to tool-specific params
                            val execParams = when (skillName) {
                                "location" -> {
                                    // LocationTool expects "action" not "command"
                                    val m = resolvedParams.toMutableMap()
                                    m.remove("command")
                                    if (!m.containsKey("action")) m["action"] = "current"
                                    m
                                }
                                else -> resolvedParams
                            }
                            println("?? DecisionEngine: executing ${skillName} with params: ${execParams.mapValues { (k,v) -> v.toString().take(60) }}")
                            val result = skill.execute(execParams)
                            when (result) {
                                is SkillResult.Success -> {
                                    val resultText = result.message
                                    val resultPath = result.data?.get("path")?.toString() ?: ""
                                    // Store structured data fields so {var.field} works
                                    if (result.data != null) {
                                        for ((dataKey, dataVal) in result.data) {
                                            val storageKey = if (step.outputVariable != null) {
                                                "${step.outputVariable}.${dataKey}"
                                            } else {
                                                "step${stepIdx}.${dataKey}"
                                            }
                                            results[storageKey] = dataVal.toString()
                                        }
                                    }
                                    println("?? DecisionEngine: stored results keys for step ${stepIdx}: ${results.keys.filter { it.startsWith(step.outputVariable ?: "step${stepIdx}") }.take(10)}")
                                    if (step.outputVariable != null) {
                                        results[step.outputVariable!!] = resultPath.ifBlank { resultText }
                                    }
                                    // Always store result for fallback, even without outputVariable
                                    val resultKey = step.outputVariable ?: "step${stepIdx}_result"
                                    results[resultKey] = resultPath.ifBlank { resultText }
                                    if (resultPath.isNotBlank()) results["${resultKey}_path"] = resultPath
                                }
                                is SkillResult.Error -> {
                                    planError = "${skillName}: ${result.message}"
                                    break
                                }
                                is SkillResult.ConfirmationRequired -> {
                                    planError = "${skillName}: confirmation required — ${result.question}"
                                    break
                                }
                            }
                        }
                    }
                    
                    if (planError != null) {
                        println("❌ Plan failed: $planError")
                        // Fall back to ReAct
                        return@withContext twoPhaseReactLoop(
                            query = query + " (plan failed: $planError)",
                            context = combinedContext,
                            fileAttachmentNames = fileAttachments.map { it.fileName }
                        )
                    }
                    
                    // Build user-facing answer:
                    // - Multi-step with ai_chat → main content + compact summary
                    // - Single-step (location, web_search, etc.) → show the result directly
                    // - Multi-step without ai_chat → compact summary
                    val finalAnswer = buildString {
                        if (mainContent != null) {
                            appendLine(mainContent!!)
                            appendLine()
                        }
                        // Determine which steps are "silent" (shouldn't appear as summary markers)
                        val silentSkills = setOf("ai_chat", "web_search", "web_fetch", "location")
                        // Terminal actions that warrant a brief summary marker when they're the main action
                        val terminalSkills = setOf("office", "email", "email_send", "sms", "call", "task_scheduler")
                        
                        if (mainContent == null && plan.steps.size == 1) {
                            // Single non-chat step: show its result directly
                            val singleResult = results["step0_result"]
                            if (!singleResult.isNullOrBlank()) {
                                append(singleResult.trimEnd())
                            } else {
                                // Still nothing? show description as fallback
                                val desc = plan.steps[0].description.replaceFirstChar { c -> c.uppercaseChar() }
                                append("✅ $desc")
                            }
                        } else {
                            // Multi-step or has mainContent: show compact summary of terminal actions only
                            val actionsTaken = plan.steps.filter { it.skillName in terminalSkills }
                            if (actionsTaken.isNotEmpty()) {
                                val desc = actionsTaken.joinToString(", ") { step ->
                                    step.description.replaceFirstChar { c -> c.uppercaseChar() }
                                }
                                append("✅ $desc")
                            }
                        }
                    }
                    val finalAnswerTrimmed = finalAnswer.trimEnd()
                    println("✅ Plan completed: ${plan.steps.size} steps")
                    persistentContext.saveInteraction(query, finalAnswerTrimmed)
                    return@withContext AgentResponse.Success(
                        answer = finalAnswerTrimmed,
                        thoughts = listOf("plan executed (${plan.steps.size} steps): ${plan.reasoning}"),
                        actions = emptyList()
                    )
                }

                // Route remaining queries to two-phase ReAct loop.
                return@withContext twoPhaseReactLoop(
                    query = query,
                    context = combinedContext,
                    fileAttachmentNames = fileAttachments.map { it.fileName }
                )
            } catch (e: Exception) {
                println("❌ DecisionEngine ошибка: ${e.message}")
                e.printStackTrace()
                // Сохраняем контекст, чтобы можно было возобновить
                val currentCtx = persistentContext.load() ?: PersistentContext.AgentContext()
                persistentContext.save(currentCtx.copy(
                    lastQuery = query,
                    updatedAt = System.currentTimeMillis()
                ))
                AgentResponse.Error(
                    error = "Ошибка обработки запроса",
                    details = e.message ?: "Неизвестная ошибка"
                )
            }
        }
    }

    /**
     * Fallback на ReActAgent, если навык не справился с задачей.
     */
    // ─── ReAct loop ───

    private val reactToolMap = mapOf(
        "read_file" to "file_system",
        "list_files" to "file_system",
        "write_file" to "file_system",
        "append_file" to "file_system",
        "delete_file" to "file_system",
        "create_folder" to "file_system",
        "web_search" to "web_search",
        "web_fetch" to "web_fetch",
        "execute_python" to "python",
        "weather" to "weather",
        "app_launch" to "app_launch",
        "email_check" to "email",
        "email_list" to "email",
        "email_read" to "email",
        "email_send" to "email",
        "contacts_search" to "contacts",
        "contacts_add" to "contacts",
        "call_dial" to "call",
        "call_call" to "call",
        "sms_send" to "sms",
        "task_scheduler" to "tool_task_scheduler",
        "notif_listener" to "tool_notif_listener",
        "location" to "tool_location",
        "get_context" to "tool_get_context",
        "clipboard" to "tool_clipboard",
        "calendar" to "tool_calendar",
        "office" to "office",
        "pptx" to "office",
        "powerpoint" to "office",
        "word" to "office",
        "excel" to "office",
        "geo" to "geo",
        "maps" to "tool_maps"
    )

    private val reactToolDescriptions: String by lazy {
        // Names from reactToolMap + actual skill requirements
        """
"- read_file: read a file. Required: path=filename""" +
"- write_file: write content to a file. Required: path=filename, content=text_to_write\n" +
"- list_files: list files in workspace. Optional: path=subfolder, recursive=true\n" +
"- office/word/excel/pptx/powerpoint: create/read MS Office files. Use this to create Word, Excel, or PowerPoint. Examples: 'office: create a document with table', 'excel: create spreadsheet with names and ages', 'pptx: make a presentation about quantum physics'\n" +
"- web_search: search the internet. Required: query=search terms\n" +
"- web_fetch: fetch a URL. Required: url=http://...\n" +
"- execute_python: run Python code. Required: code=python_code\n" +
"- weather: get weather. Required: query=city name\n" +
  "- email_check: check for new unread emails. Shows total count and list.\n" +
  "- email_list: search emails. Required: query=keywords. Optional: limit=number.\n" +
  "  Returns sender, subject, date, and [UID=X] for each email.\n" +
  "  CRITICAL: When you find an email, use email_read with its UID.\n" +
  "  NEVER invent email content. email_read output is the ONLY source.\n" +
  "  Display content exactly as returned, do not modify or improve it.\n" +
  "- email_read: read full email content. Required: uid=UID_NUMBER from email_list.\n" +
  "  Returns complete email body. Show this EXACT content to user.\n" +
  "- email_send: send an email. Required: to=recipient, subject=subject, body=message. Optional: file_path=path/to/file (attach a file), attachment_paths=path1,path2 (multiple files, comma-separated).\n" +
  "  Optional: cc=cc_recipient\n" +
"- app_launch: open an app. Required: app=app_name" + 
"- notif_listener: check Android notification listener status. action=status or action=open_settings\n" +
"- location: FRESH GPS coordinates. MANDATORY: use action=current to get live GPS fix. action=last_known for cached, action=status for GPS on/off only. Home address from memory (New York) is NOT current location - use action=current for real position.\n" +
"- get_context: get full device context (time, location, battery, tasks). action=full or action=quick\n" +
"- clipboard: read/write device clipboard content. Use action=read when user asks about clipboard/buffer. Provides actual text in clipboard.\n" +
"- calendar: read Android calendar events. Use action=list_upcoming to get upcoming events, action=search to search by text, action=create to add new event."
    }

    private fun parseToolCall(text: String): Pair<String, Map<String, String>>? {
        val regex = Regex("\\[TOOL_CALL:\\s*(\\w+)\\]", RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: return null
        val toolName = match.groupValues[1].lowercase()
        val afterCall = text.substring(match.range.last + 1).trim()
        val params = mutableMapOf<String, String>()

        // Check for multi-line content with ---CONTENT--- / ---END--- delimiter
        val contentStartKey = "---CONTENT---"
        val contentEndKey = "---END---"
        val contentStart = afterCall.indexOf(contentStartKey)
        val contentEnd = if (contentStart >= 0) {
            val end = afterCall.indexOf(contentEndKey, contentStart)
            if (end < 0) afterCall.length else end
        } else -1

        if (contentStart >= 0) {
            // Extract content between delimiters
            val content = afterCall.substring(contentStart + contentStartKey.length, contentEnd).trim()
            if (content.isNotBlank()) {
                params["content"] = content
            }
            // Parse one-line params from before the delimiter
            val beforeContent = afterCall.substring(0, contentStart).trim()
            val paramRegex = Regex("(\\w+)\\s*=\\s*", RegexOption.IGNORE_CASE)
            val paramMatches = paramRegex.findAll(beforeContent).toList()
            for (i in paramMatches.indices) {
                val key = paramMatches[i].groupValues[1]
                val start = paramMatches[i].range.last + 1
                val end = if (i + 1 < paramMatches.size) paramMatches[i + 1].range.first else beforeContent.length
                val value = beforeContent.substring(start, end).trim().trimEnd(',').trim().trim('"')
                if (value.isNotBlank() && key != "content") {
                    params[key] = value.trimEnd('\r')
                }
            }
        } else {
            // No multi-line content — parse all params (handles comma and space separators)
            val paramRegex = Regex("(\\w+)\\s*=\\s*", RegexOption.IGNORE_CASE)
            val paramMatches = paramRegex.findAll(afterCall).toList()
            for (i in paramMatches.indices) {
                val key = paramMatches[i].groupValues[1]
                val start = paramMatches[i].range.last + 1
                val end = if (i + 1 < paramMatches.size) paramMatches[i + 1].range.first else afterCall.length
                val value = afterCall.substring(start, end).trim().trimEnd(',').trim().trim('"').trim('"')
                if (value.isNotBlank()) {
                    params[key] = value.trimEnd('\r')
                }
            }
        }
        return toolName to params
    }

    private fun looksTruncated(text: String): Boolean {
        if (text.isBlank()) return true
        val trimmed = text.trim()
        val lastChar = trimmed.last()
        if (lastChar in setOf('.', '!', '?', '\n', ')', ']', '}', '>', '"', '*', '|')) {
            val backtickCount = Regex("```").findAll(text).count()
            if (backtickCount % 2 != 0) return true
            return false
        }
        val lastOpen = text.lastIndexOf('[')
        val lastClose = text.lastIndexOf(']')
        if (lastOpen > lastClose) return true
        return true
    }

    private suspend fun twoPhaseReactLoop(
        query: String,
        context: String,
        fileAttachmentNames: List<String> = emptyList()
    ): AgentResponse {
        // Базовые инструменты + внешние навыки (ext_*)
        val extSkillNames = skillRegistry.getAllSkills()
            .filter { it.name.startsWith("ext_") }
            .map { it.name }
            .toSet()
        
        val officeAliases = setOf("office", "pptx", "powerpoint", "word", "excel")
        val gatherTools = setOf("web_search", "web_fetch", "read_file", "write_file", "append_file", "delete_file", "create_folder", "weather", "list_files", "email_check", "email_list", "email_read", "email_send", "contacts_search", "contacts_add", "call_dial", "call_call", "sms_send", "task_scheduler", "notif_listener", "location", "get_context", "home", "clipboard", "calendar", "geo", "maps") + officeAliases + extSkillNames
        val executeTools = setOf("write_file", "append_file", "delete_file", "create_folder", "list_files", "execute_python", "email_send", "email_check", "email_list", "email_read", "contacts_search", "contacts_add", "call_dial", "call_call", "sms_send", "task_scheduler", "notif_listener", "location", "get_context", "home", "clipboard", "calendar", "geo", "maps") + officeAliases + extSkillNames

        // Add list of configured email accounts to context
        val emailAccountList = try {
            val es = skillRegistry.getSkill("email")
            if (es is com.pai.android.agent.skills.EmailSkill) {
                val accts = es.listAccounts()
                if (accts.isNotEmpty()) "\n\nConfigured email accounts: " + accts.joinToString(", ") { it.displayName + " (" + it.username + ")" } else ""
            } else ""
        } catch (_: Exception) { "" }
        val enhancedContext = context + emailAccountList  // context always shows available email accounts

        // Phase 1: Gather
        println("twoPhase: GATHER phase")
        val gatherResult = singlePhaseReactLoop(
            query = query,
            context = enhancedContext,
            fileAttachmentNames = fileAttachmentNames,
            allowedTools = gatherTools,
            maxSteps = 4,
            phaseInstructions = "You are in GATHER phase. " +
                "FOLLOW THE USER'S INSTRUCTIONS EXACTLY — format, scope, and content must match what was requested. " +
                "To search emails by sender/subject: use email_list query=keywords (NOT email_check). " +
                "To view inbox overview: use email_check. " +
                "After finding the right email with email_list, use email_read UID=... to read content. " +
                "For system status queries (notification access, Bluetooth, WiFi) always call the appropriate tool. " +
                "Do NOT answer from memory - call the tool to check current state. " +
                "MULTI-STEP: If the task requires multiple tool calls (e.g. search then read, find then save), " +
                "call them ONE BY ONE across consecutive rounds. Use the result of one call in the next. " +
                "Do NOT stop or answer the user until all necessary tools have been called. " +
                "Each tool once per phase. If done, say [PHASE_COMPLETE] or just answer.",
            initialSteps = emptyList()
        )

        if (gatherResult.isError) {
            return AgentResponse.Error(error = gatherResult.answer, details = gatherResult.thoughts.joinToString())
        }
        // Auto-transition to EXECUTE if GATHER produced natural language response
        // (not [PHASE_COMPLETE], not a final answer with 📄 markers)
        val hasExecLog = gatherResult.executionLog.isNotBlank()
        val hasNoAnswer = gatherResult.answer.isBlank()
        val isPhaseComplete = gatherResult.thoughts.firstOrNull() == "Phase complete"
        val hasFileMarkers = gatherResult.answer.contains("📄") || gatherResult.answer.contains("📂")
        
        // If GATHER has 📄/📂 markers in the answer, return directly (file found and presented)
        // Only treat email_read result (which has 📄+Письмо) as final, not mailbox overviews
        val hasEmailResult = gatherResult.answer.contains("📄") &&
            (gatherResult.answer.contains("Письмо") || gatherResult.answer.contains("письмо"))
        
        // Return immediately if email_read or email_sent answer is present
        val isEmailReadOrSent = gatherResult.answer.contains("📄") || gatherResult.answer.contains("✅") || gatherResult.answer.contains("📇")
        if (hasFileMarkers || hasEmailResult || isEmailReadOrSent) {
            println("twoPhase: returning answer directly (email result)")
            return AgentResponse.Success(
                answer = gatherResult.answer,
                thoughts = gatherResult.thoughts,
                actions = emptyList()
            )
        }
        
        if ((isPhaseComplete || hasExecLog) && !hasNoAnswer) {
            println("twoPhase: auto-transition to EXECUTE (GATHER said: " + gatherResult.answer.take(60) + "...)")
        } else if (gatherResult.answer.isNotBlank()) {
            return AgentResponse.Success(
                answer = gatherResult.answer,
                thoughts = gatherResult.thoughts,
                actions = emptyList()
            )
        }
        val execLog = gatherResult.executionLog

        // Phase 2: Execute
        println("twoPhase: EXECUTE phase")
        val executeResult = singlePhaseReactLoop(
            query = query,
            context = enhancedContext,
            fileAttachmentNames = fileAttachmentNames,
            allowedTools = executeTools,
            maxSteps = 4,
            phaseInstructions = "You are in EXECUTE phase. To ATTACH files: answer with 📄 path/file.ext (size) on its own line. " +
                "File attaches automatically — do NOT use read_file. " +
                "MULTI-STEP: If the task requires multiple tool calls (e.g. create a docx then email it), " +
                "call tools one by one across consecutive rounds. Use results from previous calls. " +
                "Do NOT answer the user until all necessary tools have been called. " +
                "Each tool once per phase. Present results immediately.",
            initialSteps = listOf(execLog)
        )

        if (executeResult.isError) {
            return AgentResponse.Error(error = executeResult.answer, details = executeResult.thoughts.joinToString())
        }
        return AgentResponse.Success(
            answer = executeResult.answer,
            thoughts = listOf("Two-phase ReAct complete"),
            actions = emptyList()
        )
    }

    private data class ReactLoopResult(
        val answer: String,
        val thoughts: List<String>,
        val executionLog: String,
        val isError: Boolean = false
    )

    /** Single phase of ReAct loop with configurable tools. */
    private suspend fun singlePhaseReactLoop(
        query: String,
        context: String,
        fileAttachmentNames: List<String> = emptyList(),
        allowedTools: Set<String>,
        maxSteps: Int = 3,
        phaseInstructions: String = "",
        initialSteps: List<String> = emptyList()
    ): ReactLoopResult
    {
        data class Step(val action: String, val result: String)
        val steps = mutableListOf<Step>()
        steps.addAll(initialSteps.map { Step("(prev phase)", it) })
        var lastFinalAnswer = ""
        val calledTools = mutableSetOf<String>()

        // Нативные определения инструментов (для tool calling API)
        // Берём из toolRegistry + skills из reactToolMap для полного покрытия
        val nativeToolDefs = buildNativeToolDefs(allowedTools)
        val hasNativeTools = nativeToolDefs.isNotEmpty()

        val toolList = allowedTools.joinToString("\n") { n ->
            " - " + n + getToolDescription(n)
        }

        for (round in 0 until maxSteps) {
            val stepsLog = steps.joinToString("\n---\n") {
                "Action: " + it.action + "\nResult: " + it.result.take(4000)
            }

            val prompt = buildString {
                appendLine("User query: " + query)
                if (fileAttachmentNames.isNotEmpty())
                    appendLine("Attached: " + fileAttachmentNames.joinToString(", "))
                if (context.isNotBlank())
                    appendLine("Context: " + context.take(500))
                if (phaseInstructions.isNotBlank()) { appendLine(); appendLine(phaseInstructions) }
                if (!hasNativeTools) {
                    // Без нативного tool calling — текстовое описание инструментов
                    appendLine(); appendLine("Available tools:")
                    appendLine(toolList)
                    appendLine(); appendLine("Execution log:")
                    appendLine(stepsLog.take(12000))
                    appendLine(); appendLine("Format: [TOOL_CALL: name] key=val (use ---CONTENT---/---END--- for multi-line)")
                    appendLine("Or just answer the user.")
                } else {
                    // С нативным tool calling — краткий промпт
                    appendLine(); appendLine("Execution log:")
                    appendLine(stepsLog.take(12000))
                    appendLine(); appendLine("Use the available tools when needed. Answer directly if no tool is needed.")
                }
            }

            val systemMsg = if (hasNativeTools) {
                "Helpful AI with tool access. " + phaseInstructions + " Reply in user language."
            } else {
                "Helpful AI with tool access. CRITICAL RULE: When email_read is called, display the EXACT returned content. NEVER invent, summarize, or modify email body text. " + phaseInstructions +
                    " Tools: " + allowedTools.joinToString(", ") + ". Reply in user language."
            }

            val resp = if (hasNativeTools) {
                aiRepository.sendMessage(
                    messages = listOf(com.pai.android.data.model.Message.createUserMessage("r" + round, prompt)),
                    systemPrompt = systemMsg,
                    memoryContext = context,
                    tools = nativeToolDefs,
                    toolChoice = "auto"
                )
            } else {
                aiRepository.sendMessage(
                    messages = listOf(com.pai.android.data.model.Message.createUserMessage("r" + round, prompt)),
                    systemPrompt = systemMsg,
                    memoryContext = context
                )
            }
            if (resp.isFailure) return ReactLoopResult(answer = "ReAct error: " + (resp.exceptionOrNull()?.message ?: ""), thoughts = listOf("error"), executionLog = "", isError = true)
            val aiResponse = resp.getOrThrow()
            val text = aiResponse.text
            val toolCalls = aiResponse.toolCalls
            println("singlePhase r" + round + ": " + (if (text.isNotBlank()) text.take(80) + "..." else "tool_calls=" + (toolCalls?.size ?: 0)))

            // Обработка нативных tool calls
            if (hasNativeTools && toolCalls != null && toolCalls.isNotEmpty()) {
                for (tc in toolCalls) {
                    val req = tc.name
                    if (!allowedTools.contains(req)) {
                        steps.add(Step("[native:" + req + "] (not allowed)", "Tool unavailable now"))
                        println("singlePhase: " + req + " not allowed in this phase")
                        continue
                    }
                    val an = reactToolMap[req] ?: req
                    val sk = skillRegistry.getSkill(an)
                    if (sk != null) {
                        // Параметры уже структурированы (не нужно парсить, как [TOOL_CALL])
                        val ep = tc.arguments.toMutableMap()
                        // Нормализация параметров (как в текстовом пути)
                        if (req == "read_file") {
                            if (!ep.containsKey("command")) ep["command"] = "read_file"
                            if (!ep.containsKey("path")) ep["path"] = ep["query"]?.toString() ?: ""
                        } else if (req == "write_file") { if (!ep.containsKey("command")) ep["command"] = "write_file" }
                        else if (req == "append_file") { if (!ep.containsKey("command")) ep["command"] = "append_file" }
                        else if (req == "delete_file") { if (!ep.containsKey("command")) ep["command"] = "delete" }
                        else if (req == "create_folder") { if (!ep.containsKey("command")) ep["command"] = "create_folder" }
                        else if (req == "list_files") {
                            if (!ep.containsKey("command")) ep["command"] = "list_files"
                            if (!ep.containsKey("recursive")) ep["recursive"] = true
                        } else if (req == "location") {
                            if (!ep.containsKey("action")) ep["action"] = "current"
                        } else if (req == "clipboard") {
                            if (!ep.containsKey("action")) ep["action"] = "read"
                        } else if (req == "calendar") {
                            if (!ep.containsKey("action")) ep["action"] = "list_upcoming"
                        } else if (req == "calendar") {
                            if (!ep.containsKey("action")) ep["action"] = "list_upcoming"
                        }
                        if (!ep.containsKey("command")) {
                            if (req.startsWith("email_")) ep["command"] = req
                            else if (!ep.containsKey("query")) ep["query"] = query
                        }

                        val callKey = when (req) {
                            "clipboard" -> req + "_" + (ep["action"]?.toString() ?: "")
                            "list_files" -> req + (if (ep.containsKey("tree")) "_tree" else "_search")
                            "email_read" -> req + "_" + (ep["uid"]?.toString() ?: "0")
                            "email_send" -> req + "_" + (ep["to"]?.toString() ?: "") + "_" + (ep["subject"]?.toString() ?: "")
                            "task_scheduler" -> req + "_" + (ep["command"]?.toString() ?: "") + "_" + (ep["task_name"]?.toString() ?: "")
                            "write_file", "append_file", "delete_file", "create_folder" -> req + "_" + (ep["path"]?.toString() ?: "")
                            "read_file" -> req + "_" + (ep["path"]?.toString() ?: "")
                            "office", "pptx", "powerpoint", "word", "excel" -> req + "_" + (ep["path"]?.toString() ?: "")
                            "web_search" -> req + "_" + (ep["query"]?.toString() ?: "").take(50)
                            "web_fetch" -> req + "_" + (ep["url"]?.toString() ?: "").take(50)
                            "weather" -> req + "_" + (ep["query"]?.toString() ?: ep["city"]?.toString() ?: "")
                            "email_list" -> req + "_" + (ep["query"]?.toString() ?: "").take(30)
                            "email_check" -> req + "_" + (System.currentTimeMillis() / 30000)
                            "contacts_search" -> req + "_" + (ep["query"]?.toString() ?: "")
                            "location" -> req + "_" + (ep["action"]?.toString() ?: "")
                            "execute_python" -> req + "_" + ((ep["code"]?.toString()?.hashCode() ?: 0))
                            else -> req
                        }
                        val multiCallTools = setOf("task_scheduler", "write_file", "append_file", "read_file", "delete_file", "create_folder", "list_files", "office", "pptx", "powerpoint", "word", "excel", "clipboard", "calendar", "maps")
                        val isDuplicate = if (req in multiCallTools) false else calledTools.contains(callKey)
                        if (isDuplicate) {
                            steps.add(Step("[native:" + req + "] (already called)", "Tool used this phase. Skip."))
                            continue
                        }
                        calledTools.add(callKey)

                        val er = sk.execute(ep)
                        val rt = when (er) {
                            is SkillResult.Success -> if (er.data?.get("content") != null) "Content (" + er.data["content"]?.toString()?.length + " chars)" else er.message.take(8000)
                            is SkillResult.Error -> "Error: " + er.message
                            is SkillResult.ConfirmationRequired -> "Confirm: " + er.question
                        }
                        // Bypass LLM — return tool result directly, don't let AI make up descriptions
                        if ((req == "email_read" || req == "email_send" || req == "contacts_add" || req == "call_call" || req == "sms_send" || req == "office" || req == "pptx" || req == "powerpoint" || req == "word" || req == "excel") && er is SkillResult.Success) {
                            val log = steps.joinToString("\n---\n") { "A: " + it.action + "\nR: " + it.result.take(4000) }
                            return ReactLoopResult(answer = er.message, thoughts = listOf(req + " result"), executionLog = log)
                        }
                        steps.add(Step("[native:" + req + "] " + tc.arguments.entries.joinToString(", ") { e -> e.key + "=" + e.value }, rt))
                    } else {
                        steps.add(Step("[native:" + req + "] (not found)", "Tool unavailable"))
                    }
                    println("singlePhase step " + (round + 1) + "/" + maxSteps)
                }
                // После выполнения всех tool_calls — продолжаем цикл (может быть несколько раундов)
                continue
            }

            // Текстовый путь (старый [TOOL_CALL] парсинг) — только если нет нативных tools
            if (!hasNativeTools) {
                if (text.contains("[PHASE_COMPLETE]", ignoreCase = true)) {
                    val log = steps.joinToString("\n---\n") { "A: " + it.action + "\nR: " + it.result.take(4000) }
                    return ReactLoopResult(answer = "", thoughts = listOf("Phase complete"), executionLog = log)
                }

                val tc = parseToolCall(text)
                if (tc != null) {
                    val (req, params) = tc
                    if (!allowedTools.contains(req)) {
                        steps.add(Step("[" + req + "] (not allowed)", "Tool unavailable now"))
                        println("singlePhase: " + req + " not allowed in this phase")
                        continue
                    }
                    val an = reactToolMap[req] ?: req
                    val sk = skillRegistry.getSkill(an)
                    if (sk != null) {
                        val ep = params.toMutableMap() as MutableMap<String, Any>
                        if (req == "read_file") {
                            if (!ep.containsKey("command")) ep["command"] = "read_file"
                            if (!ep.containsKey("path")) ep["path"] = ep["query"]?.toString() ?: ""
                        } else if (req == "write_file") { if (!ep.containsKey("command")) ep["command"] = "write_file" }
                        else if (req == "append_file") { if (!ep.containsKey("command")) ep["command"] = "append_file" }
                        else if (req == "delete_file") { if (!ep.containsKey("command")) ep["command"] = "delete" }
                        else if (req == "create_folder") { if (!ep.containsKey("command")) ep["command"] = "create_folder" }
                        else if (req == "list_files") {
                            if (!ep.containsKey("command")) ep["command"] = "list_files"
                            if (!ep.containsKey("recursive")) ep["recursive"] = "true"
                        } else if (req == "location") {
                            if (!ep.containsKey("action")) ep["action"] = "current"
                        } else if (req == "clipboard") {
                            if (!ep.containsKey("action")) ep["action"] = "read"
                        } else if (req == "calendar") {
                            if (!ep.containsKey("action")) ep["action"] = "list_upcoming"
                        } else if (req == "calendar") {
                            if (!ep.containsKey("action")) ep["action"] = "list_upcoming"
                        }
                        if (!ep.containsKey("command")) {
                            if (req.startsWith("email_")) ep["command"] = req
                            else if (!ep.containsKey("query")) ep["query"] = query
                        }
                        val callKey = when (req) {
                            "clipboard" -> req + "_" + (ep["action"]?.toString() ?: "")
                            "list_files" -> req + (if (ep.containsKey("tree")) "_tree" else "_search")
                            "email_read" -> req + "_" + (ep["uid"]?.toString() ?: "0")
                            "email_send" -> req + "_" + (ep["to"]?.toString() ?: "") + "_" + (ep["subject"]?.toString() ?: "")
                            "task_scheduler" -> req + "_" + (ep["command"]?.toString() ?: "") + "_" + (ep["task_name"]?.toString() ?: "")
                            "write_file", "append_file", "delete_file", "create_folder" -> req + "_" + (ep["path"]?.toString() ?: "")
                            "read_file" -> req + "_" + (ep["path"]?.toString() ?: "")
                            "office", "pptx", "powerpoint", "word", "excel" -> req + "_" + (ep["path"]?.toString() ?: "")
                            "web_search" -> req + "_" + (ep["query"]?.toString() ?: "").take(50)
                            "web_fetch" -> req + "_" + (ep["url"]?.toString() ?: "").take(50)
                            "weather" -> req + "_" + (ep["query"]?.toString() ?: ep["city"]?.toString() ?: "")
                            "email_list" -> req + "_" + (ep["query"]?.toString() ?: "").take(30)
                            "email_check" -> req + "_" + (System.currentTimeMillis() / 30000)
                            "contacts_search" -> req + "_" + (ep["query"]?.toString() ?: "")
                            "location" -> req + "_" + (ep["action"]?.toString() ?: "")
                            "execute_python" -> req + "_" + ((ep["code"]?.toString()?.hashCode() ?: 0))
                            else -> req
                        }
                        val multiCallTools2 = setOf("task_scheduler", "write_file", "append_file", "read_file", "delete_file", "create_folder", "list_files", "office", "pptx", "powerpoint", "word", "excel", "clipboard", "calendar", "maps")
                        if (req !in multiCallTools2 && calledTools.contains(callKey)) {
                            steps.add(Step("[" + req + "] (already called)", "Tool used this phase. Skip."))
                            println("singlePhase: " + req + " already called")
                            continue
                        }
                        calledTools.add(callKey)
                        val er = sk.execute(ep)
                        val rt = when (er) {
                            is SkillResult.Success -> if (er.data?.get("content") != null) "Content (" + er.data["content"]?.toString()?.length + " chars)" else er.message.take(8000)
                            is SkillResult.Error -> "Error: " + er.message
                            is SkillResult.ConfirmationRequired -> "Confirm: " + er.question
                        }
                        if (req == "email_read" && er is SkillResult.Success) {
                            val log = steps.joinToString("\n---\n") { "A: " + it.action + "\nR: " + it.result.take(4000) }
                            return ReactLoopResult(answer = er.message, thoughts = listOf("email_read result"), executionLog = log)
                        }
                        if ((req == "email_send" || req == "contacts_add" || req == "call_call" || req == "sms_send" || req == "office" || req == "pptx" || req == "powerpoint" || req == "word" || req == "excel") && er is SkillResult.Success) {
                            val log = steps.joinToString("\n---\n") { "A: " + it.action + "\nR: " + it.result.take(4000) }
                            return ReactLoopResult(answer = er.message, thoughts = listOf(req + " result"), executionLog = log)
                        }
                        steps.add(Step("[" + req + "] " + params.entries.joinToString(", ") { e -> e.key + "=" + e.value }, rt))
                    } else { steps.add(Step("[" + req + "] (not found)", "Tool unavailable")) }
                    println("singlePhase step " + (round + 1) + "/" + maxSteps)
                    continue
                }

                if (looksTruncated(text)) { steps.add(Step("(cont)", "(continuing)")); continue }

                val userWantsSend = query.lowercase().contains("перешл") || query.lowercase().contains("отправ") || query.lowercase().contains("forward") || query.lowercase().contains("send")
                val emailSendCalled = steps.any { it.action.contains("email_send") }
                if (userWantsSend && !emailSendCalled && !text.contains("[TOOL_CALL")) {
                    steps.add(Step("(must call email_send)", "User asked to send, you MUST call email_send tool. Do not claim to have sent without calling it."))
                    continue
                }
            }

            val log = steps.joinToString("\n---\n") { "A: " + it.action + "\nR: " + it.result.take(4000) }
            lastFinalAnswer = text
            lastFinalAnswer = text
            return ReactLoopResult(answer = text, thoughts = listOf("singlePhase done"), executionLog = log)
        }

        val best = if (steps.any { !it.action.startsWith("(") }) {
            steps.lastOrNull { s -> !s.action.startsWith("(") && !s.result.startsWith("Error") && !s.result.contains("не найден") }?.result
                ?: lastFinalAnswer.ifBlank { "Max steps reached." }
        } else {
            lastFinalAnswer.ifBlank { "Could not complete the task. Try rephrasing your request." }
        }
        val log = steps.joinToString("\n---\n") { "A: " + it.action + "\nR: " + it.result.take(4000) }
        return ReactLoopResult(answer = best, thoughts = listOf("singlePhase max steps"), executionLog = log)
    }

    /** Строит нативные определения инструментов из ToolRegistry + reactToolMap + SkillRegistry. */
    private fun buildNativeToolDefs(allowedTools: Set<String>): List<NativeToolDefinition> {
        val defs = mutableListOf<NativeToolDefinition>()
        val foundNames = mutableSetOf<String>()

        // 1. Инструменты из ToolRegistry (те, у кого есть AgentTool)
        val toolDefs = toolRegistry.getNativeToolDefinitions(filterNames = allowedTools)
        for (def in toolDefs) {
            defs.add(def)
            foundNames.add(def.function.name)
        }

        // 2. Инструменты/навыки из reactToolMap → skillRegistry
        for (toolName in allowedTools) {
            if (foundNames.contains(toolName)) continue
            // Пропускаем call_dial — только путает модель. Оставляем call_call (он сам делает fallback)
            if (toolName == "call_dial") continue
            val skillName = reactToolMap[toolName] ?: continue
            val skill = skillRegistry.getSkill(skillName)
            if (skill != null) {
                // Пытаемся достать schema из manifest (у ExternalSkillAdapter)
                val schemaFromManifest = if (skill is com.pai.android.agent.skills.ExternalSkillAdapter) {
                    try {
                        val gson = Gson()
                        val type = object : TypeToken<Map<String, Any>>() {}.type
                        gson.fromJson<Map<String, Any>>(skill.manifest.instructions, type)
                    } catch (_: Exception) { null }
                } else null
                
                val isGeo = toolName == "geo"
                val description = when (toolName) {
                    "read_file" -> "Read a file. Params: path=filename"
                    "write_file" -> "Write content to a file. Params: path=filename, content=text"
                    "append_file" -> "Append text to a file. Params: path=filename, content=text"
                    "delete_file" -> "Delete a file or folder. Params: path=target"
                    "create_folder" -> "Create a folder. Params: path=folder_name"
                    "list_files" -> "List/search files. Params: path=directory (search), recursive=true (list all)"
                    "execute_python" -> "Run Python code. Params: code=python_code"
                    "email_check" -> "Check for new unread emails"
                    "email_list" -> "Search emails. Params: query=keywords, limit=number"
                    "email_read" -> "Read full email content. Params: uid=UID_NUMBER"
                    "email_send" -> "Send an email. Params: to=recipient, subject=title, body=message. Optional: file_path=path/to/file (attach one file), attachment_paths=path1,path2 (multiple)"
                    "contacts_search" -> "Search contacts. Params: query=name or phone"
                    "home" -> "Scan home Wi-Fi network and list connected devices, identify smart devices. Params: query=user request. For 'сканируй сеть', the tool will ARP-scan + ping the whole /24 subnet."
                    "contacts_add" -> "Add contact. Params: name=..., phone=..."
                    "call_call" -> "Make a phone call. Use this FIRST. Calls the number directly via CALL_PHONE permission (no notification). If permission missing, uses notification fallback automatically. Params: number=phone"
                    "sms_send" -> "Send SMS. Params: number=phone, text=message"
                    else -> skill.description
                }

                val paramsSchema = if (isGeo) {
                    mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "command" to mapOf(
                                "type" to "string",
                                "description" to "What to do: 'add' to create, 'edit' to update existing (needs id), 'remove' to delete (needs id), 'list' to show all, 'test' for a test task"
                            ),
                            "label" to mapOf(
                                "type" to "string",
                                "description" to "What to remind about, e.g. 'купить молоко', 'забрать машину'"
                            ),
                            "address" to mapOf(
                                "type" to "string",
                                "description" to "Location address or description, e.g. 'Ленинский проспект 152', 'у Пятёрочки'"
                            ),
                            "latitude" to mapOf(
                                "type" to "number",
                                "description" to "Latitude of the reminder location (optional, auto-resolved from address if omitted)"
                            ),
                            "longitude" to mapOf(
                                "type" to "number",
                                "description" to "Longitude of the reminder location (optional, auto-resolved from address if omitted)"
                            ),
                            "radius" to mapOf(
                                "type" to "integer",
                                "description" to "Trigger radius in meters (default 100)"
                            )
                        ),
                        "required" to listOf("command")
                    )
                } else {
                    mapOf(
                        "type" to "object",
                        "properties" to (schemaFromManifest?.get("properties") as? Map<*, *> ?: emptyMap<String, Any>()),
                        "required" to (schemaFromManifest?.get("required") as? List<*> ?: emptyList<String>())
                    )
                }
                defs.add(NativeToolDefinition(
                    function = NativeFunctionDefinition(
                        name = toolName,
                        description = description,
                        parameters = paramsSchema
                    )
                ))
                foundNames.add(toolName)
                println("🔧 buildNativeToolDefs: added '$toolName' from skillRegistry (${skillName})")

            }
        }

        // 3. OfficeSkill — явно добавляем, если есть в registry
        if (!foundNames.contains("office")) {
            val officeSkill = skillRegistry.getSkill("office")
            if (officeSkill != null) {
                println("🔧 buildNativeToolDefs: added 'office' from direct registry lookup")
                defs.add(NativeToolDefinition(
                    function = NativeFunctionDefinition(
                        name = "office",
                        description = officeSkill.description,
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "query" to mapOf(
                                    "type" to "string",
                                    "description" to "User request: what document to create (Word, Excel, PowerPoint)"
                                )
                            ),
                            "required" to listOf("query")
                        )
                    )
                ))
                foundNames.add("office")
            }
        }

        // 4. Внешние навыки (ext_*) — созданные агентом или загруженные
        val extSkills = skillRegistry.getAllSkills().filter { it.name.startsWith("ext_") }
        for (extSkill in extSkills) {
            if (foundNames.contains(extSkill.name)) continue
            val extDescription = if (extSkill is com.pai.android.agent.skills.ExternalSkillAdapter) {
                "[External] ${extSkill.manifest.name}: ${extSkill.manifest.description}. Triggers: ${extSkill.manifest.triggers.joinToString(", ")}"
            } else {
                extSkill.description
            }
            defs.add(NativeToolDefinition(
                function = NativeFunctionDefinition(
                    name = extSkill.name,
                    description = extDescription,
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf(
                                "type" to "string",
                                "description" to "User query, search term, or command for this skill"
                            )
                        ),
                        "required" to listOf("query")
                    )
                )
            ))
            foundNames.add(extSkill.name)
        }

        println("🔧 buildNativeToolDefs: ${defs.size} definitions (${allowedTools.size} allowed + ${extSkills.size} external)")
        return defs
    }

    /** Description for a tool name. */
    private fun getToolDescription(name: String): String = when (name) {
        "home" -> ": Scan/manage home Wi-Fi network. Params: query=user request"
        "read_file" -> ": Read. Params: path=file, query=question"
        "write_file" -> ": Write. Params: path=file (use ---CONTENT---/---END--- for content)"
        "append_file" -> ": Append to file. Params: path=file, content=text"
        "delete_file" -> ": Delete file/folder. Params: path=target"
        "create_folder" -> ": Create folder. Params: path=folder_name"
        "list_files" -> ": List/search files. PREFER this over Python for file operations. Params: path=name (search), path=. recursive=true (list all), tree=true (full tree)"
        "web_search" -> ": Search web. Params: query=terms"
        "web_fetch" -> ": Fetch URL. Params: url=..."
        "execute_python" -> ": Run Python (sandbox: no full FS access). Use ONLY for computation, NOT for file listing."
        "weather" -> ": Weather. Params: query=city"
        "email_check" -> ": Check inbox. Params: (none needed, account is auto-selected)"
        "email_list" -> ": List/search emails. Params: query=keywords (optional, to search), limit=N"
        "email_read" -> ": Read email. Params: uid=number_from_list. CRITICAL: Display the EXACT returned content. NEVER invent/modify email body."
        "email_send" -> ": Send/forward email with optional attachment. Params: to=addr, subject=title, body=text, file_path=path/to/file. To forward an email, use the content from email_read result., body=text"
        "contacts_search" -> ": Search contacts. Params: query=name or phone"
        "contacts_add" -> ": Add contact. Params: name=... phone=..."
        "call_dial" -> ": Call a number. Calls directly if permission granted, otherwise opens dialer. Params: number=phone"
        "call_call" -> ": Call directly. Params: number=phone (needs CALL_PHONE permission)"
        "sms_send" -> ": Send SMS. Params: number=phone, text=message. Sends directly if SEND_SMS granted, else opens SMS app."
        "task_scheduler" -> ": Manage scheduled tasks. Params: action=list/show/add/remove. For reminders, monitoring, cron jobs."
        "geo" -> ": Location reminders. Params: command=add|test|list|remove, label=what to remind, address=where, radius=meters (default 100). Creates a task that notifies when you're near the place."
        else -> ""
    }
    private suspend fun tryFallbackToReAct(query: String, error: String, skillName: String): AgentResponse? {
        val lowerQuery = query.lowercase()

        // Если навык не смог выполнить задачу — пробуем ReActAgent для сложных запросов
        val complexQueryWords = listOf(
            "найди", "найти", "поищи", "ищи", "удали", "удалить",
            "проанализируй", "анализ", "исследуй", "изучи", "сравни",
            "опиши", "содержимое", "перемести", "скопируй",
            "сохрани", "запомни", "вспомни",
            "создай", "напиши", "запиши"
        )
        val hasComplexAction = complexQueryWords.any { lowerQuery.contains(it) }
        val hasError = error.contains("не найден", ignoreCase = true) || error.contains("не указан", ignoreCase = true) || error.contains("Неизвестная команда") || error.contains("не удалось", ignoreCase = true)

        if (hasComplexAction && hasError) {
            println("🤖 ReAct: навык '$skillName' не справился ($error), делегирую ReActAgent")
            return reactAgent.processRequest(query = query, context = "", maxSteps = 25)
        }
        return null
    }

    /**
     * Обрабатывает запрос от планировщика (без проектов, без планирования,
     * но с поиском в интернете и выполнением навыков).
     */
    suspend fun processScheduledTask(
        query: String,
        context: String = ""
    ): AgentResponse {
        println("📅 SchedulerTask: '" + query.take(80) + "...' (len=" + query.length + ")")

        // Мониторинговые задачи (watch:prefix) — без LLM, быстрый путь
        if (query.startsWith("watch:") || query.startsWith("email_watch:")) {
            return processWatchTask(query)
        }

        // Короткие reminders (<100 chars) — быстро, без инструментов
        if (query.length < 100 && !query.contains("почт") && !query.contains("email") 
            && !query.contains("письм") && !query.contains("пиши")
            && !query.contains("search") && !query.contains("найд")
            && !query.contains("провер") && !query.contains("монитор")) {
            println("📅 SchedulerTask: короткий reminder, без инструментов")
            val response = aiRepository.sendMessage(
                messages = listOf(com.pai.android.data.model.Message.createUserMessage("scheduler", query)),
                systemPrompt = buildDateAwarePrompt(
                    "You are a helpful AI assistant. Be CONCISE. " +
                    "Reply in the user's language."
                ),
                memoryContext = context
            )
            val text = response.getOrNull()?.text ?: "ok"
            return AgentResponse.Success(answer = text, thoughts = listOf("Scheduler: short"), actions = emptyList())
        }

        // Для почтовых задач — сначала REAL проверка, результаты в контекст
        val hasEmailIntent = query.contains("почт") || query.contains("email") || 
            query.contains("письм") || query.contains("ящик") || query.contains("mail")
        val emailContext = if (hasEmailIntent) {
            try {
                val emailSkill = skillRegistry.getSkill("email")
                if (emailSkill != null) {
                    println("📅 SchedulerTask: принудительная проверка почты")
                    val listResult = emailSkill.execute(mapOf(
                        "command" to "email_list", "query" to "", "limit" to 5
                    ))
                    if (listResult is SkillResult.Success) {
                        val emails = listResult.message
                        // Извлекаем UID последнего письма для контекста
                        val uidMatch = Regex("""\[UID=(\d+)\]""").find(emails)
                        val lastUid = uidMatch?.groupValues?.getOrNull(1) ?: ""
                        "\n\n📬 Результат проверки почты (последние 5 писем):\n" +
                        emails.take(2000) +
                        "\n---\nUID последнего письма: $lastUid\n" +
                        "Текущие время: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date())}"
                    } else {
                        "\n\n📬 Ошибка проверки почты: ${(listResult as? SkillResult.Error)?.message}"
                    }
                } else ""
            } catch (_: Exception) { "" }
        } else ""

        val enhancedContext = if (emailContext.isNotBlank()) "$context\n$emailContext" else context

        // Всё остальное — ReAct цикл с полными инструментами
        println("📅 SchedulerTask: ReAct с инструментами")
        return twoPhaseReactLoop(
            query = query,
            context = enhancedContext,
            fileAttachmentNames = emptyList()
        )
    }

    /**
     * Проверяет, является ли ответ 'пустым' (описание процесса вместо данных).
     */
    private fun isNonAnswer(answer: String, query: String): Boolean {
        val lower = answer.lowercase()
        val nonAnswerPhrases = listOf(
            "необходимо получить", "я выполню поиск", "для подготовки",
            "нужен поиск", "требуется поиск", "необходимо выполнить поиск",
            "after obtaining", "после получения", "я структурирую",
            "need to search", "i will search", "i need to get",
            "to prepare", "i will perform", "i will execute",
            "after i get", "after obtaining"
        )
        return nonAnswerPhrases.any { lower.contains(it) }
    }

    /**
     * Обрабатывает общие запросы (вопросы, чат) через обычный AI.
     */
    private suspend fun handleGeneralQuery(
        query: String,
        context: String,
        intentResult: IntentResult,
        autoApprove: Boolean = false
    ): AgentResponse {
        println("💬 Запрос направлен обычному AI (намерение: ${intentResult.intent})")

        return try {
            val response = aiRepository.sendMessage(
                messages = listOf(
                    com.pai.android.data.model.Message.createUserMessage("assistant", query)
                ),
                systemPrompt = buildDateAwarePrompt("You are a helpful AI assistant."),
                memoryContext = context
            )

            val text = response.getOrThrow().text

            AgentResponse.Success(
                answer = text,
                thoughts = listOf("Ответ от AI (намерение: ${intentResult.intent})"),
                actions = emptyList()
            )
        } catch (e: Exception) {
            AgentResponse.Error(
                error = "Не удалось получить ответ от AI",
                details = e.message
            )
        }
    }

    /**
     * Extracts search query from the string, removing the matched pattern prefix.
     * Example: "search the web for weather in London" → "weather in London"
     */
    private fun extractSearchQuery(query: String, matchedPatterns: List<String>): String {
        val lower = query.lowercase().trim()
        for (pattern in matchedPatterns) {
            val idx = lower.indexOf(pattern)
            if (idx >= 0) {
                // Всё после паттерна — это поисковый запрос
                val after = query.substring(idx + pattern.length).trim()
                if (after.isNotBlank()) return after
            }
        }
        return ""
    }

    /**
     * Extracts city name from weather query.
     * Examples: "find weather in London" → "London", "weather in Paris" → "Paris"
     */
    private fun extractCityFromWeatherQuery(query: String, matchedPatterns: List<String>): String {
        val lower = query.lowercase().trim()
        for (pattern in matchedPatterns) {
            val idx = lower.indexOf(pattern)
            if (idx >= 0) {
                var after = query.substring(idx + pattern.length).trim()
                // Remove prepositions "in", "at", "on"
                after = after.removePrefix("в ").removePrefix("во ").removePrefix("на ")
                    .removePrefix("in ").removePrefix("at ")
                    .trim()
                // Truncate at stop words: time, day, questions, punctuation
                val stopWords = listOf(
                    " на ", " сегодня", " завтра", " сейчас",
                    " ближайшие", " неделю", " недел", " дней", " дня", " день", " час",
                    " выходных", " выходные", " месяц",
                    "? ", " ?", "!", ".",
                    " and ", " & ", " or ",
                    " recently", " today", " tomorrow", " now",
                    " next", " week", " day", " days", " hour", " hours",
                    " month", " weekend"
                )
                // Также обрабатываем стоп-слова без ведущего пробела (после removePrefix)
                val bareStopWords = listOf(
                    "выходных ", "выходные ", "выходн ", "месяц ",
                    "сегодня ", "завтра ", "неделю ", "недел "
                )
                for (stop in stopWords) {
                    val stopIdx = after.lowercase().indexOf(stop)
                    if (stopIdx > 0) {
                        after = after.substring(0, stopIdx).trim()
                        break
                    }
                }
                // Дополнительная проверка на bare стоп-слова (с начала строки)
                val lowerAfter = after.lowercase()
                for (bare in bareStopWords) {
                    if (lowerAfter.startsWith(bare)) {
                        after = after.removePrefix(bare).trim()
                        break
                    }
                }
                // Убираем trailing вопросительные и восклицательные знаки
                after = after.trimEnd('?', '!', '.', ',', ';', ':')
                // Оставляем только первую значимую часть (до пробела, если она больше 2 символов)
                val parts = after.split(" ").filter { it.length > 2 }
                return if (parts.isNotEmpty()) parts.joinToString(" ") else after.split(" ").firstOrNull() ?: after
            }
        }
        return ""
    }

    /**
     * Извлекает количество дней из запроса о погоде.
     */
    private fun extractDaysFromQuery(query: String): Int {
        val lower = query.lowercase()
        return when {
            "месяц" in lower || "month" in lower -> 16
            "выходн" in lower || "weekend" in lower -> {
                val cal = java.util.Calendar.getInstance()
                val today = cal.get(java.util.Calendar.DAY_OF_WEEK)
                if (today == java.util.Calendar.SATURDAY) 2
                else if (today == java.util.Calendar.SUNDAY) 1
                else (java.util.Calendar.SUNDAY - today + 7) % 7 + 1
            }
            "неделю" in lower || "недел" in lower || "7 дней" in lower || "7 day" in lower || "week" in lower -> 7
            "5 дней" in lower || "5 day" in lower -> 5
            "3 дня" in lower || "3 дн" in lower || "3 day" in lower || "ближайшие" in lower || "ближайш" in lower -> 3
            "завтра" in lower || "tomorrow" in lower -> 2
            "сегодня" in lower || "today" in lower -> 1
            else -> 1
        }
    }

    /**
     * Fallback для погоды: если AI не распознал weather-запрос, проверяем ключевые слова.
     */
    private suspend fun tryWeatherFallback(query: String): AgentResponse? {
        val lower = query.lowercase()

        // Исключаем финансово-экономические запросы (цены, курсы, нефть и т.д.)
        val excludeWords = listOf(
            "цен", "нефть", "курс", "биткоин", "крипт", "доллар", "евро", "рубл",
            "акци", "фондов", "рынок", "экономик", "инфляци", "бюджет",
            "price", "oil", "stock", "market", "bitcoin", "crypto", "dollar", "euro",
            "finance", "economic", "trade"
        )
        if (excludeWords.any { lower.contains(it) }) {
            return null
        }

        val weatherKeywords = listOf(
            "погод", "прогноз", "температур", "градус", "дожд", "снег", "ветер",
            "weather", "temperature", "forecast", "rain", "snow", "wind",
            "холодн", "тепл", "жарк", "мороз"
        )
        val hasWeatherKeyword = weatherKeywords.any { lower.contains(it) }
        if (!hasWeatherKeyword) return null

        println("🔍 Fallback: запрос похож на погоду (ключевые слова: ${weatherKeywords.filter { lower.contains(it) }})")

        val weatherSkill = skillRegistry.getSkill("weather") ?: return null

        // Извлекаем город и дни
        val city = extractCityFromWeatherQuery(query, weatherKeywords)
        val days = extractDaysFromQuery(query)

        val params = mutableMapOf<String, Any>(
            "command" to "weather",
            "query" to query,
            "days" to days
        )
        // Город добавляем только если он выглядит как название города (не время/дата)
        val notTimeWords = listOf("выходн", "сегодня", "завтра", "недел", "ближайш")
        if (city.isNotBlank() && notTimeWords.none { city.lowercase().contains(it) }) {
            params["city"] = city
        }

        println("🌤️ WeatherFallback: город='${params["city"] ?: "не указан (по умолч. СПб)"}', дней=$days")

        return when (val result = weatherSkill.execute(params)) {
            is SkillResult.Success -> AgentResponse.Success(
                answer = if (isComplexForecast(query, params)) {
                    println("🌤️ WeatherFallback: сложный прогноз → AI-форматирование")
                    val rawData = result.data?.get("raw_json")?.toString() ?: result.message
                    val city = result.data?.get("city")?.toString() ?: ""
                    val source = result.data?.get("source")?.toString() ?: ""
                    val inputMap = mapOf(
                        "weather_data" to rawData,
                        "city" to city,
                        "source" to source,
                        "query" to query
                    )
                    formatFinalAnswer("Погода: $query", inputMap)
                } else {
                    result.message
                },
                thoughts = listOf("Fallback: погода (AI не распознал, но найдены ключевые слова)"),
                actions = emptyList()
            )
            is SkillResult.Error -> null // Если ошибка — пусть AI ответит
            is SkillResult.ConfirmationRequired -> AgentResponse.Error(
                error = result.question,
                details = "Требуется подтверждение"
            )
        }
    }

    /**
     * Fallback: если AI распознал QUESTION/CHAT/UNKNOWN, но запрос явно про файлы.
     * Вместо повторного AI-распознавания (слабые модели снова ошибаются),
     * сразу формируем FILE_OPERATION с list_files.
     */
    private suspend fun tryFileOperationFallback(
        query: String,
        context: String,
        originalIntentResult: IntentResult
    ): AgentResponse? {
        val lower = query.lowercase()

        // Признаки файловой операции
        val hasFileExtension = Regex("""\.\w{2,4}""").containsMatchIn(query)
        val hasFileKeywords = listOf("файл", "папк", "документ", "file", "folder", "document")
            .any { lower.contains(it) }
        val hasLocationQuery = listOf("где", "найди", "покажи", "открой", "прочитай",
            "создай", "удали", "перемести", "редактируй",
            "where", "find", "show", "open", "read", "create", "delete", "move", "edit")
            .any { lower.contains(it) }

        val seemsLikeFileOp = (hasFileExtension && hasLocationQuery) ||
                              (hasFileKeywords && hasLocationQuery)

        if (!seemsLikeFileOp) return null

        println("🔍 Fallback: запрос похож на файловую операцию")

        // Извлекаем имя файла из запроса (например, "todo.txt" из "где лежит файл todo.txt?")
        val fileName = Regex("""(\w+\.\w{2,4})""").find(query)?.value
        val fileSkill = skillRegistry.getSkill("file_system")

        if (fileSkill != null && fileName != null) {
            val params = mutableMapOf<String, Any>(
                "command" to "list_files",
                "path" to fileName,
                "recursive" to "true"
            )

            println("🔍 Fallback: ищу файл '$fileName' list_files")
            val result = fileSkill.execute(params)

            return when (result) {
                is SkillResult.Success -> AgentResponse.Success(
                    answer = result.message,
                    thoughts = listOf(
                        "Fallback: QUESTION → FILE_OPERATION/list_files",
                        "Исходное намерение: ${originalIntentResult.intent}",
                        "Исходное обоснование: ${originalIntentResult.reasoning}"
                    ),
                    actions = emptyList()
                )
                is SkillResult.Error -> {
                    println("⚠️ Fallback ошибка: ${result.message}")
                    null
                }
                is SkillResult.ConfirmationRequired -> null
            }
        }

        return null
    }

    /**
     * Проверяет, является ли запрос сложным прогнозом погоды (требует AI-форматирования).
     */
    private fun isComplexForecast(query: String, params: Map<String, Any>): Boolean {
        val lower = query.lowercase()

        // Если в params указано > 1 день — сложный
        val daysParam = when (val d = params["days"]) {
            is Int -> d
            is String -> d.toIntOrNull() ?: 0
            else -> 0
        }
        if (daysParam > 1) return true

        // Слова, указывающие на прогноз на период
        val forecastIndicators = listOf(
            "недел", "месяц", "выходн", "завтра", "прогноз",
            "forecast", "week", "month", "weekend", "tomorrow",
            "ближайш", "сегодня", "today", "дней", "дня"
        )
        if (forecastIndicators.any { lower.contains(it) }) return true

        // Только простые "сейчас" запросы — не требуют AI
        return false
    }

    private suspend fun verifyResultMatchesQuery(
        query: String,
        answer: String,
        skillName: String,
        intent: Intent
    ): String {
        if (intent != Intent.SEARCH && intent != Intent.FILE_OPERATION) return answer
        if (answer.length > 20) return answer

        println("🔍 Проверка: ответ короткий, запрашиваю AI...")
        return try {
            val response = aiRepository.sendMessage(
                messages = listOf(
                    com.pai.android.data.model.Message.createUserMessage("assistant",
                        """Пользователь спросил: "$query"
                        
                        Результат от навыка $skillName:
                        "$answer"
                        
                        Если результат не отвечает на вопрос пользователя — объясни это.
                        Если результат отвечает — просто подтверди его.
                        Дай краткий ответ."""
                    )
                ),
                systemPrompt = "Ты помощник, проверяющий корректность выполнения запроса.",
                memoryContext = ""
            )
            val text = response.getOrThrow().text
            if (text.isNotBlank()) text else answer
        } catch (e: Exception) {
            println("⚠️ Проверка результата не удалась: ${e.message}")
            answer
        }
    }

    /**
     * Проверяет, содержит ли запрос ключевые слова для анализа файла.
     * "проанализируй", "анализ", "аналитика", "analyze" + ссылка на файл.
     */

    /**
     * Анализирует содержимое файла через AI.
     */
    private suspend fun analyzeFileWithAI(content: String, filePath: String, query: String): String {
        return try {
            val prompt = """
                Пользователь попросил проанализировать файл: "$query"
                
                Путь к файлу: $filePath
                
                Содержимое файла: $content

Проанализируй файл и ответь на вопросы пользователя. В ответе:
1. Что это за файл? (тип, назначение)
2. Основная структура и формат
3. Ключевая информация из содержимого
4. Ответ на конкретный вопрос пользователя (если есть)

Дай понятный, структурированный ответ. Используй эмодзи для наглядности.
Если содержимое не соответствует ожиданиям пользователя — сообщи.
""".trimIndent()

            val response = aiRepository.sendMessage(
                messages = listOf(
                    com.pai.android.data.model.Message.createSystemMessage("file_analysis", "Ты аналитик файлов. Анализируй содержимое и отвечай на вопросы пользователя."),
                    com.pai.android.data.model.Message.createUserMessage("file_analysis", prompt)
                ),
                systemPrompt = "Анализируй содержимое файла. Дай структурированный ответ.",
                memoryContext = ""
            )

            if (response.isSuccess) {
                val text = response.getOrThrow().text.ifBlank {
                    "📄 **Содержимое файла `$filePath`:**\n\n```\n${content.take(500)}${if (content.length > 500) "\n..." else ""}\n```"
                }
                text
            } else {
                "📄 **Содержимое файла `$filePath`:**\n\n```\n${content.take(500)}${if (content.length > 500) "\n..." else ""}\n```"
            }
        } catch (e: Exception) {
            println("⚠️ AI-анализ файла не удался: ${e.message}")
            "📄 **Содержимое файла `$filePath`:**\n\n```\n${content.take(500)}${if (content.length > 500) "\n..." else ""}\n```"
        }
    }

    /**
     * Decomposes additional steps for an existing project using LLM.
     */
    private suspend fun decomposeAdditionalSteps(query: String, projectName: String, currentStepCount: Int): List<String> {
        return try {
            val prompt = buildString {
                appendLine("Decompose into steps. Same language as request.")
                appendLine("Request: " + query)
                appendLine("Project: " + projectName + " (" + currentStepCount + " steps)")
                appendLine("Return JSON array of NEW step descriptions.")
            }
            val resp = aiRepository.sendMessage(
                messages = listOf(Message.createUserMessage("decompose", prompt)),
                systemPrompt = "JSON array of strings.",
                memoryContext = ""
            )
            if (!resp.isSuccess) return emptyList()
            val text = resp.getOrThrow().text.trim()
            val s = text.indexOf('[')
            val e = text.lastIndexOf(']') + 1
            if (s < 0 || e <= s) return emptyList()
            val arr = org.json.JSONArray(text.substring(s, e))
            (0 until arr.length()).map { arr.getString(it) }.filter { it.length > 5 }
        } catch (exc: Exception) { emptyList() }
    }

    /**
     * Планирование и выполнение сложных многошаговых запросов.
     * 1. AgentPlanner → JSON-план
     * 2. Выполнение шагов через навыки
     * 3. Если задача требует цепочки рассуждений → ReActAgent
     * 4. AI-форматирование финального ответа
     */
    private suspend fun processWithPlanning(query: String, context: String, autoApprove: Boolean = false): AgentResponse? {
        println("🧠 Planner: планирую выполнение для '$query'")

        val plan = agentPlanner.plan(query, context)
        if (!plan.requiresPlanning || plan.steps.isEmpty()) {
            println("📋 Planner: простой запрос, планирование не требуется")
            return null
        }

        // Если план имеет более 2 шагов → делегируем ReActAgent
        if (plan.steps.size > 2 || plan.requiresPlanning && plan.steps.any { it.description.contains("анализ") || it.description.contains("analysis") || it.description.contains("research") }) {
            println("🤖 ReAct: сложная задача (${plan.steps.size} шагов), делегирую ReActAgent")
            return reactAgent.processRequest(
                query = query,
                context = context,
                maxSteps = 25
            )
        }

        println("📋 Planner: план (${plan.steps.size} шагов): ${plan.reasoning}")

        // Выполняем шаги последовательно
        val stepResults = mutableMapOf<String, String>()

        for ((index, step) in plan.steps.withIndex()) {
            println("⏳ Шаг ${index + 1}/${plan.steps.size}: ${step.description}")

            val stepResult = executePlanStep(step, stepResults, query)
            when (stepResult) {
                is SkillResult.Success -> {
                    println("✅ Шаг ${index + 1} успешен")
                    if (step.outputVariable != null) {
                        stepResults[step.outputVariable] = stepResult.message
                    }
                    stepResults["step_${index}"] = stepResult.message
                }
                is SkillResult.Error -> {
                    println("⚠️ Шаг ${index + 1} ошибка: ${stepResult.message}")
                    stepResults["error_step_${index}"] = stepResult.message
                }
                is SkillResult.ConfirmationRequired -> {
                    return AgentResponse.Error(
                        error = stepResult.question,
                        details = "Планировщик: требуется подтверждение"
                    )
                }
            }
        }

        // AI-форматирование финального ответа
        val finalResult = formatFinalAnswer(query, stepResults)
        println("📋 Planner: финальный ответ сформирован")

        return AgentResponse.Success(
            answer = finalResult,
            thoughts = listOf(
                "Планирование: ${plan.steps.size} шагов",
                "Обоснование: ${plan.reasoning}"
            ),
            actions = emptyList()
        )
    }

    /**
     * Выполняет один шаг плана через соответствующий навык.
     */
    private suspend fun executePlanStep(
        step: PlannerStep,
        previousResults: Map<String, String>,
        originalQuery: String
    ): SkillResult {
        return when (step.skillName) {
            "ai_chat" -> {
                val params = step.params.toMutableMap()
                val query = params["query"] as? String ?: originalQuery

                try {
                    val response = aiRepository.sendMessage(
                        messages = listOf(
                            com.pai.android.data.model.Message.createUserMessage("assistant", query)
                        ),
                        systemPrompt = "Ты умный AI-ассистент. Дай полезный и точный ответ.",
                        memoryContext = ""
                    )
                    val text = response.getOrThrow().text
                    SkillResult.Success(message = text)
                } catch (e: Exception) {
                    SkillResult.Error(message = "AI ошибка: ${e.message}")
                }
            }
            "weather" -> {
                val skill = skillRegistry.getSkill("weather")
                if (skill != null) skill.execute(step.params)
                else SkillResult.Error(message = "Навык weather не найден")
            }
            "web_search" -> {
                val skill = skillRegistry.getSkill("web_search")
                val params = step.params.toMutableMap()
                params["query"] = resolveRefs(params["query"]?.toString(), previousResults)
                if (skill != null) skill.execute(params)
                else SkillResult.Error(message = "Навык web_search не найден")
            }
            "web_fetch" -> {
                val skill = skillRegistry.getSkill("web_fetch")
                if (skill != null) skill.execute(step.params)
                else SkillResult.Error(message = "Навык web_fetch не найден")
            }
            "file_system" -> {
                val skill = skillRegistry.getSkill("file_system")
                if (skill != null) skill.execute(step.params)
                else SkillResult.Error(message = "Навык file_system не найден")
            }
            "open_file" -> {
                val skill = skillRegistry.getSkill("open_file")
                if (skill != null) skill.execute(step.params)
                else SkillResult.Error(message = "Навык open_file не найден")
            }
            else -> {
                val skill = skillRegistry.getSkill(step.skillName) ?: toolRegistry.getTool(step.skillName)?.let { ToolSkillAdapter(it) }
                if (skill != null) skill.execute(step.params)
                else SkillResult.Error(message = "Навык '${step.skillName}' не найден")
            }
        }
    }

    /**
     * Заменяет ссылки вида {var_name} значениями из предыдущих шагов.
     */
    private fun resolveRefs(value: String?, previousResults: Map<String, String>): String {
        if (value == null) return ""
        var resolved: String = value
        for ((key, result) in previousResults) {
            resolved = resolved.replace("{$key}", result)
        }
        return resolved
    }

    /**
     * AI-форматирование финального ответа из результатов всех шагов.
     */
    private suspend fun formatFinalAnswer(query: String, stepResults: Map<String, String>): String {
        val resultsSummary = stepResults.entries.joinToString("\n") { 
            "[${it.key}]: ${it.value.take(4000)}"
        }

        val isWeather = query.lowercase().contains("погод") || 
                query.lowercase().contains("weather") ||
                stepResults.containsKey("city")

        return try {
            val systemPrompt = if (isWeather) {
                """Ты — метеоролог-аналитик. Форматируй погодные данные в красивый и информативный ответ.
                Используй:
                - Эмодзи для погоды (🌤️ 🌧️ 🌬️ ❄️ ⛈️ ☀️ ☁️ 🌡️)
                - Чёткие заголовки для каждого дня
                - Упоминай осадки, ветер, температуру
                - В конце дай краткое резюме (самый холодный/тёплый день, худший/лучший)
                - Максимум 15-20 строк"""
            } else {
                "Ты полезный AI-ассистент. Форматируй результаты выполнения в красивый и понятный ответ."
            }

            val prompt = if (isWeather) {
                """Пользовательский запрос: "$query"

                Данные о погоде:
                $resultsSummary

                На основе данных составь прогноз погоды:
                - Город и дата
                - Текущая погода (если есть)
                - Таблица прогноза на каждый день (температура днём/ночью, осадки, ветер, описание)
                - Резюме: самый тёплый/холодный день, худший/лучший, тренд на период
                - Используй эмодзи и форматирование"""
            } else {
                """Пользовательский запрос: "$query"

                Результаты выполнения шагов:
                $resultsSummary

                На основе этих данных составь красивый, структурированный ответ пользователю.
                - Дай прямой и конкретный ответ на его вопрос
                - Используй эмодзи для визуального выделения
                - Сохрани все важные детали из результатов
                - Если есть даты, погода, цифры — обязательно укажи их
                - Максимум 10-15 строк"""
            }

            val response = aiRepository.sendMessage(
                messages = listOf(
                    com.pai.android.data.model.Message.createUserMessage("assistant", prompt)
                ),
                systemPrompt = systemPrompt,
                memoryContext = ""
            )
            response.getOrThrow().text
        } catch (e: Exception) {
            println("⚠️ Planner: ошибка форматирования: ${e.message}")
            stepResults.values.joinToString("\n\n").take(4000)
        }
    }

    /**
     * Добавляет текущую дату и системный контекст в промпт.
     */
    private fun buildDateAwarePrompt(basePrompt: String): String {
        val today = SimpleDateFormat("dd MMMM yyyy", Locale("ru")).format(Date())
        return """
            Today is $today.
            Your training data may be outdated — your knowledge cutoff date differs from today.
            If the request requires current data (exchange rates, weather, news, prices, dates, times,
            schedules, events "today" or "now"), use intent=SEARCH to search the internet.
            Do NOT answer from memory for time-sensitive information — perform a search instead.

            If the user asks about files (find, show, open, read) — use the file_system skill
            to work with files, do not answer from memory.

            To search for a file by name use: command=list_files, path=filename.ext, recursive=true

            FILE ATTACHMENT RULE (mandatory): When user asks to "send" or "find" a file:
            Step 1: Use command=list_files to find the file.
            Step 2: Return ONLY a SHORT file listing with 📄 emoji:
            "📂 Found N files:
             1. 📄 filename.ext (size KB)"
            Step 3: Do NOT use read_file. Do NOT show file content.
            The file is automatically attached as a downloadable document.
            Showing file content is USELESS — the user can open the attached file.

            Python execution (Chaquopy 3.12.4) is available. You can run Python code on the Android device.
            To execute Python: return intent=COMMAND, command=execute_python, code=<python_code>.
            Available pip packages: requests, aiohttp, beautifulsoup4, flask, openpyxl,
            python-docx, PyPDF2, PyYAML, plotly, sqlalchemy, aiosqlite,
            mysql-connector-python, loguru, retry, aiofiles, croniter, python-telegram-bot, psutil.
            Use 'import psutil' for system info (CPU, RAM, disks, network, uptime).
            For Python tasks, ALWAYS use execute_python instead of simulating the result.

            OFFICE DOCUMENTS: To create or read Word (.docx), Excel (.xlsx), or PowerPoint (.pptx)
            files, use the office/word/excel/pptx/powerpoint tool. Do NOT use Python for Office files —
            the office skill handles everything. Python's python-pptx is NOT available on this device.

            ADDRESSES FROM MEMORY: When using a saved address (home_address, work_address) for maps.geocode, pass the ENTIRE address string verbatim. Do NOT truncate buildings, corps, structures. Example: if fact says "г.Петергоф, ул. Парковая д. 16, к. 5, стр. 1" — pass all of it to geocode.

            ANDROID ENVIRONMENT: aarch64, 8 CPU cores (4 physical + 4 SMT), ~7.5 GB RAM,
            88 GB storage. Chaquopy embedded Python 3.12.4 — NO tkinter, NO PyQt, NO subprocess.
            File system: app sandbox at /data/data/com.pai.android/. Use /data/data/com.pai.android/cache/ for temp files.
            No os.system(), no popen. Use pure Python and available pip packages only.

            $basePrompt
        """.trimIndent()
    }

    /**
     * Обрабатывает мониторинговые задачи (watch: / email_watch:).
     * Без LLM — только проверка почты через EmailSkill.
     * Формат: "watch:from=vasya@mail.ru" или "email_watch:from=vasya@mail.ru"
     */
    private var watchLastSeenUids = mutableMapOf<String, Long>()

    private suspend fun processWatchTask(query: String): AgentResponse {
        try {
            val raw = query.removePrefix("watch:").removePrefix("email_watch:").trim()
            
            // Определяем тип поиска по префиксу
            val searchType = when {
                raw.startsWith("from=") -> "from"
                raw.startsWith("subject=") -> "subject"
                raw.startsWith("body=") -> "body"
                else -> "any"
            }
            val searchQuery = raw.removePrefix("from=").removePrefix("subject=").removePrefix("body=").trim()
            
            if (searchQuery.isBlank()) {
                return AgentResponse.Success(answer = "ok", thoughts = listOf("watch: empty"), actions = emptyList())
            }

            // Для body= проверяем реже (дорого), берём меньше писем
            val limit = if (searchType == "body") 3 else 20
            
            println("📬 WatchTask: type=$searchType query=$searchQuery")
            
            val emailSkill = skillRegistry.getSkill("email")
            if (emailSkill == null) {
                return AgentResponse.Success(answer = "ok", thoughts = listOf("watch: no email skill"), actions = emptyList())
            }

            // Получаем последние N писем
            val listResult = emailSkill.execute(mapOf(
                "command" to "email_list", "query" to "", "limit" to limit
            ))
            if (listResult !is SkillResult.Success) {
                return AgentResponse.Success(answer = "ok", thoughts = listOf("watch: list failed"), actions = emptyList())
            }

            val msg = listResult.message
            val uidPattern = Regex("""\[UID=(\d+)\]\s*\|\s*([^|]+)\s*\|\s*«([^»]+)»""")
            val matches = uidPattern.findAll(msg)
            val queryLower = searchQuery.lowercase()
            val dedupKey = "watch_$raw"
            val newMatches = mutableListOf<Pair<Long, String>>()

            for (match in matches) {
                val uid = match.groupValues[1].toLongOrNull() ?: continue
                val from = match.groupValues[2].lowercase()
                val subject = match.groupValues[3].lowercase()
                
                // Дедюп
                val lastSeen = watchLastSeenUids[dedupKey] ?: 0L
                if (uid <= lastSeen) continue

                // Фильтр: from= / subject= / body= / any
                val preMatch = when (searchType) {
                    "from" -> from.contains(queryLower)
                    "subject" -> subject.contains(queryLower)
                    "any" -> from.contains(queryLower) || subject.contains(queryLower)
                    "body" -> {
                        // body= требует чтения email — проверяем from/subject предварительно
                        from.contains(queryLower) || subject.contains(queryLower)
                    }
                    else -> false
                }
                if (!preMatch && searchType != "body") continue

                // body= — читаем содержимое и проверяем
                if (searchType == "body") {
                    val readResult = emailSkill.execute(mapOf(
                        "command" to "email_read", "uid" to uid.toString()
                    ))
                    val content = if (readResult is SkillResult.Success) {
                        readResult.message.lowercase()
                    } else ""
                    if (!content.contains(queryLower)) continue
                }

                newMatches.add(uid to match.value)
            }

            if (newMatches.isEmpty()) {
                return AgentResponse.Success(answer = "ok", thoughts = listOf("watch: no new"), actions = emptyList())
            }

            // Обновляем lastSeen на максимальный UID
            watchLastSeenUids[dedupKey] = newMatches.maxOf { it.first }

            // Читаем содержимое ПЕРВОГО нового письма
            val firstUid = newMatches.first().first
            val readResult = emailSkill.execute(mapOf(
                "command" to "email_read", "uid" to firstUid.toString()
            ))
            val content = if (readResult is SkillResult.Success) readResult.message else ""

            val answer = buildString {
                appendLine("📩 Найдено письмо (поиск: $searchQuery):")
                if (content.isNotBlank()) {
                    appendLine()
                    append(content.take(4000))
                }
            }.trim()

            println("📬 WatchTask: найдено ${newMatches.size} писем")
            return AgentResponse.Success(answer = answer, thoughts = listOf("watch: found"), actions = emptyList())
        } catch (e: Exception) {
            println("⚠️ WatchTask error: ${e.message}")
            return AgentResponse.Success(answer = "ok", thoughts = listOf("watch: error"), actions = emptyList())
        }
    }

    /**
     * Спрашивает LLM: хочет ли пользователь продолжить незавершённый проект?
     * Короткий промпт, 1 слово в ответе.
     */
    private suspend fun classifyContinuationIntent(
        query: String,
        projectName: String,
        currentStep: Int,
        totalSteps: Int
    ): Boolean {
        val trimmed = query.lowercase().trim()
        val explicitYes = setOf("да", "yes", "yep", "yeah", "ok", "okay", "continue", "go", "ага", "угу")
        val explicitNo = setOf("нет", "no", "nope", "stop", "хватит", "отмена", "cancel", "потом", "после", "nvm")
        if (trimmed in explicitYes) return true
        if (explicitNo.any { trimmed == it || trimmed.startsWith(it) }) return false
        try {
            val prompt = "User has unfinished project '$projectName' ($currentStep/$totalSteps). " +
                    "User says: '$query'. Does user want to CONTINUE the project or REJECT? " +
                    "Reply ONLY one word: CONTINUE or REJECT"
            val msg = com.pai.android.data.model.Message.createUserMessage("cls", prompt)
            val resp = aiRepository.sendMessage(
                messages = listOf(msg),
                systemPrompt = "You classify intent. Reply with exactly one word: CONTINUE or REJECT",
                memoryContext = ""
            )
            if (resp.isSuccess) {
                val answer = resp.getOrThrow().text.trim().uppercase()
                println("🧠 classifyContinuationIntent: $answer")
                if (answer.contains("CONTINUE") || answer.contains("YES")) return true
            }
        } catch (e: Exception) {
            println("⚠️ classifyContinuationIntent error: ${e.message}")
        }
        return false
    }

    companion object {
        /** Флаг: разрешены ли проактивные уведомления (управляется из ProactiveTrigger) */
        @Volatile
        var proactiveAllowed: Boolean = true
        
        var lastChatId: String? = null

        /** @deprecated Use pendingNotificationFlow instead */
        @Deprecated("Use pendingNotificationFlow")
        var pendingNotificationResult: String?
            get() = _pendingNotificationFlow.value
            set(value) { _pendingNotificationFlow.value = value }

        private val _pendingNotificationFlow = MutableStateFlow<String?>(null)

        /**
         * StateFlow для наблюдения за отложенными уведомлениями.
         * ChatDetailViewModel подписывается на него для real-time доставки.
         */
        val pendingNotificationFlow: StateFlow<String?> = _pendingNotificationFlow

        /** Если true — последнее уведомление уже отправлено в чат через onTaskResult */
        var notificationDelivered: Boolean = false

        /** Флаг: идёт ли обработка голосового запроса (для показа кнопки стоп в чате) */
        @Volatile
        var isProcessingVoice: Boolean = false

        /** Статус текущей обработки (переживает навигацию) */
        @Volatile
        var processingWorkStatus: String? = null
    }
}











