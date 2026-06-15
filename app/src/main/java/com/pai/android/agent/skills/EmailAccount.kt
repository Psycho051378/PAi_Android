package com.pai.android.agent.skills

import org.json.JSONObject

/**
 * Email account configuration.
 * Stored as JSON in PersistentContext for persistence.
 */
data class EmailAccount(
    val id: String,            // unique ID
    val displayName: String,   // "Личная почта"
    val imapServer: String,    // imap.mail.ru
    val imapPort: Int = 993,
    val smtpServer: String,    // smtp.mail.ru
    val smtpPort: Int = 465,
    val username: String,      // full email
    val password: String,      // app password (not stored plaintext in production)
    val useSSL: Boolean = true
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("imapServer", imapServer)
        put("imapPort", imapPort)
        put("smtpServer", smtpServer)
        put("smtpPort", smtpPort)
        put("username", username)
        put("password", password)
        put("useSSL", useSSL)
    }.toString()

    companion object {
        fun fromJson(json: String): EmailAccount? = try {
            val obj = JSONObject(json)
            EmailAccount(
                id = obj.getString("id"),
                displayName = obj.getString("displayName"),
                imapServer = obj.getString("imapServer"),
                imapPort = obj.optInt("imapPort", 993),
                smtpServer = obj.getString("smtpServer"),
                smtpPort = obj.optInt("smtpPort", 465),
                username = obj.getString("username"),
                password = obj.getString("password"),
                useSSL = obj.optBoolean("useSSL", true)
            )
        } catch (_: Exception) { null }
    }
}
