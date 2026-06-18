package com.pai.android.data.detector

import com.pai.android.data.model.Message

/**
 * Экстрактор фактов из сообщений (русский + английский).
 * Извлекает структурированные факты из текста сообщений для сохранения в память.
 */
class FactExtractor {
    
    /**
     * Извлекает факты из сообщения.
     * Возвращает список фактов (категория, ключ, значение, уверенность).
     */
    fun extractFacts(message: Message): List<Fact> {
        val content = message.content
        val lower = content.lowercase()
        val facts = mutableListOf<Fact>()
        
        // 1. Извлечение имени
        extractName(content, lower)?.let { facts.add(it) }
        
        // 2. Извлечение даты рождения
        extractBirthDate(content, lower)?.let { facts.add(it) }
        
        // 3. Извлечение контактов (email, телефон)
        extractContacts(lower)?.let { facts.addAll(it) }
        
        // 4. Извлечение местоположения (адреса, координаты)
        extractLocation(lower)?.let { facts.addAll(it) }
        
        // 5. Извлечение профессии
        extractProfession(lower)?.let { facts.add(it) }
        
        // 6. Извлечение семейного положения
        extractFamilyInfo(lower)?.let { facts.add(it) }
        
        // 7. Извлечение предпочтений
        extractPreferences(lower)?.let { facts.addAll(it) }
        
        // 8. Извлечение информации об AI (если пользователь сообщает о AI)
        extractAiInfo(lower)?.let { facts.addAll(it) }
        
        return facts
    }
    
    /**
     * Extracts name from text (RU + EN).
     */
    private fun extractName(content: String, lower: String): Fact? {
        val patterns = listOf(
            // Русские
            Regex("меня\\s+зовут\\s+([а-яёa-z]{2,})"),
            Regex("я\\s*-?\\s*([а-яёa-z]{2,})"),
            Regex("мо(ё|е)\\s+имя\\s+([а-яёa-z]{2,})"),
            Regex("зовут\\s+меня\\s+([а-яёa-z]{2,})"),
            // Английские
            Regex("my name is\\s+([a-z\\s\\.]{2,40})"),
            Regex("call me\\s+([a-z]{2,})"),
            Regex("i(?:'| a)?m\\s+([a-z]{2,})"),
            Regex("i am\\s+([a-z]{2,})")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val nameGroup = if (pattern.pattern.contains("мо(ё|е)\\s+имя")) 2 else 1
                val name = match.groupValues[nameGroup].replaceFirstChar { it.uppercase() }
                val lowerName = name.lowercase()
                val incorrectNames = listOf("зовут", "родился", "родилась", "я", "живу", "работаю",
                    "запомни", "меня", "мое", "моё", "своё", "свое",
                    "a", "an", "the", "from", "with", "just", "not", "born",
                    "happy", "new", "old", "sure", "glad", "sorry", "hello", "hi")
                if (lowerName in incorrectNames || name.length < 2 ||
                    lowerName.endsWith("лся") || lowerName.endsWith("лась")) {
                    return null
                }
                return Fact(
                    category = "personal_info",
                    key = "name",
                    value = name,
                    confidence = 0.9f,
                    scope = "user"
                )
            }
        }
        return null
    }
    
    /**
     * Extracts birth date (RU + EN).
     */
    private fun extractBirthDate(content: String, lower: String): Fact? {
        val patterns = listOf(
            // Русские
            Regex("родил(ся|ась)\\s+(\\d{1,2})\\s+([а-я]+)\\s+(\\d{4})(?:\\s+года)?"),
            Regex("родил(ся|ась)\\s+(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})"),
            Regex("день рождения\\s+(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})"),
            Regex("родил(ся|ась)\\s+(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4})(?:\\s+года)?"),
            // Английские
            Regex("(?:born|birth)\\s+(\\d{1,2})\\s+(january|february|march|april|may|june|july|august|september|october|november|december)\\s+(\\d{4})"),
            Regex("(?:date of birth|birthday|d\\.o\\.b|dob)\\s*[:=\\-]?\\s*(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})"),
            Regex("(?:born|birth)\\s+(?:on\\s+)?(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
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
     * Normalizes date to DD.MM.YYYY format. Supports RU + EN month names.
     */
    private fun normalizeDate(dateStr: String): String {
        val str = dateStr.trim()
        
        val monthMap = mapOf(
            // Русские (родительный + именительный)
            "января" to "01", "февраля" to "02", "марта" to "03",
            "апреля" to "04", "мая" to "05", "июня" to "06",
            "июля" to "07", "августа" to "08", "сентября" to "09",
            "октября" to "10", "ноября" to "11", "декабря" to "12",
            "январь" to "01", "февраль" to "02", "март" to "03",
            "апрель" to "04", "май" to "05", "июнь" to "06",
            "июль" to "07", "август" to "08", "сентябрь" to "09",
            "октябрь" to "10", "ноябрь" to "11", "декабрь" to "12",
            // Английские
            "january" to "01", "february" to "02", "march" to "03",
            "april" to "04", "may" to "05", "june" to "06",
            "july" to "07", "august" to "08", "september" to "09",
            "october" to "10", "november" to "11", "december" to "12"
        )
        
        // Паттерн: число месяц год (рус/англ)
        val pattern = Regex("(\\d{1,2})\\s+([а-яa-z]+)\\s+(\\d{4})")
        val match = pattern.find(str.lowercase())
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
        
        return str
    }
    
    /**
     * Извлекает контактную информацию (email, phone). RU + EN.
     */
    private fun extractContacts(lower: String): List<Fact>? {
        val facts = mutableListOf<Fact>()
        
        // Email
        val emailPattern = Regex("(?:email|e-mail|почта|mail)\\s*[:=]?\\s*([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})")
        emailPattern.find(lower)?.let { match ->
            facts.add(Fact(
                category = "contacts",
                key = "email",
                value = match.groupValues[1],
                confidence = 0.95f,
                scope = "user"
            ))
        }
        
        // Телефон / Phone (RU + EN)
        val phonePatterns = listOf(
            Regex("телефон\\s*[:=]?\\s*([+]?[0-9\\s\\-\\(\\)]{7,})"),
            Regex("(?:phone|mobile|cell|tel\\.?|telephone)\\s*[:=]?\\s*([+]?[0-9\\s\\-\\(\\)]{7,})")
        )
        for (pattern in phonePatterns) {
            pattern.find(lower)?.let { match ->
                val phone = match.groupValues[1].replace("\\s".toRegex(), "")
                // Avoid matching non-phone data
                if (phone.replace(Regex("[^0-9]"), "").length >= 7) {
                    facts.add(Fact(
                        category = "contacts",
                        key = "phone",
                        value = phone,
                        confidence = 0.9f,
                        scope = "user"
                    ))
                }
            }
        }
        
        return if (facts.isNotEmpty()) facts else null
    }
    
    /**
     * Extracts locations: home/work addresses, coordinates. RU + EN.
     */
    private fun extractLocation(lower: String): List<Fact>? {
        val facts = mutableListOf<Fact>()

        // ---- РУССКИЕ: домашний адрес ----
        val ruHomePatterns = listOf(
            Regex("(?:мой|домашний|мой домашний)\\s*(?:адрес|адреса)\\s*[:\\-=]?\\s*(.+?)(?:\\.|!|\\n|$)"),
            Regex("я\\s+живу\\s+(?:по адресу|на|в)\\s+(.+?)(?:\\.|!|\\n|$)"),
            Regex("проживаю\\s+(?:по адресу|на|в)\\s+(.+?)(?:\\.|!|\\n|$)"),
            Regex("(?:адрес|адреса)\\s*(?:моего|дома)\\s*(?:дома)?\\s*[:\\-=]?\\s*(.+?)(?:\\.|!|\\n|$)"),
            Regex("домашний\\s*(?:адрес|адреса)\\s*[:\\-=]?\\s*(.+?)(?:\\.|!|\\n|$)")
        )
        for (pattern in ruHomePatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val addr = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (addr.length > 3 && !addr.contains("адрес")) {
                    facts.add(Fact("locations", "home_address", addr, 0.9f, "global"))
                    break
                }
            }
        }

        // ---- РУССКИЕ: рабочий адрес ----
        val ruWorkPatterns = listOf(
            Regex("(?:мой|рабочий|мой рабочий)\\s*(?:адрес|адреса)\\s*[:\\-=]?\\s*(.+?)(?:\\.|!|\\n|$)"),
            Regex("работаю\\s*(?:по адресу|на|в|в офисе|в здании)\\s+(.+?)(?:\\.|!|\\n|$)"),
            Regex("офис\\s*(?:находится|расположен)\\s*(?:по адресу|на|в)\\s+(.+?)(?:\\.|!|\\n|$)"),
            Regex("(?:адрес|адреса)\\s*(?:работы|офиса|рабочий)\\s*[:\\-=]?\\s*(.+?)(?:\\.|!|\\n|$)")
        )
        for (pattern in ruWorkPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val addr = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (addr.length > 3 && !addr.contains("адрес")) {
                    facts.add(Fact("locations", "work_address", addr, 0.9f, "global"))
                    break
                }
            }
        }

        // ---- РУССКИЕ: координаты ----
        val ruCoordPatterns = listOf(
            Regex("(?:координаты|координаты дома|координаты работы|gps)\\s*[:\\-=]?\\s*([+-]?\\d{1,3}\\.\\d+)\\s*[,;:/\\s]+\\s*([+-]?\\d{1,3}\\.\\d+)"),
            Regex("\\b([+-]?\\d{1,2}\\.\\d{5,})\\s*,\\s*([+-]?\\d{1,2}\\.\\d{5,})\\b")
        )
        for (pattern in ruCoordPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val lat = match.groupValues[1].trim()
                val lng = match.groupValues[2].trim()
                val coords = "$lat, $lng"
                val textBefore = lower.substring(0, match.range.first).take(60)
                val coordKey = when {
                    textBefore.contains("дом") || textBefore.contains("домаш") -> "home_coordinates"
                    textBefore.contains("работ") || textBefore.contains("офис") || textBefore.contains("служеб") -> "work_coordinates"
                    else -> "coordinates"
                }
                facts.add(Fact("locations", coordKey, coords, 0.95f, "global"))
            }
        }

        // ---- АНГЛИЙСКИЕ: home address ----
        val enHomePatterns = listOf(
            Regex("(?:my|home|my home)\\s*(?:address)\\s*[:\\-=]?\\s*(.+?)(?:\\.|!|\\n|$)"),
            Regex("i live (?:at|on|in)\\s+(.+?)(?:\\.|!|\\n|$)"),
            Regex("i reside (?:at|on|in)\\s+(.+?)(?:\\.|!|\\n|$)"),
            Regex("(?:address|home address)\\s*[:\\-=]?\\s*(.+?)(?:\\.|!|\\n|$)")
        )
        for (pattern in enHomePatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val addr = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (addr.length > 4) {
                    facts.add(Fact("locations", "home_address", addr, 0.9f, "global"))
                    break
                }
            }
        }

        // ---- АНГЛИЙСКИЕ: work address ----
        val enWorkPatterns = listOf(
            Regex("(?:my|work|office|my work|my office)\\s*(?:address)\\s*[:\\-=]?\\s*(.+?)(?:\\.|!|\\n|$)"),
            Regex("i work (?:at|in|on)\\s+(.+?)(?:\\.|!|\\n|$)"),
            Regex("office (?:is (?:at|in|on|located at|located in))\\s+(.+?)(?:\\.|!|\\n|$)")
        )
        for (pattern in enWorkPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val addr = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (addr.length > 4) {
                    facts.add(Fact("locations", "work_address", addr, 0.9f, "global"))
                    break
                }
            }
        }

        // ---- АНГЛИЙСКИЕ: coordinates ----
        val enCoordPatterns = listOf(
            Regex("(?:coordinates|home coordinates|work coordinates|gps)\\s*[:\\-=]?\\s*([+-]?\\d{1,3}\\.\\d+)\\s*[,;:/\\s]+\\s*([+-]?\\d{1,3}\\.\\d+)"),
            Regex("\\b([+-]?\\d{1,2}\\.\\d{5,})\\s*,\\s*([+-]?\\d{1,2}\\.\\d{5,})\\b")
        )
        for (pattern in enCoordPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val lat = match.groupValues[1].trim()
                val lng = match.groupValues[2].trim()
                val coords = "$lat, $lng"
                val textBefore = lower.substring(0, match.range.first).take(60)
                val coordKey = when {
                    textBefore.contains("home") -> "home_coordinates"
                    textBefore.contains("work") || textBefore.contains("office") -> "work_coordinates"
                    else -> "coordinates"
                }
                facts.add(Fact("locations", coordKey, coords, 0.95f, "global"))
            }
        }

        // ---- ОБЩИЕ АНГЛИЙСКИЕ (город/страна) ----
        val alreadyHasAddress = facts.any { 
            it.key.startsWith("home_") || it.key.startsWith("work_")
        }
        if (!alreadyHasAddress) {
            val enGeneral = listOf(
                Regex("i (?:am )?from\\s+([a-z\\s\\-]+)"),
                Regex("i live in\\s+([a-z\\s\\-]+)"),
                Regex("i reside in\\s+([a-z\\s\\-]+)"),
                Regex("\\b(?:in|near)\\s+([a-z\\s\\-]+)")
            )
            for (pattern in enGeneral) {
                val match = pattern.find(lower)
                if (match != null) {
                    val location = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                    facts.add(Fact("personal_info", "location", location, 0.8f, "user"))
                    break
                }
            }
        }

        return if (facts.isNotEmpty()) facts else null
    }
    
    /**
     * Извлекает профессию (RU + EN).
     */
    private fun extractProfession(lower: String): Fact? {
        val patterns = listOf(
            // Русские
            Regex("я\\s+([а-яa-z]+ист|[а-яa-z]+ер|[а-яa-z]+ор)"),
            Regex("работаю\\s+([а-яa-z]+истом|[а-яa-z]+ером|[а-яa-z]+ором)"),
            Regex("моя профессия\\s+-?\\s*([а-яa-z\\s]+)"),
            Regex("занимаюсь\\s+([а-яa-z\\s]+)"),
            // Английские
            Regex("i(?:'| a)?m (?:a|an)\\s+(.{2,30})"),
            Regex("i work as (?:a|an)\\s+(.{2,30})"),
            Regex("my profession is\\s+(.{2,30})"),
            Regex("i(?:'| a)?m a (?:software|web|data|frontend|backend|full.?stack|devops|sysadmin|designer|writer|developer|programmer|engineer|analyst|manager|consultant|teacher|doctor|lawyer|architect|artist|scientist|student|trainee|intern|lead|head|chief|director)")
        )
        
        val skipWords = listOf("a", "an", "the", "just", "not", "here", "there", "with", "from", "sure", "glad", "sorry")
        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val profession = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (profession.length < 2 || profession.length > 30) continue
                if (profession.lowercase() in skipWords) continue
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
     * Извлекает информацию о семье (RU + EN).
     */
    private fun extractFamilyInfo(lower: String): Fact? {
        val patterns = listOf(
            // Русские
            Regex("женат|замужем|холост|не замужем"),
            Regex("(есть|нет)\\s+дет(ей|и)"),
            Regex("живу\\s+(с|одн[оа]|вместе)"),
            Regex("сем(ья|ейное)\\s+положение"),
            // Английские
            Regex("\\b(married|single|divorced|widowed|engaged|in a relationship)\\b"),
            Regex("(?:have|has|got|with|without)\\s+(?:no\\s+)?(?:children|kids|child)\\b"),
            Regex("(?:live|living|stay)\\s+(?:with|alone|together)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(lower)
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
     * Извлекает предпочтения (RU + EN).
     */
    private fun extractPreferences(lower: String): List<Fact>? {
        val facts = mutableListOf<Fact>()
        
        // Фильмы / Movies
        val moviePatterns = listOf(
            Regex("любим(ый|ая)\\s+фильм\\s+[-:]?\\s*\"?([^\"]+)\"?"),
            Regex("нравится\\s+фильм\\s+[-:]?\\s*\"?([^\"]+)\"?"),
            Regex("(?:favorite|favourite|fav)\\s+movie\\s*[:=\\-]?\\s*\"?([^\"]+)\"?"),
            Regex("(?:i like|i love|i enjoy)\\s+(?:watching\\s+)?(?:the\\s+)?(?:movie|film)\\s*[:=\\-]?\\s*\"?([^\"]+)\"?")
        )
        for (pattern in moviePatterns) {
            pattern.find(lower)?.let { match ->
                val movie = match.groupValues[1].trim()
                if (movie.length > 2) {
                    facts.add(Fact("preferences", "favorite_movie", movie, 0.7f, "user"))
                }
            }
        }
        
        // Музыка / Music
        val musicPatterns = listOf(
            Regex("любим(ая|ый)\\s+музык(а|ань|альный)\\s+[-:]?\\s*\"?([^\"]+)\"?"),
            Regex("слушаю\\s+([а-яa-z\\s]+)"),
            Regex("(?:favorite|favourite|fav)\\s+(?:music|genre|band|artist|song)\\s*[:=\\-]?\\s*\"?([^\"]+)\"?"),
            Regex("(?:i like|i love|i listen to)\\s+([a-z\\s]+?)(?:music|bands|artists|songs)")
        )
        for (pattern in musicPatterns) {
            pattern.find(lower)?.let { match ->
                val music = match.groupValues[1].trim()
                if (music.length > 2) {
                    facts.add(Fact("preferences", "favorite_music", music, 0.7f, "user"))
                }
            }
        }
        
        // Хобби / Hobby
        val hobbyPatterns = listOf(
            Regex("хобби\\s+[-:]?\\s*\"?([^\"]+)\"?"),
            Regex("увлекаюсь\\s+([а-яa-z\\s]+)"),
            Regex("люблю\\s+заниматься\\s+([а-яa-z\\s]+)"),
            Regex("(?:my|my favorite|my favourite)\\s+hobby\\s*[:=\\-]?\\s*\"?([^\"]+)\"?"),
            Regex("(?:i like to|i love to|i enjoy)\\s+(.{3,40})")
        )
        for (pattern in hobbyPatterns) {
            pattern.find(lower)?.let { match ->
                val hobby = match.groupValues[1].trim()
                if (hobby.length > 2) {
                    facts.add(Fact("preferences", "hobby", hobby, 0.7f, "user"))
                }
            }
        }
        
        return if (facts.isNotEmpty()) facts else null
    }
    
    /**
     * Extracts AI info from user messages (RU + EN).
     */
    private fun extractAiInfo(lower: String): List<Fact>? {
        val facts = mutableListOf<Fact>()
        
        // AI Name (рус + англ)
        val namePatterns = listOf(
            Regex("тебя\\s+зовут\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("тво(ё|е)\\s+имя\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("называйся\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("будешь\\s+зваться\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("(?:your name is|you are called|call yourself)\\s+([a-z\\s\\-]{2,})"),
            Regex("i (?:will|shall)\\s+call you\\s+([a-z\\s\\-]{2,})"),
            Regex("you are\\s+([a-z]{2,})")
        )
        val skipName = listOf("мой", "моя", "my", "a", "an", "the", "ai", "assistant", "helper")
        for (pattern in namePatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val name = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (name.lowercase() in skipName) continue
                facts.add(Fact("ai_info", "name", name, 0.9f, "ai"))
            }
        }
        
        // AI Role (рус + англ)
        val rolePatterns = listOf(
            Regex("ты\\s+(мой|моя)?\\s*([а-яёa-z\\s\\-]{2,})"),
            Regex("будешь\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("работаешь\\s+как\\s+([а-яёa-z\\s\\-]{2,})"),
            Regex("(?:you are|you will be|you're)\\s+(?:my|our)?\\s*([a-z\\s\\-]{2,})")
        )
        val roleWords = listOf("assistant", "helper", "ai", "chatbot")
        for (pattern in rolePatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val role = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (roleWords.any { role.lowercase().contains(it) }) {
                    facts.add(Fact("ai_info", "role", role, 0.8f, "ai"))
                }
            }
        }
        
        // AI Instructions (рус + англ)
        val instructionPatterns = listOf(
            Regex("запомни\\s+что\\s+([^.!?]+)"),
            Regex("не забывай\\s+что\\s+([^.!?]+)"),
            Regex("всегда\\s+(помни|говори|упоминай)\\s+([^.!?]+)"),
            Regex("(?:remember|remember that|never forget)\\s+(?:that\\s+)?([^.!?]+)"),
            Regex("(?:always|always say|always remember)\\s+([^.!?]+)"),
            Regex("(?:from now on|from now on,)\\s+(?:you\\s+)?(.{5,80})")
        )
        for (pattern in instructionPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val idx = if (pattern.pattern.contains("всегда")) 2 else 1
                val instruction = match.groupValues[idx].trim()
                if (instruction.length > 3) {
                    facts.add(Fact("ai_info", "instruction", instruction, 0.7f, "ai"))
                }
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
