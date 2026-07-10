package com.pai.android.agent.skills.home.device

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
 */
@Singleton
class WizController @Inject constructor() : DeviceController {

    override val protocol: String = "WIZ"

    companion object {
        private const val DEFAULT_PORT = 38899
        private const val TIMEOUT_MS = 3000
        private const val MAX_PACKET_SIZE = 2048
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
}
