package com.pai.android

import android.app.Application
import android.content.Intent
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pai.android.agent.tools.LocaleManager
import com.pai.android.data.repository.VoiceSettingsRepository
import com.pai.android.service.WakeWordService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PaiApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var voiceSettingsRepository: VoiceSettingsRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
    
    companion object {
        lateinit var instance: PaiApplication
    }

    private lateinit var localeManager: LocaleManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        localeManager = LocaleManager(this)
        localeManager.applyLocale(this)
        // Init Logger to capture println() into ring buffer
        com.pai.android.agent.Logger.init()

        // Автостарт голосового сервиса, если включён в настройках
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = voiceSettingsRepository.settings.first()
                if (settings.enabled) {
                    android.util.Log.i("PaiApp", "Auto-starting WakeWordService (enabled in settings)")
                    val intent = Intent(this@PaiApplication, WakeWordService::class.java).apply {
                        action = WakeWordService.ACTION_START
                    }
                    startForegroundService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("PaiApp", "Auto-start WakeWordService error", e)
            }
        }
    }
}