package com.pai.android.agent.tools

import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ToolResult
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.*
import java.security.SecureRandom

/**
 * WebFetchTool — загружает веб-страницу, извлекает основной контент
 * и конвертирует его в чистый структурированный Markdown.
 *
 * Принцип работы:
 * 1. Удаление заведомо бесполезных элементов (script, style, nav, footer, form)
 * 2. Извлечение приоритетного контейнера (article > main > body)
 * 3. Выделение только контентных тегов (p, h1-h6, li, blockquote, pre, td, th)
 * 4. Конвертация в Markdown со структурой
 * 5. Пост-фильтрация: удаление пустых и мусорных строк
 */
class WebFetchTool : BaseAgentTool() {

    private val shareUrl = Regex("facebook\\.com/(share|dialog/share)|twitter\\.com/intent|x\\.com/intent|linkedin\\.com/share|pinterest\\.com/pin/create|reddit\\.com/submit|bluesky\\.app/intent|flipboard\\.com/(share|bookmarklet)|cdn-cgi/l/email|email-protection|mailto:", RegexOption.IGNORE_CASE)


    override val name: String = "web_fetch"

    override val description: String = "Fetch a web page, extract main text content, and return as Markdown (with headers, lists, and links)"

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "URL страницы для загрузки"
                },
                "max_chars": {
                    "type": "integer",
                    "description": "Максимум символов (500-50000)",
                    "default": 10000
                }
            },
            "required": ["url"]
        }
    """.trimIndent()

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val urlStr = params["url"] as? String ?: params["link"] as? String
                ?: return ToolResult.Error("Не указан URL")

            if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))
                return ToolResult.Error("Некорректный URL. Должен начинаться с http:// или https://")

            val maxChars = (params["max_chars"] as? Number)?.toInt()?.coerceIn(500, 50000) ?: 10000

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 0
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36")
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.instanceFollowRedirects = true

            if (conn.responseCode != 200) {
                conn.disconnect()
                val cleanUrl = urlStr.substringBefore("?").substringBefore("#")
                if (cleanUrl != urlStr && cleanUrl.length > 15)
                    return execute(mapOf("url" to cleanUrl, "max_chars" to maxChars))
                return ToolResult.Error("Страница недоступна (HTTP ${conn.responseCode})")
            }

            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val result = extractContent(html, maxChars)
            ToolResult.Success(output = result, data = mapOf("url" to urlStr, "length" to result.length, "goal_achieved" to true))

        } catch (e: Exception) {
            ToolResult.Error("Ошибка загрузки: ${e.message}")
        }
    }

    // ======== ИЗВЛЕЧЕНИЕ ОСНОВНОГО КОНТЕНТА ========

    private fun extractContent(html: String, maxChars: Int): String {
        // 1. Извлекаем мета-данные
        val title = getMeta(html, "og:title") ?: getMeta(html, "title")
            ?: extract("<title[^>]*>(.*?)</title>", html)
        val source = getMeta(html, "og:site_name") ?: ""
        val description = getMeta(html, "description") ?: getMeta(html, "og:description") ?: ""

        // 2. Удаляем шум
        var cleaned = html
            .replace(Regex("(?si)<script[^>]*>.*?</script>"), "")
            .replace(Regex("(?si)<style[^>]*>.*?</style>"), "")
            .replace(Regex("(?si)<nav[^>]*>.*?</nav>"), "")
            .replace(Regex("(?si)<footer[^>]*>.*?</footer>"), "")
            .replace(Regex("(?si)<aside[^>]*>.*?</aside>"), "")
            .replace(Regex("(?si)<svg[^>]*>.*?</svg>"), "")
            .replace(Regex("(?si)<form[^>]*>.*?</form>"), "")
            .replace(Regex("(?si)<select[^>]*>.*?</select>"), "")
            .replace(Regex("(?si)<button[^>]*>.*?</button>"), "")
            .replace(Regex("(?si)<noscript[^>]*>.*?</noscript>"), "")
            .replace(Regex("(?si)<header[^>]*>.*?</header>"), "")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        // 3. Берём приоритетный контейнер: article > main > тело документа
        val body = extract("(?si)<article[^>]*>(.*?)</article>", cleaned)
            ?: extract("(?si)<main[^>]*>(.*?)</main>", cleaned)
            ?: cleaned

        // 4. Извлекаем только контентные теги — социальный мусор остаётся в div/span
        val contentBlocks = mutableListOf<String>()

        // Заголовки
        extractAll("(?si)<h1[^>]*>(.*?)</h1>", body).forEach { contentBlocks.add("h1|${it.strip()}") }
        extractAll("(?si)<h2[^>]*>(.*?)</h2>", body).forEach { contentBlocks.add("h2|${it.strip()}") }
        extractAll("(?si)<h3[^>]*>(.*?)</h3>", body).forEach { contentBlocks.add("h3|${it.strip()}") }
        extractAll("(?si)<h4[^>]*>(.*?)</h4>", body).forEach { contentBlocks.add("h4|${it.strip()}") }
        extractAll("(?si)<h5[^>]*>(.*?)</h5>", body).forEach { contentBlocks.add("h5|${it.strip()}") }
        extractAll("(?si)<h6[^>]*>(.*?)</h6>", body).forEach { contentBlocks.add("h6|${it.strip()}") }

        // Параграфы — основной контент
        extractAll("(?si)<p[^>]*>(.*?)</p>", body).forEach { contentBlocks.add("p|${it.strip()}") }

        // Списки
        extractAll("(?si)<li[^>]*>(.*?)</li>", body).forEach { contentBlocks.add("li|${it.strip()}") }

        // Цитаты
        extractAll("(?si)<blockquote[^>]*>(.*?)</blockquote>", body).forEach { contentBlocks.add("bq|${it.strip()}") }

        // Код
        extractAll("(?si)<pre[^>]*>(.*?)</pre>", body).forEach { contentBlocks.add("pre|${it.strip()}") }

        // Ячейки таблиц (осмысленный табличный контент)
        extractAll("(?si)<td[^>]*>(.*?)</td>", body).forEach { contentBlocks.add("td|${it.strip()}") }
        extractAll("(?si)<th[^>]*>(.*?)</th>", body).forEach { contentBlocks.add("th|${it.strip()}") }

        // 5. Фильтрация мусора
        val filtered = contentBlocks
            .map { it to it.substringAfter("|") }
            .filter { (_, text) ->
                val clean = text.stripTags()
                clean.length > 20 &&                          // слишком короткие — мусор
                clean.count { it == '/' } < clean.length / 3 && // ссылки-каши
                !clean.startsWith("http") &&                   // голые URL
                clean.length < 2000 &&                         // аномально длинные
                !shareUrl.containsMatchIn(text)                // share-ссылки (ещё в HTML)
            }
            .sortedBy { (tag) ->
                // Восстанавливаем порядок как в оригинале
                body.indexOf(extractTagFromBlock(tag, contentBlocks))
            }
            .distinct()

        // 6. Конвертируем в Markdown с декором
        // 6b. Фильтр подписей к фото ("X of Y | ...")
        val filteredNoCaptions = filtered.filter { (tag, text) ->
            !(tag.startsWith("p|") && Regex("^\\d+ of \\d+ \\| ").containsMatchIn(text))
        }

        val md = buildString {
            for ((tag, text) in filteredNoCaptions) {
                when {
                    tag.startsWith("h1|") -> appendLine("# ${convertInline(text)}").appendLine()
                    tag.startsWith("h2|") -> appendLine("## ${convertInline(text)}").appendLine()
                    tag.startsWith("h3|") -> appendLine("### ${convertInline(text)}").appendLine()
                    tag.startsWith("h4|") -> appendLine("#### ${convertInline(text)}").appendLine()
                    tag.startsWith("h5|") -> appendLine("##### ${convertInline(text)}").appendLine()
                    tag.startsWith("h6|") -> appendLine("###### ${convertInline(text)}").appendLine()
                    tag.startsWith("li|") -> {
                        val converted = convertInline(text)
                        // Фильтр: если после конвертации осталась только ссылка-поделиться, игнорируем
                        if (converted.length > 25 && !converted.startsWith("http") && !shareUrl.containsMatchIn(converted))
                            appendLine("- $converted")
                    }
                    tag.startsWith("bq|") -> appendLine("> ${convertInline(text)}").appendLine()
                    tag.startsWith("pre|") -> appendLine("```\n${cleanPre(text)}\n```").appendLine()
                    tag.startsWith("p|") -> appendLine(convertInline(text)).appendLine()
                }
            }
        }

        // 7. Финальная чистка
        val result = md
            .replace(Regex("\\n{4,}"), "\n\n")
            .replace(Regex("^- $", RegexOption.MULTILINE), "")
            .trim()

        val finalMd = buildString {
            if (!title.isNullOrBlank()) appendLine("# $title")
            if (!source.isNullOrBlank()) appendLine("*Источник: $source*")
            if (!description.isNullOrBlank()) appendLine()
            appendLine()
            if (result.length > maxChars) append(result.take(maxChars) + "\n\n*[текст обрезан]*")
            else append(result)
        }

        return finalMd
    }

    // ======== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ========

    private fun extract(pattern: String, html: String): String? {
        return Regex(pattern, RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
            ?.trim()?.ifBlank { null }
    }

    private fun extractAll(pattern: String, html: String): List<String> {
        return Regex(pattern, RegexOption.IGNORE_CASE).findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun getMeta(html: String, name: String): String? {
        val pattern1 = Regex("""<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(name)}["'][^>]+content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val m1 = pattern1.find(html)
        if (m1 != null) return m1.groupValues[1].trim().ifBlank { null }
        val pattern2 = Regex("""<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]+(?:property|name)\s*=\s*["']${Regex.escape(name)}["']""", RegexOption.IGNORE_CASE)
        return pattern2.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
    }

    private fun extractTagFromBlock(tag: String, allBlocks: List<String>): String {
        val text = tag.substringAfter("|")
        return text
    }

    // ======== ДОМЕНЫ-ПОДЕЛИТЬСЯ (расширенный список) ========
        /** Проверяет, является ли URL «шаринговым» по доменам или паттернам */
    private fun isSharingUrl(url: String): Boolean {
        // Проверка по известным доменам
        if (shareUrl.containsMatchIn(url)) return true
        // Универсальные паттерны (пути и параметры)
        val sharingPatterns = listOf("/share", "/intent", "bookmarklet", "/compose?")
        return sharingPatterns.any { url.contains(it, ignoreCase = true) }
    }

    /** Конвертирует inline-элементы HTML → Markdown, с фильтрацией шаринговых ссылок */
    private fun convertInline(html: String): String {
        var result = html
            // Ссылки
            .replace(Regex("(?si)<a[^>]*href\\s*=\\s*\"([^\"]*)\"[^>]*>(.*?)</a>")) { match ->
                val url = match.groupValues[1].trim()
                val text = match.groupValues[2].stripTags().trim()
                // Пропускаем javascript-ссылки, пустые или шаринговые
                if (text.isNotBlank() && url.isNotBlank()
                    && !url.startsWith("javascript:")
                    && !isSharingUrl(url)
                ) {
                    "[$text]($url)"
                } else {
                    text
                }
            }
            // Жирный
            .replace(Regex("(?si)<strong[^>]*>(.*?)</strong>")) { "**${it.groupValues[1].stripTags()}**" }
            .replace(Regex("(?si)<b[^>]*>(.*?)</b>")) { "**${it.groupValues[1].stripTags()}**" }
            // Курсив
            .replace(Regex("(?si)<em[^>]*>(.*?)</em>")) { "*${it.groupValues[1].stripTags()}*" }
            .replace(Regex("(?si)<i[^>]*>(.*?)</i>")) { "*${it.groupValues[1].stripTags()}*" }
            // Код
            .replace(Regex("(?si)<code[^>]*>(.*?)</code>")) { "`${it.groupValues[1].stripTags()}`" }
            // Переносы
            .replace(Regex("<br[^>]*>", RegexOption.IGNORE_CASE), " ")
            // Остальные теги — удалить
            .replace(Regex("<[^>]+>"), " ")
            // HTML entities
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace(Regex("&[a-zA-Z]+;"), " ")
            // Схлопывание пробелов
            .replace(Regex("[\\t\\r]+"), " ").replace(Regex("\\s{3,}"), "  ")
            .trim()
        return result
    }

    private fun cleanPre(html: String): String {
        return html.replace(Regex("<[^>]+>"), "").trim()
    }

    private fun String.stripTags(): String {
        return this.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
