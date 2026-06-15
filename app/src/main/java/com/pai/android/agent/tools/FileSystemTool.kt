package com.pai.android.agent.tools

import com.pai.android.agent.AgentTool
import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ToolResult
import com.pai.android.agent.FileManager
import com.pai.android.agent.FileInfo

/**
 * Инструмент для работы с файловой системой.
 * Поддерживает основные операции: создание папок, чтение/запись файлов, анализ.
 */
class FileSystemTool constructor(
    private val fileManager: FileManager
) : BaseAgentTool() {
    
    override val name: String = "file_system"
    
    override val description: String = """
        File system operations in workspace.
        Supported commands:
        - create_folder: create a new folder
        - write_file: create or overwrite a file
        - append_file: append content to a file
        - read_file: read file content
        - read_multiple: read MULTIPLE FILES AT ONCE (param: paths)
        - list_files: list files in directory
        - analyze_directory: analyze directory contents
        - tree: show directory tree structure
        - read_directory: read ALL files in directory recursively
        - delete: delete file or folder (requires confirmation)
        - create_report: create a report in reports/ folder
        - move: move file (source → target)
    """.trimIndent()
    
    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["create_folder", "write_file", "append_file", "read_file", "read_multiple", "list_files", "analyze_directory", "tree", "read_directory", "delete", "move", "rename", "copy", "get_file_info", "edit_file", "create_report"],
                    "description": "Command to execute"
                },
                "path": {
                    "type": "string",
                    "description": "Relative path from workspace root"
                },
                "paths": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Array of paths for read_multiple: [\"path1\", \"path2\", ...]"
                },
                "content": {
                    "type": "string",
                    "description": "File content (for write_file/append_file/create_report)"
                },
                "recursive": {
                    "type": "boolean",
                    "description": "Рекурсивный обход (для list_files/analyze_directory) или удаление (для delete)",
                    "default": false
                },
                "format": {
                    "type": "string",
                    "description": "Формат файла (для create_report)",
                    "enum": ["md", "txt", "csv", "json"],
                    "default": "md"
                },
                "category": {
                    "type": "string",
                    "description": "Категория отчёта (для create_report)",
                    "default": "analysis"
                },
                "depth": {
                    "type": "integer",
                    "description": "Traversal depth for tree",
                    "default": 5
                }
            },
            "required": ["command"]
        }
    """.trimIndent()
    
    override val requiresConfirmation: Boolean
        get() {
            // Удаление требует подтверждения
            return when (lastCommand) {
                "delete" -> true
                else -> false
            }
        }
    
    private var lastCommand: String? = null
    
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val command = getStringParam(params, "command")
            lastCommand = command
            
            when (command) {
                "create_folder" -> executeCreateFolder(params)
                "write_file" -> executeWriteFile(params, append = false)
                "append_file" -> executeWriteFile(params, append = true)
                "read_file" -> executeReadFile(params)
                "read_multiple" -> executeReadMultiple(params)
                "list_files" -> executeListFiles(params)
                "analyze_directory" -> executeAnalyzeDirectory(params)
                "tree" -> executeTree(params)
                "read_directory" -> executeReadDirectory(params)
                "delete" -> executeDelete(params)
                "move" -> executeMove(params)
                "rename" -> executeRename(params)
                "copy" -> executeCopy(params)
                "get_file_info" -> executeGetFileInfo(params)
                "edit_file" -> executeEditFile(params)
                "create_report" -> executeCreateReport(params)
                else -> ToolResult.Error("Неизвестная команда: $command")
            }
        } catch (e: Exception) {
            ToolResult.Error("Ошибка выполнения: ${e.message}")
        }
    }
    
    private fun executeCreateFolder(params: Map<String, Any>): ToolResult {
        val path = getStringParam(params, "path")
        val folder = fileManager.createDirectory(path)
        
        // Если папка уже существует — это не ошибка
        if (folder == null) {
            val existingDir = java.io.File(fileManager.workspaceRoot, path)
            if (existingDir.exists()) {
                return ToolResult.Success(
                    output = "Папка уже существует: $path",
                    data = mapOf("path" to path, "goal_achieved" to true)
                )
            }
        }
        
        return if (folder != null) {
            return ToolResult.Success(
                output = "Папка создана: $path\nПолный путь: ${folder.absolutePath}",
                data = mapOf(
                    "absolute_path" to folder.absolutePath,
                    "goal_achieved" to true
                )
            )
        } else {
            ToolResult.Error("Не удалось создать папку: $path")
        }
    }
    
    private fun executeWriteFile(params: Map<String, Any>, append: Boolean): ToolResult {
        val path = getStringParam(params, "path")
        val content = getStringParam(params, "content")
        
        val success = fileManager.writeFile(path, content, append)
        
        return if (success) {
            val action = if (append) "дополнен" else "создан"
            return ToolResult.Success(
                output = "Файл $action: $path\nРазмер: ${content.length} символов",
                data = mapOf(
                    "path" to path,
                    "size_bytes" to content.length,
                    "action" to if (append) "append" else "write",
                    "goal_achieved" to true
                )
            )
        } else {
            ToolResult.Error("Не удалось ${if (append) "дополнить" else "создать"} файл: $path")
        }
    }
    
    private fun executeReadFile(params: Map<String, Any>): ToolResult {
        // Если передан массив путей — ошибка, нужно использовать read_multiple
        if (params["path"] is List<*>) {
            return ToolResult.Error("Для чтения нескольких файлов используй read_multiple с параметром paths=[...]")
        }
        var path = getStringParam(params, "path")
        
        // Поддержка нескольких файлов через разделитель ;
        if (path.contains(";")) {
            val paths = path.split(";").map { it.trim() }.filter { it.isNotBlank() }
            val results = mutableListOf<String>()
            var allSuccess = true
            for (p in paths) {
                val content = fileManager.readFile(p)
                if (content != null) {
                    val preview = if (content.length > 500) content.take(500) + "..." else content
                    results.add("--- $p (${content.length} симв.) ---\n$preview")
                } else {
                    results.add("--- $p ---\nФайл не найден")
                    allSuccess = false
                }
            }
            val output = results.joinToString("\n\n")
            return ToolResult.Success(
                output = output,
                data = mapOf("paths" to paths, "files" to results.size, "goal_achieved" to true)
            )
        }
        
        val content = fileManager.readFile(path)
        
        return if (content != null) {
            val preview = if (content.length > 500) content.take(500) + "..." else content
            return ToolResult.Success(
                output = "Содержимое файла $path (${content.length} символов):\n$preview",
                data = mapOf(
                    "path" to path,
                    "content" to content,
                    "size_bytes" to content.length,
                    "preview" to preview,
                    "goal_achieved" to true
                )
            )
        } else {
            ToolResult.Error("Файл не найден или не может быть прочитан: $path")
        }
    }
    
    private fun executeListFiles(params: Map<String, Any>): ToolResult {
        val path = params["path"] as? String ?: ""
        val recursive = getBooleanParam(params, "recursive", false)
        
        val files = fileManager.listFiles(path, recursive)
        
        return if (files.isNotEmpty()) {
            val output = StringBuilder()
            output.append("Файлы в ${if (path.isBlank()) "workspace" else path}:\n")
            
            files.forEach { file ->
                val type = if (file.isDirectory) "[DIR] " else "[FILE]"
                output.append("$type ${file.name} (${file.sizeFormatted}, ${file.lastModifiedFormatted})\n")
            }
            
            output.append("\nВсего: ${files.size} объектов")
            
            return ToolResult.Success(
                output = output.toString(),
                data = mapOf(
                    "path" to path,
                    "total_files" to files.size,
                    "files" to files.map { it.toMap() },
                    "goal_achieved" to true
                )
            )
        } else {
            return ToolResult.Success(
                output = "Директория ${if (path.isBlank()) "workspace" else path} пуста",
                data = mapOf("path" to path, "total_files" to 0, "goal_achieved" to true)
            )
        }
    }
    
    private fun executeAnalyzeDirectory(params: Map<String, Any>): ToolResult {
        val path = params["path"] as? String ?: ""
        val analysis = fileManager.analyzeDirectory(path)
        
        return ToolResult.Success(
            output = analysis.getSummary(),
            data = mapOf(
                "analysis" to mapOf(
                    "path" to analysis.path,
                    "total_files" to analysis.totalFiles,
                    "total_directories" to analysis.totalDirectories,
                    "total_size_bytes" to analysis.totalSizeBytes,
                    "total_size_formatted" to analysis.totalSizeFormatted,
                    "file_types" to analysis.fileTypes,
                    "last_modified" to analysis.lastModified
                ),
                "goal_achieved" to true
            )
        )
    }
    
    private fun executeTree(params: Map<String, Any>): ToolResult {
        val path = params["path"] as? String ?: ""
        val depth = (params["depth"] as? Number)?.toInt() ?: 5
        val tree = fileManager.generateTree(path, depth)
        return ToolResult.Success(
            output = "📂 Структура директории:\n\n" + tree,
            data = mapOf("path" to path, "goal_achieved" to true)
        )
    }

    private fun executeReadDirectory(params: Map<String, Any>): ToolResult {
        val path = params["path"] as? String ?: ""
        val content = fileManager.readDirectory(path)
        return ToolResult.Success(
            output = content,
            data = mapOf("path" to path, "goal_achieved" to true)
        )
    }

    private fun executeDelete(params: Map<String, Any>): ToolResult {
        val path = getStringParam(params, "path")
        val recursive = getBooleanParam(params, "recursive", true)
        
        // Для удаления пока просто выполняем, требует подтверждения через requiresConfirmation
        val success = fileManager.delete(path, recursive)
        
        return if (success) {
            return ToolResult.Success(
                output = "Удалено: $path",
                data = mapOf("path" to path, "recursive" to recursive, "goal_achieved" to true)
            )
        } else {
            ToolResult.Error("Не удалось удалить: $path")
        }
    }
    
    private fun executeMove(params: Map<String, Any>): ToolResult {
        val source = params["source"] as? String ?: params["path"] as? String
            ?: return ToolResult.Error("Не указан исходный файл")
        val target = params["target"] as? String ?: params["destination"] as? String
            ?: return ToolResult.Error("Не указан целевой путь")
        
        val content = fileManager.readFile(source) ?: return ToolResult.Error("Файл не найден: $source")
        val targetPath = if (target.endsWith("/") || !target.contains(".")) {
            "${target.trimEnd('/')}/${java.io.File(source).name}"
        } else target
        
        if (!fileManager.writeFile(targetPath, content, append = false))
            return ToolResult.Error("Не удалось записать: $targetPath")
        if (!fileManager.delete(source, recursive = false))
            return ToolResult.Error("Скопирован, но не удалён: $source")
        
        return ToolResult.Success(output = "Файл перемещён: $source → $targetPath",
            data = mapOf("source" to source, "target" to targetPath, "goal_achieved" to true))
    }
    
    private fun executeRename(params: Map<String, Any>): ToolResult {
        val path = params["path"] as? String ?: return ToolResult.Error("Не указан путь к файлу")
        val newName = params["name"] as? String ?: params["new_name"] as? String
            ?: return ToolResult.Error("Не указано новое имя")
        
        val parent = java.io.File(path).parent ?: ""
        val targetPath = if (parent.isNotEmpty()) "$parent/$newName" else newName
        
        val content = fileManager.readFile(path) ?: return ToolResult.Error("Файл не найден: $path")
        if (!fileManager.writeFile(targetPath, content, append = false))
            return ToolResult.Error("Не удалось создать файл: $targetPath")
        if (!fileManager.delete(path, recursive = false))
            return ToolResult.Error("Не удалось удалить исходный файл: $path")
        
        return ToolResult.Success(output = "Файл переименован: $path → $targetPath",
            data = mapOf("old_path" to path, "new_path" to targetPath, "goal_achieved" to true))
    }
    
    private fun executeCopy(params: Map<String, Any>): ToolResult {
        val source = params["source"] as? String ?: params["path"] as? String
            ?: return ToolResult.Error("Не указан исходный файл")
        val target = params["target"] as? String ?: params["destination"] as? String
            ?: return ToolResult.Error("Не указан целевой путь")
        
        val content = fileManager.readFile(source) ?: return ToolResult.Error("Файл не найден: $source")
        val targetPath = if (target.endsWith("/") || !target.contains(".")) {
            "${target.trimEnd('/')}/${java.io.File(source).name}"
        } else target
        
        if (!fileManager.writeFile(targetPath, content, append = false))
            return ToolResult.Error("Не удалось скопировать: $targetPath")
        
        return ToolResult.Success(output = "Файл скопирован: $source → $targetPath",
            data = mapOf("source" to source, "target" to targetPath, "goal_achieved" to true))
    }
    
    private fun executeGetFileInfo(params: Map<String, Any>): ToolResult {
        val path = getStringParam(params, "path")
        val file = java.io.File(fileManager.workspaceRoot, path)
        if (!file.exists()) return ToolResult.Error("Файл не найден: $path")
        
        val info = mapOf(
            "path" to path,
            "exists" to file.exists(),
            "is_directory" to file.isDirectory,
            "size_bytes" to file.length(),
            "last_modified" to file.lastModified(),
            "name" to file.name
        )
        return ToolResult.Success(output = "Информация о файле '$path': ${file.length()} байт, изменён ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))}",
            data = info + mapOf("goal_achieved" to true))
    }
    
    private fun executeEditFile(params: Map<String, Any>): ToolResult {
        val path = getStringParam(params, "path")
        val instruction = params["instruction"] as? String ?: params["content"] as? String
            ?: return ToolResult.Error("Не указана инструкция для редактирования")
        
        val content = fileManager.readFile(path) ?: return ToolResult.Error("Файл не найден: $path")
        val lines = content.lines().toMutableList()
        
        when {
            instruction.contains("добав") || instruction.contains("append") -> {
                val text = instruction.replace(Regex("добав.*?[:\\n]", setOf(RegexOption.IGNORE_CASE)), "").trim()
                lines.add(text)
            }
            instruction.contains("удали") || instruction.contains("delete") || instruction.contains("remove") -> {
                val lineNum = Regex("""\d+""").find(instruction)?.value?.toIntOrNull()
                if (lineNum != null && lineNum > 0 && lineNum <= lines.size) {
                    lines.removeAt(lineNum - 1)
                }
            }
            else -> {
                // По умолчанию: заменить содержимое
                lines.clear()
                lines.add(instruction)
            }
        }
        
        val newContent = lines.joinToString("\n")
        if (!fileManager.writeFile(path, newContent, append = false))
            return ToolResult.Error("Не удалось записать файл: $path")
        
        return ToolResult.Success(output = "Файл отредактирован: $path",
            data = mapOf("path" to path, "size" to newContent.length, "goal_achieved" to true))
    }
    
    private fun executeReadMultiple(params: Map<String, Any>): ToolResult {
        val paths = params["paths"] as? List<*>
            ?: params["files"] as? List<*>
            ?: (params["paths"] as? org.json.JSONArray)?.let { arr -> (0 until arr.length()).map { arr.getString(it).trim() } }
            ?: (params["files"] as? org.json.JSONArray)?.let { arr -> (0 until arr.length()).map { arr.getString(it).trim() } }
            ?: (params["paths"] as? String)?.let { parsePathsString(it) }
            ?: (params["files"] as? String)?.let { parsePathsString(it) }
            ?: return ToolResult.Error("Не указан список путей (paths)")
        
        val results = mutableMapOf<String, Any>()
        val errors = mutableListOf<String>()
        
        for (p in paths) {
            val path = p.toString().replace("\\", "/")
            
            // Ищем файл через listFiles — гарантирует совпадение путей
            val parentDir = if (path.contains("/")) path.substringBeforeLast("/") else ""
            val fileName = path.substringAfterLast("/")
            val dirFiles = fileManager.listFiles(parentDir, recursive = false)
            val match = dirFiles.firstOrNull { 
                it.name.equals(fileName, ignoreCase = true) || it.path.endsWith("/$fileName") || it.path == path
            }
            
            val resolvedPath = match?.path ?: path
            val content = fileManager.readFile(resolvedPath)
            
            if (content != null) {
                results[path] = mapOf<String, Any>(
                    "content" to content,
                    "size" to (match?.size ?: 0L),
                    "exists" to true
                )
            } else {
                errors.add("$path: файл не найден")
            }
        }
        
        val output = buildString {
            appendLine("📂 Прочитано файлов: ${results.size}")
            if (errors.isNotEmpty()) {
                appendLine("⚠️ Ошибок: ${errors.size}")
                errors.take(5).forEach { appendLine("  - $it") }
            }
            appendLine()
            results.forEach { (path, data) ->
                @Suppress("UNCHECKED_CAST")
                val info = data as Map<String, Any>
                val content = info["content"] as? String ?: ""
                appendLine("\n--- $path (${content.length} символов) ---")
                appendLine(content.take(1000))
                if (content.length > 1000) appendLine("...(показано 1000 из ${content.length})")
            }
        }
        
        return ToolResult.Success(
            output = output,
            data = mapOf(
                "command" to "read_multiple",
                "files" to results,
                "total" to results.size,
                "errors" to errors.size,
                "goal_achieved" to true
            )
        )
    }
    
    private fun executeCreateReport(params: Map<String, Any>): ToolResult {
        val content = getStringParam(params, "content")
        val format = params["format"] as? String ?: "md"
        val category = params["category"] as? String ?: "analysis"
        
        return try {
            val path = fileManager.createReport(content, format, category)
            return ToolResult.Success(
                output = "Отчёт создан: $path",
                data = mapOf(
                    "path" to path,
                    "format" to format,
                    "category" to category,
                    "size_bytes" to content.length,
                    "goal_achieved" to true
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("Не удалось создать отчёт: ${e.message}")
        }
    }
}

// Расширение для преобразования FileInfo в Map
private fun FileInfo.toMap(): Map<String, Any> {
    return mapOf(
        "path" to path,
        "name" to name,
        "size_bytes" to size,
        "size_formatted" to sizeFormatted,
        "is_directory" to isDirectory,
        "last_modified" to lastModified,
        "last_modified_formatted" to lastModifiedFormatted
    )
}
    private fun parsePathsString(input: String): List<String>? {
        // JSON array: ["path1", "path2"] or [path1, path2]
        val jsonMatch = Regex("""\[(.*?)\]""").find(input.trim())
        if (jsonMatch != null) {
            val inner = jsonMatch.groupValues[1]
            return inner.split(",").map { 
                it.trim().removeSurrounding("\"").removeSurrounding("'")
            }.filter { it.isNotBlank() }.ifEmpty { null }
        }
        // Comma-separated: path1, path2
        val parts = input.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return parts.ifEmpty { null }
    }
