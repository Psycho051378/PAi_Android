package com.pai.android.agent.tools

import com.pai.android.agent.AgentTool
import com.pai.android.agent.ContextEngine
import com.pai.android.agent.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Инструмент контекста устройства.
 * Возвращает снимок текущего контекста: локация, уведомления, батарея, задачи, время.
 *
 * Доступен в ReAct цикле через gatherTools и executeTools.
 */
@Singleton
class ContextTool @Inject constructor(
    private val contextEngine: ContextEngine
) : AgentTool {

    override val name: String = "get_context"
    override val description: String = "Get device context snapshot: current time, location, weather, battery, notifications, active tasks. Use when user asks 'what's going on', 'give me context', or before proactive suggestions."
    override val parametersSchema: String = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["full", "quick"],
                "description": "full = complete context (location, battery, tasks), quick = just time and summary"
            }
        },
        "required": ["action"]
    }"""
    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString() ?: "full"

        return when (action) {
            "full" -> getFullContext()
            "quick" -> getQuickContext()
            else -> ToolResult.Error(error = "Unknown action: $action")
        }
    }

    private suspend fun getFullContext(): ToolResult {
        val context = contextEngine.getContext()
        return ToolResult.Success(
            output = context.formatForPrompt(),
            data = mapOf(
                "timestamp" to context.timestamp.toString(),
                "isFresh" to context.isFresh.toString(),
                "location" to (context.location?.toShortString() ?: "unknown"),
                "battery" to "${context.systemInfo.batteryLevel}%",
                "charging" to context.systemInfo.isCharging.toString(),
                "tasks" to context.activeTasks.size.toString()
            )
        )
    }

    private suspend fun getQuickContext(): ToolResult {
        val context = contextEngine.getContext()
        val loc = context.location?.toShortString() ?: "неизвестно"
        val charging = if (context.systemInfo.isCharging) " (зарядка)" else ""
        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru")).format(java.util.Date())
        return ToolResult.Success(
            output = "⏰ $timeStr, 📍 $loc, 🔋 ${context.systemInfo.batteryLevel}%$charging",
            data = mapOf("timestamp" to context.timestamp.toString())
        )
    }
}
