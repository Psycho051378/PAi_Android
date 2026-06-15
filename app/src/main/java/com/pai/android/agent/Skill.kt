package com.pai.android.agent

import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.Message
import org.json.JSONObject

/**
 * Базовый интерфейс навыка.
 * Каждый навык самостоятельно определяет, может ли он обработать запрос.
 */
interface Skill {
    /** Уникальное имя навыка */
    val name: String
    
    /** Описание навыка для AI */
    val description: String
    
    /**
     * Проверяет, может ли навык обработать данный запрос.
     * @param intent распознанное намерение пользователя
     * @param query оригинальный запрос пользователя
     * @param params извлечённые параметры
     */
    fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean
    
    /**
     * Выполняет действие навыка.
     * @param params параметры для выполнения
     * @return результат выполнения
     */
    suspend fun execute(params: Map<String, Any>): SkillResult
}

/**
 * Типы намерений пользователя, распознаваемые AI.
 */
enum class Intent(val description: String) {
    QUESTION("User question requiring answer from AI knowledge"),
    COMMAND("Command to perform an action"),
    FILE_OPERATION("File operations (create, read, write, delete)"),
    MEMORY_QUERY("Query assistant's memory"),
    SEARCH("Search for current/realtime information on the internet"),
    CALCULATION("Calculations"),
    APP_LAUNCH("Launch an app on the device"),
    TOOL_OPERATION("Operation via tool (memory, file system, document analysis)"),
    CHAT("Regular dialogue, reminders, simple requests that don't need internet search or file operations"),
    SYSTEM("System command (settings, info)"),
    UNKNOWN("Unknown intent")
}

/**
 * Результат выполнения навыка.
 */
sealed class SkillResult {
    /** Успешное выполнение с данными для ответа */
    data class Success(
        val message: String,
        val data: Map<String, Any>? = null,
        val responseType: ResponseType = ResponseType.TEXT
    ) : SkillResult()
    
    /** Ошибка выполнения */
    data class Error(
        val message: String,
        val details: String? = null
    ) : SkillResult()
    
    /** Требуется подтверждение пользователя */
    data class ConfirmationRequired(
        val question: String,
        val params: Map<String, Any>
    ) : SkillResult()
}

/**
 * Тип ответа пользователю.
 */
enum class ResponseType {
    TEXT,           // Простой текст
    RICH_TEXT,      // Форматированный текст
    LIST,           // Список
    TABLE,          // Таблица
    ERROR,          // Ошибка
    CONFIRMATION    // Запрос подтверждения
}

/**
 * Реестр навыков.
 * Управляет регистрацией и поиском подходящих навыков.
 */
class SkillRegistry {
    private val skills = mutableMapOf<String, Skill>()
    
    fun register(skill: Skill) {
        skills[skill.name] = skill
        println("🔧 Зарегистрирован навык: ${skill.name}")
    }
    
    fun registerAll(vararg skills: Skill) {
        skills.forEach { register(it) }
    }
    
    fun getSkill(name: String): Skill? = skills[name]
    
    fun getAllSkills(): List<Skill> = skills.values.toList()
    
    fun unregister(name: String) {
        skills.remove(name)
        println("🔧 Удалён навык: $name")
    }
    
    /**
     * Находит подходящий навык для обработки запроса.
     * Если несколько навыков подходят, выбирает самый специфичный
     * (по совпадению имени навыка с командой из params).
     */
    fun findSkill(intent: Intent, query: String, params: Map<String, Any>): Skill? {
        // Собираем все подходящие навыки
        val candidates = skills.values.filter { it.canHandle(intent, query, params) }
        
        if (candidates.isEmpty()) {
            return null
        }
        
        if (candidates.size == 1) {
            println("🔍 Найден навык '${candidates.first().name}' для намерения $intent")
            return candidates.first()
        }
        
                // Несколько подходящих навыков — EXT_ ВСЕГДА В ПРИОРИТЕТЕ
        val extCandidates = candidates.filter { it.name.startsWith("ext_") }
        if (extCandidates.isNotEmpty()) {
            val ext = extCandidates.first()
            println("🔍 Внешний навык '${ext.name}' приоритетнее ${candidates.size} встроенных")
            return ext
        }
        
        // Если ext_ нет — выбираем по совпадению имени с командой
        val command = params["command"] as? String
        if (command != null) {
            val byCommand = candidates.firstOrNull { it.name == command }
            if (byCommand != null) {
                println("🔍 Найден навык '${byCommand.name}' по команде '$command'")
                return byCommand
            }
        }
        
        // Если не нашли по команде: отдаём предпочтение web_fetch над web_search
        val webFetch = candidates.firstOrNull { it.name == "web_fetch" }
        if (webFetch != null) {
            println("🔍 Найден навык web_fetch (приоритет над web_search)")
            return webFetch
        }
        
        // Берём первый подходящий
        println("🔍 Найден навык '${candidates.first().name}' (из ${candidates.size} подходящих)")
        return candidates.first()
    }

    

    
    /**
     * Возвращает описание всех навыков для AI.
     */
    fun getSkillsDescription(): String {
        val builder = StringBuilder()
        builder.append("Доступные навыки:\n\n")
        
        skills.forEach { (name, skill) ->
            builder.append("🔧 **$name**: ${skill.description}\n")
        }
        
        return builder.toString()
    }
}

/**
 * Распознаватель намерений пользователя через AI.
 * 
 * Без паттернов! AI анализирует смысл запроса и возвращает
 * намерение + параметры в JSON.
 */
class IntentRecognizer(
    private val aiRepository: AiRepository,
    private val fileManager: FileManager
) {
    
    /**
     * Распознаёт намерение пользователя через AI.
     * @param query запрос пользователя
     * @param context контекст диалога
     * @return распознанное намерение и извлечённые параметры
     */
    suspend fun recognize(query: String, context: String = "", attachedFiles: List<String>? = null): IntentResult {
        val ctxWithFiles = if (attachedFiles != null && attachedFiles.isNotEmpty()) {
            val files = attachedFiles.joinToString(", ")
            "$context\n\nUser attached these files: $files. The query refers to one of them."
        } else context
        return aiRecognize(query, ctxWithFiles)
    }
    
    /**
     * AI-распознавание намерения и параметров.
     * AI сам анализирует запрос и возвращает структурированный JSON.
     */
    private suspend fun aiRecognize(query: String, context: String): IntentResult {
        val prompt = """
            You are an intent recognizer. Analyze the user's query and determine:
            1. Intent (the user's goal)
            2. Parameters (params)
            
            IMPORTANT: Use conversation context to understand pronouns ("them", "it", "that file").
            If the query has pronouns, determine which files they refer to from the context.
            SIMPLE CHAT: Greetings, casual conversation, short reminders, questions about the agent itself — anything that does NOT require internet search or file operations is intent=CHAT. Gratitude, opinions, simple prompts are always CHAT.
            
            FILE SYSTEM CONTEXT:
            - Current directory: ${fileManager.currentDir()}
            - Available files: ${fileManager.quickList()}
            
            IMPORTANT: Use this information about real files. DO NOT invent non-existent files.
            If the user mentions a file not in the list above, assume they might be mistaken,
            or use recursive=true to search in all subfolders.
            
            CURRENT DATE: ${java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale("ru")).format(java.util.Date())}
            Your training data may be outdated. If the query asks for current/realtime information
            (weather, exchange rates, news, prices, dates, times), use intent=SEARCH.
            Do NOT answer from memory for time-sensitive queries.
            
            QUERY: "$query"
            
            CONTEXT: "$context"
            
            POSSIBLE INTENTS:
            ${Intent.entries.joinToString("\n") { "- ${it.name}: ${it.description}" }}
            
            PARAMETER RULES BY INTENT TYPE:

            IMPORTANT: If query asks to BOTH create AND execute/run Python code, use TOOL_OPERATION with command=execute_python.
            Execution is the primary goal. "создай скрипт... выполни его" → TOOL_OPERATION + execute_python.
            "напиши и выполни python" → TOOL_OPERATION + execute_python.
            "выполни python import os" → TOOL_OPERATION + execute_python.

            1. FILE_OPERATION — file operations:
               "command": "list_files" | "read_file" | "write_file" | "append_file" | "create_folder" | "delete" | "move" | "edit_file" | "format_file" | "get_file_info" | "open_file" | "web_search"
               "path": "path to file or folder"
               "source": "source file (for move)"
               "target": "target file or folder (for move)"
               "content": "text to write (for write_file/append_file)"
               "instruction": "edit instruction (for edit_file/format_file)"
               "filter_extension": "extension filter (for list_files, without dot)"
               "recursive": "true" if recursive search needed
               "tree": "'true' for tree view (for list_files only)"
               "query": "search query (for web_search only)"
               Examples:
               - "найди все .txt файлы" / "find all .txt files" → command=list_files, filter_extension=txt, recursive=true
               - "найди файл todo.txt" / "find file todo.txt" → command=list_files, path=todo.txt, recursive=true
               - "покажи структуру папок" / "show folder structure" → command=list_files, tree=true, recursive=true
               - "создай папку test" / "create folder test" → command=create_folder, path=test
               - "добавь запись Купить хлеб в файл todo.txt" / "append Buy milk to shopping.txt" → command=append_file, path=todo.txt, content=Buy milk
               - "прочитай файл notes.txt" / "read file notes.txt" → command=read_file, path=notes.txt
               - "удали файл tmp.txt" / "delete file tmp.txt" → command=delete, path=tmp.txt
               - "перемести файл notes.txt в папку archive" / "move notes.txt to archive" → command=move, source=notes.txt, target=archive
               - "когда была создана папка doc?" / "when was folder doc created?" → command=get_file_info, path=doc, attribute=creation_time
               - "какой размер файла report.txt?" / "what is size of report.txt?" → command=get_file_info, path=report.txt, attribute=size
               - "покажи информацию о файле notes.txt" / "show file info for notes.txt" → command=get_file_info, path=notes.txt, attribute=all
               - "наведи порядок в файле todo.txt" / "fix formatting in todo.txt" → command=edit_file, path=todo.txt, instruction="format as numbered list"
               - "открой файл index.html" / "open file index.html" → command=open_file, path=index.html

            5. TOOL_OPERATION - user wants to manage scheduled tasks/reminders/time-based jobs (add/list/remove/run), save/query memory, analyze files, manage notifications, or execute Python code.
               "command": "task_scheduler" (scheduled tasks: list/add/remove, accepts task_name, task_prompt, task_time) | "memory" | "file_system" | "document_analysis" | "notif_listener" (check Android notification listener status) | "execute_python"
               "content": "text to save/analyze (for memory)"
               "path": "file path (for file_system/document_analysis)"
               "code": "Python code to execute (for execute_python)"
               Examples:
               - "сохрани в память: сегодня солнечно" / "save to memory: sunny today" → command=memory, content=сегодня солнечно
               - "что я знаю о проекте?" / "what do I know about the project?" → command=memory, content=проект
               - "проанализируй файл readme.md" / "analyze readme.md" → command=document_analysis, path=readme.md
               - "статус уведомлений" / "notification status" → command=notif_listener, action=status
               - "открой настройки уведомлений" / "open notification settings" → command=notif_listener, action=open_settings
            
            6. APP_LAUNCH — user wants to launch/open an app on the device
               "query": "user's original query (for AI extraction of app name)"
               Examples:
               - "открой калькулятор" / "open calculator" → intent=APP_LAUNCH, query=открой калькулятор
               - "запусти камеру" / "launch camera" → intent=APP_LAUNCH, query=запусти камеру
               - "open settings" / "открой настройки" → intent=APP_LAUNCH, query=open settings
               - "открой хром" / "open chrome" → intent=APP_LAUNCH, query=open chrome
               - "напиши заметку" / "write a note" → intent=APP_LAUNCH, query=напиши заметку
               - "сфоткай меня" / "take a photo" → intent=APP_LAUNCH, query=сфоткай меня
               - "найди в интернете погода" / "search the web for weather" → command=web_search, query=weather
               - "найди и удали файл example.txt, проверь все папки" / "find and delete example.txt in all folders" → command=delete, path=example.txt, recursive=true
               - "перемести их в корневую папку" (context: just found shopping.txt and todo.txt) → command=move, source=shopping.txt;todo.txt, target=/
            
            2. QUESTION — user asks a question (needs AI answer, not an action)
               params not needed
            
            3. SEARCH — user needs CURRENT/REAL-TIME information from the internet
               "command": "weather" for weather queries (default: web_search for other searches)
               "query": "search query"
               "city": "city name (for weather only)"
               "days": "number of days, e.g. 1, 3, 7 (for weather only)"
               Use SEARCH for: currency exchange rates, weather, news, stock prices, current events, 
               today's date/time, any query requiring up-to-date information.
               Examples:
               - "what is dollar rate today?" → intent=SEARCH, query=usd exchange rate
               - "weather in moscow" → intent=SEARCH, command=weather, city=Moscow, days=1
               - "what's the weather in London for 3 days?" → intent=SEARCH, command=weather, city=London, days=3
               - "weekend weather" → intent=SEARCH, command=weather, days=2
               - "weather forecast" → intent=SEARCH, command=weather
               - "latest news" → intent=SEARCH, query=latest news
               - "what time in tokyo?" → intent=SEARCH, query=time tokyo
            
            4. CHAT — regular dialogue, greeting
               params not needed
            
            5. CALCULATION — math calculations
               "expression": "mathematical expression"
            
            6. MEMORY_QUERY — memory search
               "query": "text to search in memory"
            
            7. COMMAND — general command (if not FILE_OPERATION)
               params based on context
            
            RESPOND ONLY WITH JSON (no extra text):
            {
                "intent": "FILE_OPERATION|QUESTION|COMMAND|MEMORY_QUERY|CHAT|CALCULATION|TOOL_OPERATION|APP_LAUNCH|SYSTEM|UNKNOWN",
                "confidence": 0.0-1.0,
                "reasoning": "why this intent was chosen",
                "params": { }
            }
        """.trimIndent()
        
        return try {
            val response = aiRepository.sendMessage(
                messages = listOf(
                    Message.createSystemMessage("intent_recognition", prompt),
                    Message.createUserMessage("intent_recognition", query)
                ),
                systemPrompt = "You are an intent recognizer. Return only JSON.",
                memoryContext = context
            )
            
            val text = response.getOrThrow().text
            println("🤖 AI recognizer response: $text")
            parseIntentResponse(text, query)
        } catch (e: Exception) {
            println("⚠️ AI recognition error: ${e.message}")
            // Используем fallback при недоступности AI
            fallbackRecognize(query, context)
        }
    }
    
    /**
     * Извлекает путь к файлу или папке из запроса.
     * Пример: "когда была создана папка doc?" → "doc"
     */
    fun extractPathFromQuery(query: String): String {
        // Убираем знаки препинания и разделяем на слова
        // НЕ удаляем точки внутри слов (файл "todo.txt" должен остаться "todo.txt"),
        // только точки в конце слов (предложение "открой файл todo.txt.")
        val clean = query.replace("?", "").replace("!", "").replace(",", "")
        val words = clean.split(" ").filter { it.isNotBlank() }.map { it.trimEnd('.') }
        
        // Служебные слова, которые не должны возвращаться как пути (русский + английский)
        val stopWords = listOf(
            // Русские
            "папка", "файл", "документ",
            "был", "была", "создан", "создана", "изменён", "изменена",
            "когда", "какая", "какой", "покажи", "найди", "перемести",
            "создай", "удали", "прочитай", "запиши", "добавь", "в",
            "открой", "открыть", "запусти",
            "поищи", "найди", "поиск",
            // Английские
            "folder", "file", "document", "directory",
            "was", "created", "modified", "changed", "updated",
            "when", "what", "which", "show", "display", "find", "search",
            "move", "create", "delete", "remove", "read", "write", "append",
            "open", "launch", "run", "execute", "start",
            "how", "big", "size", "info", "information", "properties", "details",
            "all", "every", "list", "tree", "view",
            "in", "at", "to", "from", "for", "the", "a", "an"
        )
        
        // Ищем слова, которые могут быть путями (содержат ., / или выглядят как имена файлов)
        for (i in words.indices) {
            val word = words[i]
            
            // Если слово содержит расширение (.txt, .md и т.д.) - это точно путь
            if (word.contains(".") && word.length > 1 && word !in stopWords) {
                return word
            }
            
            // Если слово после служебных слов "папка", "файл", "документ"
            if (word in listOf("папка", "файл", "документ", "folder", "file") && i + 1 < words.size) {
                val next = words[i + 1]
                if (next.isNotBlank() && next !in stopWords) {
                    return next
                }
            }
            
            // Если слово содержит / - это путь к папке
            if (word.contains("/") && word !in stopWords) {
                return word
            }
        }
        
        // Пытаемся найти слово, которое не является стоп-словом и выглядит как имя файла/папки
        val potentialPaths = words.filter { word ->
            word !in stopWords && 
            word.length > 1 && 
            word.any { it.isLetterOrDigit() } // Должно содержать буквы или цифры
        }
        
        // Исключаем слова, которые состоят только из русских букв (скорее всего не имена файлов)
        val filteredPaths = potentialPaths.filter { word ->
            !word.matches(Regex("^[а-яА-ЯёЁ]+$")) || 
            word.length > 3 // Допускаем короткие русские слова, если они длиннее 3 символов
        }
        
        if (filteredPaths.isNotEmpty()) {
            return filteredPaths.first()
        }
        
        // Если ничего не нашли - возвращаем пустую строку
        return ""
    }
    
    /**
     * Резервное распознавание намерений при недоступности AI.
     * Использует простые паттерны для самых частых запросов.
     */
    private fun fallbackRecognize(query: String, context: String): IntentResult {
        val lower = query.lowercase().trim()
        println("🔄 Использую fallback распознавание для: '$query'")
        
        // Простые паттерны для файловых операций
        return when {
            // Поиск файлов (русский)
            "найди" in lower && (".txt" in lower || "txt файл" in lower || "текстовые файлы" in lower) -> 
                IntentResult(
                    intent = Intent.FILE_OPERATION,
                    confidence = 0.7f,
                    params = mapOf(
                        "command" to "list_files",
                        "filter_extension" to "txt",
                        "recursive" to "true"
                    ),
                    reasoning = "Fallback: поиск .txt файлов"
                )
            
            // Поиск файлов (английский)
            "find" in lower && ".txt" in lower ->
                IntentResult(
                    intent = Intent.FILE_OPERATION,
                    confidence = 0.7f,
                    params = mapOf(
                        "command" to "list_files",
                        "filter_extension" to "txt",
                        "recursive" to "true"
                    ),
                    reasoning = "Fallback: find .txt files"
                )
            
            // Структура папок (русский + английский)
            "покажи структуру" in lower || "структуру папок" in lower || "список файлов" in lower ||
            "в красивом виде" in lower || "древовидную" in lower || "дерево папок" in lower ||
            "folder structure" in lower || "directory tree" in lower || "tree view" in lower ||
            "show all files" in lower || "list files" in lower ->
                IntentResult(
                    intent = Intent.FILE_OPERATION,
                    confidence = 0.8f,
                    params = mapOf(
                        "command" to "list_files",
                        "recursive" to "true",
                        "tree" to "true"
                    ),
                    reasoning = "Fallback: показать структуру папок в древовидном формате"
                )
            
            // Переместить текстовые файлы (русский + английский)
            ("перемести" in lower && "текстовые файлы" in lower) ||
            ("move" in lower && "text files" in lower) ->
                IntentResult(
                    intent = Intent.FILE_OPERATION,
                    confidence = 0.7f,
                    params = mapOf(
                        "command" to "move",
                        "source" to "*.txt",
                        "recursive" to "true"
                    ),
                    reasoning = "Fallback: переместить все .txt файлы"
                )
            
            // Создать файл (русский + английский)
            ("создай файл" in lower || "create file" in lower) -> {
                val fileName = if (".txt" in lower) "new_file.txt"
                else if (".md" in lower) "new_file.md"
                else "new_file.txt"
                IntentResult(
                    intent = Intent.FILE_OPERATION,
                    confidence = 0.6f,
                    params = mapOf("command" to "write_file", "path" to fileName, "content" to ""),
                    reasoning = "Fallback: создать файл"
                )
            }
            
            // Открыть файл (русский + английский)
            ("открой файл" in lower || "open file" in lower) ->
                IntentResult(
                    intent = Intent.FILE_OPERATION,
                    confidence = 0.6f,
                    params = mapOf("command" to "open_file", "path" to extractPathFromQuery(lower)),
                    reasoning = "Fallback: открыть файл"
                )
            
            // Веб-поиск (русский + английский)
            ("найди в интернете" in lower || "поищи" in lower || 
             "search the web" in lower || "search for" in lower || "look up" in lower) ->
                IntentResult(
                    intent = Intent.COMMAND,
                    confidence = 0.6f,
                    params = mapOf("command" to "web_search", "query" to lower),
                    reasoning = "Fallback: веб-поиск"
                )
            
            // Редактирование файлов (русский + английский)
            (("наведи порядок" in lower || "отформатируй" in lower || "исправь" in lower || "сократи" in lower ||
              "format" in lower || "fix" in lower || "edit" in lower) && 
             ("файл" in lower || ".txt" in lower || ".md" in lower || "file" in lower)) ->
                IntentResult(
                    intent = Intent.FILE_OPERATION,
                    confidence = 0.7f,
                    params = mapOf(
                        "command" to "edit_file",
                        "path" to extractPathFromQuery(lower),
                        "instruction" to "отформатируй файл согласно инструкции пользователя"
                    ),
                    reasoning = "Fallback: редактирование файла"
                )
            
            // Вопросы о файлах (дата создания, размер) — русский + английский
            (("когда" in lower && ("создан" in lower || "создана" in lower || "папк" in lower || "файл" in lower)) ||
             ("какой размер" in lower && ("файл" in lower || "папк" in lower)) ||
             ("when was" in lower && ("created" in lower || "folder" in lower || "file" in lower)) ||
             ("size of" in lower && ("file" in lower || "folder" in lower))) ->
                IntentResult(
                    intent = Intent.FILE_OPERATION,
                    confidence = 0.7f,
                    params = mapOf(
                        "command" to "get_file_info",
                        "path" to extractPathFromQuery(lower),
                        "attribute" to if ("размер" in lower || "size" in lower) "size" else "creation_time"
                    ),
                    reasoning = "Fallback: вопрос о файле/папке"
                )
            
            // Вопросы (русский + английский)
            "?" in lower || 
            lower.startsWith("кто") || lower.startsWith("что") || 
            lower.startsWith("где") || lower.startsWith("когда") || 
            lower.startsWith("почему") || lower.startsWith("как") ||
            lower.startsWith("who") || lower.startsWith("what") || 
            lower.startsWith("where") || lower.startsWith("when") || 
            lower.startsWith("why") || lower.startsWith("how") ->
                IntentResult(
                    intent = Intent.QUESTION,
                    confidence = 0.8f,
                    reasoning = "Fallback: вопрос пользователя"
                )
            
            // Приветствия (мультиязычные)
            "привет" in lower || "здравствуй" in lower || "hello" in lower || "hi" in lower ||
            "hey" in lower || "good morning" in lower || "good afternoon" in lower ->
                IntentResult(
                    intent = Intent.CHAT,
                    confidence = 0.9f,
                    reasoning = "Fallback: приветствие"
                )
            
            // По умолчанию
            else -> IntentResult(
                intent = Intent.UNKNOWN,
                confidence = 0.3f,
                reasoning = "Fallback: не удалось распознать запрос"
            )
        }
    }
    
    /**
     * Парсит ответ AI с намерением.
     */
    private fun parseIntentResponse(text: String, originalQuery: String): IntentResult {
        return try {
            val jsonStart = text.indexOf('{')
            val jsonEnd = text.lastIndexOf('}') + 1
            val jsonText = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                text.substring(jsonStart, jsonEnd)
            } else {
                text
            }
            
            val json = JSONObject(jsonText)
            val intentName = json.getString("intent")
            val confidence = json.optDouble("confidence", 0.5).toFloat()
            val reasoning = json.optString("reasoning", "")
            
            val intent = try {
                Intent.valueOf(intentName)
            } catch (e: Exception) {
                println("⚠️ Неизвестное намерение: $intentName")
                Intent.UNKNOWN
            }
            
            val params = mutableMapOf<String, Any>()
            if (json.has("params")) {
                val paramsObj = json.getJSONObject("params")
                paramsObj.keys().forEach { key ->
                    params[key] = paramsObj.optString(key, "")
                }
            }
            
            IntentResult(
                intent = intent,
                confidence = confidence,
                params = params,
                reasoning = reasoning
            )
        } catch (e: Exception) {
            println("⚠️ Ошибка парсинга намерения: ${e.message}")
            IntentResult(
                intent = Intent.UNKNOWN,
                confidence = 0.3f,
                reasoning = "Ошибка парсинга: ${e.message}"
            )
        }
    }
}

/**
 * Результат распознавания намерения.
 */
data class IntentResult(
    val intent: Intent,
    val confidence: Float,
    val params: Map<String, Any> = emptyMap(),
    val reasoning: String = ""
)


