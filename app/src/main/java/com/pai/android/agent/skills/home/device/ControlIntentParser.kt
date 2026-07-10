package com.pai.android.agent.skills.home.device

/**
 * Разобранная команда управления устройством.
 */
data class ControlIntent(
    val action: String,        // "turn_on", "turn_off", "set_brightness", "set_color_temp", "set_rgb"
    val targetType: String?,   // "LIGHT", "VACUUM", null = любое manageable устройство
    val targetName: String?,   // конкретное имя устройства или null
    val params: Map<String, Any> = emptyMap()  // доп. параметры
)

/**
 * Парсит текстовую команду в ControlIntent.
 * Поддерживает русский и английский.
 */
object ControlIntentParser {

    /**
     * Разобрать запрос пользователя в ControlIntent.
     * Если распознать не удалось — возвращает null.
     */
    fun parse(query: String): ControlIntent? {
        val lower = query.lowercase().trim()

        // Определяем действие
        val action = detectAction(lower) ?: return null

        // Определяем тип устройства
        val targetType = detectTargetType(lower)

        // Определяем имя устройства (если указано)
        val targetName = detectTargetName(lower)

        // Определяем параметры (яркость, температура и т.д.)
        val params = detectParams(lower)

        return ControlIntent(
            action = action,
            targetType = targetType,
            targetName = targetName,
            params = params
        )
    }

    private fun detectAction(query: String): String? {
        return when {
            // Charge / return to base
            query.contains("на зарядк") || query.contains("на базу") ||
            query.contains("заряжайс") || query.contains("зарядить") ||
            query.contains("заряди") || query.contains("заряжа") ||
            query.contains("charge") || query.contains("return to base") ||
            query.contains("go home") || query.contains("dock") ->
                "charge"

            // Get status
            query.contains("статус") || query.contains("сколько заряда") ||
            (query.contains("что с") && query.contains("пылесос")) ||
            query.contains("проверь") || query.contains("status") ||
            query.contains("battery") || query.contains("check") ->
                "get_status"

            // Turn on
            query.contains("включи") || query.contains("включить") ||
            query.startsWith("turn on") || query.startsWith("on") ->
                "turn_on"

            // Turn off
            query.contains("выключи") || query.contains("выключить") ||
            query.startsWith("turn off") || query.contains("switch off") ||
            query.startsWith("off") ->
                "turn_off"

            // Brightness
            query.contains("яркость") || query.contains("ярче") ||
            query.contains("тусклее") || query.contains("%") ||
            query.contains("brightness") || query.contains("dim") ||
            query.contains("percent") ->
                "set_brightness"

            // Color temperature
            query.contains("теплее") || query.contains("холоднее") ||
            query.contains("цветовая температура") ||
            query.contains("color temp") || query.contains("warmer") || query.contains("cooler") ->
                "set_color_temp"

            // RGB
            query.contains("цвет") || query.contains("color") ||
            query.contains("красный") || query.contains("синий") ||
            query.contains("зеленый") || query.contains("жёлтый") ||
            query.contains("set color") || query.contains("rgb") ->
                "set_rgb"

            else -> null
        }
    }

    private fun detectTargetType(query: String): String? {
        return when {
            query.contains("свет") || query.contains("ламп") || query.contains("люстр") ||
            query.contains("light") || query.contains("lamp") || query.contains("bulb") ->
                "LIGHT"

            query.contains("пылесос") || query.contains("vacuum") || query.contains("roborock") ->
                "VACUUM"

            query.contains("увлажнитель") || query.contains("humidifier") ->
                "HUMIDIFIER"

            query.contains("колонк") || query.contains("speaker") || query.contains("яндекс") ||
            query.contains("алиса") || query.contains("alice") || query.contains("yandex") ->
                "SPEAKER"

            query.contains("телевизор") || query.contains("tv") || query.contains("chromecast") ->
                "TV"

            query.contains("роутер") || query.contains("router") ->
                "ROUTER"

            query.contains("компьютер") || query.contains("computer") || query.contains("pc") ||
            query.contains("ноутбук") ->
                "COMPUTER"

            else -> null // любой manageable device
        }
    }

    private fun detectTargetName(query: String): String? {
        // Ищем "лампу 2" → "Lamp 2"
        val patterns = listOf(
            Regex("ламп[уаы]?\\s+(\\d+)", RegexOption.IGNORE_CASE),
            Regex("lamp\\s+(\\d+)", RegexOption.IGNORE_CASE),
            Regex("лампа\\s+(\\S+)", RegexOption.IGNORE_CASE),
            Regex("lamp\\s+(\\S+)", RegexOption.IGNORE_CASE),
            Regex("устройств[оа]\\s+(\\S+)", RegexOption.IGNORE_CASE),
            Regex("device\\s+(\\S+)", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(query)
            if (match != null) {
                val num = match.groupValues[1]
                // Если число — конструируем имя "Lamp N"
                val digit = num.toIntOrNull()
                if (digit != null) {
                    // Определяем префикс по типу
                    val baseName = when {
                        query.contains("ламп") || query.contains("свет") ||
                        query.contains("lamp") || query.contains("light") -> "Lamp"
                        else -> "Device"
                    }
                    return "$baseName $digit"
                }
                return num
            }
        }

        return null
    }

    private fun detectParams(query: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()

        // Яркость: "50%" или "яркость 50" или "уровень 75"
        val brightnessPattern = Regex("(?:яркость|уровень|brightness|dim|percent)\\s*(?:в\\s*)?(\\d+)%?", RegexOption.IGNORE_CASE)
        val match = brightnessPattern.find(query)
        if (match != null) {
            val level = match.groupValues[1].toIntOrNull()
            if (level != null) params["level"] = level.coerceIn(0, 100)
        }

        // Процент в конце: "включи на 50%"
        if (!params.containsKey("level")) {
            val percentPattern = Regex("на\\s+(\\d+)\\s*%", RegexOption.IGNORE_CASE)
            val pMatch = percentPattern.find(query)
            if (pMatch != null) {
                val level = pMatch.groupValues[1].toIntOrNull()
                if (level != null) params["level"] = level.coerceIn(0, 100)
            }
        }

        // Число без единиц как яркость (если в контексте управления светом)
        if (!params.containsKey("level") && (query.contains("свет") || query.contains("lamp") || query.contains("light"))) {
            val numPattern = Regex("(\\d+)")
            val nums = numPattern.findAll(query).map { it.groupValues[1].toIntOrNull() }.filterNotNull().toList()
            val brightness = nums.firstOrNull { it in 1..100 }
            if (brightness != null) params["level"] = brightness
        }

        return params
    }
}
