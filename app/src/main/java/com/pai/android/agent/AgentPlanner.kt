package com.pai.android.agent

import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.Message
import javax.inject.Inject
import javax.inject.Singleton

/**
 * План выполнения, сгенерированный AI для сложного запроса.
 * Содержит последовательность шагов, которые нужно выполнить.
 */
data class AgentPlan(
    val requiresPlanning: Boolean,
    val steps: List<PlannerStep> = emptyList(),
    val reasoning: String = ""
)

/**
 * Один шаг плана.
 * @param skillName имя навыка для выполнения
 * @param params параметры для навыка
 * @param outputVariable имя переменной, в которую сохранить результат (для передачи между шагами)
 * @param description описание шага для логирования
 */
data class PlannerStep(
    val skillName: String,
    val params: Map<String, Any>,
    val outputVariable: String? = null,
    val description: String = ""
)

/**
 * Генератор планов выполнения на основе AI.
 * Модель-независим - использует AiRepository.
 */
@Singleton
class AgentPlanner @Inject constructor(
    private val aiRepository: AiRepository,
    private val skillRegistry: SkillRegistry
) {

    companion object {
        private const val TAG = "AgentPlanner"

        // All skills the planner recognizes for multi-step decomposition
        private val KNOWN_SKILLS = mapOf(
            "weather" to "get weather forecast by city and date range",
            "web_search" to "search the web (Google/Tavily API or DuckDuckGo fallback)",
            "web_fetch" to "download and read content from a specific URL",
            "file_system" to "file operations: list_files, read_file, write_file, append_file, create_folder, delete, move, edit_file, format_file, get_file_info",
            "open_file" to "open a file in an external app",
            "email" to "send, check, list, read emails via IMAP/SMTP - can attach files",
            "office" to "create and read Office documents: Word (.docx), Excel (.xlsx), PowerPoint (.pptx)",
            "calendar" to "read, search, create, and delete calendar events",
            "geo" to "set up geofence reminders and location-based tasks",
            "contacts" to "search and add phone contacts",
            "sms" to "send SMS messages",
            "call" to "make phone calls",
            "clipboard" to "read and write the system clipboard",
            "task_scheduler" to "create, list, delete scheduled tasks (cron jobs)",
            "location" to "get current GPS location",
            "maps" to "maps & navigation: geocode (address to coordinates), reverse_geocode (coords to address), places_search (find POIs near location - fuel, cafe, atm, restaurant, pharmacy, hospital, supermarket, parking, hotel, school), route (driving directions with distance and time), maps_open (open location in Google Maps or Yandex Maps app)",
            "memory" to "save and search facts in permanent memory. Use save_fact to store user info (name, address, phone, preferences). Use search_facts to look up saved info",
            "home" to "control smart home devices (router, lights, etc.)",
            "ai_chat" to "direct AI answer without tools"
        )
    }

    /**
     * Анализирует запрос и возвращает план выполнения.
     * Простые запросы → one-step план.
     * Сложные (праздники, курсы + погода и т.д.) → multi-step.
     */
    suspend fun plan(query: String, context: String): AgentPlan {
        // Проверяем на простые запросы (один навык) - без AI
        val simpleMatch = matchSimpleSkill(query)
        if (simpleMatch != null) {
            return AgentPlan(
                requiresPlanning = false,
                steps = listOf(
                    PlannerStep(
                        skillName = simpleMatch.first,
                        params = simpleMatch.second,
                        description = "Выполнение запроса"
                    )
                )
            )
        }

        // Сложный запрос - просим DeepSeek распланировать
        return try {
            println("🧠 $TAG: запрашиваю план для '$query'")
            val prompt = buildPlanPrompt(query, context)

            val response = aiRepository.sendMessage(
                messages = listOf(
                    Message.createUserMessage("assistant", prompt)
                ),
                systemPrompt = "You are an action planner for an AI agent. Break user queries into multi-step JSON plans when multiple tools are needed.",
                memoryContext = ""
            )

            parsePlan(response.getOrThrow().text, query)
        } catch (e: Exception) {
            println("⚠️ $TAG: ошибка планирования: ${e.message}")
            // Fallback: одношаговый план через AI chat
            createFallbackPlan(query)
        }
    }

    /**
     * Простой матчинг для очевидных запросов (без AI).
     */
    private fun matchSimpleSkill(query: String): Pair<String, Map<String, Any>>? {
        val lower = query.lowercase()

        // Погода без уточнений → one-step
        val weatherPatterns = listOf("погод", "weather", "температур", "градус", "дожд", "снег")
        if (weatherPatterns.any { lower.contains(it) }) {
            val city = extractCity(query)
            val days = extractDays(query)
            val params = mutableMapOf<String, Any>(
                "command" to "weather",
                "query" to query,
                "days" to days
            )
            if (city != null) params["city"] = city
            return Pair("weather", params)
        }

        return null
    }

    /**
     * Формирует промпт для AI-планировщика.
     */
    private fun buildPlanPrompt(query: String, context: String): String {
        val today = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale("ru")).format(java.util.Date())
        val skillDescriptions = KNOWN_SKILLS.entries.joinToString("\n") {
            "- ${it.key}: ${it.value}"
        }

        return """Today is $today.
User query: "$query"
Context: "$context"

AVAILABLE SKILLS:
$skillDescriptions

Analyze the user query. If answering requires more than one tool call - create a multi-step plan. Otherwise set requires_planning=false with a single step.

OUTPUT FORMAT (JSON only, no extra text, no markdown):
{
  "requires_planning": true/false,
  "steps": [
    {
      "skill": "skill_name",
      "params": { "command": "action", ... },
      "result_as": "variable_name"
    }
  ],
  "reasoning": "why this plan"
}

RULES:
1. If simple (1 skill needed) → requires_planning=false, steps=[1 step]
2. Parameter "command" is REQUIRED for all skills except ai_chat
3. For weather: command=weather, city (city name), days (number), query (original query)
4. For web_search: command=web_search, query (search string)
5. For web_fetch: command=web_fetch, url (target URL), maxChars (max chars, optional)
6. For file_system: command=list_files|read_file|write_file|append_file|create_folder|delete|move|edit_file|get_file_info, path (file path), recursive=true|false
7. For email: command=email_send|email_check|email_list|email_read, to (recipient), subject, body, file_path (attachment)
8. For office: action=word_create|excel_create|pptx_create, path (output path)
9. For contacts: command=contacts_search|contacts_add, query (search) or name+phone (add)
10. For sms: command=sms_send, phone (recipient), text (message body)
11. For call: command=call_call, phone (number)
12. For calendar: action=list_upcoming|create|search|delete
13. For geo: action=create, lat, lon, radius, label, description
14. For clipboard: action=read|write, text (for write)
15. For task_scheduler: command=create|list|delete|schedule
16. For location: action=current (get fresh GPS coordinates), action=status (check GPS on/off)
17. For location queries: ALWAYS use location skill with action=current. NEVER rely on memory/context for location - those may be outdated.
18. DO NOT answer location/coordinates from memory or context. Always call the location skill.
19. For maps: action=geocode (query=address), action=reverse_geocode (lat, lon), action=places_search (lat, lon, type=fuel|cafe|atm|restaurant|pharmacy|hospital|supermarket|parking|hotel|school, radius=1000, limit=5), action=route (from_lat, from_lon, to_lat, to_lon), action=maps_open (lat, lon [or to_lat, to_lon])
20. For route building: ALWAYS call location(current) first for current coords, then maps(geocode) for destination if needed, then maps(route), then maps(maps_open) to show on actual map app. DO NOT generate verbal walking/driving directions from AI knowledge — use maps tools.
21. Results from previous steps are available as {variable_name} in params of following steps
22. For memory: command=save_fact to store info (category=locations for addresses, category=personal_info for name/birth/profession, category=contacts for phone/email, category=preferences for likes/hobbies). Also: command=search_facts to look up saved facts, command=get_fact to get specific fact by category+key
23. When user says "запомни", "сохрани", "remember", "save my", "my address is", "my phone is" — use memory.save_fact. Do NOT use file_system.write_file for personal info — use memory instead

EXAMPLES:
{
  "requires_planning": false,
  "steps": [{"skill": "weather", "params": {"command": "weather", "city": "Moscow", "days": 3}}]
}

{
  "requires_planning": true,
  "steps": [
    {"skill": "web_search", "params": {"command": "web_search", "query": "current weather Moscow"}, "result_as": "weather_data"},
    {"skill": "ai_chat", "params": {"query": "Summarize this weather: {weather_data}"}}
  ],
  "reasoning": "Search weather info first, then format the answer"
}

{
  "requires_planning": true,
  "steps": [
    {"skill": "office", "params": {"command": "word", "query": "Create doc with weather summary"}, "result_as": "doc_path"},
    {"skill": "email", "params": {"command": "email_send", "to": "user@example.com", "subject": "Weather Report", "body": "Here is the weather report", "file_path": "{doc_path}"}}
  ],
  "reasoning": "Create document first, then email it as attachment"
}

{"}]}
  "requires_planning": true,
  "steps": [
    {"skill": "web_search", "params": {"command": "web_search", "query": "праздничные дни в России май 2026"}, "result_as": "holidays"},
    {"skill": "weather", "params": {"command": "weather", "city": "Санкт-Петербург", "days": 7, "query": "$query"}, "result_as": "weather_data"},
    {"skill": "ai_chat", "params": {"query": "отформатируй прогноз погоды на праздники: {holidays} + {weather_data}"}}
  ],
  "reasoning": "Узнаём даты праздников, получаем погоду, форматируем ответ"
}

{
  "requires_planning": true,
  "steps": [
    {"skill": "location", "params": {"action": "current"}, "result_as": "my_location"},
    {"skill": "maps", "params": {"action": "places_search", "lat": "{my_location.latitude}", "lon": "{my_location.longitude}", "type": "fuel", "radius": "2000", "limit": "3"}, "result_as": "poi"},
    {"skill": "maps", "params": {"action": "route", "from_lat": "{my_location.latitude}", "from_lon": "{my_location.longitude}", "to_lat": "{poi.nearest_lat}", "to_lon": "{poi.nearest_lon}"}, "result_as": "route"},
    {"skill": "maps", "params": {"action": "maps_open", "from_lat": "{my_location.latitude}", "from_lon": "{my_location.longitude}", "to_lat": "{poi.nearest_lat}", "to_lon": "{poi.nearest_lon}"}}
  ],
  "reasoning": "Get GPS, find nearest gas station, calculate route, open in maps app"
}

{
  "requires_planning": true,
  "steps": [
    {"skill": "location", "params": {"action": "current"}, "result_as": "my_location"},
    {"skill": "maps", "params": {"action": "geocode", "query": "Государственный Эрмитаж, Санкт-Петербург"}, "result_as": "destination"},
    {"skill": "maps", "params": {"action": "route", "from_lat": "{my_location.latitude}", "from_lon": "{my_location.longitude}", "to_lat": "{destination.lat}", "to_lon": "{destination.lon}"}, "result_as": "route"},
    {"skill": "maps", "params": {"action": "maps_open", "from_lat": "{my_location.latitude}", "from_lon": "{my_location.longitude}", "to_lat": "{destination.lat}", "to_lon": "{destination.lon}"}}
  ],
  "reasoning": "Get current location, geocode the Hermitage, calculate route, show on maps"
}

{
  "requires_planning": false,
  "steps": [{"skill": "memory", "params": {"command": "save_fact", "category": "locations", "key": "home_address", "value": "г.Петергоф, ул. Парковая д.16", "scope": "global", "confidence": 1.0}}],
  "reasoning": "User provided home address — save to permanent memory for later use in routes"
}

Пользовательский запрос: "$query"
Ответь JSON."""
    }

    /**
     * Парсит JSON-план из ответа AI.
     */
    private fun parsePlan(text: String, query: String): AgentPlan {
        return try {
            // Извлекаем JSON из ответа (может быть обёрнут в ```json ... ```)
            val jsonStr = extractJson(text) ?: return createFallbackPlan(query)

            val json = org.json.JSONObject(jsonStr)
            val requiresPlanning = json.optBoolean("requires_planning", false)
            val reasoning = json.optString("reasoning", "")

            val stepsArray = json.optJSONArray("steps")
            val steps = if (stepsArray != null) {
                (0 until stepsArray.length()).map { i ->
                    val stepJson = stepsArray.getJSONObject(i)
                    val paramsJson = stepJson.optJSONObject("params") ?: org.json.JSONObject()

                    val params = mutableMapOf<String, Any>()
                    paramsJson.keys().forEach { key ->
                        when (val value = paramsJson.get(key)) {
                            is String -> params[key] = value
                            is Int -> params[key] = value
                            is Double -> params[key] = value
                            is Boolean -> params[key] = value.toString()
                            else -> params[key] = value?.toString() ?: ""
                        }
                    }

                    PlannerStep(
                        skillName = stepJson.getString("skill"),
                        params = params,
                        outputVariable = stepJson.optString("result_as", null),
                        description = stepJson.optString("description", stepJson.optString("skill", ""))
                    )
                }
            } else {
                emptyList()
            }

            AgentPlan(
                requiresPlanning = requiresPlanning,
                steps = steps,
                reasoning = reasoning
            )
        } catch (e: Exception) {
            println("⚠️ $TAG: ошибка парсинга плана: ${e.message}")
            createFallbackPlan(query)
        }
    }

    /**
     * Извлекает JSON из текста (поддерживает ```json ... ``` и чистый JSON).
     */
    private fun extractJson(text: String): String? {
        val codeBlock = Regex("""```(?:json)?\s*\n?(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.getOrNull(1)?.trim()
        if (codeBlock != null) return codeBlock

        // Чистый JSON без обёртки
        val jsonPattern = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.value
        if (jsonPattern != null) return jsonPattern

        return null
    }

    /**
     * Fallback: простой одношаговый план через AI chat.
     */
    private fun createFallbackPlan(query: String): AgentPlan {
        return AgentPlan(
            requiresPlanning = false,
            steps = listOf(
                PlannerStep(
                    skillName = "ai_chat",
                    params = mapOf("query" to query),
                    description = "Ответ AI на общий вопрос"
                )
            ),
            reasoning = "Fallback: не удалось распланировать, отвечаю напрямую"
        )
    }

    /**
     * Извлекает название города из запроса.
     */
    private fun extractCity(query: String): String? {
        val lower = query.lowercase()
        val patterns = listOf(
            "в " to Regex("""в ([а-яё\-]+)""", RegexOption.IGNORE_CASE),
            "city " to Regex("""(?:in|at) ([a-z\-]+)""", RegexOption.IGNORE_CASE)
        )

        for ((prefix, regex) in patterns) {
            val idx = lower.indexOf(prefix)
            if (idx >= 0) {
                val result = regex.find(query)?.groupValues?.getOrNull(1)
                if (result != null && result.length > 2) return result
            }
        }
        return null
    }

    /**
     * Извлекает количество дней из запроса.
     */
    private fun extractDays(query: String): Int {
        val lower = query.lowercase()
        return when {
            "месяц" in lower || "month" in lower -> 16 // макс, что даёт Open-Meteo
            "выходн" in lower || "weekend" in lower -> {
                val cal = java.util.Calendar.getInstance()
                val today = cal.get(java.util.Calendar.DAY_OF_WEEK)
                // Если сегодня СБ или ВС - показываем только эти 2 дня
                // Иначе считаем дни до конца недели (СБ + ВС)
                if (today == java.util.Calendar.SATURDAY) 2
                else if (today == java.util.Calendar.SUNDAY) 1
                else (java.util.Calendar.SUNDAY - today + 7) % 7 + 1
            }
            "недел" in lower || "week" in lower -> 7
            "5 дней" in lower || "5 day" in lower -> 5
            "3 дня" in lower || "3 day" in lower -> 3
            "ближайш" in lower -> 3
            "завтра" in lower || "tomorrow" in lower -> 2
            "сегодня" in lower || "today" in lower -> 1
            else -> 3
        }
    }
}
