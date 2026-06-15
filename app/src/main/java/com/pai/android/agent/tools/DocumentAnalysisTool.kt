package com.pai.android.agent.tools

import com.pai.android.agent.AgentTool
import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ToolResult
import com.pai.android.agent.FileManager
import java.io.File

/**
 * Инструмент для анализа документов.
 * Анализирует текстовые файлы, извлекает статистику, ключевые темы.
 */
class DocumentAnalysisTool constructor(
    private val fileManager: FileManager
) : BaseAgentTool() {
    
    override val name: String = "document_analysis"
    
    override val description: String = """
        Analyze documents in workspace.
        Supports analysis of individual files and entire folders.
        Extracts: statistics (word count, characters), main topics, key phrases.
        Supported formats: .txt, .md, .csv (as text), .json (as text).
    """.trimIndent()
    
    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["analyze_file", "analyze_folder", "compare_documents"],
                    "description": "Команда для выполнения"
                },
                "path": {
                    "type": "string",
                    "description": "Путь к файлу или папке"
                },
                "recursive": {
                    "type": "boolean",
                    "description": "Рекурсивный анализ папки",
                    "default": false
                },
                "extensions": {
                    "type": "string",
                    "description": "Расширения файлов через запятую (например: txt,md,json)",
                    "default": "txt,md,json,csv"
                },
                "min_words": {
                    "type": "integer",
                    "description": "Минимальное количество слов для анализа",
                    "default": 10
                },
                "max_files": {
                    "type": "integer",
                    "description": "Максимальное количество файлов для анализа",
                    "default": 50
                }
            },
            "required": ["command", "path"]
        }
    """.trimIndent()
    
    override val requiresConfirmation: Boolean = false
    
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val command = getStringParam(params, "command")
            
            when (command) {
                "analyze_file" -> executeAnalyzeFile(params)
                "analyze_folder" -> executeAnalyzeFolder(params)
                "compare_documents" -> executeCompareDocuments(params)
                else -> ToolResult.Error("Неизвестная команда: $command")
            }
        } catch (e: Exception) {
            ToolResult.Error("Ошибка выполнения: ${e.message}")
        }
    }
    
    private fun executeAnalyzeFile(params: Map<String, Any>): ToolResult {
        val path = getStringParam(params, "path")
        val content = fileManager.readFile(path)
        
        if (content == null) {
            return ToolResult.Error("Файл не найден или не может быть прочитан: $path")
        }
        
        val analysis = analyzeText(content, path)
        
        return ToolResult.Success(
            output = formatAnalysis(analysis),
            data = mapOf(
                "analysis" to analysis.toMap(),
                "path" to path,
                "goal_achieved" to true
            )
        )
    }
    
    private fun executeAnalyzeFolder(params: Map<String, Any>): ToolResult {
        val path = getStringParam(params, "path")
        val recursive = getBooleanParam(params, "recursive", false)
        val extensions = (params["extensions"] as? String ?: "txt,md,json,csv")
            .split(",").map { it.trim() }
        val minWords = params["min_words"] as? Int ?: 10
        val maxFiles = params["max_files"] as? Int ?: 50
        
        // Получаем все файлы
        val allFiles = fileManager.listFiles(path, recursive)
        val textFiles = allFiles.filter { file ->
            !file.isDirectory && extensions.any { ext ->
                file.name.lowercase().endsWith(".$ext")
            }
        }
        
        if (textFiles.isEmpty()) {
            return ToolResult.Success(
                output = "В папке $path не найдено текстовых файлов с расширениями: ${extensions.joinToString()}",
                data = mapOf("path" to path, "total_files" to 0, "goal_achieved" to true)
            )
        }
        
        // Анализируем файлы (ограничиваем количество)
        val filesToAnalyze = textFiles.take(maxFiles)
        val analyses = mutableListOf<DocumentAnalysis>()
        
        filesToAnalyze.forEach { file ->
            val content = fileManager.readFile(file.path)
            if (content != null && content.split("\\s+".toRegex()).size >= minWords) {
                analyses.add(analyzeText(content, file.path))
            }
        }
        
        if (analyses.isEmpty()) {
            return ToolResult.Success(
                output = "Файлы найдены, но ни один не содержит достаточно текста (минимум $minWords слов)",
                data = mapOf("path" to path, "total_files" to textFiles.size, "analyzed" to 0, "goal_achieved" to true)
            )
        }
        
        // Сводный анализ
        val summary = createSummaryAnalysis(analyses, path)
        
        return ToolResult.Success(
            output = formatFolderAnalysis(summary, analyses),
            data = mapOf(
                "summary" to summary.toMap(),
                "analyses" to analyses.map { it.toMap() },
                "total_files" to textFiles.size,
                "analyzed_files" to analyses.size,
                "path" to path,
                "goal_achieved" to true
            )
        )
    }
    
    private fun executeCompareDocuments(params: Map<String, Any>): ToolResult {
        // TODO: Реализовать сравнение документов
        return ToolResult.Success(
            output = "Сравнение документов (функция в разработке)",
            data = mapOf("status" to "in_development", "goal_achieved" to true)
        )
    }
    
    private fun analyzeText(text: String, filePath: String): DocumentAnalysis {
        // Очищаем текст
        val cleanText = text.replace("\\s+".toRegex(), " ").trim()
        
        // Базовая статистика
        val chars = cleanText.length
        val words = cleanText.split("\\s+".toRegex()).size
        val lines = text.lines().size
        val paragraphs = text.split("\\n\\s*\\n".toRegex()).size
        
        // Простой анализ тем (по ключевым словам)
        val commonWords = listOf(
            "проект", "задача", "отчёт", "анализ", "данные", "результат",
            "план", "время", "работа", "система", "пользователь", "файл",
            "память", "инструмент", "ассистент", "android", "приложение"
        )
        
        val foundThemes = commonWords.filter { theme ->
            cleanText.lowercase().contains(theme.lowercase())
        }.take(5)
        
        // Извлекаем "ключевые фразы" (первые 3 предложения)
        val sentences = text.split("[.!?]+".toRegex())
        val keyPhrases = sentences.take(3).map { it.trim() }.filter { it.isNotEmpty() }
        
        return DocumentAnalysis(
            filePath = filePath,
            fileName = File(filePath).name,
            charCount = chars,
            wordCount = words,
            lineCount = lines,
            paragraphCount = paragraphs,
            themes = foundThemes,
            keyPhrases = keyPhrases,
            readingTimeMinutes = words / 200, // 200 слов в минуту
            complexityScore = calculateComplexity(text)
        )
    }
    
    private fun createSummaryAnalysis(analyses: List<DocumentAnalysis>, path: String): FolderAnalysis {
        val totalFiles = analyses.size
        val totalWords = analyses.sumOf { it.wordCount }
        val totalChars = analyses.sumOf { it.charCount }
        
        // Общие темы
        val allThemes = analyses.flatMap { it.themes }
        val themeFrequency = allThemes.groupingBy { it }.eachCount()
        val topThemes = themeFrequency.entries.sortedByDescending { it.value }.take(5)
        
        // Средние показатели
        val avgWords = if (totalFiles > 0) totalWords / totalFiles else 0
        val avgChars = if (totalFiles > 0) totalChars / totalFiles else 0
        
        // Самый большой и самый маленький файл
        val largestFile = analyses.maxByOrNull { it.wordCount }
        val smallestFile = analyses.minByOrNull { it.wordCount }
        
        return FolderAnalysis(
            path = path,
            totalFiles = totalFiles,
            totalWords = totalWords,
            totalChars = totalChars,
            avgWordsPerFile = avgWords,
            avgCharsPerFile = avgChars,
            topThemes = topThemes.associate { it.key to it.value },
            largestFile = largestFile?.fileName ?: "",
            largestFileWords = largestFile?.wordCount ?: 0,
            smallestFile = smallestFile?.fileName ?: "",
            smallestFileWords = smallestFile?.wordCount ?: 0
        )
    }
    
    private fun calculateComplexity(text: String): Float {
        // Простая метрика сложности: средняя длина слова + длина предложения
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return 0f
        
        val avgWordLength = words.sumOf { it.length } / words.size.toFloat()
        val sentences = text.split("[.!?]+".toRegex()).filter { it.trim().isNotEmpty() }
        val avgSentenceLength = if (sentences.isNotEmpty()) words.size / sentences.size.toFloat() else 0f
        
        return (avgWordLength * 0.3f + avgSentenceLength * 0.7f) / 10f
    }
    
    private fun formatAnalysis(analysis: DocumentAnalysis): String {
        return """
        📄 Анализ документа: ${analysis.fileName}
        
        📊 Статистика:
        - Символов: ${analysis.charCount}
        - Слов: ${analysis.wordCount}
        - Строк: ${analysis.lineCount}
        - Абзацев: ${analysis.paragraphCount}
        - Время чтения: ~${analysis.readingTimeMinutes} мин
        - Сложность: ${String.format("%.1f", analysis.complexityScore)}/10
        
        🏷️ Темы: ${if (analysis.themes.isNotEmpty()) analysis.themes.joinToString(", ") else "не определены"}
        
        🔑 Ключевые фразы:
        ${analysis.keyPhrases.take(3).joinToString("\n") { "• $it" }}
        
        📍 Путь: ${analysis.filePath}
        """.trimIndent()
    }
    
    private fun formatFolderAnalysis(summary: FolderAnalysis, analyses: List<DocumentAnalysis>): String {
        return """
        📁 Анализ папки: ${summary.path}
        
        📊 Общая статистика:
        - Файлов проанализировано: ${summary.totalFiles}
        - Всего слов: ${summary.totalWords}
        - Всего символов: ${summary.totalChars}
        - Среднее слов на файл: ${summary.avgWordsPerFile}
        - Среднее символов на файл: ${summary.avgCharsPerFile}
        
        🏷️ Частые темы:
        ${summary.topThemes.entries.joinToString("\n") { "- ${it.key}: ${it.value} файлов" }}
        
        📈 Экстремумы:
        - Самый большой файл: ${summary.largestFile} (${summary.largestFileWords} слов)
        - Самый маленький файл: ${summary.smallestFile} (${summary.smallestFileWords} слов)
        
        📋 Детали по файлам (первые 5):
        ${analyses.take(5).joinToString("\n\n") { formatAnalysis(it) }}
        
        ${if (analyses.size > 5) "... и ещё ${analyses.size - 5} файлов" else ""}
        """.trimIndent()
    }
}

/**
 * Анализ одного документа.
 */
data class DocumentAnalysis(
    val filePath: String,
    val fileName: String,
    val charCount: Int,
    val wordCount: Int,
    val lineCount: Int,
    val paragraphCount: Int,
    val themes: List<String>,
    val keyPhrases: List<String>,
    val readingTimeMinutes: Int,
    val complexityScore: Float
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "file_path" to filePath,
            "file_name" to fileName,
            "char_count" to charCount,
            "word_count" to wordCount,
            "line_count" to lineCount,
            "paragraph_count" to paragraphCount,
            "themes" to themes,
            "key_phrases" to keyPhrases,
            "reading_time_minutes" to readingTimeMinutes,
            "complexity_score" to complexityScore
        )
    }
}

/**
 * Сводный анализ папки.
 */
data class FolderAnalysis(
    val path: String,
    val totalFiles: Int,
    val totalWords: Int,
    val totalChars: Int,
    val avgWordsPerFile: Int,
    val avgCharsPerFile: Int,
    val topThemes: Map<String, Int>,
    val largestFile: String,
    val largestFileWords: Int,
    val smallestFile: String,
    val smallestFileWords: Int
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "path" to path,
            "total_files" to totalFiles,
            "total_words" to totalWords,
            "total_chars" to totalChars,
            "avg_words_per_file" to avgWordsPerFile,
            "avg_chars_per_file" to avgCharsPerFile,
            "top_themes" to topThemes,
            "largest_file" to largestFile,
            "largest_file_words" to largestFileWords,
            "smallest_file" to smallestFile,
            "smallest_file_words" to smallestFileWords
        )
    }
}