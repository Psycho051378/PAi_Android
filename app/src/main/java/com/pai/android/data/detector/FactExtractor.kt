package com.pai.android.data.detector

import com.pai.android.data.model.Message

/**
 * Экстрактор фактов из сообщений.
 * Извлекает структурированные факты из текста сообщений для сохранения в память.
 */
class FactExtractor {
    
    /**
     * Извлекает факты из сообщения.
     * Возвращает список фактов (категория, ключ, значение, уверенность).
     */
    fun extractFacts(message: Message): List<Fact> {
        val content = message.content.lowercase()
        val facts = mutableListOf<Fact>()
        
        // 1. Извлечение имени
        extractName(content)?.let { facts.add(it) }
        
        // 2. Извлечение даты рождения
        extractBirthDate(content)?.let { facts.add(it) }
        
        // 3. Извлечение контактов (email, телефон)
        extractContacts(content)?.let { facts.addAll(it) }
        
        // 4. Извлечение местоположения
        extractLocation(content)?.let { facts.add(it) }
        
        // 5. Извлечение профессии
        extractProfession(content)?.let { facts.add(it) }
        
        // 6. Извлечение семейного положения
        extractFamilyInfo(content)?.let { facts.add(it) }
        
        // 7. Извлечение предпочтений
        extractPreferences(content)?.let { facts.addAll(it) }
        
        // 8. Извлечение информации об AI (если пользователь сообщает о AI)
        extractAiInfo(content)?.let { facts.addAll(it) }
        
        return facts
    }
    
    /**
     * Extracts name from text.
     * Examples: "My name is John", "I am Alex", "Call me Jane"
     */
    private fun extractName(content: String): Fact? {
        val patterns = listOf(
            Regex("меня\\s+зовут\\s+([а-яёa-z]{2,})"),
            Regex("я\\s*-?\\s*([а-яёa-z]{2,})"),
            Regex("мо(ё|е)\\s+имя\\s+([а-яёa-z]{2,})"),
            Regex("зовут\\s+меня\\s+([а-яёa-z]{2,})")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(content.lowercase())
            if (match != null) {
                // Получаем правильную группу (1 или 2 в зависимости от паттерна)
                val nameGroup = if (pattern.pattern.contains("мо(ё|е)\\s+имя")) 2 else 1
                val name = match.groupValues[nameGroup].replaceFirstChar { it.uppercase() }
                // Фильтрация некорректных имён
                val lowerName = name.lowercase()
                val incorrectNames = listOf("зовут", "родился", "родилась", "я", "живу", "работаю", "запомни", "меня", "мое", "моё", "своё", "свое")
                if (lowerName in incorrectNames || name.length < 2 || lowerName.endsWith("лся") || lowerName.endsWith("лась")) {
                    return null // Не возвращаем некорректное имя
                }
                return Fact(
                    category = "personal_info",
                    key = "name",  // Стандартизируем на английский ключ
                    value = name,
                    confidence = 0.9f,
                    scope = "user"
                )
            }
        }
        return null
    }
    
    /**
     * Extracts birth date.
     * Examples: "Born 15 March 1990", "My birthday is 15.03.1990"
     */
    private fun extractBirthDate(content: String): Fact? {
        val patterns = listOf(
            // "15 March 1990" or "15 March 1990 year"
            Regex("родил(ся|ась)\\s+(\\d{1,2})\\s+([а-я]+)\\s+(\\d{4})(?:\\s+года)?"),
            // "13.05.1978", "13/05/1978", "13-05-1978"
            Regex("родил(ся|ась)\\s+(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})"),
            Regex("день рождения\\s+(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})"),
            // Точные месяцы в родительном падеже с возможным "года"
            Regex("родил(ся|ась)\\s+(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4})(?:\\s+года)?")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                // Определяем формат даты и извлекаем полную строку
                val dateStr = match.value
                val normalized = normalizeDate(dateStr)
                return Fact(
                    category = "personal_info",
                    key = "birth_date",
                    value = normalized,
                    confidence = 0.9f,
                    scope = "user"
                )
            }
        }
        return null
    }
    
    /**
     * Normalizes date to DD.MM.YYYY format.
     * Examples:
     * - "15 March 1990" -> "15.03.1990"
     * - "15.03.1985" -> "15.03.1985"
     */
    private fun normalizeDate(dateStr: String): String {
        val str = dateStr.trim()
        
        // Try to recognize format like "15 March 1990"
        val monthMap = mapOf(
            "января" to "01", "февраля" to "02", "марта" to "03",
            "апреля" to "04", "мая" to "05", "июня" to "06",
            "июля" to "07", "августа" to "08", "сентября" to "09",
            "октября" to "10", "ноября" to "11", "декабря" to "12",
            "январь" to "01", "февраль" to "02", "март" to "03",
            "апрель" to "04", "май" to "05", "июнь" to "06",
            "июль" to "07", "август" to "08", "сентябрь" to "09",
            "октябрь" to "10", "ноябрь" to "11", "декабрь" to "12"
        )
        
        // Паттерн: число месяц год (года)
        val pattern = Regex("(\\d{1,2})\\s+([а-я]+)\\s+(\\d{4})")
        val match = pattern.find(str)
        if (match != null) {
            val day = match.groupValues[1].padStart(2, '0')
            val monthName = match.groupValues[2].lowercase()
            val year = match.groupValues[3]
            
            val monthNum = monthMap[monthName]
            if (monthNum != null) {
                return "$day.$monthNum.$year"
            }
        }
        
        // Паттерн: DD.MM.YYYY, DD/MM/YYYY, DD-MM-YYYY
        val numericPattern = Regex("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})")
        val numericMatch = numericPattern.find(str)
        if (numericMatch != null) {
            val day = numericMatch.groupValues[1].padStart(2, '0')
            val month = numericMatch.groupValues[2].padStart(2, '0')
            val year = numericMatch.groupValues[3]
            return "$day.$month.$year"
        }
        
        // Если не распознали, возвращаем оригинал
        return str
    }
    
    /**
     * Извлекает контактную информацию (email, телефон).
     */
    private fun extractContacts(content: String): List<Fact>? {
        val facts = mutableListOf<Fact>()
        
        // Email
        val emailPattern = Regex("email\\s*[:=]?\\s*([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})")
        emailPattern.find(content)?.let { match ->
            facts.add(Fact(
                category = "contacts",
                key = "email",
                value = match.groupValues[1],
                confidence = 0.95f,
                scope = "user"
            ))
        }
        
        // Телефон
        val phonePattern = Regex("телефон\\s*[:=]?\\s*([+]?[0-9\\s\\-\\(\\)]{7,})")
        phonePattern.find(content)?.let { match ->
            val phone = match.groupValues[1].replace("\\s".toRegex(), "")
            facts.add(Fact(
                category = "contacts",
                key = "phone",
                value = phone,
                confidence = 0.9f,
                scope = "user"
            ))
        }
        
        return if (facts.isNotEmpty()) facts else null
    }
    
    /**
     * Extracts location.
     * Examples: "I live in New York", "From Chicago"
     */
    private fun extractLocation(content: String): Fact? {
        val patterns = listOf(
            Regex("i (?:am )?from\\s+([a-z\\s\\-]+)"),
            Regex("i live in\\s+([a-z\\s\\-]+)"),
            Regex("i reside in\\s+([a-z\\s\\-]+)"),
            Regex("\\b(?:in|near)\\s+([a-z\\s\\-]+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                val location = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                return Fact(
                    category = "personal_info",
                    key = "location",
                    value = location,
                    confidence = 0.8f,
                    scope = "user"
                )
            }
        }
        return null
    }
    
    /**
     * Извлекает профессию.
     * Примеры: "Я программист", "Работаю инженером"
     */
    private fun extractProfession(content: String): Fact? {
        val patterns = listOf(
            Regex("я\\s+([а-яa-z]+ист|[а-яa-z]+ер|[а-яa-z]+ор)"),
            Regex("работаю\\s+([а-яa-z]+истом|[а-яa-z]+ером|[а-яa-z]+ором)"),
            Regex("моя профессия\\s+-?\\s*([а-яa-z\\s]+)"),
            Regex("занимаюсь\\s+([а-яa-z\\s]+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                val profession = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                return Fact(
                    category = "personal_info",
                    key = "profession",
                    value = profession,
                    confidence = 0.8f,
                    scope = "user"
                )
            }
        }
        return null
    }
    
    /**
     * Извлекает информацию о семье.
     * Примеры: "Женат", "Есть дети", "Живу с родителями"
     */
    private fun extractFamilyInfo(content: String): Fact? {
        val patterns = listOf(
            Regex("женат|замужем|холост|не замужем"),
            Regex("(есть|нет)\\s+дет(ей|и)"),
            Regex("живу\\s+(с|одн[оа]|вместе)"),
            Regex("сем(ья|ейное)\\s+положение")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                val value = match.value.trim()
                return Fact(
                    category = "personal_info",
                    key = "marital_status",
                    value = value,
                    confidence = 0.7f,
                    scope = "user"
                )
            }
        }
        return null
    }
    
    /**
     * Извлекает предпочтения (любимые фильмы, музыка, хобби и т.д.).
     */
    private fun extractPreferences(content: String): List<Fact>? {
        val facts = mutableListOf<Fact>()
        
        // Любимые фильмы
        val moviePatterns = listOf(
            Regex("любим(ый|ая)\\s+фильм\\s+[-:]?\\s*\"?([^\"]+)\"?"),
            Regex("нравится\\s+фильм\\s+[-:]?\\s*\"?([^\"]+)\"?")
        )
        
        for (pattern in moviePatterns) {
            pattern.find(content)?.let { match ->
                val movie = match.groupValues[1].trim()
                facts.add(Fact(
                    category = "preferences",
                    key = "favorite_movie",
                    value = movie,
                    confidence = 0.7f,
                    scope = "user"
                ))
            }
        }
        
        // Любимая музыка
        val musicPatterns = listOf(
            Regex("любим(ая|ый)\\s+музык(а|ань|альный)\\s+[-:]?\\s*\"?([^\"]+)\"?"),
            Regex("слушаю\\s+([а-яa-z\\s]+)")
        )
        
        for (pattern in musicPatterns) {
            pattern.find(content)?.let { match ->
                val music = match.groupValues[1].trim()
                facts.add(Fact(
                    category = "preferences",
                    key = "favorite_music",
                    value = music,
                    confidence = 0.7f,
                    scope = "user"
                ))
            }
        }
        
        // Хобби
        val hobbyPatterns = listOf(
            Regex("хобби\\s+[-:]?\\s*\"?([^\"]+)\"?"),
            Regex("увлекаюсь\\s+([а-яa-z\\s]+)"),
            Regex("люблю\\s+заниматься\\s+([а-яa-z\\s]+)")
        )
        
        for (pattern in hobbyPatterns) {
            pattern.find(content)?.let { match ->
                val hobby = match.groupValues[1].trim()
                facts.add(Fact(
                    category = "preferences",
                    key = "hobby",
                    value = hobby,
                    confidence = 0.7f,
                    scope = "user"
                ))
            }
        }
        
        return if (facts.isNotEmpty()) facts else null
    }
    
    /**
     * Extracts AI info from user messages.
     * Examples: "your name is Nova", "you are my assistant"
     */
    private fun extractAiInfo(content: String): List<Fact>? {
        val facts = mutableListOf<Fact>()
        
        // AI Name: "your name is Nova", "you are assistant"
        val namePatterns = listOf(
            Regex("тебя\\s+зовут\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("тво(ё|е)\\s+имя\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("называйся\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("будешь\\s+зваться\\s+([а-яёa-z\\s\\-]{2,})")
        )
        
        for (pattern in namePatterns) {
            val match = pattern.find(content)
            if (match != null) {
                val name = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                facts.add(Fact(
                    category = "ai_info",
                    key = "name",
                    value = name,
                    confidence = 0.9f,
                    scope = "ai"
                ))
            }
        }
        
        // AI Role: "you are my assistant", "you are helper", "you are AI"
        val rolePatterns = listOf(
            Regex("ты\\s+(мой|моя)?\\s*([а-яёa-z\\s\\-]{2,})"),
            Regex("будешь\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("работаешь\\s+как\\s+([а-яёa-z\\s\\-]{2,})")
        )
        
        for (pattern in rolePatterns) {
            val match = pattern.find(content)
            if (match != null) {
                val role = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                // Verify it's actually a role, not a name
                val roleWords = listOf("assistant", "helper", "ai", "chatbot")
                if (roleWords.any { role.lowercase().contains(it) }) {
                    facts.add(Fact(
                        category = "ai_info",
                        key = "role",
                        value = role,
                        confidence = 0.8f,
                        scope = "ai"
                    ))
                }
            }
        }
        
        // AI Instructions: "remember", "don't forget", "always say"
        val instructionPatterns = listOf(
            Regex("запомни\\s+что\\s+([^.!?]+)"),
            Regex("не забывай\\s+что\\s+([^.!?]+)"),
            Regex("всегда\\s+(помни|говори|упоминай)\\s+([^.!?]+)")
        )
        
        for (pattern in instructionPatterns) {
            val match = pattern.find(content)
            if (match != null) {
                val instruction = match.groupValues[1].trim()
                facts.add(Fact(
                    category = "ai_info",
                    key = "instruction",
                    value = instruction,
                    confidence = 0.7f,
                    scope = "ai"
                ))
            }
        }
        
        return if (facts.isNotEmpty()) facts else null
    }
    
    /**
     * Структура факта.
     */
    data class Fact(
        val category: String,
        val key: String,
        val value: String,
        val confidence: Float,
        val scope: String = "user"
    )
}