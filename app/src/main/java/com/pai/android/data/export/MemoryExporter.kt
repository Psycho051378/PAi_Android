package com.pai.android.data.export

import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.model.DailyMemory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспортёр памяти в формат OpenClaw Markdown (MEMORY.md).
 */
class MemoryExporter {
    
    /**
     * Экспортирует факты и дневные записи в формат Markdown.
     */
    fun exportToMarkdown(
        facts: List<PermanentMemory>,
        dailyEntries: List<DailyMemory>
    ): String {
        val builder = StringBuilder()
        
        // Заголовок файла
        builder.append("# Память Pai_Android\n\n")
        builder.append("> Экспортировано: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}\n")
        builder.append("> Всего фактов: ${facts.size}, дневных записей: ${dailyEntries.size}\n\n")
        
        // Экспорт фактов по scope
        exportFactsByScope(builder, facts)
        
        // Экспорт дневных записей
        exportDailyEntries(builder, dailyEntries)
        
        return builder.toString()
    }
    
    /**
     * Экспортирует факты, сгруппированные по scope.
     */
    private fun exportFactsByScope(
        builder: StringBuilder,
        facts: List<PermanentMemory>
    ) {
        if (facts.isEmpty()) {
            builder.append("## Факты\n\n*Нет фактов*\n\n")
            return
        }
        
        // Группировка по scope
        val groupedByScope = facts.groupBy { it.scope }
        
        listOf("user", "ai", "global").forEach { scope ->
            val scopeFacts = groupedByScope[scope] ?: return@forEach
            if (scopeFacts.isEmpty()) return@forEach
            
            val scopeEmoji = when (scope) {
                "user" -> "👤"
                "ai" -> "🤖"
                "global" -> "🌍"
                else -> "❓"
            }
            
            builder.append("## Факты ($scopeEmoji ${scope.capitalize()})\n\n")
            
            // Группировка по категориям для лучшей читаемости
            val groupedByCategory = scopeFacts.groupBy { it.category }
            groupedByCategory.forEach { (category, categoryFacts) ->
                if (categoryFacts.isNotEmpty()) {
                    builder.append("### $category\n\n")
                    
                    categoryFacts.forEach { fact ->
                        exportFact(builder, fact)
                    }
                    builder.append("\n")
                }
            }
            
            builder.append("\n")
        }
    }
    
    /**
     * Экспортирует один факт в Markdown.
     */
    private fun exportFact(
        builder: StringBuilder,
        fact: PermanentMemory
    ) {
        builder.append("- **${fact.key}**: ${fact.value}")
        
        // Добавляем метаданные
        val metadata = mutableListOf<String>()
        
        // Уверенность (только если не 1.0)
        if (fact.confidence < 1.0f) {
            metadata.add("confidence: ${String.format("%.2f", fact.confidence)}")
        }
        
        // Теги
        if (!fact.tags.isNullOrEmpty()) {
            metadata.add("tags: ${fact.tags}")
        }
        
        // Добавляем метаданные в скобках
        if (metadata.isNotEmpty()) {
            builder.append(" (${metadata.joinToString(", ")})")
        }
        
        builder.append("\n")
    }
    
    /**
     * Экспортирует дневные записи.
     */
    private fun exportDailyEntries(
        builder: StringBuilder,
        dailyEntries: List<DailyMemory>
    ) {
        if (dailyEntries.isEmpty()) {
            builder.append("## Дневные записи\n\n*Нет дневных записей*\n\n")
            return
        }
        
        builder.append("## Дневные записи\n\n")
        
        // Сортируем по дате (новые сверху)
        val sortedEntries = dailyEntries.sortedByDescending { it.date }
        
        sortedEntries.forEach { daily ->
            exportDailyEntry(builder, daily)
        }
    }
    
    /**
     * Экспортирует одну дневную запись.
     */
    private fun exportDailyEntry(
        builder: StringBuilder,
        daily: DailyMemory
    ) {
        builder.append("### ${formatDate(daily.date)}\n\n")
        builder.append("${daily.content}\n\n")
        
        // Теги
        if (!daily.tags.isNullOrEmpty()) {
            builder.append("🏷️ *Теги*: ${daily.tags}\n\n")
        }
        
        builder.append("---\n\n")
    }
    
    /**
     * Форматирует дату в читаемый вид.
     */
    private fun formatDate(dateStr: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val outputFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
            val date = inputFormat.parse(dateStr)
            date?.let { outputFormat.format(it) } ?: dateStr
        } catch (e: Exception) {
            dateStr
        }
    }
    
    /**
     * Создаёт имя файла для экспорта.
     */
    fun generateFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        return "pai_memory_export_$date.md"
    }
}