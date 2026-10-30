package com.pai.android.agent.skills.home.device

import com.pai.android.agent.skills.home.identity.DeviceState
import com.pai.android.agent.skills.home.net.LanNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Контроллер для ламп WiZ (Protocol.WIZ).
 *
 * Протокол WiZ:
 * - Порт: 38899 (UDP)
 * - Команды: JSON через UDP датаграммы
 * - Формат: {"method":"setPilot","params":{"state":true,"dimming":100,...}}
 * - Ответ: {"method":"setPilot","env":"pro","result":{"mac":"...","success":true}}
 *
 * Все методы thread-safe (каждый вызов создаёт свой сокет).
 *
 * ## Broadcast-режим
 *
 * WiZ поддерживает отправку команд на broadcast-адрес (255.255.255.255:38899).
 * Все лампы в сети получат команду одновременно, что позволяет управлять
 * светом даже без предварительного сканирования и базы устройств.
 *
 * Используется в BlindDispatcher для работы «вслепую».
 */
@Singleton
class WizController @Inject constructor(
    private val lanNetwork: LanNetwork
) : StatefulController {

    override val protocol: String = "WIZ"

    companion object {
        private const val DEFAULT_PORT = 38899
        private const val TIMEOUT_MS = 3000
        private const val MAX_PACKET_SIZE = 2048
        /** Broadcast-адрес для отправки команд всем WiZ лампам в сети. */
        private const val BROADCAST_ADDR = "255.255.255.255"
    }

    override suspend fun turnOn(ip: String, port: Int): ControlResult {
        return sendCommand(ip, port, """{"method":"setPilot","params":{"state":true}}""")
    }

    override suspend fun turnOff(ip: String, port: Int): ControlResult {
        return sendCommand(ip, port, """{"method":"setPilot","params":{"state":false}}""")
    }

    override suspend fun setBrightness(ip: String, port: Int, level: Int): ControlResult {
        val clamped = level.coerceIn(0, 100)
        return sendCommand(ip, port, """{"method":"setPilot","params":{"state":${clamped > 0},"dimming":$clamped}}""")
    }

    override suspend fun setColorTemp(ip: String, port: Int, temp: Int): ControlResult {
        // WiZ: temp in range 0-100 (0=cold, 100=warm)
        val clamped = temp.coerceIn(0, 100)
        return sendCommand(ip, port, """{"method":"setPilot","params":{"state":true,"temp":$clamped}}""")
    }

    override suspend fun setRGB(ip: String, port: Int, r: Int, g: Int, b: Int): ControlResult {
        return sendCommand(ip, port,
            """{"method":"setPilot","params":{"state":true,"r":$r,"g":$g,"b":$b}}""")
    }

    override suspend fun ping(ip: String, port: Int): Boolean {
        return try {
            val result = sendCommand(ip, port, """{"method":"getPilot","params":{}}""")
            result.success
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun customCommand(ip: String, port: Int, command: String, params: Map<String, Any>): ControlResult {
        val jsonParams = params.entries.joinToString(",") { (k, v) ->
            """"$k":${if (v is String) "\"$v\"" else v}"""
        }
        val payload = """{"method":"$command","params":{$jsonParams}}"""
        return sendCommand(ip, port, payload)
    }

    // ================== Broadcast-методы (Blind mode) ==================

    /**
     * Отправить команду на broadcast-адрес (255.255.255.255:38899).
     * Команду получат ВСЕ WiZ лампы в сети — не требуется знать их IP.
     *
     * @param action действие: "turn_on", "turn_off", "set_brightness", "set_color_temp", "set_rgb"
     * @param params параметры: level, temp, r/g/b
     * @return ControlResult — успех если пакет отправлен (даже если ламп нет в сети)
     */
    suspend fun broadcast(action: String, params: Map<String, Any> = emptyMap()): ControlResult {
        val payload = buildBroadcastPayload(action, params) ?:
            return ControlResult(success = false, error = "Неизвестное действие: $action")
        return sendBroadcast(payload)
    }

    /**
     * Включить весь свет в сети (broadcast).
     */
    suspend fun broadcastTurnOn(): ControlResult =
        sendBroadcast("""{"method":"setPilot","params":{"state":true}}""")

    /**
     * Выключить весь свет в сети (broadcast).
     */
    suspend fun broadcastTurnOff(): ControlResult =
        sendBroadcast("""{"method":"setPilot","params":{"state":false}}""")

    /**
     * Установить яркость всего света в сети (broadcast).
     */
    suspend fun broadcastBrightness(level: Int): ControlResult {
        val clamped = level.coerceIn(0, 100)
        return sendBroadcast("""{"method":"setPilot","params":{"state":${clamped > 0},"dimming":$clamped}}""")
    }

    /**
     * Установить цветовую температуру всего света (broadcast).
     */
    suspend fun broadcastColorTemp(temp: Int): ControlResult {
        val clamped = temp.coerceIn(0, 100)
        return sendBroadcast("""{"method":"setPilot","params":{"state":true,"temp":$clamped}}""")
    }

    /**
     * Установить RGB цвет всего света (broadcast).
     */
    suspend fun broadcastRGB(r: Int, g: Int, b: Int): ControlResult {
        return sendBroadcast("""{"method":"setPilot","params":{"state":true,"r":$r,"g":$g,"b":$b}}""")
    }

    /**
     * Построить JSON-payload для broadcast по типу действия.
     */
    private fun buildBroadcastPayload(action: String, params: Map<String, Any>): String? {
        return when (action) {
            "turn_on", "start" -> """{"method":"setPilot","params":{"state":true}}"""
            "turn_off", "stop" -> """{"method":"setPilot","params":{"state":false}}"""
            "set_brightness" -> {
                val level = (params["level"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 50
                """{"method":"setPilot","params":{"state":${level > 0},"dimming":$level}}"""
            }
            "set_color_temp" -> {
                val temp = (params["temp"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 50
                """{"method":"setPilot","params":{"state":true,"temp":$temp}}"""
            }
            "set_rgb" -> {
                val r = (params["r"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                val g = (params["g"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                val b = (params["b"] as? Number)?.toInt()?.coerceIn(0, 255) ?: 255
                """{"method":"setPilot","params":{"state":true,"r":$r,"g":$g,"b":$b}}"""
            }
            "set_mode" -> {
                val mode = params["mode"] ?: "auto"
                """{"method":"setPilot","params":{"state":true,"sceneId":1}}"""
            }
            else -> null
        }
    }

    /**
     * Отправить UDP пакет на broadcast-адрес (255.255.255.255:38899).
     * Не ждёт ответов — fire-and-forget.
     */
    private suspend fun sendBroadcast(payload: String): ControlResult {
        return try {
            val socket = DatagramSocket()
            // Без привязки к Wi-Fi broadcast уходит в VPN-туннель (проверено на WARP)
            lanNetwork.bind(socket)
            socket.broadcast = true
            socket.soTimeout = 500

            val data = payload.toByteArray(Charsets.UTF_8)
            // Broadcast подсети надёжнее глобального
            val targets = listOfNotNull(lanNetwork.broadcastAddress(), BROADCAST_ADDR).distinct()
            for (target in targets) {
                socket.send(DatagramPacket(data, data.size, InetAddress.getByName(target), DEFAULT_PORT))
            }

            // Короткое ожидание — собираем статистику ответов
            var replies = 0
            val buf = ByteArray(MAX_PACKET_SIZE)
            val endTime = System.currentTimeMillis() + 1000
            while (System.currentTimeMillis() < endTime) {
                try {
                    val recv = DatagramPacket(buf, buf.size)
                    socket.receive(recv)
                    replies++
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }
            socket.close()

            val msg = if (replies > 0) "✅ Отправлено (broadcast), ответили $replies ламп"
                      else "✅ Отправлено (broadcast)"
            ControlResult(success = true, message = msg)
        } catch (e: Exception) {
            ControlResult(success = false, error = "WiZ broadcast: ${e.message ?: "ошибка"}")
        }
    }

    // ================== Адресные методы (Addressable mode) ==================

    private suspend fun sendCommand(ip: String, port: Int, payload: String): ControlResult {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS

            val addr = InetAddress.getByName(ip)
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            val sendData = payload.toByteArray(Charsets.UTF_8)
            val sendPacket = DatagramPacket(sendData, sendData.size, addr, effectivePort)
            socket.send(sendPacket)

            // Ждём ответ
            val recvBuf = ByteArray(MAX_PACKET_SIZE)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)

            val response = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
            socket.close()

            if (response.contains("\"success\":true")) {
                ControlResult(success = true, message = "✅ OK")
            } else if (response.contains("\"success\":false")) {
                ControlResult(success = false, error = "Устройство отклонило команду")
            } else {
                // Ответ получен, но нестандартный — считаем успехом
                ControlResult(success = true, message = "✅ Ответ: ${response.take(100)}")
            }
        } catch (e: java.net.SocketTimeoutException) {
            ControlResult(success = false, error = "Таймаут — устройство не ответило за ${TIMEOUT_MS}мс")
        } catch (e: Exception) {
            ControlResult(success = false, error = e.message ?: "Неизвестная ошибка")
        }
    }

    // ================== Чтение состояния (StatefulController) ==================

    /**
     * Текущее состояние лампы: getPilot возвращает state/dimming/temp/rgb
     * одним ответом — этого достаточно и для верификации, и для идемпотентности.
     */
    override suspend fun readState(ip: String, port: Int): DeviceState? = withContext(Dispatchers.IO) {
        val raw = requestRaw(ip, port, """{"method":"getPilot","params":{}}""") ?: return@withContext null
        parsePilotState(raw)
    }

    private fun requestRaw(ip: String, port: Int, payload: String, timeoutMs: Int = 1200): String? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            lanNetwork.bind(socket)
            socket.soTimeout = timeoutMs
            val effectivePort = if (port > 0) port else DEFAULT_PORT
            val data = payload.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), effectivePort))
            val buffer = ByteArray(MAX_PACKET_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            String(packet.data, 0, packet.length, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    private fun parsePilotState(raw: String): DeviceState? {
        return try {
            val result = JSONObject(raw).optJSONObject("result") ?: return null
            // Новые прошивки WiZ отдают temp в Кельвинах, старые — в процентах
            val rawTemp = if (result.has("temp")) result.optInt("temp", -1) else -1
            val kelvin = if (rawTemp in 1000..10000) rawTemp else null
            val rgb = if (result.has("r") && result.has("g") && result.has("b")) {
                Triple(
                    result.optInt("r", 0).coerceIn(0, 255),
                    result.optInt("g", 0).coerceIn(0, 255),
                    result.optInt("b", 0).coerceIn(0, 255)
                )
            } else null

            DeviceState(
                power = if (result.has("state")) result.optBoolean("state") else null,
                brightness = if (result.has("dimming")) result.optInt("dimming", 0) else null,
                colorTempKelvin = kelvin,
                rgb = rgb
            )
        } catch (e: Exception) {
            null
        }
    }
}
