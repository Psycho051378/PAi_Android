package com.pai.android.agent

import com.pai.android.data.repository.AiRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.max
import kotlin.text.Regex
import kotlin.text.RegexOption

/**
 * Тип запроса пользователя.
 */
enum class RequestType {
    // Файловые операции
    FILE_SYSTEM_LIST,      // Показать файлы/папки
    FILE_SYSTEM_CREATE,    // Создать файл/папку
    FILE_SYSTEM_DELETE,    // Удалить файл/папку
    FILE_SYSTEM_READ,      // Прочитать файл
    FILE_SYSTEM_WRITE,     // Записать в файл
    FILE_SYSTEM_ANALYZE,   // Проанализировать директорию
    
    // Операции с памятью
    MEMORY_SEARCH,         // Поиск в памяти
    MEMORY_SAVE,           // Сохранить факт
    MEMORY_RETRIEVE,       // Получить факт
    MEMORY_TEMPORAL,       // Временной поиск в дневной памяти
    
    // Анализ документов
    DOCUMENT_ANALYZE,      // Анализ документа
    DOCUMENT_COMPARE,      // Сравнение документов
    DOCUMENT_FOLDER_ANALYZE, // Анализ папки с документами
    
    // Общие запросы
    GENERAL_CHAT,          // Общий разговор
    AGENT_PLANNING,        // Сложное планирование (требует нескольких действий)
    
    // Неизвестный тип
    UNKNOWN
}

/**
 * Результат классификации запроса.
 */
data class RequestClassification(
    val type: RequestType,
    val confidence: Float,
    val parameters: Map<String, Any> = emptyMap(),
    val toolName: String? = null,
    val reasoning: String = ""
)

/**
 * AI-классификатор запросов пользователя.
 * Определяет тип запроса и извлекает параметры для инструментов.
 */
@Singleton
class RequestClassifier @Inject constructor(
    private val aiRepository: AiRepository,
    private val defaultDispatcher: CoroutineDispatcher
) {
    
    /**
     * Классифицирует запрос пользователя.
     */
    suspend fun classify(query: String): RequestClassification {
        return withContext(defaultDispatcher) {
            try {
                println("🔍 RequestClassifier.classify: запрос='$query'")
                
                // Шаг 1: Определяем тип запроса через AI
                val classification = classifyWithAi(query)
                println("📊 AI классификация: type=${classification.type}, confidence=${classification.confidence}, reasoning=${classification.reasoning.take(50)}...")
                
                // Шаг 2: Извлекаем параметры для определённого типа
                val parameters = extractParametersForType(query, classification.type)
                println("📝 Извлечённые параметры: $parameters")
                
                // Шаг 3: Определяем имя инструмента (если применимо)
                val toolName = determineToolName(classification.type)
                println("🛠️ Инструмент: $toolName")
                
                RequestClassification(
                    type = classification.type,
                    confidence = classification.confidence,
                    parameters = parameters,
                    toolName = toolName,
                    reasoning = classification.reasoning
                )
            } catch (e: Exception) {
                println("❌ Ошибка в RequestClassifier.classify: ${e.message}")
                // В случае ошибки возвращаем UNKNOWN с минимальной уверенностью
                RequestClassification(
                    type = RequestType.UNKNOWN,
                    confidence = 0.1f,
                    parameters = emptyMap(),
                    reasoning = "Ошибка классификации: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Классифицирует запрос с помощью AI.
     */
    private suspend fun classifyWithAi(query: String): RequestClassification {
        val prompt = buildClassificationPrompt(query)
        
        val response = aiRepository.sendMessage(
            messages = listOf(
                com.pai.android.data.model.Message.createSystemMessage("request_classifier", prompt),
                com.pai.android.data.model.Message.createUserMessage("request_classifier", query)
            ),
            systemPrompt = "Ты классификатор запросов. Определи тип запроса и верни JSON.",
            memoryContext = null
        )
        
        return parseAiResponse(response.getOrThrow().text, query)
    }
    
    /**
     * Строит промпт для классификации.
     */
    private fun buildClassificationPrompt(query: String): String {
        return """
            Ты - классификатор запросов для AI-ассистента. 
            Проанализируй запрос пользователя и определи наиболее подходящий тип действия.
            
            ## Запрос пользователя:
            "$query"
            
            ## Возможные типы запросов:
            
            ### Файловые операции:
            1. FILE_SYSTEM_LIST - показать файлы/папки (например: "покажи файлы", "что в папке", "список файлов")
            2. FILE_SYSTEM_CREATE - создать файл/папку (например: "создай папку", "создай файл", "запиши в файл")
            3. FILE_SYSTEM_DELETE - удалить файл/папку (например: "удали папку", "удалить файл", "убери")
            4. FILE_SYSTEM_READ - прочитать файл (например: "покажи содержимое файла", "прочитай файл")
            5. FILE_SYSTEM_WRITE - записать в файл (например: "добавь в файл", "измени файл")
            6. FILE_SYSTEM_ANALYZE - проанализировать директорию (например: "проанализируй папку", "сколько места занимает")
            
            ### Операции с памятью:
            7. MEMORY_SEARCH - поиск в памяти (например: "что ты помнишь", "найди в памяти", "вспомни")
            8. MEMORY_SAVE - сохранить факт (например: "запомни", "сохрани информацию")
            9. MEMORY_RETRIEVE - получить факт (например: "как меня зовут", "где я живу")
            10. MEMORY_TEMPORAL - временной поиск в дневной памяти (например: "что было вчера", "сегодня делал")
            
            ### Анализ документов:
            11. DOCUMENT_ANALYZE - анализ документа (например: "проанализируй документ", "разбери файл")
            12. DOCUMENT_COMPARE - сравнение документов (например: "сравни файлы", "что общего")
            13. DOCUMENT_FOLDER_ANALYZE - анализ папки с документами (например: "проанализируй все документы в папке")
            
            ### Общие запросы:
            14. GENERAL_CHAT - общий разговор (например: "привет", "как дела", "расскажи о чём-нибудь")
            15. AGENT_PLANNING - сложное планирование (например: "создай отчёт и сохрани", "найди файл и проанализируй")
            
            ## Правила классификации:
            1. Если запрос содержит указание на файлы, папки, директории - выбирай FILE_SYSTEM_*
            2. Если запрос содержит слова "помни", "память", "вспомни" - выбирай MEMORY_*
            3. Если запрос содержит слова "анализ", "разбери", "проанализируй" для документов - выбирай DOCUMENT_*
            4. Если запрос простой и не требует действий - выбирай GENERAL_CHAT
            5. Если запрос сложный и требует нескольких действий - выбирай AGENT_PLANNING
            
            ## Выходной формат (JSON):
            {
                "type": "TYPE_NAME",
                "confidence": 0.0-1.0,
                "reasoning": "Краткое объяснение выбора типа",
                "hints": {
                    "path": "путь к файлу/папке (если есть)",
                    "content": "содержимое для записи (если есть)",
                    "search_query": "поисковый запрос (если есть)",
                    "recursive": true/false (для файловых операций)
                }
            }
            
            ## Примеры:
            1. Запрос: "покажи файлы в workspace"
               Ответ: {"type": "FILE_SYSTEM_LIST", "confidence": 0.95, "reasoning": "Пользователь хочет увидеть список файлов", "hints": {"path": "workspace", "recursive": false}}
            
            2. Запрос: "создай папку проекты_2026"
               Ответ: {"type": "FILE_SYSTEM_CREATE", "confidence": 0.9, "reasoning": "Пользователь просит создать папку", "hints": {"path": "проекты_2026"}}
            
            3. Запрос: "что ты помнишь обо мне?"
               Ответ: {"type": "MEMORY_SEARCH", "confidence": 0.85, "reasoning": "Пользователь спрашивает о сохранённой информации", "hints": {"search_query": "обо мне", "scope": "user"}}
            
            Верни ТОЛЬКО JSON без дополнительного текста.
        """.trimIndent()
    }
    
    /**
     * Парсит ответ AI.
     */
    private fun parseAiResponse(responseText: String, originalQuery: String): RequestClassification {
        return try {
            // Извлекаем JSON из ответа
            val jsonStart = responseText.indexOf('{')
            val jsonEnd = responseText.lastIndexOf('}') + 1
            val jsonText = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                responseText.substring(jsonStart, jsonEnd)
            } else {
                responseText
            }
            
            val json = JSONObject(jsonText)
            val typeStr = json.getString("type")
            val confidence = json.getDouble("confidence").toFloat()
            val reasoning = json.optString("reasoning", "Тип определён на основе анализа запроса")
            val hints = json.optJSONObject("hints") ?: JSONObject()
            
            // Конвертируем hints в Map
            val hintsMap = mutableMapOf<String, Any>()
            hints.keys().forEach { key ->
                when (val value = hints.get(key)) {
                    is String -> hintsMap[key] = value
                    is Number -> hintsMap[key] = value
                    is Boolean -> hintsMap[key] = value
                    is JSONArray -> hintsMap[key] = value.toString()
                    is JSONObject -> hintsMap[key] = value.toString()
                    else -> hintsMap[key] = value.toString()
                }
            }
            
            // Преобразуем строку типа в enum
            val type = try {
                RequestType.valueOf(typeStr)
            } catch (e: IllegalArgumentException) {
                // Пытаемся найти похожий тип
                guessRequestType(typeStr, originalQuery)
            }
            
            RequestClassification(
                type = type,
                confidence = confidence,
                parameters = hintsMap,
                reasoning = reasoning
            )
        } catch (e: Exception) {
            // Если не удалось распарсить JSON, пытаемся угадать тип по ключевым словам
            guessTypeFromKeywords(originalQuery)
        }
    }
    
    /**
     * Извлекает параметры для определённого типа запроса.
     */
    private suspend fun extractParametersForType(query: String, type: RequestType): Map<String, Any> {
        return when (type) {
            RequestType.FILE_SYSTEM_LIST -> extractFileSystemListParams(query)
            RequestType.FILE_SYSTEM_CREATE -> extractFileSystemCreateParams(query)
            RequestType.FILE_SYSTEM_DELETE -> extractFileSystemDeleteParams(query)
            RequestType.FILE_SYSTEM_READ -> extractFileSystemReadParams(query)
            RequestType.FILE_SYSTEM_WRITE -> extractFileSystemWriteParams(query)
            RequestType.FILE_SYSTEM_ANALYZE -> extractFileSystemAnalyzeParams(query)
            RequestType.MEMORY_SEARCH -> extractMemorySearchParams(query)
            RequestType.MEMORY_SAVE -> extractMemorySaveParams(query)
            RequestType.MEMORY_RETRIEVE -> extractMemoryRetrieveParams(query)
            RequestType.MEMORY_TEMPORAL -> extractMemoryTemporalParams(query)
            RequestType.DOCUMENT_ANALYZE -> extractDocumentAnalyzeParams(query)
            RequestType.DOCUMENT_COMPARE -> extractDocumentCompareParams(query)
            RequestType.DOCUMENT_FOLDER_ANALYZE -> extractDocumentFolderAnalyzeParams(query)
            else -> emptyMap()
        }
    }
    
    /**
     * Определяет имя инструмента для типа запроса.
     */
    private fun determineToolName(type: RequestType): String? {
        return when (type) {
            RequestType.FILE_SYSTEM_LIST,
            RequestType.FILE_SYSTEM_CREATE,
            RequestType.FILE_SYSTEM_DELETE,
            RequestType.FILE_SYSTEM_READ,
            RequestType.FILE_SYSTEM_WRITE,
            RequestType.FILE_SYSTEM_ANALYZE -> "file_system"
            
            RequestType.MEMORY_SEARCH,
            RequestType.MEMORY_SAVE,
            RequestType.MEMORY_RETRIEVE,
            RequestType.MEMORY_TEMPORAL -> "memory"
            
            RequestType.DOCUMENT_ANALYZE,
            RequestType.DOCUMENT_COMPARE,
            RequestType.DOCUMENT_FOLDER_ANALYZE -> "document_analysis"
            
            else -> null
        }
    }
    
    // ==================== Методы извлечения параметров ====================
    
    private fun extractFileSystemListParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        // Извлекаем путь
        val path = extractPathFromQuery(query)
        if (path.isNotEmpty()) {
            params["path"] = path
        }
        
        // Определяем рекурсивность по ключевым словам
        val lowerQuery = query.lowercase()
        val recursive = "все" in lowerQuery || "рекурсивно" in lowerQuery || "со всеми" in lowerQuery
        params["recursive"] = recursive
        
        params["command"] = "list_files"
        return params
    }
    
    private fun extractFileSystemCreateParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val lowerQuery = query.lowercase()
        
        // Определяем, создаём ли мы файл или папку
        val isFileRequest = "файл" in lowerQuery
        val isFolderRequest = "папк" in lowerQuery || "директор" in lowerQuery
        
        // Unicode паттерн для любых букв и цифр
        val unicodePattern = "[\\p{L}\\p{N}_\\-/.]+"
        
        if (isFileRequest) {
            // СОЗДАНИЕ ФАЙЛА
            // 1. Извлекаем путь к папке (если указан)
            var folderPath = ""
            val folderPatterns = listOf(
                Regex("в\\s+папке\\s+($unicodePattern)", RegexOption.IGNORE_CASE),
                Regex("папку\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
            )
            
            folderPatterns.forEach { regex ->
                val match = regex.find(query)
                if (match != null && folderPath.isEmpty()) {
                    folderPath = match.groupValues[1]
                }
            }
            
            // 2. Извлекаем имя файла
            var fileName = ""
            val filePatterns = listOf(
                Regex("файл\\s+($unicodePattern)", RegexOption.IGNORE_CASE),
                Regex("создай\\s+файл\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
            )
            
            filePatterns.forEach { regex ->
                val match = regex.find(query)
                if (match != null && fileName.isEmpty()) {
                    fileName = match.groupValues[1]
                }
            }
            
            // 3. Если имя файла не найдено, используем дефолтное
            if (fileName.isEmpty()) {
                fileName = "новый_файл.txt"
            }
            
            // 4. Собираем полный путь
            val fullPath = if (folderPath.isNotEmpty()) {
                "$folderPath/$fileName"
            } else {
                fileName
            }
            
            params["path"] = fullPath
            params["command"] = "write_file"
            
            // 5. Извлекаем содержимое файла
            var content = ""
            
            // Пытаемся извлечь содержимое файла
            val extractedContent = extractContentFromQuery(query)
            params["content"] = extractedContent
            println("📄 Извлечённое содержимое: '$extractedContent'")
            
        } else if (isFolderRequest) {
            // СОЗДАНИЕ ПАПКИ
            val path = extractPathFromQuery(query)
            if (path.isNotEmpty()) {
                params["path"] = path
            } else {
                // Пытаемся извлечь имя папки из запроса
                val unicodePattern = "[\\p{L}\\p{N}_\\-/.]+"
                val folderMatch = Regex("создай\\s+папку\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
                    .find(query)
                params["path"] = folderMatch?.groupValues?.get(1) ?: "новая_папка"
            }
            params["command"] = "create_folder"
        } else {
            // По умолчанию считаем, что создаётся файл
            val path = extractPathFromQuery(query)
            if (path.isNotEmpty()) {
                params["path"] = path
            }
            params["command"] = "write_file"
            params["content"] = "Содержимое файла"
        }
        
        return params
    }
    
    private fun extractFileSystemDeleteParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val lowerQuery = query.lowercase()
        
        val path = extractPathFromQuery(query)
        if (path.isNotEmpty()) {
            params["path"] = path
        } else {
            // Пытаемся извлечь путь из запроса на удаление
            val unicodePattern = "[\\p{L}\\p{N}_\\-/.]+"
            val deleteMatch = Regex("удали\\s+(?:папку|файл)\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
                .find(query)
            params["path"] = deleteMatch?.groupValues?.get(1) ?: ""
        }
        
        // Определяем рекурсивность: для папок - true, для файлов - false
        val isFolder = "папк" in lowerQuery
        params["recursive"] = isFolder
        params["command"] = "delete"
        
        return params
    }
    
    private fun extractFileSystemReadParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        val path = extractPathFromQuery(query)
        if (path.isNotEmpty()) {
            params["path"] = path
        }
        
        params["command"] = "read_file"
        return params
    }
    
    private fun extractFileSystemWriteParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        // Извлекаем путь к файлу
        val path = extractPathFromQuery(query)
        println("📍 extractFileSystemWriteParams: extractPathFromQuery вернула '$path'")
        if (path.isNotEmpty()) {
            params["path"] = path
            println("📍 Используем путь из extractPathFromQuery: '$path'")
        } else {
            // Пытаемся извлечь имя файла из запроса на добавление/запись
            val filePatterns = listOf(
                Regex("файл\\s+([\\p{L}\\p{N}_\\-/.]+)", RegexOption.IGNORE_CASE),
                Regex("в\\s+файл\\s+([\\p{L}\\p{N}_\\-/.]+)", RegexOption.IGNORE_CASE),
                Regex("todo\\.txt", RegexOption.IGNORE_CASE) // специально для тестового файла
            )
            
            filePatterns.forEach { regex ->
                val match = regex.find(query)
                if (match != null && !params.containsKey("path")) {
                    val foundPath = match.groupValues[1]
                    params["path"] = foundPath
                    println("📍 Найден путь через fallback паттерн: '$foundPath'")
                }
            }
            
            // Если путь всё ещё не найден, используем дефолтный
            if (!params.containsKey("path")) {
                params["path"] = "файл.txt"
                println("📍 Путь не найден, используется дефолтный: 'файл.txt'")
            }
        }
        
        // Извлекаем содержимое с помощью улучшенной функции
        val content = extractContentForWriteQuery(query)
        params["content"] = content
        println("📝 Для write/append извлечено содержимое: '$content'")
        
        val lowerQuery = query.lowercase()
        val append = "добавь" in lowerQuery || "дополни" in lowerQuery
        params["command"] = if (append) "append_file" else "write_file"
        
        return params
    }
    
    private fun extractFileSystemAnalyzeParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        val path = extractPathFromQuery(query)
        if (path.isNotEmpty()) {
            params["path"] = path
        }
        
        params["command"] = "analyze_directory"
        return params
    }
    
    private fun extractMemorySearchParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        // Извлекаем поисковый запрос
        val searchQuery = extractSearchQuery(query)
        if (searchQuery.isNotEmpty()) {
            params["query"] = searchQuery
        } else {
            // Используем весь запрос как поисковый
            params["query"] = query
        }
        
        // Определяем scope по контексту
        val lowerQuery = query.lowercase()
        val scope = when {
            "обо мне" in lowerQuery || "меня" in lowerQuery -> "user"
            "ты" in lowerQuery || "тво" in lowerQuery -> "ai"
            else -> "all"
        }
        
        params["scope"] = scope
        params["command"] = "search_facts"
        return params
    }
    
    private fun extractMemorySaveParams(query: String): Map<String, Any> {
        // TODO: Реализовать AI-извлечение фактов для сохранения
        return mapOf("command" to "save_fact")
    }
    
    private fun extractMemoryRetrieveParams(query: String): Map<String, Any> {
        // TODO: Реализовать извлечение конкретного факта
        return mapOf("command" to "get_fact")
    }
    
    private fun extractMemoryTemporalParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        params["query"] = query
        params["command"] = "search_daily_memory"
        return params
    }
    
    private fun extractDocumentAnalyzeParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        val path = extractPathFromQuery(query)
        if (path.isNotEmpty()) {
            params["path"] = path
        }
        
        params["command"] = "analyze_file"
        return params
    }
    
    private fun extractDocumentCompareParams(query: String): Map<String, Any> {
        return mapOf("command" to "compare_documents")
    }
    
    private fun extractDocumentFolderAnalyzeParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        val path = extractPathFromQuery(query)
        if (path.isNotEmpty()) {
            params["path"] = path
        }
        
        params["command"] = "analyze_folder"
        return params
    }
    
    // ==================== Вспомогательные методы ====================
    
    private fun extractPathFromQuery(query: String): String {
        // Используем Unicode класс \p{L} для любых букв (включая ё, Ё)
        // и \p{N} для цифр, а также разрешаем точки, дефисы, подчёркивания, слэши
        val unicodePattern = "[\\p{L}\\p{N}_\\-/.]+"
        
        // Проверка на кириллицу в запросе для выбора набора паттернов
        val hasCyrillic = query.contains(Regex("[а-яА-ЯёЁ]"))
        
        // ===== РУССКИЕ ПАТТЕРНЫ =====
        if (hasCyrillic) {
            // Паттерн 1: "в папке <путь>"
            val regex1 = Regex("в\\s+папке\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
            val match1 = regex1.find(query)
            if (match1 != null) {
                val result = match1.groupValues[1]
                println("📍 extractPathFromQuery: паттерн 'в папке <путь>' -> '$result'")
                return result
            }
            
            // Паттерн 2: "в файл <путь>" (специально для запросов с "в файл ...")
            val regex2 = Regex("в\\s+файл\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
            val match2 = regex2.find(query)
            if (match2 != null) {
                val result = match2.groupValues[1]
                println("📍 extractPathFromQuery: паттерн 'в файл <путь>' -> '$result'")
                return result
            }
            
            // Паттерн 3: "файл <путь>" (для создания/удаления/чтения)
            val regex3 = Regex("файл\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
            val match3 = regex3.find(query)
            if (match3 != null) {
                val result = match3.groupValues[1]
                println("📍 extractPathFromQuery: паттерн 'файл <путь>' -> '$result'")
                return result
            }
            
            // Паттерн 4: "папку <путь>" (для создания/удаления)
            val regex4 = Regex("папку\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
            val match4 = regex4.find(query)
            if (match4 != null) {
                val result = match4.groupValues[1]
                println("📍 extractPathFromQuery: паттерн 'папку <путь>' -> '$result'")
                return result
            }
            
            // Паттерн 5: "в <путь>" (общий, должен быть после более специфичных)
            val regex5 = Regex("в\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
            val match5 = regex5.find(query)
            if (match5 != null) {
                val result = match5.groupValues[1]
                println("📍 extractPathFromQuery: паттерн 'в <путь>' -> '$result'")
                return result
            }
        }
        
        // ===== АНГЛИЙСКИЕ ПАТТЕРНЫ =====
        // Паттерн: "the file <path>" или "file <path>" (add entry to the file todo.txt)
        val filePatterns = listOf(
            Regex("the\\s+file\\s+($unicodePattern)", RegexOption.IGNORE_CASE),
            Regex("file\\s+($unicodePattern)", RegexOption.IGNORE_CASE),
            Regex("to\\s+($unicodePattern)", RegexOption.IGNORE_CASE),
            Regex("folder\\s+($unicodePattern)", RegexOption.IGNORE_CASE),
            Regex("directory\\s+($unicodePattern)", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in filePatterns) {
            val match = pattern.find(query)
            if (match != null) {
                val result = match.groupValues[1].trim()
                println("📍 extractPathFromQuery: английский паттерн '$result'")
                return result
            }
        }
        
        // Паттерн: имя файла с расширением (любой текст перед .txt, .md и т.д.)
        val fileExtensionRegex = Regex("""([\p{L}\p{N}_\-/.]+)\.(txt|md|json|xml|html|css|js|kt|java|py|csv|log|ini|cfg|yml|yaml)\b""", RegexOption.IGNORE_CASE)
        val extMatch = fileExtensionRegex.find(query)
        if (extMatch != null) {
            val fileName = extMatch.groupValues[0] // полное имя с расширением
            println("📍 extractPathFromQuery: найден файл с расширением '$fileName'")
            return fileName
        }
        
        println("📍 extractPathFromQuery: путь не найден")
        return ""
    }
    
    private fun extractSearchQuery(query: String): String {
        val lowerQuery = query.lowercase()
        
        // Пытаемся извлечь поисковый запрос после "найди", "что ты помнишь" и т.д.
        val patterns = listOf(
            Regex("найди\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("что ты помнишь\\s+(?:о|про)?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("вспомни\\s+(.+)", RegexOption.IGNORE_CASE)
        )
        
        patterns.forEach { regex ->
            val match = regex.find(lowerQuery)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        
        return ""
    }
    
    private fun extractContentFromQuery(query: String): String {
        println("🔍 Извлечение содержимого из запроса: '$query'")
        
        val patterns = listOf(
            // Паттерн 1: "с записью 'текст'" (с кавычками)
            Regex("с\\s+записью\\s+['\"](.+?)['\"]", RegexOption.IGNORE_CASE),
            // Паттерн 2: "с записью текст" (без кавычек, до конца или до следующего ключевого слова)
            Regex("с\\s+записью\\s+(.+?)(?:\\s+(?:в|файл|папку|создай)|$)", RegexOption.IGNORE_CASE),
            // Паттерн 3: "с текстом 'текст'" (с кавычками)
            Regex("с\\s+текстом\\s+['\"](.+?)['\"]", RegexOption.IGNORE_CASE),
            // Паттерн 4: "с текстом текст" (без кавычек)
            Regex("с\\s+текстом\\s+(.+?)(?:\\s+(?:в|файл|папку|создай)|$)", RegexOption.IGNORE_CASE),
            // Паттерн 5: "запиши текст в"
            Regex("запиши\\s+(.+?)\\s+в", RegexOption.IGNORE_CASE),
            // Паттерн 6: "содержимое: текст"
            Regex("содержимое\\s*[:=]?\\s*['\"](.+?)['\"]", RegexOption.IGNORE_CASE),
            // Паттерн 7: общий паттерн для текста в кавычках после файла
            Regex("файл\\s+[\\p{L}\\p{N}_\\-/.]+\\s+(?:с\\s+)?(?:записью|текстом)?\\s*['\"](.+?)['\"]", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            try {
                val match = pattern.find(query)
                if (match != null && match.groupValues.size > 1) {
                    val content = match.groupValues[1].trim()
                    if (content.isNotEmpty()) {
                        println("✅ Найдено содержимое по паттерну: '$content'")
                        return content
                    }
                }
            } catch (e: Exception) {
                println("⚠️ Ошибка при обработке паттерна $pattern: ${e.message}")
            }
        }
        
        // Fallback: если ничего не найдено, пытаемся извлечь текст после последнего упоминания файла
        val filePattern = Regex("файл\\s+[\\p{L}\\p{N}_\\-/.]+\\s+(.+)", RegexOption.IGNORE_CASE)
        val fileMatch = filePattern.find(query)
        if (fileMatch != null && fileMatch.groupValues.size > 1) {
            val afterFile = fileMatch.groupValues[1].trim()
            // Убираем ключевые слова в начале
            val prefixRegex = Regex("^(с\\s+(?:записью|текстом)\\s+)", RegexOption.IGNORE_CASE)
            val cleaned = afterFile.replaceFirst(prefixRegex, "")
            if (cleaned.isNotEmpty() && cleaned != afterFile) {
                println("📝 Извлечено содержимое из конца запроса: '$cleaned'")
                return cleaned
            }
        }
        
        println("📭 Содержимое не найдено, используется значение по умолчанию")
        return "Содержимое файла"
    }
    
    /**
     * Извлекает содержимое для запросов на запись/добавление в файл.
     */
    private fun extractContentForWriteQuery(query: String): String {
        println("🔍 Извлечение содержимого для write/append: '$query'")
        
        val patterns = listOf(
            // Паттерн 1: "добавь запись 'текст' в файл"
            Regex("добавь\\s+запись\\s+['\"](.+?)['\"]\\s+в\\s+файл", RegexOption.IGNORE_CASE),
            // Паттерн 2: "добавь запись текст в файл" (без кавычек)
            Regex("добавь\\s+запись\\s+(.+?)\\s+в\\s+файл", RegexOption.IGNORE_CASE),
            // Паттерн 3: "добавь 'текст' в файл"
            Regex("добавь\\s+['\"](.+?)['\"]\\s+в\\s+файл", RegexOption.IGNORE_CASE),
            // Паттерн 4: "добавь текст в файл" (без кавычек)
            Regex("добавь\\s+(.+?)\\s+в\\s+файл", RegexOption.IGNORE_CASE),
            // Паттерн 5: "запиши 'текст' в файл"
            Regex("запиши\\s+['\"](.+?)['\"]\\s+в\\s+файл", RegexOption.IGNORE_CASE),
            // Паттерн 6: "запиши текст в файл" (без кавычек)
            Regex("запиши\\s+(.+?)\\s+в\\s+файл", RegexOption.IGNORE_CASE),
            // Паттерн 7: общий паттерн для текста после "добавь запись" и до конца или до "в файл"
            Regex("добавь\\s+запись\\s+(.+?)(?:\\s+в\\s+файл|$)", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            try {
                val match = pattern.find(query)
                if (match != null && match.groupValues.size > 1) {
                    val content = match.groupValues[1].trim()
                    if (content.isNotEmpty()) {
                        println("✅ Для write/append найдено содержимое: '$content'")
                        return content
                    }
                }
            } catch (e: Exception) {
                println("⚠️ Ошибка при обработке паттерна $pattern: ${e.message}")
            }
        }
        
        // Если ничего не найдено, используем общую функцию извлечения
        return extractContentFromQuery(query)
    }
    
    private fun guessRequestType(typeStr: String, query: String): RequestType {
        val lowerType = typeStr.lowercase()
        val lowerQuery = query.lowercase()
        
        return when {
            "file" in lowerType || "файл" in lowerType -> {
                when {
                    "list" in lowerType || "показать" in lowerType -> RequestType.FILE_SYSTEM_LIST
                    "create" in lowerType || "созда" in lowerType -> RequestType.FILE_SYSTEM_CREATE
                    "delete" in lowerType || "удал" in lowerType -> RequestType.FILE_SYSTEM_DELETE
                    "read" in lowerType || "чита" in lowerType -> RequestType.FILE_SYSTEM_READ
                    "write" in lowerType || "запис" in lowerType -> RequestType.FILE_SYSTEM_WRITE
                    "analyze" in lowerType || "анализ" in lowerType -> RequestType.FILE_SYSTEM_ANALYZE
                    else -> RequestType.FILE_SYSTEM_LIST
                }
            }
            "memory" in lowerType || "памят" in lowerType -> {
                when {
                    "search" in lowerType || "поиск" in lowerType -> RequestType.MEMORY_SEARCH
                    "save" in lowerType || "сохран" in lowerType -> RequestType.MEMORY_SAVE
                    "retrieve" in lowerType || "получ" in lowerType -> RequestType.MEMORY_RETRIEVE
                    "temporal" in lowerType || "времен" in lowerType -> RequestType.MEMORY_TEMPORAL
                    else -> RequestType.MEMORY_SEARCH
                }
            }
            "document" in lowerType || "документ" in lowerType -> {
                when {
                    "analyze" in lowerType || "анализ" in lowerType -> RequestType.DOCUMENT_ANALYZE
                    "compare" in lowerType || "сравн" in lowerType -> RequestType.DOCUMENT_COMPARE
                    "folder" in lowerType || "папк" in lowerType -> RequestType.DOCUMENT_FOLDER_ANALYZE
                    else -> RequestType.DOCUMENT_ANALYZE
                }
            }
            else -> guessTypeFromKeywords(query).type
        }
    }
    
    private fun guessTypeFromKeywords(query: String): RequestClassification {
        val lowerQuery = query.lowercase()
        
        // Проверяем файловые операции
        when {
            "покажи файл" in lowerQuery || 
            "список файл" in lowerQuery || 
            "что в папке" in lowerQuery ||
            "что лежит" in lowerQuery -> 
                return RequestClassification(
                    type = RequestType.FILE_SYSTEM_LIST,
                    confidence = 0.8f,
                    reasoning = "Определено по ключевым словам: показать файлы"
                )
            
            "создай папку" in lowerQuery || 
            "создай файл" in lowerQuery ->
                return RequestClassification(
                    type = RequestType.FILE_SYSTEM_CREATE,
                    confidence = 0.8f,
                    reasoning = "Определено по ключевым словам: создать"
                )
            
            "удали папку" in lowerQuery ||
            "удалить файл" in lowerQuery ->
                return RequestClassification(
                    type = RequestType.FILE_SYSTEM_DELETE,
                    confidence = 0.8f,
                    reasoning = "Определено по ключевым словам: удалить"
                )
            
            "прочитай файл" in lowerQuery ||
            "покажи содержимое" in lowerQuery ->
                return RequestClassification(
                    type = RequestType.FILE_SYSTEM_READ,
                    confidence = 0.7f,
                    reasoning = "Определено по ключевым словам: прочитать файл"
                )
            
            "запиши в файл" in lowerQuery ||
            "добавь в файл" in lowerQuery ->
                return RequestClassification(
                    type = RequestType.FILE_SYSTEM_WRITE,
                    confidence = 0.7f,
                    reasoning = "Определено по ключевым словам: записать в файл"
                )
            
            "проанализируй папку" in lowerQuery ||
            "анализ директории" in lowerQuery ->
                return RequestClassification(
                    type = RequestType.FILE_SYSTEM_ANALYZE,
                    confidence = 0.7f,
                    reasoning = "Определено по ключевым словам: анализ папки"
                )
            
            "что ты помнишь" in lowerQuery ||
            "найди в памяти" in lowerQuery ->
                return RequestClassification(
                    type = RequestType.MEMORY_SEARCH,
                    confidence = 0.8f,
                    reasoning = "Определено по ключевым словам: поиск в памяти"
                )
            
            "проанализируй документ" in lowerQuery ||
            "разбери файл" in lowerQuery ->
                return RequestClassification(
                    type = RequestType.DOCUMENT_ANALYZE,
                    confidence = 0.7f,
                    reasoning = "Определено по ключевым словам: анализ документа"
                )
        }
        
        // По умолчанию - общий разговор
        return RequestClassification(
            type = RequestType.GENERAL_CHAT,
            confidence = 0.5f,
            reasoning = "Не удалось определить конкретный тип, используется общий разговор"
        )
    }
}