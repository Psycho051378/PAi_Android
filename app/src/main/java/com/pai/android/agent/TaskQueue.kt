package com.pai.android.agent

import com.pai.android.data.model.Message
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.repository.MemoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TaskQueue — декомпозиция больших запросов на последовательность малых.
 */
@Singleton
class TaskQueue @Inject constructor(
    private val aiRepository: AiRepository,
    private val reactAgent: ReActAgent,
    private val projectManager: ProjectManager,
    private val persistentContext: PersistentContext,
    private val defaultDispatcher: CoroutineDispatcher,
    private val fileManager: FileManager,
    private val codeGenerator: CodeGenerator
) {
    suspend fun execute(projectId: String, query: String): AgentResponse {
        return withContext(defaultDispatcher) {
            try {
                val project = projectManager.getProject(projectId)
                if (project == null) return@withContext AgentResponse.Error(error = "Project not found: $projectId")
                println("📋 TaskQueue: executing project '${project.name}' (${project.steps.size} steps)")
                var stepIndex = project.currentStepIndex
                val results = mutableListOf<String>()
                var currentProject = project
                while (stepIndex < project.steps.size) {
                    val freshProject = projectManager.getProject(projectId) ?: break
                    currentProject = freshProject
                    val step = currentProject.steps[stepIndex]
                    if (step.status == ProjectManager.StepStatus.DONE) { stepIndex++; continue }
                    if (step.status == ProjectManager.StepStatus.FAILED) {
                        projectManager.updateStep(projectId, stepIndex, ProjectManager.StepStatus.PENDING)
                    }
                    println("▶️ Step ${stepIndex + 1}/${project.steps.size}: ${step.description}")
                    projectManager.updateStep(projectId, stepIndex, ProjectManager.StepStatus.IN_PROGRESS)
                    val projectFolder = project.name.lowercase()
                        .replace(" ", "_").replace("[", "").replace("]", "").trim()
                    val projDir = persistentContext.load()?.projectDirectory ?: "projects"

                    val stepQuery = buildString {
                        append("Project: ").append(project.name).append("\n")
                        append("Location: workspace/").append(projDir).append("/").append(projectFolder).append("/\n")
                        append("Step ").append(stepIndex + 1).append("/").append(project.steps.size).append(": ").append(step.description).append("\n")
                        if (results.isNotEmpty()) {
                            append("Files created so far:\n")
                            results.forEachIndexed { i, r -> append("  " + (i + 1) + ". " + r.take(200) + "\n") }
                        }
                                                // Include existing file content for context (steps after first)
                        if (stepIndex > 0) {
                            val projectPath = projDir + "/" + projectFolder
                            try {
                                val files = fileManager.listFiles(projectPath)
                                val codeFiles = files.filter { fi ->
                                    fi.path.endsWith(".html") || fi.path.endsWith(".js") || fi.path.endsWith(".css") || 
                                    fi.path.endsWith(".py") || fi.path.endsWith(".kt") || fi.path.endsWith(".json")
                                }
                                if (codeFiles.isNotEmpty()) {
                                    append("\nEXISTING FILES:\n")
                                    codeFiles.forEach { fi ->
                                        append("--- " + fi.path + " ---\n")
                                        val content = fileManager.readFile(fi.path)
                                        if (content != null && content.length < 4000) {
                                            append(content + "\n")
                                        } else if (content != null) {
                                            append(content.take(4000) + "\n[... truncated]\n")
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        append("\nIMPORTANT: Actually WRITE code. READ existing files above, then MODIFY or APPEND.")
                        append(" If file doesn't exist, CREATE it with complete working code.")
                        append(" Do NOT skip. Write real code now.")
                    }

                    val stepResult: String
                    println("🎯 CodeGenerator for step " + (stepIndex + 1))
                    val projectDirPath = "projects/" + projectFolder
                    val (success, msg) = codeGenerator.generate(
                        stepDescription = step.description,
                        projectContext = "Project: " + project.name + " | Location: " + projectDirPath,
                        existingFiles = stepQuery.toString(),
                        projectDir = projectDirPath
                    )
                    stepResult = if (success) msg else "FAILED: " + msg

                    if (!stepResult.startsWith("FAILED")) {
                        results.add(stepResult)
                        results.add(stepResult)
                        // Verify files were actually modified (check timestamps)
                        val projectPath = projDir + "/" + projectFolder
                            val filesBefore = try {
                                fileManager.listFiles(projectPath).associate { it.path to it.lastModified }
                            } catch (e: Exception) { emptyMap() }
                            projectManager.updateStep(
                                projectId, stepIndex,
                                ProjectManager.StepStatus.DONE, stepResult.take(500),
                                workspaceDir = fileManager.workspaceRoot.absolutePath + "/" + projectPath
                            )
                            val filesAfter = try { fileManager.listFiles(projectPath).associate { it.path to it.lastModified } } catch (e: Exception) { emptyMap() }
                            val anyModified = filesAfter.any { (path, time) -> filesBefore[path] != time }
                            if (!anyModified && stepIndex > 0) {
                                println("⚠️ Step ${stepIndex + 1}: files NOT modified, retrying...")
                                projectManager.updateStep(projectId, stepIndex, ProjectManager.StepStatus.FAILED, "No file changes detected")
                                stepIndex++
                                continue
                            }
                            println("✅ Step ${stepIndex + 1} done")
                        }
                    stepIndex++
                }  // end while
                projectManager.completeProject(projectId)

                val initiativeResp = aiRepository.sendMessage(
                    messages = listOf(Message.createUserMessage("initiative",
                        "Project " + project.name + " done.\\nSteps: " + project.steps.joinToString(", ") { it.description.take(30) } +
                        "\\n\\nSuggest 2-3 improvements.")),
                    systemPrompt = "Project reviewer. Suggest 2-3 improvements.",
                    memoryContext = ""
                )
                val suggestion = if (initiativeResp.isSuccess) initiativeResp.getOrThrow().text.trim() else ""
                if (suggestion.isNotBlank()) {
                    println("💡 Initiative: " + suggestion)
                    // Save to PC so it gets delivered on next interaction
                    try {
                        val cur = persistentContext.load() ?: PersistentContext.AgentContext()
                        persistentContext.save(cur.copy(
                            pendingInitiativeProjectId = projectId,
                            pendingInitiativeSuggestion = suggestion
                        ))
                    } catch (_: Exception) { }
                }

                val cleanResults = results.map { r ->
                    r.replace(Regex("""Completed \(forced stop after \d+ repeats\)[. ]*"""), "")
                        .replace(Regex("""^Достигнут лимит шагов.*"""), "").trim()
                }.filter { it.isNotBlank() }
                val rawSteps = buildString { cleanResults.forEach { appendLine("  - " + it) } }
                val summaryText = try {
                    val fmtResp = aiRepository.sendMessage(
                        messages = listOf(Message.createUserMessage("format",
                            "Project: " + project.name + "\nSteps:\n" + rawSteps +
                            "\n\nOriginal request: " + project.description +
                            "\nFormat as clean report, 3-4 emoji bullet points.")),
                        systemPrompt = "Project report formatter. Be brief, with emoji.",
                        memoryContext = ""
                    )
                    if (fmtResp.isSuccess) fmtResp.getOrThrow().text.trim() else rawSteps.toString()
                } catch (_: Exception) { rawSteps.toString() }
                val finalAnswer = if (suggestion.isNotBlank()) {
                    summaryText + "\n\n" +
                    "💡 **Инициатива:** Хочешь, улучшим проект?\n" + suggestion.take(500)
                } else summaryText
                AgentResponse.Success(answer = finalAnswer, thoughts = emptyList(), actions = emptyList())
            } catch (e: Exception) {
                AgentResponse.Error(error = "TaskQueue error: ${e.message}")
            }
        }
    }

    suspend fun planAndExecute(query: String, autoApprove: Boolean = false): AgentResponse {
        return withContext(defaultDispatcher) {
            try {
                val planPrompt = buildString {
                    try {
                        val projects = projectManager.listProjects()
                        val latest = projects.filter { it.status == ProjectManager.ProjectStatus.ACTIVE || it.status == ProjectManager.ProjectStatus.COMPLETED }
                            .maxByOrNull { it.updatedAt }
                        if (latest != null) {
                            append("CURRENT PROJECT: \"").append(latest.name).append("\" (").append(latest.status.name).append(")\n")
                            append("Description: ").append(latest.description.take(200)).append("\n")
                            append("Steps (").append(latest.steps.size).append("):\n")
                            latest.steps.forEachIndexed { i, s -> append("  ").append(i+1).append(". ").append(s.description.take(100)).append(" [" + s.status.name + "]\n") }
                            append("\n")
                        }
                    } catch (_: Exception) {}
                    append("Decompose this request into 2-4 LARGE steps. Return JSON array. Use the same language as the request.\n")
                    append("If continuing an existing project, add 1-2 steps to finish it.\n")
                    append("If this is a NEW project, ignore current project context.\n")
                    append("IMPORTANT: Each step must produce COMPLETE, WORKING code. Do NOT split into tiny steps.\n")
                    append("Each step must include full file paths.\n")
                    append("Example: \"Create COMPLETE game in index.html with full HTML, CSS and JS\" not \"Create HTML structure\".\n")
                    append("Request: ").append(query).append("\n")
                    appendLine("")
                    appendLine("Python execution (Chaquopy 3.12.4) is available on Android.")
                    appendLine("Add exec block after creating Python files: --- exec: python3 projects/NAME/filename.py ---")
                    appendLine("Packages: psutil, requests, beautifulsoup4, flask, PyYAML, openpyxl, etc.")
                    appendLine("If the request asks to run/execute a script, ADD an execution step after the creation step.")
                    append("Format: [\"step 1\", \"step 2\", ...]")
                }
                val response = aiRepository.sendMessage(
                    messages = listOf(Message.createSystemMessage("planner", planPrompt), Message.createUserMessage("planner", query)),
                    systemPrompt = "JSON array of strings only.",
                    memoryContext = ""
                )
                if (!response.isSuccess) return@withContext AgentResponse.Error(error = "Failed to plan")
                val text = response.getOrThrow().text.trim()
                val steps = parseSteps(text)
                if (steps.isEmpty()) {
                    println("⚠️ TaskQueue: could not decompose, running raw ReAct")
                    return@withContext reactAgent.processRequest(query = query, maxSteps = 25)
                }
                val projectName = extractProjectName(query)
                val project = projectManager.createProject(name = projectName, description = query, steps = steps)
                if (autoApprove) {
                    println("📋 План готов, autoApprove=true, выполняю: " + projectName)
                    return@withContext execute(project.id, query)
                }
                val planDisplay = buildString {
                    appendLine("📋 План проекта \u0022" + projectName + "\u0022:")
                    appendLine("Всего шагов: " + steps.size)
                    steps.forEachIndexed { i, s -> appendLine("  " + (i+1) + ". " + s) }
                    appendLine()
                    appendLine("Утверждаешь план? Ответь \"да\" чтобы начать, или напиши что изменить.")
                }
                println("📋 План готов, ожидаю утверждения: " + projectName)
                try {
                    val ctx = persistentContext.load() ?: PersistentContext.AgentContext()
                    persistentContext.save(ctx.copy(activeProjectId = project.id, pendingApprovalProjectId = project.id, pendingApprovalPlan = planDisplay))
                } catch (_: Exception) { }
                return@withContext AgentResponse.Success(answer = planDisplay, thoughts = emptyList(), actions = emptyList())
            } catch (e: Exception) {
                AgentResponse.Error(error = "TaskQueue error: ${e.message}")
            }
        }
    }

    private fun parseSteps(t: String): List<String> {
        return try {
            val s = t.indexOf('[')
            val e = t.lastIndexOf(']') + 1
            if (s < 0 || e <= s) return emptyList()
            val arr = JSONArray(t.substring(s, e))
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun extractProjectName(query: String): String {
        for (c in listOf('\"', '\'', '«', '»')) {
            val idx = query.indexOf(c)
            if (idx >= 0) {
                val end = query.indexOf(c, idx + 1)
                if (end > idx) return query.substring(idx + 1, end).trim().take(30)
            }
        }
        return try {
            val resp = runBlocking {
                aiRepository.sendMessage(
                    messages = listOf(Message.createUserMessage("extract",
                        "Extract the project name from this request. Return ONLY the name (1-3 words). " +
                        "If no specific name, return 'project'.\\n\\nRequest: " + query)),
                    systemPrompt = "Extract project name. Return one short name only.",
                    memoryContext = ""
                )
            }
            val text = if (resp.isSuccess) resp.getOrThrow().text.trim() else ""
            if (text.isNotBlank() && text.length in 2..40 && (text.split(" ").size <= 3 || !text.contains(" "))) {
                text.take(30)
            } else {
                query.split(" ").firstOrNull()?.take(20)?.trim() ?: "project"
            }
        } catch (e: Exception) {
            query.split(" ").firstOrNull()?.take(20)?.trim() ?: "project"
        }
    
}}