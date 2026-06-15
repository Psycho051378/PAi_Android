package com.pai.android.data.model

/**
 * Модель геолокационного контекста устройства.
 * Содержит текущие координаты, адрес и временные метки.
 */
data class LocationContext(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val altitude: Double = 0.0,
    val provider: String = "",          // "gps", "network", "fused"
    val address: String = "",           // Обратный геокод: город, улица
    val city: String = "",
    val country: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isFresh: Boolean = false        // данные получены менее 5 минут назад
) {
    /**
     * Возвращает строковое представление для контекста агента.
     */
    fun toContextString(): String {
        val freshness = if (isFresh) "свежие" else "устаревшие (>5 мин)"
        return buildString {
            appendLine("📍 Местоположение ($freshness):")
            if (address.isNotBlank()) appendLine("   Адрес: $address")
            if (city.isNotBlank()) appendLine("   Город: $city")
            if (latitude != 0.0 || longitude != 0.0) {
                appendLine("   Координаты: $latitude, $longitude")
            }
            if (accuracy > 0) appendLine("   Точность: ${accuracy.toInt()} м")
        }.trimEnd()
    }

    /**
     * Короткое представление для быстрого использования.
     */
    fun toShortString(): String {
        return if (city.isNotBlank()) city
        else if (latitude != 0.0 || longitude != 0.0) "$latitude, $longitude"
        else "неизвестно"
    }

    companion object {
        /** Порог свежести: 5 минут */
        const val FRESHNESS_THRESHOLD_MS = 5 * 60 * 1000L
    }
}
