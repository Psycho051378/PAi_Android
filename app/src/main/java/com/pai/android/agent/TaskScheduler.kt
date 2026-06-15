package com.pai.android.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import com.pai.android.data.repository.MemoryRepository
import com.pai.android.agent.FileManager
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Планировщик задач (Cron).
 *
 * Запускает задачи по расписанию, пока приложение активно.
 * Для фоновых задач (когда приложение закрыто) использует WorkManager.
 *
 * Принцип работы:
 * - Проверяет каждые 60 секунд, не наступило ли время для запуска задачи
 * - При совпадении времени — вызывает DecisionEngine с промптом задачи
 * - Результат сохраняет в permanent memory
 */
@Singleton
class TaskScheduler @Inject constructor(
    private val decisionEngineProvider: Provider<DecisionEngine>,
    private val persistentContext: PersistentContext,
    private val memoryRepository: MemoryRepository,
    private val proactiveTrigger: ProactiveTrigger? = null
) {
    private val _tasks = MutableStateFlow(ScheduledTask.DEFAULT_TASKS)
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    private val _lastRun = MutableStateFlow<Map<String, String>>(emptyMap()) // taskId -> date
    val lastRun: StateFlow<Map<String, String>> = _lastRun.asStateFlow()

    private var schedulerJob: Job? = null
    private var isRunning = false
    private var lastProactiveCheck = 0L

    init {
        loadPersistedTasks()
    }

    private fun loadPersistedTasks() {
        try {
            val fact = kotlinx.coroutines.runBlocking {
                memoryRepository.getFactByCategoryAndKey("scheduler", "tasks")
            } ?: return
            val json = org.json.JSONObject(fact.value)
            val tasksArray = json.getJSONArray("tasks")
            val loaded = mutableListOf<ScheduledTask>()
            for (i in 0 until tasksArray.length()) {
                val obj = tasksArray.getJSONObject(i)
                loaded.add(ScheduledTask(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    prompt = obj.getString("prompt"),
                    cronExpression = obj.optString("cron", ""),
                    enabled = obj.optBoolean("enabled", true),
                    intervalMinutes = obj.optInt("interval", 0),
                    lastRunAt = obj.optLong("lastRunAt", 0L)
                ))
            }
            if (loaded.isNotEmpty()) {
                // Merge with defaults — add any missing system tasks (e.g., new heartbeat)
                val loadedIds = loaded.map { it.id }.toSet()
                val missingDefaults = ScheduledTask.DEFAULT_TASKS.filter { it.id !in loadedIds }
                loaded.addAll(missingDefaults)
                _tasks.value = loaded
                println("?? TaskScheduler: loaded ${loaded.size} tasks (${missingDefaults.size} new defaults)")
                if (missingDefaults.isNotEmpty()) persistTasks()
            }
        } catch (e: Exception) {
            println("?? TaskScheduler: load error: ҉{e.message}")
        }
    }

    private fun persistTasks() {
        try {
            val json = org.json.JSONObject()
            val arr = org.json.JSONArray()
            for (task in _tasks.value) {
                val obj = org.json.JSONObject()
                obj.put("id", task.id)
                obj.put("name", task.name)
                obj.put("prompt", task.prompt)
                obj.put("cron", task.cronExpression)
                obj.put("enabled", task.enabled)
                obj.put("interval", task.intervalMinutes)
                obj.put("lastRunAt", task.lastRunAt)
                arr.put(obj)
            }
            json.put("tasks", arr)
            kotlinx.coroutines.runBlocking {
                memoryRepository.savePermanentFactFull(
                    category = "scheduler",
                    key = "tasks",
                    value = json.toString(2),
                    confidence = 0.5f, // ниже порога 0.7 — не лезет в общий контекст
                    scope = "user",
                    tags = null
                )
            }
        } catch (e: Exception) {
            println("⚠️ TaskScheduler: save error: ${e.message}")
        }
    }

    /**
     * Запускает планировщик.
     * @param scope CoroutineScope для работы планировщика
     * @param onTaskResult Callback для отправки результата пользователю (опционально)
     */
    fun start(scope: CoroutineScope, onTaskResult: ((String) -> Unit)? = null) {
        if (schedulerJob?.isActive != true) isRunning = false
        if (isRunning) return
        isRunning = true

        // Auto-recovery: restart scheduler if crashed
        val crashHandler = CoroutineExceptionHandler { _, e ->
            println("\u2757 Scheduler CRASHED: " + e.message + ". Restarting in 30s...")
            isRunning = false
            scope.launch {
                delay(30000)
                start(scope, onTaskResult)
            }
        }

        schedulerJob = scope.launch(Dispatchers.IO + crashHandler) {
            while (isActive) {
                try {
                    val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                    println("Scheduler check: time=${now} tasks=${_tasks.value.size}")
                    val dayOfWeek = java.text.SimpleDateFormat("E", Locale.US).format(Date()) // Mon, Tue...
                    val dayOfMonth = java.text.SimpleDateFormat("d", Locale.getDefault()).format(Date()).toIntOrNull() ?: 1
                    for (task in _tasks.value.filter { it.enabled }) {
                        val nowMs = System.currentTimeMillis()
                        
                        // Интервальная задача (каждые N минут)
                        if (task.isInterval) {
                            val elapsed = nowMs - task.lastRunAt
                            var shouldRun = elapsed >= task.intervalMinutes * 60_000L
                            if (!shouldRun) {
                                println("  task '" + task.name + "' interval=" + task.intervalMinutes + "min next=" + ((task.intervalMinutes * 60_000L - elapsed) / 60000) + "min")
                                continue
                            }
                            val updatedTask = task.copy(lastRunAt = nowMs)
                            val newTasks = _tasks.value.toMutableList()
                            val idx = newTasks.indexOfFirst { it.id == task.id }
                            if (idx >= 0) newTasks[idx] = updatedTask
                            _tasks.value = newTasks
                            persistTasks()
                            println("  task '" + task.name + "' interval=" + task.intervalMinutes + "min — ЗАПУСК")
                            launch { executeScheduledTask(task, nowMs) }
                            continue
                        }

                        val lastExecuted = _lastRun.value[task.id]
                        val cronParts = task.cronExpression.split(",").map { it.trim() }
                        val shouldRun = cronParts.any { cron ->
                            val parts = cron.split(" ").filter { it.isNotBlank() }
                            when (parts.size) {
                                1 -> parts[0] == now // "HH:MM" - daily
                                2 -> { // "DD HH:MM" (monthly), "dow HH:MM" (weekly) or "YYYY-MM-DD HH:MM" (one-time)
                                    val time = parts[1]
                                    if (time != now) false
                                    else {
                                        val first = parts[0]
                                        when {
                                            first.contains("-") -> first == today // one-time date
                                            first.length == 3 && first[0].isUpperCase() -> first == dayOfWeek // weekly
                                            else -> first.toIntOrNull() == dayOfMonth // monthly
                                        }
                                    }
                                }
                                else -> false
                            }
                        }
                        // Пропущенная задача: время уже прошло, но не выполнялась сегодня (только для recurring)
                        val isMissed = !shouldRun && lastExecuted != null && lastExecuted != today && cronParts.any { cron ->
                            val parts = cron.split(" ").filter { it.isNotBlank() }
                            val time = if (parts.size >= 1) parts.last() else ""
                            time < now // время задачи меньше текущего
                        }
                        println("  task '" + task.name + "' cron='" + task.cronExpression + "' shouldRun=" + shouldRun + " missed=" + isMissed + " last='" + (lastExecuted ?: "-") + "' now=" + now + " today=" + today)
                        if ((shouldRun || isMissed) && lastExecuted != today) {
                            println("⏰ TaskScheduler: запускаю '${task.name}'")
                            _lastRun.value = _lastRun.value + (task.id to today)
                            launch { executeScheduledTask(task) }
                        }
                    }

                    // Проактивный триггер (каждые ~5 мин)
                    if (proactiveTrigger != null) {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastProactiveCheck > 300_000L) {
                            lastProactiveCheck = nowMs
                            try {
                                val decision = proactiveTrigger.evaluate()
                                val msg = proactiveTrigger.formatForDecision(decision)
                                if (msg != null) {
                                    println("🔔 ProactiveTrigger: $msg")
                                    onTaskResult?.invoke(msg)
                                }
                            } catch (e: Exception) {
                                println("⚠️ ProactiveTrigger error: ${e.message}")
                            }
                        }
                    }

                    // Пауза 60 секунд между проверками
                    delay(5_000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("⚠️ TaskScheduler: ошибка цикла: ${e.message}")
                    delay(60_000L)
                }
            }
        }
    }

    /**
     * Останавливает планировщик.
     */
    fun stop() {
        schedulerJob?.cancel()
        isRunning = false
        println("⏰ TaskScheduler: остановлен")
    }

    /**
     * Добавляет/обновляет задачу.
     */
    fun addTask(task: ScheduledTask) {
        val current = _tasks.value.toMutableList()
        val idx = current.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            current[idx] = task
            // Сбрасываем lastRun при изменении cron (чтобы задача сработала по новому расписанию)
            _lastRun.value = _lastRun.value - task.id
        } else {
            current.add(task)
        }
        _tasks.value = current
        persistTasks()
    }

    /**
     * Удаляет задачу.
     */
    fun removeTask(taskId: String) {
        _tasks.value = _tasks.value.filter { it.id != taskId }
        persistTasks()
    }

    /**
     * Запускает задачу немедленно.
     */
    suspend fun runNow(taskId: String): String? {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return null
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val context = buildString {
            appendLine("Ручной запуск задачи: ${task.name}")
            appendLine("Дата: $today Время: $now")
        }

        return when (val response = decisionEngineProvider.get().processScheduledTask(query = task.prompt, context = context)) {
            is AgentResponse.Success -> {
                _lastRun.value = _lastRun.value + (task.id to today)
                response.answer
            }
            is AgentResponse.Error -> "❌ Ошибка: ${response.error}"
        }
    }

    /**
     * Проверяет, запущен ли планировщик.
     */
    /**
     * Выполняет задачу: вызывает DecisionEngine и обрабатывает результат.
     */
    private suspend fun executeScheduledTask(task: ScheduledTask, nowMs: Long = System.currentTimeMillis()) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val context = buildString {
            appendLine("Запланированная задача: ${task.name}")
            appendLine("Дата: $today Время: $now")
            try {
                val ctx = persistentContext.load()
                if (ctx?.lastQuery != null) {
                    appendLine("Последний запрос пользователя: ${ctx.lastQuery}")
                }
            } catch (_: Exception) {}
        }
        try {
            val response = decisionEngineProvider.get().processScheduledTask(
                query = task.prompt,
                context = context
            )
            when (response) {
                is AgentResponse.Success -> {
                    println("✅ TaskScheduler: '${task.name}' выполнен (${response.answer.take(100)}...)")
                    val answer = response.answer.trim()
                    val isSilent = answer.equals("ok", ignoreCase = true) || answer.length < 5
                    if (!isSilent) {
                        val msg = "✅ ${task.name}: ${answer.take(8000)}"
                        saveNotification(msg)
                        // Прямая доставка в чат через pendingNotificationFlow
                        com.pai.android.agent.DecisionEngine.pendingNotificationResult = msg
                    }
                }
                is AgentResponse.Error -> {
                    println("⚠️ TaskScheduler: '${task.name}' ошибка: ${response.error}")
                }
            }
        } catch (e: Exception) {
            println("⚠️ TaskScheduler: '${task.name}' ошибка: ${e.message}")
        }
    }

    fun isActive(): Boolean = isRunning
    private fun saveNotification(msg: String) {
        try {
            kotlinx.coroutines.runBlocking {
                val oldCtx = persistentContext.load() ?: PersistentContext.AgentContext()
                // Перезаписываем последнее уведомление (не накапливаем)
                val ctx = oldCtx.copy(
                    pendingTaskNotification = msg.take(8000),
                    lastChatId = oldCtx.lastChatId ?: com.pai.android.agent.DecisionEngine.lastChatId
                )
                persistentContext.save(ctx)
                println("📬 saveNotification: сохранено (${msg.take(40)}...)")
            }
        } catch (e: Exception) {
            println("❌ saveNotification error: ${e.message}")
        }
    }
}


