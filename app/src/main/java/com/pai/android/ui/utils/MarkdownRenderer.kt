package com.pai.android.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Лёгкий парсер Markdown для Compose.
 * Конвертирует текст с MD-разметкой в AnnotatedString с применёнными стилями.
 */
object MarkdownRenderer {

    fun render(
        text: String,
        linkColor: Color = Color(0xFF1976D2)
    ): AnnotatedString = buildAnnotatedString {
        val lines = text.lines()
        
        for ((index, line) in lines.withIndex()) {
            if (index > 0) append("\n")
            
            when {
                // Горизонтальный разделитель
                line.matches(Regex("^\\s*[-*_]{3,}\\s*$")) -> {
                    withStyle(SpanStyle(color = Color.Gray, fontWeight = FontWeight.Light)) {
                        append("─".repeat(40))
                    }
                }
                
                // Заголовки ### ... ######
                line.matches(Regex("^#{1,6}\\s.*")) -> {
                    val level = line.takeWhile { it == '#' }.length
                    val headerText = line.drop(level).trim()
                    val fontSize = when (level) {
                        1 -> 20.sp; 2 -> 18.sp; 3 -> 16.sp; 4 -> 15.sp
                        else -> 14.sp
                    }
                    append("\n")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = fontSize)) {
                        renderInline(headerText, linkColor)
                    }
                    append("\n")
                }
                
                // Таблица | col1 | col2 | col3 |
                line.matches(Regex("^\\s*\\|.*\\|\\s*$")) && line.matches(Regex(".*-+-.*")) -> {
                    // Separator line like |---|---|
                    // skip
                }
                line.matches(Regex("^\\s*\\|.*\\|\\s*$")) -> {
                    val cols = line.split(Regex("\\|")).map { it.trim() }.filter { it.isNotEmpty() }
                    val formatted = cols.joinToString(" │ ")  // U+2502 box drawing light vertical
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )) {
                        append("│ ")  // leading bar
                        renderInline(formatted, linkColor)
                        append(" │")
                    }
                }
                
                // Всё остальное
                else -> renderInline(line, linkColor)
            }
        }
    }

    private fun AnnotatedString.Builder.renderInline(text: String, linkColor: Color) {
        val regex = Regex(
            """(`[^`\n]+`)|""" +
            """(\*\*\*[^*\n]+\*\*\*)|""" +
            """(\*\*[^*\n]+\*\*)|""" +
            """(\*[^*\n]+\*)|""" +
            """(\[([^\]]+)\]\(([^)]+)\))"""
        )
        
        var lastIndex = 0
        for (match in regex.findAll(text)) {
            if (match.range.first > lastIndex) {
                append(text.substring(lastIndex, match.range.first))
            }
            
            when {
                // `code`
                match.groupValues[1].isNotEmpty() -> {
                    val code = match.groupValues[1].trim('`')
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0xFFE8E8E8),
                        color = Color(0xFFD63384),
                        fontSize = 14.sp
                    )) {
                        append(code)
                    }
                }
                // ***bold italic***
                match.groupValues[2].isNotEmpty() -> {
                    val content = match.groupValues[2].trim('*')
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(content)
                    }
                }
                // **bold**
                match.groupValues[3].isNotEmpty() -> {
                    val content = match.groupValues[3].trim('*')
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(content)
                    }
                }
                // *italic*
                match.groupValues[4].isNotEmpty() -> {
                    val content = match.groupValues[4].trim('*')
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(content)
                    }
                }
                // [text](url)
                match.groupValues[5].isNotEmpty() -> {
                    val linkText = match.groupValues[6]
                    val url = match.groupValues[7]
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(linkText)
                    }
                    pop()
                }
            }
            
            lastIndex = match.range.last + 1
        }
        
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}
