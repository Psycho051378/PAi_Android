package com.pai.android.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.ProviderSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderSettingsDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: ProviderSettings): Long
    
    @Update
    suspend fun update(settings: ProviderSettings)
    
    @Delete
    suspend fun delete(settings: ProviderSettings)
    
    @Query("SELECT * FROM provider_settings ORDER BY provider")
    fun observeAll(): Flow<List<ProviderSettings>>
    
    @Query("SELECT * FROM provider_settings WHERE id = :settingsId")
    suspend fun getById(settingsId: String): ProviderSettings?
    
    @Query("SELECT * FROM provider_settings WHERE id = :settingsId")
    fun observeById(settingsId: String): Flow<ProviderSettings?>
    
    @Query("SELECT * FROM provider_settings WHERE provider = :provider AND isEnabled = 1")
    suspend fun getEnabledByProvider(provider: AiProvider): List<ProviderSettings>
    
    @Query("SELECT * FROM provider_settings WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultSettings(): ProviderSettings?
    
    @Query("SELECT * FROM provider_settings WHERE isDefault = 1")
    fun observeDefaultSettings(): Flow<ProviderSettings?>
    
    @Query("UPDATE provider_settings SET isDefault = 0 WHERE isDefault = 1")
    suspend fun clearDefault()
    
    @Query("UPDATE provider_settings SET isDefault = 1 WHERE id = :settingsId")
    suspend fun setAsDefault(settingsId: String)
    
    @Query("DELETE FROM provider_settings")
    suspend fun deleteAll()
}