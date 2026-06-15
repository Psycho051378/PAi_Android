package com.pai.android.ui.diagrams

/**
 * Типы диаграмм, поддерживаемые приложением.
 */
enum class DiagramType {
    /** Mermaid диаграммы (https://mermaid.js.org/) */
    MERMAID,
    
    /** Graphviz диаграммы (DOT формат) */
    GRAPHVIZ,
    
    /** PlantUML диаграммы (UML, последовательности и др.) */
    PLANTUML,
    
    /** Неизвестный тип диаграммы */
    UNKNOWN;
    
    companion object {
        /**
         * Определяет тип диаграммы по её коду.
         */
        fun fromCode(code: String): DiagramType {
            val trimmed = code.trim()
            return when {
                trimmed.startsWith("```mermaid") || 
                trimmed.contains("graph ") || 
                trimmed.contains("sequenceDiagram") ||
                trimmed.contains("classDiagram") ||
                trimmed.contains("stateDiagram") ||
                trimmed.contains("pie") ||
                trimmed.contains("gantt") ||
                trimmed.contains("gitGraph") -> MERMAID
                
                trimmed.startsWith("```dot") ||
                trimmed.startsWith("digraph") ||
                trimmed.startsWith("graph") ||
                trimmed.contains("->") && trimmed.contains("[") -> GRAPHVIZ
                
                trimmed.startsWith("```plantuml") ||
                trimmed.startsWith("@startuml") ||
                trimmed.contains("participant") ||
                trimmed.contains("actor") ||
                trimmed.contains("usecase") ||
                trimmed.contains("class") && trimmed.contains("{" ) -> PLANTUML
                
                else -> UNKNOWN
            }
        }
    }
}