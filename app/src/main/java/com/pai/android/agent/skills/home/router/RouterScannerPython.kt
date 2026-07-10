package com.pai.android.agent.skills.home.router

import com.chaquo.python.Python
import com.chaquo.python.PyObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сканирование сети через Chaquopy + tplinkrouterc6u.
 *
 * Вызывает Python-скрипт home_network.py, который:
 * 1. Авторизуется на TP-Link роутере
 * 2. Получает DHCP leases (IP, MAC, hostname)
 * 3. Возвращает JSON-список устройств
 *
 * Используется как альтернатива HttpRouterClient (чистый Kotlin),
 * когда самописный HTTP handler не справляется с конкретной прошивкой.
 */
@Singleton
class RouterScannerPython @Inject constructor() {

    companion object {
        private const val TAG = "RouterScannerPython"
        private const val MODULE_NAME = "home_network"
        private const val FUNCTION_NAME = "scan"
    }

    /**
     * Сканирует сеть через Python-скрипт.
     *
     * @param config конфигурация роутера
     * @return Map<IP, MAC> — ARP-таблица, совместимая с RouterScanner.scan()
     */
    suspend fun scan(config: RouterConfig): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            println("$TAG: starting scan for ${config.ip} via Python")

            val python = Python.getInstance()
            val module = python.getModule(MODULE_NAME)

            val result: PyObject = module.callAttr(
                FUNCTION_NAME,
                config.ip,
                config.password,
                config.username,
                config.protocol.name.lowercase()
            )

            val resultStr = result.toString()
            println("$TAG: raw result (first 300 chars): ${resultStr.take(300)}")

            parseResult(resultStr)
        } catch (e: Exception) {
            println("$TAG: Python scan error: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Тестирует соединение с роутером через Python.
     * Возвращает TestResult, совместимый с RouterScanner.testConnection().
     */
    suspend fun testConnection(config: RouterConfig): TestResult = withContext(Dispatchers.IO) {
        try {
            println("$TAG: testConnection to ${config.ip} via Python")

            val python = Python.getInstance()
            val module = python.getModule(MODULE_NAME)

            val result: PyObject = module.callAttr(
                FUNCTION_NAME,
                config.ip,
                config.password,
                config.username,
                config.protocol.name.lowercase()
            )

            val resultStr = result.toString()
            println("$TAG: testConnection raw result (first 300): ${resultStr.take(300)}")

            val json = JSONObject(resultStr)
            val status = json.optString("status", "error")

            if (status == "ok") {
                val devices = json.optJSONArray("devices")
                val deviceCount = if (devices != null) devices.length() else 0

                // Если devices пустой, но status OK — считаем что соединение есть
                // (DHCP leases могли быть пусты, или это не TP-Link)
                if (deviceCount > 0 || json.optBoolean("connected", false)) {
                    TestResult(
                        success = true,
                        deviceCount = deviceCount,
                        protocol = config.protocol,
                        error = null
                    )
                } else {
                    TestResult(
                        success = false,
                        deviceCount = 0,
                        error = "Соединение установлено, но устройства не найдены. " +
                                "Возможно, роутер не TP-Link."
                    )
                }
            } else {
                val error = json.optString("error", "Неизвестная ошибка")
                TestResult(
                    success = false,
                    error = error
                )
            }
        } catch (e: Exception) {
            println("$TAG: testConnection error: ${e.message}")
            TestResult(
                success = false,
                error = "Python error: ${e.message}"
            )
        }
    }

    /**
     * Парсит JSON-результат Python-скрипта в Map<IP, MAC>.
     */
    private fun parseResult(resultStr: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val json = JSONObject(resultStr)
            val status = json.optString("status", "error")

            if (status != "ok") {
                val error = json.optString("error", "unknown error")
                println("$TAG: scan returned error: $error")
                return emptyMap()
            }

            val devices = json.optJSONArray("devices")
            if (devices == null) {
                println("$TAG: no devices array in result")
                return emptyMap()
            }

            for (i in 0 until devices.length()) {
                val device = devices.getJSONObject(i)
                val ip = device.optString("ip", "")
                val mac = device.optString("mac", "?")

                if (ip.isNotBlank() && mac != "?" && android.util.Patterns.IP_ADDRESS.matcher(ip).matches()) {
                    result[ip] = mac.lowercase()
                }
            }

            println("$TAG: parsed ${result.size} devices from Python result")
        } catch (e: Exception) {
            println("$TAG: parseResult error: ${e.message}")
        }
        return result
    }

    /**
     * Полная информация об устройствах (IP + MAC + hostname).
     * Возвращает данные для отображения пользователю.
     */
    data class DeviceInfo(
        val ip: String,
        val mac: String,
        val name: String
    )

    /**
     * Сканирует и возвращает полную информацию об устройствах.
     */
    suspend fun scanDetailed(config: RouterConfig): List<DeviceInfo> = withContext(Dispatchers.IO) {
        try {
            val python = Python.getInstance()
            val module = python.getModule(MODULE_NAME)

            val result: PyObject = module.callAttr(
                FUNCTION_NAME,
                config.ip,
                config.password,
                config.username,
                config.protocol.name.lowercase()
            )

            val resultStr = result.toString()
            val json = JSONObject(resultStr)
            val devices = json.optJSONArray("devices") ?: return@withContext emptyList()

            val list = mutableListOf<DeviceInfo>()
            for (i in 0 until devices.length()) {
                val dev = devices.getJSONObject(i)
                list.add(DeviceInfo(
                    ip = dev.optString("ip", "?"),
                    mac = dev.optString("mac", "?"),
                    name = dev.optString("name", "?")
                ))
            }
            list
        } catch (e: Exception) {
            println("$TAG: scanDetailed error: ${e.message}")
            emptyList()
        }
    }
}
