package com.pai.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.voiceDataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_settings")

/**
 * Настройки голосового управления.
 */
data class VoiceSettings(
    val enabled: Boolean = false,
    val wakeWord: String = "компьютер",
    val ttsEnabled: Boolean = true
)

@Singleton
class VoiceSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val ENABLED_KEY = booleanPreferencesKey("voice_enabled")
        private val WAKE_WORD_KEY = stringPreferencesKey("wake_word")
        private val TTS_ENABLED_KEY = booleanPreferencesKey("tts_enabled")
    }

    /** Наблюдаемый поток настроек */
    val settings: Flow<VoiceSettings> = context.voiceDataStore.data.map { prefs ->
        VoiceSettings(
            enabled = prefs[ENABLED_KEY] ?: false,
            wakeWord = prefs[WAKE_WORD_KEY] ?: "компьютер",
            ttsEnabled = prefs[TTS_ENABLED_KEY] ?: true
        )
    }

    /** Текущие настройки (однократно) */
    suspend fun get(): VoiceSettings = settings.first()

    /** Включить/выключить голосовое управление */
    suspend fun setEnabled(enabled: Boolean) {
        context.voiceDataStore.edit { prefs ->
            prefs[ENABLED_KEY] = enabled
        }
    }

    /** Установить ключевое слово */
    suspend fun setWakeWord(wakeWord: String) {
        context.voiceDataStore.edit { prefs ->
            prefs[WAKE_WORD_KEY] = wakeWord
        }
    }

    /** Включить/выключить TTS */
    suspend fun setTtsEnabled(enabled: Boolean) {
        context.voiceDataStore.edit { prefs ->
            prefs[TTS_ENABLED_KEY] = enabled
        }
    }

    /** Установить все настройки сразу */
    suspend fun update(settings: VoiceSettings) {
        context.voiceDataStore.edit { prefs ->
            prefs[ENABLED_KEY] = settings.enabled
            prefs[WAKE_WORD_KEY] = settings.wakeWord
            prefs[TTS_ENABLED_KEY] = settings.ttsEnabled
        }
    }
}
