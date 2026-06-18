package com.pai.android.agent

import com.pai.android.data.model.Message
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.repository.MemoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ReAct agent with preliminary planning (Plan‑and‑Execute).
 *
 * Before executing a task, the agent estimates the required number of steps
 * and dynamically sets the limit (from 30 to 100 steps).
 * This allows solving both simple and complex tasks without code changes.
 */
@Singleton
class ReActAgent @Inject constructor(
    private val aiRepository: AiRepository,
    private val memoryRepository: MemoryRepository,
    private val toolRegistry: ToolRegistry,
    private val defaultDispatcher: CoroutineDispatcher,
    private val fileManager: FileManager
) {

    suspend fun processRequest(
        query: String,
        context: String = "",
        maxSteps: Int = 30  // fallback, usually not used
    ): AgentResponse {
        return withContext(defaultDispatcher) {
            try {
                val memoryContext = buildMemoryContext(query)
                // ---- PRELIMINARY PLANNING ----
                val estimated = estimateRequiredSteps(query, memoryContext)
                val effectiveMaxSteps = (estimated * 2).coerceIn(30, 100)
                println("🔮 Estimated steps: $estimated → limit set to $effectiveMaxSteps")

                val toolsDesc = toolRegistry.getToolsDescription()
                val fsContext = getFilesystemContext()

                val requiredFiles = extractRequiredFiles(query)
                val createdFiles = mutableSetOf<String>()
                val readCount = mutableMapOf<String, Int>()
                val recentCalls = mutableMapOf<String, Int>()

                val systemPrompt = buildSystemPrompt(toolsDesc, memoryContext, fsContext)

                val messages = mutableListOf<Message>()
                messages.add(Message.createSystemMessage("react", systemPrompt))
                messages.add(Message.createUserMessage("react", query))

                val thoughts = mutableListOf<String>()
                val actions = mutableListOf<ActionResult>()

                for (step in 1..effectiveMaxSteps) {
                    println("🔧 ReAct step $step/$effectiveMaxSteps")

                    if (step == effectiveMaxSteps - 2) {
                        messages.add(Message.createUserMessage("react",
                            "Only 2 steps left. If task is complete, return 'done': true."))
                    }

                    val response = aiRepository.sendMessage(
                        messages = messages,
                        systemPrompt = "You are a ReAct agent. Reply with JSON only.",
                        memoryContext = memoryContext
                    )
                    if (!response.isSuccess) {
                        return@withContext AgentResponse.Error(error = "LLM error: ${response.exceptionOrNull()?.message}")
                    }
                    val text = response.getOrThrow().text.trim()

                    if (text.isBlank()) {
                        saveToMemory(query, actions)
                        return@withContext AgentResponse.Error(error = "LLM failed to respond")
                    }

                    // Try to parse as JSON
                    val json = parseJson(text)
                    if (json == null) {
                        val rawAnswer = text
                        saveToMemory(query, actions)
                        return@withContext AgentResponse.Success(
                            answer = rawAnswer,
                            thoughts = thoughts,
                            actions = actions
                        )
                    }

                    // Check for done
                    val answer = json.optString("answer", "")
                    val done = json.optBoolean("done", false)
                    if (done) {
                        saveToMemory(query, actions)
                        return@withContext AgentResponse.Success(
                            answer = answer,
                            thoughts = thoughts,
                            actions = actions
                        )
                    }

                    val actionName = normalizeToolName(json.optString("action", ""))
                    val params = json.optJSONObject("params")?.toMap()
                        ?: json.optJSONObject("parameters")?.toMap()
                        ?: emptyMap()
                    val thought = json.optString("thought", "")

                    if (actionName.isBlank()) {
                        saveToMemory(query, actions)
                        return@withContext AgentResponse.Success(
                            answer = text,
                            thoughts = thoughts,
                            actions = actions
                        )
                    }

                    thoughts.add(thought.ifBlank { "Step $step: $actionName" })
                    println("🔧 ReAct: $actionName(${params.keys.joinToString(",")})")

                    // Deterministic key for repeat detection
                    val callKey = when {
                        actionName == "file_system" && params["command"] == "write_file" -> {
                            val p = params["path"] as? String ?: ""
                            "${actionName}:write_file:$p"
                        }
                        else -> {
                            val sortedParams = params.entries.sortedBy { it.key.toString() }
                            val paramsStr = sortedParams.joinToString(",") { (k, v) ->
                                "$k=${valueToString(v)}"
                            }
                            "$actionName:$paramsStr"
                        }
                    }

                    recentCalls[callKey] = (recentCalls[callKey] ?: 0) + 1
                    val repeatCount = recentCalls[callKey]!!

                    // Only warn about repeated reads (don't block - content may change via write)
                    if (actionName == "file_system" && params["command"] == "read_file") {
                        val p = params["path"] as? String
                        if (p != null) {
                            val rc = readCount.getOrDefault(p, 0)
                            readCount[p] = rc + 1
                            if (rc >= 4) {
                                messages.add(Message.createUserMessage("react",
                                    "Note: reading $p for the ${rc+1}th time. If content hasn't changed - move on."))
                            }
                        }
                    }

                    // Anti-loop: force stop at 3+ repeats
                    if (repeatCount > 2) {
                        println("⚠️ Repeat ${callKey} (${repeatCount}x)")
                        if (repeatCount >= 3) {
                            // Собираем последние успешные результаты вместо "forced stop"
                            val lastResults = actions.filterIsInstance<ActionResult.Success>().takeLast(5)
                                .joinToString("\n\n") { it.observation }
                            val answer = if (lastResults.isNotBlank()) {
                                "📄 Вот что удалось получить:\n\n$lastResults"
                            } else {
                                thoughts.lastOrNull() ?: ""
                            }
                            saveToMemory(query, actions)
                            return@withContext AgentResponse.Success(
                                answer = answer,
                                thoughts = thoughts,
                                actions = actions
                            )
                        }
                        messages.add(Message.createUserMessage("react",
                            "Stop. You already have the data. Produce the final answer now."
                        ))
                        continue
                    }

                    // Allow overwriting existing files (LLM may have improvements)
                    if (actionName == "file_system" && params["command"] == "write_file") {
                        val p = params["path"] as? String
                        if (p != null && createdFiles.contains(p)) {
                            println("⚠️ Overwriting existing file: $p")
                        }
                    }

                    val tool = toolRegistry.getTool(actionName)
                    if (tool == null) {
                        val error = "Tool '$actionName' not found"
                        println("❌ $error")
                        messages.add(Message.createUserMessage("react",
                            "Error: $error. Available tools: ${toolRegistry.getAllTools().map { it.name }.joinToString(", ")}"))
                        continue
                    }

                    val toolResult = try {
                        tool.execute(normalizeParams(actionName, params))
                    } catch (e: Exception) {
                        ToolResult.Error("Error: ${e.message}")
                    }

                    when (toolResult) {
                        is ToolResult.Success -> {
                            val resultText = formatToolResult(actionName, toolResult)
                            messages.add(Message.createUserMessage("react",
                                "Result of $actionName: $resultText"))
                            actions.add(ActionResult.Success(
                                toolName = actionName,
                                observation = resultText,
                                data = toolResult.data
                            ))
                            println("✅ $actionName succeeded")

                            // После успешного read_multiple — сразу к ответу
                            if (actionName == "file_system" && params["command"] == "read_multiple") {
                                messages.add(Message.createUserMessage("react",
                                    "All files read. Proceed to final answer now."))
                            }

                            if (actionName == "file_system") {
                                val command = params["command"] as? String ?: ""
                                val path = params["path"] as? String
                                    ?: toolResult.data?.get("path") as? String
                                    ?: (toolResult.data?.get("file") as? String)
                                if (!path.isNullOrBlank() && (command == "write_file" || command == "create_report")) {
                                    createdFiles.add(path)
                                    println("📄 File written: $path")
                                    if (requiredFiles.isNotEmpty() && createdFiles.size < requiredFiles.size) {
                                        val remaining = requiredFiles - createdFiles
                                        messages.add(Message.createUserMessage("react",
                                            "File $path created. Remaining to create: $remaining."))
                                    }
                                } else if (command == "list_files" || command == "analyze_directory") {
                                    println("📂 Directory scanned: ${if (path.isNullOrBlank()) "workspace root" else path}")
                                    if (command == "list_files" && params["recursive"] == true) {
                                        messages.add(Message.createUserMessage("react",
                                            "Directory listing complete (recursive). All nested files are included. Proceed to next step."))
                                    }
                                }
                            } else if (actionName == "weather" || actionName == "web_search") {
                                messages.add(Message.createUserMessage("react",
                                    "Data received via $actionName. Can use for next steps."))
                            }
                        }
                        is ToolResult.Error -> {
                            val errorText = toolResult.error
                            messages.add(Message.createUserMessage("react",
                                "Error from $actionName: $errorText"))
                            actions.add(ActionResult.Error(
                                toolName = actionName,
                                observation = errorText,
                                error = errorText
                            ))
                            println("❌ $actionName error: $errorText")
                        }
                        is ToolResult.ConfirmationRequired -> {
                            messages.add(Message.createUserMessage("react",
                                "$actionName requires confirmation: ${toolResult.question}"))
                            actions.add(ActionResult.ConfirmationRequired(
                                toolName = actionName,
                                observation = toolResult.question,
                                question = toolResult.question,
                                confirmAction = toolResult.confirmAction
                            ))
                            println("⚠️ $actionName requires confirmation")
                        }
                    }
                }

                return@withContext AgentResponse.Success(
                    answer = "Step limit reached ($effectiveMaxSteps). Actions performed:\n" +
                        thoughts.joinToString("\n") { "- $it" },
                    thoughts = thoughts,
                    actions = actions
                )

            } catch (e: Exception) {
                AgentResponse.Error(
                    error = "ReAct error: ${e.message}",
                    details = e.stackTraceToString()
                )
            }
        }
    }

    // ===== PRELIMINARY PLANNING =====

    private suspend fun estimateRequiredSteps(query: String, context: String): Int {
        val planningPrompt = """
You are a planner. Estimate how many steps (actions) the agent will need to accomplish the following task.
The agent can use tools: weather, web_search, file_system (write_file, read_file, read_multiple), web_fetch, app_launch, memory.
Each tool call is one step. The final answer {"answer": "...", "done": true} also counts as a step.
Return ONLY a single integer – the number of steps. No explanation.

Task: $query
        """.trimIndent()

        val messages = listOf(Message.createUserMessage("planner", planningPrompt))
        val response = aiRepository.sendMessage(
            messages = messages,
            systemPrompt = "You are a planner. Reply with a number only.",
            memoryContext = ""
        )
        return if (response.isSuccess) {
            val text = response.getOrThrow().text.trim()
            val steps = text.toIntOrNull()
            when {
                steps != null -> steps.coerceIn(10, 100)
                else -> 30
            }
        } else {
            30
        }
    }

    // ===== SYSTEM PROMPT =====

    private fun buildSystemPrompt(
        toolsDesc: String,
        memoryContext: String,
        fsContext: String
    ): String {
        val dateInfo = getCurrentDateInfo()
        return """
$dateInfo

You are an agent that performs tasks using tools. Respond ONLY with JSON.

BEHAVIOR:
- Follow the user's instructions EXACTLY. If they ask for Word — create Word, not PowerPoint. If they ask for 5 pages — create 5 pages. Do not substitute your own judgment for what was requested.
- Verify your work before responding. The tool result tells you what was actually created — read it. Do NOT describe content, colors, effects, or formatting that the tool did not actually produce.
- Generate content matching the user's requested scope. If they ask for detailed text with N pages/slides, provide N pages/slides worth of actual content.
- Do not search the internet unless the user explicitly asks for up-to-date or external information. For generic/sample tasks, create the files directly.

TASK MANAGEMENT:
- If a task consists of several steps (e.g., fetch data and then save to files), try to first collect all data, then create all files.
- Do not repeat already performed actions with the same parameters. If a tool was already called and returned success, do not call it again.
- After reading files or fetching data, immediately produce the final answer. Do NOT re-read files or re-fetch URLs.
- Every file should be created once. If a file already exists, do not attempt to overwrite it.
- After completing all actions reply with {"answer": "...", "done": true}.

FILE SYSTEM USAGE:
- To list/scan files and folders: use file_system with command="list_files", path="" (or "."), recursive=true
- To get a summary of file types and counts: use file_system with command="analyze_directory", path="" (or ".")
- To write results: use file_system with command="write_file", path="reports/filename.md", content="..."
- NEVER use path="/" for write_file — use a proper filename under reports/ or projects/
- ALWAYS start with list_files or analyze_directory to understand what exists before creating anything

PROJECT INTEGRITY ANALYSIS:
- When checking a project for errors, read ALL project files completely using file_system (command=read_file).
- Compare imports, function calls, and API routes across files. If script.js calls a function that does not exist in the backend, report it.
- Verify that all dependencies listed in requirements.txt are actually used in the code.
- Point out any inconsistencies, missing files, unclosed tags, or syntax errors.

TOOLS:
$toolsDesc

MEMORY:
${if (memoryContext.isNotBlank()) memoryContext else "No saved facts"}

FILES:
$fsContext

RESPONSE FORMAT (JSON ONLY):
- {"action": "tool_name", "params": {...}, "thought": "explanation"}
- {"answer": "response to user", "done": true}
        """.trimIndent()
    }

    // ===== HELPER FUNCTIONS =====

    private fun getCurrentDateInfo(): String {
        val now = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, EEEE", Locale("ru"))
        return "Today: ${now.format(formatter)}"
    }

    private fun extractRequiredFiles(query: String): List<String> {
        val pattern = Pattern.compile("""[\w\/\-]+\.(md|txt|json|html|xml|csv)""", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(query)
        val files = mutableListOf<String>()
        while (matcher.find()) {
            files.add(matcher.group())
        }
        return files.distinct()
    }

    private fun formatToolResult(toolName: String, result: ToolResult.Success): String {
        val output = result.output
        // For file reading – return up to 15000 characters to see full content
        if (toolName == "file_system") {
            val data = result.data
            val command = data?.get("command") as? String ?: ""
            if (command == "read_file" || command == "read_multiple") {
                return if (output.length > 15000) output.take(15000) + "\n...(truncated)" else output
            }
        }
        return if (output.length > 3000) output.take(3000) + "\n...(truncated)" else output
    }

    private fun parseJson(text: String): JSONObject? {
        return try {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}') + 1
            if (start >= 0 && end > start) JSONObject(text.substring(start, end)) else null
        } catch (e: Exception) {
            println("⚠️ JSON parse error: ${e.message}")
            null
        }
    }

    private fun normalizeToolName(name: String): String = when (name.lowercase()) {
        "weather", "forecast", "погода", "погоду" -> "weather"
        "file_system", "filesystem", "file", "fs", "файлы", "файл" -> "file_system"
        "web_search", "search", "поиск", "websearch", "найди" -> "web_search"
        "web_fetch", "fetch", "webfetch", "read_url", "загрузить" -> "web_fetch"
        "memory", "mem", "память" -> "memory"
        "app_launch", "launch", "app", "запустить", "открыть" -> "app_launch"
        "document_analysis", "document", "doc", "анализ", "документ" -> "document_analysis"
        else -> name
    }

    private fun normalizeParams(toolName: String, params: Map<String, Any>): MutableMap<String, Any> {
        val mutable = params.toMutableMap()
        when (toolName) {
            "weather" -> {
                if (!mutable.containsKey("command")) {
                    val city = mutable["city"] as? String ?: mutable["town"] as? String
                        ?: mutable["location"] as? String ?: mutable["query"] as? String ?: ""
                    mutable["city"] = city
                    mutable["days"] = mutable["days"] ?: 1
                    mutable["command"] = "forecast"
                }
            }
            "web_search" -> {
                if (!mutable.containsKey("command")) {
                    val q = mutable["query"] as? String ?: mutable["q"] as? String ?: mutable["text"] as? String ?: ""
                    mutable["query"] = q
                    mutable["command"] = "search"
                }
            }
            "web_fetch" -> {
                val url = mutable["url"] as? String ?: mutable["link"] as? String ?: mutable["href"] as? String ?: ""
                if (url.isNotBlank()) mutable["url"] = url
            }
            "file_system" -> {
                // Normalize path: "/" and "." mean workspace root
                val rawPath = mutable["path"] as? String
                if (rawPath == "/" || rawPath == ".") {
                    mutable["path"] = ""
                }
                
                if (!mutable.containsKey("command")) {
                    mutable["command"] = when {
                        mutable.containsKey("paths") || mutable.containsKey("files") -> "read_multiple"
                        mutable.containsKey("content") && mutable.containsKey("path") -> "write_file"
                        mutable.containsKey("content") -> "create_report"
                        mutable.containsKey("path") -> "read_file"
                        else -> "list_files"
                    }
                } else {
                    // If LLM specified command=write_file with path="/" or empty, fix it
                    val cmd = mutable["command"] as? String
                    val path = mutable["path"] as? String
                    if (cmd == "write_file" && (path.isNullOrBlank() || path == "/" || !path.contains("."))) {
                        val content = mutable["content"] as? String ?: ""
                        val filename = "reports/workspace_analysis_${System.currentTimeMillis()}.md"
                        mutable["path"] = filename
                        println("📋 Fixed file_system write path: / → $filename")
                    }
                }
            }
        }
        return mutable
    }

    private fun valueToString(value: Any): String = when (value) {
        is String -> value
        is Number -> value.toString()
        is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(";") { "${it.key}=${valueToString(it.value ?: "null")}" }
        is Collection<*> -> value.joinToString(",") { valueToString(it ?: "null") }
        else -> value.toString()
    }

    private suspend fun buildMemoryContext(query: String): String {
        return try {
            val facts = memoryRepository.searchRelevantFacts(query, limit = 25)
            val daily = memoryRepository.searchDailyEntries(query, limit = 5)
            val parts = mutableListOf<String>()
            if (facts.isNotEmpty()) {
                parts.add("Facts:\n" + facts.joinToString("\n") { "- ${it.key}: ${it.value}" })
            }
            if (daily.isNotEmpty()) {
                parts.add("Entries:\n" + daily.joinToString("\n") { "${it.date}: ${it.content.take(100)}" })
            }
            parts.joinToString("\n\n")
        } catch (e: Exception) { "" }
    }

    private fun getFilesystemContext(): String {
        return try {
            val files = fileManager.quickList()
            "Existing files:\n$files"
        } catch (e: Exception) { "" }
    }

    private suspend fun saveToMemory(query: String, actions: List<ActionResult>) {
        try {
            val files = actions.filterIsInstance<ActionResult.Success>()
                .filter { it.toolName == "file_system" }
                .mapNotNull { it.data?.get("path")?.toString() }
                .filter { it.isNotBlank() }
            if (files.isNotEmpty()) {
                memoryRepository.saveDailyEntry(
                    "ReAct: $query\nCreated: ${files.joinToString(", ")}",
                    listOf("agent_actions")
                )
            }
        } catch (_: Exception) {}
    }
}

// ===== Data types =====

sealed class ActionResult {
    abstract val toolName: String
    abstract val observation: String
    open val error: String = ""
    open val question: String = ""
    open val confirmAction: String = ""
    val isError: Boolean get() = this is Error

    data class Success(
        override val toolName: String,
        override val observation: String,
        val data: Map<String, Any>? = null
    ) : ActionResult()

    data class Error(
        override val toolName: String = "error",
        override val observation: String,
        override val error: String
    ) : ActionResult()

    data class ConfirmationRequired(
        override val toolName: String = "confirmation",
        override val observation: String,
        override val question: String,
        override val confirmAction: String
    ) : ActionResult()
}

sealed class AgentResponse {
    data class Success(
        val answer: String,
        val thoughts: List<String>,
        val actions: List<ActionResult>,
        val attachments: List<com.pai.android.data.model.Attachment> = emptyList()
    ) : AgentResponse()

    data class Error(
        val error: String,
        val details: String? = null
    ) : AgentResponse()
}

private fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val value = get(key)
        when (value) {
            is String -> map[key] = value
            is Number -> map[key] = value
            is Boolean -> map[key] = value
            is JSONObject -> map[key] = value.toString()
            else -> map[key] = value.toString()
        }
    }
    return map
}

private fun <K, V> Map<K, V>.toSortedMap(): Map<K, V> {
    return toList()
        .sortedBy { it.first.toString() }
        .toMap(LinkedHashMap())
}