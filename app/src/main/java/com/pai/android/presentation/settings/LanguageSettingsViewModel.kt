package com.pai.android.presentation.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.pai.android.agent.tools.LocaleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel для управления языком приложения.
 */
@HiltViewModel
class LanguageSettingsViewModel @Inject constructor() : ViewModel() {

    private val localeManager by lazy {
        com.pai.android.PaiApplication.instance.let {
            LocaleManager(it)
        }
    }

    /**
     * Текущий код языка.
     */
    fun getCurrentLang(): String = localeManager.getCurrentLang()

    /**
     * Установить язык и пересоздать Activity для применения.
     */
    fun setLanguage(lang: String, activity: Activity) {
        localeManager.setCurrentLang(lang)
        activity.recreate()
    }
}
