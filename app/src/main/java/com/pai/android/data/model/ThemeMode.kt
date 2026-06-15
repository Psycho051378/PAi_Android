package com.pai.android.data.model

/**
 * Режимы темы приложения.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        /**
         * Конвертирует строку в ThemeMode.
         * Возвращает SYSTEM по умолчанию, если строка не распознана.
         */
        fun fromString(value: String?): ThemeMode {
            return when (value?.uppercase()) {
                "SYSTEM" -> SYSTEM
                "LIGHT" -> LIGHT
                "DARK" -> DARK
                else -> SYSTEM // Значение по умолчанию
            }
        }
    }
}