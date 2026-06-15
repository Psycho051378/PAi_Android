package com.pai.android.agent.tools

import com.pai.android.agent.AgentTool
import com.pai.android.agent.ToolResult
import com.pai.android.agent.Logger as AppLogger
import com.pai.android.data.service.LocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Инструмент геолокации.
 * Доступен как в ToolRegistry (для ReActAgent), так и через SkillRegistry (через ToolSkillAdapter).
 *
 * Позволяет:
 * - Получить текущее местоположение (город, адрес, координаты)
 * - Получить последнее известное местоположение (быстро, без GPS)
 * - Проверить статус GPS/геолокации
 * - Открыть настройки геолокации
 */
@Singleton
class LocationTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationService: LocationService
) : AgentTool {

    override val name: String = "location"
    override val description: String = "FRESH GPS location from device sensor. CRITICAL: IGNORE any location data from memory (personal_info/location) - those are outdated. Use action=current for REAL current position. action=last_known for cached (may be outdated). action=status checks GPS on/off."
    override val parametersSchema: String = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["current", "last_known", "status", "cached", "open_settings"],
                "description": "current = get fresh GPS location (slower, accurate), last_known = get last known location (fast), status = check if GPS/location is enabled, cached = get cached location without GPS, open_settings = open Android location settings"
            }
        },
        "required": ["action"]
    }"""
    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString() ?: "current"
        AppLogger.i("LocationTool", "🔥 execute called! action=$action")

        return when (action) {
            "current" -> getCurrentLocation()
            "last_known" -> getLastKnown()
            "cached" -> getCached()
            "status" -> checkStatus()
            "open_settings" -> openSettings()
            else -> {
                println("🔥 LocationTool: default handler (no action)")
                // Действие не указано — пробуем cached, last_known, статус
                val cached = locationService.getCachedLocation()
                if (cached != null) {
                    val sb = StringBuilder(cached.toContextString())
                    sb.append("\n")
                    sb.append("GPS: ${if (isLocationEnabled()) "✅ включён" else "❌ выключен"}")
                    sb.append("\n\nДля свежих координат используйте: action=current")
                    ToolResult.Success(
                        output = sb.toString(),
                        data = mapOf(
                            "latitude" to cached.latitude.toString(),
                            "longitude" to cached.longitude.toString(),
                            "city" to cached.city,
                            "address" to cached.address,
                            "available" to "true"
                        )
                    )
                } else {
                    // Нет кеша — активный запрос координат
                    val current = getCurrentLocation()
                    if (current is ToolResult.Success && current.data?.get("available") == "true") {
                        current
                    } else {
                        // Ничего не дало — статус
                        checkStatus()
                    }
                }
            }
        }
    }

    private suspend fun getCurrentLocation(): ToolResult {
        AppLogger.i("LocationTool", "🔥 trying GPS first...")
        
        // 1. GPS через LocationService — точные координаты
        val location = locationService.getCurrentLocation()
        if (location != null) {
            AppLogger.i("LocationTool", "🔥 GPS success: ${location.city}")
            return ToolResult.Success(
                output = location.toContextString(),
                data = mapOf(
                    "latitude" to location.latitude.toString(),
                    "longitude" to location.longitude.toString(),
                    "city" to location.city,
                    "address" to location.address,
                    "accuracy" to location.accuracy.toString(),
                    "available" to "true"
                )
            )
        }
        
        AppLogger.w("LocationTool", "🔥 GPS null, trying IP geolocation...")
        
        // 2. IP geolocation fallback — не требует прав
        val ipLoc = try { 
            withContext(kotlinx.coroutines.Dispatchers.IO) { getLocationByIp() }
        } catch (e: Exception) { 
            AppLogger.e("LocationTool", "🔥 IP exception: ${e.message}")
            null 
        }
        if (ipLoc != null) {
            AppLogger.i("LocationTool", "🔥 IP SUCCESS -> $ipLoc")
            val coordsMatch = Regex("\\(([0-9.-]+), ([0-9.-]+)\\)").find(ipLoc)
            val lat = (coordsMatch?.groupValues?.getOrNull(1) ?: "0").trimEnd('.')
            val lon = (coordsMatch?.groupValues?.getOrNull(2) ?: "0").trimEnd('.')
            val cleanAddr = ipLoc.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
            AppLogger.i("LocationTool", "🔥 IP returning lat=$lat lon=$lon")
            return ToolResult.Success(
                output = "📍 Мы находимся: $cleanAddr. Координаты: $lat, $lon (определено по IP)",
                data = mapOf(
                    "latitude" to lat,
                    "longitude" to lon,
                    "city" to cleanAddr,
                    "address" to cleanAddr,
                    "available" to "true",
                    "provider" to "ip_api"
                )
            )
        }

        AppLogger.e("LocationTool", "🔥 GPS + IP both failed")
        return ToolResult.Success(
            output = "📍 No GPS fix available. Provider returned no coordinates. IGNORE any previous location data from memory - those are outdated. Try again outside with clear sky.",
            data = mapOf("available" to "false")
        )
    }

    /** IP geolocation fallback — без прав, без GPS, через интернет */
    private fun getLocationByIp(): String? {
        AppLogger.i("LocationTool", "🔥 getLocationByIp: запрос к ip-api.com...")
        return try {
            val url = java.net.URL("http://ip-api.com/json/?lang=ru&fields=status,lat,lon,city,country,query")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "PaiAndroid/1.0")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, "UTF-8"))
            val response = reader.readText()
            reader.close()
            conn.disconnect()
            val json = org.json.JSONObject(response)
            if (json.getString("status") == "success") {
                val city = json.optString("city", "")
                val lat = json.getDouble("lat")
                val lon = json.getDouble("lon")
                val country = json.optString("country", "")
                val result = if (city.isNotBlank() && country.isNotBlank()) "$country, $city ($lat, $lon)"
                            else if (city.isNotBlank()) "$city ($lat, $lon)"
                            else "$country ($lat, $lon)"
                AppLogger.i("LocationTool", "🔥 IP result: $result ($lat, $lon)")
                result
            } else {
                AppLogger.w("LocationTool", "🔥 ip-api status != success: ${json.optString("status")}")
                null
            }
        } catch (e: Exception) {
            AppLogger.e("LocationTool", "🔥 IP error: ${e.message}")
            null
        }
    }

    private suspend fun getLastKnown(): ToolResult {
        val location = locationService.getLastKnownLocation()
        if (location == null) {
            return ToolResult.Success(
                output = "📍 Нет сохранённого местоположения.",
                data = mapOf("available" to "false")
            )
        }

        return ToolResult.Success(
            output = location.toContextString(),
            data = mapOf(
                "latitude" to location.latitude.toString(),
                "longitude" to location.longitude.toString(),
                "city" to location.city,
                "address" to location.address,
                "accuracy" to location.accuracy.toString(),
                "available" to "true"
            )
        )
    }

    private fun getCached(): ToolResult {
        val cached = locationService.getCachedLocation()
        if (cached == null) {
            return ToolResult.Success(
                output = "📍 Нет кешированного местоположения. Используйте action=current для получения свежих данных.",
                data = mapOf("available" to "false")
            )
        }

        return ToolResult.Success(
            output = cached.toContextString(),
            data = mapOf(
                "latitude" to cached.latitude.toString(),
                "longitude" to cached.longitude.toString(),
                "city" to cached.city,
                "address" to cached.address,
                "accuracy" to cached.accuracy.toString(),
                "is_fresh" to cached.isFresh.toString(),
                "available" to "true"
            )
        )
    }

    private fun checkStatus(): ToolResult {
        val text = "📍 No GPS fix available. Provider returned no coordinates. IGNORE any previous location data from memory - those are outdated. Try again outside with clear sky."
        return ToolResult.Success(
            output = text,
            data = mapOf(
                "gps_enabled" to isLocationEnabled().toString(),
                "location_available" to "false"
            )
        )
    }

    private fun openSettings(): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult.Success(output = "📍 Открыты настройки геолокации")
        } catch (e: Exception) {
            ToolResult.Error(error = "Не удалось открыть настройки: ${e.message}")
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
