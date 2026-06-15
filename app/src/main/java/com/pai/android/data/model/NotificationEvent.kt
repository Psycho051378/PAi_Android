package com.pai.android.data.model

/**
 * Модель уведомления, перехваченного NotificationListenerService.
 */
data class NotificationEvent(
    val id: Int,
    val tag: String? = null,
    val packageName: String,
    val appName: String = "",
    val title: String?,
    val text: String?,
    val category: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isGroup: Boolean = false,
    val groupKey: String? = null,
    val priority: Int = 0
) {
    /**
     * Важность уведомления для принятия решения — показывать агенту или нет.
     */
    enum class Importance {
        CRITICAL,   // Банки, платежи, коды доступа
        HIGH,       // Сообщения, календарь, напоминания
        NORMAL,     // Обычные уведомления
        LOW,        // Игры, развлечения, реклама
        IGNORE      // Системные, фоновые
    }

    /**
     * Оценка важности на основе пакета, категории и приоритета.
     */
    fun guessImportance(): Importance {
        val lowerPkg = packageName.lowercase()
        val lowerTitle = title?.lowercase() ?: ""
        val lowerText = text?.lowercase() ?: ""
        val lowerContent = "$lowerTitle $lowerText"

        // Ключевые слова в заголовке/тексте (работает для любых пакетов, включая adb-тесты)
        val financeWords = listOf("списание", "списано", "оплат", "пополнен", "баланс",
            "счёт", "перевод", "код", "code", "otp", "подтверждение",
            "payment", "transfer", "balance", "charge", "withdrawal")
        val scheduleWords = listOf("встреча", "напоминание", "событие", "календарь",
            "meeting", "reminder", "event", "appointment", "calendar")
        val messageWords = listOf("сообщение", "звонок", "пропущен",
            "message", "missed call", "incoming call")

        val hasFinanceWord = financeWords.any { lowerContent.contains(it) }
        val hasScheduleWord = scheduleWords.any { lowerContent.contains(it) }
        val hasMessageWord = messageWords.any { lowerContent.contains(it) }

        return when {
            // Критичное: банки, финансы, коды 2FA
            lowerPkg.contains("bank") || lowerPkg.contains("sber") ||
            lowerPkg.contains("tinkoff") || lowerPkg.contains("alfabank") ||
            lowerPkg.contains("vtb") || lowerPkg.contains("raiffeisen") ||
            lowerPkg.contains("pay") || lowerPkg.contains("wallet") ||
            category == "call" ||
            lowerText.contains("код") || lowerText.contains("code") ||
            lowerText.contains("подтверждение") || lowerText.contains("otp") ||
            category == "alarm" || hasFinanceWord -> Importance.CRITICAL

            // Высокое: сообщения, календарь, напоминания
            lowerPkg.contains("whatsapp") || lowerPkg.contains("telegram") ||
            lowerPkg.contains("viber") || lowerPkg.contains("signal") ||
            lowerPkg.contains("messenger") || lowerPkg.contains("sms") ||
            lowerPkg.contains("messages") ||
            category == "message" || category == "event" ||
            category == "reminder" || category == "alarm" ||
            lowerPkg.contains("calendar") || lowerPkg.contains("clock") ||
            hasScheduleWord || hasMessageWord -> Importance.HIGH

            // Низкое: игры, развлечения
            lowerPkg.contains("game") || lowerPkg.contains("gaming") ||
            lowerPkg.contains("play") || lowerCategory(lowerPkg, "entertainment") ||
            lowerPkg.contains("youtube") || lowerPkg.contains("video") -> Importance.LOW

            // Игнорировать: системные, фоновые
            lowerPkg.contains("android") && (lowerTitle.contains("system") ||
            lowerTitle.contains("background") || lowerTitle.contains("зарядка") ||
            lowerTitle.contains("charging") || lowerTitle.contains("battery")) -> Importance.IGNORE

            // По умолчанию — нормальное
            else -> Importance.NORMAL
        }
    }

    private fun lowerCategory(pkg: String, cat: String): Boolean {
        return category?.lowercase()?.contains(cat) == true
    }

    /**
     * Строковое представление для передачи в контекст агента.
     */
    fun toContextString(): String {
        val importance = guessImportance()
        return "[$importance] $appName: ${title ?: ""} — ${text ?: ""}"
    }
}

/**
 * Результат обработки уведомления.
 */
data class NotificationProcessingResult(
    val event: NotificationEvent,
    val shouldNotifyAgent: Boolean = false,
    val shouldStore: Boolean = true
)
