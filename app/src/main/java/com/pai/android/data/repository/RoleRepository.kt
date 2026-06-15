package com.pai.android.data.repository

import com.pai.android.data.local.RoleDao
import com.pai.android.data.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Репозиторий для работы с ролями AI.
 */
class RoleRepository(
    private val roleDao: RoleDao
) {
    
    /** Получает все роли как Flow */
    fun observeAllRoles(): Flow<List<Role>> {
        return roleDao.observeAll()
    }
    
    /** Получает роль по ID */
    suspend fun getRole(roleId: String): Role? {
        return roleDao.getById(roleId)
    }
    
    /** Получает роль по ID как Flow */
    fun observeRole(roleId: String): Flow<Role?> {
        return roleDao.observeById(roleId)
    }
    
    /** Получает роль по умолчанию */
    suspend fun getDefaultRole(): Role? {
        return roleDao.getDefaultRole()
    }
    
    /** Получает роль по умолчанию как Flow */
    fun observeDefaultRole(): Flow<Role?> {
        return roleDao.observeDefaultRole()
    }
    
    /** Создаёт новую роль */
    suspend fun createRole(
        name: String,
        description: String? = null,
        systemPrompt: String,
        temperature: Float? = null,
        maxTokens: Int? = null,
        isDefault: Boolean = false
    ): Role {
        val role = Role(
            name = name,
            description = description,
            systemPrompt = systemPrompt,
            temperature = temperature,
            maxTokens = maxTokens,
            isDefault = isDefault
        )
        roleDao.insert(role)
        
        // Если эта роль стала ролью по умолчанию, снимаем флаг с других ролей
        if (isDefault) {
            roleDao.setDefaultRole(role.id)
        }
        
        return role
    }
    
    /** Обновляет существующую роль */
    suspend fun updateRole(role: Role) {
        roleDao.update(role)
        
        // Если роль стала ролью по умолчанию, обновляем флаги
        if (role.isDefault) {
            roleDao.setDefaultRole(role.id)
        }
    }
    
    /** Удаляет роль */
    suspend fun deleteRole(roleId: String) {
        val role = roleDao.getById(roleId)
        role?.let { roleDao.delete(it) }
    }
    
    /** Устанавливает роль по умолчанию */
    suspend fun setDefaultRole(roleId: String) {
        roleDao.setDefaultRole(roleId)
    }
    
    /** Ищет роли по названию */
    suspend fun searchRoles(query: String): List<Role> {
        return roleDao.searchByName(query)
    }
    
    /** Получает количество ролей */
    suspend fun countRoles(): Int {
        return roleDao.count()
    }
    
    /** Инициализирует стандартные роли, если таблица пуста */
    suspend fun initializeDefaultRoles() {
        val count = roleDao.count()
        if (count == 0) {
            val defaultRoles = Role.createDefaultRoles()
            defaultRoles.forEach { roleDao.insert(it) }
        }
    }
    
    /** Получает роль для чата (если roleId null, возвращает роль по умолчанию) */
    suspend fun getRoleForChat(roleId: String?): Role? {
        return if (roleId != null) {
            roleDao.getById(roleId)
        } else {
            roleDao.getDefaultRole()
        }
    }
    
    /** Получает системный промпт для роли (если роль не найдена, возвращает null) */
    suspend fun getSystemPromptForRole(roleId: String?): String? {
        return getRoleForChat(roleId)?.systemPrompt
    }
}