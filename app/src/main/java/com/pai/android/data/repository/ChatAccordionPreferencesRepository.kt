package com.pai.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ключи для DataStore состояния аккордеона групп чатов.
 */
private object AccordionPreferencesKeys {
    val TODAY_EXPANDED = booleanPreferencesKey("accordion_today_expanded")
    val YESTERDAY_EXPANDED = booleanPreferencesKey("accordion_yesterday_expanded")
    val THIS_WEEK_EXPANDED = booleanPreferencesKey("accordion_this_week_expanded")
    val THIS_MONTH_EXPANDED = booleanPreferencesKey("accordion_this_month_expanded")
    val EARLIER_EXPANDED = booleanPreferencesKey("accordion_earlier_expanded")
    val ARCHIVED_EXPANDED = booleanPreferencesKey("accordion_archived_expanded")
}

/**
 * Расширение для доступа к DataStore.
 */
private val Context.accordionDataStore: DataStore<Preferences> by preferencesDataStore(name = "accordion_preferences")

/**
 * Репозиторий для управления состоянием аккордеона групп чатов.
 */
@Singleton
class ChatAccordionPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    /**
     * Получает состояние раскрытия всех групп.
     */
    val expandedGroups: Flow<Map<String, Boolean>> = context.accordionDataStore.data
        .map { preferences ->
            mapOf(
                "today" to ((preferences[AccordionPreferencesKeys.TODAY_EXPANDED] as? Boolean) ?: true), // по умолчанию раскрыто
                "yesterday" to ((preferences[AccordionPreferencesKeys.YESTERDAY_EXPANDED] as? Boolean) ?: true),
                "this_week" to ((preferences[AccordionPreferencesKeys.THIS_WEEK_EXPANDED] as? Boolean) ?: true),
                "this_month" to ((preferences[AccordionPreferencesKeys.THIS_MONTH_EXPANDED] as? Boolean) ?: true),
                "earlier" to ((preferences[AccordionPreferencesKeys.EARLIER_EXPANDED] as? Boolean) ?: true),
                "archived" to ((preferences[AccordionPreferencesKeys.ARCHIVED_EXPANDED] as? Boolean) ?: false) // по умолчанию закрыто
            )
        }
    
    /**
     * Устанавливает состояние раскрытия для конкретной группы.
     */
    suspend fun setGroupExpanded(groupId: String, expanded: Boolean) {
        context.accordionDataStore.edit { preferences ->
            val key = when (groupId) {
                "today" -> AccordionPreferencesKeys.TODAY_EXPANDED
                "yesterday" -> AccordionPreferencesKeys.YESTERDAY_EXPANDED
                "this_week" -> AccordionPreferencesKeys.THIS_WEEK_EXPANDED
                "this_month" -> AccordionPreferencesKeys.THIS_MONTH_EXPANDED
                "earlier" -> AccordionPreferencesKeys.EARLIER_EXPANDED
                "archived" -> AccordionPreferencesKeys.ARCHIVED_EXPANDED
                else -> return@edit
            }
            preferences[key] = expanded
        }
    }
    
    /**
     * Сбрасывает все состояния аккордеона к значениям по умолчанию.
     */
    suspend fun resetToDefaults() {
        context.accordionDataStore.edit { preferences ->
            preferences[AccordionPreferencesKeys.TODAY_EXPANDED] = true
            preferences[AccordionPreferencesKeys.YESTERDAY_EXPANDED] = true
            preferences[AccordionPreferencesKeys.THIS_WEEK_EXPANDED] = true
            preferences[AccordionPreferencesKeys.THIS_MONTH_EXPANDED] = true
            preferences[AccordionPreferencesKeys.EARLIER_EXPANDED] = true
            preferences[AccordionPreferencesKeys.ARCHIVED_EXPANDED] = false
        }
    }
}