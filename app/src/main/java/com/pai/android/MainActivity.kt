package com.pai.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.pai.android.agent.TaskScheduler
import com.pai.android.agent.tools.LocaleManager
import com.pai.android.data.model.Message
import com.pai.android.data.repository.ChatRepository
import com.pai.android.data.repository.MemoryRepository
import com.pai.android.data.repository.ProviderSettingsRepository
import com.pai.android.data.repository.RoleRepository
import com.pai.android.ui.navigation.PaiNavigation
import com.pai.android.ui.navigation.Screen
import com.pai.android.ui.theme.PaiAndroidTheme
import com.pai.android.ui.theme.ThemeWrapper
import com.pai.android.agent.DecisionEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: ProviderSettingsRepository
    @Inject lateinit var roleRepository: RoleRepository
    @Inject lateinit var taskScheduler: TaskScheduler
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var memoryRepository: MemoryRepository

    private lateinit var localeManager: LocaleManager
    private var servicesStarted = false

    override fun attachBaseContext(newBase: Context) {
        localeManager = LocaleManager(newBase)
        super.attachBaseContext(localeManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Проверяем, проходил ли пользователь онбординг
        val onboardingComplete = isOnboardingComplete()

        // Если онбординг пройден — запускаем сервисы сразу (разрешения уже есть)
        if (onboardingComplete) {
            startAgentServices()
        }

        setContent {
            ThemeWrapper {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PaiApp(startDestination = if (onboardingComplete) Screen.ChatList.route else Screen.Permissions.route)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Если онбординг только что завершён (например, после возврата из PermissionsScreen),
        // запускаем сервисы, которые ещё не стартовали
        if (!servicesStarted && isOnboardingComplete()) {
            startAgentServices()
        }
    }

    /**
     * Проверить, завершён ли онбординг разрешений.
     */
    private fun isOnboardingComplete(): Boolean {
        return getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
            .getBoolean("onboarding_complete", false)
    }

    /**
     * Запустить фоновые сервисы агента.
     * Вызывается после того, как все необходимые разрешения получены.
     */
    private fun startAgentServices() {
        if (servicesStarted) return
        servicesStarted = true

        // Create notification channel for proactive alerts
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "proactive_alerts",
                "Proactive Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "AI proactive suggestions"
                setShowBadge(true)
            }
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }

        // Start foreground service (keep process alive for scheduled tasks)
        try {
            val svcIntent = android.content.Intent(this, com.pai.android.service.SchedulerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (e: Exception) {
            println("SchedulerService start error: ${e.message}")
        }

        // Start the scheduler
        lifecycleScope.launch {
            try {
                settingsRepository.initializeDefaultProviders()
                roleRepository.initializeDefaultRoles()
                taskScheduler.start(lifecycleScope) { result ->
                    var chatId = DecisionEngine.lastChatId
                    if (chatId == null) {
                        try {
                            val fact = kotlinx.coroutines.runBlocking {
                                memoryRepository.getFactByCategoryAndKey("persistent_context", "agent_state")
                            }
                            if (fact != null) {
                                val parts = fact.value.split("|", limit = 13)
                                if (parts.size >= 10) {
                                    val pcChatId = parts[9].ifBlank { null }
                                    if (pcChatId != null) { chatId = pcChatId; DecisionEngine.lastChatId = pcChatId }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    // Proactive trigger: send push notification
                    if (result.startsWith("{")) {
                        try {
                            val json = org.json.JSONObject(result)
                            if (json.optString("action") == "proactive_suggest") {
                                val text = json.optString("text", "AI suggestion")
                                println("🔔 Sending push notification: $text")
                                val notif = androidx.core.app.NotificationCompat.Builder(this@MainActivity, "proactive_alerts")
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle("PAi")
                                    .setContentText(text)
                                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                                    .setAutoCancel(true)
                                    .build()
                                val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                nm.notify(System.nanoTime().toInt() and 0x7FFFFFFF, notif)
                            }
                        } catch (_: Exception) {}
                    }

                    if (chatId != null) {
                        lifecycleScope.launch {
                            try {
                                val msg = Message.createAssistantMessage(chatId, result)
                                chatRepository.addMessage(msg)
                            } catch (e: Exception) {
                                println("TaskScheduler send error: ${e.message}")
                            }
                        }
                    }
                    DecisionEngine.notificationDelivered = true
                }
            } catch (e: Exception) {
                println("Scheduler start error: ${e.message}")
            }
        }
    }
}

@Composable
fun PaiApp(startDestination: String = Screen.ChatList.route) {
    PaiNavigation(startDestination = startDestination)
}

@Preview(showBackground = true)
@Composable
fun PaiAppPreview() {
    PaiAndroidTheme { PaiApp() }
}
