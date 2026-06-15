package com.pai.android.agent.tools

import com.pai.android.agent.AgentTool
import com.pai.android.agent.FileManager
import com.pai.android.agent.ProjectManager
import com.pai.android.agent.ToolResult
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.Message
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProjectAnalyzerTool — анализирует проект на целостность и ошибки.
 *
 * Собирает все файлы проекта, отправляет LLM для анализа связности,
 * импортов, отсутствующих компонентов и логических несоответствий.
 */
@Singleton
class ProjectAnalyzerTool @Inject constructor(
    private val aiRepository: AiRepository,
    private val fileManager: FileManager,
    private val projectManager: ProjectManager
) : AgentTool {

    override val name: String = "project_analyzer"

    override val description: String = """
        Analyze project integrity: check imports, file connections,
        missing components, function call mismatches.
        Parameters:
        - project (required): project name or ID
        - check_depth (optional): "quick" (structure only) or "deep" (full code analysis)
    """.trimIndent()

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "project": {
                    "type": "string",
                    "description": "Имя или ID проекта для анализа"
                },
                "check_depth": {
                    "type": "string",
                    "enum": ["quick", "deep"],
                    "default": "quick",
                    "description": "Глубина проверки: quick - структура, deep - полный анализ"
                }
            },
            "required": ["project"]
        }
    """.trimIndent()

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val projectName = params["project"] as? String
                ?: params["name"] as? String
                ?: return ToolResult.Error("Не указан проект для анализа")

            // Ищем проект по имени или ID
            val allProjects = projectManager.listProjects()
            val project = allProjects.find { it.id == projectName || it.name.lowercase().contains(projectName.lowercase()) }
                ?: return ToolResult.Error("Проект '$projectName' не найден")

            val depth = params["check_depth"] as? String ?: "quick"

            // Получаем все файлы проекта через FileManager
            // Ищем папку проекта в workspace: сначала по точному совпадению, потом по списку папок
            val projectAlias = project.name.lowercase().replace(" ", "_").replace("[", "").replace("]", "").trim()
            var projectFolder = projectAlias
            
            // Если файлы не найдены — сканируем корень workspace в поисках подходящей папки
            val rootFiles = fileManager.listFiles("", recursive = false)
            val matchedDir = rootFiles.firstOrNull { f ->
                f.isDirectory && (f.name.equals(projectAlias, ignoreCase = true) ||
                    project.name.lowercase().contains(f.name.lowercase()) ||
                    f.name.lowercase().contains(projectAlias))
            }
            if (matchedDir != null) {
                projectFolder = matchedDir.path
            }
            
            val files = fileManager.listFiles(projectFolder, recursive = true)

            if (files.isEmpty()) {
                return ToolResult.Success(
                    output = "В проекте '${project.name}' не найдено файлов для анализа.",
                    data = mapOf("project" to project.name, "files_count" to 0, "goal_achieved" to true)
                )
            }

            // Собираем содержимое файлов для анализа
            val filesContent = buildString {
                appendLine("Структура проекта '${project.name}':")
                appendLine("Всего файлов: ${files.size}")
                appendLine()

                for (file in files) {
                    val content = fileManager.readFile(file.path)
                    val preview = if (content != null) {
                        if (depth == "deep") content
                        else content.take(2000)
                    } else {
                        "[файл не читается]"
                    }
                    appendLine("--- ${file.path} (${file.size} байт) ---")
                    appendLine(preview)
                    appendLine()
                }
            }

            // Отправляем LLM на анализ
            val analysisPrompt = buildString {
                appendLine("Проанализируй проект '$projectName'. Найди:")
                appendLine("1. Отсутствующие файлы или импорты")
                appendLine("2. Несоответствия в вызовах функций между файлами")
                appendLine("3. Логические ошибки или неполные реализации")
                appendLine("4. Рекомендации по улучшению")
                appendLine()
                append(filesContent)
                appendLine()
                appendLine("Формат ответа: краткий список проблем (если есть) или 'Проект корректен.'")
            }

            val response = aiRepository.sendMessage(
                messages = listOf(
                    Message.createSystemMessage("analyzer", "Ты — экспертный анализатор кода. Находишь ошибки и несоответствия в проектах."),
                    Message.createUserMessage("analyzer", analysisPrompt)
                ),
                systemPrompt = "Анализатор проектов. Отвечай кратко и по делу.",
                memoryContext = ""
            )

            val analysisResult = if (response.isSuccess) {
                response.getOrThrow().text.trim()
            } else {
                "Не удалось выполнить анализ (ошибка AI). Проверь структуру вручную."
            }

            val report = buildString {
                appendLine("📊 Анализ проекта '${project.name}':")
                appendLine("   Файлов: ${files.size}")
                appendLine("   Папок: ${files.count { it.isDirectory }}")
                appendLine()
                append(analysisResult)
            }

            ToolResult.Success(
                output = report,
                data = mapOf(
                    "project" to project.name,
                    "files_analyzed" to files.size,
                    "analysis" to analysisResult,
                    "goal_achieved" to true
                )
            )

        } catch (e: Exception) {
            ToolResult.Error("Ошибка анализа проекта: ${e.message}")
        }
    }
}
