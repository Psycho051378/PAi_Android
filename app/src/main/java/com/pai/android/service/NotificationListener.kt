package com.pai.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pai.android.data.model.NotificationEvent
import com.pai.android.data.model.NotificationProcessingResult
import com.pai.android.data.model.Message
import com.pai.android.data.repository.AiRepository
import com.pai.android.agent.DecisionEngine
import com.pai.android.agent.ContextEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Слушатель уведомлений Android.
 *
 * Перехватывает все уведомления системы, фильтрует по важности
 * и передаёт значимые в DecisionEngine для анализа и проактивных действий.
 */
@AndroidEntryPoint
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private const val CHANNEL_ID = "notification_listener"
        private const val NOTIFICATION_ID = 1002

        private val IGNORED_PACKAGES = setOf(
            "com.pai.android",
            "com.android.systemui",
            "com.android.phone",
            "android"
        )
    }

    @Inject
    lateinit var contextEngine: ContextEngine

    @Inject
    lateinit var aiRepository: AiRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isConnected = false

    /** Флаг предотвращает рекурсию: уведомление → AI → действие → новое уведомление → AI */
    private var isProcessingNotification = false

    /** Минимальный интервал между AI-обработками уведомлений (ms) */
    private var lastNotificationProcessingTime = 0L
    private val notificationCooldownMs = 10_000L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "NotificationListener created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, "Connected to notification system")
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "Disconnected from notification system")
        requestRebind(ComponentName(this, this@NotificationListener.javaClass))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        processNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (IGNORED_PACKAGES.any { packageName.startsWith(it) }) return

        val notification = sbn.notification
        val extras = notification.extras ?: return

        val title = extras.getString(NotificationCompat.EXTRA_TITLE)
        val text = extras.getString(NotificationCompat.EXTRA_TEXT)
        val category = notification.category
        val priority = notification.priority

        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val isGroup = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val group = notification.group

        val event = NotificationEvent(
            id = sbn.id,
            tag = sbn.tag,
            packageName = packageName,
            appName = getAppName(packageName),
            title = title,
            text = text,
            category = category,
            timestamp = sbn.postTime,
            isGroup = isGroup,
            groupKey = group,
            priority = priority
        )

        val result = processEvent(event)
        if (result.shouldNotifyAgent) notifyAgent(event)
        if (result.shouldStore) storeEvent(event)

        Log.d(TAG, "📬 $packageName: ${title ?: ""} — ${text?.take(50) ?: ""}")
    }

    private fun processEvent(event: NotificationEvent): NotificationProcessingResult {
        val importance = event.guessImportance()
        val shouldNotifyAgent = importance == NotificationEvent.Importance.CRITICAL ||
                                importance == NotificationEvent.Importance.HIGH
        val shouldStore = importance != NotificationEvent.Importance.IGNORE
        return NotificationProcessingResult(event, shouldNotifyAgent, shouldStore)
    }

    private fun notifyAgent(event: NotificationEvent) {
        // Защита от рекурсии и спама
        val now = System.currentTimeMillis()
        if (isProcessingNotification || (now - lastNotificationProcessingTime) < notificationCooldownMs) {
            Log.d(TAG, "⏭️ Skipping AI analysis (cooldown or already processing)")
            return
        }

        scope.launch {
            isProcessingNotification = true
            try {
                Log.i(TAG, "🔔 AI-driven analysis: ${event.toContextString()}")

                val prompt = buildProactivePrompt(event)
                Log.d(TAG, "📝 Prompt: ${prompt.take(150)}...")

                // Direct AI call — bypasses processQuery to avoid TaskQueue/project creation
                val response = aiRepository.sendMessage(
                    messages = listOf(Message.createUserMessage("notification", prompt)),
                    systemPrompt = "You analyze notifications. " +
                        "- Payment/charge → check email balance and report to user\n" +
                        "- Meeting/event → remind user with details\n" +
                        "- Message from person → brief summary\n" +
                        "- Code/OTP → confirm silently\n" +
                        "If no action needed, reply EXACTLY: 'Everything is fine' (English). " +
                        "Otherwise reply in user's language.",
                    memoryContext = ""
                )

                if (response.isSuccess) {
                    val answer = response.getOrThrow().text.trim()
                    if (answer.isNotBlank() && !answer.startsWith("Everything is fine") &&
                        !answer.startsWith("No action") && !answer.startsWith("Nothing to do")) {
                        Log.i(TAG, "✅ AI-driven response: ${answer.take(100)}...")
                        if (DecisionEngine.proactiveAllowed) {
                            DecisionEngine.pendingNotificationResult = answer
                        } else {
                            Log.d(TAG, "⏭️ Notifications forwarding disabled, skipping")
                        }
                    } else {
                        Log.d(TAG, "ℹ️ AI decided not to bother: ${answer.take(80)}")
                    }
                } else {
                    Log.w(TAG, "⚠️ AI-driven error: ${response.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ notifyAgent error", e)
            } finally {
                isProcessingNotification = false
                lastNotificationProcessingTime = System.currentTimeMillis()
            }
        }
    }

    /**
     * Build proactive prompt for the AI.
     * AI decides what to do: check balance, remind, respond, or stay silent.
     */
    private fun buildProactivePrompt(event: NotificationEvent): String {
        val importance = event.guessImportance()
        val criticalDetail = when {
            importance == NotificationEvent.Importance.CRITICAL ->
                "\nThis is CRITICAL (banking, payment, security). Consider checking email for balance."
            importance == NotificationEvent.Importance.HIGH ->
                "\nThis is HIGH priority (message, calendar, reminder)."
            else -> ""
        }
        return buildString {
            appendLine("🔔 Notification from ${event.appName} (${event.packageName}):")
            event.title?.let { appendLine("Title: $it") }
            event.text?.let { appendLine("Text: $it") }
            appendLine("Importance: $importance")
            append(criticalDetail)
            appendLine()
            appendLine()
            appendLine("Analyze this notification and decide:")
            appendLine("- Payment/charge → check email for balance, report to user")
            appendLine("- Meeting/event → remind user with details")
            appendLine("- Message from person → brief summary")
            appendLine("- Code/OTP → confirm receipt silently")
            appendLine("- CRITICAL notification → take action (check balance, etc)")
            appendLine("If no action needed, reply EXACTLY: \"Everything is fine\" (in English).")
            appendLine()
            appendLine("If action needed, reply in user's language. Your answer will be sent as proactive notification.")
        }
    }

    private fun storeEvent(event: NotificationEvent) {
        contextEngine.pushNotification(event)
        android.util.Log.d(TAG, "💾 Буфер уведомлений: ${contextEngine.getRecentNotifications().size} шт")
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Notification Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Сервис прослушивания уведомлений PAI Agent"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PAI Agent")
            .setContentText("Слушает уведомления")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
