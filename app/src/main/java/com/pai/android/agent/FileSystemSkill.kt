package com.pai.android.agent

import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.Message
import java.io.File
import kotlin.text.Regex
import kotlin.text.RegexOption

/**
 * Навык работы с файловой системой.
 * Выполняет операции с файлами и возвращает результат в чат.
 */
class FileSystemSkill(
    private val fileManager: FileManager,
    private val aiRepository: AiRepository
) : Skill {
    
    override val name: String = "file_system"
    
    override val description: String = "File operations: create, read, write, delete, search files and folders"
    
    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        return intent == Intent.FILE_OPERATION || intent == Intent.COMMAND
    }
    
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        return try {
            val command = (params["command"] as? String) ?: return SkillResult.Error(
                message = "Не указана команда для файловой операции",
                details = "Доступные команды: list_files, read_file, write_file, append_file, create_folder, delete"
            )
            
            println("📁 FileSystemSkill: выполняю команду '$command'")
            
            when (command) {
                "list_files", "search" -> executeListFiles(params)
                "read_file" -> executeReadFile(params)
                "write_file" -> executeWriteFile(params)
                "append_file" -> executeAppendFile(params)
                "create_folder" -> executeCreateFolder(params)
                "delete" -> executeDelete(params)
                "move", "перемести" -> executeMove(params)
                "rename", "переименуй" -> executeMove(mapOf(
                    "source" to (params["path"] ?: ""),
                    "target" to (params["new_name"] ?: params["name"] ?: "")
                ))
                "copy", "копируй" -> executeCopy(params)
                "create_report" -> executeCreateReport(params)
                "get_file_info", "file_info" -> executeGetFileInfo(params)
                "edit_file", "format_file" -> executeEditFile(params)
                else -> SkillResult.Error(
                    message = "Неизвестная команда: $command",
                    details = "Доступные команды: list_files, read_file, write_file, append_file, create_folder, delete, move, get_file_info, edit_file"
                )
            }
        } catch (e: Exception) {
            println("❌ FileSystemSkill ошибка: ${e.message}")
            SkillResult.Error(
                message = "Ошибка при выполнении файловой операции",
                details = e.message ?: "Неизвестная ошибка"
            )
        }
    }
    
    /**
     * Список файлов в папке.
     */
    private fun executeListFiles(params: Map<String, Any>): SkillResult {
        val path = params["path"] as? String ?: ""
        val recursive = when (val recursiveParam = params["recursive"]) {
            is Boolean -> recursiveParam
            is String -> recursiveParam.toBoolean()
            else -> false
        }
        val filterExtension = params["filter_extension"] as? String
        val treeFormat = when (val treeParam = params["tree"]) {
            is Boolean -> treeParam
            is String -> treeParam.toBoolean()
            else -> false
        }
        
        // Для древовидного формата всегда используем рекурсивный поиск
        val actualRecursive = if (treeFormat) true else recursive
        
        // Determine search mode:
        // 1. path like "*.md" → glob by extension
        // 2. path like "index.html" → search by filename
        // 3. path like "." or "" → list root
        // 4. path like "subdir/" → list subdirectory
        val isGlob = path.contains("*") || path.contains("?")
        val isFileSearch = !isGlob && path.contains(".") && path.indexOf(".") > 0 && !path.endsWith("/")
        val isRoot = path == "." || path == "./" || path.isBlank()
        
        val fileName = if (isFileSearch) path else null
        val searchPath = if (isFileSearch || isRoot || isGlob) "" else path
        
        // Handle glob: *.ext → set filterExtension
        val effectiveFilterExt = when {
            filterExtension != null -> filterExtension
            isGlob && path.startsWith("*.") -> path.substring(2)
            else -> null
        }
        
        val files = if (searchPath.isNotEmpty()) {
            fileManager.listFiles(searchPath, actualRecursive)
        } else {
            fileManager.listFiles("", actualRecursive)
        }
        
        if (files.isEmpty()) {
            return SkillResult.Success(
                message = "📂 В папке '${searchPath.ifEmpty { "корень" }}' файлы не найдены",
                responseType = ResponseType.TEXT
            )
        }
        
        var fileList = if (effectiveFilterExt != null && effectiveFilterExt.isNotEmpty()) {
            val ext = effectiveFilterExt.removePrefix(".")
            files.filter { it.name.endsWith(".$ext", ignoreCase = true) }
        } else {
            files
        }
        
        // Если ищем конкретный файл по имени
        if (fileName != null) {
            fileList = fileList.filter { 
                it.name.equals(fileName, ignoreCase = true) || 
                it.path.equals(fileName, ignoreCase = true) ||
                it.path.endsWith("/$fileName", ignoreCase = true)
            }
        }
        
        if (fileList.isEmpty()) {
            val notFoundMsg = if (fileName != null) {
                "📂 Файл '$fileName' не найден"
            } else if (effectiveFilterExt != null) {
                "📂 Файлы с расширением '.$effectiveFilterExt' не найдены"
            } else {
                "📂 В папке '${searchPath.ifEmpty { "корень" }}' файлы не найдены"
            }
            return SkillResult.Success(
                message = notFoundMsg,
                responseType = ResponseType.TEXT
            )
        }
        
        // Древовидный формат
        if (treeFormat) {
            val rootName = if (path.isNotEmpty()) path else "workspace"
            val tree = formatAsTree(fileList, rootName)
            return SkillResult.Success(
                message = tree,
                data = mapOf("files" to fileList.map { it.path }, "tree" to true),
                responseType = ResponseType.RICH_TEXT
            )
        }
        
        // Обычный список
        val builder = StringBuilder()
        builder.append("📂 **Найдено ${fileList.size} файлов:**\n\n")
        
        fileList.forEachIndexed { index, fileInfo ->
            val icon = if (fileInfo.isDirectory) "📁" else "📄"
            val fileSize = if (!fileInfo.isDirectory) " (${fileInfo.sizeFormatted})" else ""
            
            // Показываем относительный путь, а не только имя
            val displayPath = if (fileInfo.path.contains("/")) {
                // Файл в подпапке, показываем путь
                fileInfo.path
            } else {
                fileInfo.name
            }
            
            builder.append("${index + 1}. $icon $displayPath$fileSize\n")
        }
        
        // Если recursive=true и есть подпапки, добавляем пояснение
        if (actualRecursive && fileList.any { it.isDirectory }) {
            builder.append("\n📁 *Папки отображаются вместе с файлами (рекурсивный поиск)*")
        }
        
        return SkillResult.Success(
            message = builder.toString(),
            data = mapOf("files" to fileList.map { it.path }),
            responseType = ResponseType.LIST
        )
    }
    
    /**
     * Форматирует список файлов в древовидную структуру.
     */
    private fun formatAsTree(files: List<FileInfo>, rootName: String = "workspace"): String {
        // Строим дерево
        val root = mutableMapOf<String, Any?>()
        
        for (file in files) {
            val path = file.path
            val parts = path.split("/").filter { it.isNotEmpty() }
            
            if (parts.isEmpty()) continue // Корневой элемент (сам workspace)
            
            var current = root
            for (i in parts.indices) {
                val part = parts[i]
                val isLast = i == parts.lastIndex
                
                if (isLast) {
                    // Это конечный элемент (файл или папка из списка)
                    if (file.isDirectory) {
                        // Для директории создаём пустой узел
                        if (!current.containsKey(part) || current[part] !is MutableMap<*, *>) {
                            current[part] = mutableMapOf<String, Any?>()
                        }
                    } else {
                        // Файл
                        current[part] = file
                    }
                } else {
                    // Это промежуточная папка
                    val existing = current[part]
                    when (existing) {
                        null -> {
                            // Создаём новую папку
                            current[part] = mutableMapOf<String, Any?>()
                        }
                        is MutableMap<*, *> -> {
                            // Уже есть папка, продолжаем
                        }
                        is FileInfo -> {
                            if (existing.isDirectory) {
                                // Заменяем директорию на Map узел
                                current[part] = mutableMapOf<String, Any?>()
                            } else {
                                // Существует файл с таким именем, создаём папку и помещаем файл внутрь
                                val newMap: MutableMap<String, Any?> = mutableMapOf()
                                newMap["_self"] = existing
                                current[part] = newMap
                            }
                        }
                        else -> {
                            // Неожиданный тип, создаём папку и помещаем значение внутрь
                            val newMap: MutableMap<String, Any?> = mutableMapOf()
                            newMap["_self"] = existing
                            current[part] = newMap
                        }
                    }
                    
                    // Переходим в дочерний узел
                    val next = current[part]
                    if (next is MutableMap<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        current = next as MutableMap<String, Any?>
                    } else {
                        // Не должно случиться
                        val newMap: MutableMap<String, Any?> = mutableMapOf()
                        newMap["_self"] = next
                        current[part] = newMap
                        current = newMap
                    }
                }
            }
        }
        
        // Рекурсивно генерируем дерево
        val result = StringBuilder()
        result.append("📁 **$rootName**\n")
        
        fun buildTree(
            node: Map<String, Any?>,
            prefix: String = "",
            isLast: Boolean = true
        ) {
            // Фильтруем служебные ключи и сортируем
            val entries = node.entries
                .filter { !it.key.startsWith("_") } // Игнорируем _self, _file и т.д.
                .sortedWith(compareBy<Map.Entry<String, Any?>>(
                    { entry ->
                        val v = entry.value
                        !(v is Map<*, *> || (v is FileInfo && v.isDirectory))
                    }, // Папки сначала
                    { entry -> entry.key.lowercase() }
                ))
            
            val size = entries.size
            
            entries.forEachIndexed { index, (name, value) ->
                val isLastEntry = index == size - 1
                val connector = if (isLast) "└── " else "├── "
                val newPrefix = if (isLast) "    " else "│   "
                val item = value // Локальная переменная для smart cast
                
                when (item) {
                    is FileInfo -> {
                        val icon = if (item.isDirectory) "📁" else "📄"
                        val sizeStr = if (!item.isDirectory) " (${item.sizeFormatted})" else ""
                        val comment = if (item.path.endsWith(".md")) " # ${getFileComment(item)}" else ""
                        result.append("$prefix$connector$icon $name$sizeStr$comment\n")
                        
                        // Если это директория, но у неё нет дочерних элементов в Map,
                        // всё равно показываем её как папку (уже показали)
                    }
                    is Map<*, *> -> {
                        val icon = "📁"
                        result.append("$prefix$connector$icon $name/\n")
                        @Suppress("UNCHECKED_CAST")
                        buildTree(
                            item as Map<String, Any?>,
                            prefix + newPrefix,
                            isLastEntry
                        )
                    }
                }
            }
        }
        
        buildTree(root)
        return result.toString()
    }
    
    /**
     * Возвращает комментарий для файла (первая строка или описание).
     */
    private fun getFileComment(file: FileInfo): String {
        if (!file.path.endsWith(".md")) return ""
        try {
            val content = fileManager.readFile(file.path)
            if (content != null) {
                val firstLine = content.lines().firstOrNull { it.isNotBlank() }
                if (firstLine != null) {
                    // Убираем Markdown заголовки
                    return firstLine.trim().removePrefix("#").trim()
                }
            }
        } catch (e: Exception) {
            // Игнорируем ошибки чтения
        }
        return ""
    }
    
    /**
     * Чтение файла.
     */
    private fun executeReadFile(params: Map<String, Any>): SkillResult {
        val path = params["path"] as? String
            ?: return SkillResult.Error(message = "Не указан путь к файлу")
        
        var content = fileManager.readFile(path)
        
        // Если файл не найден по точному пути — ищем рекурсивно по имени
        if (content == null) {
            val fileName = path.substringAfterLast('/').substringAfterLast('\\')
            if (fileName.isNotBlank()) {
                val allFiles = fileManager.listFiles("", recursive = true)
                val found = allFiles.firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
                if (found != null && found.path != path) {
                    println("📁 FileSystemSkill: файл '$path' найден рекурсивно как '${found.path}'")
                    content = fileManager.readFile(found.path)
                    if (content != null) {
                        return buildReadFileResult(found.path, content)
                    }
                }
            }
        }
        
        return if (content != null) {
            buildReadFileResult(path, content)
        } else {
            SkillResult.Error(message = "Файл '$path' не найден или не может быть прочитан")
        }
    }
    
    private fun buildReadFileResult(path: String, content: String): SkillResult {
        val preview = if (content.length > 500) {
            content.take(500) + "\n\n... (${content.length - 500} символов ещё)"
        } else {
            content
        }
        
        return SkillResult.Success(
            message = "📄 **Содержимое файла '$path':**\n\n```\n$preview\n```",
            data = mapOf("content" to content),
            responseType = ResponseType.RICH_TEXT
        )
    }
    
    /**
     * Запись в файл (создание или перезапись).
     */
    private fun executeWriteFile(params: Map<String, Any>): SkillResult {
        val path = params["path"] as? String
            ?: return SkillResult.Error(message = "Не указан путь к файлу")
        val content = params["content"] as? String ?: ""
        
        // Ищем существующий файл (сначала по точному пути, потом рекурсивно)
        val targetPath = findExistingFile(path)
        val finalPath = targetPath ?: path
        
        val success = fileManager.writeFile(finalPath, content, append = false)
        
        return if (success) {
            if (targetPath != null) {
                SkillResult.Success(
                    message = "✅ **Файл обновлён:** `$finalPath` (${content.length} символов)",
                    data = mapOf("path" to finalPath, "size" to content.length),
                    responseType = ResponseType.TEXT
                )
            } else {
                SkillResult.Success(
                    message = "✅ **Файл создан:** `$finalPath` (${content.length} символов)",
                    data = mapOf("path" to finalPath, "size" to content.length),
                    responseType = ResponseType.TEXT
                )
            }
        } else {
            SkillResult.Error(message = "Не удалось создать/обновить файл '$finalPath'")
        }
    }
    
    /**
     * Добавление в файл с AI-форматированием для соблюдения структуры.
     */
    private suspend fun executeAppendFile(params: Map<String, Any>): SkillResult {
        val path = params["path"] as? String
            ?: return SkillResult.Error(message = "Не указан путь к файлу")
        val content = params["content"] as? String ?: ""
        
        // 1. Проверяем прямой путь
        var targetPath: String? = null
        if (fileManager.getFileInfo(path) != null) {
            targetPath = path
        } else {
            // 2. Если файл не найден — ищем рекурсивно по имени
            val found = findExistingFiles(path)
            when {
                found.isEmpty() -> {
                    targetPath = path
                }
                found.size == 1 -> {
                    targetPath = found.first()
                    println("📁 executeAppendFile: найден существующий файл: '$targetPath'")
                }
                else -> {
                    val paths = found.joinToString("\n") { "  `$it`" }
                    return SkillResult.ConfirmationRequired(
                        question = "Найдено несколько файлов с именем '$path':\n$paths\n\nВ какой файл добавить запись? Укажите полный путь.",
                        params = mapOf("files" to found, "content" to content)
                    )
                }
            }
        }
        
        // 3. Читаем существующее содержимое и форматируем через AI
        val existingContent = fileManager.readFile(targetPath!!)
        val formattedContent = if (existingContent != null) {
            formatAppendContentWithAI(existingContent, content)
        } else {
            content  // Новый файл — просто запись
        }
        
        val success = if (existingContent != null) {
            val cleanExisting = if (existingContent.endsWith("\n")) existingContent else "$existingContent\n"
            fileManager.writeFile(targetPath!!, "$cleanExisting$formattedContent", append = false)
        } else {
            fileManager.writeFile(targetPath!!, formattedContent, append = false)
        }
        
        return if (success) {
            SkillResult.Success(
                message = "✅ **Запись добавлена** в файл `$targetPath`:\n> $formattedContent",
                data = mapOf("path" to targetPath, "added" to formattedContent),
                responseType = ResponseType.TEXT
            )
        } else {
            SkillResult.Error(message = "Не удалось добавить запись в файл '$targetPath'")
        }
    }
    
    /**
     * Форматирует добавляемый контент через AI с учётом структуры существующего файла.
     * AI анализирует файл и добавляет новую запись в правильном формате.
     * При ошибке AI — fallback на эвристики.
     */
    private suspend fun formatAppendContentWithAI(existingContent: String, newContent: String): String {
        return try {
            val prompt = """
                Анализируй структуру файла и добавь новую запись в том же стиле.
                
                Текущее содержимое файла:
                ```
                $existingContent
                ```
                
                Новая запись для добавления: $newContent
                
                Верни ТОЛЬКО текст новой записи (без даты, без номера в начале, если в файле не используется нумерация).
                - Если файл содержит нумерованный список — добавь следующую строку с правильным номером.
                - Если маркированный список — с тем же маркером.
                - Если список отсортирован (даты, алфавит) — вставь в правильное место.
                - Если таблица — добавь строку в таблицу.
                - Сохрани регистр, форматирование, пунктуацию как в существующем файле.
                Не добавляй номер строки, не оборачивай в ``` и не добавляй пояснений.
            """.trimIndent()
            
            val response = aiRepository.sendMessage(
                messages = listOf(
                    Message.createSystemMessage("file_edit", "Ты умный редактор. Возвращай только отформатированный текст для добавления в файл."),
                    Message.createUserMessage("file_edit", prompt)
                ),
                systemPrompt = "Возвращай только текст для добавления.",
                memoryContext = ""
            )
            
            if (response.isSuccess && response.getOrThrow().text.isNotBlank()) {
                val aiText = response.getOrThrow().text.trim()
                println("📁 AI-форматирование: '${aiText.take(80)}'")
                aiText
            } else {
                fallbackFormatContent(existingContent, newContent)
            }
        } catch (e: Exception) {
            println("⚠️ AI-форматирование не удалось (${e.message}), fallback на эвристики")
            fallbackFormatContent(existingContent, newContent)
        }
    }
    
    /**
     * Fallback-форматирование на основе эвристик (когда AI недоступен).
     */
    private fun fallbackFormatContent(existingContent: String, newContent: String): String {
        val lines = existingContent.trimEnd().split("\n")
        val lastLine = lines.lastOrNull { it.isNotBlank() } ?: return newContent
        
        // Нумерованный список: строка вида "N. текст" или "N) текст"
        val numberedRegex = Regex("""^\s*(\d+)[.)]\s+.*""")
        val numberedMatch = numberedRegex.find(lastLine)
        if (numberedMatch != null) {
            val lastNumber = numberedMatch.groupValues[1].toInt()
            return "${lastNumber + 1}. $newContent"
        }
        
        // Маркированный список: строка вида "- текст", "* текст", "+ текст"
        val bulletRegex = Regex("""^\s*([-*+])\s+.*""")
        val bulletMatch = bulletRegex.find(lastLine)
        if (bulletMatch != null) {
            return "${bulletMatch.groupValues[1]} $newContent"
        }
        
        return newContent
    }
    
    /**
     * Ищет существующий файл в workspace по точному пути или по имени рекурсивно.
     * @return путь к найденному файлу или null, если файл не существует
     */
    private fun findExistingFile(path: String): String? {
        if (fileManager.getFileInfo(path) != null) return path
        
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank()) return null
        
        val allFiles = fileManager.listFiles("", recursive = true)
        val found = allFiles.firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
        return found?.path
    }
    
    /**
     * Ищет ВСЕ существующие файлы с указанным именем в workspace рекурсивно.
     * @return список путей к найденным файлам
     */
    private fun findExistingFiles(path: String): List<String> {
        if (fileManager.getFileInfo(path) != null) return listOf(path)
        
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank()) return emptyList()
        
        val allFiles = fileManager.listFiles("", recursive = true)
        return allFiles.filter { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
            .map { it.path }
    }
    
    /**
     * Создание папки.
     */
    private fun executeCreateFolder(params: Map<String, Any>): SkillResult {
        val path = params["path"] as? String
            ?: return SkillResult.Error(message = "Не указан путь к папке")
        
        val folder = fileManager.createDirectory(path)
        
        // Проверяем, существует ли уже папка (createDirectory возвращает null если папка уже есть)
        val exists = java.io.File(fileManager.workspaceRoot, path).exists()
        
        return if (folder != null || exists) {
            SkillResult.Success(
                message = "✅ **Папка готова:** `$path`",
                data = mapOf("path" to (folder?.absolutePath ?: path)),
                responseType = ResponseType.TEXT
            )
        } else {
            SkillResult.Error(message = "Не удалось создать папку '$path'")
        }
    }
    
    /**
     * Удаление файла или папки.
     */
    private fun executeDelete(params: Map<String, Any>): SkillResult {
        val path = params["path"] as? String
            ?: return SkillResult.Error(message = "Не указан путь к файлу или папке")
        
        val recursive = when (val recursiveParam = params["recursive"]) {
            is Boolean -> recursiveParam
            is String -> recursiveParam.toBoolean()
            else -> false
        }
        
        // Если путь пустой — ошибка (предотвращает случайное удаление всех файлов)
        if (path.isBlank()) {
            return SkillResult.Error(message = "Путь не может быть пустым")
        }
        
        // Очищаем путь от wildcard'ов (*), которые AI иногда добавляет
        val cleanPath = path.replace("*", "").trim()
        if (cleanPath.isBlank()) {
            return SkillResult.Error(message = "После удаления wildcard'ов путь пуст. Укажите конкретное имя файла.")
        }
        
        // Если recursive=true и путь не содержит '/' (просто имя файла), ищем файл рекурсивно
        if (recursive && !cleanPath.contains("/") && !cleanPath.contains("\\")) {
            println("🔍 Ищу файл '$cleanPath' рекурсивно...")
            
            // Парсим исключения (папки, которые НЕ трогать)
            val excludePaths = parseExcludeParam(params["exclude"])
            if (excludePaths.isNotEmpty()) println("🔍 Исключаю папки: $excludePaths")
            
            val allFiles = fileManager.listFiles("", recursive = true)
            var matchingFiles = allFiles.filter { !it.isDirectory && cleanPath.isNotBlank() && it.name.contains(cleanPath, ignoreCase = true) }
            
            // Фильтруем исключённые папки
            if (excludePaths.isNotEmpty()) {
                matchingFiles = matchingFiles.filter { fileInfo ->
                    !excludePaths.any { exclude ->
                        fileInfo.path.startsWith(exclude) || fileInfo.path.startsWith("/$exclude") || 
                        fileInfo.path.startsWith("$exclude/") || fileInfo.path.contains("/$exclude/")
                    }
                }
                println("🔍 После фильтрации исключений: ${matchingFiles.size} файлов")
            }
            
            if (matchingFiles.isEmpty()) {
                return SkillResult.Error(
                    message = "Файлы с именем, содержащим '$path', не найдены",
                    details = "Проверьте название файла или попробуйте другой поиск"
                )
            }
            
            // Удаляем все найденные файлы (на случай дубликатов)
            var deletedCount = 0
            val deletedPaths = mutableListOf<String>()
            
            for (fileInfo in matchingFiles) {
                if (fileManager.delete(fileInfo.path)) {
                    deletedCount++
                    deletedPaths.add(fileInfo.path)
                }
            }
            
            return if (deletedCount > 0) {
                val pathsList = deletedPaths.joinToString("\n- ") { "`$it`" }
                SkillResult.Success(
                    message = "✅ **Удалено $deletedCount файлов:**\n\n- $pathsList",
                    data = mapOf("deleted" to deletedPaths),
                    responseType = ResponseType.TEXT
                )
            } else {
                SkillResult.Error(message = "Найдены файлы '$path', но не удалось их удалить")
            }
        }
        
        // Обычное удаление по пути
        val success = fileManager.delete(path, recursive = recursive)
        
        return if (success) {
            SkillResult.Success(
                message = "✅ **Удалено:** `$path`",
                data = mapOf("path" to path),
                responseType = ResponseType.TEXT
            )
        } else {
            SkillResult.Error(message = "Не удалось удалить '$path'")
        }
    }
    
    /**
     * Перемещение файлов.
     */
    private fun executeMove(params: Map<String, Any>): SkillResult {
        val source = params["source"] as? String
            ?: params["path"] as? String
            ?: return SkillResult.Error(message = "Не указан исходный файл или путь")
        
        val target = params["target"] as? String
            ?: params["destination"] as? String
            ?: params["to"] as? String
            ?: return SkillResult.Error(message = "Не указан целевой файл или папка")
        
        val overwrite = when (val overwriteParam = params["overwrite"]) {
            is Boolean -> overwriteParam
            is String -> overwriteParam.toBoolean()
            else -> false
        }
        val recursive = when (val recursiveParam = params["recursive"]) {
            is Boolean -> recursiveParam
            is String -> recursiveParam.toBoolean()
            else -> false
        }
        val filterExtension = params["filter_extension"] as? String
        
        // Нормализуем target: убираем начальный / если есть
        val normalizedTarget = if (target.startsWith("/")) target.substring(1) else target
        
        println("📁 Workspace root: ${fileManager.workspaceRoot.absolutePath}")
        println("📁 Target: '$target', normalized: '$normalizedTarget'")
        
        // Определяем, является ли target папкой (оканчивается на / или не содержит . в последнем сегменте)
        val lastSegment = normalizedTarget.trimEnd('/').substringAfterLast('/')
        val isTargetDirectory = normalizedTarget.endsWith("/") || !lastSegment.contains(".")
        println("📁 isTargetDirectory: $isTargetDirectory")
        
        // Создаём целевую папку, если это директория и её нет
        if (isTargetDirectory) {
            val targetDir = File(fileManager.workspaceRoot, normalizedTarget)
            if (!targetDir.exists()) {
                println("📁 Создаю папку: $normalizedTarget")
                targetDir.mkdirs()
            }
        }
        
        var movedCount = 0
        val movedFiles = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        // Обработка wildcard (*.txt, *.md и т.д.)
        val filesToMove = if (source.contains("*")) {
            println("🔍 Wildcard обнаружен: '$source', recursive=$recursive, filterExtension=$filterExtension")
            
            // Преобразуем wildcard в regex pattern
            val pattern = source.replace("*", ".*")
            val regex = try {
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                println("❌ Ошибка создания regex из шаблона '$pattern': ${e.message}")
                return SkillResult.Error(
                    message = "Некорректный шаблон файлов: '$source'",
                    details = e.message
                )
            }
            
            // Ищем файлы по всему workspace
            val allFiles = fileManager.listFiles("", recursive = recursive)
            println("🔍 Всего файлов в workspace: ${allFiles.size}")
            
            // Фильтруем по regex и расширению (если указано)
            val matchingFiles = allFiles.filter { fileInfo ->
                if (fileInfo.isDirectory) return@filter false
                
                val matchesRegex = regex.matches(fileInfo.name)
                val matchesExtension = filterExtension?.let { ext ->
                    fileInfo.name.endsWith(".$ext", ignoreCase = true)
                } ?: true
                
                matchesRegex && matchesExtension
            }
            
            println("🔍 Найдено файлов по шаблону: ${matchingFiles.size}")
            matchingFiles.map { it.path }
        } else {
            // Поддержка нескольких файлов через ;
            if (source.contains(";")) {
                source.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                listOf(source)
            }
        }
        
        println("🔍 Всего файлов для перемещения: ${filesToMove.size}")
        
        // Получаем все файлы рекурсивно для поиска по имени
        val allFilesRecursive = fileManager.listFiles("", recursive = true)
        println("🔍 Всего файлов в workspace (рекурсивно): ${allFilesRecursive.size}")
        
        for (sourceFile in filesToMove) {
            var actualSourcePath = sourceFile
            var foundFiles = listOf<com.pai.android.agent.FileInfo>()
            
            // Проверяем, существует ли файл по указанному пути
            val sourceFullPath = File(fileManager.workspaceRoot, sourceFile)
            if (!sourceFullPath.exists()) {
                // Ищем файл по имени во всём workspace
                val fileName = File(sourceFile).name
                println("🔍 Файл '$sourceFile' не найден по прямому пути. Ищу по имени '$fileName'")
                
                foundFiles = allFilesRecursive.filter { !it.isDirectory && it.name == fileName }
                println("🔍 Найдено файлов с именем '$fileName': ${foundFiles.size}")
                
                if (foundFiles.isEmpty()) {
                    println("❌ Файл '$sourceFile' не существует")
                    errors.add("Файл '$sourceFile' не существует")
                    continue
                }
                
                // Используем первый найденный файл (или все? пока используем первый)
                actualSourcePath = foundFiles.first().path
                println("🔍 Использую путь: '$actualSourcePath'")
            }
            
            // Определяем целевой путь для каждого файла
            val targetPath = if (isTargetDirectory) {
                // Если target - папка, сохраняем имя файла
                val fileName = File(actualSourcePath).name
                if (normalizedTarget.isEmpty()) fileName else "$normalizedTarget/$fileName"
            } else {
                // Если target - полный путь к файлу
                normalizedTarget
            }
            
            println("🔍 Перемещаю: '$actualSourcePath' → '$targetPath'")
            println("📁 Абсолютный путь к исходному файлу: ${File(fileManager.workspaceRoot, actualSourcePath).absolutePath}")
            println("📁 Абсолютный путь к целевому файлу: ${File(fileManager.workspaceRoot, targetPath).absolutePath}")
            
            // Перемещаем файл
            val success = fileManager.move(actualSourcePath, targetPath, overwrite)
            
            if (success) {
                movedCount++
                movedFiles.add("`$actualSourcePath` → `$targetPath`")
            } else {
                errors.add("Не удалось переместить '$actualSourcePath' в '$targetPath'")
            }
        }
        
        return when {
            movedCount > 0 && errors.isEmpty() -> {
                val filesList = movedFiles.joinToString("\n") { "  - $it" }
                SkillResult.Success(
                    message = "📂 **Перемещено $movedCount файлов:**\n\n$filesList",
                    data = mapOf("moved" to movedFiles),
                    responseType = ResponseType.TEXT
                )
            }
            movedCount > 0 && errors.isNotEmpty() -> {
                val filesList = movedFiles.joinToString("\n") { "  - $it" }
                val errorList = errors.joinToString("\n") { "  - $it" }
                SkillResult.Success(
                    message = "📂 **Перемещено $movedCount файлов (с ошибками):**\n\n$filesList\n\n⚠️ **Ошибки:**\n$errorList",
                    data = mapOf("moved" to movedFiles, "errors" to errors),
                    responseType = ResponseType.TEXT
                )
            }
            errors.isNotEmpty() -> {
                val errorList = errors.joinToString("\n") { "  - $it" }
                SkillResult.Error(
                    message = "Не удалось переместить файлы",
                    details = errorList
                )
            }
            else -> {
                SkillResult.Error(
                    message = "Не удалось переместить файлы",
                    details = "Неизвестная ошибка"
                )
            }
        }
    }
    
    private fun executeCopy(params: Map<String, Any>): SkillResult {
        val source = params["source"] as? String ?: params["path"] as? String
            ?: return SkillResult.Error(message = "Не указан исходный файл")
        val target = params["target"] as? String ?: params["destination"] as? String
            ?: return SkillResult.Error(message = "Не указан целевой путь")
        
        val content = fileManager.readFile(source)
        if (content == null) return SkillResult.Error(message = "Файл не найден: $source")
        
        val targetPath = if (target.endsWith("/") || !target.contains(".")) {
            "$target${java.io.File(source).name}"
        } else target
        
        val parentDir = java.io.File(targetPath).parentFile
        if (parentDir != null) {
            java.io.File(fileManager.workspaceRoot, parentDir.path).mkdirs()
        }
        
        return if (fileManager.writeFile(targetPath, content, append = false)) {
            SkillResult.Success(message = "✅ **Скопировано:** `$source` → `$targetPath`",
                data = mapOf("source" to source, "target" to targetPath),
                responseType = ResponseType.TEXT)
        } else {
            SkillResult.Error(message = "Не удалось скопировать файл: $targetPath")
        }
    }
    
    /**
     * Получение информации о файле или папке.
     */
    private fun executeGetFileInfo(params: Map<String, Any>): SkillResult {
        val path = params["path"] as? String ?: return SkillResult.Error(
            message = "Не указан путь к файлу или папке",
            details = "Используйте параметр 'path'"
        )
        
        val attribute = params["attribute"] as? String ?: "all"
        
        val fileInfo = fileManager.getFileInfo(path)
        if (fileInfo == null) {
            return SkillResult.Error(
                message = "Файл или папка '$path' не найдены",
                details = "Проверьте правильность пути"
            )
        }
        
        return when (attribute.lowercase()) {
            "path", "location", "where" -> {
                SkillResult.Success(
                    message = "📁 **Путь к файлу '$path':** `/${fileInfo.path}`",
                    data = mapOf(
                        "path" to fileInfo.path,
                        "name" to fileInfo.name,
                        "full_path" to "/${fileInfo.path}",
                        "is_directory" to fileInfo.isDirectory
                    ),
                    responseType = ResponseType.TEXT
                )
            }
            "creation_time", "created", "created_at", "date" -> {
                // В Android нет времени создания, используем время последнего изменения
                SkillResult.Success(
                    message = "📅 **Папка '$path' создана/изменена:** ${fileInfo.lastModifiedFormatted}",
                    data = mapOf(
                        "path" to path,
                        "last_modified" to fileInfo.lastModified,
                        "last_modified_formatted" to fileInfo.lastModifiedFormatted,
                        "is_directory" to fileInfo.isDirectory,
                        "size" to fileInfo.size,
                        "size_formatted" to fileInfo.sizeFormatted
                    ),
                    responseType = ResponseType.TEXT
                )
            }
            "size" -> {
                SkillResult.Success(
                    message = "📏 **Размер '$path':** ${fileInfo.sizeFormatted}",
                    data = mapOf(
                        "path" to path,
                        "size" to fileInfo.size,
                        "size_formatted" to fileInfo.sizeFormatted,
                        "is_directory" to fileInfo.isDirectory
                    ),
                    responseType = ResponseType.TEXT
                )
            }
            "all", "info" -> {
                val message = buildString {
                    append("📋 **Информация о '${fileInfo.name}':**\n\n")
                    append("📁 **Путь:** `${fileInfo.path}`\n")
                    append("📊 **Тип:** ${if (fileInfo.isDirectory) "Папка" else "Файл"}\n")
                    if (!fileInfo.isDirectory) {
                        append("📏 **Размер:** ${fileInfo.sizeFormatted}\n")
                    }
                    append("📅 **Изменён:** ${fileInfo.lastModifiedFormatted}\n")
                    append("🔧 **Атрибуты:** ${if (fileInfo.isDirectory) "директория" else "файл"}")
                }
                
                SkillResult.Success(
                    message = message,
                    data = mapOf(
                        "path" to fileInfo.path,
                        "name" to fileInfo.name,
                        "size" to fileInfo.size,
                        "size_formatted" to fileInfo.sizeFormatted,
                        "is_directory" to fileInfo.isDirectory,
                        "last_modified" to fileInfo.lastModified,
                        "last_modified_formatted" to fileInfo.lastModifiedFormatted
                    ),
                    responseType = ResponseType.TEXT
                )
            }
            else -> {
                SkillResult.Error(
                    message = "Неизвестный атрибут: '$attribute'",
                    details = "Доступные атрибуты: creation_time, size, path, all"
                )
            }
        }
    }
    
    /**
     * Редактирование файла с помощью AI.
     * Читает файл, отправляет содержимое AI с инструкцией, записывает обработанный текст обратно.
     */
    /**
     * Парсит параметр exclude.
     */
    private fun parseExcludeParam(exclude: Any?): List<String> {
        if (exclude == null) return emptyList()
        return when (exclude) {
            is String -> {
                if (exclude.startsWith("[")) {
                    exclude.removeSurrounding("[", "]").split(",")
                        .map { it.trim().removeSurrounding("\"", "\"").removeSurrounding("'", "'") }
                        .filter { it.isNotBlank() }
                } else {
                    exclude.split(",").map { it.trim() }.filter { it.isNotBlank() }
                }
            }
            is List<*> -> exclude.filterIsInstance<String>().map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private suspend fun executeEditFile(params: Map<String, Any>): SkillResult {
        val path = params["path"] as? String ?: return SkillResult.Error(
            message = "Не указан путь к файлу",
            details = "Используйте параметр 'path'"
        )
        
        val instruction = params["instruction"] as? String ?: params["task"] as? String ?: ""
        
        // 1. Читаем текущее содержимое файла
        val currentContent = fileManager.readFile(path)
        if (currentContent == null) {
            return SkillResult.Error(
                message = "Файл '$path' не найден или не может быть прочитан",
                details = "Проверьте существование файла и права доступа"
            )
        }
        
        if (currentContent.isEmpty()) {
            return SkillResult.Success(
                message = "📄 **Файл '$path' пуст.** Нечего редактировать.",
                responseType = ResponseType.TEXT
            )
        }
        
        // 2. Отправляем AI на обработку
        val prompt = """
            Пользователь просит отредактировать файл. Вот текущее содержимое файла:
            
            ```
            $currentContent
            ```
            
            Инструкция пользователя: $instruction
            
            Отредактируй содержимое файла согласно инструкции. 
            Верни ТОЛЬКО отредактированное содержимое файла, без пояснений, без обрамляющих символов.
            Если инструкция требует форматирования (нумерованный список, сортировка и т.д.) — примени его.
            Сохрани смысл и основное содержание.
        """.trimIndent()
        
        val response = try {
            aiRepository.sendMessage(
                messages = listOf(
                    Message.createSystemMessage("file_edit", "Ты редактор файлов. Редактируй содержимое согласно инструкции."),
                    Message.createUserMessage("file_edit", prompt)
                ),
                systemPrompt = "Ты редактор файлов. Возвращай только отредактированное содержимое.",
                memoryContext = ""
            )
        } catch (e: Exception) {
            return SkillResult.Error(
                message = "Ошибка при обращении к AI для редактирования файла",
                details = "AI временно недоступен: ${e.message}"
            )
        }
        
        val editedContent = response.getOrThrow().text.trim()
        
        if (editedContent.isEmpty()) {
            return SkillResult.Error(
                message = "AI вернул пустой ответ",
                details = "Не удалось отредактировать файл"
            )
        }
        
        // 3. Записываем отредактированное содержимое обратно
        val success = fileManager.writeFile(path, editedContent, append = false)
        
        return if (success) {
            val changes = if (currentContent == editedContent) {
                " (содержимое не изменилось)"
            } else {
                val oldLines = currentContent.lines().size
                val newLines = editedContent.lines().size
                val oldChars = currentContent.length
                val newChars = editedContent.length
                " ($oldLines → $newLines строк, $oldChars → $newChars символов)"
            }
            
            SkillResult.Success(
                message = "✅ **Файл отредактирован:** `$path`$changes\n\n📄 **Новое содержимое:**\n```\n${editedContent.take(500)}${if (editedContent.length > 500) "\n... (ещё ${editedContent.length - 500} символов)" else ""}\n```",
                data = mapOf(
                    "path" to path,
                    "original_size" to currentContent.length,
                    "edited_size" to editedContent.length,
                    "line_changes" to "${currentContent.lines().size} → ${editedContent.lines().size}",
                    "preview" to editedContent.take(500)
                ),
                responseType = ResponseType.RICH_TEXT
            )
        } else {
            SkillResult.Error(
                message = "Не удалось записать отредактированное содержимое в файл '$path'",
                details = "Проверьте права доступа к файлу"
            )
        }
    }
    
    /**
     * Создание отчёта.
     */
    private fun executeCreateReport(params: Map<String, Any>): SkillResult {
        val path = params["path"] as? String ?: "report.txt"
        val content = params["content"] as? String ?: ""
        
        val success = fileManager.writeFile(path, content, append = false)
        
        return if (success) {
            SkillResult.Success(
                message = "📊 **Отчёт создан:** `$path` (${content.length} символов)",
                data = mapOf("path" to path, "size" to content.length),
                responseType = ResponseType.TEXT
            )
        } else {
            SkillResult.Error(message = "Не удалось создать отчёт '$path'")
        }
    }
    
}
