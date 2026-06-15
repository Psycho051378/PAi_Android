package com.pai.android.ui.theme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.pai.android.data.model.ThemeMode
import com.pai.android.presentation.settings.ThemeViewModel

/**
 * Обёртка, которая применяет тему с учётом пользовательских настроек.
 * Используется в корне приложения для правильного применения темы.
 * Поддерживает плавную анимацию переключения тем (Crossfade).
 */
@Composable
fun ThemeWrapper(
    enableAnimations: Boolean = true,
    content: @Composable () -> Unit
) {
    // Получаем ViewModel для управления темой
    val themeViewModel: ThemeViewModel = hiltViewModel()
    val themeState by themeViewModel.state.collectAsState()
    
    // Применяем тему с учётом пользовательских настроек
    if (enableAnimations) {
        Crossfade(
            targetState = themeState.themeMode,
            animationSpec = tween(durationMillis = 300),
            label = "theme_transition"
        ) { currentThemeMode ->
            PaiAndroidTheme(
                themeMode = currentThemeMode,
                useModernColors = themeState.useModernColors,
                content = content
            )
        }
    } else {
        PaiAndroidTheme(
            themeMode = themeState.themeMode,
            useModernColors = themeState.useModernColors,
            content = content
        )
    }
}

/**
 * Упрощённая версия для предпросмотра без ViewModel.
 */
@Composable
fun ThemeWrapperPreview(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    useModernColors: Boolean = true,
    enableAnimations: Boolean = true,
    content: @Composable () -> Unit
) {
    if (enableAnimations) {
        Crossfade(
            targetState = themeMode,
            animationSpec = tween(durationMillis = 300),
            label = "theme_transition_preview"
        ) { currentThemeMode ->
            PaiAndroidTheme(
                themeMode = currentThemeMode,
                useModernColors = useModernColors,
                content = content
            )
        }
    } else {
        PaiAndroidTheme(
            themeMode = themeMode,
            useModernColors = useModernColors,
            content = content
        )
    }
}