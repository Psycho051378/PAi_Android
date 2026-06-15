package com.pai.android.agent.skills

import android.content.Context
import android.provider.ContactsContract
import com.pai.android.agent.Intent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import com.pai.android.agent.ResponseType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsSkill @Inject constructor(
    @ApplicationContext private val context: Context
) : Skill {

    companion object {
        @Volatile var enabled: Boolean = true
    }

    override val name: String = "contacts"
    override val description: String = "Search and manage contacts"

    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        if (intent == Intent.TOOL_OPERATION && params["command"]?.toString()?.startsWith("contacts_") == true) return true
        val lower = query.lowercase()
        return lower.contains("контакт") || lower.contains("телефон") || lower.contains("номер") ||
               lower.contains("звони") || lower.contains("найди") && (lower.contains("мам") || lower.contains("пап") || lower.contains("жен") || lower.contains("муж"))
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val command = params["command"]?.toString() ?: "contacts_search"
        return when {
            command.startsWith("contacts_search") || command.startsWith("contacts_list") -> searchContacts(params)
            command.startsWith("contacts_add") -> addContact(params)
            else -> SkillResult.Error(message = "Unknown contacts command: $command")
        }
    }

    private fun searchContacts(params: Map<String, Any>): SkillResult {
        val query = params["query"]?.toString() ?: params["q"]?.toString() ?: ""
        val limit = (params["limit"]?.toString()?.toIntOrNull() ?: 10).coerceIn(1, 30)

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        val selection = if (query.isNotBlank()) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        } else null
        val selectionArgs = if (query.isNotBlank()) {
            arrayOf("%$query%", "%$query%")
        } else null

        val cursor = try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
        } catch (e: SecurityException) {
            return SkillResult.Error(message = "Permission denied. Grant contacts access in Settings > Apps > PAI > Permissions")
        } ?: return SkillResult.Error(message = "No contacts access")

        val results = mutableListOf<String>()
        var count = 0
        while (cursor.moveToNext() && count < limit) {
            val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
            val phone = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            val typeCode = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))
            val typeLabel = when (typeCode) {
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "моб."
                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "дом."
                ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "раб."
                ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "факс"
                ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "др."
                else -> ""
            }
            results.add("• $name: $phone${if (typeLabel.isNotBlank()) " ($typeLabel)" else ""}")
            count++
        }
        cursor.close()

        if (results.isEmpty()) {
            return SkillResult.Success(
                message = "Контакты по запросу \"$query\" не найдены.",
                responseType = ResponseType.TEXT
            )
        }

        val sb = StringBuilder()
        sb.appendLine("📞 **Контакты**${if (query.isNotBlank()) " по запросу \"$query\"" else ""}:")
        sb.appendLine(results.joinToString("\n"))
        if (count >= limit) sb.appendLine("\n... и ещё (показаны первые $limit)")

        return SkillResult.Success(message = sb.toString(), responseType = ResponseType.TEXT)
    }

    private fun addContact(params: Map<String, Any>): SkillResult {
        val name = params["name"]?.toString() ?: return SkillResult.Error(message = "Укажите имя (name)")
        val phone = params["phone"]?.toString() ?: ""
        val email = params["email"]?.toString() ?: ""

        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            if (phone.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, phone)
            if (email.isNotBlank()) putExtra(ContactsContract.Intents.Insert.EMAIL, email)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
            return SkillResult.Success(
                message = "📇 Открыта форма добавления контакта \"$name\"",
                responseType = ResponseType.TEXT
            )
        } catch (e: Exception) {
            return SkillResult.Error(message = "Не удалось открыть форму контакта: ${e.message}")
        }
    }
}
