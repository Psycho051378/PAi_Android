package com.pai.android.agent.skills.home.device

/**
 * Маппер названий цветов в RGB.
 * Поддерживает русские и английские названия.
 */
object ColorNameMapper {

    private val colorMap = mapOf(
        // Русские
        "красный" to Triple(255, 0, 0),
        "ярко-красный" to Triple(255, 0, 0),
        "тёмно-красный" to Triple(139, 0, 0),
        "бордовый" to Triple(128, 0, 0),
        "малиновый" to Triple(220, 20, 60),
        "розовый" to Triple(255, 192, 203),
        "тёмно-розовый" to Triple(255, 105, 180),
        "оранжевый" to Triple(255, 165, 0),
        "тёмно-оранжевый" to Triple(255, 140, 0),
        "жёлтый" to Triple(255, 255, 0),
        "светло-жёлтый" to Triple(255, 255, 224),
        "золотой" to Triple(255, 215, 0),
        "зелёный" to Triple(0, 255, 0),
        "зеленый" to Triple(0, 255, 0),
        "ярко-зелёный" to Triple(0, 255, 0),
        "ярко-зеленый" to Triple(0, 255, 0),
        "тёмно-зелёный" to Triple(0, 100, 0),
        "тёмно-зеленый" to Triple(0, 100, 0),
        "салатовый" to Triple(124, 252, 0),
        "изумрудный" to Triple(80, 200, 120),
        "голубой" to Triple(173, 216, 230),
        "синий" to Triple(0, 0, 255),
        "тёмно-синий" to Triple(0, 0, 139),
        "светло-синий" to Triple(173, 216, 230),
        "фиолетовый" to Triple(128, 0, 128),
        "пурпурный" to Triple(255, 0, 255),
        "сиреневый" to Triple(200, 162, 200),
        "белый" to Triple(255, 255, 255),
        "тёплый белый" to Triple(255, 245, 230),
        "тёплый" to Triple(255, 245, 230),
        "холодный белый" to Triple(235, 245, 255),
        "холодный" to Triple(235, 245, 255),
        "серый" to Triple(128, 128, 128),
        "коричневый" to Triple(165, 42, 42),
        "бирюзовый" to Triple(64, 224, 208),
        "лавандовый" to Triple(230, 230, 250),

        // English
        "red" to Triple(255, 0, 0),
        "dark red" to Triple(139, 0, 0),
        "crimson" to Triple(220, 20, 60),
        "pink" to Triple(255, 192, 203),
        "hot pink" to Triple(255, 105, 180),
        "orange" to Triple(255, 165, 0),
        "dark orange" to Triple(255, 140, 0),
        "yellow" to Triple(255, 255, 0),
        "gold" to Triple(255, 215, 0),
        "green" to Triple(0, 255, 0),
        "lime" to Triple(0, 255, 0),
        "dark green" to Triple(0, 100, 0),
        "emerald" to Triple(80, 200, 120),
        "cyan" to Triple(0, 255, 255),
        "blue" to Triple(0, 0, 255),
        "dark blue" to Triple(0, 0, 139),
        "light blue" to Triple(173, 216, 230),
        "purple" to Triple(128, 0, 128),
        "magenta" to Triple(255, 0, 255),
        "violet" to Triple(238, 130, 238),
        "white" to Triple(255, 255, 255),
        "warm white" to Triple(255, 245, 230),
        "warm" to Triple(255, 245, 230),
        "cool white" to Triple(235, 245, 255),
        "cool" to Triple(235, 245, 255),
        "gray" to Triple(128, 128, 128),
        "grey" to Triple(128, 128, 128),
        "brown" to Triple(165, 42, 42),
        "turquoise" to Triple(64, 224, 208),
        "lavender" to Triple(230, 230, 250)
    )

    /**
     * Преобразовать название цвета в RGB.
     * @param colorName название цвета (например "зелёный", "синий", "warm white")
     * @return Triple(r, g, b) или null, если цвет не найден
     */
    fun toRgb(colorName: String): Triple<Int, Int, Int>? {
        val normalized = colorName.lowercase().trim()
        // Прямой поиск
        colorMap[normalized]?.let { return it }
        // Поиск по частичному совпадению
        return colorMap.entries.find { (key, _) ->
            normalized.contains(key) || key.contains(normalized)
        }?.value
    }

    /**
     * Получить список поддерживаемых цветов (для подсказок).
     */
    fun getSupportedColors(): List<String> {
        return colorMap.keys.distinct().sorted()
    }
}
