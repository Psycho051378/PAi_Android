package com.pai.android.agent.skills.home.device

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeout

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
    private val yeelightController: YeelightController,
    private val shellyController: ShellyController,
    private val tasmotaController: TasmotaController,
    private val genericHttpController: GenericHttpController,
    private val tpLinkKasaController: TpLinkKasaController
) {
    private val TAG = "BlindDispatcher"

    /** Порт Yeelight для TCP-подключения. */
    companion object {
        private const val YEELIGHT_PORT = 55443
        private const val YEELIGHT_TIMEOUT_MS = 300  // 300мс на IP — 254× = ~76с макс
        private const val PING_TIMEOUT_MS = 200
        /** Сколько IP проверять в подсети при ping sweep. */
        private const val MAX_PING_SWEEP = 254
        /** Сколько параллельных probe (TCP connect) запускать. */
        private const val PROBE_PARALLELISM = 30
        /** HTTP порт для Shelly / Tasmota / WLED probe. */
        private const val HTTP_PORT = 80
        /** Таймаут для HTTP probe. */
        private const val HTTP_PROBE_TIMEOUT_MS = 500
    }

    // ================== Основной метод ==================

    /**
     * Выполнить команду вслепую.
     *
     * Прогоняет команду через все известные протоколы:
     * 1. WiZ broadcast (UDP 38899)
     * 2. Yeelight probe (TCP 55443)
     * 3. Shelly probe (HTTP 80)
     * 4. Tasmota probe (HTTP 80)
     * 5. WLED / Generic HTTP probe (HTTP 80)
         * 6. TP-Link Kasa/Tapo probe (UDP discovery)
     *
     * @param action действие (turn_on, turn_off, set_brightness, set_rgb, set_color_temp)
     * @param params параметры (level, r/g/b, temp)
     * @param ips опциональный список IP для probe (если null — ping sweep)
     * @return человекочитаемый результат
     */
    suspend fun dispatch(
        action: String,
        params: Map<String, Any> = emptyMap(),
        ips: List<String>? = null
    ): String {
        val results = mutableListOf<String>()

        // 1. WiZ broadcast (всегда — fire-and-forget)
        println("BlindDispatcher: WiZ broadcast: action=$action params=$params")
        try {
            val wizResult = wizController.broadcast(action, params)
            if (wizResult.success) {
                results.add("💡 WiZ: ${wizResult.message}")
            }
        } catch (e: Exception) {
            println("BlindDispatcher: WiZ exception — ${e.message}")
        }

        // 2. Yeelight blind probe
        println("BlindDispatcher: starting Yeelight probe...")
        try {
            val yeelightResult = blindYeelight(action, params, ips)
            if (yeelightResult.isNotEmpty()) {
                results.add(yeelightResult)
            }
        } catch (e: Exception) {
            println("BlindDispatcher: Yeelight probe error — ${e.message}")
        }

        // 3. HTTP blind probe (Shelly + Tasmota + WLED — порт 80)
        println("BlindDispatcher: starting HTTP probe (Shelly/Tasmota/WLED)...")
        try {
            val httpResult = kotlinx.coroutines.withTimeout(15000L) {
                blindHttpDevices(action, params, ips)
            }
            if (httpResult.isNotEmpty()) {
                results.add(httpResult)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            println("BlindDispatcher: HTTP probe timeout (15s)")
        } catch (e: Exception) {
            println("BlindDispatcher: HTTP probe error — ${e.message}")
        }

        // 4. TP-Link Kasa blind probe (UDP discovery через python-kasa)
        println("BlindDispatcher: starting Kasa/Tapo probe...")
        try {
            val kasaResult = tpLinkKasaController.blindControl(action, params)
            if (kasaResult.isNotEmpty()) {
                results.add(kasaResult)
                println("BlindDispatcher: Kasa result: $kasaResult")
            } else {
                println("BlindDispatcher: Kasa — no devices found")
            }
        } catch (e: Exception) {
            println("BlindDispatcher: Kasa probe error — ${e.message}")
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
     * 1. Если передан список IP — пробуем каждый на порт 55443 (параллельно)
     * 2. Если списка нет — делаем ping sweep всей подсети, затем на живые хосты шлём команду
     *
     * @return строка с результатом или пустая строка
     */
    private suspend fun blindYeelight(
        action: String,
        params: Map<String, Any>,
        ips: List<String>?
    ): String {
        println("BlindDispatcher: blindYeelight started, action=$action ips=${ips?.size ?: "null"}")

        val targets = if (!ips.isNullOrEmpty()) {
            println("BlindDispatcher: using provided ${ips.size} IPs")
            ips
        } else {
            val subnet = guessSubnet()
            if (subnet == null) {
                println("BlindDispatcher: Yeelight — не удалось определить подсеть")
                return ""
            }
            println("BlindDispatcher: ping sweep subnet=$subnet")
            val alive = pingSweep(subnet)
            println("BlindDispatcher: ping sweep found ${alive.size} alive hosts")
            alive
        }

        if (targets.isEmpty()) {
            println("BlindDispatcher: Yeelight — нет IP для проверки")
            return "⚠️ Yeelight: нет IP для проверки"
        }

        // Параллельный probe: PROBE_PARALLELISM потоков
        val results = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        val lock = Any()
        var ipIndex = 0

        for (tId in 0 until PROBE_PARALLELISM) {
            val t = Thread {
                while (true) {
                    val ip: String
                    synchronized(lock) {
                        if (ipIndex >= targets.size) return@Thread
                        ip = targets[ipIndex]
                        ipIndex++
                    }
                    try {
                        val result = sendYeelightCommandSync(ip, action, params)
                        if (result.success) {
                            synchronized(lock) {
                                results.add("💡 $ip: ✅ ${result.message.ifBlank { "OK" }}")
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            threads.add(t)
            t.start()
        }

        for (t in threads) {
            try { t.join(60000) } catch (_: Exception) { }
        }

        println("BlindDispatcher: Yeelight done — ${results.size} lamps found")
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
     * Синхронная версия sendYeelightCommand (без suspend) для вызова из Thread.
     */
    private fun sendYeelightCommandSync(
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
            try { socket?.close() } catch (_: Exception) { }
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

    // ================== HTTP blind probe (Shelly + Tasmota + WLED) ==================

    /**
     * Отправить команду на все устройства с открытым HTTP-портом.
     *
     * Стратегия:
     * 1. Если передан список IP — пробуем каждый на порт 80 (параллельно)
     * 2. Если списка нет — ping sweep, затем проверяем открыт ли порт 80
     * 3. Для каждого живого IP: идентифицируем протокол (Shelly / Tasmota / WLED)
     * 4. Отправляем команду через соответствующий контроллер
     *
     * @return строка с результатом или пустая строка
     */
    private suspend fun blindHttpDevices(
        action: String,
        params: Map<String, Any>,
        ips: List<String>?
    ): String {
        val targets = if (!ips.isNullOrEmpty()) {
            ips
        } else {
            val subnet = guessSubnet()
            if (subnet == null) {
                println("BlindDispatcher: HTTP probe — subnet not found")
                return ""
            }
            println("BlindDispatcher: HTTP probe — TCP scan $subnet.1-254 port $HTTP_PORT...")
            scanPort80(subnet)
        }

        if (targets.isEmpty()) {
            println("BlindDispatcher: HTTP probe — no hosts with port $HTTP_PORT open")
            return ""
        }

        println("BlindDispatcher: HTTP probe — ${targets.size} hosts, probing (10 IPs at a time)...")
        val results = mutableListOf<String>()

        // По 10 IP за раз, чтобы не создавать 254 потока одновременно
        targets.chunked(10).forEach { batch ->
            val threads = batch.map { ip ->
                Thread {
                    val found = probeDeviceSync(ip, action, params)
                    if (found != null) {
                        synchronized(results) { results.add(found) }
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { try { it.join(5000) } catch (_: Exception) {} }
        }

        val resultStr = if (results.isNotEmpty()) results.joinToString("; ")
        else ""
        println("BlindDispatcher: HTTP probe done — ${if (resultStr.isEmpty()) "no matching devices found" else resultStr}")
        return resultStr
    }

    /**
     * Пробует Shelly, Tasmota и WLED на одном IP **последовательно**.
     * Вызывается из потока — не suspend.
     */
    private fun probeDeviceSync(
        ip: String,
        action: String,
        params: Map<String, Any>
    ): String? {
        // Shelly
        try {
            if (kotlinx.coroutines.runBlocking { shellyController.probeIdentity(ip, HTTP_PORT) }) {
                val r = kotlinx.coroutines.runBlocking { shellySend(action, params, ip) }
                if (r.success) return "💡 Shelly $ip: ${r.message}"
            }
        } catch (_: Exception) { }

        // Tasmota
        try {
            if (kotlinx.coroutines.runBlocking { tasmotaController.probeIdentity(ip, HTTP_PORT) }) {
                val r = kotlinx.coroutines.runBlocking { tasmotaSend(action, params, ip) }
                if (r.success) return "🔧 Tasmota $ip: ${r.message}"
            }
        } catch (_: Exception) { }

        // WLED
        try {
            if (kotlinx.coroutines.runBlocking { genericHttpController.probeWled(ip, HTTP_PORT) }) {
                val r = kotlinx.coroutines.runBlocking { wledSend(action, params, ip) }
                if (r.success) return "💡 WLED $ip: ${r.message}"
            }
        } catch (_: Exception) { }

        return null
    }

    /**
     * Отправить команду на Shelly устройство.
     */
    private suspend fun shellySend(action: String, params: Map<String, Any>, ip: String): ControlResult {
        return when (action) {
            "turn_on", "start" -> shellyController.turnOn(ip, HTTP_PORT)
            "turn_off", "stop" -> shellyController.turnOff(ip, HTTP_PORT)
            "set_brightness" -> {
                val level = (params["level"] as? Number)?.toInt() ?: 50
                shellyController.setBrightness(ip, HTTP_PORT, level)
            }
            "set_color_temp" -> {
                val temp = (params["temp"] as? Number)?.toInt() ?: 3500
                shellyController.setColorTemp(ip, HTTP_PORT, temp)
            }
            "set_rgb" -> {
                val r = (params["r"] as? Number)?.toInt() ?: 255
                val g = (params["g"] as? Number)?.toInt() ?: 255
                val b = (params["b"] as? Number)?.toInt() ?: 255
                shellyController.setRGB(ip, HTTP_PORT, r, g, b)
            }
            else -> shellyController.customCommand(ip, HTTP_PORT, action, params)
        }
    }

    /**
     * Отправить команду на Tasmota устройство.
     */
    private suspend fun tasmotaSend(action: String, params: Map<String, Any>, ip: String): ControlResult {
        return when (action) {
            "turn_on", "start" -> tasmotaController.turnOn(ip, HTTP_PORT)
            "turn_off", "stop" -> tasmotaController.turnOff(ip, HTTP_PORT)
            "set_brightness" -> {
                val level = (params["level"] as? Number)?.toInt() ?: 50
                tasmotaController.setBrightness(ip, HTTP_PORT, level)
            }
            "set_color_temp" -> {
                val temp = (params["temp"] as? Number)?.toInt() ?: 3500
                tasmotaController.setColorTemp(ip, HTTP_PORT, temp)
            }
            "set_rgb" -> {
                val r = (params["r"] as? Number)?.toInt() ?: 255
                val g = (params["g"] as? Number)?.toInt() ?: 255
                val b = (params["b"] as? Number)?.toInt() ?: 255
                tasmotaController.setRGB(ip, HTTP_PORT, r, g, b)
            }
            else -> tasmotaController.customCommand(ip, HTTP_PORT, action, params)
        }
    }

    /**
     * Отправить команду на WLED устройство.
     */
    private suspend fun wledSend(action: String, params: Map<String, Any>, ip: String): ControlResult {
        return when (action) {
            "turn_on", "start" -> genericHttpController.turnOn(ip, HTTP_PORT)
            "turn_off", "stop" -> genericHttpController.turnOff(ip, HTTP_PORT)
            "set_brightness" -> {
                val level = (params["level"] as? Number)?.toInt() ?: 128
                genericHttpController.setBrightness(ip, HTTP_PORT, level)
            }
            "set_rgb" -> {
                val r = (params["r"] as? Number)?.toInt() ?: 255
                val g = (params["g"] as? Number)?.toInt() ?: 255
                val b = (params["b"] as? Number)?.toInt() ?: 255
                genericHttpController.setRGB(ip, HTTP_PORT, r, g, b)
            }
            else -> genericHttpController.customCommand(ip, HTTP_PORT, action, params)
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
            println("BlindDispatcher: guessSubnet error — ${e.message}")
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
        println("BlindDispatcher: ping sweep $subnet.1-254")
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

        println("BlindDispatcher: ping sweep done — ${alive.size} alive hosts")
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

    /**
     * Быстрый параллельный TCP скан порта 80 по всей подсети.
     * Таймаут 100ms — реальное устройство ответит быстрее,
     * а фантомные адреса не проходят.
     */
    private fun scanPort80(subnet: String): List<String> {
        val found = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        val timeoutMs = 100 // 100ms на connect — достаточно для локальной сети

        for (i in 1..MAX_PING_SWEEP) {
            val ip = "$subnet.$i"
            val t = Thread {
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(ip, HTTP_PORT), timeoutMs)
                    socket.close()
                    synchronized(found) { found.add(ip) }
                } catch (_: Exception) {}
            }
            threads.add(t)
            t.start()
        }

        for (t in threads) {
            try { t.join(timeoutMs.toLong() + 50) } catch (_: Exception) {}
        }

        return found.sorted()
    }
}
