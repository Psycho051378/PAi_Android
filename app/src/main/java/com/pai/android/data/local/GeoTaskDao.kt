package com.pai.android.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pai.android.data.model.GeoTask
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoTaskDao {

    /** Наблюдать за активными задачами (Flow для UI). */
    @Query("SELECT * FROM geo_tasks WHERE isActive = 1 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<GeoTask>>

    /** Все активные задачи (suspend). */
    @Query("SELECT * FROM geo_tasks WHERE isActive = 1")
    suspend fun getActive(): List<GeoTask>

    /** Все задачи (включая неактивные). */
    @Query("SELECT * FROM geo_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GeoTask>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: GeoTask)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<GeoTask>)

    @Update
    suspend fun update(task: GeoTask)

    /** Деактивировать задачу (без удаления). */
    @Query("UPDATE geo_tasks SET isActive = 0 WHERE id = :taskId")
    suspend fun deactivate(taskId: String)

    /** Отметить время срабатывания. */
    @Query("UPDATE geo_tasks SET lastTriggeredAt = :timestamp, lastTriggeredAddress = :address WHERE id = :taskId")
    suspend fun markTriggered(taskId: String, timestamp: Long = System.currentTimeMillis(), address: String? = null)

    @Delete
    suspend fun delete(task: GeoTask)

    @Query("DELETE FROM geo_tasks")
    suspend fun clearAll()

    /** Удалить неактивные задачи старше N дней. */
    @Query("DELETE FROM geo_tasks WHERE isActive = 0 AND createdAt < :beforeTimestamp")
    suspend fun cleanOld(beforeTimestamp: Long)
}
