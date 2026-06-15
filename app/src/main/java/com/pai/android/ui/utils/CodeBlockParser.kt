package com.pai.android.ui.utils

import kotlin.text.RegexOption.DOT_MATCHES_ALL

/**
 * Модель для представления частей сообщения.
 */
sealed class MessagePart {
    data class TextPart(val text: String) : MessagePart()
    data class CodeBlockPart(
        val language: String,
        val code: String
    ) : MessagePart()
}

/**
 * Парсер для разбивки текста сообщения на обычный текст и блоки кода.
 */
object CodeBlockParser {
    
    /**
     * Регулярное выражение для поиска блоков кода в формате ```language\ncode\n```
     * Поддерживает необязательное указание языка после ```
     */
    private val codeBlockRegex = Regex("```(\\w*)\\n([\\s\\S]*?)\\n```", DOT_MATCHES_ALL)
    
    /**
     * Разбивает текст на части: обычный текст и блоки кода.
     * 
     * @param text Исходный текст сообщения
     * @return Список частей сообщения в порядке их следования в тексте
     */
    fun parse(text: String): List<MessagePart> {
        val parts = mutableListOf<MessagePart>()
        var lastIndex = 0
        
        // Находим все блоки кода в тексте
        codeBlockRegex.findAll(text).forEach { matchResult ->
            val (language, code) = matchResult.destructured
            val matchStart = matchResult.range.first
            val matchEnd = matchResult.range.last + 1
            
            // Добавляем текст перед блоком кода (если есть)
            if (matchStart > lastIndex) {
                val textBefore = text.substring(lastIndex, matchStart)
                parts.add(MessagePart.TextPart(textBefore))
            }
            
            // Добавляем блок кода
            val actualLanguage = if (language.isNotBlank()) language else "text"
            parts.add(MessagePart.CodeBlockPart(actualLanguage, code.trim()))
            
            lastIndex = matchEnd
        }
        
        // Добавляем оставшийся текст после последнего блока кода
        if (lastIndex < text.length) {
            val remainingText = text.substring(lastIndex)
            parts.add(MessagePart.TextPart(remainingText))
        }
        
        return parts
    }
    
    /**
     * Извлекает все блоки кода из текста.
     * 
     * @param text Исходный текст
     * @return Список блоков кода (язык и код)
     */
    fun extractCodeBlocks(text: String): List<Pair<String, String>> {
        return codeBlockRegex.findAll(text).map { matchResult ->
            val (language, code) = matchResult.destructured
            val actualLanguage = if (language.isNotBlank()) language else "text"
            actualLanguage to code.trim()
        }.toList()
    }
    
    /**
     * Проверяет, содержит ли текст блоки кода.
     */
    fun containsCodeBlocks(text: String): Boolean {
        return codeBlockRegex.containsMatchIn(text)
    }
    
    /**
     * Удаляет обёртку ``` из текста блока кода.
     * Полезно для получения чистого кода.
     */
    fun stripCodeBlockMarkers(text: String): String {
        return codeBlockRegex.replace(text) { matchResult ->
            val (_, code) = matchResult.destructured
            code.trim()
        }
    }
    
    /**
     * Возвращает рекомендуемое расширение файла для заданного языка программирования.
     */
    fun getFileExtension(language: String): String {
        return when (language.lowercase()) {
            "python", "py" -> ".py"
            "javascript", "js" -> ".js"
            "typescript", "ts" -> ".ts"
            "java" -> ".java"
            "kotlin", "kt" -> ".kt"
            "cpp", "c++" -> ".cpp"
            "c" -> ".c"
            "csharp", "cs" -> ".cs"
            "go" -> ".go"
            "rust", "rs" -> ".rs"
            "php" -> ".php"
            "ruby", "rb" -> ".rb"
            "swift" -> ".swift"
            "objectivec", "objective-c", "m" -> ".m"
            "sql" -> ".sql"
            "html" -> ".html"
            "css" -> ".css"
            "xml" -> ".xml"
            "json" -> ".json"
            "yaml", "yml" -> ".yml"
            "markdown", "md" -> ".md"
            "bash", "sh", "shell" -> ".sh"
            "powershell", "ps1" -> ".ps1"
            "dockerfile", "docker" -> ".dockerfile"
            "text", "plaintext", "txt" -> ".txt"
            else -> ".txt"
        }
    }
}