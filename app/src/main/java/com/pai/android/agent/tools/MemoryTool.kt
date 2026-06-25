package com.pai.android.agent.tools

import com.pai.android.agent.AgentTool
import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ToolResult
import com.pai.android.data.repository.MemoryRepository
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.model.Message

/**
 * Инструмент для работы с памятью (фактами и дневными записями).
 * Умеет парсить естественно-языковое содержимое на отдельные факты через AI.
 */
class MemoryTool constructor(
    private val memoryRepository: MemoryRepository,
    private val aiRepository: AiRepository? = null
) : BaseAgentTool() {
    
    override val name: String = "memory"
    
    override val description: String = """
        Assistant memory operations.
        Search facts, save new information, analyze daily records.
        Facts stored in three scopes: user, ai, global.
    """.trimIndent()
    
    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["search_facts", "save_fact", "get_fact", "search_daily", "summarize_period"],
                    "description": "Команда для выполнения"
                },
                "query": {
                    "type": "string",
                    "description": "Поисковый запрос (для search_facts, search_daily)"
                },
                "scope": {
                    "type": "string",
                    "enum": ["user", "ai", "global", "all"],
                    "description": "Область памяти (user - информация о пользователе, ai - об ассистенте, global - общая информация)",
                    "default": "all"
                },
                "category": {
                    "type": "string",
                    "enum": ["personal_info", "ai_info", "preferences", "contacts", "locations", "projects", "context", "tasks"],
                    "description": "Категория факта (для save_fact, get_fact). personal_info — о пользователе, ai_info — об ассистенте, preferences — предпочтения пользователя"
                },
                "key": {
                    "type": "string",
                    "description": "Ключ факта (для save_fact, get_fact)"
                },
                "value": {
                    "type": "string",
                    "description": "Значение факта (для save_fact)"
                },
                "confidence": {
                    "type": "number",
                    "minimum": 0,
                    "maximum": 1,
                    "description": "Уверенность в факте (0-1, для save_fact)",
                    "default": 0.9
                },
                "tags": {
                    "type": "string",
                    "description": "Теги через запятую (для save_fact)"
                },
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 100,
                    "description": "Лимит результатов",
                    "default": 10
                },
                "start_date": {
                    "type": "string",
                    "description": "Начальная дата в формате YYYY-MM-DD (для summarize_period)"
                },
                "end_date": {
                    "type": "string",
                    "description": "Конечная дата в формате YYYY-MM-DD (для summarize_period)"
                }
            },
            "required": ["command"]
        }
    """.trimIndent()
    
    override val requiresConfirmation: Boolean = false
    
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            // Маппинг: LocalReAct описывает memory(action, text, query), а MemoryTool ждёт command
            val command = if (params.containsKey("command")) {
                getStringParam(params, "command")
            } else {
                when (getStringParam(params, "action")) {
                    "search" -> "search_facts"
                    "save" -> "save_fact"
                    "recall" -> "get_fact"
                    else -> null
                }
            } ?: return ToolResult.Error("Параметр 'command' не найден или имеет неверный тип")
            
            when (command) {
                "search_facts" -> executeSearchFacts(params)
                "save_fact" -> executeSaveFact(params)
                "get_fact" -> executeGetFact(params)
                "search_daily" -> executeSearchDaily(params)
                "summarize_period" -> executeSummarizePeriod(params)
                else -> ToolResult.Error("Неизвестная команда: $command")
            }
        } catch (e: Exception) {
            ToolResult.Error("Ошибка выполнения: ${e.message}")
        }
    }
    
    private suspend fun executeSearchFacts(params: Map<String, Any>): ToolResult {
        val query = getStringParam(params, "query")
        val scope = params["scope"] as? String ?: "all"
        val limit = params["limit"] as? Int ?: 10
        
        var facts = if (scope == "all") {
            memoryRepository.searchRelevantFacts(query, limit)
        } else {
            memoryRepository.searchFactsInScope(scope, query, limit)
        }
        
        // Если SQL LIKE не нашёл (например, latin vs cyrillic mismatch) — fallback к общему контексту
        if (facts.isEmpty()) {
            facts = memoryRepository.getFactsForPromptWithScope(query, limit = 20)
        }
        
        return if (facts.isNotEmpty()) {
            val output = StringBuilder()
            output.append("Найдено фактов: ${facts.size}\n\n")
            
            facts.groupBy { it.scope }.forEach { (scopeGroup, scopeFacts) ->
                output.append("${getScopeEmoji(scopeGroup)} $scopeGroup:\n")
                scopeFacts.forEach { fact ->
                    output.append("- ${fact.key}: ${fact.value}")
                    if (fact.confidence < 1.0f) {
                        output.append(" (уверенность: ${String.format("%.1f", fact.confidence)})")
                    }
                    if (!fact.tags.isNullOrEmpty()) {
                        output.append(" [${fact.tags}]")
                    }
                    output.append("\n")
                }
                output.append("\n")
            }
            
            ToolResult.Success(
                output = output.toString(),
                data = mapOf(
                    "query" to query,
                    "scope" to scope,
                    "total_facts" to facts.size,
                    "facts" to facts.map { it.toMap() },
                    "goal_achieved" to true
                )
            )
        } else {
            ToolResult.Success(
                output = "По запросу \"$query\" фактов не найдено",
                data = mapOf("query" to query, "scope" to scope, "total_facts" to 0, "goal_achieved" to true)
            )
        }
    }
    
    private suspend fun executeSaveFact(params: Map<String, Any>): ToolResult {
        // LocalReAct передаёт text (содержимое) и query (поиск/описание) — маппим
        // Категория без явного указания: не предполагаем personal_info — AI сам разберёт
        val category = if (params.containsKey("category")) getStringParam(params, "category") else ""
        val key = if (params.containsKey("key")) getStringParam(params, "key") else params["query"]?.toString()?.take(80) ?: ""
        val value = if (params.containsKey("value")) getStringParam(params, "value") else params["text"]?.toString() ?: ""
        val confidence = (params["confidence"] as? Number)?.toFloat() ?: 0.9f
        val tags = params["tags"] as? String
        
        // Если категория не указана — AI парсинг сам определит, fallback на global
        val effectiveCategory = category.ifBlank { "global" }
        
        // Если value содержит несколько фактов (длинный текст) — пробуем распарсить через AI
        if (value.length > 30 && aiRepository != null && (key.isBlank() || key.length > 40 || key == value.take(50))) {
            val parsedFacts = parseFactsWithAI(value, effectiveCategory)
            if (parsedFacts.isNotEmpty()) {
                var savedCount = 0
                for ((cat, k, v, conf, sc, tg) in parsedFacts) {
                    val resolvedScope = sc ?: when {
                        cat.contains("personal", ignoreCase = true) -> "user"
                        cat.contains("ai", ignoreCase = true) -> "ai"
                        else -> "user"
                    }
                    val resolvedTags = if (tg.isNullOrBlank()) tags else tg
                    val resolvedConf = if (conf != null) conf else confidence
                    memoryRepository.insertPermanentFact(PermanentMemory(
                        category = cat, key = k, value = v,
                        confidence = resolvedConf, scope = resolvedScope, tags = resolvedTags
                    ))
                    savedCount++
                }
                return ToolResult.Success(
                    output = "Сохранено $savedCount фактов из текста:\n" +
                        parsedFacts.joinToString("\n") { (cat, k, v, conf, _, _) ->
                            "  [$cat] $k = $v (${ String.format("%.0f", (conf ?: 0.9f) * 100f) }%)"
                        },
                    data = mapOf("saved_count" to savedCount, "goal_achieved" to true)
                )
            }
        }
        
        // Определяем scope: сначала из params (если это не просто 'global' по умолчанию), потом по категории
        val explicitScope = params["scope"] as? String
        val scope = if (explicitScope != null && explicitScope != "global") {
            explicitScope  // LLM явно указала user/ai — уважаем
        } else {
            // LLM не указала scope или сказала 'global' — определяем по категории
            when {
                effectiveCategory.contains("personal", ignoreCase = true) -> "user"
                effectiveCategory.contains("preferences", ignoreCase = true) -> "user"
                effectiveCategory.contains("contacts", ignoreCase = true) -> "user"
                effectiveCategory.contains("ai", ignoreCase = true) -> "ai"
                effectiveCategory.contains("locations", ignoreCase = true) -> "global"
                else -> explicitScope ?: "global"
            }
        }
        
        val scopeForDisplay = getScopeEmoji(scope)
        memoryRepository.insertPermanentFact(PermanentMemory(
            category = effectiveCategory, key = key, value = value,
            confidence = confidence, scope = scope, tags = tags
        ))
        
        return ToolResult.Success(
            output = "$scopeForDisplay Факт сохранён: [$effectiveCategory] $key = $value (${String.format("%.0f", confidence * 100f)}%)",
            data = mapOf(
                "category" to effectiveCategory, "key" to key, "value" to value,
                "scope" to scope, "goal_achieved" to true
            )
        )
    }
    
    /**
     * Парсит естественно-языковой текст на отдельные факты через AI.
     */
    private suspend fun parseFactsWithAI(content: String, defaultCategory: String): List<FactCandidate> {
        val ai = aiRepository ?: return emptyList()
        return try {
            val prompt = """
                Extract individual facts from user text.
                Each fact: category, key (snake_case, English), value (in user language).
                
                Categories:
                - personal_info: info about the HUMAN user (name, date, birth place, location)
                - ai_info: info about the AI assistant itself (name, role, personality)
                - preferences: user likes/dislikes (food, hobbies)
                - contacts: phone, email
                - global: projects, skills, context
                
                Analyze the text contextually — if it mentions the AI assistant's own info, use ai_info.
                
                Text: "$content"
                
                Reply strictly with JSON array:
                [{"category":"...", "key":"...", "value":"...", "confidence":0.9, "tags":"personal"}]
                
                Example:
                "My name is John Smith, born January 15 1985 in Chicago, living in New York"
                → [
                  {"category":"personal_info", "key":"name", "value":"John Smith", "confidence":1.0, "tags":"personal,name"},
                  {"category":"personal_info", "key":"birth_date", "value":"1985-01-15", "confidence":0.9, "tags":"personal"},
                  {"category":"personal_info", "key":"birth_place", "value":"Chicago", "confidence":0.9, "tags":"personal"},
                  {"category":"personal_info", "key":"location", "value":"New York", "confidence":0.9, "tags":"personal,location"}
                ]
            """.trimIndent()
            
            val response = ai.sendMessage(
                messages = listOf(Message.createUserMessage("memory_parse", prompt)),
                systemPrompt = "You are a fact extraction system. Reply with JSON only.",
                memoryContext = ""
            )
            val text = response.getOrThrow().text
            val jsonStart = text.indexOf('[')
            val jsonEnd = text.lastIndexOf(']') + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()
            
            val json = org.json.JSONArray(text.substring(jsonStart, jsonEnd))
            val facts = mutableListOf<FactCandidate>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val cat = obj.optString("category", defaultCategory)
                val k = obj.optString("key", "")
                val v = obj.optString("value", "")
                if (k.isNotBlank() && v.isNotBlank()) {
                    val c = obj.optDouble("confidence", 0.8).toFloat()
                    val t = obj.optString("tags", "")
                    val sc = when {
                        cat.contains("personal") || cat.contains("contact") || cat.contains("prefer") -> "user"
                        cat.contains("ai") -> "ai"
                        else -> "global"
                    }
                    facts.add(FactCandidate(cat, k, v, c, sc, t.ifBlank { null }))
                }
            }
            facts
        } catch (e: Exception) {
            println("⚠️ MemoryTool: AI fact parsing failed: ${e.message}")
            emptyList()
        }
    }
    
    private data class FactCandidate(
        val category: String, val key: String, val value: String,
        val confidence: Float?, val scope: String?, val tags: String?
    )
    
    private suspend fun executeGetFact(params: Map<String, Any>): ToolResult {
        val category = getStringParam(params, "category")
        val key = getStringParam(params, "key")
        
        val fact = memoryRepository.getFactByCategoryAndKey(category, key)
        
        return if (fact != null) {
            ToolResult.Success(
                output = "Найден факт:\nКатегория: ${fact.category}\nКлюч: ${fact.key}\nЗначение: ${fact.value}\nScope: ${fact.scope}\nУверенность: ${fact.confidence}${if (!fact.tags.isNullOrEmpty()) "\nТеги: ${fact.tags}" else ""}",
                data = fact.toMap() + mapOf("goal_achieved" to true)
            )
        } else {
            ToolResult.Success(
                output = "Факт не найден: категория=$category, ключ=$key",
                data = mapOf("found" to false, "category" to category, "key" to key, "goal_achieved" to true)
            )
        }
    }
    
    private suspend fun executeSearchDaily(params: Map<String, Any>): ToolResult {
        val query = getStringParam(params, "query")
        val limit = params["limit"] as? Int ?: 10
        
        val entries = memoryRepository.searchDailyEntries(query, limit)
        
        return if (entries.isNotEmpty()) {
            val output = StringBuilder()
            output.append("Найдено дневных записей: ${entries.size}\n\n")
            
            entries.forEach { entry ->
                output.append("📅 ${entry.date}:\n")
                val preview = if (entry.content.length > 200) entry.content.take(200) + "..." else entry.content
                output.append("$preview\n")
                if (!entry.tags.isNullOrEmpty()) {
                    output.append("Теги: ${entry.tags}\n")
                }
                output.append("---\n")
            }
            
            ToolResult.Success(
                output = output.toString(),
                data = mapOf(
                    "query" to query,
                    "total_entries" to entries.size,
                    "entries" to entries.map { it.toMap() },
                    "goal_achieved" to true
                )
            )
        } else {
            ToolResult.Success(
                output = "По запросу \"$query\" дневных записей не найдено",
                data = mapOf("query" to query, "total_entries" to 0, "goal_achieved" to true)
            )
        }
    }
    
    private suspend fun executeSummarizePeriod(params: Map<String, Any>): ToolResult {
        val startDate = params["start_date"] as? String
        val endDate = params["end_date"] as? String
        
        // TODO: Реализовать суммаризацию периода
        // Пока заглушка
        return ToolResult.Success(
            output = "Суммаризация периода с $startDate по $endDate (функция в разработке)",
            data = mapOf("start_date" to (startDate ?: ""), "end_date" to (endDate ?: ""), "goal_achieved" to true)
        )
    }
    
    private fun getScopeEmoji(scope: String): String {
        return when (scope) {
            "user" -> "👤"
            "ai" -> "🤖"
            "global" -> "🌍"
            else -> "❓"
        }
    }
}

// Расширение для преобразования PermanentMemory в Map
private fun PermanentMemory.toMap(): Map<String, Any> {
    return mapOf<String, Any>(
        "id" to id,
        "category" to category,
        "key" to key,
        "value" to value,
        "confidence" to confidence,
        "scope" to scope,
        "tags" to (tags ?: ""),
        "created_at" to createdAt
    )
}

// Расширение для преобразования DailyMemory в Map
private fun com.pai.android.data.model.DailyMemory.toMap(): Map<String, Any> {
    return mapOf(
        "id" to id,
        "date" to date,
        "content" to content,
        "tags" to tags,
        "created_at" to createdAt
    )
}