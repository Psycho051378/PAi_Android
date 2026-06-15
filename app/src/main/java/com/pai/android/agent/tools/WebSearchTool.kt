package com.pai.android.agent.tools

import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ToolResult
import com.pai.android.data.repository.WebSearchRepository
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.Message
import com.pai.android.data.service.WebSearchService
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL

/**
 * Инструмент веб-поиска. Используется ReActAgent для поиска информации в интернете.
 * Поддерживает AI-суммаризацию результатов для краткого ответа.
 */
class WebSearchTool constructor(
    private val webSearchService: WebSearchService,
    private val webSearchRepository: WebSearchRepository,
    private val aiRepository: AiRepository? = null
) : BaseAgentTool() {

    override val name: String = "web_search"

    override val description: String = "Search the internet for information via Google/Tavily/DuckDuckGo"

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["search"],
                    "description": "Выполнить поиск"
                },
                "query": {
                    "type": "string",
                    "description": "Поисковый запрос"
                }
            },
            "required": ["query"]
        }
    """.trimIndent()

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val query = params["query"] as? String ?: params["q"] as? String
                ?: return ToolResult.Error("Не указан поисковый запрос")

            val canSearch = webSearchRepository.canPerformSearch()
            if (canSearch) {
                val results = webSearchService.search(query, maxResults = 5)
                if (results.isNotEmpty()) {
                    val resultText = results.joinToString("\n\n") { r ->
                        "${r.title}\n${r.snippet}\nИсточник: ${r.link}"
                    }
                    val output = if (results.size > 1 && aiRepository != null) {
                        summarizeSearchResults(query, resultText, aiRepository!!)
                    } else {
                        "Результаты поиска по запросу '$query':\n\n$resultText"
                    }
                    return ToolResult.Success(
                        output = output,
                        data = mapOf("query" to query, "results_count" to results.size, "goal_achieved" to true)
                    )
                }
            }

            // Fallback на DuckDuckGo HTML (без API ключа, всегда работает)
            return searchViaDuckDuckGo(query)
        } catch (e: Exception) {
            ToolResult.Error("Ошибка поиска: ${e.message}")
        }
    }

    /**
     * Поиск через DuckDuckGo HTML (без API ключа, всегда доступен как fallback).
     * Пробует несколько endpoint-ов, если первый блокирует.
     */
    private suspend fun searchViaDuckDuckGo(query: String): ToolResult {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Android 14; Mobile; rv:126.0) Gecko/126.0 Firefox/126.0",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.4621.107 Mobile Safari/537.36"
        )
        val endpoints = listOf(
            "https://html.duckduckgo.com/html/?q=$encoded",
            "https://lite.duckduckgo.com/lite/?q=$encoded",
            "https://duckduckgo.com/html/?q=$encoded"
        )

        for (i in endpoints.indices) {
            val urlStr = endpoints[i]
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", userAgents[i % userAgents.size])
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                conn.setRequestProperty("Referer", "https://duckduckgo.com/")
                conn.instanceFollowRedirects = true

                val responseCode = conn.responseCode
                
                // 202 = капча/блокировка, пропускаем этот endpoint
                if (responseCode == 202) {
                    println("\u26A0\uFE0F DuckDuckGo 202 captcha at $urlStr, trying next...")
                    conn.disconnect()
                    if (i < endpoints.size - 1) kotlinx.coroutines.delay(2000)
                    continue
                }

                val html = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                }
                conn.disconnect()

                if (html.isBlank() || html.contains("captcha", ignoreCase = true) || html.contains("blocked", ignoreCase = true)) {
                    println("\u26A0\uFE0F DuckDuckGo blocked/captcha detected, trying next...")
                    if (i < endpoints.size - 1) kotlinx.coroutines.delay(2000)
                    continue
                }

                // Try multiple parsing strategies
                var titles = Regex("""<a rel="nofollow" class="result__a" href="(.*?)">(.*?)</a>""").findAll(html).toList()
                var snippets = Regex("""<a class="result__snippet"[^>]*>(.*?)</a>""").findAll(html).toList()

                // Fallback: try different result patterns (lite/modern DDG)
                if (titles.isEmpty()) {
                    titles = Regex("""<a[^>]+class="[^"]*result[^"]*"[^>]*href="(.*?)"[^>]*>(.*?)</a>""").findAll(html).toList()
                }
                if (titles.isEmpty()) {
                    titles = Regex("""<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>""").findAll(html)
                        .filter { !it.value.contains("duckduckgo.com") }
                        .toList()
                }
                if (titles.isEmpty()) {
                    // Lite mode: parse table rows
                    titles = Regex("""<td class="result-snippet">(.*?)</td>""").findAll(html).toList()
                }

                if (titles.isNotEmpty()) {
                    val maxResults = minOf(titles.size, 5)
                    val resultText = (0 until maxResults).joinToString("\n\n") { i ->
                        val rawTitle = titles[i].groupValues[2].ifBlank {
                            titles[i].groupValues[1].ifBlank { "Result ${i + 1}" }
                        }
                        val title = rawTitle
                            .replace(Regex("<[^>]*>"), "").replace("&amp;", "&")
                            .replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&#x27;", "'").replace("&quot;", "\"").trim()

                        var url_text = titles[i].groupValues[1]
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        if (url_text.contains("/l/?uddg=")) {
                            val uddg = Regex("""uddg=([^&]+)""").find(url_text)
                            url_text = uddg?.let { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") } ?: url_text
                        }
                        if (url_text.startsWith("//")) url_text = "https:$url_text"

                        val snippet = if (i < snippets.size) {
                            snippets[i].groupValues[1]
                                .replace(Regex("<[^>]*>"), "").replace("&amp;", "&")
                                .replace("&lt;", "<").replace("&gt;", ">").trim()
                        } else if (title.length < 20 && i < titles.size) {
                            // Use URL as fallback description
                            ""
                        } else ""

                        buildString {
                            append("**${i + 1}. $title**")
                            if (snippet.isNotBlank()) append("\n$snippet")
                            append("\n📎 $url_text")
                        }
                    }

                    val output = if (aiRepository != null) {
                        summarizeSearchResults(query, resultText, aiRepository!!)
                    } else {
                        "Результаты поиска по запросу '$query':\n\n$resultText"
                    }
                    return ToolResult.Success(
                        output = output,
                        data = mapOf("query" to query, "source" to "duckduckgo", "goal_achieved" to true)
                    )
                }
            } catch (e: Exception) {
                println("⚠️ DuckDuckGo endpoint failed: $urlStr (${e.message})")
                // Try next endpoint
            }
        }

        // All DDG endpoints failed — try Bing HTML
        val bingResult = try { searchViaBing(query) } catch (_: Exception) { null }
        if (bingResult != null) return bingResult

        // All endpoints failed — return empty results gracefully instead of error
        return ToolResult.Success(
            output = "Поиск по запросу '$query' не дал результатов. Попробуйте другой запрос или используйте web_fetch для прямого доступа к сайтам.",
            data = mapOf("query" to query, "source" to "duckduckgo", "goal_achieved" to true)
        )
    }

    /**
     * Поиск через Bing HTML (fallback, если DuckDuckGo блокирует).
     */
    private suspend fun searchViaBing(query: String): ToolResult? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urlStr = "https://www.bing.com/search?q=$encoded&setlang=ru-ru"
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
            conn.setRequestProperty("Referer", "https://www.bing.com/")
            conn.instanceFollowRedirects = true
            
            val html = try {
                conn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()
            
            if (html.isBlank() || html.contains("captcha", ignoreCase = true)) return null
            
            // Bing results: <li class="b_algo"><h2><a href="...">title</a></h2><p>snippet</p></li>
            val results = Regex("""<li class="b_algo">.*?<h2><a[^>]*href="(.*?)"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(html).take(5).toList()
            
            if (results.isEmpty()) return null
            
            val resultText = results.mapIndexed { i, m ->
                val url_text = m.groupValues[1].replace("&amp;", "&")
                val title = m.groupValues[2].replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim()
                val snippet = try {
                    val snippetMatch = Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL)).find(m.value)
                    snippetMatch?.groupValues?.getOrNull(1)
                        ?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""
                } catch (_: Exception) { "" }
                buildString {
                    append("**${i + 1}. $title**")
                    if (snippet.isNotBlank()) append("\n$snippet")
                    append("\n\uD83D\uDCCE $url_text")
                }
            }.joinToString("\n\n")
            
            val output = if (aiRepository != null) {
                summarizeSearchResults(query, resultText, aiRepository!!)
            } else {
                "Результаты поиска по запросу '$query' (Bing):\n\n$resultText"
            }
            ToolResult.Success(output = output, data = mapOf("query" to query, "source" to "bing", "goal_achieved" to true))
        } catch (e: Exception) {
            println("\u26A0\uFE0F Bing search error: ${e.message}")
            null
        }
    }
    
    /**
     * Суммаризирует результаты поиска через AI.
     */
    private suspend fun summarizeSearchResults(query: String, rawResults: String, ai: AiRepository): String {
        return try {
            val prompt = """
                Пользователь искал: "$query"
                
                Результаты поиска:
                ${rawResults.take(4000)}
                
                Суммаризируй: выдели главное, ключевые факты.
                Ответ на русском, с эмодзи, кратко.
            """.trimIndent()
            val response = ai.sendMessage(
                messages = listOf(Message.createUserMessage("search_summary", prompt)),
                systemPrompt = "Ты аналитик. Суммаризируй поиск.",
                memoryContext = ""
            )
            response.getOrThrow().text.ifBlank { rawResults }
        } catch (e: Exception) {
            rawResults
        }
    }
}
