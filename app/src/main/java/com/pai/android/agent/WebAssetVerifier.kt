package com.pai.android.agent

/**
 * Структурная проверка сгенерированных веб-файлов (HTML/JS/CSS).
 * Чистая логика без Android — покрыта unit-тестами.
 */
internal object WebAssetVerifier {

    data class Result(val ok: Boolean, val message: String)

    private val VOID = setOf("area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr")
    // Теги с необязательным закрытием — не проверяем, чтобы не ловить ложные срабатывания
    private val OPTIONAL_CLOSE = setOf("p","li","td","tr","th","option","dt","dd","thead","tbody","tfoot","colgroup","rt","rp","optgroup")

    fun validate(fileName: String, content: String): Result {
        val n = fileName.lowercase()
        return when {
            n.endsWith(".html") || n.endsWith(".htm") -> validateHtml(content)
            n.endsWith(".js") -> validateJs(content)
            n.endsWith(".css") -> validateCss(content)
            else -> Result(true, "OK")
        }
    }

    fun validateHtml(html: String): Result {
        var s = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(html, "")
        s = Regex("(<script[^>]*>).*?(</script>)", RegexOption.DOT_MATCHES_ALL).replace(s) { m -> m.groupValues[1] + m.groupValues[2] }
        s = Regex("(<style[^>]*>).*?(</style>)", RegexOption.DOT_MATCHES_ALL).replace(s) { m -> m.groupValues[1] + m.groupValues[2] }
        val tagRegex = Regex("</?([a-zA-Z][a-zA-Z0-9]*)[^>]*>")
        val stack = ArrayList<String>()
        for (m in tagRegex.findAll(s)) {
            val raw = m.value
            val name = m.groupValues[1].lowercase()
            if (name in VOID || name in OPTIONAL_CLOSE) continue
            val closing = raw.startsWith("</")
            val selfClose = raw.trimEnd().endsWith("/>")
            if (closing) {
                if (stack.isEmpty()) return Result(false, "лишний закрывающий тег </" + name + ">")
                val top = stack.removeAt(stack.size - 1)
                if (top != name) return Result(false, "несовпадение тегов: <" + top + "> закрыт как </" + name + ">")
            } else if (!selfClose) {
                stack.add(name)
            }
        }
        if (stack.isNotEmpty()) return Result(false, "не закрыты теги: " + stack.joinToString(", ") { "<" + it + ">" })
        return Result(true, "OK")
    }

    fun validateJs(js: String): Result = validateBrackets(js, "JS")

    fun validateCss(css: String): Result = validateBrackets(css, "CSS")

    private fun validateBrackets(src: String, what: String): Result {
        var curly = 0; var paren = 0; var square = 0
        var inStr: Char? = null
        var inLine = false; var inBlock = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else ' '
            if (inLine) { if (c == '\n') inLine = false; i++; continue }
            if (inBlock) { if (c == '*' && n == '/') { inBlock = false; i += 2 } else i++; continue }
            val sc = inStr
            if (sc != null) {
                if (c.code == 92) { i += 2; continue }
                if (c == sc) inStr = null
                i++; continue
            }
            when {
                c == '/' && n == '/' -> { inLine = true; i += 2 }
                c == '/' && n == '*' -> { inBlock = true; i += 2 }
                c == '"' || c == 39.toChar() || c == '`' -> { inStr = c; i++ }
                c == '{' -> { curly++; i++ }
                c == '}' -> { curly--; i++ }
                c == '(' -> { paren++; i++ }
                c == ')' -> { paren--; i++ }
                c == '[' -> { square++; i++ }
                c == ']' -> { square--; i++ }
                else -> i++
            }
            if (curly < 0 || paren < 0 || square < 0) return Result(false, what + ": лишняя закрывающая скобка")
        }
        if (inStr != null) return Result(false, what + ": незакрытая строка")
        if (inBlock) return Result(false, what + ": незакрытый комментарий")
        if (curly != 0 || paren != 0 || square != 0) return Result(false, what + ": незакрытые скобки {}=$curly ()=$paren []=$square")
        return Result(true, "OK")
    }
}