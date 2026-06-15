package com.pai.android.agent

import android.content.Context
import android.location.Location
import com.pai.android.data.model.ContextSnapshot
import com.pai.android.data.model.NotificationEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import com.pai.android.data.service.LocationService
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import com.pai.android.data.model.GeoTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат оценки проактивного триггера.
 */
data class ProactiveSuggestion(
    val text: String,
    val priority: Int,          // 0-100: выше = важнее
    val category: String,       // "battery", "calendar", "location", "notification", "system"
    val action: String = ""     // опциональное действие
)

/**
 * Настройки проактивного режима.
 * Хранятся в SharedPreferences, доступны для изменения через UI.
 */
data class ProactiveSettings(
    val enabled: Boolean = true,
    val batteryAlerts: Boolean = true,
    val calendarReminders: Boolean = true,
    val notificationDigest: Boolean = true,
    val forwardNotifications: Boolean = true,
    val locationBasedHints: Boolean = false,
    val quietModeStart: Int = 23,    // час начала тихого режима (23:00)
    val quietModeEnd: Int = 8,        // час окончания (08:00)
    val minPriority: Int = 30         // минимальный приоритет для показа
)

/**
 * Движок проактивных подсказок.
 *
 * Анализирует текущий контекст (ContextSnapshot) и выдаёт список предложений
 * на основе правил и настроек пользователя.
 *
 * Правила:
 * - Заряд батареи < 20% → предложить зарядить
 * - Скоро событие в календаре (< 30 мин) → напомнить
 * - Много уведомлений без реакции → предложить проверить
 */
@Singleton
class ProactiveTrigger @Inject constructor(
    private val locationService: LocationService,
    private val skillRegistry: SkillRegistry,
    private val geoTaskRepository: com.pai.android.data.repository.GeoTaskRepository,
    @ApplicationContext private val context: Context
) {
    @Volatile
    var settings: ProactiveSettings = loadSettings()

    private fun loadSettings(): ProactiveSettings {
        val prefs = context.getSharedPreferences("proactive_prefs", Context.MODE_PRIVATE)
        return ProactiveSettings(
            enabled = prefs.getBoolean("enabled", true),
            batteryAlerts = prefs.getBoolean("battery_alerts", true),
            calendarReminders = prefs.getBoolean("calendar_reminders", true),
            notificationDigest = prefs.getBoolean("notification_digest", true),
            forwardNotifications = prefs.getBoolean("forward_notifications", true),
            locationBasedHints = prefs.getBoolean("location_hints", false),
            quietModeStart = prefs.getInt("quiet_start", 23),
            quietModeEnd = prefs.getInt("quiet_end", 8),
            minPriority = prefs.getInt("min_priority", 30)
        )
    }

    /**
     * Оценивает контекст и возвращает список предложений.
     * Учитывает тихий режим и настройки пользователя.
     */
    fun evaluate(snapshot: ContextSnapshot): List<ProactiveSuggestion> {
        // Read fresh settings from SharedPreferences each time (user may have changed in UI)
        val prefs = context.getSharedPreferences("proactive_prefs", Context.MODE_PRIVATE)
        val effectiveEnabled = prefs.getBoolean("enabled", true)
        val effectiveForward = prefs.getBoolean("forward_notifications", true)
        DecisionEngine.proactiveAllowed = effectiveEnabled && effectiveForward
        settings = loadSettings()
        if (!effectiveEnabled) return emptyList()
        if (isQuietHour()) return emptyList()

        val suggestions = mutableListOf<ProactiveSuggestion>()

        // 1. Батарея
        if (settings.batteryAlerts) {
            val batterySuggestion = checkBattery(snapshot)
            if (batterySuggestion != null) suggestions.add(batterySuggestion)
        }

        // 2. Календарь (скоро событие)
        if (settings.calendarReminders) {
            val calendarSuggestion = checkUpcomingEvents()
            if (calendarSuggestion != null) suggestions.add(calendarSuggestion)
        }

        // 3. Уведомления (скопление без реакции)
        if (settings.notificationDigest) {
            val notifSuggestion = checkNotifications(snapshot)
            if (notifSuggestion != null) suggestions.add(notifSuggestion)
        }

        // 4. Локация (если включено)
        if (settings.locationBasedHints) {
            val locationSuggestion = checkLocation()
            if (locationSuggestion != null) suggestions.add(locationSuggestion)
        }

        // Фильтруем по минимальному приоритету
        return suggestions.filter { it.priority >= settings.minPriority }
            .sortedByDescending { it.priority }
    }

    /**
     * Оценивает контекст без внешнего snapshot (используется TaskScheduler).
     */
    fun evaluate(): List<ProactiveSuggestion> {
        return evaluate(ContextSnapshot())
    }

    /**
     * Форматирует лучшую подсказку для отправки (используется TaskScheduler).
     */
    fun formatForDecision(suggestions: List<ProactiveSuggestion>): String? {
        val best = suggestions.firstOrNull() ?: return null
        return "{\"action\":\"proactive_suggest\",\"text\":\"" + best.text + "\",\"priority\":" + best.priority + "}"
    }

    /**
     * Проверка тихого часа.
     */
    private fun isQuietHour(): Boolean {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return if (settings.quietModeStart <= settings.quietModeEnd) {
            // Не пересекает полночь (например, 23:00-08:00)
            hour >= settings.quietModeStart && hour < settings.quietModeEnd
        } else {
            // Пересекает полночь (например, 22:00-07:00)
            hour >= settings.quietModeStart || hour < settings.quietModeEnd
        }
    }

    /**
     * Правило: низкий заряд батареи.
     */
    private fun checkBattery(snapshot: ContextSnapshot): ProactiveSuggestion? {
        val battery = snapshot.systemInfo ?: return null
        if (battery.isCharging) return null
        if (battery.batteryLevel <= 0) return null // 0 = данные недоступны
        return when {
            battery.batteryLevel <= 10 -> ProactiveSuggestion(
                text = "🔋 Критический заряд: ${battery.batteryLevel}%. Поставьте на зарядку.",
                priority = 90,
                category = "battery"
            )
            battery.batteryLevel <= 20 -> ProactiveSuggestion(
                text = "🔋 Заряд батареи: ${battery.batteryLevel}%. Скоро может понадобиться зарядка.",
                priority = 40,
                category = "battery"
            )
            else -> null
        }
    }

    /**
     * Правило: скоро событие в календаре.
     * Пытается получить ближайшее событие через CalendarTool.
     */
    private fun checkUpcomingEvents(): ProactiveSuggestion? {
        return try {
            val calendarSkill = skillRegistry.getSkill("tool_calendar")
            if (calendarSkill == null) return null

            val result = runBlocking {
                calendarSkill.execute(mapOf("action" to "list_upcoming", "days" to 1))
            }
            if (result !is SkillResult.Success) return null

            val message = result.message
            // Ищем событие, которое начинается скоро
            // Парсим временные метки из ответа
            val lines = message.lines()
            var inEvent = false
            var eventTitle = ""
            var eventTime = ""

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("**") && trimmed.contains(".**")) {
                    inEvent = true
                    eventTitle = trimmed.replace("**", "").replace(Regex("^\\d+\\.\\s*"), "")
                    continue
                }
                if (inEvent && trimmed.startsWith("🕐")) {
                    eventTime = trimmed
                    inEvent = false

                    // Парсим время: "🕐 пн, 08 июн 2026 12:00 — 13:00"
                    val timeMatch = Regex("""(\d{2}:\d{2})""").find(eventTime)
                    val startHour = timeMatch?.groupValues?.getOrNull(0) ?: continue

                    val now = Calendar.getInstance()
                    val currentHour = now.get(Calendar.HOUR_OF_DAY)
                    val currentMin = now.get(Calendar.MINUTE)
                    val parts = startHour.split(":")
                    val eventHour = parts[0].toIntOrNull() ?: continue
                    val eventMin = parts[1].toIntOrNull() ?: 0

                    val minutesUntil = (eventHour - currentHour) * 60 + (eventMin - currentMin)

                    return when {
                        minutesUntil in 0..15 -> ProactiveSuggestion(
                            text = "⏰ Через $minutesUntil мин: «$eventTitle»! $eventTime",
                            priority = 85,
                            category = "calendar"
                        )
                        minutesUntil in 16..30 -> ProactiveSuggestion(
                            text = "⏰ Через $minutesUntil мин: «$eventTitle». $eventTime",
                            priority = 60,
                            category = "calendar"
                        )
                        minutesUntil in 31..60 -> ProactiveSuggestion(
                            text = "⏰ Через ~${minutesUntil}мин: «$eventTitle» $eventTime",
                            priority = 40,
                            category = "calendar"
                        )
                        else -> null
                    }
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.w("ProactiveTrigger", "checkUpcomingEvents error", e)
            null
        }
    }

    /**
     * Правило: много непрочитанных уведомлений.
     */
    private fun checkNotifications(snapshot: ContextSnapshot): ProactiveSuggestion? {
        val notifications = snapshot.recentNotifications
        if (notifications.size < 5) return null

        // Считаем только важные уведомления
        val highPriority = notifications.count {
            val imp = it.guessImportance()
            imp == NotificationEvent.Importance.CRITICAL || imp == NotificationEvent.Importance.HIGH
        }
        if (highPriority >= 3) {
            val first = notifications.firstOrNull()
            val preview = first?.text?.take(50) ?: ""
            return ProactiveSuggestion(
                text = "📬 $highPriority важных уведомлений. Первое: «$preview». Проверить?",
                priority = 50,
                category = "notification",
                action = "check_notifications"
            )
        }
        return null
    }

    /**
     * Правило: гео-подсказки.
     *
     * Проверяет текущую локацию по всем активным GeoTask.
     * Если расстояние < radiusMeters — создаёт предложение.
     */
    private fun checkLocation(): ProactiveSuggestion? {
        return try {
            val loc = locationService.getCachedLocation() ?: return null
            val lat = loc.latitude
            val lng = loc.longitude
            if (lat == 0.0 && lng == 0.0) return null

            val tasks = runBlocking { geoTaskRepository.getActive() }
            if (tasks.isEmpty()) return null

            val current = Location("").also { it.latitude = lat; it.longitude = lng }
            val results = mutableListOf<Pair<GeoTask, Float>>()

            for (task in tasks) {
                if (!task.canTrigger()) continue
                val taskLoc = Location("").also {
                    it.latitude = task.latitude; it.longitude = task.longitude
                }
                val distance = current.distanceTo(taskLoc)
                if (distance <= task.radiusMeters) {
                    results.add(task to distance)
                }
            }

            if (results.isEmpty()) return null

            // Берём ближайшую задачу
            val (nearest, dist) = results.minByOrNull { it.second } ?: return null

            // Отмечаем как сработавшую
            runBlocking {
                if (nearest.oneShot) {
                    geoTaskRepository.deactivate(nearest.id)
                } else {
                    geoTaskRepository.markTriggered(nearest.id, address = loc.address)
                }
            }

            val meters = dist.toInt()
            val place = nearest.address ?: ""
            val locationHint = if (place.isNotBlank()) " у $place" else ""

            ProactiveSuggestion(
                text = "📍 Ты рядом$locationHint ($meters м)\n${nearest.label}",
                priority = 70,
                category = "location",
                action = "geo_task_triggered"
            )
        } catch (e: Exception) {
            android.util.Log.w("ProactiveTrigger", "checkLocation error", e)
            null
        }
    }
}
