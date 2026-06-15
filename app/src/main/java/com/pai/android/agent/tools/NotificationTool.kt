package com.pai.android.agent.tools

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.pai.android.agent.AgentTool
import com.pai.android.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Инструмент для проверки статуса NotificationListener.
 * Доступен как в ToolRegistry (для ReActAgent), так и через SkillRegistry (через ToolSkillAdapter).
 */
@Singleton
class NotificationTool @Inject constructor(
    @ApplicationContext private val context: Context
) : AgentTool {

    override val name: String = "notif_listener"
    override val description: String = "Android system notification listener. Check if notification access is enabled, or open Android notification access settings. This is NOT about reminders or scheduled tasks."
    override val parametersSchema: String = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["status", "open_settings"],
                "description": "status = check if Android notification listener is enabled, open_settings = open Android notification access settings"
            }
        },
        "required": ["action"]
    }"""
    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString() ?: "status"

        return when (action) {
            "status" -> checkStatus()
            "open_settings" -> openSettings()
            else -> ToolResult.Error(error = "Unknown notification action: $action")
        }
    }

    private fun checkStatus(): ToolResult {
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

        val text = buildString {
            appendLine("📡 Notification Listener")
            if (isListenerEnabled) {
                appendLine("Status: ✅ Active")
                appendLine("Слушает уведомления в фоне")
            } else {
                appendLine("Status: ❌ Not active")
                appendLine("Разрешите доступ:")
                appendLine("Настройки → Специальные возможности → Слушать уведомления → PAI Agent")
            }
        }

        return ToolResult.Success(
            output = text.trimEnd(),
            data = mapOf("listener_enabled" to isListenerEnabled.toString())
        )
    }

    private fun openSettings(): ToolResult {
        return try {
            val intent = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult.Success(output = "Opened notification access settings")
        } catch (e: Exception) {
            ToolResult.Error(error = "Failed to open settings: ${e.message}")
        }
    }
}
