package com.pai.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pai.android.data.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ключи для DataStore настроек темы.
 */
private object ThemePreferencesKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
}

/**
 * Расширение для доступа к DataStore.
 */
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

/**
 * Репозиторий для управления настройками темы.
 */
@Singleton
class ThemePreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    /**
     * Получает текущий режим темы.
     */
    val themeMode: Flow<ThemeMode> = context.themeDataStore.data
        .map { preferences ->
            val modeString = preferences[ThemePreferencesKeys.THEME_MODE]
            ThemeMode.fromString(modeString)
        }
    
    /**
     * Получает настройку использования динамических цветов (Material You).
     */
    val useDynamicColor: Flow<Boolean> = context.themeDataStore.data
        .map { preferences ->
            preferences[ThemePreferencesKeys.USE_DYNAMIC_COLOR] ?: false
        }
    
    /**
     * Сохраняет режим темы.
     */
    suspend fun setThemeMode(themeMode: ThemeMode) {
        context.themeDataStore.edit { preferences ->
            preferences[ThemePreferencesKeys.THEME_MODE] = themeMode.name
        }
    }
    
    /**
     * Сохраняет настройку использования динамических цветов.
     */
    suspend fun setUseDynamicColor(useDynamicColor: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[ThemePreferencesKeys.USE_DYNAMIC_COLOR] = useDynamicColor
        }
    }
    
    /**
     * Сбрасывает все настройки темы к значениям по умолчанию.
     */
    suspend fun resetToDefaults() {
        context.themeDataStore.edit { preferences ->
            preferences.remove(ThemePreferencesKeys.THEME_MODE)
            preferences.remove(ThemePreferencesKeys.USE_DYNAMIC_COLOR)
        }
    }
}