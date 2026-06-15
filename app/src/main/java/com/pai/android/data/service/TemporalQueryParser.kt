package com.pai.android.data.service

import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.text.RegexOption

/**
 * Парсер временных запросов на русском языке.
 * Распознаёт выражения типа "вчера", "15 апреля", "на прошлой неделе".
 */
@Singleton
class TemporalQueryParser @Inject constructor() {
    
    companion object {
        // Форматы дат
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val DISPLAY_FORMAT = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
        private val DAY_MONTH_FORMAT = SimpleDateFormat("d MMMM", Locale("ru"))
        
        // Регулярные выражения для русских дат
        private val RUSSIAN_DATE_REGEX = Pattern.compile(
            "(\\d{1,2})\\s+([а-я]+)(?:\\s+(\\d{4}))?", 
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
        
        // Месяцы по-русски
        private val RUSSIAN_MONTHS = mapOf(
            "января" to 1, "январь" to 1,
            "февраля" to 2, "февраль" to 2,
            "марта" to 3, "март" to 3,
            "апреля" to 4, "апрель" to 4,
            "мая" to 5, "май" to 5,
            "июня" to 6, "июнь" to 6,
            "июля" to 7, "июль" to 7,
            "августа" to 8, "август" to 8,
            "сентября" to 9, "сентябрь" to 9,
            "октября" to 10, "октябрь" to 10,
            "ноября" to 11, "ноябрь" to 11,
            "декабря" to 12, "декабрь" to 12
        )
        
        // Временные выражения
        private val TEMPORAL_EXPRESSIONS = mapOf(
            "вчера" to -1,
            "сегодня" to 0,
            "позавчера" to -2,
            "послезавтра" to 2,
            "завтра" to 1
        )
    }
    
    /**
     * Анализирует запрос и извлекает временную информацию.
     */
    fun parseQuery(query: String): TemporalQueryResult {
        val lowerQuery = query.lowercase(Locale.getDefault())
        
        // 1. Проверяем на точную дату (15 апреля 2026)
        val exactDate = parseExactDate(lowerQuery)
        if (exactDate != null) {
            return TemporalQueryResult(
                date = exactDate,
                dateDisplay = formatDateForDisplay(exactDate),
                isRange = false,
                searchTerm = extractSearchTerm(query, exactDate),
                confidence = 0.95f,
                temporalType = TemporalType.EXACT_DATE
            )
        }
        
        // 2. Проверяем на относительные выражения (вчера, сегодня)
        val relativeDate = parseRelativeDate(lowerQuery)
        if (relativeDate != null) {
            return TemporalQueryResult(
                date = relativeDate,
                dateDisplay = formatDateForDisplay(relativeDate),
                isRange = false,
                searchTerm = extractSearchTerm(query, relativeDate),
                confidence = 0.9f,
                temporalType = TemporalType.RELATIVE_DAY
            )
        }
        
        // 3. Проверяем на диапазоны (на прошлой неделе, в прошлом месяце)
        val dateRange = parseDateRange(lowerQuery)
        if (dateRange != null) {
            return TemporalQueryResult(
                date = null,
                dateDisplay = dateRange.displayName,
                isRange = true,
                startDate = dateRange.startDate,
                endDate = dateRange.endDate,
                searchTerm = extractSearchTermForRange(query, dateRange),
                confidence = 0.85f,
                temporalType = dateRange.type
            )
        }
        
        // 4. Проверяем на месяц (в апреле, в прошлом месяце)
        val monthQuery = parseMonthQuery(lowerQuery)
        if (monthQuery != null) {
            return TemporalQueryResult(
                date = null,
                dateDisplay = monthQuery.displayName,
                isRange = true,
                startDate = monthQuery.startDate,
                endDate = monthQuery.endDate,
                searchTerm = extractSearchTermForMonth(query, monthQuery),
                confidence = 0.8f,
                temporalType = TemporalType.MONTH
            )
        }
        
        // 5. Не нашли временных указаний
        return TemporalQueryResult(
            date = null,
            dateDisplay = null,
            isRange = false,
            searchTerm = query,
            confidence = 0.1f,
            temporalType = TemporalType.NONE
        )
    }
    
    /**
     * Парсит точную дату (15 апреля 2026).
     */
    private fun parseExactDate(query: String): String? {
        val matcher = RUSSIAN_DATE_REGEX.matcher(query)
        
        if (matcher.find()) {
            val day = matcher.group(1)?.toIntOrNull() ?: return null
            val monthName = matcher.group(2)?.lowercase() ?: return null
            val yearStr = matcher.group(3)
            
            val month = RUSSIAN_MONTHS[monthName] ?: return null
            
            val year = yearStr?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
            
            // Проверяем валидность даты
            if (day !in 1..31 || month !in 1..12) return null
            
            val calendar = Calendar.getInstance()
            calendar.set(year, month - 1, day)
            
            // Проверяем, что дата не в будущем (можно и будущие, но для памяти вряд ли)
            val today = Calendar.getInstance()
            if (calendar.after(today)) {
                // Если дата в будущем, может быть ошибка ввода
                return null
            }
            
            return DATE_FORMAT.format(calendar.time)
        }
        
        return null
    }
    
    /**
     * Парсит относительные выражения (вчера, сегодня).
     */
    private fun parseRelativeDate(query: String): String? {
        for ((expression, daysOffset) in TEMPORAL_EXPRESSIONS) {
            if (query.contains(expression)) {
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, daysOffset)
                return DATE_FORMAT.format(calendar.time)
            }
        }
        return null
    }
    
    /**
     * Парсит диапазоны дат (на прошлой неделе, в прошлом месяце, на прошлой неделе).
     */
    private fun parseDateRange(query: String): DateRangeResult? {
        val calendar = Calendar.getInstance()
        
        return when {
            query.contains("на прошлой неделе") || query.contains("прошлую неделю") -> {
                calendar.add(Calendar.WEEK_OF_YEAR, -1)
                val startOfWeek = getStartOfWeek(calendar)
                val endOfWeek = getEndOfWeek(calendar)
                
                DateRangeResult(
                    startDate = DATE_FORMAT.format(startOfWeek.time),
                    endDate = DATE_FORMAT.format(endOfWeek.time),
                    displayName = "прошлая неделя",
                    type = TemporalType.LAST_WEEK
                )
            }
            
            query.contains("на этой неделе") || query.contains("эту неделю") -> {
                val startOfWeek = getStartOfWeek(calendar)
                val endOfWeek = getEndOfWeek(calendar)
                
                DateRangeResult(
                    startDate = DATE_FORMAT.format(startOfWeek.time),
                    endDate = DATE_FORMAT.format(endOfWeek.time),
                    displayName = "эта неделя",
                    type = TemporalType.THIS_WEEK
                )
            }
            
            query.contains("в прошлом месяце") || query.contains("прошлый месяц") -> {
                calendar.add(Calendar.MONTH, -1)
                val startOfMonth = getStartOfMonth(calendar)
                val endOfMonth = getEndOfMonth(calendar)
                
                DateRangeResult(
                    startDate = DATE_FORMAT.format(startOfMonth.time),
                    endDate = DATE_FORMAT.format(endOfMonth.time),
                    displayName = "прошлый месяц",
                    type = TemporalType.LAST_MONTH
                )
            }
            
            query.contains("в этом месяце") || query.contains("этот месяц") -> {
                val startOfMonth = getStartOfMonth(calendar)
                val endOfMonth = getEndOfMonth(calendar)
                
                DateRangeResult(
                    startDate = DATE_FORMAT.format(startOfMonth.time),
                    endDate = DATE_FORMAT.format(endOfMonth.time),
                    displayName = "этот месяц",
                    type = TemporalType.THIS_MONTH
                )
            }
            
            query.contains("в прошлом году") || query.contains("прошлый год") -> {
                calendar.add(Calendar.YEAR, -1)
                val startOfYear = getStartOfYear(calendar)
                val endOfYear = getEndOfYear(calendar)
                
                DateRangeResult(
                    startDate = DATE_FORMAT.format(startOfYear.time),
                    endDate = DATE_FORMAT.format(endOfYear.time),
                    displayName = "прошлый год",
                    type = TemporalType.LAST_YEAR
                )
            }
            
            else -> null
        }
    }
    
    /**
     * Парсит запросы по месяцам (в апреле).
     */
    private fun parseMonthQuery(query: String): MonthQueryResult? {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        for ((monthName, monthNum) in RUSSIAN_MONTHS) {
            if (query.contains("в $monthName") || query.contains("$monthName")) {
                // Определяем год (по умолчанию текущий, если не указан иначе)
                var year = currentYear
                
                // Проверяем, не прошлый ли это месяц (если сейчас начало года, а спрашивают про декабрь)
                val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
                if (monthNum > currentMonth) {
                    year-- // Прошлый год
                }
                
                val calendar = Calendar.getInstance()
                calendar.set(year, monthNum - 1, 1)
                
                val startOfMonth = getStartOfMonth(calendar)
                val endOfMonth = getEndOfMonth(calendar)
                
                val displayName = DISPLAY_FORMAT.format(startOfMonth.time).substringAfter(" ")
                
                return MonthQueryResult(
                    startDate = DATE_FORMAT.format(startOfMonth.time),
                    endDate = DATE_FORMAT.format(endOfMonth.time),
                    displayName = displayName,
                    month = monthNum,
                    year = year
                )
            }
        }
        
        return null
    }
    
    /**
     * Извлекает поисковый термин из запроса (убирая временные указания).
     */
    private fun extractSearchTerm(query: String, date: String): String {
        var cleaned = query
        
        // Удаляем временные выражения
        TEMPORAL_EXPRESSIONS.keys.forEach { expr ->
            cleaned = cleaned.replace(expr, "", ignoreCase = true)
        }
        
        // Удаляем точные даты (15 апреля)
        cleaned = RUSSIAN_DATE_REGEX.matcher(cleaned).replaceAll("")
        
        // Удаляем лишние слова
        val stopWords = listOf(
            "что", "как", "где", "когда", "кто", "вчера", "сегодня", "обсуждали", 
            "говорил", "говорили", "делал", "делали", "ты", "вы", "мы", "вспомни",
            "напомни", "расскажи", "покажи", "найди", "ищи", "поиск"
        )
        
        stopWords.forEach { word ->
            cleaned = cleaned.replace("\\b$word\\b".toRegex(RegexOption.IGNORE_CASE), "")
        }
        
        // Очищаем от лишних пробелов и знаков препинания
        cleaned = cleaned.trim()
            .replace("[\\s\\p{Punct}]+".toRegex(), " ")
            .trim()
        
        return if (cleaned.isBlank()) "обсуждение" else cleaned
    }
    
    /**
     * Извлекает поисковый термин для диапазона.
     */
    private fun extractSearchTermForRange(query: String, range: DateRangeResult): String {
        var cleaned = query
        
        // Удаляем временные диапазоны
        val rangePhrases = listOf(
            "на прошлой неделе", "прошлую неделю",
            "на этой неделе", "эту неделю",
            "в прошлом месяце", "прошлый месяц",
            "в этом месяце", "этот месяц",
            "в прошлом году", "прошлый год"
        )
        
        rangePhrases.forEach { phrase ->
            cleaned = cleaned.replace(phrase, "", ignoreCase = true)
        }
        
        return extractSearchTerm(cleaned, "")
    }
    
    /**
     * Извлекает поисковый термин для месяца.
     */
    private fun extractSearchTermForMonth(query: String, monthQuery: MonthQueryResult): String {
        var cleaned = query
        
        // Удаляем "в апреле" и подобные
        RUSSIAN_MONTHS.keys.forEach { month ->
            cleaned = cleaned.replace("в $month".toRegex(RegexOption.IGNORE_CASE), "")
            cleaned = cleaned.replace(month, "", ignoreCase = true)
        }
        
        return extractSearchTerm(cleaned, "")
    }
    
    /**
     * Форматирует дату для отображения.
     */
    private fun formatDateForDisplay(dateStr: String): String {
        return try {
            val date = DATE_FORMAT.parse(dateStr)
            DISPLAY_FORMAT.format(date)
        } catch (e: Exception) {
            dateStr
        }
    }
    
    /**
     * Вспомогательные методы для работы с календарём.
     */
    private fun getStartOfWeek(calendar: Calendar): Calendar {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }
    
    private fun getEndOfWeek(calendar: Calendar): Calendar {
        val cal = getStartOfWeek(calendar)
        cal.add(Calendar.DAY_OF_WEEK, 6)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal
    }
    
    private fun getStartOfMonth(calendar: Calendar): Calendar {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }
    
    private fun getEndOfMonth(calendar: Calendar): Calendar {
        val cal = getStartOfMonth(calendar)
        cal.add(Calendar.MONTH, 1)
        cal.add(Calendar.DAY_OF_MONTH, -1)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal
    }
    
    private fun getStartOfYear(calendar: Calendar): Calendar {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }
    
    private fun getEndOfYear(calendar: Calendar): Calendar {
        val cal = getStartOfYear(calendar)
        cal.add(Calendar.YEAR, 1)
        cal.add(Calendar.DAY_OF_MONTH, -1)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal
    }
}

/**
 * Результат парсинга временного запроса.
 */
data class TemporalQueryResult(
    val date: String?,                    // Конкретная дата (YYYY-MM-DD)
    val dateDisplay: String?,             // Дата для отображения (15 апреля 2026)
    val isRange: Boolean,                 // Это диапазон дат?
    val startDate: String? = null,        // Начало диапазона
    val endDate: String? = null,          // Конец диапазона
    val searchTerm: String,               // Поисковый термин (без временных указаний)
    val confidence: Float,                // Уверенность в распознавании (0.0-1.0)
    val temporalType: TemporalType        // Тип временного запроса
)

/**
 * Результат распознавания диапазона дат.
 */
data class DateRangeResult(
    val startDate: String,
    val endDate: String,
    val displayName: String,
    val type: TemporalType
)

/**
 * Результат распознавания месяца.
 */
data class MonthQueryResult(
    val startDate: String,
    val endDate: String,
    val displayName: String,
    val month: Int,
    val year: Int
)

/**
 * Типы временных запросов.
 */
enum class TemporalType {
    NONE,           // Без временных указаний
    EXACT_DATE,     // Точная дата (15 апреля)
    RELATIVE_DAY,   // Относительный день (вчера, сегодня)
    LAST_WEEK,      // На прошлой неделе
    THIS_WEEK,      // На этой неделе
    LAST_MONTH,     // В прошлом месяце
    THIS_MONTH,     // В этом месяце
    LAST_YEAR,      // В прошлом году
    MONTH,          // В конкретном месяце (в апреле)
    RANGE           // Произвольный диапазон
}