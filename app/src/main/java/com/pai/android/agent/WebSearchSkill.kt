package com.pai.android.agent

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.repository.WebSearchRepository
import com.pai.android.agent.tools.WebFetchTool
import com.pai.android.data.model.Message
import com.pai.android.data.model.WebSearchProvider
import org.json.JSONObject

/**
 * Навык веб-поиска в интернете.
 * 
 * Стратегия поиска (каскадная):
 * 1. Если в настройках включены внешние API (Google/Tavily) и есть ключи → используем их
 * 2. Если внешний API не настроен или не ответил → fallback на встроенный DuckDuckGo HTML
 * 3. После поиска загружаем топ-страниц и суммаризируем через AI
 */
class WebSearchSkill(
    private val aiRepository: AiRepository,
    private val webSearchRepository: WebSearchRepository
) : Skill {

    override val name: String = "web_search"

    override val description: String = "Search the internet: search by query, get information from the web"

    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        return params["command"] == "web_search" || intent == Intent.SEARCH
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        return try {
            val query = params["query"] as? String ?: return SkillResult.Error(
                message = "Не указан запрос для поиска",
                details = "Используйте параметр 'query' для указания поискового запроса"
            )

            println("🌐 WebSearchSkill: поиск '$query'")

            val result = performSearch(query)
            
            if (result != null) {
                // Загружаем содержимое топ-страниц (до 5)
                val pageContents = mutableListOf<String>()
                val fetchLimit = minOf(result.urls.size, 5)
                for (i in 0 until fetchLimit) {
                    println("🌐 WebSearchSkill: загружаю страницу $i: ${result.urls[i]}")
                    val content = WebFetchTool().execute(mapOf("url" to result.urls[i], "max_chars" to 3000)).let {
                        when (it) {
                            is ToolResult.Success -> it.output.take(3000)
                            else -> null
                        }
                    }
                    if (content != null) {
                        pageContents.add("--- Страница ${i + 1}: ${result.urls[i]} ---\n$content")
                    }
                }
                
                // Объединяем результаты поиска + содержимое страниц
                val aggregatedResults = buildString {
                    append(result.formattedText)
                    append("\n\n")
                    if (pageContents.isNotEmpty()) {
                        append("--- Детальное содержимое страниц ---\n\n")
                        pageContents.forEach { append(it).append("\n\n") }
                    }
                }
                
                // Отдаём сырые результаты поиска + содержимое страниц — AI сам справится
                SkillResult.Success(
                    message = "🌐 **Результаты поиска по запросу:** \"$query\"\n\n$aggregatedResults",
                    data = mapOf("query" to query, "result" to aggregatedResults),
                    responseType = ResponseType.TEXT
                )
            } else {
                SkillResult.Error(
                    message = "Не удалось выполнить поиск по запросу '$query'",
                    details = "Попробуйте позже или уточните запрос"
                )
            }
        } catch (e: Exception) {
            println("❌ WebSearchSkill ошибка: ${e.message}")
            SkillResult.Error(
                message = "Ошибка при поиске в интернете",
                details = e.message ?: "Неизвестная ошибка"
            )
        }
    }

    /**
     * Результат поиска: форматированный текст + список чистых URL.
     */
    data class SearchResult(
        val formattedText: String,
        val urls: List<String>
    )

    /**
     * Выполняет поиск по каскадной стратегии.
     * 1. Внешний API (Google/Tavily) — по настройкам
     * 2. DuckDuckGo HTML — всегда как fallback
     */
    private suspend fun performSearch(query: String): SearchResult? {
        // Шаг 1: Проверяем настройки внешних API
        try {
            val settings = webSearchRepository.getSettings()
            println("🌐 WebSearchSkill: настройки — enabled=${settings.enabled}, provider=${settings.provider}")
            
            if (settings.enabled && settings.canPerformSearch()) {
                println("🌐 WebSearchSkill: пробую внешний API (${settings.provider.displayName})")
                
                val apiResult = when (settings.provider) {
                    WebSearchProvider.GOOGLE -> searchViaGoogle(query, settings)
                    WebSearchProvider.TAVILY -> searchViaTavily(query, settings)
                    WebSearchProvider.DUCKDUCKGO -> null // DuckDuckGo — встроенный, не через API
                }
                
                if (apiResult != null) {
                    println("✅ WebSearchSkill: внешний API успешно выполнил поиск")
                    return apiResult
                }
                
                println("⚠️ WebSearchSkill: внешний API не дал результатов, падаю на DuckDuckGo")
            } else {
                println("🌐 WebSearchSkill: внешние API не настроены, использую встроенный DuckDuckGo")
            }
        } catch (e: Exception) {
            println("⚠️ WebSearchSkill: ошибка при проверке/вызове внешнего API: ${e.message}")
            println("🌐 WebSearchSkill: падаю на DuckDuckGo")
        }
        
        // Шаг 2: Fallback на DuckDuckGo HTML
        return searchViaDuckDuckGo(query)
    }

    /**
     * Поиск через Google Custom Search JSON API.
     */
    private fun searchViaGoogle(query: String, settings: com.pai.android.data.model.WebSearchSettings): SearchResult? {
        try {
            val apiKey = settings.googleApiKey ?: return null
            val engineId = settings.googleSearchEngineId ?: return null
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://www.googleapis.com/customsearch/v1?key=$apiKey&cx=$engineId&q=$encodedQuery&num=5&hl=ru")
            
            println("🌐 WebSearchSkill: Google Custom Search API запрос")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                println("❌ Google API вернул $responseCode: ${errorStream.take(200)}")
                connection.disconnect()
                return null
            }
            
            val json = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val jsonObject = JSONObject(json)
            val items = jsonObject.optJSONArray("items") ?: return null
            
            if (items.length() == 0) return null
            
            val resultText = StringBuilder()
            val cleanUrls = mutableListOf<String>()
            
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val title = item.optString("title", "")
                val link = item.optString("link", "")
                val snippet = item.optString("snippet", "")
                
                cleanUrls.add(link)
                
                resultText.append("**$i. $title**\n")
                if (snippet.isNotBlank()) {
                    resultText.append("${snippet.replace(Regex("<[^>]*>"), "")}\n")
                }
                resultText.append("$link\n\n")
            }
            
            return SearchResult(
                formattedText = "🔍 **Google Search** по запросу \"$query\":\n\n${resultText.toString().trimEnd()}",
                urls = cleanUrls.distinct()
            )
        } catch (e: Exception) {
            println("❌ Google API ошибка: ${e.message}")
            return null
        }
    }

    /**
     * Поиск через Tavily Search API.
     */
    private fun searchViaTavily(query: String, settings: com.pai.android.data.model.WebSearchSettings): SearchResult? {
        try {
            val apiKey = settings.tavilyApiKey ?: return null
            
            println("🌐 WebSearchSkill: Tavily Search API запрос")
            
            val url = URL("https://api.tavily.com/search")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            // Тело запроса
            val requestBody = JSONObject().apply {
                put("api_key", apiKey)
                put("query", query)
                put("max_results", 5)
                put("include_answer", false)
                put("include_raw_content", false)
            }.toString()
            
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody)
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                println("❌ Tavily API вернул $responseCode: ${errorStream.take(200)}")
                connection.disconnect()
                return null
            }
            
            val json = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val jsonObject = JSONObject(json)
            val resultsArray = jsonObject.optJSONArray("results") ?: return null
            
            if (resultsArray.length() == 0) return null
            
            val resultText = StringBuilder()
            val cleanUrls = mutableListOf<String>()
            
            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                val title = item.optString("title", "")
                val link = item.optString("url", "")
                val snippet = item.optString("content", "")
                
                if (link.isNotBlank()) {
                    cleanUrls.add(link)
                }
                
                resultText.append("**$i. $title**\n")
                if (snippet.isNotBlank()) {
                    resultText.append("${snippet.replace(Regex("<[^>]*>"), "")}\n")
                }
                if (link.isNotBlank()) {
                    resultText.append("$link\n")
                }
                resultText.append("\n")
            }
            
            return SearchResult(
                formattedText = "🔍 **Tavily Search** по запросу \"$query\":\n\n${resultText.toString().trimEnd()}",
                urls = cleanUrls.distinct()
            )
        } catch (e: Exception) {
            println("❌ Tavily API ошибка: ${e.message}")
            return null
        }
    }

    /**
     * Поиск через DuckDuckGo HTML (встроенный, без API ключа).
     * Всегда доступен как fallback.
     */
    private fun searchViaDuckDuckGo(query: String): SearchResult? {
        println("🌐 WebSearchSkill: DuckDuckGo HTML search (fallback)")

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.4621.107 Mobile Safari/537.36"
        )
        val urls = listOf(
            "https://html.duckduckgo.com/html/?q=$encodedQuery",
            "https://lite.duckduckgo.com/lite/?q=$encodedQuery"
        )

        for ((i, urlStr) in urls.withIndex()) {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", userAgents[i % userAgents.size])
                connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                connection.setRequestProperty("Referer", "https://duckduckgo.com/")

                val responseCode = connection.responseCode

                if (responseCode == 202 || responseCode == 403) {
                    println("⚠️ WebSearchSkill: DDG $responseCode at $urlStr, skip")
                    connection.disconnect()
                    continue
                }

                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                    val html = buildString {
                        var line: String? = reader.readLine()
                        while (line != null) {
                            append(line)
                            append('\n')
                            line = reader.readLine()
                        }
                    }
                    reader.close()
                    connection.disconnect()

                    if (html.contains("captcha", ignoreCase = true)) {
                        println("⚠️ WebSearchSkill: DDG captcha, skip")
                        continue
                    }

                    val result = parseDuckDuckGoHtml(html, query)
                    if (result != null) return result
                } else {
                    println("❌ WebSearchSkill: DDG вернул код: $responseCode")
                    connection.disconnect()
                }
            } catch (e: Exception) {
                println("⚠️ WebSearchSkill: $urlStr error: ${e.message}")
            }
        }

        println("❌ WebSearchSkill: all DDG endpoints failed, trying Bing...")
        return searchViaBing(query)
    }

    /**
     * Поиск через Bing HTML (fallback, если DuckDuckGo недоступен).
     */
    private fun searchViaBing(query: String): SearchResult? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return try {
            val url = URL("https://www.bing.com/search?q=$encoded&setlang=ru-ru")
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

            val results = Regex("<li class=\"b_algo\">.*?<h2><a[^>]*href=\"(.*?)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(html).take(5).toList()

            if (results.isEmpty()) return null

            val resultText = results.mapIndexed { i, m ->
                val link = m.groupValues[1].replace("&amp;", "&")
                val title = m.groupValues[2].replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim()
                val snippet = try {
                    Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(m.value)
                        ?.groupValues?.getOrNull(1)
                        ?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""
                } catch (_: Exception) { "" }
                buildString {
                    append("**${i + 1}. $title**")
                    if (snippet.isNotBlank()) append("\n$snippet")
                    append("\n📎 $link")
                }
            }.joinToString("\n\n")

            SearchResult(
                formattedText = "🌐 **Bing** по запросу \"$query\":\n\n$resultText",
                urls = results.map { it.groupValues[1] }
            )
        } catch (e: Exception) {
            println("⚠️ WebSearchSkill: Bing error: ${e.message}")
            null
        }
    }

    /**
     * Парсит HTML страницу результатов DuckDuckGo.
     * Извлекает заголовки, описания и ссылки из result__a / result__snippet.
     */
    private fun parseDuckDuckGoHtml(html: String, query: String): SearchResult {
        val result = StringBuilder()
        val cleanUrls = mutableListOf<String>()

        // Regex для поиска result__a (заголовок + ссылка)
        val resultPattern = Regex(
            """<a rel="nofollow" class="result__a" href="(.*?)">(.*?)</a>"""
        )
        val snippetPattern = Regex(
            """<a class="result__snippet"[^>]*>(.*?)</a>"""
        )

        val titleMatches = resultPattern.findAll(html).toList()
        val snippetMatches = snippetPattern.findAll(html).toList()

        val maxResults = minOf(titleMatches.size, 5)
        if (maxResults == 0) {
            return SearchResult(
                formattedText = "Результаты поиска по запросу \"$query\" не найдены. Попробуйте уточнить запрос.",
                urls = emptyList()
            )
        }

        for (i in 0 until maxResults) {
            val titleMatch = titleMatches[i]
            var url = titleMatch.groupValues[1]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
            
            // Декодируем внутренние ссылки DuckDuckGo (редиректы)
            if (url.contains("/l/?uddg=")) {
                val uddgMatch = Regex("""uddg=([^&]+)""").find(url)
                if (uddgMatch != null) {
                    url = URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
                }
            }
            if (url.startsWith("//")) url = "https:$url"
            
            if (url.isNotEmpty() && !url.contains("duckduckgo.com")) {
                cleanUrls.add(url)
            }
            
            val title = titleMatch.groupValues[2]
                .replace(Regex("<[^>]*>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .trim()

            val snippet = if (i < snippetMatches.size) {
                snippetMatches[i].groupValues[1]
                    .replace(Regex("<[^>]*>"), "")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#x27;", "'")
                    .trim()
            } else ""

            result.append("**$i. $title**\n")
            if (snippet.isNotBlank()) {
                result.append("$snippet\n")
            }
            result.append("$url\n\n")
        }

        return SearchResult(
            formattedText = result.toString().trimEnd(),
            urls = cleanUrls.distinct()
        )
    }

    /**
     * Суммаризирует сырые результаты поиска через AI для читаемого ответа.
     */
    private suspend fun aiSummarizeSearchResults(query: String, rawResults: String): String {
        return try {
            println("🧠 Суммаризирую результаты поиска через AI...")
            val response = aiRepository.sendMessage(
                messages = listOf(
                    Message.createUserMessage("assistant",
                        """Пользователь искал: "$query"
                        
                        Вот результаты поиска из интернета и содержимое найденных страниц:
                        
                        $rawResults
                        
                        На основе этих данных сделай краткий, структурированный и понятный ответ.
                        - Дай конкретный ответ на вопрос пользователя, если данные позволяют
                        - Если это погода: назови температуру, осадки, ветер
                        - Если это курс валют: назови конкретные цифры
                        - Выдели самое важное
                        - Ссылки укажи полностью с https:// (например, https://gismeteo.ru)
                        - Не более 10-15 строк"""
                    )
                ),
                systemPrompt = "Ты полезный AI-ассистент. На основе полученных данных из интернета дай точный и конкретный ответ на вопрос пользователя. Всегда указывай ссылки с протоколом https://.",
                memoryContext = ""
            )
            val text = response.getOrThrow().text
            if (text.isNotBlank()) text else rawResults
        } catch (e: Exception) {
            println("⚠️ AI суммаризация не удалась: ${e.message}")
            rawResults
        }
    }
}
