package com.pai.android.data.repository

import android.content.Context
import com.google.gson.Gson
import com.pai.android.data.local.MemoryDao
import com.pai.android.data.model.DailyMemory
import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.model.PermanentMemory.Companion.Scopes
import com.pai.android.data.service.QueryAnalyzer
import com.pai.android.data.service.DailyMemorySearchService
import com.pai.android.data.export.MemoryExporter
import com.pai.android.data.export.MemoryImporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Репозиторий для работы с памятью (дневной и постоянной).
 */
class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val queryAnalyzer: QueryAnalyzer,
    private val dailyMemorySearchService: DailyMemorySearchService
) {
    
    // ============= DAILY MEMORY =============
    
    /**
     * Сохраняет запись в дневную память.
     * Если запись за сегодня уже существует, добавляет контент к существующему.
     */
    suspend fun saveDailyEntry(content: String, tags: List<String> = emptyList()) {
        val today = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val existing = memoryDao.getDailyByDate(today)
        
        if (existing != null) {
            // Добавляем к существующей записи
            val updated = existing.appendContent(content)
                .let { if (tags.isNotEmpty()) it.addTag(tags.joinToString(",")) else it }
            memoryDao.updateDaily(updated)
        } else {
            // Создаём новую запись
            val dailyMemory = DailyMemory.createForToday(content, tags)
            memoryDao.insertDaily(dailyMemory)
        }
    }
    
    /**
     * Получает дневную запись за указанную дату.
     */
    suspend fun getDailyMemory(date: String = SimpleDateFormat("yyyy-MM-dd").format(Date())): DailyMemory? {
        return memoryDao.getDailyByDate(date)
    }
    
    /**
     * Наблюдает за дневной записью за указанную дату.
     */
    fun observeDailyMemory(date: String = SimpleDateFormat("yyyy-MM-dd").format(Date())): Flow<DailyMemory?> {
        return memoryDao.observeDailyByDate(date)
    }
    
    /**
     * Наблюдает за всеми дневными записями (сортировка по дате, новые первыми).
     */
    fun observeAllDailyMemory(): Flow<List<DailyMemory>> {
        return memoryDao.observeAllDaily()
    }
    
    /**
     * Получает последние N дневных записей.
     */
    suspend fun getRecentDailyMemory(limit: Int = 30): List<DailyMemory> {
        return memoryDao.getRecentDaily(limit)
    }
    
    /**
     * Ищет дневные записи по содержимому или тегам.
     */
    suspend fun searchDailyEntries(query: String, limit: Int = 30): List<DailyMemory> {
        return memoryDao.searchDailyContent(query, limit)
    }
    
    /**
     * Удаляет дневную запись по дате.
     */
    suspend fun deleteDailyMemory(date: String) {
        memoryDao.deleteDailyByDate(date)
    }
    
    // ============= PERMANENT MEMORY =============
    
    /**
     * Сохраняет факт в постоянную память.
     * Если факт с такой категорией и ключом уже существует, обновляет значение и уверенность.
     * Старая сигнатура для обратной совместимости (scope = "user").
     */
    suspend fun savePermanentFact(
        category: String,
        key: String,
        value: String,
        confidence: Float = 0.8f,
        sourceChatId: String? = null,
        sourceMessageId: String? = null
    ) {
        savePermanentFactFull(
            category = category,
            key = key,
            value = value,
            confidence = confidence,
            scope = Scopes.USER,
            tags = null,
            metadata = null,
            sourceChatId = sourceChatId,
            sourceMessageId = sourceMessageId
        )
    }
    
    /**
     * Сохраняет факт в постоянную память с полным набором параметров.
     * Учитывает scope для уникальности (scope + category + key).
     */
    suspend fun savePermanentFactFull(
        category: String,
        key: String,
        value: String,
        confidence: Float = 0.8f,
        scope: String = Scopes.USER,
        tags: String? = null,
        metadata: Map<String, Any>? = null,
        sourceChatId: String? = null,
        sourceMessageId: String? = null
    ) {
        require(Scopes.isValid(scope)) { "Invalid scope: $scope" }
        
        // Выполняем операции с БД в IO dispatcher
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val existing = memoryDao.getByScopeCategoryAndKey(scope, category, key)
            
            if (existing != null) {
                // Обновляем существующий факт
                val updated = existing.updateValue(value, confidence)
                    .copy(
                        scope = scope,
                        tags = tags ?: existing.tags,
                        metadata = metadata?.let { com.google.gson.Gson().toJson(it) } ?: existing.metadata,
                        sourceChatId = sourceChatId ?: existing.sourceChatId,
                        sourceMessageId = sourceMessageId ?: existing.sourceMessageId
                    )
                memoryDao.updatePermanent(updated)
            } else {
                // Создаём новый факт
                val permanentMemory = PermanentMemory.createAssumedFull(
                    category = category,
                    key = key,
                    value = value,
                    confidence = confidence,
                    scope = scope,
                    tags = tags,
                    metadata = metadata,
                    sourceChatId = sourceChatId,
                    sourceMessageId = sourceMessageId
                )
                memoryDao.insertPermanent(permanentMemory)
            }
        }
    }
    
    /**
     * Сохраняет подтверждённый пользователем факт (confidence = 1.0).
     * Старая сигнатура для обратной совместимости (scope = "user").
     */
    suspend fun saveConfirmedFact(
        category: String,
        key: String,
        value: String,
        sourceChatId: String? = null,
        sourceMessageId: String? = null
    ) {
        saveConfirmedFactFull(
            category = category,
            key = key,
            value = value,
            scope = Scopes.USER,
            tags = null,
            metadata = null,
            sourceChatId = sourceChatId,
            sourceMessageId = sourceMessageId
        )
    }
    
    /**
     * Сохраняет подтверждённый факт с полным набором параметров.
     */
    suspend fun saveConfirmedFactFull(
        category: String,
        key: String,
        value: String,
        scope: String = Scopes.USER,
        tags: String? = null,
        metadata: Map<String, Any>? = null,
        sourceChatId: String? = null,
        sourceMessageId: String? = null
    ) {
        savePermanentFactFull(
            category = category,
            key = key,
            value = value,
            confidence = 1.0f,
            scope = scope,
            tags = tags,
            metadata = metadata,
            sourceChatId = sourceChatId,
            sourceMessageId = sourceMessageId
        )
    }
    
    /**
     * Наблюдает за всеми фактами постоянной памяти.
     */
    fun observeAllPermanentFacts(): Flow<List<PermanentMemory>> {
        return memoryDao.observeAllPermanent()
    }
    
    /**
     * Наблюдает за фактами по категории.
     */
    fun observePermanentFactsByCategory(category: String): Flow<List<PermanentMemory>> {
        return memoryDao.observeByCategory(category)
    }
    

    
    /**
     * Получает факты для обогащения промпта AI.
     * Возвращает факты с высокой уверенностью, наиболее релевантные текущему контексту.
     */
    suspend fun getFactsForPrompt(context: String = "", limit: Int = 25): List<PermanentMemory> {
        // Если есть контекст, ищем связанные факты
        if (context.isNotBlank()) {
            val relatedFacts = memoryDao.findRelatedFacts(context, limit)
            if (relatedFacts.isNotEmpty()) {
                return relatedFacts
            }
        }
        // Иначе возвращаем факты с высокой уверенностью
        return memoryDao.getFactsForPrompt(limit)
    }
    
    /**
     * Увеличивает уверенность в факте.
     */
    suspend fun increaseFactConfidence(id: String, amount: Float = 0.1f) {
        memoryDao.increaseConfidence(id, amount)
    }
    
    /**
     * Удаляет факт из постоянной памяти.
     */
    suspend fun deletePermanentFact(id: String) {
        memoryDao.deletePermanent(id)
    }
    
    /**
     * Обновляет факт в постоянной памяти.
     */
    suspend fun updatePermanentFact(
        id: String,
        key: String? = null,
        value: String? = null,
        confidence: Float? = null,
        scope: String? = null,
        tags: String? = null
    ) {
        val fact = memoryDao.getPermanentById(id) ?: return
        val updated = fact.copy(
            key = key ?: fact.key,
            value = value ?: fact.value,
            confidence = confidence ?: fact.confidence,
            scope = scope ?: fact.scope,
            tags = tags ?: fact.tags
        )
        memoryDao.updatePermanent(updated)
    }
    
    /**
     * Получает факт по ID.
     */
    suspend fun getPermanentFact(id: String): PermanentMemory? {
        return memoryDao.getPermanentById(id)
    }
    
    /**
     * Получает все уникальные категории.
     */
    suspend fun getAllCategories(): List<String> {
        return memoryDao.getAllCategories()
    }
    
    /**
     * Получает статистику по категориям.
     */
    suspend fun getCategoryStats(): Map<String, Int> {
        val stats = memoryDao.getCategoryStats()
        return stats.associate { it.category to it.count }
    }
    
    /**
     * Суммаризирует разговор и сохраняет ключевые моменты.
     * @param messages Список сообщений для суммаризации
     * @param tags Теги для дневной записи
     */
    suspend fun summarizeConversation(messages: List<com.pai.android.data.model.Message>, tags: List<String> = emptyList()) {
        if (messages.isEmpty()) return
        
        // Простая суммаризация: первые 3 и последние 3 сообщения
        val dateStr = SimpleDateFormat("yyyy-MM-dd").format(Date())
        val summary = buildString {
            append("## Разговор от ${dateStr}\n\n")
            append("**Начало:**\n")
            messages.take(3).forEach { msg ->
                append("- ${msg.role}: ${msg.content.take(100)}...\n")
            }
            if (messages.size > 6) {
                append("\n**...пропущено ${messages.size - 6} сообщений...**\n\n")
            }
            append("**Конец:**\n")
            messages.takeLast(3).forEach { msg ->
                append("- ${msg.role}: ${msg.content.take(100)}...\n")
            }
            append("\n**Всего сообщений:** ${messages.size}\n")
        }
        
        saveDailyEntry(summary, tags)
        
        // TODO: В будущем можно добавить AI-суммаризацию
        // для извлечения ключевых фактов в постоянную память
    }
    
    /**
     * Форматирует факты для вставки в промпт AI.
     */
    suspend fun formatFactsForPrompt(context: String = ""): String {
        val facts = getFactsForPrompt(context)
        if (facts.isEmpty()) return ""
        
        return buildString {
            append("\n\n[Контекст пользователя из памяти]\n")
            facts.forEach { fact ->
                append("- ${fact.key}: ${fact.value} (${(fact.confidence * 100).toInt()}%)\n")
            }
        }
    }
    
    // ==================== НОВЫЕ МЕТОДЫ ДЛЯ ГИБКОЙ СХЕМЫ ====================
    
    /**
     * Сохраняет факт об AI (scope = "ai").
     */
    suspend fun saveAiFact(
        key: String,
        value: String,
        confidence: Float = 0.9f,
        tags: String? = null,
        metadata: Map<String, Any>? = null,
        sourceChatId: String? = null,
        sourceMessageId: String? = null
    ) {
        savePermanentFactFull(
            category = PermanentMemory.Companion.AiInfoCategories.AI_INFO,
            key = key,
            value = value,
            confidence = confidence,
            scope = Scopes.AI,
            tags = tags,
            metadata = metadata,
            sourceChatId = sourceChatId,
            sourceMessageId = sourceMessageId
        )
    }
    
    /**
     * Сохраняет глобальный факт (scope = "global").
     */
    suspend fun saveGlobalFact(
        category: String,
        key: String,
        value: String,
        confidence: Float = 0.8f,
        tags: String? = null,
        metadata: Map<String, Any>? = null,
        sourceChatId: String? = null,
        sourceMessageId: String? = null
    ) {
        savePermanentFactFull(
            category = category,
            key = key,
            value = value,
            confidence = confidence,
            scope = Scopes.GLOBAL,
            tags = tags,
            metadata = metadata,
            sourceChatId = sourceChatId,
            sourceMessageId = sourceMessageId
        )
    }
    
    /**
     * Получает факты для указанного scope.
     */
    suspend fun getFactsByScope(scope: String): List<PermanentMemory> {
        require(Scopes.isValid(scope)) { "Invalid scope: $scope" }
        return memoryDao.getByScope(scope)
    }
    
    /**
     * Получает факты для указанного scope и категории.
     */
    suspend fun getFactsByScopeAndCategory(scope: String, category: String): List<PermanentMemory> {
        require(Scopes.isValid(scope)) { "Invalid scope: $scope" }
        return memoryDao.getByScopeAndCategory(scope, category)
    }
    
    /**
     * Получает факты, содержащие указанный тег.
     */
    suspend fun findFactsByTag(tag: String): List<PermanentMemory> {
        return memoryDao.findByTag(tag)
    }
    
    /**
     * Получает все теги из всех фактов.
     */
    suspend fun getAllTags(): List<String> {
        return memoryDao.getAllTags()
    }
    
    /**
     * Получает статистику по scope.
     */
    suspend fun getScopeStats(): Map<String, Int> {
        val stats = memoryDao.getScopeStats()
        return stats.associate { it.scope to it.count }
    }
    

    
    /**
     * Получает факты для обогащения промпта AI с учётом scope.
     * Приоритет: AI факты → пользовательские факты → глобальные факты.
     */
    suspend fun getFactsForPromptWithScope(context: String = "", limit: Int = 30): List<PermanentMemory> {
        // Если есть контекст, ищем связанные факты
        if (context.isNotBlank()) {
            val relatedFacts = memoryDao.findRelatedFacts(context, limit)
            if (relatedFacts.isNotEmpty()) {
                return relatedFacts
            }
        }
        // Иначе возвращаем факты с учётом scope приоритета
        return memoryDao.getFactsForPromptWithScope(limit)
    }
    
    /**
     * Форматирует факты для промпта с учётом scope.
     */
    suspend fun formatFactsForPromptWithScope(context: String = ""): String {
        val facts = getFactsForPromptWithScope(context)
        if (facts.isEmpty()) return ""
        
        val groupedByScope = facts.groupBy { it.scope }
        
        return buildString {
            append("\n\n[Контекст из памяти]\n")
            
            // AI факты
            groupedByScope[Scopes.AI]?.let { aiFacts ->
                append("\n🤖 **Информация об AI:**\n")
                aiFacts.forEach { fact ->
                    append("- ${fact.key}: ${fact.value}\n")
                }
            }
            
            // Пользовательские факты
            groupedByScope[Scopes.USER]?.let { userFacts ->
                append("\n👤 **Информация о пользователе:**\n")
                userFacts.forEach { fact ->
                    append("- ${fact.key}: ${fact.value} (${(fact.confidence * 100).toInt()}%)\n")
                }
            }
            
            // Глобальные факты
            groupedByScope[Scopes.GLOBAL]?.let { globalFacts ->
                append("\n🌍 **Общая информация:**\n")
                globalFacts.forEach { fact ->
                    append("- ${fact.key}: ${fact.value}\n")
                }
            }
        }
    }
    
    /**
     * Удаляет все факты с указанным scope (опасно!).
     */
    suspend fun clearScope(scope: String) {
        require(Scopes.isValid(scope)) { "Invalid scope: $scope" }
        val facts = memoryDao.getByScope(scope)
        facts.forEach { fact ->
            memoryDao.deletePermanent(fact.id)
        }
    }
    
    // ==================== MARKDOWN EXPORT ====================
    
    /**
     * Экспортирует память в формат Markdown (для контекста LLM).
     * Формат: разделы по scope (AI/пользователь/глобальное).
     */
    suspend fun exportMemoryForPrompt(): String = withContext(Dispatchers.IO) {
        val aiFacts = memoryDao.getByScope(Scopes.AI)
        val userFacts = memoryDao.getByScope(Scopes.USER)
        val globalFacts = memoryDao.getByScope(Scopes.GLOBAL)
        
        return@withContext buildString {
            // AI информация
            if (aiFacts.isNotEmpty()) {
                append("# 🤖 AI Memory\n\n")
                aiFacts.forEach { fact ->
                    append("- **${fact.key}:** ${fact.value}\n")
                }
                append("\n")
            }
            
            // Пользовательская информация
            if (userFacts.isNotEmpty()) {
                append("# 👤 User Information\n\n")
                userFacts.forEach { fact ->
                    val confidenceStr = if (fact.confidence < 0.9) " (${(fact.confidence * 100).toInt()}% уверенности)" else ""
                    append("- **${fact.key}:** ${fact.value}$confidenceStr\n")
                }
                append("\n")
            }
            
            // Глобальная информация
            if (globalFacts.isNotEmpty()) {
                append("# 🌍 Global Context\n\n")
                globalFacts.forEach { fact ->
                    append("- **${fact.key}:** ${fact.value}\n")
                }
            }
            
            // Если память пуста
            if (aiFacts.isEmpty() && userFacts.isEmpty() && globalFacts.isEmpty()) {
                append("_Память пока пуста. AI будет учиться в процессе диалога._\n")
            }
        }
    }
    
    /**
     * Форматирует факты для промпта AI (расширенная версия с Markdown).
     */
    suspend fun formatFactsForPromptEnhanced(context: String = ""): String {
        val md = exportMemoryForPrompt()
        if (md.isBlank() || md.contains("_Память пока пуста")) {
            return ""
        }
        return "\n\n[КОНТЕКСТ ИЗ ПАМЯТИ]\n\n$md"
    }
    
    // ==================== СЕМАНТИЧЕСКИЙ ПОИСК ====================
    
    /**
     * Маппинг семантических запросов на ключи фактов.
     * Ключи: русские слова/фразы из запросов пользователя
     * Значения: соответствующие ключи фактов в базе данных
     */
    private val semanticMapping = mapOf(
        // ============ ИМЯ ПОЛЬЗОВАТЕЛЯ ============
        "зовут" to "name",
        "имя" to "name",
        "зовут меня" to "name",
        "мое имя" to "name",
        "моё имя" to "name",
        "меня зовут" to "name",
        "как зовут" to "name",
        "какое имя" to "name",
        "как тебя зовут" to "name", // для AI
        "твоё имя" to "name",       // для AI
        "твое имя" to "name",       // для AI
        "представься" to "name",
        "назови себя" to "name",
        
        // ============ ТЕКУЩЕЕ МЕСТОПОЛОЖЕНИЕ ============
        "живу" to "location",
        "местоположение" to "location",
        "город" to "location",
        "проживаю" to "location",
        "сейчас живу" to "location",
        "где живу" to "location",
        "где живешь" to "location",
        "где находишься" to "location",
        "место жительства" to "location",
        "адрес" to "location",
        "дом" to "location",
        "квартира" to "location",
        "страна" to "location",
        "область" to "location",
        "регион" to "location",
        
        // ============ ДАТА РОЖДЕНИЯ ============
        "родился" to "birth_date",
        "родилась" to "birth_date",
        "день рождения" to "birth_date",
        "дата рождения" to "birth_date",
        "когда родился" to "birth_date",
        "когда родилась" to "birth_date",
        "сколько лет" to "birth_date",
        "возраст" to "birth_date",
        "год рождения" to "birth_date",
        "месяц рождения" to "birth_date",
        "число рождения" to "birth_date",
        
        // ============ МЕСТО РОЖДЕНИЯ ============
        "место рождения" to "birth_place",
        "родился в" to "birth_place",
        "родилась в" to "birth_place",
        "где родился" to "birth_place",
        "где родилась" to "birth_place",
        "город рождения" to "birth_place",
        "страна рождения" to "birth_place",
        "родной город" to "birth_place",
        "родная страна" to "birth_place",
        
        // ============ ПРОФЕССИЯ И РАБОТА ============
        "работаю" to "profession",
        "профессия" to "profession",
        "работа" to "profession",
        "специальность" to "profession",
        "чем занимаюсь" to "profession",
        "кем работаю" to "profession",
        "должность" to "profession",
        "профессиональная деятельность" to "profession",
        "карьера" to "profession",
        "компания" to "profession",
        "организация" to "profession",
        "начальник" to "profession",
        "коллеги" to "profession",
        
        // ============ ПРЕДПОЧТЕНИЯ ============
        "люблю" to "favorite",
        "нравится" to "favorite",
        "любимый" to "favorite",
        "любимая" to "favorite",
        "предпочитаю" to "favorite",
        "хобби" to "hobby",
        "увлечения" to "hobby",
        "интересы" to "hobby",
        "увлечение" to "hobby",
        "отдых" to "hobby",
        "развлечения" to "hobby",
        "спорт" to "hobby",
        "музыка" to "favorite_music",
        "фильм" to "favorite_movie",
        "кино" to "favorite_movie",
        "книга" to "favorite_book",
        "еда" to "favorite_food",
        "цвет" to "favorite_color",
        "напиток" to "favorite_drink",
        "игра" to "favorite_game",
        
        // ============ ПРОЕКТЫ ============
        "проекты" to "projects",
        "разрабатываю" to "projects",
        "создаю" to "projects",
        "делаю" to "projects",
        "работаю над" to "projects",
        "занимаюсь" to "projects",
        "идеи" to "projects",
        "планы" to "projects",
        "цели" to "projects",
        "задачи" to "projects",
        "разработка" to "projects",
        "программирование" to "projects",
        "код" to "projects",
        "приложение" to "projects",
        "софт" to "projects",
        "программа" to "projects",
        
        // ============ AI ИНФОРМАЦИЯ ============
        "тебя зовут" to "name",        // AI name
        "твоё имя" to "name",          // AI name
        "твое имя" to "name",          // AI name
        "твоя роль" to "role",         // AI role
        "ты кто" to "role",            // AI role
        "кто ты" to "role",            // AI role
        "твоя функция" to "role",      // AI role
        "твои задачи" to "instructions", // AI instructions
        "твоя цель" to "instructions",   // AI instructions
        "твои инструкции" to "instructions", // AI instructions
        "как ты работаешь" to "instructions", // AI instructions
        "твой характер" to "personality", // AI personality
        "твоя личность" to "personality", // AI personality
        "твой стиль" to "personality",  // AI personality
        "твоё поведение" to "personality", // AI personality
        "твое поведение" to "personality", // AI personality
        
        // ============ КОНТАКТНАЯ ИНФОРМАЦИЯ ============
        "email" to "email",
        "почта" to "email",
        "электронная почта" to "email",
        "адрес почты" to "email",
        "телефон" to "phone",
        "номер телефона" to "phone",
        "мобильный" to "phone",
        "сотовый" to "phone",
        "контакты" to "contacts",
        "связь" to "contacts",
        
        // ============ СЕМЕЙНОЕ ПОЛОЖЕНИЕ ============
        "семейное положение" to "marital_status",
        "женат" to "marital_status",
        "замужем" to "marital_status",
        "холост" to "marital_status",
        "не замужем" to "marital_status",
        "семья" to "marital_status",
        "дети" to "marital_status",
        "ребенок" to "marital_status",
        "детишки" to "marital_status",
        "супруг" to "marital_status",
        "супруга" to "marital_status",
        "муж" to "marital_status",
        "жена" to "marital_status",
        
        // ============ НАВЫКИ И УМЕНИЯ ============
        "навыки" to "skills",
        "умения" to "skills",
        "способности" to "skills",
        "знания" to "skills",
        "опыт" to "skills",
        "компетенции" to "skills",
        "технологии" to "skills",
        "инструменты" to "skills",
        "языки программирования" to "skills",
        "фреймворки" to "skills",
        "библиотеки" to "skills",
        
        // ============ ОБРАЗОВАНИЕ ============
        "образование" to "education",
        "университет" to "education",
        "институт" to "education",
        "школа" to "education",
        "колледж" to "education",
        "курсы" to "education",
        "диплом" to "education",
        "степень" to "education",
        "специализация" to "education",
        
        // ============ ОБЩИЕ ЗАПРОСЫ ============
        // Общие вопросы о пользователе
        "знаешь обо мне" to "user_info",
        "что знаешь" to "user_info",
        "что помнишь" to "user_info",
        "что знаешь обо мне" to "user_info",
        "что помнишь обо мне" to "user_info",
        "расскажи обо мне" to "user_info",
        "расскажи что знаешь" to "user_info",
        "информация обо мне" to "user_info",
        "мои данные" to "user_info",
        "моя информация" to "user_info",
        "про меня" to "user_info",
        "обо мне" to "user_info",
        "моё досье" to "user_info",
        "мое досье" to "user_info",
        "личная информация" to "user_info",
        "персональные данные" to "user_info",
        
        // Общие вопросы об AI
        "что ты умеешь" to "ai_capabilities",
        "твои возможности" to "ai_capabilities",
        "чем можешь помочь" to "ai_capabilities",
        "как ты работаешь" to "ai_instructions",
        "твои функции" to "ai_capabilities",
        "твои задачи" to "ai_instructions",
        
        // Общие вопросы о проектах
        "мои проекты" to "projects",
        "над чем работаю" to "projects",
        "что разрабатываю" to "projects",
        "мои разработки" to "projects",
        "мои приложения" to "projects",
        "мои программы" to "projects",
    )
    
    /**
     * Извлекает ключевые слова из семантического запроса.
     * Возвращает список потенциальных ключей фактов.
     */
    private fun extractKeywordsFromQuery(query: String): List<String> {
        val lowerQuery = query.lowercase()
        val keywords = mutableListOf<String>()
        
        // Логируем исходный запрос
        println("🔍 Анализ семантического запроса: '$query'")
        
        // 1. Ищем прямые совпадения в маппинге
        val foundMappings = mutableListOf<Pair<String, String>>()
        semanticMapping.forEach { (phrase, key) ->
            if (lowerQuery.contains(phrase)) {
                foundMappings.add(phrase to key)
                keywords.add(key)
            }
        }
        
        // Логируем найденные маппинги
        if (foundMappings.isNotEmpty()) {
            println("  📌 Найдены маппинги:")
            foundMappings.forEach { (phrase, key) ->
                println("    '$phrase' → '$key'")
            }
        } else {
            println("  ⚠️ Не найдено прямых маппингов")
        }
        
        // 2. Добавляем сами слова запроса (для общего поиска)
        val stopWords = listOf("как", "где", "что", "когда", "почему", "зачем", "кто", "чей", "чем", "на", "в", "у", "с", "по", "о", "об", "от", "до", "из", "или", "и", "но", "а", "же", "ли", "бы", "то", "же", "вот", "ну", "да", "нет", "не", "ни")
        val words = lowerQuery.split(" ", ",", ".", "?", "!", ":", ";", "-", "—")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length >= 3 && it !in stopWords }
            .take(8)
        
        if (words.isNotEmpty()) {
            println("  📝 Извлечённые слова: $words")
            keywords.addAll(words)
        }
        
        // 3. Убираем дубликаты
        val distinctKeywords = keywords.distinct()
        println("  ✅ Итоговые ключевые слова: $distinctKeywords")
        
        return distinctKeywords
    }
    
    /**
     * Семантический поиск фактов по запросу на естественном языке.
     * Многоуровневый поиск: маппинг → ключи → теги → значения → обычный поиск.
     */
    suspend fun semanticSearch(query: String, limit: Int = 10): List<PermanentMemory> {
        if (query.isBlank()) return emptyList()
        
        println("\n" + "-".repeat(60))
        println("🔍 ЗАПУСК СЕМАНТИЧЕСКОГО ПОИСКА")
        println("Запрос: '$query'")
        println("-".repeat(60))
        
        val keywords = extractKeywordsFromQuery(query)
        
        // ============ ПРОВЕРКА СПЕЦИАЛЬНЫХ КЛЮЧЕЙ ============
        // Если запрос содержит общие вопросы (например, "что ты знаешь обо мне"),
        // возвращаем все факты соответствующего scope
        when {
            keywords.contains("user_info") -> {
                println("🎯 Запрос на общую информацию о пользователе")
                val userFacts = memoryDao.getHighConfidenceFactsByScope("user", limit)
                if (userFacts.isNotEmpty()) {
                    println("✅ Возвращаю ${userFacts.size} фактов о пользователе")
                    return userFacts
                }
            }
            keywords.contains("ai_capabilities") -> {
                println("🎯 Запрос на информацию об AI")
                val aiFacts = memoryDao.getHighConfidenceFactsByScope("ai", limit)
                if (aiFacts.isNotEmpty()) {
                    println("✅ Возвращаю ${aiFacts.size} фактов об AI")
                    return aiFacts
                }
            }
        }
        
        val foundFacts = mutableListOf<PermanentMemory>()
        val foundIds = mutableSetOf<String>()
        
        // Функция для логирования найденных фактов
        fun logFoundFacts(step: String, facts: List<PermanentMemory>) {
            if (facts.isNotEmpty()) {
                println("\n  🔎 $step:")
                facts.forEach { fact ->
                    println("    • ${fact.scope}/${fact.category}/${fact.key} = ${fact.value} (${fact.confidence})")
                }
            }
        }
        
        // ============ ЭТАП 1: Поиск по ключам (точное совпадение) ============
        val keyMatches = mutableListOf<PermanentMemory>()
        for (keyword in keywords) {
            // Точное совпадение ключа
            val exactKeyFacts = memoryDao.getByKey(keyword)
            for (fact in exactKeyFacts) {
                if (fact.id !in foundIds) {
                    keyMatches.add(fact)
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        logFoundFacts("Факты по точному совпадению ключей", keyMatches)
        
        // ============ ЭТАП 2: Поиск по ключам (частичное совпадение) ============
        val partialKeyMatches = mutableListOf<PermanentMemory>()
        for (keyword in keywords) {
            // Частичное совпадение ключа через специализированный метод
            val partialKeyFacts = memoryDao.searchByKey(keyword, limit = 3)
            for (fact in partialKeyFacts) {
                if (fact.id !in foundIds) {
                    partialKeyMatches.add(fact)
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        logFoundFacts("Факты по частичному совпадению ключей", partialKeyMatches)
        
        // ============ ЭТАП 3: Поиск по тегам ============
        val tagMatches = mutableListOf<PermanentMemory>()
        for (keyword in keywords) {
            val tagFacts = memoryDao.findByTag(keyword)
            for (fact in tagFacts) {
                if (fact.id !in foundIds) {
                    tagMatches.add(fact)
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        logFoundFacts("Факты по тегам", tagMatches)
        
        // ============ ЭТАП 4: Поиск по значениям (смысловой поиск) ============
        val valueMatches = mutableListOf<PermanentMemory>()
        if (foundFacts.size < limit / 2) { // Если ещё мало фактов
            for (keyword in keywords) {
                // Ищем факты, где значение содержит ключевое слово через специализированный метод
                val valueFacts = memoryDao.searchByValue(keyword, limit = 2)
                for (fact in valueFacts) {
                    if (foundFacts.size >= limit) break
                    if (fact.id !in foundIds) {
                        valueMatches.add(fact)
                        foundFacts.add(fact)
                        foundIds.add(fact.id)
                    }
                }
            }
        }
        logFoundFacts("Факты по совпадению значений", valueMatches)
        
        // ============ ЭТАП 5: Поиск по категориям ============
        val categoryMatches = mutableListOf<PermanentMemory>()
        if (foundFacts.size < limit) {
            // Преобразуем ключевые слова в возможные категории
            val possibleCategories = keywords.map { it.replace("_", " ") }
            
            for (category in possibleCategories) {
                val categoryFacts = memoryDao.getByCategory(category)
                for (fact in categoryFacts) {
                    if (foundFacts.size >= limit) break
                    if (fact.id !in foundIds) {
                        categoryMatches.add(fact)
                        foundFacts.add(fact)
                        foundIds.add(fact.id)
                    }
                }
            }
        }
        logFoundFacts("Факты по категориям", categoryMatches)
        
        // ============ ЭТАП 6: Общий текстовый поиск (fallback) ============
        val textMatches = mutableListOf<PermanentMemory>()
        if (foundFacts.isEmpty()) {
            println("\n  ⚠️ Не найдено фактов по семантическому маппингу, использую обычный поиск")
            val fallbackFacts = memoryDao.searchPermanent(query, limit)
            for (fact in fallbackFacts) {
                if (fact.id !in foundIds) {
                    textMatches.add(fact)
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
            logFoundFacts("Факты из обычного поиска", textMatches)
        }
        
        // ============ ИТОГИ ============
        println("\n" + "-".repeat(60))
        println("📊 РЕЗУЛЬТАТЫ ПОИСКА")
        println("Найдено фактов: ${foundFacts.size}")
        
        if (foundFacts.isNotEmpty()) {
            val byScope = foundFacts.groupBy { it.scope }
            byScope.forEach { (scope, facts) ->
                val scopeName = when (scope) {
                    Scopes.AI -> "AI"
                    Scopes.USER -> "Пользователь"
                    Scopes.GLOBAL -> "Глобальное"
                    else -> scope
                }
                println("  $scopeName: ${facts.size} фактов")
            }
        }
        
        println("-".repeat(60) + "\n")
        
        return foundFacts.take(limit)
    }
    
    /**
     * Семантический поиск с AI-анализом запроса.
     * Основной метод для определения релевантных фактов.
     */
    suspend fun semanticSearchAI(query: String, limit: Int = 10): List<PermanentMemory> {
        if (query.isBlank()) return emptyList()
        
        println("\n" + "-".repeat(60))
        println("🧠 AI-СЕМАНТИЧЕСКИЙ ПОИСК")
        println("Запрос: '$query'")
        println("-".repeat(60))
        
        // 1. AI-анализ запроса
        val analysis = queryAnalyzer.analyzeQuery(query)
        println("📊 Результат AI-анализа:")
        println("  • Ключи: ${analysis.suggestedKeys}")
        println("  • Категории: ${analysis.suggestedCategories}")
        println("  • Scope: ${analysis.suggestedScope}")
        println("  • Уверенность: ${analysis.confidence}")
        
        // 2. Если анализ имеет низкую уверенность, используем fallback
        if (analysis.confidence < 0.3 || analysis.suggestedKeys.isEmpty()) {
            println("⚠️ Низкая уверенность AI-анализа, использую гибридный поиск")
            return semanticSearchHybrid(query, limit)
        }
        
        val foundFacts = mutableListOf<PermanentMemory>()
        val foundIds = mutableSetOf<String>()
        
        // Функция для логирования найденных фактов
        fun logFoundFacts(step: String, facts: List<PermanentMemory>) {
            if (facts.isNotEmpty()) {
                println("\n  🔎 $step:")
                facts.forEach { fact ->
                    println("    • ${fact.scope}/${fact.category}/${fact.key} = ${fact.value} (${fact.confidence})")
                }
            }
        }
        
        // 3. Поиск по suggestedKeys (точное совпадение)
        val exactKeyMatches = mutableListOf<PermanentMemory>()
        for (key in analysis.suggestedKeys) {
            val facts = memoryDao.getByKey(key)
            for (fact in facts) {
                if (fact.id !in foundIds) {
                    exactKeyMatches.add(fact)
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        logFoundFacts("Факты по точному совпадению ключей (AI)", exactKeyMatches)
        
        // 4. Поиск по suggestedKeys (частичное совпадение)
        val partialKeyMatches = mutableListOf<PermanentMemory>()
        for (key in analysis.suggestedKeys) {
            val facts = memoryDao.searchByKey(key, limit = 3)
            for (fact in facts) {
                if (fact.id !in foundIds) {
                    partialKeyMatches.add(fact)
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        logFoundFacts("Факты по частичному совпадению ключей (AI)", partialKeyMatches)
        
        // 5. Поиск по suggestedCategories
        val categoryMatches = mutableListOf<PermanentMemory>()
        for (category in analysis.suggestedCategories) {
            val facts = memoryDao.getByCategory(category)
            for (fact in facts) {
                if (foundFacts.size >= limit) break
                if (fact.id !in foundIds) {
                    categoryMatches.add(fact)
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        logFoundFacts("Факты по категориям (AI)", categoryMatches)
        
        // 6. Поиск по тегам (используем ключи как теги)
        val tagMatches = mutableListOf<PermanentMemory>()
        for (key in analysis.suggestedKeys) {
            val facts = memoryDao.findByTag(key)
            for (fact in facts) {
                if (foundFacts.size >= limit) break
                if (fact.id !in foundIds) {
                    tagMatches.add(fact)
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        logFoundFacts("Факты по тегам (AI)", tagMatches)
        
        // 7. Если нашли мало фактов, добавляем факты по scope
        val scopeMatches = mutableListOf<PermanentMemory>()
        if (foundFacts.size < limit / 2) {
            val scopeFacts = memoryDao.getHighConfidenceFactsByScope(analysis.suggestedScope, limit = limit - foundFacts.size)
            for (fact in scopeFacts) {
                if (foundFacts.size >= limit) break
                if (fact.id !in foundIds) {
                    scopeMatches.add(fact)
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        logFoundFacts("Факты по scope (AI)", scopeMatches)
        
        // 8. Если всё ещё мало фактов, используем старый семантический поиск как fallback
        val fallbackMatches = mutableListOf<PermanentMemory>()
        if (foundFacts.isEmpty()) {
            println("⚠️ AI-поиск не нашёл фактов, использую гибридный поиск")
            return semanticSearchHybrid(query, limit)
        }
        
        println("\n" + "-".repeat(60))
        println("📊 РЕЗУЛЬТАТЫ AI-ПОИСКА")
        println("Найдено фактов: ${foundFacts.size}")
        
        if (foundFacts.isNotEmpty()) {
            val byScope = foundFacts.groupBy { it.scope }
            byScope.forEach { (scope, facts) ->
                val scopeName = when (scope) {
                    Scopes.AI -> "AI"
                    Scopes.USER -> "Пользователь"
                    Scopes.GLOBAL -> "Глобальное"
                    else -> scope
                }
                println("  $scopeName: ${facts.size} фактов")
            }
        }
        
        println("-".repeat(60) + "\n")
        
        return foundFacts.take(limit)
    }
    
    /**
     * Гибридный поиск: AI-анализ + маппинг.
     * Используется как fallback когда AI-анализ имеет низкую уверенность.
     */
    private suspend fun semanticSearchHybrid(query: String, limit: Int = 10): List<PermanentMemory> {
        println("🔄 Запуск гибридного поиска для запроса: '$query'")
        
        // 1. Пробуем AI-анализ
        val analysis = queryAnalyzer.analyzeQuery(query)
        val aiKeys = analysis.suggestedKeys
        val aiCategories = analysis.suggestedCategories
        
        // 2. Пробуем маппинг
        val mappingKeywords = extractKeywordsFromQuery(query)
        val mappingKeys = mappingKeywords.filter { it.length > 2 }
        
        // 3. Объединяем ключи из обоих подходов
        val allKeys = (aiKeys + mappingKeys).distinct()
        val allCategories = aiCategories.distinct()
        
        println("  🤝 Объединённые ключи: $allKeys")
        println("  🤝 Объединённые категории: $allCategories")
        
        if (allKeys.isEmpty() && allCategories.isEmpty()) {
            println("  ⚠️ Ни AI, ни маппинг не нашли ключей, использую обычный поиск")
            return memoryDao.searchPermanent(query, limit)
        }
        
        val foundFacts = mutableListOf<PermanentMemory>()
        val foundIds = mutableSetOf<String>()
        
        // 4. Поиск по объединённым ключам
        for (key in allKeys) {
            if (foundFacts.size >= limit) break
            
            // Точное совпадение ключей
            val exactFacts = memoryDao.getByKey(key)
            for (fact in exactFacts) {
                if (fact.id !in foundIds) {
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
            
            // Частичное совпадение ключей
            val partialFacts = memoryDao.searchByKey(key, limit = 2)
            for (fact in partialFacts) {
                if (foundFacts.size >= limit) break
                if (fact.id !in foundIds) {
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        
        // 5. Поиск по категориям
        for (category in allCategories) {
            if (foundFacts.size >= limit) break
            
            val categoryFacts = memoryDao.getByCategory(category)
            for (fact in categoryFacts) {
                if (foundFacts.size >= limit) break
                if (fact.id !in foundIds) {
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        
        // 6. Если всё ещё мало фактов, обычный поиск
        if (foundFacts.size < limit / 3) {
            val fallbackFacts = memoryDao.searchPermanent(query, limit - foundFacts.size)
            for (fact in fallbackFacts) {
                if (foundFacts.size >= limit) break
                if (fact.id !in foundIds) {
                    foundFacts.add(fact)
                    foundIds.add(fact.id)
                }
            }
        }
        
        println("  ✅ Гибридный поиск нашёл ${foundFacts.size} фактов")
        return foundFacts.take(limit)
    }
    
    /**
     * Форматирует релевантные факты для промпта с учётом семантического поиска.
     */
    suspend fun formatSemanticFactsForPrompt(query: String = ""): String {
        if (query.isBlank()) {
            return formatFactsForPromptWithScope()
        }
        
        println("\n" + "-".repeat(60))
        println("🧠 ФОРМИРОВАНИЕ КОНТЕКСТА ДЛЯ AI")
        println("Запрос пользователя: '$query'")
        println("-".repeat(60))
        
        val facts = semanticSearchAI(query, limit = 5)
        
        if (facts.isEmpty()) {
            println("⚠️ Не найдено фактов для запроса, использую общий контекст")
            val fallbackContext = formatFactsForPromptWithScope(query)
            println("📋 Fallback контекст: ${if (fallbackContext.isNotBlank()) "есть" else "пусто"}")
            println("-".repeat(60) + "\n")
            return fallbackContext
        }
        
        // Логируем найденные факты
        println("✅ Найдено ${facts.size} релевантных фактов:")
        facts.forEachIndexed { index, fact ->
            println("  ${index + 1}. [${fact.scope}/${fact.category}] ${fact.key} = ${fact.value} (${(fact.confidence * 100).toInt()}%)")
        }
        
        // Группируем найденные факты по scope для красивого вывода
        val groupedByScope = facts.groupBy { it.scope }
        
        val result = buildString {
            append("\n\n[Релевантный контекст из памяти]\n")
            
            // AI факты
            groupedByScope[Scopes.AI]?.let { aiFacts ->
                append("\n🤖 **Информация об AI:**\n")
                aiFacts.forEach { fact ->
                    append("- ${fact.key}: ${fact.value}\n")
                }
            }
            
            // Пользовательские факты
            groupedByScope[Scopes.USER]?.let { userFacts ->
                append("\n👤 **Информация о пользователе:**\n")
                userFacts.forEach { fact ->
                    val confidenceStr = if (fact.confidence < 0.9) " (${(fact.confidence * 100).toInt()}%)" else ""
                    append("- ${fact.key}: ${fact.value}$confidenceStr\n")
                }
            }
            
            // Глобальные факты
            groupedByScope[Scopes.GLOBAL]?.let { globalFacts ->
                append("\n🌍 **Общая информация:**\n")
                globalFacts.forEach { fact ->
                    append("- ${fact.key}: ${fact.value}\n")
                }
            }
            
            // Если фактов мало, добавляем дополнительные из общего поиска
            if (facts.size < 3) {
                val additionalFacts = memoryDao.searchPermanent(query, limit = 3)
                    .filter { it.id !in facts.map { f -> f.id } }
                
                if (additionalFacts.isNotEmpty()) {
                    println("➕ Добавлено ${additionalFacts.size} дополнительных фактов из общего поиска")
                    append("\n📚 **Дополнительная информация:**\n")
                    additionalFacts.take(2).forEach { fact ->
                        append("- ${fact.key}: ${fact.value}\n")
                    }
                }
            }
        }
        
        println("\n📝 Сформированный контекст:")
        println(result)
        println("-".repeat(60) + "\n")
        
        return result
    }
    
    /**
     * Сохраняет память в файл (для отладки и ручного редактирования).
     */
    suspend fun exportMemoryToFile(context: android.content.Context) {
        // TODO: Реализовать сохранение в файл
        // val md = exportMemoryToMarkdown()
        // val file = File(context.filesDir, "memory_export.md")
        // file.writeText(md)
    }
    
    // ============= ПОИСК В ДНЕВНОЙ ПАМЯТИ (TEMPORAL QUERIES) =============
    
    /**
     * Выполняет поиск в дневной памяти для временных запросов.
     * Возвращает готовый ответ для пользователя.
     */
    suspend fun searchTemporalMemory(query: String): String {
        println("\n" + "=".repeat(60))
        println("📅 ВРЕМЕННОЙ ПОИСК В ДНЕВНОЙ ПАМЯТИ")
        println("Запрос: '$query'")
        println("=".repeat(60))
        
        try {
            // 1. Анализируем запрос через QueryAnalyzer
            val analysis = queryAnalyzer.analyzeQuery(query)
            println("📊 Результат анализа запроса:")
            println("  • Suggested scope: ${analysis.suggestedScope}")
            println("  • Suggested keys: ${analysis.suggestedKeys}")
            println("  • Confidence: ${analysis.confidence}")
            
            // 2. Если анализ предлагает daily scope или есть временные указания
            val isTemporalQuery = analysis.suggestedScope == "daily" || 
                                 analysis.suggestedKeys.contains("daily_search") ||
                                 analysis.suggestedCategories.contains("temporal_queries")
            
            if (!isTemporalQuery && analysis.confidence < 0.5) {
                println("⚠️ Запрос не распознан как временной, пропускаем дневной поиск")
                return ""
            }
            
            // 3. Выполняем поиск в дневной памяти
            val searchResult = dailyMemorySearchService.searchDailyMemory(query)
            
            // 4. Форматируем результат
            return if (searchResult.found) {
                val formatted = searchResult.formatForDisplay()
                println("✅ Результат поиска в дневной памяти (${formatted.length} символов):")
                println(formatted.take(200) + if (formatted.length > 200) "..." else "")
                formatted
            } else {
                // Если не найдено, возвращаем пустую строку (будет использован обычный поиск)
                println("📭 Информация не найдена в дневной памяти")
                ""
            }
            
        } catch (e: Exception) {
            println("⚠️ Ошибка поиска в дневной памяти: ${e.message}")
            e.printStackTrace()
            return ""
        }
    }
    
    // ============= ПОИСК ФАКТОВ =============
    
    /**
     * Ищет факты по запросу (ключ, значение, теги).
     */
    suspend fun searchPermanentFacts(query: String, limit: Int = 50): List<PermanentMemory> {
        return memoryDao.searchPermanent(query, limit)
    }
    
    /**
     * Ищет факты в конкретном scope.
     */
    suspend fun searchFactsInScope(scope: String, query: String, limit: Int = 50): List<PermanentMemory> {
        return memoryDao.searchPermanentInScope(scope, query, limit)
    }
    
    /**
     * Ищет релевантные факты по запросу (во всех scope).
     */
    suspend fun searchRelevantFacts(query: String, limit: Int = 25): List<PermanentMemory> {
        return searchPermanentFacts(query, limit)
    }
    
    /**
     * Получает факт по категории и ключу.
     */
    suspend fun getFactByCategoryAndKey(category: String, key: String): PermanentMemory? {
        return memoryDao.getByCategoryAndKey(category, key)
    }
    
    /**
     * Вставляет факт в постоянную память (для импорта).
     */
    suspend fun insertPermanentFact(fact: PermanentMemory) {
        memoryDao.insertPermanent(fact)
    }
    
    /**
     * Вставляет дневную запись (для импорта).
     */
    suspend fun insertDailyMemory(daily: DailyMemory) {
        memoryDao.insertDaily(daily)
    }
    
    // ============= ЭКСПОРТ/ИМПОРТ =============
    
    /**
     * Экспортирует всю память в формат Markdown.
     */
    suspend fun exportMemoryToMarkdown(): String {
        val exporter = MemoryExporter()
        val facts = getAllPermanentFacts()
        val dailyEntries = getRecentDailyMemory(limit = 1000) // Все записи
        return exporter.exportToMarkdown(facts, dailyEntries)
    }
    
    /**
     * Импортирует память из Markdown строки.
     */
    suspend fun importMemoryFromMarkdown(
        markdown: String,
        mergeStrategy: ImportMergeStrategy = ImportMergeStrategy.MERGE
    ): MemoryImporter.ImportResult {
        val importer = MemoryImporter()
        val result = importer.importFromMarkdown(markdown)
        
        if (result.facts.isNotEmpty()) {
            when (mergeStrategy) {
                ImportMergeStrategy.MERGE -> {
                    // Добавляем новые факты, обновляем существующие по ключу
                    result.facts.forEach { fact ->
                        val existing = getFactsByKey(fact.key, fact.scope)
                        if (existing.isEmpty()) {
                            insertPermanentFact(fact)
                        } else {
                            // Можно обновить confidence, если нужно
                            // Пока просто пропускаем дубликаты
                        }
                    }
                }
                ImportMergeStrategy.REPLACE -> {
                    // Удаляем все факты и вставляем новые
                    // Для простоты пока не реализуем полную замену
                    result.facts.forEach { fact ->
                        insertPermanentFact(fact)
                    }
                }
            }
        }
        
        if (result.dailyEntries.isNotEmpty()) {
            result.dailyEntries.forEach { daily ->
                insertDailyMemory(daily)
            }
        }
        
        return result
    }
    
    /**
     * Получает все факты (без ограничений).
     */
    private suspend fun getAllPermanentFacts(): List<PermanentMemory> {
        return memoryDao.getAllPermanent()
    }
    
    /**
     * Получает факты по ключу и scope.
     */
    private suspend fun getFactsByKey(key: String, scope: String): List<PermanentMemory> {
        return memoryDao.getByKeyAndScope(key, scope)
    }
}

/**
 * Стратегия импорта памяти.
 */
enum class ImportMergeStrategy {
    /**
     * Объединить с существующими данными (по умолчанию).
     */
    MERGE,
    
    /**
     * Заменить существующие данные.
     */
    REPLACE
}