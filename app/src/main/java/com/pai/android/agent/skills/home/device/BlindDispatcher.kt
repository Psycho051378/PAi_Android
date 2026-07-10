package com.pai.android.agent.skills.home.device

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Диспетчер для управления устройствами умного дома «вслепую» —
 * без предварительного сканирования и базы устройств.
 *
 * ## Режимы работы
 *
 * 1. **WiZ broadcast** — UDP broadcast на 255.255.255.255:38899.
 *    Команду получают ВСЕ WiZ лампы в сети. Не требует IP-адресов.
 *
 * 2. **Yeelight blind probe** — TCP-перебор по списку IP (порт 55443).
 *    Если нет списка — делает ping sweep подсети (254 адреса).
 *
 * ## Когда используется
 *
 * - Новая сеть, сканирование ещё не проводилось
 * - Сеть без авторизации на роутере (нет MAC/имён устройств)
 * - Быстрая команда «выключи везде свет» не глядя в базу
 */
@Singleton
class BlindDispatcher @Inject constructor(
    private val wizController: WizController,
    private val yeelightController: YeelightController
) {
    private val TAG = "BlindDispatcher"

    /** Порт Yeelight для TCP-подключения. */
    companion object {
        private const val YEELIGHT_PORT = 55443
        private const val YEELIGHT_TIMEOUT_MS = 500
        private const val PING_TIMEOUT_MS = 300
        /** Сколько IP проверять в подсети при ping sweep. */
        private const val MAX_PING_SWEEP = 254
    }

    // ================== Основной метод ==================

    /**
     * Выполнить команду вслепую.
     *
     * @param action действие (turn_on, turn_off, set_brightness, set_rgb, set_color_temp)
     * @param params параметры (level, r/g/b, temp)
     * @param ips опциональный список IP для Yeelight probe (если null — ping sweep)
     * @return человекочитаемый результат
     */
    suspend fun dispatch(
        action: String,
        params: Map<String, Any> = emptyMap(),
        ips: List<String>? = null
    ): String {
        val results = mutableListOf<String>()

        // 1. WiZ broadcast (всегда — fire-and-forget)
        try {
            val wizResult = wizController.broadcast(action, params)
            if (wizResult.success) {
                results.add("💡 WiZ: ${wizResult.message}")
            } else {
                Log.w(TAG, "WiZ broadcast: ${wizResult.error}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WiZ broadcast error: ${e.message}")
        }

        // 2. Yeelight blind probe
        try {
            val yeelightResult = blindYeelight(action, params, ips)
            if (yeelightResult.isNotEmpty()) {
                results.add(yeelightResult)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Yeelight probe error: ${e.message}")
        }

        if (results.isEmpty()) {
            return "⚠️ Не удалось отправить команду. Убедись, что устройство в сети."
        }
        return results.joinToString("\n")
    }

    /**
     * Удобный метод для dispatch по текстовой команде.
     * Парсит через ControlIntentParser и выполняет.
     *
     * @param query текст запроса ("выключи свет", "включи", "яркость 50%")
     * @param ips опциональный список IP для Yeelight
     * @return результат
     */
    suspend fun dispatchQuery(query: String, ips: List<String>? = null): String {
        val intent = ControlIntentParser.parse(query)
        if (intent == null) {
            return "❌ Не могу понять команду. Примеры:\n" +
                    "• «выключи свет»\n" +
                    "• «включи свет»\n" +
                    "• «яркость 50%»\n" +
                    "• «цвет красный»\n" +
                    "• «turn off»"
        }

        val params = intent.params.toMutableMap()
        // Если есть имя цвета — конвертируем в RGB
        if (intent.action == "set_rgb" && !params.containsKey("r")) {
            val colorName = params["color"] as? String
            if (colorName != null) {
                val rgb = ColorNameMapper.toRgb(colorName)
                if (rgb != null) {
                    params["r"] = rgb.first
                    params["g"] = rgb.second
                    params["b"] = rgb.third
                }
            }
        }

        return dispatch(intent.action, params, ips)
    }

    // ================== Yeelight blind probe ==================

    /**
     * Отправить команду на все возможные Yeelight лампы.
     *
     * Стратегия:
     * 1. Если передан список IP — пробуем каждый на порт 55443
     * 2. Если списка нет — делаем ping sweep всей подсети (192.168.x.1-254)
     *    и на отвечающие шлём команду
     *
     * @return строка с результатом или пустая строка
     */
    private suspend fun blindYeelight(
        action: String,
        params: Map<String, Any>,
        ips: List<String>?
    ): String {
        val targets = if (!ips.isNullOrEmpty()) {
            ips
        } else {
            // Ping sweep — найдём живые хосты в подсети
            val subnet = guessSubnet()
            if (subnet == null) {
                Log.w(TAG, "Yeelight: не удалось определить подсеть")
                return ""
            }
            pingSweep(subnet)
        }

        if (targets.isEmpty()) {
            return "⚠️ Yeelight: нет IP для проверки"
        }

        val results = mutableListOf<String>()
        for (ip in targets) {
            try {
                val result = sendYeelightCommand(ip, action, params)
                if (result.success) {
                    results.add("💡 $ip: ✅ ${result.message.ifBlank { "OK" }}")
                }
            } catch (_: Exception) {
                // Игнорируем — это ожидаемо для не-Yeelight устройств
            }
        }

        return if (results.isNotEmpty()) {
            "Yeelight: " + results.joinToString("; ")
        } else {
            ""
        }
    }

    /**
     * Попробовать отправить команду на IP:55443 по протоколу Yeelight.
     *
     * Yeelight JSON-RPC: {"id":1,"method":"METHOD","params":[...]}\r\n
     *
     * Если TCP-подключение успешно и пришёл ответ с "result" — считаем,
     * что устройство поняло команду.
     */
    private suspend fun sendYeelightCommand(
        ip: String,
        action: String,
        params: Map<String, Any>
    ): ControlResult {
        val jsonRpc = buildYeelightRpc(action, params) ?:
            return ControlResult(false, error = "Неизвестное действие: $action")

        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, YEELIGHT_PORT), YEELIGHT_TIMEOUT_MS)
            socket.soTimeout = 1000

            val request = "$jsonRpc\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()

            // Читаем ответ
            val buf = ByteArray(1024)
            val len = socket.getInputStream().read(buf)
            socket.close()

            if (len > 0) {
                val response = String(buf, 0, len, Charsets.UTF_8)
                if (response.contains("\"result\"")) {
                    ControlResult(success = true, message = "✅ OK")
                } else if (response.contains("\"error\"")) {
                    ControlResult(success = false, error = "Ошибка устройства")
                } else {
                    ControlResult(success = true, message = "✅ Ответ: ${response.take(80)}")
                }
            } else {
                ControlResult(success = false, error = "Нет ответа")
            }
        } catch (e: java.net.SocketTimeoutException) {
            ControlResult(success = false, error = "Таймаут")
        } catch (e: java.net.ConnectException) {
            ControlResult(success = false, error = "Нет соединения")
        } catch (e: Exception) {
            ControlResult(success = false, error = e.message ?: "Ошибка")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Построить JSON-RPC для Yeelight по типу действия.
     */
    private fun buildYeelightRpc(action: String, params: Map<String, Any>): String? {
        return when (action) {
            "turn_on", "start" -> """{"id":1,"method":"set_power","params":["on","smooth",500]}"""
            "turn_off", "stop" -> """{"id":1,"method":"set_power","params":["off","smooth",500]}"""
            "set_brightness" -> {
                val level = (params["level"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 50
                """{"id":1,"method":"set_bright","params":[$level,"smooth",500]}"""
            }
            "set_color_temp" -> {
                val temp = (params["temp"] as? Number)?.toInt()?.coerceIn(1700, 6500) ?: 3500
                """{"id":1,"method":"set_ct_abx","params":[$temp,"smooth",500]}"""
            }
            "set_rgb" -> {
                val r = (params["r"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                val g = (params["g"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                val b = (params["b"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                val rgb = (r shl 16) or (g shl 8) or b
                """{"id":1,"method":"set_rgb","params":[$rgb,"smooth",500]}"""
            }
            else -> null
        }
    }

    // ================== Утилиты ==================

    /**
     * Определить подсеть по IP телефонного интерфейса.
     * Ищет Wi-Fi интерфейс, берёт его IP и вычисляет /24 подсеть.
     *
     * @return строка вида "192.168.1" или null
     */
    private fun guessSubnet(): String? {
        return try {
            val interfaces = java.util.Collections.list(
                java.net.NetworkInterface.getNetworkInterfaces()
            )
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback ||
                    ni.name.contains("rmnet") || ni.name.contains("p2p") ||
                    ni.name.contains("lo") || ni.name.contains("docker")
                ) continue

                val addrs = java.util.Collections.list(ni.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        // Берём первые 3 октета
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "guessSubnet error: ${e.message}")
            null
        }
    }

    /**
     * Ping sweep: быстро проверить, какие хосты в подсети отвечают.
     * Использует InetAddress.isReachable (ICMP или TCP echo на порт 7).
     *
     * @param subnet префикс подсети, например "192.168.1"
     * @return список живых IP
     */
    private fun pingSweep(subnet: String): List<String> {
        Log.d(TAG, "pingSweep: scanning $subnet.1-254")
        val alive = mutableListOf<String>()
        val threads = mutableListOf<Thread>()

        for (i in 1..MAX_PING_SWEEP) {
            val ip = "$subnet.$i"
            val t = Thread {
                try {
                    val addr = InetAddress.getByName(ip)
                    if (addr.isReachable(PING_TIMEOUT_MS)) {
                        synchronized(alive) { alive.add(ip) }
                    }
                } catch (_: Exception) {}
            }
            threads.add(t)
            t.start()
        }

        for (t in threads) {
            try { t.join(5000) } catch (_: Exception) {}
        }

        Log.d(TAG, "pingSweep: found ${alive.size} alive hosts")
        return alive.toList()
    }

    /**
     * Быстрый TCP probe: проверить, открыт ли порт на IP.
     * Используется для быстрого поиска Yeelight ламп без ping sweep.
     *
     * @param ips список IP для проверки
     * @param port порт (55443 для Yeelight)
     * @return список IP с открытым портом
     */
    suspend fun probePort(ips: List<String>, port: Int = YEELIGHT_PORT): List<String> {
        val found = mutableListOf<String>()
        for (ip in ips) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 300)
                socket.close()
                found.add(ip)
            } catch (_: Exception) {}
        }
        return found
    }
}
