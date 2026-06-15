package com.pai.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.pai.android.agent.tools.LocaleManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.pai.android.data.repository.ProviderSettingsRepository
import com.pai.android.data.repository.RoleRepository
import com.pai.android.ui.navigation.PaiNavigation
import com.pai.android.ui.theme.PaiAndroidTheme
import com.pai.android.ui.theme.ThemeWrapper
import com.pai.android.agent.DecisionEngine
import com.pai.android.data.model.Message
import com.pai.android.data.repository.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: ProviderSettingsRepository
    @Inject lateinit var roleRepository: RoleRepository
    @Inject lateinit var taskScheduler: com.pai.android.agent.TaskScheduler
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var memoryRepository: com.pai.android.data.repository.MemoryRepository

    private lateinit var localeManager: LocaleManager

    override fun attachBaseContext(newBase: Context) {
        localeManager = LocaleManager(newBase)
        super.attachBaseContext(localeManager.applyLocale(newBase))
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestLocationPermissions() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startAgentServices() }

    private fun requestLocationPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        locationPermissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+ — service starts after grant
        val needPermission = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestLocationPermissions()
        }
        setContent {
            ThemeWrapper {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { PaiApp() }
            }
        }
    }

    private fun startAgentServices() {
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
fun PaiApp() {
    PaiNavigation()
}

@Preview(showBackground = true)
@Composable
fun PaiAppPreview() {
    PaiAndroidTheme { PaiApp() }
}
