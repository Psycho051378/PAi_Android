package com.pai.android.agent.skills.home.device

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Контроллер для устройств Shelly (все поколения).
 *
 * Протоколы Shelly:
 *
 * ## Gen1 (HTTP GET)
 * - Порт: 80
 * - Relay: GET http://{ip}/relay/0?turn=on|off
 * - Light (Dimmer): GET http://{ip}/light/0?brightness=50&turn=on
 * - Color (RGBW2): GET http://{ip}/color/0?red=255&green=0&blue=0
 * - Roller: GET http://{ip}/roller/0?go=open|close|stop
 * - Статус: GET http://{ip}/relay/0 → {"ison":true,...}
 *
 * ## Gen2/Gen3 (HTTP POST RPC)
 * - Порт: 80
 * - Switch: POST http://{ip}/rpc/Switch.Set {"id":0,"on":true}
 * - Light: POST http://{ip}/rpc/Light.Set {"id":0,"on":true,"brightness":50}
 * - Roller: POST http://{ip}/rpc/Cover.Set {"id":0,"go":"open"}
 * - Статус: GET http://{ip}/rpc/Shelly.GetDeviceInfo
 *
 * Все методы thread-safe (каждый вызов создаёт свой HTTP-запрос).
 */
@Singleton
class ShellyController @Inject constructor() : DeviceController {

    override val protocol: String = "SHELLY"

    companion object {
        private const val DEFAULT_PORT = 80
        private const val TIMEOUT_MS = 3000L
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    /** HTTP-клиент с короткими таймаутами. */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // ═══════════════════ DeviceController ═══════════════════

    override suspend fun turnOn(ip: String, port: Int): ControlResult =
        sendShellyCommand(ip, port, "turn_on")

    override suspend fun turnOff(ip: String, port: Int): ControlResult =
        sendShellyCommand(ip, port, "turn_off")

    override suspend fun setBrightness(ip: String, port: Int, level: Int): ControlResult {
        val clamped = level.coerceIn(1, 100)
        return sendShellyCommand(ip, port, "set_brightness", mapOf("level" to clamped))
    }

    override suspend fun setColorTemp(ip: String, port: Int, temp: Int): ControlResult {
        val clamped = temp.coerceIn(2700, 6500)
        return sendShellyCommand(ip, port, "set_color_temp", mapOf("temp" to clamped))
    }

    override suspend fun setRGB(ip: String, port: Int, r: Int, g: Int, b: Int): ControlResult =
        sendShellyCommand(ip, port, "set_rgb", mapOf("r" to r.coerceIn(0, 255), "g" to g.coerceIn(0, 255), "b" to b.coerceIn(0, 255)))

    override suspend fun ping(ip: String, port: Int): Boolean {
        return try {
            val result = sendShellyCommand(ip, port, "ping")
            result.success
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun customCommand(ip: String, port: Int, command: String, params: Map<String, Any>): ControlResult {
        return sendShellyCommand(ip, port, command, params)
    }

    // ═══════════════════ Shelly-специфичные команды ═══════════════════

    /**
     * Управление жалюзи (Shelly 2.5 / Plus 2PM в roller-режиме).
     *
     * @param ip IP устройства
     * @param port порт (0 = 80)
     * @param action "open", "close", "stop"
     * @param channel номер канала (0-based)
     */
    suspend fun rollerControl(ip: String, port: Int = DEFAULT_PORT, action: String, channel: Int = 0): ControlResult {
        val effectivePort = if (port > 0) port else DEFAULT_PORT
        return try {
            // Сначала пробуем Gen2 RPC
            val json = """{"id":0,"method":"Cover.Set","params":{"id":$channel,"go":"$action"}}"""
            val body = json.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("http://$ip:$effectivePort/rpc")
                .post(body)
                .build()
            val response = httpClient.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            response.close()

            if (response.isSuccessful && bodyStr.contains("\"result\"")) {
                ControlResult(success = true, message = "✅ Жалюзи $action")
            } else {
                // Fallback Gen1: GET http://{ip}/roller/0?go=open
                val fallbackRequest = Request.Builder()
                    .url("http://$ip:$effectivePort/roller/$channel?go=$action")
                    .build()
                val fallbackResp = httpClient.newCall(fallbackRequest).execute()
                val fallbackBody = fallbackResp.body?.string() ?: ""
                fallbackResp.close()

                if (fallbackResp.isSuccessful) {
                    ControlResult(success = true, message = "✅ Жалюзи $action")
                } else {
                    ControlResult(success = false, error = "Shelly roller: ${fallbackResp.code}")
                }
            }
        } catch (e: Exception) {
            ControlResult(success = false, error = "Shelly roller: ${e.message ?: "ошибка"}")
        }
    }

    /**
     * Получить информацию об устройстве Shelly (модель, имя, MAC и т.д.).
     * Пробует Gen2 RPC, затем Gen1 /settings.
     */
    suspend fun getDeviceInfo(ip: String, port: Int = DEFAULT_PORT): JSONObject? {
        val effectivePort = if (port > 0) port else DEFAULT_PORT
        return try {
            // Gen2: GET /rpc/Shelly.GetDeviceInfo
            val gen2Req = Request.Builder()
                .url("http://$ip:$effectivePort/rpc/Shelly.GetDeviceInfo")
                .build()
            val gen2Resp = httpClient.newCall(gen2Req).execute()
            val gen2Body = gen2Resp.body?.string() ?: ""
            gen2Resp.close()

            if (gen2Resp.isSuccessful && gen2Body.isNotBlank()) {
                val json = JSONObject(gen2Body)
                if (json.has("result")) return json.getJSONObject("result")
                if (json.has("name")) return json
            }

            // Fallback Gen1: GET /settings
            val gen1Req = Request.Builder()
                .url("http://$ip:$effectivePort/settings")
                .build()
            val gen1Resp = httpClient.newCall(gen1Req).execute()
            val gen1Body = gen1Resp.body?.string() ?: ""
            gen1Resp.close()

            if (gen1Resp.isSuccessful && gen1Body.isNotBlank()) {
                JSONObject(gen1Body)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════ Blind-режим ═══════════════════

    /**
     * Проверить, является ли хост Shelly (Gen1 или Gen2).
     * Делает GET /rpc/Shelly.GetDeviceInfo (Gen2) или GET /settings (Gen1).
     *
     * @param ip IP для проверки
     * @param port порт
     * @return true если это Shelly
     */
    suspend fun probeIdentity(ip: String, port: Int = DEFAULT_PORT): Boolean {
        val info = getDeviceInfo(ip, port)
        if (info == null) return false

        // Gen2: в ответе есть поле "app":"Shelly"
        if (info.optString("app", "").contains("shelly", ignoreCase = true)) return true
        // Gen1: в ответе есть поле "device" или "type", содержащее "shelly"
        if (info.optString("device", "").contains("shelly", ignoreCase = true)) return true
        if (info.optString("type", "").contains("shelly", ignoreCase = true)) return true

        return false
    }

    // ═══════════════════ Внутренняя реализация ═══════════════════

    /**
     * Универсальный метод для отправки команды Shelly.
     * Пробует Gen2 RPC, затем Gen1 HTTP GET.
     */
    private suspend fun sendShellyCommand(
        ip: String,
        port: Int,
        action: String,
        params: Map<String, Any> = emptyMap()
    ): ControlResult {
        val effectivePort = if (port > 0) port else DEFAULT_PORT

        return try {
            // 1. Пробуем Gen2 RPC
            val gen2Payload = buildGen2Rpc(action, params)
            if (gen2Payload != null) {
                val gen2Result = executeGen2Rpc(ip, effectivePort, gen2Payload)
                if (gen2Result.success) return gen2Result
                // Если Gen2 не удался (не Shelly Gen2) — пробуем Gen1
            }

            // 2. Пробуем Gen1 HTTP GET
            val gen1Url = buildGen1Url(action, params)
            if (gen1Url != null) {
                return executeGen1Command(ip, effectivePort, gen1Url)
            }

            ControlResult(success = false, error = "Неизвестное действие: $action")
        } catch (e: Exception) {
            ControlResult(success = false, error = "Shelly: ${e.message ?: "ошибка"}")
        }
    }

    /**
     * Построить URL для Gen1 команды.
     */
    private fun buildGen1Url(action: String, params: Map<String, Any>): String? {
        return when (action) {
            "turn_on" -> "/relay/0?turn=on"
            "turn_off" -> "/relay/0?turn=off"
            "set_brightness" -> {
                val level = (params["level"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 50
                "/light/0?brightness=$level&turn=on"
            }
            "set_color_temp" -> {
                val temp = (params["temp"] as? Number)?.toInt()?.coerceIn(2700, 6500) ?: 3500
                "/light/0?temp=$temp&turn=on"
            }
            "set_rgb" -> {
                val r = (params["r"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                val g = (params["g"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                val b = (params["b"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                "/color/0?red=$r&green=$g&blue=$b&turn=on"
            }
            "ping" -> "/relay/0"
            "status" -> "/relay/0"
            else -> null
        }
    }

    /**
     * Построить JSON-RPC для Gen2 команды.
     * Возвращает JSON-строку тела запроса или null если действие не поддерживается.
     */
    private fun buildGen2Rpc(action: String, params: Map<String, Any>): String? {
        return when (action) {
            "turn_on" -> """{"id":0,"method":"Switch.Set","params":{"id":0,"on":true}}"""
            "turn_off" -> """{"id":0,"method":"Switch.Set","params":{"id":0,"on":false}}"""
            "set_brightness" -> {
                val level = (params["level"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 50
                """{"id":0,"method":"Light.Set","params":{"id":0,"on":true,"brightness":$level}}"""
            }
            "set_color_temp" -> {
                val temp = (params["temp"] as? Number)?.toInt()?.coerceIn(2700, 6500) ?: 3500
                """{"id":0,"method":"Light.Set","params":{"id":0,"on":true,"temp":$temp}}"""
            }
            "set_rgb" -> {
                val r = (params["r"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                val g = (params["g"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                val b = (params["b"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                """{"id":0,"method":"Light.Set","params":{"id":0,"on":true,"red":$r,"green":$g,"blue":$b}}"""
            }
            "ping" -> null  // ping проверяется через getDeviceInfo
            "status" -> null // status через getDeviceInfo
            else -> null
        }
    }

    /**
     * Выполнить Gen2 RPC запрос (POST /rpc).
     */
    private suspend fun executeGen2Rpc(ip: String, port: Int, jsonPayload: String): ControlResult {
        return try {
            val body = jsonPayload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("http://$ip:$port/rpc")
                .post(body)
                .build()
            val response = httpClient.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val success = response.isSuccessful
            response.close()

            if (success && bodyStr.contains("\"result\"")) {
                ControlResult(success = true, message = "✅ OK")
            } else if (bodyStr.contains("\"error\"")) {
                val msg = extractRpcError(bodyStr)
                ControlResult(success = false, error = "Устройство вернуло ошибку: $msg")
            } else if (success) {
                ControlResult(success = true, message = "✅ OK")
            } else {
                ControlResult(success = false, error = "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            ControlResult(success = false, error = e.message ?: "Ошибка соединения")
        }
    }

    /**
     * Выполнить Gen1 команду (HTTP GET).
     */
    private suspend fun executeGen1Command(ip: String, port: Int, path: String): ControlResult {
        return try {
            val request = Request.Builder()
                .url("http://$ip:$port$path")
                .build()
            val response = httpClient.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val success = response.isSuccessful
            response.close()

            if (success) {
                ControlResult(success = true, message = "✅ OK")
            } else {
                ControlResult(success = false, error = "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            ControlResult(success = false, error = e.message ?: "Ошибка соединения")
        }
    }

    /**
     * Извлечь сообщение ошибки из RPC-ответа Shelly Gen2.
     * {"id":0,"error":{"code":-1,"message":"msg"}}
     */
    private fun extractRpcError(json: String): String {
        return try {
            val obj = JSONObject(json)
            val error = obj.optJSONObject("error")
            error?.optString("message", "неизвестная ошибка") ?: "неизвестная ошибка"
        } catch (e: Exception) {
            "неизвестная ошибка"
        }
    }
}
