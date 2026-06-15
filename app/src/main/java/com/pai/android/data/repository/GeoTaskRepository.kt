package com.pai.android.data.repository

import com.pai.android.data.local.GeoTaskDao
import com.pai.android.data.model.GeoTask
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий для гео-задач (напоминаний по геолокации).
 *
 * Позволяет:
 * - Создавать гео-задачи (вручную или через AI)
 * - Получать активные задачи для проверки дистанции
 * - Помечать задачи как сработавшие (с защитой от дублирования)
 * - Удалять/деактивировать задачи
 */
@Singleton
class GeoTaskRepository @Inject constructor(
    private val dao: GeoTaskDao
) {
    /** Наблюдать за активными задачами (для UI-списка). */
    fun observeActive(): Flow<List<GeoTask>> = dao.observeActive()

    /** Все задачи (включая завершённые). */
    fun observeAll(): Flow<List<GeoTask>> = dao.observeAll()

    /** Получить список активных задач (для фоновой проверки). */
    suspend fun getActive(): List<GeoTask> = dao.getActive()

    /** Сохранить новую задачу. */
    suspend fun save(task: GeoTask) = dao.insert(task)

    /** Сохранить список задач. */
    suspend fun saveAll(tasks: List<GeoTask>) = dao.insertAll(tasks)

    /** Деактивировать задачу (не удалять, просто выключить). */
    suspend fun deactivate(id: String) = dao.deactivate(id)

    /** Отметить что задача сработала в заданной локации. */
    suspend fun markTriggered(id: String, address: String? = null) =
        dao.markTriggered(id, address = address)

    /** Удалить задачу. */
    suspend fun delete(task: GeoTask) = dao.delete(task)

    /** Очистить все задачи. */
    suspend fun clearAll() = dao.clearAll()

    /** Очистить старые неактивные задачи (старше 7 дней). */
    suspend fun cleanOld(daysOld: Int = 7) {
        val before = System.currentTimeMillis() - daysOld * 24L * 60L * 60L * 1000L
        dao.cleanOld(before)
    }
}
