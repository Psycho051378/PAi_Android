package com.pai.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pai.android.data.model.ManufacturerAuth
import kotlinx.coroutines.flow.Flow

@Dao
interface ManufacturerAuthDao {

    /** Сохранить или обновить учётные данные производителя. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(auth: ManufacturerAuth)

    /** Получить учётные данные производителя. */
    @Query("SELECT * FROM manufacturer_auth WHERE manufacturer = :manufacturer")
    suspend fun get(manufacturer: String): ManufacturerAuth?

    /** Получить учётные данные производителя (Flow). */
    @Query("SELECT * FROM manufacturer_auth WHERE manufacturer = :manufacturer")
    fun observe(manufacturer: String): Flow<ManufacturerAuth?>

    /** Все производители (Flow). */
    @Query("SELECT * FROM manufacturer_auth ORDER BY manufacturer")
    fun observeAll(): Flow<List<ManufacturerAuth>>

    /** Все производители (однократно). */
    @Query("SELECT * FROM manufacturer_auth ORDER BY manufacturer")
    suspend fun getAll(): List<ManufacturerAuth>

    /** Удалить учётные данные. */
    @Query("DELETE FROM manufacturer_auth WHERE manufacturer = :manufacturer")
    suspend fun delete(manufacturer: String)

    /** Включить/выключить производителя. */
    @Query("UPDATE manufacturer_auth SET enabled = :enabled WHERE manufacturer = :manufacturer")
    suspend fun setEnabled(manufacturer: String, enabled: Boolean)
}
