package com.pai.android.agent.tools

import com.pai.android.agent.AppLaunchSkill
import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ToolResult

/**
 * Инструмент запуска приложений.
 * Обёртка над AppLaunchSkill для ToolRegistry.
 */
class LaunchAppTool(
    private val appLaunchSkill: AppLaunchSkill
) : BaseAgentTool() {
    override val name: String = "launch_app"
    override val description: String = "Open applications on the phone"
    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "Command",
                    "default": "launch"
                },
                "package_name": {
                    "type": "string",
                    "description": "App package name or app name (e.g. calculator, settings, chrome)"
                },
                "app_name": {
                    "type": "string",
                    "description": "App name alias"
                }
            },
            "required": ["package_name"]
        }
    """.trimIndent()
    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val packageName = (params["package_name"] as? String ?: params["app_name"] as? String)
            ?: return ToolResult.Error("Укажите название приложения (package_name)")

        val result = appLaunchSkill.execute(mapOf(
            "command" to "launch",
            "package_name" to packageName
        ))
        return when (result) {
            is com.pai.android.agent.SkillResult.Success -> ToolResult.Success(result.message)
            is com.pai.android.agent.SkillResult.Error -> ToolResult.Error(result.message)
            is com.pai.android.agent.SkillResult.ConfirmationRequired -> ToolResult.Error("Требуется подтверждение")
        }
    }
}
