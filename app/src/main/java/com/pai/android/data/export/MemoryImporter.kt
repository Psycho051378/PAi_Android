package com.pai.android.data.export

import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.model.DailyMemory
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

/**
 * Импортёр памяти из формата OpenClaw Markdown (MEMORY.md).
 */
class MemoryImporter {
    
    /**
     * Результат импорта.
     */
    data class ImportResult(
        val facts: List<PermanentMemory> = emptyList(),
        val dailyEntries: List<DailyMemory> = emptyList(),
        val errors: List<String> = emptyList(),
        val totalProcessed: Int = 0,
        val successfullyImported: Int = 0
    ) {
        val isSuccess: Boolean get() = errors.isEmpty() && successfullyImported > 0
    }
    
    /**
     * Импортирует память из Markdown строки.
     */
    fun importFromMarkdown(
        markdown: String,
        defaultScope: String = "user",
        defaultConfidence: Float = 1.0f
    ): ImportResult {
        val lines = markdown.lines()
        val errors = mutableListOf<String>()
        val importedFacts = mutableListOf<PermanentMemory>()
        val importedDailyEntries = mutableListOf<DailyMemory>()
        
        var currentScope = defaultScope
        var currentCategory = "imported"
        var i = 0
        
        while (i < lines.size) {
            val line = lines[i].trim()
            
            when {
                // Игнорируем комментарии и пустые строки
                line.startsWith("<!--") || line.isEmpty() -> {
                    i++
                }
                
                // Определяем scope по заголовку
                line.startsWith("## Факты (") -> {
                    currentScope = parseScopeFromHeader(line)
                    i++
                }
                
                // Определяем категорию
                line.startsWith("### ") && !line.contains("MMM") -> { // Не дата (в датах есть MMM)
                    currentCategory = line.removePrefix("### ").trim()
                    i++
                }
                
                // Импорт факта (форматы: "- **ключ**: значение" или "- ключ: значение")
                line.startsWith("- ") && line.contains(":") -> {
                    try {
                        val fact = parseFactLine(line, currentScope, currentCategory, defaultConfidence)
                        importedFacts.add(fact)
                    } catch (e: Exception) {
                        errors.add("Ошибка парсинга факта (строка ${i + 1}): ${e.message}")
                    }
                    i++
                }
                
                // Секция дневных записей
                line.startsWith("## Дневные записи") -> {
                    i = parseDailyEntriesSection(lines, i + 1, importedDailyEntries, errors)
                }
                
                else -> {
                    i++
                }
            }
        }
        
        return ImportResult(
            facts = importedFacts,
            dailyEntries = importedDailyEntries,
            errors = errors,
            totalProcessed = importedFacts.size + importedDailyEntries.size,
            successfullyImported = importedFacts.size + importedDailyEntries.size
        )
    }
    
    /**
     * Парсит scope из заголовка.
     */
    private fun parseScopeFromHeader(line: String): String {
        return when {
            "👤" in line -> "user"
            "🤖" in line -> "ai"
            "🌍" in line -> "global"
            else -> "user"
        }
    }
    
    /**
     * Парсит строку факта.
     * Поддерживает два формата:
     * 1. "- **ключ**: значение (confidence: 0.8, tags: тег1,тег2)"
     * 2. "- ключ: значение (confidence: 0.8, tags: тег1,тег2)"
     */
    private fun parseFactLine(
        line: String,
        scope: String,
        category: String,
        defaultConfidence: Float
    ): PermanentMemory {
        // Убираем маркер списка
        var content = line.removePrefix("- ").trim()
        
        // Извлекаем ключ
        val key: String
        val keyStart = content.indexOf("**")
        if (keyStart >= 0) {
            // Формат с **
            val keyStartIdx = keyStart + 2
            val keyEndIdx = content.indexOf("**", keyStartIdx)
            require(keyEndIdx > keyStartIdx) { "Не найден закрывающий **" }
            key = content.substring(keyStartIdx, keyEndIdx)
            content = content.substring(keyEndIdx + 2).trim()
        } else {
            // Формат без ** (просто ключ: значение)
            val colonIdx = content.indexOf(':')
            require(colonIdx > 0) { "Не найден разделитель : для ключа" }
            key = content.substring(0, colonIdx).trim()
            content = content.substring(colonIdx + 1).trim()
        }
        
        // Извлекаем значение (до открывающей скобки или до конца)
        val value: String
        var confidence = defaultConfidence
        var tags: String? = null
        
        val metadataStart = content.indexOf('(')
        if (metadataStart > 0) {
            value = content.substring(0, metadataStart).trim()
            val metadataEnd = content.indexOf(')', metadataStart)
            if (metadataEnd > metadataStart) {
                val metadata = content.substring(metadataStart + 1, metadataEnd)
                parseMetadata(metadata).let { (conf, tag) ->
                    confidence = conf ?: confidence
                    tags = tag
                }
            }
        } else {
            value = content.trim()
        }
        
        return PermanentMemory(
            category = category,
            key = key,
            value = value,
            confidence = confidence,
            scope = scope,
            tags = tags
        )
    }
    
    /**
     * Парсит метаданные из скобок.
     * Формат: "confidence: 0.8, tags: тег1,тег2"
     * Поддерживает любой порядок и отсутствие полей.
     */
    private fun parseMetadata(metadata: String): Pair<Float?, String?> {
        var confidence: Float? = null
        var tags: String? = null
        
        var remaining = metadata.trim()
        while (remaining.isNotEmpty()) {
            when {
                remaining.startsWith("confidence:") -> {
                    // Извлекаем значение confidence
                    val afterPrefix = remaining.substringAfter("confidence:").trim()
                    val endIndex = afterPrefix.indexOf(',')
                    val confStr = if (endIndex > 0) afterPrefix.substring(0, endIndex).trim() else afterPrefix.trim()
                    confidence = confStr.toFloatOrNull()
                    // Удаляем обработанную часть
                    remaining = if (endIndex > 0) afterPrefix.substring(endIndex + 1).trim() else ""
                }
                remaining.startsWith("tags:") -> {
                    // Извлекаем все теги (до конца или до следующего confidence: если есть)
                    val afterPrefix = remaining.substringAfter("tags:").trim()
                    val confidenceIndex = afterPrefix.indexOf("confidence:")
                    val tagsStr = if (confidenceIndex > 0) afterPrefix.substring(0, confidenceIndex).trim() else afterPrefix.trim()
                    tags = tagsStr
                    // Удаляем обработанную часть
                    remaining = if (confidenceIndex > 0) afterPrefix.substring(confidenceIndex).trim() else ""
                }
                else -> {
                    // Пропускаем запятую или нераспознанную часть
                    if (remaining.startsWith(",")) {
                        remaining = remaining.substring(1).trim()
                    } else {
                        // Неизвестный формат, пропускаем символ
                        remaining = remaining.substring(1).trim()
                    }
                }
            }
        }
        
        return Pair(confidence, tags)
    }
    
    /**
     * Парсит секцию дневных записей.
     */
    private fun parseDailyEntriesSection(
        lines: List<String>,
        startIndex: Int,
        dailyEntries: MutableList<DailyMemory>,
        errors: MutableList<String>
    ): Int {
        var i = startIndex
        var currentDate: String? = null
        val contentBuilder = StringBuilder()
        
        while (i < lines.size && !lines[i].trim().startsWith("## ")) {
            val line = lines[i].trim()
            
            when {
                // Новая дата
                line.startsWith("### ") -> {
                    // Сохраняем предыдущую запись
                    saveDailyEntryIfNeeded(currentDate, contentBuilder, dailyEntries, errors)
                    
                    // Начинаем новую запись
                    currentDate = parseDateFromHeader(line)
                    contentBuilder.clear()
                    i++
                }
                
                // Игнорируем разделители
                line == "---" || line.isEmpty() -> {
                    i++
                }
                
                // Теги
                line.startsWith("🏷️") -> {
                    // Теги уже обработаны в saveDailyEntryIfNeeded
                    i++
                }
                
                // Контент
                else -> {
                    contentBuilder.append(line).append("\n")
                    i++
                }
            }
        }
        
        // Сохраняем последнюю запись
        saveDailyEntryIfNeeded(currentDate, contentBuilder, dailyEntries, errors)
        
        return i
    }
    
    /**
     * Парсит дату из заголовка.
     */
    private fun parseDateFromHeader(line: String): String? {
        return try {
            val dateStr = line.removePrefix("### ").trim()
            // Пытаемся распарсить "d MMMM yyyy" в "yyyy-MM-dd"
            val inputFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
            val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val date = inputFormat.parse(dateStr)
            date?.let { outputFormat.format(it) }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Сохраняет дневную запись, если есть данные.
     */
    private fun saveDailyEntryIfNeeded(
        date: String?,
        contentBuilder: StringBuilder,
        dailyEntries: MutableList<DailyMemory>,
        errors: MutableList<String>
    ) {
        val content = contentBuilder.toString().trim()
        if (date != null && content.isNotBlank()) {
            try {
                val daily = DailyMemory(
                    date = date!!,
                    content = content,
                    tags = "" // Теги парсятся отдельно (пока пусто)
                )
                dailyEntries.add(daily)
            } catch (e: Exception) {
                errors.add("Ошибка создания дневной записи для даты $date: ${e.message}")
            }
        }
    }
    
    /**
     * Проверяет, является ли файл корректным экспортом памяти.
     */
    fun isValidMemoryExport(markdown: String): Boolean {
        val lines = markdown.lines()
        return lines.any { it.contains("# Память Pai_Android") } ||
               lines.any { it.contains("## Факты") } ||
               lines.any { it.contains("## Дневные записи") }
    }
}