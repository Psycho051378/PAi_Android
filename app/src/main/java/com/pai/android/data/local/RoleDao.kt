package com.pai.android.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pai.android.data.model.Role
import kotlinx.coroutines.flow.Flow

@Dao
interface RoleDao {
    
    /** Вставляет или заменяет роль */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(role: Role): Long
    
    /** Обновляет существующую роль */
    @Update
    suspend fun update(role: Role)
    
    /** Удаляет роль */
    @Delete
    suspend fun delete(role: Role)
    
    /** Получает все роли, отсортированные по дате создания */
    @Query("SELECT * FROM roles ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Role>>
    
    /** Получает роль по ID */
    @Query("SELECT * FROM roles WHERE id = :roleId")
    suspend fun getById(roleId: String): Role?
    
    /** Получает роль по ID как Flow для наблюдения */
    @Query("SELECT * FROM roles WHERE id = :roleId")
    fun observeById(roleId: String): Flow<Role?>
    
    /** Получает роль по умолчанию */
    @Query("SELECT * FROM roles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultRole(): Role?
    
    /** Получает роль по умолчанию как Flow */
    @Query("SELECT * FROM roles WHERE isDefault = 1 LIMIT 1")
    fun observeDefaultRole(): Flow<Role?>
    
    /** Устанавливает роль по умолчанию */
    @Query("UPDATE roles SET isDefault = CASE WHEN id = :roleId THEN 1 ELSE 0 END")
    suspend fun setDefaultRole(roleId: String)
    
    /** Удаляет все роли */
    @Query("DELETE FROM roles")
    suspend fun deleteAll()
    
    /** Получает количество ролей */
    @Query("SELECT COUNT(*) FROM roles")
    suspend fun count(): Int
    
    /** Ищет роли по названию (поиск с LIKE) */
    @Query("SELECT * FROM roles WHERE name LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun searchByName(query: String): List<Role>
}