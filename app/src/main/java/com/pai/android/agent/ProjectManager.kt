package com.pai.android.agent

import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.repository.MemoryRepository
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProjectManager — оркестратор долгосрочных задач.
 * Хранит проекты, их статус, структуру и историю.
 */
@Singleton
class ProjectManager @Inject constructor(
    private val memoryRepository: MemoryRepository
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
        val id = "project_${System.currentTimeMillis()}_${name.filter { it.isLetterOrDigit() }.take(20)}"
        val project = Project(
            id = id,
            name = name,
            description = description,
            status = ProjectStatus.ACTIVE,
            steps = steps.mapIndexed { i, s ->
                ProjectStep(id = "step_$i", description = s, status = StepStatus.PENDING)
            }
        )
        saveProject(project)
        println("📁 Project created: $name ($id, ${steps.size} steps)")
        return project
    }

    /** Возвращает список всех проектов. */
    suspend fun listProjects(): List<Project> {
        return try {
            val facts = memoryRepository.searchFactsInScope("project", "", 200)
            val projectFacts = facts.filter { it.category == MEMORY_CATEGORY && it.key.startsWith("project_") }
            projectFacts.mapNotNull { deserialize(it) }
        } catch (e: Exception) {
            if (e.message?.contains("CursorWindow") == true || e.message?.contains("Row too big") == true) {
                println("⚠️ ProjectManager: corrupted data detected, clearing")
                clearAllProjects()
            }
            emptyList()
        }
    }

    private suspend fun clearAllProjects() {
        println("⚠️ ProjectManager: old project data needs manual clear")
    }

    /** Загружает проект по ID. */
    suspend fun getProject(id: String): Project? {
        val fact = memoryRepository.getFactByCategoryAndKey(MEMORY_CATEGORY, id) ?: return null
        return deserialize(fact)
    }

    /** Обновляет статус шага и проекта. */
    suspend fun updateStep(
        projectId: String, stepIndex: Int, status: StepStatus,
        result: String? = null, workspaceDir: String = ""
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

        // Создаём снапшот при завершении шага
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
            println("📸 Snapshot: step ${stepIndex + 1}/${project.steps.size}")
        } catch (e: Exception) {
            println("⚠️ Snapshot error: ${e.message}")
        }
    }

    /** Завершает проект. */
    suspend fun completeProject(projectId: String, summary: String? = null): Project? {
        val project = getProject(projectId) ?: return null
        val updated = project.copy(
            status = ProjectStatus.COMPLETED,
            updatedAt = System.currentTimeMillis()
        )
        saveProject(updated)
        if (summary != null) {
            memoryRepository.savePermanentFactFull(
                category = MEMORY_CATEGORY,
                key = "${project.id}_summary",
                value = summary,
                confidence = 0.5f,
                scope = "project",
                tags = null
            )
        }
        println("✅ Project completed: ${project.name}")
        return updated
    }

    /** Сохраняет проект в память. */
    private suspend fun saveProject(project: Project) {
        val header = "${project.id}|${project.name}|${project.description}|${project.status.name}|${project.currentStepIndex}|${project.createdAt}|${project.updatedAt}|${project.snapshotCount}"
        memoryRepository.savePermanentFactFull(
            category = MEMORY_CATEGORY,
            key = project.id,
            value = header,
            confidence = 0.5f,
            scope = "project",
            tags = null
        )
        // Сохраняем шаги отдельно
        val stepsJson = org.json.JSONArray()
        for (step in project.steps) {
            val obj = org.json.JSONObject()
            obj.put("id", step.id)
            obj.put("desc", step.description)
            obj.put("status", step.status.name)
            if (step.result != null) obj.put("result", step.result)
            stepsJson.put(obj)
        }
        memoryRepository.savePermanentFactFull(
            category = MEMORY_CATEGORY,
            key = "${project.id}_steps",
            value = stepsJson.toString(2),
            confidence = 0.5f,
            scope = "project",
            tags = null
        )
    }

    /** Десериализует проект из PermanentMemory. */
    private suspend fun deserialize(fact: PermanentMemory): Project? {
        return try {
            val parts = fact.value.split("|")
            if (parts.size < 7) return null
            val id = parts[0]
            val stepsFact = memoryRepository.getFactByCategoryAndKey(MEMORY_CATEGORY, "${id}_steps") ?: return null
            val stepsArr = org.json.JSONArray(stepsFact.value)
            val steps = (0 until stepsArr.length()).map { i ->
                val obj = stepsArr.getJSONObject(i)
                ProjectStep(
                    id = obj.getString("id"),
                    description = obj.getString("desc"),
                    status = StepStatus.valueOf(obj.getString("status")),
                    result = obj.optString("result", null)
                )
            }
            Project(
                id = id,
                name = parts[1],
                description = parts[2],
                status = ProjectStatus.valueOf(parts[3]),
                steps = steps,
                currentStepIndex = parts[4].toIntOrNull() ?: 0,
                createdAt = parts[5].toLongOrNull() ?: System.currentTimeMillis(),
                updatedAt = parts[6].toLongOrNull() ?: System.currentTimeMillis(),
                snapshotCount = parts.getOrNull(7)?.toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            println("⚠️ ProjectManager: deserialize error: ${e.message}")
            null
        }
    }

    suspend fun addSteps(projectId: String, newSteps: List<String>): Project? {
        val project = getProject(projectId) ?: return null
        val existingSteps = project.steps.toMutableList()
        val startIdx = existingSteps.size
        val stepsToAdd = newSteps.mapIndexed { i, s ->
            ProjectStep(id = "step_${startIdx + i}", description = s, status = StepStatus.PENDING)
        }
        existingSteps.addAll(stepsToAdd)
        // Reset currentStepIndex to the first new step so execute() picks them up
        val updated = project.copy(steps = existingSteps, currentStepIndex = startIdx, updatedAt = System.currentTimeMillis())
        saveProject(updated)
        println("📋 Added ${stepsToAdd.size} steps to project '${project.name}' (from step $startIdx)")
        return updated
    }
}
