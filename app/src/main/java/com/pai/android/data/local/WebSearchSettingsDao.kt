package com.pai.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pai.android.data.model.WebSearchSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface WebSearchSettingsDao {
    
    /**
     * Вставляет или заменяет настройки веб-поиска.
     * Поскольку запись только одна, используется фиксированный ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(settings: WebSearchSettings): Long
    
    /**
     * Обновляет существующие настройки.
     */
    @Update
    suspend fun update(settings: WebSearchSettings)
    
    /**
     * Получает текущие настройки веб-поиска.
     * Возвращает null, если настройки ещё не сохранены.
     */
    @Query("SELECT * FROM web_search_settings WHERE id = 'web_search_settings'")
    suspend fun getSettings(): WebSearchSettings?
    
    /**
     * Наблюдает за изменениями настроек веб-поиска.
     */
    @Query("SELECT * FROM web_search_settings WHERE id = 'web_search_settings'")
    fun observeSettings(): Flow<WebSearchSettings?>
    
    /**
     * Обновляет глобальное включение модуля.
     */
    @Query("UPDATE web_search_settings SET enabled = :enabled, updated_at = :updatedAt WHERE id = 'web_search_settings'")
    suspend fun updateEnabled(enabled: Boolean, updatedAt: Long = System.currentTimeMillis())
    
    /**
     * Обновляет провайдера поиска.
     */
    @Query("UPDATE web_search_settings SET provider = :provider, updated_at = :updatedAt WHERE id = 'web_search_settings'")
    suspend fun updateProvider(provider: String, updatedAt: Long = System.currentTimeMillis())
    
    /**
     * Обновляет Google API ключ.
     */
    @Query("UPDATE web_search_settings SET google_api_key = :apiKey, updated_at = :updatedAt WHERE id = 'web_search_settings'")
    suspend fun updateGoogleApiKey(apiKey: String?, updatedAt: Long = System.currentTimeMillis())
    
    /**
     * Обновляет Google Search Engine ID.
     */
    @Query("UPDATE web_search_settings SET google_search_engine_id = :engineId, updated_at = :updatedAt WHERE id = 'web_search_settings'")
    suspend fun updateGoogleSearchEngineId(engineId: String?, updatedAt: Long = System.currentTimeMillis())
    
    /**
     * Обновляет Tavily API ключ.
     */
    @Query("UPDATE web_search_settings SET tavily_api_key = :apiKey, updated_at = :updatedAt WHERE id = 'web_search_settings'")
    suspend fun updateTavilyApiKey(apiKey: String?, updatedAt: Long = System.currentTimeMillis())
    
    /**
     * Удаляет все настройки (для тестирования).
     */
    @Query("DELETE FROM web_search_settings")
    suspend fun deleteAll()
}