package com.pai.android.agent

import com.pai.android.agent.tools.WebFetchTool
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.Message

/**
 * Навык загрузки и парсинга HTML страниц.
 * Делегирует всю работу WebFetchTool, затем форматирует через AI.
 */
class WebFetchSkill(
    private val aiRepository: AiRepository? = null
) : Skill {

    override val name: String = "web_fetch"

    override val description: String = "Fetch and read web page content by URL: get page text in Markdown format"

    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        if (params["command"] == "web_fetch" || params["command"] == "fetch") return true
        if (intent == Intent.SEARCH && query.contains("https://")) {
            val lower = query.lowercase()
            val fetchWords = listOf("загрузи", "открой", "скачай", "fetch", "open", "download")
            if (fetchWords.any { lower.startsWith(it) }) return true
        }
        return false
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val url = params["url"] as? String
            ?: (params["query"] as? String)?.let { extractUrl(it) }
            ?: return SkillResult.Error(message = "Не указан URL для загрузки")

        println("🌐 WebFetchSkill → WebFetchTool: загружаю '$url'")

        val tool = WebFetchTool()
        val toolParams = mutableMapOf<String, Any>("url" to url)
        (params["maxChars"] as? Int)?.let { toolParams["max_chars"] = it }

        return when (val result = tool.execute(toolParams)) {
            is ToolResult.Success -> {
                val formatted = formatResult(result.output, url)
                SkillResult.Success(
                    message = formatted,
                    data = mapOf("url" to url, "content_length" to result.output.length),
                    responseType = ResponseType.TEXT
                )
            }
            is ToolResult.Error -> {
                SkillResult.Error(message = "Не удалось загрузить страницу", details = result.error)
            }
            is ToolResult.ConfirmationRequired -> {
                SkillResult.Error(message = "Страница требует подтверждения", details = result.question)
            }
        }
    }

    private suspend fun formatResult(rawContent: String, url: String): String {
        val ai = aiRepository ?: return "📄 **Содержимое страницы:** $url\n\n$rawContent"

        return try {
            val response = ai.sendMessage(
                messages = listOf(Message.createUserMessage("format_fetch",
                    """
                    Отформатируй содержимое веб-страницы для показа пользователю.
                    
                    Требования:
                    - Сохрани заголовки (можно с ##)
                    - Сделай краткое резюме в начале (3-5 предложений)
                    - Выдели ключевые даты, имена, цифры
                    - Разбей на логические разделы
                    - Используй эмодзи для разделов
                    - Не добавляй свои комментарии, просто отформатируй
                    
                    Контент:
                    ${rawContent.take(12000)}
                    """.trimIndent()
                )),
                systemPrompt = "Ты форматируешь содержимое веб-страниц. Отвечай на том же языке, что и контент.",
                memoryContext = ""
            )
            response.getOrThrow().text
        } catch (e: Exception) {
            println("⚠️ WebFetchSkill: AI formatting failed: ${e.message}")
            "📄 **Содержимое страницы:** $url\n\n${rawContent.take(5000)}"
        }
    }

    private fun extractUrl(text: String): String? {
        return Regex("https?://[^\\s\"]+").find(text)?.value
    }
}
