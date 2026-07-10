package com.pai.android.agent.skills.home.device

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Контроллер для ламп Yeelight (Protocol.YEELIGHT).
 *
 * Протокол Yeelight (JSON-RPC over TCP):
 * - Порт: 55443
 * - Команды: JSON-RPC через TCP-сокет
 * - Формат: {"id":1,"method":"set_power","params":["on","smooth",500]}
 * - Ответ:  {"id":1,"result":["ok"]}
 *
 * Все методы thread-safe (каждый вызов создаёт свой сокет).
 * Yeelight поддерживает до 4 одновременных соединений — этого достаточно.
 */
@Singleton
class YeelightController @Inject constructor() : DeviceController {

    override val protocol: String = "YEELIGHT"

    companion object {
        private const val DEFAULT_PORT = 55443
        private const val TIMEOUT_MS = 3000
        private const val EFFECT_SMOOTH = "smooth"
        private const val EFFECT_SUDDEN = "sudden"
        private const val DURATION_MS = 500
    }

    /** Счётчик ID для JSON-RCP запросов (потокобезопасный). */
    private var requestId = 0

    override suspend fun turnOn(ip: String, port: Int): ControlResult {
        return sendCommand(ip, port, "set_power", listOf("on", EFFECT_SMOOTH, DURATION_MS))
    }

    override suspend fun turnOff(ip: String, port: Int): ControlResult {
        return sendCommand(ip, port, "set_power", listOf("off", EFFECT_SMOOTH, DURATION_MS))
    }

    override suspend fun setBrightness(ip: String, port: Int, level: Int): ControlResult {
        val clamped = level.coerceIn(1, 100)
        return sendCommand(ip, port, "set_bright", listOf(clamped, EFFECT_SMOOTH, DURATION_MS))
    }

    override suspend fun setColorTemp(ip: String, port: Int, temp: Int): ControlResult {
        // Yeelight: 1700..6500K
        val clamped = temp.coerceIn(1700, 6500)
        return sendCommand(ip, port, "set_ct_abx", listOf(clamped, EFFECT_SMOOTH, DURATION_MS))
    }

    override suspend fun setRGB(ip: String, port: Int, r: Int, g: Int, b: Int): ControlResult {
        val rgb = ((r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255))
        return sendCommand(ip, port, "set_rgb", listOf(rgb, EFFECT_SMOOTH, DURATION_MS))
    }

    override suspend fun ping(ip: String, port: Int): Boolean {
        return try {
            val result = sendCommand(ip, port, "get_prop", listOf("power"))
            result.success
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun customCommand(ip: String, port: Int, command: String, params: Map<String, Any>): ControlResult {
        val jsonParams = params.map { (_, v) ->
            if (v is String) "\"$v\"" else v.toString()
        }
        return sendCommand(ip, port, command, jsonParams)
    }

    /**
     * Отправить JSON-RPC команду по TCP и получить ответ.
     */
    private fun sendCommand(ip: String, port: Int, method: String, params: List<Any>): ControlResult {
        val effectivePort = if (port > 0) port else DEFAULT_PORT
        val id = synchronized(this) { ++requestId }

        val jsonParams = params.joinToString(",") { param ->
            when (param) {
                is String -> "\"$param\""
                is Int -> param.toString()
                is Boolean -> param.toString()
                else -> "\"$param\""
            }
        }
        val payload = """{"id":$id,"method":"$method","params":[$jsonParams]}"""
        val request = "$payload\r\n"

        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, effectivePort), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS

            // Отправляем
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write(request)
            writer.flush()

            // Читаем ответ — Yeelight присылает одну строку \r\n
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val response = reader.readLine() ?: ""

            socket.close()

            if (response.contains("\"result\"")) {
                ControlResult(success = true, message = "✅ OK")
            } else if (response.contains("\"error\"")) {
                val msg = extractErrorMessage(response)
                ControlResult(success = false, error = "Устройство вернуло ошибку: $msg")
            } else {
                ControlResult(success = true, message = "✅ Ответ: ${response.take(100)}")
            }
        } catch (e: java.net.SocketTimeoutException) {
            ControlResult(success = false, error = "Таймаут — устройство не ответило за ${TIMEOUT_MS}мс")
        } catch (e: java.net.ConnectException) {
            ControlResult(success = false, error = "Не удалось подключиться к ${ip}:${effectivePort}")
        } catch (e: Exception) {
            ControlResult(success = false, error = e.message ?: "Неизвестная ошибка")
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    /**
     * Извлечь сообщение ошибки из JSON-RPC ответа:
     * {"id":1,"error":{"code":-1,"message":"msg"}}
     */
    private fun extractErrorMessage(response: String): String {
        val msgRegex = """"message"\s*:\s*"([^"]+)"""".toRegex()
        return msgRegex.find(response)?.groupValues?.getOrNull(1) ?: "неизвестная ошибка"
    }
}
