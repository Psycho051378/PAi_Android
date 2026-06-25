package com.pai.android.agent.tools

import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ToolResult
import com.pai.android.agent.skills.ContactsSkill

/**
 * Инструмент поиска контактов.
 * Обёртка над ContactsSkill для ToolRegistry.
 */
class ContactsTool(
    private val contactsSkill: ContactsSkill
) : BaseAgentTool() {
    override val name: String = "contacts"
    override val description: String = "Search contacts in phonebook"
    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["contacts_search", "contacts_add"],
                    "description": "Command"
                },
                "query": {
                    "type": "string",
                    "description": "Search query (name or phone number)"
                },
                "name": {
                    "type": "string",
                    "description": "Contact name (for add)"
                },
                "phone": {
                    "type": "string",
                    "description": "Phone number (for add)"
                }
            },
            "required": ["query"]
        }
    """.trimIndent()
    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val command = (params["command"] as? String) ?: "contacts_search"
        return when {
            command.startsWith("contacts_search") || command.startsWith("contacts_list") -> search(params)
            command.startsWith("contacts_add") -> add(params)
            else -> ToolResult.Error("Unknown contacts command: $command")
        }
    }

    private suspend fun search(params: Map<String, Any>): ToolResult {
        val query = (params["query"] as? String ?: params["q"] as? String)
            ?: return ToolResult.Error("Укажите запрос для поиска (query)")

        val result = contactsSkill.execute(mapOf(
            "command" to "contacts_search",
            "query" to query
        ))
        return when (result) {
            is com.pai.android.agent.SkillResult.Success -> ToolResult.Success(result.message)
            is com.pai.android.agent.SkillResult.Error -> ToolResult.Error(result.message)
            is com.pai.android.agent.SkillResult.ConfirmationRequired -> ToolResult.Error("Требуется подтверждение")
        }
    }

    private suspend fun add(params: Map<String, Any>): ToolResult {
        val name = (params["name"] as? String) ?: return ToolResult.Error("Укажите имя (name)")
        val phone = (params["phone"] as? String) ?: return ToolResult.Error("Укажите номер (phone)")

        val result = contactsSkill.execute(mapOf(
            "command" to "contacts_add",
            "name" to name,
            "phone" to phone
        ))
        return when (result) {
            is com.pai.android.agent.SkillResult.Success -> ToolResult.Success(result.message)
            is com.pai.android.agent.SkillResult.Error -> ToolResult.Error(result.message)
            is com.pai.android.agent.SkillResult.ConfirmationRequired -> ToolResult.Error("Требуется подтверждение")
        }
    }
}
