package com.pai.android.agent

import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.repository.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PersistentContext — сохранение и восстановление состояния агента.
 *
 * При старте агент должен понимать, над чем работал, какие файлы создал,
 * какие проекты в процессе. PersistentContext сериализует это в memory.
 */
@Singleton
class PersistentContext @Inject constructor(
    private val memoryRepository: MemoryRepository
) {
    companion object {
        private const val SCOPE = "global"
        private const val CATEGORY = "persistent_context"
    }

    data class AgentContext(
        val activeProjectId: String? = null,
        val pendingResumeProjectId: String? = null,
        val pendingApprovalProjectId: String? = null,
        val pendingApprovalPlan: String? = null,
        val pendingInitiativeProjectId: String? = null,
        val pendingInitiativeSuggestion: String? = null,
        val lastQuery: String? = null,
        val lastResponse: String? = null,
        val pendingTaskNotification: String? = null,
        val lastChatId: String? = null,
        val sessionCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val projectDirectory: String = "projects"  // папка для проектов по умолчанию
    )

    /**
     * Сохраняет текущее состояние.
     */
    suspend fun save(context: AgentContext) {
        memoryRepository.savePermanentFactFull(
            category = CATEGORY,
            key = "agent_state",
            value = serialize(context),
            confidence = 1.0f,
            scope = SCOPE,
            tags = "context,state"
        )
        println("💾 Context saved")
    }

    /**
     * Загружает последнее сохранённое состояние.
     */
    suspend fun load(): AgentContext? {
        val fact = memoryRepository.getFactByCategoryAndKey(CATEGORY, "agent_state") ?: return null
        return deserialize(fact)
    }

    /**
     * Проверяет и забирает отложенное уведомление от планировщика.
     * @return текст уведомления или null
     */
    suspend fun takePendingNotification(): String? {
        val fact = memoryRepository.getFactByCategoryAndKey(CATEGORY, "agent_state") ?: return null
        val ctx = deserialize(fact) ?: return null
        val notif = ctx.pendingTaskNotification ?: return null
        // Очищаем уведомление
        save(ctx.copy(pendingTaskNotification = null))
        return notif
    }

    /**
     * Обновляет активный проект.
     */
    suspend fun setActiveProject(projectId: String?) {
        val ctx = (load() ?: AgentContext()).copy(
            activeProjectId = projectId,
            updatedAt = System.currentTimeMillis(),
            sessionCount = (load()?.sessionCount ?: 0) + if (projectId != null) 0 else 0
        )
        save(ctx)
    }

    /**
     * Сохраняет последний запрос и ответ.
     */
    suspend fun saveInteraction(query: String, response: String) {
        val ctx = (load() ?: AgentContext()).copy(
            lastQuery = query.take(500),
            lastResponse = response.take(500),
            sessionCount = (load()?.sessionCount ?: 0) + 1,
            updatedAt = System.currentTimeMillis()
        )
        save(ctx)
    }

    /**
     * Очищает состояние.
     */
    suspend fun clear() {
        // Не удаляем, а сбрасываем — сохраняем историю сессий
        val ctx = load() ?: return
        save(ctx.copy(
            activeProjectId = null,
            pendingResumeProjectId = null,
            pendingApprovalProjectId = null,
            pendingApprovalPlan = null,
            pendingInitiativeProjectId = null,
            pendingInitiativeSuggestion = null,
            lastQuery = null,
            lastResponse = null,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun serialize(ctx: AgentContext): String {
        return "${ctx.activeProjectId ?: ""}|${ctx.pendingResumeProjectId ?: ""}|${ctx.pendingApprovalProjectId ?: ""}|${ctx.pendingApprovalPlan ?: ""}|${ctx.pendingInitiativeProjectId ?: ""}|${ctx.pendingInitiativeSuggestion ?: ""}|${ctx.lastQuery ?: ""}|${ctx.lastResponse ?: ""}|${ctx.pendingTaskNotification ?: ""}|${ctx.lastChatId ?: ""}|${ctx.sessionCount}|${ctx.createdAt}|${ctx.updatedAt}"
    }

    private fun deserialize(fact: PermanentMemory): AgentContext? {
        return try {
            val parts = fact.value.split("|", limit = 14)
            if (parts.size < 6) return null
            // lastChatId was added later; old data (12 parts) won't have it
            val lastChatId = if (parts.size >= 10) parts[9].ifBlank { null } else null
            val sessionIdx = if (parts.size >= 10) 10 else 9
            val createdAtIdx = if (parts.size >= 10) 11 else 10
            val updatedAtIdx = if (parts.size >= 10) 12 else 11
            AgentContext(
                activeProjectId = parts[0].ifBlank { null },
                pendingResumeProjectId = parts[1].ifBlank { null },
                pendingApprovalProjectId = parts[2].ifBlank { null },
                pendingApprovalPlan = parts[3].ifBlank { null },
                pendingInitiativeProjectId = parts[4].ifBlank { null },
                pendingInitiativeSuggestion = parts[5].ifBlank { null },
                lastQuery = parts[6].ifBlank { null },
                lastResponse = parts[7].ifBlank { null },
                pendingTaskNotification = parts[8].ifBlank { null },
                lastChatId = lastChatId,
                sessionCount = parts[sessionIdx].toIntOrNull() ?: 0,
                createdAt = parts[createdAtIdx].toLongOrNull() ?: 0L,
                updatedAt = parts[updatedAtIdx].toLongOrNull() ?: 0L
            )
        } catch (e: Exception) { null }
    }
}

