package com.pai.android.agent

import com.pai.android.data.repository.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProjectManager — оркестратор долгосрочных задач.
 * Хранит проекты, их статус, структуру и историю.
 *
 * Хранилище проектов теперь файловое ([ProjectStore]) — устойчиво к «Row too big»/CursorWindow.
 * MemoryRepository остаётся только для опциональной сводки по проекту.
 */
@Singleton
class ProjectManager @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val projectStore: ProjectStore
) {
    companion object {
        private const val MEMORY_CATEGORY = "project_manager"
    }

    data class Project(
        val id: String,
        val name: String,
        val description: String,
        val status: ProjectStatus,
        val steps: List<ProjectStep>,
        val currentStepIndex: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val snapshotCount: Int = 0
    )

    data class ProjectStep(
        val id: String,
        val description: String,
        val status: StepStatus,
        val result: String? = null
    )

    enum class ProjectStatus { ACTIVE, PAUSED, COMPLETED, FAILED }
    enum class StepStatus { PENDING, IN_PROGRESS, DONE, SKIPPED, FAILED }

    /** Создаёт новый проект. */
    suspend fun createProject(name: String, description: String, steps: List<String>): Project {
        val id = "project_" + System.currentTimeMillis() + "_" + name.filter { it.isLetterOrDigit() }.take(20)
        val project = Project(
            id = id,
            name = name,
            description = description,
            status = ProjectStatus.ACTIVE,
            steps = steps.mapIndexed { i, s -> ProjectStep(id = "step_$i", description = s, status = StepStatus.PENDING) }
        )
        saveProject(project)
        println("📁 Project created: $name ($id, " + steps.size + " steps)")
        return project
    }

    /** Возвращает список всех проектов (битые файлы пропускаются, остальные читаются). */
    suspend fun listProjects(): List<Project> {
        val result = mutableListOf<Project>()
        for (id in projectStore.listIds()) {
            val raw = projectStore.read(id) ?: continue
            val p = try { deserialize(raw) } catch (e: Exception) {
                println("⚠️ ProjectManager: пропускаю повреждённый проект '" + id + "': " + e.message); null
            }
            if (p != null) result.add(p)
        }
        return result
    }

    /** Загружает проект по ID. */
    suspend fun getProject(id: String): Project? {
        val raw = projectStore.read(id) ?: return null
        return deserialize(raw)
    }

    /** Обновляет статус шага и проекта. */
    suspend fun updateStep(
        projectId: String, stepIndex: Int, status: StepStatus,
        result: String? = null, workspaceDir: String = "",
    ): Project? {
        val project = getProject(projectId) ?: return null
        if (stepIndex < 0 || stepIndex >= project.steps.size) return null

        val updatedSteps = project.steps.toMutableList()
        updatedSteps[stepIndex] = updatedSteps[stepIndex].copy(status = status, result = result)

        val newStatus = when {
            updatedSteps.all { it.status == StepStatus.DONE } -> ProjectStatus.COMPLETED
            status == StepStatus.FAILED -> ProjectStatus.FAILED
            else -> ProjectStatus.ACTIVE
        }

        val updated = project.copy(
            steps = updatedSteps,
            currentStepIndex = stepIndex + 1,
            status = newStatus,
            updatedAt = System.currentTimeMillis()
        )
        saveProject(updated)

        if (status == StepStatus.DONE) {
            saveSnapshot(projectId, stepIndex, workspaceDir)
        }
        return updated
    }

    /** Возвращает следующий невыполненный шаг. */
    suspend fun getNextStep(projectId: String): ProjectStep? {
        val project = getProject(projectId) ?: return null
        return project.steps.firstOrNull { it.status == StepStatus.PENDING }
    }

    /** Сохраняет метаданные шага как снапшот. */
    private suspend fun saveSnapshot(projectId: String, stepIndex: Int, workspaceDir: String) {
        val project = getProject(projectId) ?: return
        if (stepIndex < 0 || stepIndex >= project.steps.size) return
        if (workspaceDir.isBlank()) return

        val step = project.steps[stepIndex]
        val snapDir = workspaceDir + "/.history/step_" + stepIndex.toString().padStart(3, '0')

        try {
            val meta = org.json.JSONObject().apply {
                put("projectId", projectId)
                put("stepIndex", stepIndex)
                put("stepDescription", step.description)
                put("stepResult", step.result ?: "")
                put("timestamp", System.currentTimeMillis())
                put("totalSteps", project.steps.size)
            }
            val metaFile = java.io.File(snapDir, ".snapshot.json")
            metaFile.parentFile.mkdirs()
            metaFile.writeText(meta.toString(2))

            val updated = project.copy(
                snapshotCount = project.snapshotCount + 1,
                updatedAt = System.currentTimeMillis()
            )
            saveProject(updated)
            println("📸 Snapshot: step " + (stepIndex + 1) + "/" + project.steps.size)
        } catch (e: Exception) {
            println("⚠️ Snapshot error: " + e.message)
        }
    }

    /** Завершает проект. */
    suspend fun completeProject(projectId: String, summary: String? = null): Project? {
        val project = getProject(projectId) ?: return null
        val updated = project.copy(status = ProjectStatus.COMPLETED, updatedAt = System.currentTimeMillis())
        saveProject(updated)
        if (summary != null) {
            try {
                memoryRepository.savePermanentFactFull(
                    category = MEMORY_CATEGORY,
                    key = project.id + "_summary",
                    value = summary,
                    confidence = 0.5f,
                    scope = "project",
                    tags = null
                )
            } catch (e: Exception) {
                println("⚠️ completeProject summary save failed: " + e.message)
            }
        }
        println("✅ Project completed: " + project.name)
        return updated
    }

    /** Сохраняет проект в файловое хранилище. */
    private suspend fun saveProject(project: Project) {
        projectStore.write(project.id, serialize(project))
    }

    private fun serialize(project: Project): String {
        val steps = org.json.JSONArray()
        for (s in project.steps) {
            steps.put(org.json.JSONObject().apply {
                put("id", s.id)
                put("description", s.description)
                put("status", s.status.name)
                if (s.result != null) put("result", s.result)
            })
        }
        return org.json.JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("description", project.description)
            put("status", project.status.name)
            put("currentStepIndex", project.currentStepIndex)
            put("createdAt", project.createdAt)
            put("updatedAt", project.updatedAt)
            put("snapshotCount", project.snapshotCount)
            put("steps", steps)
        }.toString()
    }

    /** Десериализует проект из JSON. */
    private fun deserialize(json: String): Project? {
        return try {
            val o = org.json.JSONObject(json)
            val stepsArr = o.optJSONArray("steps") ?: org.json.JSONArray()
            val steps = (0 until stepsArr.length()).map { i ->
                val s = stepsArr.getJSONObject(i)
                ProjectStep(
                    id = s.getString("id"),
                    description = s.getString("description"),
                    status = StepStatus.valueOf(s.getString("status")),
                    result = if (s.has("result")) s.optString("result") else null
                )
            }
            Project(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description", ""),
                status = ProjectStatus.valueOf(o.getString("status")),
                steps = steps,
                currentStepIndex = o.optInt("currentStepIndex", 0),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                snapshotCount = o.optInt("snapshotCount", 0)
            )
        } catch (e: Exception) {
            println("⚠️ ProjectManager: deserialize error: " + e.message)
            null
        }
    }

    suspend fun addSteps(projectId: String, newSteps: List<String>): Project? {
        val project = getProject(projectId) ?: return null
        val existingSteps = project.steps.toMutableList()
        val startIdx = existingSteps.size
        val stepsToAdd = newSteps.mapIndexed { i, s ->
            ProjectStep(id = "step_" + (startIdx + i), description = s, status = StepStatus.PENDING)
        }
        existingSteps.addAll(stepsToAdd)
        val updated = project.copy(steps = existingSteps, currentStepIndex = startIdx, updatedAt = System.currentTimeMillis())
        saveProject(updated)
        println("📋 Added " + stepsToAdd.size + " steps to project '" + project.name + "' (from step " + startIdx + ")")
        return updated
    }
}
