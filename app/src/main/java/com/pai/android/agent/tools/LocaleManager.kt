package com.pai.android.agent.tools

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

/**
 * Управление языком приложения.
 * Сохраняет выбор в SharedPreferences и применяет locale к Context.
 */
class LocaleManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Текущий код языка. По умолчанию "ru".
     */
    fun getCurrentLang(): String {
        return prefs.getString(KEY_LANG, DEFAULT_LANG) ?: DEFAULT_LANG
    }

    /**
     * Установить код языка.
     */
    fun setCurrentLang(lang: String) {
        prefs.edit().putString(KEY_LANG, lang).apply()
    }

    /**
     * Применить locale к Configuration.
     * Вызывается в attachBaseContext() или ресурсных обновлениях.
     */
    fun applyLocale(baseContext: Context): Context {
        val lang = getCurrentLang()
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(locale)
        return baseContext.createConfigurationContext(config)
    }

    companion object {
        private const val PREFS_NAME = "locale_prefs"
        private const val KEY_LANG = "app_language"
        private const val DEFAULT_LANG = "ru"

        /**
         * Список поддерживаемых языков.
         * Первый — код языка, второй — отображаемое название.
         */
        val SUPPORTED_LANGUAGES = listOf(
            "ru" to "Русский",
            "en" to "English"
        )
    }
}
