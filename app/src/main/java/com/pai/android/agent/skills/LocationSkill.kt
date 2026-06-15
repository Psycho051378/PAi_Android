package com.pai.android.agent.skills

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.pai.android.agent.Intent as AgentIntent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import com.pai.android.agent.ResponseType
import com.pai.android.data.service.LocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Навык геолокации для DecisionEngine.
 *
 * Позволяет:
 * - Узнать текущее местоположение (город, адрес)
 * - Проверить статус GPS
 * - Открыть настройки геолокации
 * - Получить кешированные координаты
 */
@Singleton
class LocationSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationService: LocationService
) : Skill {

    companion object {
        @Volatile var enabled: Boolean = true
    }

    override val name: String = "location"
    override val description: String = "Device location: get current city, address, GPS status, and coordinates"

    override fun canHandle(intent: AgentIntent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        return intent == AgentIntent.TOOL_OPERATION && (params["command"]?.toString()?.startsWith("location_") == true)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val command = params["command"]?.toString() ?: ""
        val action = params["action"]?.toString()

        return when {
            command.startsWith("location_current") || action == "current" -> getCurrent()
            command.startsWith("location_last_known") || action == "last_known" -> getLastKnown()
            command.startsWith("location_cached") || action == "cached" -> getCached()
            command.startsWith("location_status") || action == "status" -> checkStatus()
            command.startsWith("location_open_settings") || action == "open_settings" -> openSettings()
            command.startsWith("location_") -> SkillResult.Error(message = "Unknown location command: $command")
            else -> {
                // По умолчанию — cached + статус
                val cached = locationService.getCachedLocation()
                if (cached != null) {
                    val text = cached.toContextString() + "\nGPS: ${if (isLocationEnabled()) "✅ включён" else "❌ выключен"}"
                    SkillResult.Success(
                        message = text,
                        responseType = ResponseType.TEXT,
                        data = mapOf(
                            "latitude" to cached.latitude.toString(),
                            "longitude" to cached.longitude.toString(),
                            "city" to cached.city
                        )
                    )
                } else {
                    checkStatus()
                }
            }
        }
    }

    private suspend fun getCurrent(): SkillResult {
        val location = locationService.getCurrentLocation()
        if (location == null) {
            return SkillResult.Success(
                message = "📍 Местоположение недоступно. Включите GPS.",
                responseType = ResponseType.TEXT
            )
        }
        return SkillResult.Success(
            message = location.toContextString(),
            responseType = ResponseType.TEXT,
            data = mapOf(
                "latitude" to location.latitude.toString(),
                "longitude" to location.longitude.toString(),
                "city" to location.city,
                "address" to location.address
            )
        )
    }

    private suspend fun getLastKnown(): SkillResult {
        val location = locationService.getLastKnownLocation()
        if (location == null) {
            return SkillResult.Success(
                message = "📍 Нет сохранённого местоположения.",
                responseType = ResponseType.TEXT
            )
        }
        return SkillResult.Success(
            message = location.toContextString(),
            responseType = ResponseType.TEXT,
            data = mapOf(
                "latitude" to location.latitude.toString(),
                "longitude" to location.longitude.toString(),
                "city" to location.city
            )
        )
    }

    private fun getCached(): SkillResult {
        val cached = locationService.getCachedLocation()
        if (cached == null) {
            return SkillResult.Success(
                message = "📍 Нет кешированных данных. Запросите свежее местоположение.",
                responseType = ResponseType.TEXT
            )
        }
        return SkillResult.Success(
            message = cached.toContextString(),
            responseType = ResponseType.TEXT
        )
    }

    private fun checkStatus(): SkillResult {
        val isEnabled = isLocationEnabled()

        val message = buildString {
            appendLine("📍 **Location Status**")
            appendLine()
            if (isEnabled) {
                appendLine("✅ GPS/Геолокация: **Включена**")
            } else {
                appendLine("❌ GPS/Геолокация: **Выключена**")
            }
            appendLine("Google Play Services: ${if (com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS) "✅" else "❌"}")
            if (!isEnabled) {
                appendLine()
                appendLine("Включите геолокацию:")
                appendLine("Настройки → Местоположение → Включить")
            }
        }

        return SkillResult.Success(
            message = message.trimEnd(),
            responseType = ResponseType.RICH_TEXT
        )
    }

    private fun openSettings(): SkillResult {
        return try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success(
                message = "📍 Открыты настройки геолокации",
                responseType = ResponseType.TEXT
            )
        } catch (e: Exception) {
            SkillResult.Error(message = "Не удалось открыть настройки: ${e.message}")
        }
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val locationMode = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE
            )
            locationMode != Settings.Secure.LOCATION_MODE_OFF
        } catch (e: Exception) {
            false
        }
    }
}
