package com.pai.android.agent.tools

import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ScheduledTask
import com.pai.android.agent.TaskScheduler
import com.pai.android.agent.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TaskSchedulerTool - управление запланированными задачами через диалог.
 *
 * LLM вызывает этот инструмент, когда пользователь просит:
 * - "напомни в 15:00 проверить почту"
 * - "добавь ежедневный мониторинг в 9 утра"
 * - "удали задачу"
 * - "покажи список задач"
 */
@Singleton
class TaskSchedulerTool @Inject constructor(
    private val taskScheduler: TaskScheduler
) : BaseAgentTool() {

    override val name: String = "task_scheduler"

    override val description: String = """
        Manage scheduled tasks: create, delete, list.
        Parameters:
        - command (required): "list", "add", "remove", "run_now"
        - task_id: task ID (for remove, run_now)
        - task_name: task name (for add)
        - task_prompt: what the task does (for add). For email monitoring use 'watch:from=EMAIL' 
        - task_time: time in HH:MM format (for add). Optional if interval is set.
        - interval: repeat every N minutes (for add). Use with watch:from= in task_prompt.
          Example: interval=5, task_prompt="watch:from=user@mail.com" checks email every 5 min.
    """.trimIndent()

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["list", "add", "remove", "run_now"],
                    "description": "Команда: list - список задач, add - добавить, remove - удалить, run_now - запустить сейчас"
                },
                "task_id": {
                    "type": "string",
                    "description": "ID задачи (для remove, run_now)"
                },
                "task_name": {
                    "type": "string",
                    "description": "Название задачи (для add)"
                },
                "task_prompt": {
                    "type": "string",
                    "description": "Что должна делать задача (для add). Для мониторинга почты используй: watch:from=email@domain.com"
                },
                "task_time": {
                    "type": "string",
                    "description": "Время запуска в формате HH:MM, например 09:00 (для add). Если указан interval — необязательно"
                },
                "task_cron": {
                    "type": "string",
                    "description": "Cron-выражение (если нужно сложное расписание)"
                },
                "interval": {
                    "type": "integer",
                    "description": "Повторять каждые N минут (для add). Укажи 5 для проверки каждые 5 мин. Вместе с task_prompt=\"watch:from=...\""
                }
            },
            "required": ["command"]
        }
    """.trimIndent()

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val rawCommand = params["command"] as? String
            // Если command == имени инструмента - LLM передала tool name, определяем action по params
            // Определяем, есть ли task-параметры (add/remove/list)
            val hasTaskParams = params.any { (k, _) -> k.startsWith("task_") }
            val hasAddParams = params.containsKey("task_time") || params.containsKey("task_cron") || (params.containsKey("task_prompt") && params.containsKey("task_name"))
            val hasRemoveParams = params.containsKey("task_id") || params.containsKey("task_name")

            val explicitAction = params["action"] as? String ?: params["task_action"] as? String
            val command = if (rawCommand == name || rawCommand == "tool_$name") {
                explicitAction ?: when {
                    hasAddParams -> "add"
                    hasRemoveParams -> "remove"
                    else -> "list"
                }
            } else {
                rawCommand ?: explicitAction ?: when {
                    hasAddParams -> "add"
                    hasRemoveParams -> "remove"
                    else -> "list"
                }
            }

            when (command) {
                "list" -> executeList()
                "add" -> executeAdd(params)
                "remove" -> executeRemove(params)
                "run_now" -> executeRunNow(params)
                else -> ToolResult.Error("Неизвестная команда: $command")
            }
        } catch (e: Exception) {
            ToolResult.Error("Ошибка: ${e.message}")
        }
    }

    private fun executeList(): ToolResult {
        val tasks = taskScheduler.tasks.value
        if (tasks.isEmpty()) {
            return ToolResult.Success(output = "Нет запланированных задач.")
        }
        val output = buildString {
            appendLine("📋 Запланированные задачи:")
            for (task in tasks) {
                val status = if (task.enabled) "✅" else "⏸️"
                val lastRun = taskScheduler.lastRun.value[task.id]
                val lastRunStr = if (lastRun != null) " (последний запуск: $lastRun)" else ""
                appendLine("$status ${task.name} [${task.id}]")
                appendLine("   ⏰ ${task.cronExpression} | ${task.prompt.take(80)}...$lastRunStr")
            }
        }
        return ToolResult.Success(output = output.toString(), data = mapOf("tasks" to tasks.size, "goal_achieved" to true))
    }

    private fun executeAdd(params: Map<String, Any>): ToolResult {
        val name = params["task_name"] as? String ?: return ToolResult.Error("Укажите название задачи (task_name)")
        val prompt = params["task_prompt"] as? String ?: name  // если нет промпта, используем название
        
        // Интервальная задача (email watch) — каждые N минут
        val interval = params["interval"] as? Int 
            ?: (params["task_interval"] as? Int)
            ?: (params["interval_minutes"] as? Int)
            ?: 0
        
        if (interval > 0) {
            val id = "watch_${System.currentTimeMillis()}"
            // prompt может быть email'ом от AI или уже содержать watch:from=
            val watchPrompt = if (prompt.startsWith("watch:") || prompt.startsWith("email_watch:")) {
                prompt
            } else {
                "watch:from=$prompt"
            }
            val task = ScheduledTask(
                id = id, 
                name = name, 
                prompt = watchPrompt, 
                cronExpression = "", 
                intervalMinutes = interval
            )
            taskScheduler.addTask(task)
            return ToolResult.Success(
                output = "✅ Задача '${task.name}' — проверка каждые ${interval} мин. ID: ${task.id}",
                data = mapOf("task_id" to task.id, "interval" to interval, "goal_achieved" to true)
            )
        }

        var time = params["task_time"] as? String ?: params["time"] as? String
        if (time != null) {
            time = time.replace('.', ':')
            if (!time.contains(":")) time = time.take(2) + ":" + time.drop(2)
        }
        if (time == null) return ToolResult.Error("Укажите время в формате HH:MM (task_time) или interval (в минутах)")
        val cron = params["task_cron"] as? String ?: time

        val id = "task_${System.currentTimeMillis()}_${name.filter { it.isLetterOrDigit() }.take(20)}"
        val task = ScheduledTask(id = id, name = name, prompt = prompt, cronExpression = cron)
        taskScheduler.addTask(task)

        return ToolResult.Success(
            output = "✅ Задача '${task.name}' добавлена на ${task.cronExpression}. ID: ${task.id}",
            data = mapOf("task_id" to task.id, "goal_achieved" to true)
        )
    }

    private fun findTask(query: String): ScheduledTask? {
        val tasks = taskScheduler.tasks.value
        val lowerQuery = query.lowercase()
        // По ID
        tasks.firstOrNull { it.id == query }?.let { return it }
        // По точному имени
        tasks.firstOrNull { it.name.equals(query, ignoreCase = true) }?.let { return it }
        // По части имени (в обе стороны: имя содержит запрос ИЛИ запрос содержит имя)
        tasks.firstOrNull { task ->
            val lowerName = task.name.lowercase()
            lowerName.contains(lowerQuery) || lowerQuery.contains(lowerName)
        }?.let { return it }
        // По части промпта
        tasks.firstOrNull { it.prompt.contains(query, ignoreCase = true) }?.let { return it }
        return null
    }
    
    private fun executeRemove(params: Map<String, Any>): ToolResult {
        val taskId = params["task_id"] as? String
            ?: params["task_name"] as? String
            ?: return ToolResult.Error("Укажите название или ID задачи. Например: удали задачу уборка")
        val task = findTask(taskId)
            ?: return ToolResult.Error("Задача '$taskId' не найдена. Активные задачи:\n${taskScheduler.tasks.value.joinToString("\n") { "  - ${it.name} (ID: ${it.id.take(20)}...)" }}")
        
        taskScheduler.removeTask(task.id)
        return ToolResult.Success(
            output = "✅ Задача '${task.name}' удалена",
            data = mapOf("task_id" to task.id, "goal_achieved" to true)
        )
    }
    
    private suspend fun executeRunNow(params: Map<String, Any>): ToolResult {
        val taskId = params["task_id"] as? String
            ?: params["task_name"] as? String
            ?: return ToolResult.Error("Укажите название или ID задачи")
        val task = findTask(taskId)
            ?: return ToolResult.Error("Задача '$taskId' не найдена")

        val result = taskScheduler.runNow(task.id)
        return if (result != null) {
            ToolResult.Success(
                output = "✅ Задача '${task.name}' выполнена.\n\n$result",
                data = mapOf("task_id" to task.id, "result" to result, "goal_achieved" to true)
            )
        } else {
            ToolResult.Error("Не удалось выполнить задачу '${task.name}'")
        }
    }
}
