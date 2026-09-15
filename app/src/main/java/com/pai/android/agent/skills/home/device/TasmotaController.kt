package com.pai.android.agent.skills.home.device

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Контроллер для устройств на прошивке Tasmota (Sonoff и другие ESP8266/ESP32).
 *
 * Протокол: HTTP GET на порт 80.
 * Все команды через /cm?cmnd=...
 *
 * Примеры команд:
 * - Вкл: GET /cm?cmnd=Power%20ON
 * - Выкл: GET /cm?cmnd=Power%20OFF
 * - Яркость: GET /cm?cmnd=Dimmer%2050
 * - Цвет: GET /cm?cmnd=Color%2000FF00
 * - Цвет. температура: GET /cm?cmnd=CTemp%20350
 * - Статус: GET /cm?cmnd=Status%200
 *
 * Tasmota возвращает JSON: {"POWER":"ON"}, {"Dimmer":50}, {"Color":"00FF00"}
 */
@Singleton
class TasmotaController @Inject constructor() : DeviceController {

    override val protocol: String = "TASMOTA"

    companion object {
        private const val DEFAULT_PORT = 80
        private const val TIMEOUT_MS = 3000L

        /** Маппинг цветовой температуры Tasmota (200-500) в Кельвины (2000K-6500K). */
        private const val TEMP_MIN = 200   // ~2000K
        private const val TEMP_MAX = 500   // ~6500K
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // ═══════════════════ DeviceController ═══════════════════

    override suspend fun turnOn(ip: String, port: Int): ControlResult =
        sendCommand(ip, port, "Power%20ON")

    override suspend fun turnOff(ip: String, port: Int): ControlResult =
        sendCommand(ip, port, "Power%20OFF")

    override suspend fun setBrightness(ip: String, port: Int, level: Int): ControlResult {
        val clamped = level.coerceIn(0, 100)
        return sendCommand(ip, port, "Dimmer%20$clamped")
    }

    override suspend fun setColorTemp(ip: String, port: Int, temp: Int): ControlResult {
        // Tasmota: 200-500 = 2000K-6500K
        // Конвертируем Кельвины в Tasmota-значение
        val clamped = temp.coerceIn(2000, 6500)
        val tasmotaTemp = ((clamped - 2000) * (TEMP_MAX - TEMP_MIN) / (6500 - 2000) + TEMP_MIN)
            .coerceIn(TEMP_MIN, TEMP_MAX)
        return sendCommand(ip, port, "CTemp%20$tasmotaTemp")
    }

    override suspend fun setRGB(ip: String, port: Int, r: Int, g: Int, b: Int): ControlResult {
        val hex = String.format("%02X%02X%02X", r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        return sendCommand(ip, port, "Color%20$hex")
    }

    override suspend fun ping(ip: String, port: Int): Boolean {
        return try {
            val result = sendCommand(ip, port, "Status%200")
            result.success
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun customCommand(ip: String, port: Int, command: String, params: Map<String, Any>): ControlResult {
        return when (command) {
            "status" -> sendCommand(ip, port, "Status%200")
            "set_mode" -> {
                val mode = params["mode"] as? String ?: "auto"
                // Tasmota режимы: 0=normal, 1=color, 2=white и т.д.
                sendCommand(ip, port, "Scheme%20${modeToScheme(mode)}")
            }
            "fan_speed" -> {
                val speed = params["speed"] as? String ?: "balanced"
                val fanSpeed = speedToFanSpeed(speed)
                sendCommand(ip, port, "FanSpeed%20$fanSpeed")
            }
            "charge" -> {
                // Tasmota может управлять роботом-пылесосом через IR
                // Для большинства устройств команда не поддерживается
                ControlResult(success = false, error = "Tasmota: зарядка не поддерживается")
            }
            else -> {
                // Прямая команда: params["cmnd"] или command как cmnd
                val cmnd = (params["cmnd"] as? String) ?: command
                sendCommand(ip, port, cmnd.replace(" ", "%20"))
            }
        }
    }

    // ═══════════════════ Blind-режим ═══════════════════

    /**
     * Проверить, является ли устройство Tasmota.
     * Отправляет Status 0 и ищет "Tasmota" в ответе.
     *
     * @param ip IP для проверки
     * @param port порт (0 = 80)
     * @return true если это Tasmota
     */
    suspend fun probeIdentity(ip: String, port: Int = DEFAULT_PORT): Boolean {
        return try {
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            val request = Request.Builder()
                .url("http://$ip:$effectivePort/cm?cmnd=Status%200")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            body.contains("Tasmota", ignoreCase = true) ||
            body.contains("\"Module\"")  // Любое устройство Tasmota отвечает Status 0
        } catch (e: Exception) {
            false
        }
    }

    // ═══════════════════ Внутренняя реализация ═══════════════════

    /**
     * Отправить команду Tasmota.
     */
    private suspend fun sendCommand(ip: String, port: Int, cmnd: String): ControlResult {
        val effectivePort = if (port > 0) port else DEFAULT_PORT
        return try {
            val request = Request.Builder()
                .url("http://$ip:$effectivePort/cm?cmnd=$cmnd")
                .build()
            val response = httpClient.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val success = response.isSuccessful
            response.close()

            if (success && bodyStr.isNotBlank()) {
                // Парсим JSON-ответ Tasmota
                val json = try { JSONObject(bodyStr) } catch (e: Exception) { null }
                if (json != null) {
                    // Проверяем на ошибки
                    if (json.has("WARNING") || json.has("ERROR")) {
                        val warning = json.optString("WARNING") ?: json.optString("ERROR")
                        ControlResult(success = false, error = warning)
                    } else {
                        ControlResult(success = true, message = "✅ OK")
                    }
                } else {
                    ControlResult(success = true, message = "✅ OK")
                }
            } else {
                ControlResult(success = false, error = "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            ControlResult(success = false, error = e.message ?: "Ошибка соединения")
        }
    }

    /**
     * Конвертировать название режима в номер Tasmota Scheme (0-4).
     */
    private fun modeToScheme(mode: String): Int = when (mode.lowercase()) {
        "normal", "white" -> 0
        "color", "rgb" -> 1
        "party" -> 2
        "fire" -> 3
        "wave" -> 4
        "auto" -> 0
        else -> 0
    }

    /**
     * Конвертировать название скорости в номер FanSpeed (1-4).
     */
    private fun speedToFanSpeed(speed: String): Int = when (speed.lowercase()) {
        "quiet", "low", "min" -> 1
        "balanced", "medium", "mid" -> 2
        "turbo", "high", "max" -> 3
        "max", "full" -> 4
        else -> 2
    }
}
