package com.pai.android.agent.skills

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.ComponentName
import com.pai.android.agent.Intent as AgentIntent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import com.pai.android.agent.ResponseType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Навык управления уведомлениями и доступа к истории уведомлений.
 *
 * Позволяет:
 * - Проверить, активен ли NotificationListener
 * - Открыть настройки доступа к уведомлениям
 * - Получить последние значимые уведомления (из истории)
 * - Включить/выключить категории уведомлений для агента
 */
@Singleton
class NotificationSkill @Inject constructor(
    @ApplicationContext private val context: Context
) : Skill {

    companion object {
        @Volatile var enabled: Boolean = true

        /**
         * Категории уведомлений, которые агент обрабатывает.
         */
        @Volatile var notifyOnCritical: Boolean = true
        @Volatile var notifyOnHigh: Boolean = true
        @Volatile var notifyOnNormal: Boolean = false
        @Volatile var notifyOnLow: Boolean = false
    }

    override val name: String = "notification"
    override val description: String = "Manage notifications: check listener status, open settings, show recent alerts"

    override fun canHandle(intent: AgentIntent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        return intent == AgentIntent.TOOL_OPERATION && (params["command"]?.toString()?.startsWith("notification_") == true || params["command"]?.toString()?.startsWith("notif_listener") == true)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val command = params["command"]?.toString() ?: "notification_status"
        val action = params["action"]?.toString()
        return when {
            // old-style commands
            command.startsWith("notification_status") -> checkStatus()
            command.startsWith("notification_open_settings") -> openSettings()
            command.startsWith("notification_recent") -> getRecent()
            command.startsWith("notification_enable_critical") -> {
                notifyOnCritical = true
                SkillResult.Success(message = "✅ Критические уведомления включены", responseType = ResponseType.TEXT)
            }
            command.startsWith("notification_disable_critical") -> {
                notifyOnCritical = false
                SkillResult.Success(message = "⛔ Критические уведомления отключены", responseType = ResponseType.TEXT)
            }
            // new-style: command=notif_listener, action=status|open_settings
            command.startsWith("notif_listener") && action == "status" -> checkStatus()
            command.startsWith("notif_listener") && action == "open_settings" -> openSettings()
            else -> SkillResult.Error(message = "Unknown notification command: $command")
        }
    }

    private fun checkStatus(): SkillResult {
        val isListenerEnabled = try {
            val cn = ComponentName(context, com.pai.android.service.NotificationListener::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            flat?.contains(cn.flattenToString()) == true
        } catch (e: Exception) {
            false
        }

        val message = buildString {
            appendLine("📡 **Notification Listener**")
            appendLine()
            if (isListenerEnabled) {
                appendLine("✅ Статус: **Активен**")
                appendLine()
                appendLine("**Категории:**")
                appendLine("- Критические: ${if (notifyOnCritical) "✅" else "⛔"}")
                appendLine("- Высокие: ${if (notifyOnHigh) "✅" else "⛔"}")
                appendLine("- Обычные: ${if (notifyOnNormal) "✅" else "⛔"}")
                appendLine("- Низкие: ${if (notifyOnLow) "⛔" else "⛔"}")
            } else {
                appendLine("❌ Статус: **Не активен**")
                appendLine()
                appendLine("Разрешите доступ:")
                appendLine("Настройки → Специальные возможности → Слушать уведомления → PAI Agent")
            }
        }

        return SkillResult.Success(message = message.trimEnd(), responseType = ResponseType.RICH_TEXT)
    }

    private fun openSettings(): SkillResult {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return SkillResult.Success(
                message = "📋 Открыты настройки доступа к уведомлениям. Разрешите PAI Agent слушать уведомления.",
                responseType = ResponseType.TEXT
            )
        } catch (e: Exception) {
            return SkillResult.Error(message = "Не удалось открыть настройки: ${e.message}")
        }
    }

    private fun getRecent(): SkillResult {
        // TODO: возвращать из хранилища последние N уведомлений
        // Пока заглушка — после реализации ContextRepository
        return SkillResult.Success(
            message = "📭 Хранилище уведомлений пока не реализовано.",
            responseType = ResponseType.TEXT
        )
    }
}
