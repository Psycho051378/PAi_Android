package com.pai.android.agent.skills

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pai.android.agent.Intent as AgentIntent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import com.pai.android.agent.ResponseType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallSkill @Inject constructor(
    @ApplicationContext private val context: Context
) : Skill {

    companion object {
        @Volatile var enabled: Boolean = true
        private const val CHANNEL_ID = "call_skill"
        private const val NOTIFICATION_ID = 2001
    }

    override val name: String = "call"
    override val description: String = "Make phone calls and open dialer"

    override fun canHandle(intent: AgentIntent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        if (intent == AgentIntent.TOOL_OPERATION && params["command"]?.toString()?.startsWith("call_") == true) return true
        val lower = query.lowercase()
        return lower.contains("позвони") || lower.contains("звонить") || lower.contains("набери") ||
               lower.contains("набрать") || lower.contains("call")
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val command = params["command"]?.toString() ?: "call_call"
        return when {
            command.startsWith("call_call") -> makeCall(params)
            command.startsWith("call_dial") -> makeCall(params)  // call_dial = call_call
            else -> SkillResult.Error(message = "Unknown call command: $command")
        }
    }

    /**
     * Совершает звонок напрямую если есть CALL_PHONE permission.
     * Если нет — показывает уведомление в шторке (fallback).
     */
    private fun makeCall(params: Map<String, Any>): SkillResult {
        val number = params["number"]?.toString() ?: params["phone"]?.toString()
            ?: return SkillResult.Error(message = "Укажите номер (number)")

        try {
            val hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            val appIsForeground = isAppForeground()

            if (hasCallPermission && appIsForeground) {
                // Приложение видимо + есть permission → прямой звонок
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$number")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return SkillResult.Success(
                    message = "📞 Звоню на $number",
                    responseType = ResponseType.TEXT
                )
            }

            // Фон или нет permission → уведомление (единственный способ на Android 12+)
            val actionAction = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val actionTitle = if (hasCallPermission) "📞 Звонок" else "📞 Набор"
            showNotificationWithIntent(
                number = number,
                action = actionAction,
                title = actionTitle,
                text = "Нажмите для ${if (hasCallPermission) "звонка" else "набора"} $number"
            )
            return SkillResult.Success(
                message = "📞 ${if (appIsForeground) "Уведомление для набора" else "Уведомление отправлено"}: $number",
                responseType = ResponseType.TEXT
            )
        } catch (e: Exception) {
            // Если прямой вызов упал — пробуем уведомление как fallback
            try {
                showNotificationWithIntent(
                    number = number,
                    action = Intent.ACTION_DIAL,
                    title = "📞 Набор",
                    text = "Нажмите для набора $number"
                )
                return SkillResult.Success(
                    message = "📞 Уведомление для набора $number",
                    responseType = ResponseType.TEXT
                )
            } catch (_: Exception) {
                return SkillResult.Error(message = "Не удалось совершить звонок: ${e.message}")
            }
        }
    }

    private fun isAppForeground(): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val appProcesses = activityManager?.runningAppProcesses ?: return false
            appProcesses.any { process ->
                process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                process.processName == context.packageName
            }
        } catch (_: Exception) { false }
    }

    /**
     * Показывает уведомление, при нажатии на которое выполняется intent.
     * Единственный способ запустить Activity из сервиса на Android 12+.
     */
    private fun showNotificationWithIntent(number: String, action: String, title: String, text: String) {
        createCallChannel()

        val intent = Intent(action).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun createCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления для звонков и набора"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
