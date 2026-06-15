package com.pai.android.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.pai.android.data.model.ContextSnapshot
import com.pai.android.data.model.NotificationEvent
import com.pai.android.data.service.LocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import com.pai.android.data.repository.GeoTaskRepository
import com.pai.android.data.model.GeoTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Кольцевой буфер для временного хранения уведомлений.
 * Автоматически вытесняет самые старые при превышении maxSize.
 */
class CircularBuffer<T>(private val maxSize: Int) {
    private val buffer = ArrayDeque<T>(maxSize)

    @Synchronized
    fun add(item: T) {
        if (buffer.size >= maxSize) {
            buffer.removeFirst()
        }
        buffer.addLast(item)
    }

    @Synchronized
    fun toList(): List<T> = buffer.toList()

    @Synchronized
    fun clear() { buffer.clear() }

    val size: Int get() = buffer.size
}

/**
 * Движок контекста.
 *
 * Собирает данные из всех источников (локация, уведомления, система, задачи)
 * и предоставляет единый снимок контекста для DecisionEngine.
 *
 * Используется для:
 * - Обогащения AI-промпта текущим контекстом
 * - Проактивных подсказок (ProactiveTrigger)
 * - Принятия решений на основе окружения
 */
@Singleton
class ContextEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationService: LocationService,
    private val skillRegistry: SkillRegistry,
    private val proactiveTrigger: ProactiveTrigger,
    private val geoTaskRepository: GeoTaskRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val notificationBuffer = CircularBuffer<NotificationEvent>(50)

    // Защита от спама уведомлений о батарее
    @Volatile
    private var lastBatteryAlertTime = 0L
    private val batteryAlertCooldownMs = 300_000L // 5 минут

    init {
        registerBatteryReceiver()
    }

    /**
     * Регистрирует приёмник для отслеживания низкого заряда батареи.
     * Срабатывает сразу при достижении порога (обычно 15%).
     */
    private fun registerBatteryReceiver() {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_LOW)
            context.registerReceiver(batteryReceiver, filter)
            android.util.Log.i(TAG, "Battery low receiver registered")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "registerBatteryReceiver error", e)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_LOW) {
                if (!isProactiveEnabled()) return // проактивность выключена
                val now = System.currentTimeMillis()
                if (now - lastBatteryAlertTime < batteryAlertCooldownMs) return
                lastBatteryAlertTime = now

                val (charging, percent) = getBatteryInfo()
                if (charging || percent > 15) return // Если на зарядке или уже зарядилась

                android.util.Log.w(TAG, "⚠️ Battery low: $percent%")

                val msg = "{\"action\":\"proactive_suggest\",\"text\":\"Critical battery: $percent%. Please charge.\",\"priority\":100}"
                DecisionEngine.pendingNotificationResult = msg
            }
        }
    }

    @Volatile
    private var latestSnapshot: ContextSnapshot? = null

    @Volatile
    private var isUpdating = false

    /**
     * Получает текущий снимок контекста.
     * Если кеш свежий (< 2 мин) — возвращает его.
     * Иначе — собирает новый снимок.
     */
    suspend fun getContext(): ContextSnapshot {
        val cached = latestSnapshot
        if (cached != null && cached.isFresh && !isUpdating) {
            return cached
        }

        return refreshContext()
    }

    /**
     * Принудительно обновляет контекст (все источники).
     */
    suspend fun refreshContext(): ContextSnapshot {
        isUpdating = true
        return try {
            val deferred = coroutineScope {
                val locationDeferred = async { locationService.getCachedLocation() }
                val notificationsDeferred = async { getRecentNotifications() }
                val batteryDeferred = async { getBatteryInfo() }
                val tasksDeferred = async { getActiveTasks() }

                val location = locationDeferred.await()
                val notifications = notificationsDeferred.await()
                val battery = batteryDeferred.await()
                val tasks = tasksDeferred.await()

                // Проверяем низкий заряд при каждом обновлении контекста
                checkBatteryLevel(battery.first, battery.second)

                ContextSnapshot(
                    location = location,
                    recentNotifications = notifications,
                    activeTasks = tasks,
                    systemInfo = ContextSnapshot.SystemInfo(
                        isCharging = battery.first,
                        batteryLevel = battery.second
                    )
                )
            }

            val snapshot = deferred
            latestSnapshot = snapshot

            // Проактивная оценка контекста
            try {
                val suggestions = proactiveTrigger.evaluate(snapshot)
                val best = suggestions.firstOrNull()
                if (best != null) {
                    val msg = "{\"action\":\"proactive_suggest\",\"text\":\"" + best.text + "\",\"priority\":" + best.priority + "}"
                    DecisionEngine.pendingNotificationResult = msg
                }
            } catch (_: Exception) {}

            snapshot
        } catch (e: Exception) {
            android.util.Log.e(TAG, "refreshContext error", e)
            latestSnapshot ?: ContextSnapshot()
        } finally {
            isUpdating = false
        }
    }

    /**
     * Форматирует контекст для AI-промпта.
     */
    suspend fun formatForPrompt(): String {
        val snapshot = getContext()
        val base = snapshot.formatForPrompt()
        val geoText = formatGeoTasksForPrompt()
        return if (geoText.isNotBlank()) "$base\n$geoText" else base
    }

    /**
     * Добавляет активные гео-задачи в промпт для AI.
     */
    private suspend fun formatGeoTasksForPrompt(): String {
        if (!proactiveTrigger.settings.locationBasedHints) return ""
        return try {
            val tasks = geoTaskRepository.getActive()
            if (tasks.isEmpty()) return ""
            val sb = StringBuilder()
            sb.appendLine()
            sb.appendLine("📍 **Активные гео-задачи:**")
            val loc = locationService.getCachedLocation()
            tasks.forEach { task ->
                val dist = if (loc != null) {
                    val loc1 = android.location.Location("").also { it.latitude = loc.latitude; it.longitude = loc.longitude }
                    val loc2 = android.location.Location("").also { it.latitude = task.latitude; it.longitude = task.longitude }
                    val d = loc1.distanceTo(loc2).toInt()
                    if (d < 1000) "${d}м" else "${d / 1000}км"
                } else ""
                val distStr = if (dist.isNotBlank()) " (≈$dist)" else ""
                val place = if (task.address != null) " у ${task.address}" else ""
                sb.appendLine("  • `${task.label}`$place$distStr")
            }
            sb.toString()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "formatGeoTasksForPrompt error", e)
            ""
        }
    }

    /**
     * Добавляет уведомление в кольцевой буфер.
     * Вызывается из NotificationListener при каждом новом уведомлении.
     */
    /**
     * Доступен ли проактивный режим (проверка для внешних компонентов).
     */
    fun isProactiveEnabled(): Boolean {
        return proactiveTrigger.settings.enabled
    }

    fun pushNotification(event: NotificationEvent) {
        notificationBuffer.add(event)
        // Сбрасываем кеш, чтобы следующий getContext() пересобрал снимок со свежими уведомлениями
        latestSnapshot = null
    }

    /**
     * Возвращает список последних уведомлений из кольцевого буфера.
     */
    fun getRecentNotifications(): List<NotificationEvent> {
        return notificationBuffer.toList()
    }

    /**
     * Проверяет уровень батареи и отправляет уведомление если низкий.
     * Срабатывает при каждом refreshContext, не чаще раза в 5 минут.
     */
    private fun checkBatteryLevel(charging: Boolean, percent: Int) {
        if (!isProactiveEnabled()) return
        if (charging || percent > BATTERY_LOW_THRESHOLD) return
        val now = System.currentTimeMillis()
        if (now - lastBatteryAlertTime < batteryAlertCooldownMs) return
        lastBatteryAlertTime = now
        android.util.Log.w(TAG, "⚠️ Battery low: $percent%")
        val msg = "{\"action\":\"proactive_suggest\",\"text\":\"Critical battery: $percent%. Please charge.\",\"priority\":100}"
        DecisionEngine.pendingNotificationResult = msg
    }

    /**
     * Получает информацию о батарее.
     * @return Pair(charging, batteryPercent)
     */
    private fun getBatteryInfo(): Pair<Boolean, Int> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val percent = (level * 100) / scale
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                Pair(charging, percent)
            } else {
                Pair(false, 0)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getBatteryInfo error", e)
            Pair(false, 0)
        }
    }

    /**
     * Получает список активных задач из TaskScheduler.
     */
    private fun getActiveTasks(): List<ContextSnapshot.TaskInfo> {
        return try {
            // Пробуем получить TaskScheduler через SkillRegistry
            val tasks = skillRegistry.getSkill("task_scheduler")
            if (tasks != null) {
                // Вызываем task_scheduler с командой status
                val result = runBlocking {
                    tasks.execute(mapOf("command" to "task_scheduler_status"))
                }
                if (result is SkillResult.Success) {
                    // Парсим результат — получаем список задач
                    parseTasksFromResult(result.message)
                } else emptyList()
            } else emptyList()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getActiveTasks error", e)
            emptyList()
        }
    }

    /**
     * Парсит список задач из текстового результата TaskScheduler.
     */
    private fun parseTasksFromResult(text: String): List<ContextSnapshot.TaskInfo> {
        val tasks = mutableListOf<ContextSnapshot.TaskInfo>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("|") && line.contains("Задача")) {
                // Пропускаем заголовок таблицы
                i++
                continue
            }
            if (line.startsWith("|") && line.contains("|")) {
                val parts = line.split("|").map { it.trim() }
                if (parts.size >= 3) {
                    val name = parts[1].replace(Regex("[✅⏸️☑️🔴🟢❌️]"), "").trim()
                    val status = parts[2].trim()
                    val schedule = if (parts.size > 3) parts[3].trim() else ""
                    tasks.add(ContextSnapshot.TaskInfo(
                        name = name,
                        isActive = status.contains("актив") || status.contains("✅"),
                        schedule = schedule
                    ))
                }
            }
            i++
        }
        return tasks
    }

    companion object {
        private const val TAG = "ContextEngine"
        private const val BATTERY_LOW_THRESHOLD = 15
    }
}
