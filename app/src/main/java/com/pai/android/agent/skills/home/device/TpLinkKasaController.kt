package com.pai.android.agent.skills.home.device

import com.pai.android.data.repository.ManufacturerRepository
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Контроллер для устройств TP-Link Kasa / Tapo.
 *
 * Вызывает Python-скрипт [kasa_control] через Chaquopy для локального
 * управления устройствами TP-Link (лампы, розетки, выключатели).
 *
 * ## Протоколы
 * - **Kasa (старые)**: шифрованный TCP на порт 9999
 * - **Tapo (новые)**: KLAP (AES-CBC-SHA256) через HTTP на порт 80
 *
 * Оба покрываются библиотекой python-kasa.
 *
 * ## Credentials
 * - Kasa: не требует авторизации (username/password пустые)
 * - Tapo: требует username/password от TP-Link аккаунта
 * Хранятся в [credentialsCache] по IP, берутся из ManufacturerRepository
 * для производителя "tplink".
 */
@Singleton
class TpLinkKasaController @Inject constructor(
    private val manufacturerRepository: ManufacturerRepository
) : DeviceController {

    override val protocol: String = "TPLINK_KASA"

    companion object {
        private const val MODULE = "kasa_control"
        private const val CONTROL_FUNCTION = "control"
        private const val DISCOVER_FUNCTION = "discover"
    }

    /** Кеш credentials: IP → Pair(username, password) */
    private val credentialsCache = ConcurrentHashMap<String, Pair<String, String>>()

    /** Глобальные credentials (если не заданы для конкретного IP). */
    private var globalUsername: String = ""
    private var globalPassword: String = ""

    // ═══════════════════ Credentials management ═══════════════════

    /**
     * Установить глобальные credentials для всех TP-Link устройств.
     * Используется из UI (Manufacturer Settings).
     */
    fun setGlobalCredentials(username: String, password: String) {
        globalUsername = username
        globalPassword = password
    }

    /**
     * Установить credentials для конкретного устройства.
     */
    fun setCredentials(ip: String, username: String, password: String) {
        credentialsCache[ip] = Pair(username, password)
    }

    /**
     * Очистить кеш credentials.
     */
    fun clearCache() = credentialsCache.clear()

    /**
     * Загрузить credentials из ManufacturerRepository.
     * Вызывается перед каждым обращением к Python, чтобы подхватить изменения из UI.
     */
    private suspend fun loadCredentials() {
        try {
            val auth = manufacturerRepository.get("tplink")
            if (auth != null && auth.enabled) {
                val creds = try {
                    org.json.JSONObject(auth.credentials)
                } catch (e: Exception) { org.json.JSONObject() }
                globalUsername = creds.optString("username", "")
                globalPassword = creds.optString("password", "")
            }
        } catch (e: Exception) {
            println("TpLinkKasaController: loadCredentials error — ${e.message}")
        }
    }

    /**
     * Получить credentials для устройства.
     * Сначала проверяет кеш по IP, затем глобальные.
     */
    private suspend fun getCredentials(ip: String): Pair<String, String> {
        loadCredentials()
        return credentialsCache[ip] ?: Pair(globalUsername, globalPassword)
    }

    // ═══════════════════ DeviceController ═══════════════════

    override suspend fun turnOn(ip: String, port: Int): ControlResult =
        callPython(ip, "turn_on")

    override suspend fun turnOff(ip: String, port: Int): ControlResult =
        callPython(ip, "turn_off")

    override suspend fun setBrightness(ip: String, port: Int, level: Int): ControlResult {
        val clamped = level.coerceIn(0, 100)
        return callPython(ip, "set_brightness", "{\"level\": $clamped}")
    }

    override suspend fun setColorTemp(ip: String, port: Int, temp: Int): ControlResult {
        val clamped = temp.coerceIn(2500, 6500)
        return callPython(ip, "set_color_temp", "{\"temp\": $clamped}")
    }

    override suspend fun setRGB(ip: String, port: Int, r: Int, g: Int, b: Int): ControlResult {
        val cr = r.coerceIn(0, 255)
        val cg = g.coerceIn(0, 255)
        val cb = b.coerceIn(0, 255)
        return callPython(ip, "set_rgb", """{"r": $cr, "g": $cg, "b": $cb}""")
    }

    override suspend fun ping(ip: String, port: Int): Boolean {
        val result = callPython(ip, "ping")
        return result.success
    }

    override suspend fun customCommand(ip: String, port: Int, command: String, params: Map<String, Any>): ControlResult {
        val jsonParams = try {
            JSONObject(params).toString()
        } catch (e: Exception) { "{}" }
        return callPython(ip, command, jsonParams)
    }

    // ═══════════════════ Специфичные методы ═══════════════════

    /**
     * Настроить цвет в HSV (нативный формат TP-Link).
     */
    suspend fun setHSV(ip: String, h: Int, s: Int, v: Int): ControlResult {
        return callPython(ip, "set_hsv", """{"h": $h, "s": $s, "v": $v}""")
    }

    /**
     * Получить статус устройства.
     */
    suspend fun getStatus(ip: String): JSONObject? {
        val result = callPython(ip, "status")
        if (!result.success) return null
        return try {
            JSONObject(result.message)
        } catch (e: Exception) { null }
    }

    // ═══════════════════ Discovery ═══════════════════

    /**
     * Найти все TP-Link устройства в сети.
     * Использует UDP broadcast discovery через python-kasa.
     *
     * @param timeout таймаут discovery в секундах (по умолчанию 3)
     * @return список найденных устройств: [{ip, mac, model, name, is_on}]
     */
    suspend fun discover(timeout: Int = 3): List<KasaDiscoveredDevice> {
        return try {
            loadCredentials()
            val python = Python.getInstance()
            val module = python.getModule(MODULE)
            val result = module.callAttr(DISCOVER_FUNCTION, globalUsername, globalPassword, timeout)
            val resultStr = result.toString()

            val json = JSONObject(resultStr)
            if (json.optString("status") == "ok") {
                val devices = json.optJSONArray("result") ?: JSONArray()
                (0 until devices.length()).map { i ->
                    val dev = devices.getJSONObject(i)
                    KasaDiscoveredDevice(
                        ip = dev.optString("ip", ""),
                        mac = dev.optString("mac", ""),
                        model = dev.optString("model", ""),
                        name = dev.optString("name", ""),
                        isOn = dev.optBoolean("is_on", false)
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            println("TpLinkKasaController: discover error — ${e.message}")
            emptyList()
        }
    }

    /**
     * Blind-режим: отправить broadcast команду на все найденные Kasa/Tapo устройства.
     *
     * Используется, когда нет базы устройств, но нужно быстро
     * включить/выключить всё, что поддерживает TP-Link протокол.
     */
    suspend fun blindControl(action: String, params: Map<String, Any> = emptyMap()): String {
        val devices = discover(timeout = 2)
        if (devices.isEmpty()) return ""

        val results = mutableListOf<String>()
        for (device in devices) {
            val paramsJson = try {
                JSONObject(params).toString()
            } catch (e: Exception) { "{}" }
            val result = callPython(device.ip, action, paramsJson)
            if (result.success) {
                results.add("💡 ${device.name} (${device.ip}): ✅ ${result.message}")
            } else {
                results.add("💡 ${device.ip}: ❌ ${result.error}")
            }
        }
        return if (results.isNotEmpty()) "TP-Link Kasa: " + results.joinToString("; ") else ""
    }

    // ═══════════════════ Внутренняя реализация ═══════════════════

    /**
     * Вызвать Python-функцию для управления устройством.
     */
    private suspend fun callPython(ip: String, command: String, paramsJson: String = "{}"): ControlResult {
        val (username, password) = getCredentials(ip)

        return try {
            val python = Python.getInstance()
            val module = python.getModule(MODULE)
            val result = module.callAttr(CONTROL_FUNCTION, ip, username, password, command, paramsJson)
            val resultStr = result.toString()

            try {
                val json = JSONObject(resultStr)
                if (json.optString("status") == "ok") {
                    val msg = json.opt("result")
                    val displayMsg = when (msg) {
                        is String -> msg
                        is JSONObject -> msg.optString("result", "OK")
                        else -> "OK"
                    }
                    ControlResult(success = true, message = "✅ $displayMsg")
                } else {
                    ControlResult(success = false, error = json.optString("error", "Ошибка Kasa"))
                }
            } catch (e: Exception) {
                ControlResult(success = true, message = resultStr)
            }
        } catch (e: Exception) {
            ControlResult(success = false, error = "Kasa: ${e.message ?: "неизвестная ошибка"}")
        }
    }
}

/**
 * Устройство TP-Link, найденное при discovery.
 */
data class KasaDiscoveredDevice(
    val ip: String,
    val mac: String,
    val model: String,
    val name: String,
    val isOn: Boolean
)
