package com.pai.android.agent

/**
 * Определение запланированной задачи.
 *
 * @property cronExpression — "HH:MM" (ежедневно), "dow HH:MM" (еженедельно),
 *   "DD HH:MM" (ежемесячно), "YYYY-MM-DD HH:MM" (однократно),
 *   несколько через запятую: "08:00,12:00,18:00"
 * @property intervalMinutes — если > 0, задача выполняется каждые N минут
 *   (cronExpression игнорируется). Пример: 5 = каждые 5 минут.
 * @property lastRunAt — метка времени последнего выполнения (для interval)
 */
data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val cronExpression: String = "", // "HH:MM" format, пусто если interval
    val enabled: Boolean = true,
    val intervalMinutes: Int = 0,    // 0 = не интервальная
    val lastRunAt: Long = 0L         // для interval — время последнего запуска
) {
    val isInterval: Boolean get() = intervalMinutes > 0

    companion object {
        val DEFAULT_TASKS = listOf(
            ScheduledTask(
                id = "daily_monitoring",
                name = "Ежедневный мониторинг",
                prompt = "Выполни ежедневный мониторинг конфликта США-Иран",
                cronExpression = "09:00"
            ),
            ScheduledTask(
                id = "daily_summary",
                name = "Вечернее резюме",
                prompt = "Составь краткое резюме дня: ключевые события, выводы",
                cronExpression = "21:00"
            ),
            ScheduledTask(
                id = "heartbeat",
                name = "Heartbeat",
                prompt = "Check current weather and top news. If anything notable requires attention, prepare a brief 1-2 sentence summary. Otherwise respond with 'OK'.",
                cronExpression = "08:00,09:00,10:00,11:00,12:00,13:00,14:00,15:00,16:00,17:00,18:00,19:00,20:00,21:00,22:00"
            )
        )
    }
}
