package com.pai.android.agent.skills.home.device

import com.chaquo.python.Python
import com.pai.android.agent.skills.home.identity.DeviceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Контроллер для Xiaomi MiIO устройств (пылесос, увлажнитель и т.д.).
 *
 * Вызывает Python-скрипт [miio_control] через Chaquopy для локального
 * управления по UDP протоколу MiIO. Требует device token, который
 * получается через Mi Cloud API из настроек.
 *
 * Token'ы кешируются по IP: [tokenCache]. При смене IP устройства
 * токен обновляется из deviceConfig в SmartHomeDispatcher.
 */
@Singleton
class MiioController @Inject constructor() : StatefulController {

    override val protocol: String = "MIIO"

    /** Кеш токенов: IP -> token */
    private val tokenCache = ConcurrentHashMap<String, String>()

    companion object {
        private const val MODULE = "miio_control"
        private const val FUNCTION = "control"

        /** Состояния пылесоса, означающие «едет/убирает». */
        private val ACTIVE_STATES = setOf(
            "cleaning", "returning", "spot", "zoned_clean", "segment_clean", "paused"
        )
        private val IDLE_STATES = setOf("docked", "charging", "idle", "unknown")
    }

    fun setToken(ip: String, token: String) {
        if (token.isNotBlank()) {
            tokenCache[ip] = token
        }
    }

    fun clearCache() = tokenCache.clear()

    override suspend fun turnOn(ip: String, port: Int): ControlResult =
        callPython(ip, "turn_on")

    override suspend fun turnOff(ip: String, port: Int): ControlResult =
        callPython(ip, "turn_off")

    override suspend fun setBrightness(ip: String, port: Int, level: Int): ControlResult =
        callPython(ip, "set_brightness", "{\"level\": $level}")

    override suspend fun setColorTemp(ip: String, port: Int, temp: Int): ControlResult =
        ControlResult(success = false, error = "Цвет. температура не поддерживается для MiIO")

    override suspend fun setRGB(ip: String, port: Int, r: Int, g: Int, b: Int): ControlResult =
        ControlResult(success = false, error = "RGB не поддерживается для MiIO")

    override suspend fun ping(ip: String, port: Int): Boolean {
        val result = callPython(ip, "status")
        return result.success
    }

    override suspend fun customCommand(ip: String, port: Int, command: String, params: Map<String, Any>): ControlResult {
        val jsonParams = try {
            JSONObject(params).toString()
        } catch (e: Exception) { "{}" }
        return callPython(ip, command, jsonParams)
    }

    /**
     * Состояние пылесоса через get_prop (state, battery, ...).
     */
    override suspend fun readState(ip: String, port: Int): DeviceState? = withContext(Dispatchers.IO) {
        val json = invokePython(ip, "status") ?: return@withContext null
        val result = json.optJSONArray("result") ?: return@withContext null

        val state = result.optString(0, "").lowercase()
        val batteryRaw = result.optString(1, "")
        val battery = batteryRaw.toIntOrNull() ?: result.optInt(1, -1).takeIf { it in 0..100 }

        val power = when {
            state.isBlank() -> null
            state in ACTIVE_STATES -> true
            state in IDLE_STATES -> false
            else -> null
        }

        DeviceState(
            power = power,
            battery = battery,
            extra = mapOf("miio_state" to state)
        )
    }

    // ═══════════════════ Внутренняя реализация ═══════════════════

    private fun callPython(ip: String, command: String, paramsJson: String = "{}"): ControlResult {
        if (tokenCache[ip] == null) {
            return ControlResult(
                success = false,
                error = "Токен не найден для $ip. Авторизуй Xiaomi в настройках."
            )
        }

        val json = invokePython(ip, command, paramsJson)
            ?: return ControlResult(success = false, error = "MiIO: устройство не ответило")

        return if (json.optString("status") == "ok") {
            ControlResult(success = true, message = "✅ ${json.optString("result", "OK")}")
        } else {
            ControlResult(success = false, error = json.optString("error", "Ошибка MiIO"))
        }
    }

    /** Вызов Python-модуля с разбором JSON-ответа. null — ошибка или отсутствие токена. */
    private fun invokePython(ip: String, command: String, paramsJson: String = "{}"): JSONObject? {
        val token = tokenCache[ip] ?: return null
        return try {
            val python = Python.getInstance()
            val module = python.getModule(MODULE)
            val result = module.callAttr(FUNCTION, ip, token, command, paramsJson)
            JSONObject(result.toString())
        } catch (e: Exception) {
            println("MiioController: python error — ${e.message}")
            null
        }
    }
}
