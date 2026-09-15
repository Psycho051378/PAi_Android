package com.pai.android.agent.skills.home.device

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Универсальный HTTP-контроллер для DIY-устройств и мелких брендов.
 *
 * Поддерживает два режима:
 *
 * ## 1. Предустановленные профили (Profiles)
 * Каждый профиль содержит фиксированные URL-шаблоны для управления.
 * Сейчас реализованы:
 * - WLED — самая популярная DIY-прошивка для адресных LED-лент (WS2812B)
 *
 * ## 2. Пользовательские шаблоны (Templates)
 * Устройство может хранить в deviceConfig JSON с кастомными URL-шаблонами:
 * ```json
 * {
 *   "http_on": "GET /on",
 *   "http_off": "GET /off",
 *   "http_status": "GET /status",
 *   "http_brightness": "GET /brightness?level={level}",
 *   "http_rgb": "GET /color?r={r}&g={g}&b={b}",
 *   "http_temp": "GET /temp?value={temp}",
 *   "response_parser": "json",
 *   "status_json_path": "$.state"
 * }
 * ```
 */
@Singleton
class GenericHttpController @Inject constructor() : DeviceController {

    override val protocol: String = "GENERIC_HTTP"

    companion object {
        private const val DEFAULT_PORT = 80
        private const val TIMEOUT_MS = 3000L

        // ── Предустановленные профили ──
        private val PROFILES = mapOf(
            "WLED" to WledProfile
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // ═══════════════════ DeviceController ═══════════════════

    override suspend fun turnOn(ip: String, port: Int): ControlResult =
        sendByProfileOrTemplate(ip, port, "turn_on", profileAction = { profile -> profile.on },
            templateAction = { it["http_on"] })

    override suspend fun turnOff(ip: String, port: Int): ControlResult =
        sendByProfileOrTemplate(ip, port, "turn_off", profileAction = { profile -> profile.off },
            templateAction = { it["http_off"] })

    override suspend fun setBrightness(ip: String, port: Int, level: Int): ControlResult {
        val clamped = level.coerceIn(0, 255)
        return sendByProfileOrTemplate(ip, port, "set_brightness",
            profileAction = { profile -> profile.brightness(clamped) },
            templateAction = { it["http_brightness"]?.replace("{level}", clamped.toString()) })
    }

    override suspend fun setColorTemp(ip: String, port: Int, temp: Int): ControlResult {
        val clamped = temp.coerceIn(2000, 6500)
        return sendByProfileOrTemplate(ip, port, "set_color_temp",
            profileAction = { profile -> profile.colorTemp(clamped) },
            templateAction = { it["http_temp"]?.replace("{temp}", clamped.toString()) })
    }

    override suspend fun setRGB(ip: String, port: Int, r: Int, g: Int, b: Int): ControlResult {
        val (cr, cg, cb) = Triple(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        return sendByProfileOrTemplate(ip, port, "set_rgb",
            profileAction = { profile -> profile.rgb(cr, cg, cb) },
            templateAction = {
                it["http_rgb"]?.replace("{r}", cr.toString())
                    ?.replace("{g}", cg.toString())
                    ?.replace("{b}", cb.toString())
            })
    }

    override suspend fun ping(ip: String, port: Int): Boolean {
        return try {
            val result = sendByProfileOrTemplate(ip, port, "ping",
                profileAction = { profile -> profile.status },
                templateAction = { it["http_status"] })
            result.success
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun customCommand(ip: String, port: Int, command: String, params: Map<String, Any>): ControlResult {
        // Пробуем выполнить кастомную команду через профиль или шаблоны
        val customUrl = params["url"] as? String
        if (customUrl != null) {
            return executeHttpGet(ip, if (port > 0) port else DEFAULT_PORT, customUrl)
        }
        return ControlResult(success = false, error = "Generic HTTP: нет URL для команды $command")
    }

    // ═══════════════════ Публичные методы ═══════════════════

    /**
     * Проверить, является ли устройство WLED.
     * Запрашивает GET /json/info и ищет "wled" в ответе.
     */
    suspend fun probeWled(ip: String, port: Int = DEFAULT_PORT): Boolean {
        return try {
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            val request = Request.Builder()
                .url("http://$ip:$effectivePort/json/info")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            body.contains("\"name\"") && body.contains("wled", ignoreCase = true) ||
            body.contains("\"brand\"") && body.contains("wled", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Получить информацию об устройстве WLED (имя, эффекты, состояние).
     */
    suspend fun getWledInfo(ip: String, port: Int = DEFAULT_PORT): JSONObject? {
        return try {
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            val request = Request.Builder()
                .url("http://$ip:$effectivePort/json/info")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            if (response.isSuccessful && body.isNotBlank()) {
                JSONObject(body)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Получить состояние WLED (on, яркость, цвет).
     */
    suspend fun getWledState(ip: String, port: Int = DEFAULT_PORT): JSONObject? {
        return try {
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            val request = Request.Builder()
                .url("http://$ip:$effectivePort/json/state")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            if (response.isSuccessful && body.isNotBlank()) {
                JSONObject(body)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════ Внутренняя реализация ═══════════════════

    /**
     * Отправить команду через профиль или шаблон.
     */
    private suspend fun sendByProfileOrTemplate(
        ip: String,
        port: Int,
        action: String,
        profileAction: (GenericHttpProfile) -> String?,
        templateAction: (Map<String, String>) -> String?
    ): ControlResult {
        val effectivePort = if (port > 0) port else DEFAULT_PORT

        // Сначала пробуем предустановленный профиль
        val profile = resolveProfile(ip, effectivePort)
        if (profile != null) {
            val url = profileAction(profile)
            if (url != null) {
                return executeHttpGet(ip, effectivePort, url)
            }
        }

        // Потом пробуем шаблоны из deviceConfig (если будут переданы)
        // TODO: получать deviceConfig из репозитория по MAC/IP
        // Пока шаблоны не заданы — возвращаем ошибку
        return ControlResult(success = false, error = "Generic HTTP: нет шаблонов для $action")
    }

    /**
     * Определить профиль устройства по IP.
     * Пока только WLED — определяется по HTTP-ответу.
     */
    private suspend fun resolveProfile(ip: String, port: Int): GenericHttpProfile? {
        // Пробуем WLED
        if (probeWled(ip, port)) return WledProfile
        return null
    }

    /**
     * Выполнить HTTP GET запрос.
     */
    private suspend fun executeHttpGet(ip: String, port: Int, path: String): ControlResult {
        return try {
            val url = if (path.startsWith("http")) path else "http://$ip:$port$path"
            val request = Request.Builder()
                .url(url)
                .build()
            val response = httpClient.newCall(request).execute()
            val success = response.isSuccessful
            val body = response.body?.string() ?: ""
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
}

// ═══════════════════ Профили ═══════════════════

/**
 * Профиль HTTP-устройства — набор URL-шаблонов для управления.
 */
interface GenericHttpProfile {
    val on: String
    val off: String
    val status: String
    fun brightness(level: Int): String?
    fun rgb(r: Int, g: Int, b: Int): String?
    fun colorTemp(temp: Int): String?
}

/**
 * WLED — прошивка для адресных LED-лент (WS2812B, SK6812 и др.).
 * HTTP API: GET запросы на /win с параметрами.
 *
 * Управление:
 * - Вкл/Выкл: /win&T=1 / /win&T=0
 * - Яркость: /win&A=50  (0-255)
 * - Цвет: /win&R=255&G=0&B=0
 * - Эффект: /win&FX=0
 * - Цвет. температура: /win&C=255&W=0  (CW/WW)
 * - Статус: GET /json/state
 * - Информация: GET /json/info
 */
object WledProfile : GenericHttpProfile {
    override val on: String get() = "/win&T=1"
    override val off: String get() = "/win&T=0"
    override val status: String get() = "/json/state"

    override fun brightness(level: Int): String {
        // WLED: 0-255, если level 0-100 → конвертируем
        val wledLevel = if (level <= 100) (level * 255 / 100).coerceIn(0, 255) else level.coerceIn(0, 255)
        return "/win&A=$wledLevel"
    }

    override fun rgb(r: Int, g: Int, b: Int): String =
        "/win&R=${r.coerceIn(0, 255)}&G=${g.coerceIn(0, 255)}&B=${b.coerceIn(0, 255)}"

    override fun colorTemp(temp: Int): String {
        // WLED не имеет прямой поддержки CT через /win
        // Используем белый канал: C=cold, W=warm
        val cold = ((temp - 2000) * 255 / 4500).coerceIn(0, 255)
        val warm = 255 - cold
        return "/win&C=$cold&W=$warm"
    }
}


