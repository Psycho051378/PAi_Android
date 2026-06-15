package com.pai.android.agent.skills

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.pai.android.agent.Intent as AgentIntent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import com.pai.android.agent.ResponseType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsSkill @Inject constructor(
    @ApplicationContext private val context: Context
) : Skill {

    companion object {
        @Volatile var enabled: Boolean = true
    }

    override val name: String = "sms"
    override val description: String = "Send SMS messages"

    override fun canHandle(intent: AgentIntent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        if (intent == AgentIntent.TOOL_OPERATION && params["command"]?.toString()?.startsWith("sms_") == true) return true
        val lower = query.lowercase()
        return lower.contains("смс") || lower.contains("сообщени") || lower.contains("sms") ||
               lower.contains("напиши") && (lower.contains("номер") || lower.contains("телефон"))
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val command = params["command"]?.toString() ?: "sms_send"
        return when {
            command.startsWith("sms_send") -> sendSms(params)
            else -> SkillResult.Error(message = "Unknown sms command: $command")
        }
    }

    private fun sendSms(params: Map<String, Any>): SkillResult {
        val number = params["number"]?.toString() ?: params["phone"]?.toString() ?: params["to"]?.toString()
            ?: return SkillResult.Error(message = "Укажите номер (number)")
        val text = params["text"]?.toString() ?: params["message"]?.toString() ?: params["body"]?.toString()
            ?: return SkillResult.Error(message = "Укажите текст сообщения (text)")

        // Try direct send via SmsManager if permission granted
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.getSystemService(android.telecom.TelecomManager::class.java)
                    context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                } else {
                    android.telephony.SmsManager.getDefault()
                }
                android.telephony.SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
                return SkillResult.Success(
                    message = "✉️ SMS отправлено на $number",
                    responseType = ResponseType.TEXT
                )
            } catch (e: Exception) {
                // Fall through to open SMS app
            }
        }

        // Fallback: open SMS app with pre-filled number and text
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
                putExtra("sms_body", text)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val permNote = if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                " (нет разрешения на отправку)"
            } else ""
            return SkillResult.Success(
                message = "✉️ Открыто SMS для $number$permNote",
                responseType = ResponseType.TEXT
            )
        } catch (e: Exception) {
            return SkillResult.Error(message = "Не удалось отправить SMS: ${e.message}")
        }
    }
}
