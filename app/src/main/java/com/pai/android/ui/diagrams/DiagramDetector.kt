package com.pai.android.ui.diagrams

/**
 * Утилита для обнаружения диаграмм в тексте сообщения.
 */
object DiagramDetector {
    
    /**
     * Проверяет, содержит ли текст диаграмму.
     */
    fun containsDiagram(text: String): Boolean {
        return extractDiagramCode(text) != null
    }
    
    /**
     * Извлекает код диаграммы из текста.
     * Поддерживает форматы с бэктиками:
     * ```mermaid
     * graph TD
     * A-->B
     * ```
     * 
     * Возвращает чистый код диаграммы или null, если диаграмма не обнаружена.
     */
    fun extractDiagramCode(text: String): String? {
        val lines = text.lines()
        var inCodeBlock = false
        var codeBlockType: String? = null
        val codeBuilder = StringBuilder()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Проверяем начало code block
            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    // Начало code block
                    inCodeBlock = true
                    codeBlockType = trimmed.substring(3).trim().lowercase()
                    codeBuilder.clear()
                } else {
                    // Конец code block
                    inCodeBlock = false
                    val code = codeBuilder.toString().trim()
                    
                    // Проверяем, является ли это диаграммой
                    val diagramType = DiagramType.fromCode(code)
                    if (diagramType != DiagramType.UNKNOWN) {
                        return code
                    }
                }
                continue
            }
            
            if (inCodeBlock) {
                codeBuilder.appendLine(line)
            } else {
                // Проверяем встроенные диаграммы без бэктиков
                val diagramType = DiagramType.fromCode(trimmed)
                if (diagramType != DiagramType.UNKNOWN) {
                    return trimmed
                }
            }
        }
        
        return null
    }
    
    /**
     * Определяет тип диаграммы в тексте.
     */
    fun detectDiagramType(text: String): DiagramType {
        val code = extractDiagramCode(text)
        return if (code != null) {
            DiagramType.fromCode(code)
        } else {
            DiagramType.UNKNOWN
        }
    }
    
    /**
     * Извлекает заголовок/описание диаграммы (первая строка комментария).
     */
    fun extractDiagramDescription(code: String): String {
        val lines = code.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("%%") || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                val description = trimmed.substring(2).trim()
                if (description.isNotEmpty()) {
                    return description
                }
            }
            if (trimmed.startsWith("title") && trimmed.contains(":")) {
                return trimmed.substringAfter(":").trim()
            }
        }
        return "Диаграмма"
    }
}