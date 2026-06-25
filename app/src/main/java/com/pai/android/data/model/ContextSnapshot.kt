package com.pai.android.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Снимок контекста устройства.
 * Собирается ContextEngine из всех доступных источников.
 */
data class ContextSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val location: LocationContext? = null,
    val recentNotifications: List<NotificationEvent> = emptyList(),
    val activeTasks: List<TaskInfo> = emptyList(),
    val weather: WeatherInfo? = null,
    val systemInfo: SystemInfo = SystemInfo()
) {
    /**
     * Информация о задаче для контекста.
     */
    data class TaskInfo(
        val name: String,
        val isActive: Boolean,
        val schedule: String = "",
        val lastRun: String = ""
    )

    /**
     * Информация о погоде.
     */
    data class WeatherInfo(
        val temperature: String = "",
        val condition: String = "",
        val city: String = "",
        val description: String = ""
    )

    /**
     * Системная информация.
     */
    data class SystemInfo(
        val isCharging: Boolean = false,
        val batteryLevel: Int = 0,
        val wifiEnabled: Boolean = false,
        val notificationListenerActive: Boolean = false,
        val proactiveEnabled: Boolean = false,
        val forwardNotifications: Boolean = false,
        val notificationDigest: Boolean = false
    )

    /**
     * Возраст снимка в миллисекундах.
     */
    val age: Long
        get() = System.currentTimeMillis() - timestamp

    /**
     * Свежий ли снимок (менее 2 минут).
     */
    val isFresh: Boolean
        get() = age < 120_000L

    /**
     * Форматирует контекст в строку для AI-промпта.
     */
    fun formatForPrompt(): String {
        val sb = StringBuilder()
        val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))
        val dateFormat = SimpleDateFormat("dd MMMM yyyy, EEEE", Locale("ru"))
        val now = Date()

        sb.appendLine("📅 **Текущий контекст:**")
        sb.appendLine("⏰ Время: ${timeFormat.format(now)}, ${dateFormat.format(now)}")

        // Местоположение
        location?.let { loc ->
            sb.appendLine()
            sb.appendLine(loc.toContextString())
        }

        // Погода
        weather?.let { w ->
            if (w.description.isNotBlank() || w.temperature.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("🌤 **Погода:** ${w.description} ${w.temperature}")
            }
        }

        // Уведомления — всегда показываем статус
        sb.appendLine()
        val listenerIcon = if (systemInfo.notificationListenerActive) "✅" else "❌"
        val proactiveIcon = if (systemInfo.proactiveEnabled) "✅" else "❌"
        val forwardIcon = if (systemInfo.forwardNotifications) "✅" else "❌"
        val digestIcon = if (systemInfo.notificationDigest) "✅" else "❌"
        sb.appendLine("🔔 **Уведомления (${recentNotifications.size} в буфере):**")
        sb.appendLine("  $listenerIcon Listener: ${if (systemInfo.notificationListenerActive) "активен" else "не активен"}")
        sb.appendLine("  $proactiveIcon Проактивный режим: ${if (systemInfo.proactiveEnabled) "вкл" else "выкл"}")
        sb.appendLine("  $forwardIcon Пересылка в чат: ${if (systemInfo.forwardNotifications) "вкл" else "выкл"}")
        sb.appendLine("  $digestIcon Дайджест уведомлений: ${if (systemInfo.notificationDigest) "вкл" else "выкл"}")
        if (recentNotifications.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("  **Последние:**")
            recentNotifications.take(3).forEach { n ->
                sb.appendLine("  • ${n.toContextString()}")
            }
            if (recentNotifications.size > 3) {
                sb.appendLine("  ...и ещё ${recentNotifications.size - 3}")
            }
        }

        // Задачи
        if (activeTasks.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("📋 **Задачи (${activeTasks.size}):**")
            activeTasks.forEach { t ->
                val status = if (t.isActive) "✅" else "⏸️"
                sb.appendLine("  $status ${t.name}")
            }
        }

        // Система
        sb.appendLine()
        sb.appendLine("🔋 **Система:** ${systemInfo.batteryLevel}%${if (systemInfo.isCharging) " (зарядка)" else ""}${if (systemInfo.wifiEnabled) " · Wi-Fi включён" else ""}")

        return sb.toString()
    }
}
