package com.pai.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextDecoration

/**
 * Компонент для отображения кода с подсветкой синтаксиса.
 * Кастомная реализация с использованием AnnotatedString и SpanStyle.
 */
@Composable
fun SyntaxHighlightedCode(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true
) {
    val annotatedString = highlightSyntax(code, language, isDarkTheme)
    
    Text(
        text = annotatedString,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 18.sp
    )
}

/**
 * Подсвечивает синтаксис для заданного языка.
 */
private fun highlightSyntax(code: String, language: String, isDarkTheme: Boolean): AnnotatedString {
    val keywordColor = if (isDarkTheme) Color(0xFF569CD6) else Color(0xFF0000FF) // синий
    val stringColor = if (isDarkTheme) Color(0xFFCE9178) else Color(0xFFA31515) // оранжевый/красный
    val commentColor = if (isDarkTheme) Color(0xFF6A9955) else Color(0xFF008000) // зелёный
    val numberColor = if (isDarkTheme) Color(0xFFB5CEA8) else Color(0xFF098658) // светло-зелёный
    val typeColor = if (isDarkTheme) Color(0xFF4EC9B0) else Color(0xFF267F99) // бирюзовый
    val functionColor = if (isDarkTheme) Color(0xFFDCDCAA) else Color(0xFF795E26) // жёлтый
    val defaultColor = if (isDarkTheme) Color(0xFFD4D4D4) else Color(0xFF000000) // белый/чёрный
    
    return AnnotatedString.Builder().apply {
        val languageLower = language.lowercase()
        
        when (languageLower) {
            "kotlin", "kt" -> highlightKotlin(
                code, 
                keywordColor, 
                stringColor, 
                commentColor, 
                numberColor, 
                typeColor, 
                functionColor, 
                defaultColor
            )
            "java" -> highlightJava(
                code,
                keywordColor,
                stringColor,
                commentColor,
                numberColor,
                typeColor,
                functionColor,
                defaultColor
            )
            "python", "py" -> highlightPython(
                code,
                keywordColor,
                stringColor,
                commentColor,
                numberColor,
                typeColor,
                functionColor,
                defaultColor
            )
            "javascript", "js" -> highlightJavaScript(
                code,
                keywordColor,
                stringColor,
                commentColor,
                numberColor,
                typeColor,
                functionColor,
                defaultColor
            )
            "sql" -> highlightSql(
                code,
                keywordColor,
                stringColor,
                commentColor,
                numberColor,
                defaultColor
            )
            "html" -> highlightHtml(
                code,
                keywordColor,
                stringColor,
                commentColor,
                defaultColor
            )
            "css" -> highlightCss(
                code,
                keywordColor,
                stringColor,
                commentColor,
                numberColor,
                defaultColor
            )
            else -> {
                // Простой текст без подсветки
                append(code)
            }
        }
    }.toAnnotatedString()
}

/**
 * Подсветка для Kotlin.
 */
private fun AnnotatedString.Builder.highlightKotlin(
    code: String,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
    typeColor: Color,
    functionColor: Color,
    defaultColor: Color
) {
    val keywords = setOf(
        "fun", "val", "var", "class", "interface", "object", "typealias",
        "this", "super", "typeof", "is", "as", "in", "!in", "for", "while",
        "do", "when", "if", "else", "try", "catch", "finally", "throw",
        "return", "continue", "break", "package", "import", "constructor",
        "init", "get", "set", "where", "by", "companion", "init", "inner",
        "override", "private", "protected", "public", "internal", "abstract",
        "enum", "open", "sealed", "data", "annotation", "const", "external",
        "final", "infix", "inline", "lateinit", "noinline", "operator",
        "out", "reified", "suspend", "tailrec", "vararg", "actual",
        "expect", "field", "param", "property", "receiver", "delegate",
        "dynamic", "crossinline"
    )
    
    val types = setOf(
        "String", "Int", "Long", "Double", "Float", "Boolean", "Char",
        "Byte", "Short", "Unit", "Any", "Nothing", "List", "MutableList",
        "Set", "MutableSet", "Map", "MutableMap", "Array", "Sequence",
        "Flow", "CoroutineScope", "suspend"
    )
    
    highlightGenericCode(
        code,
        keywords,
        types,
        keywordColor,
        stringColor,
        commentColor,
        numberColor,
        typeColor,
        functionColor,
        defaultColor
    )
}

/**
 * Подсветка для Java.
 */
private fun AnnotatedString.Builder.highlightJava(
    code: String,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
    typeColor: Color,
    functionColor: Color,
    defaultColor: Color
) {
    val keywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "true", "false",
        "null"
    )
    
    val types = setOf(
        "String", "Integer", "Long", "Double", "Float", "Boolean", "Character",
        "Byte", "Short", "Void", "Object", "List", "ArrayList", "Set",
        "HashSet", "Map", "HashMap", "Array", "Optional", "Stream"
    )
    
    highlightGenericCode(
        code,
        keywords,
        types,
        keywordColor,
        stringColor,
        commentColor,
        numberColor,
        typeColor,
        functionColor,
        defaultColor
    )
}

/**
 * Подсветка для Python.
 */
private fun AnnotatedString.Builder.highlightPython(
    code: String,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
    typeColor: Color,
    functionColor: Color,
    defaultColor: Color
) {
    val keywords = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "try",
        "except", "finally", "with", "as", "from", "import", "global",
        "nonlocal", "lambda", "pass", "break", "continue", "return",
        "yield", "assert", "raise", "del", "and", "or", "not", "is",
        "in", "not in", "is not", "None", "True", "False", "async",
        "await"
    )
    
    val builtins = setOf(
        "str", "int", "float", "bool", "list", "dict", "tuple", "set",
        "range", "enumerate", "len", "print", "input", "open", "type",
        "isinstance", "hasattr", "getattr", "setattr", "property"
    )
    
    highlightGenericCode(
        code,
        keywords,
        builtins,
        keywordColor,
        stringColor,
        commentColor,
        numberColor,
        typeColor,
        functionColor,
        defaultColor
    )
}

/**
 * Подсветка для JavaScript.
 */
private fun AnnotatedString.Builder.highlightJavaScript(
    code: String,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
    typeColor: Color,
    functionColor: Color,
    defaultColor: Color
) {
    val keywords = setOf(
        "function", "var", "let", "const", "if", "else", "for", "while",
        "do", "switch", "case", "break", "continue", "return", "try",
        "catch", "finally", "throw", "new", "delete", "typeof", "instanceof",
        "in", "of", "await", "async", "yield", "export", "import", "default",
        "class", "extends", "super", "this", "true", "false", "null",
        "undefined", "NaN", "Infinity"
    )
    
    val builtins = setOf(
        "Array", "Object", "String", "Number", "Boolean", "Date", "Math",
        "JSON", "Promise", "Set", "Map", "console", "window", "document",
        "localStorage", "sessionStorage", "fetch", "XMLHttpRequest"
    )
    
    highlightGenericCode(
        code,
        keywords,
        builtins,
        keywordColor,
        stringColor,
        commentColor,
        numberColor,
        typeColor,
        functionColor,
        defaultColor
    )
}

/**
 * Подсветка для SQL.
 */
private fun AnnotatedString.Builder.highlightSql(
    code: String,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
    defaultColor: Color
) {
    val keywords = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE",
        "SET", "DELETE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON",
        "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET", "UNION",
        "ALL", "DISTINCT", "AS", "AND", "OR", "NOT", "IN", "BETWEEN",
        "LIKE", "IS", "NULL", "CREATE", "TABLE", "INDEX", "VIEW",
        "DROP", "ALTER", "ADD", "COLUMN", "PRIMARY KEY", "FOREIGN KEY",
        "REFERENCES", "UNIQUE", "CHECK", "DEFAULT", "AUTOINCREMENT",
        "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "GRANT", "REVOKE"
    )
    
    highlightGenericCode(
        code,
        keywords,
        emptySet(),
        keywordColor,
        stringColor,
        commentColor,
        numberColor,
        keywordColor, // типы - те же ключевые слова
        keywordColor, // функции - те же ключевые слова
        defaultColor
    )
}

/**
 * Подсветка для HTML.
 */
private fun AnnotatedString.Builder.highlightHtml(
    code: String,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    defaultColor: Color
) {
    // HTML теги
    val tags = setOf(
        "html", "head", "body", "div", "span", "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "a", "img", "ul", "ol", "li", "table", "tr", "td", "th", "form", "input",
        "button", "select", "option", "textarea", "label", "script", "style",
        "link", "meta", "title", "header", "footer", "nav", "section", "article",
        "aside", "main", "figure", "figcaption", "video", "audio", "source",
        "canvas", "svg", "path", "circle", "rect", "line", "polygon"
    )
    
    highlightGenericCode(
        code,
        tags,
        emptySet(),
        keywordColor,
        stringColor,
        commentColor,
        defaultColor, // числа не используются
        keywordColor,
        keywordColor,
        defaultColor
    )
}

/**
 * Подсветка для CSS.
 */
private fun AnnotatedString.Builder.highlightCss(
    code: String,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
    defaultColor: Color
) {
    val properties = setOf(
        "color", "background", "font-size", "font-family", "margin", "padding",
        "border", "width", "height", "display", "position", "top", "right",
        "bottom", "left", "flex", "grid", "align-items", "justify-content",
        "text-align", "vertical-align", "opacity", "visibility", "z-index",
        "overflow", "cursor", "transition", "animation", "transform"
    )
    
    highlightGenericCode(
        code,
        properties,
        emptySet(),
        keywordColor,
        stringColor,
        commentColor,
        numberColor,
        keywordColor,
        keywordColor,
        defaultColor
    )
}

/**
 * Общая логика подсветки кода для разных языков.
 */
private fun AnnotatedString.Builder.highlightGenericCode(
    code: String,
    keywords: Set<String>,
    typesOrBuiltins: Set<String>,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
    typeColor: Color,
    functionColor: Color,
    defaultColor: Color
) {
    var i = 0
    val n = code.length
    
    while (i < n) {
        // Пропускаем пробелы и табы
        if (code[i].isWhitespace()) {
            append(code[i])
            i++
            continue
        }
        
        // Комментарии
        if (i + 1 < n && code[i] == '/' && code[i + 1] == '/') {
            withStyle(SpanStyle(color = commentColor)) {
                while (i < n && code[i] != '\n') {
                    append(code[i])
                    i++
                }
                if (i < n) {
                    append(code[i]) // добавляем \n
                    i++
                }
            }
            continue
        }
        
        if (i + 1 < n && code[i] == '/' && code[i + 1] == '*') {
            withStyle(SpanStyle(color = commentColor)) {
                append(code[i])
                append(code[i + 1])
                i += 2
                while (i + 1 < n && !(code[i] == '*' && code[i + 1] == '/')) {
                    append(code[i])
                    i++
                }
                if (i + 1 < n) {
                    append(code[i])   // *
                    append(code[i + 1]) // /
                    i += 2
                }
            }
            continue
        }
        
        // Строки
        if (code[i] == '"' || code[i] == '\'' || code[i] == '`') {
            val quoteChar = code[i]
            withStyle(SpanStyle(color = stringColor)) {
                append(code[i])
                i++
                var escaped = false
                while (i < n && (escaped || code[i] != quoteChar)) {
                    if (code[i] == '\\') escaped = !escaped else escaped = false
                    append(code[i])
                    i++
                }
                if (i < n) {
                    append(code[i]) // закрывающая кавычка
                    i++
                }
            }
            continue
        }
        
        // Числа
        if (code[i].isDigit() || (code[i] == '.' && i + 1 < n && code[i + 1].isDigit())) {
            withStyle(SpanStyle(color = numberColor)) {
                while (i < n && (code[i].isDigit() || code[i] == '.' || code[i] == 'e' || 
                       code[i] == 'E' || code[i] == '+' || code[i] == '-')) {
                    append(code[i])
                    i++
                }
            }
            continue
        }
        
        // Идентификаторы и ключевые слова
        if (code[i].isLetter() || code[i] == '_') {
            val start = i
            while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) {
                i++
            }
            val word = code.substring(start, i)
            
            when {
                keywords.contains(word) -> withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)) {
                    append(word)
                }
                typesOrBuiltins.contains(word) -> withStyle(SpanStyle(color = typeColor)) {
                    append(word)
                }
                word == "function" || word == "def" || word == "fun" -> withStyle(SpanStyle(color = functionColor, fontWeight = FontWeight.Bold)) {
                    append(word)
                }
                else -> {
                    // Проверяем, является ли следующее слово функцией (есть открывающая скобка)
                    if (i < n && code[i] == '(') {
                        withStyle(SpanStyle(color = functionColor)) {
                            append(word)
                        }
                    } else {
                        append(word)
                    }
                }
            }
            continue
        }
        
        // Остальные символы
        append(code[i])
        i++
    }
}