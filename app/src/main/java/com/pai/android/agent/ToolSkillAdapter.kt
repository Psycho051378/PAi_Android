package com.pai.android.agent

/**
 * Адаптер, который оборачивает AgentTool в интерфейс Skill.
 * Позволяет DecisionEngine находить и использовать Tools через SkillRegistry.
 */
class ToolSkillAdapter(
    private val tool: AgentTool
) : Skill {

    override val name: String = "tool_${tool.name}"

    override val description: String = tool.description

    /**
     * Проверяет, может ли инструмент обработать запрос.
     * Использует TaskToolRegistry.findToolsForTask() через семантический поиск,
     * либо прямое сопоставление по имени команды.
     */
    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        // Прямое сопоставление по команде
        val command = params["command"] as? String
        if (command != null && command == tool.name) return true
        
        
        // TOOL_OPERATION — проверяем соответствие конкретному инструменту
        if (intent == Intent.TOOL_OPERATION) {
            if (command != null) {
                // Маппинг команд на инструменты
                val toolMapping = mapOf(
                    "memory" to "memory",
                    "file_system" to "file_system",
                    "document_analysis" to "document_analysis",
                    "save_fact" to "memory",
                    "search_facts" to "memory",
                    "analyze_file" to "document_analysis",
                    "analyze_folder" to "document_analysis",
                    "read_file" to "file_system",
                    "list_files" to "file_system",
                    "write_file" to "file_system"
                )
                val expectedTool = toolMapping[command]
                if (expectedTool != null) return expectedTool == tool.name
            }
            // Без команды — не знаем, какой инструмент
            return false
        }
        
        // Семантический поиск по запросу (ключевые слова)
        val lowerQuery = query.lowercase()
        val toolText = "${tool.name} ${tool.description}".lowercase()
        
        // Проверяем, есть ли ключевые слова из запроса в описании инструмента
        val keywords = lowerQuery.split(" ", ",", ".", "!", "?", ":", ";")
        val matches = keywords.count { keyword ->
            keyword.length > 3 && toolText.contains(keyword)
        }
        
        return matches >= 2 || lowerQuery.contains(tool.name.replace("_", " "))
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        return try {
            // Преобразуем параметры: маппинг удобных команд во внутренние команды инструментов
            val toolParams = params.toMutableMap()
            val command = params["command"] as? String
            
            // Маппинг команд для MemoryTool
            if (command == "memory") {
                val content = params["content"] as? String ?: params["query"] as? String ?: ""
                if (content.isNotBlank()) {
                    toolParams["command"] = "save_fact"
                    // Заполняем обязательные параметры, если не указаны
                    if (!toolParams.containsKey("category")) {
                        toolParams["category"] = "user"
                    }
                    if (!toolParams.containsKey("key")) {
                        // Берём первые 50 символов как ключ
                        toolParams["key"] = content.take(50).trim().replace("\n", " ")
                    }
                    if (!toolParams.containsKey("value")) {
                        toolParams["value"] = content
                    }
                    if (!toolParams.containsKey("scope")) {
                        toolParams["scope"] = "user"
                    }
                } else {
                    toolParams["command"] = "search_facts"
                }
            }
            // Маппинг команд для FileSystemTool
            else if (command == "file_system" && !params.containsKey("subcommand")) {
                if (params.containsKey("path") && !params.containsKey("content")) {
                    toolParams["command"] = "read_file"
                } else if (params.containsKey("content")) {
                    toolParams["command"] = "write_file"
                } else {
                    toolParams["command"] = "list_files"
                }
            }
            // Маппинг команд для DocumentAnalysisTool
            else if (command == "document_analysis") {
                toolParams["command"] = "analyze_file"
                if (!toolParams.containsKey("path")) {
                    toolParams["path"] = "."
                }
            }
            
            val result = tool.execute(toolParams)
            
            when (result) {
                is ToolResult.Success -> {
                    SkillResult.Success(
                        message = result.output,
                        data = result.data,
                        responseType = ResponseType.TEXT
                    )
                }
                is ToolResult.Error -> {
                    SkillResult.Error(
                        message = result.error,
                        details = if (result.recoverable) "Попробуйте переформулировать запрос" else null
                    )
                }
                is ToolResult.ConfirmationRequired -> {
                    SkillResult.ConfirmationRequired(
                        question = result.question,
                        params = mapOf("action" to result.confirmAction)
                    )
                }
            }
        } catch (e: Exception) {
            println("❌ ToolSkillAdapter ошибка для '${tool.name}': ${e.message}")
            SkillResult.Error(
                message = "Ошибка при выполнении инструмента '${tool.name}'",
                details = e.message
            )
        }
    }
}
