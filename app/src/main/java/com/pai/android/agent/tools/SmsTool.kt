package com.pai.android.agent.tools

import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ToolResult
import com.pai.android.agent.skills.SmsSkill

/**
 * Инструмент отправки SMS.
 * Обёртка над SmsSkill для ToolRegistry.
 */
class SmsTool(
    private val smsSkill: SmsSkill
) : BaseAgentTool() {
    override val name: String = "sms"
    override val description: String = "Send SMS messages"
    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["sms_send"],
                    "description": "Command"
                },
                "phone": {
                    "type": "string",
                    "description": "Phone number"
                },
                "number": {
                    "type": "string",
                    "description": "Phone number (alias)"
                },
                "text": {
                    "type": "string",
                    "description": "SMS text content"
                },
                "message": {
                    "type": "string",
                    "description": "SMS text content (alias)"
                }
            },
            "required": ["phone", "text"]
        }
    """.trimIndent()
    override val requiresConfirmation: Boolean = true

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val phone = (params["phone"] as? String ?: params["number"] as? String)
            ?: return ToolResult.Error("Не указан номер телефона")
        val text = (params["text"] as? String ?: params["message"] as? String)
            ?: return ToolResult.Error("Не указан текст сообщения")

        val result = smsSkill.execute(mapOf(
            "command" to "sms_send",
            "number" to phone,
            "text" to text
        ))
        return when (result) {
            is com.pai.android.agent.SkillResult.Success -> ToolResult.Success(
                output = result.message,
                data = mapOf("phone" to phone, "sms_sent" to "true")
            )
            is com.pai.android.agent.SkillResult.Error -> ToolResult.Error(result.message)
            is com.pai.android.agent.SkillResult.ConfirmationRequired -> ToolResult.Error("Требуется подтверждение")
        }
    }
}
