package com.pai.android.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.model.ThemeMode
import com.pai.android.data.repository.ThemePreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние экрана настроек темы.
 */
data class ThemeSettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = false,
    val useModernColors: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * ViewModel для управления настройками темы.
 */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val themeRepository: ThemePreferencesRepository
) : ViewModel() {

    /**
     * Состояние настроек темы.
     */
    val state: StateFlow<ThemeSettingsState> = combine(
        themeRepository.themeMode,
        themeRepository.useDynamicColor
    ) { themeMode, useDynamicColor ->
        ThemeSettingsState(
            themeMode = themeMode,
            useDynamicColor = useDynamicColor,
            useModernColors = true, // По умолчанию используем современные цвета
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ThemeSettingsState(isLoading = true)
    )

    /**
     * Устанавливает режим темы.
     */
    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            try {
                themeRepository.setThemeMode(themeMode)
            } catch (e: Exception) {
                // Ошибка логируется, но не показывается пользователю
                // чтобы не прерывать UX
                println("❌ Ошибка при сохранении темы: ${e.message}")
            }
        }
    }

    /**
     * Устанавливает использование динамических цветов (Material You).
     */
    fun setUseDynamicColor(useDynamicColor: Boolean) {
        viewModelScope.launch {
            try {
                themeRepository.setUseDynamicColor(useDynamicColor)
            } catch (e: Exception) {
                println("❌ Ошибка при сохранении настройки динамических цветов: ${e.message}")
            }
        }
    }

    /**
     * Сбрасывает настройки темы к значениям по умолчанию.
     */
    fun resetToDefaults() {
        viewModelScope.launch {
            try {
                themeRepository.resetToDefaults()
            } catch (e: Exception) {
                println("❌ Ошибка при сбросе настроек темы: ${e.message}")
            }
        }
    }

    /**
     * Получает текущий режим темы для немедленного использования.
     */
    fun getCurrentThemeMode(): ThemeMode {
        return state.value.themeMode
    }

    /**
     * Проверяет, используется ли тёмная тема.
     * Полезно для компонентов, которым нужно знать текущую тему.
     */
    fun isDarkTheme(): Boolean {
        val themeMode = state.value.themeMode
        return when (themeMode) {
            ThemeMode.SYSTEM -> {
                // Для SYSTEM нужно проверять системную настройку
                // Эта функция вызывается из Compose, поэтому мы не можем здесь
                // использовать isSystemInDarkTheme(). Вместо этого возвращаем false
                // и полагаемся на PaiAndroidTheme для правильного определения.
                false
            }
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    }
}