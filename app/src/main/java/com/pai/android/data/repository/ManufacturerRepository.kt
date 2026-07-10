package com.pai.android.data.repository

import com.pai.android.data.local.ManufacturerAuthDao
import com.pai.android.data.model.ManufacturerAuth
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий для управления авторизацией у производителей устройств.
 *
 * Хранит учётные данные (логин/пароль, OAuth-токены) для получения
 * device token'ов через облачные API.
 */
@Singleton
class ManufacturerRepository @Inject constructor(
    private val dao: ManufacturerAuthDao
) {
    /** Получить настройки производителя. */
    suspend fun get(manufacturer: String): ManufacturerAuth? = dao.get(manufacturer)

    /** Наблюдать настройки производителя. */
    fun observe(manufacturer: String): Flow<ManufacturerAuth?> = dao.observe(manufacturer)

    /** Все производители (Flow). */
    fun observeAll(): Flow<List<ManufacturerAuth>> = dao.observeAll()

    /** Все производители. */
    suspend fun getAll(): List<ManufacturerAuth> = dao.getAll()

    /** Сохранить/обновить настройки. */
    suspend fun save(auth: ManufacturerAuth) = dao.upsert(auth)

    /** Удалить настройки. */
    suspend fun delete(manufacturer: String) = dao.delete(manufacturer)

    /** Получить credentials как Map. */
    suspend fun getCredentials(manufacturer: String): Map<String, String> {
        val auth = dao.get(manufacturer) ?: return emptyMap()
        if (!auth.enabled) return emptyMap()
        return try {
            val json = org.json.JSONObject(auth.credentials)
            json.keys().asSequence().associateWith { json.optString(it, "") }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Сохранить credentials в JSON. */
    suspend fun saveCredentials(manufacturer: String, creds: Map<String, String>) {
        val json = org.json.JSONObject(creds).toString()
        val existing = dao.get(manufacturer)
        val auth = if (existing != null) {
            existing.copy(credentials = json)
        } else {
            ManufacturerAuth(
                manufacturer = manufacturer,
                displayName = manufacturer.replaceFirstChar { it.uppercase() },
                credentials = json
            )
        }
        dao.upsert(auth)
    }
}
