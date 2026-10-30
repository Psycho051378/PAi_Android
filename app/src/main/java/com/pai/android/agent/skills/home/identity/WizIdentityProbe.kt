package com.pai.android.agent.skills.home.identity

import com.pai.android.agent.skills.home.net.LanNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Идентичность ламп WiZ (и совместимых Gauss).
 *
 * WiZ — самый удобный протокол для «нулевой конфигурации»: любая лампа отвечает
 * на broadcast-запрос getPilot и присылает в ответе свой MAC, текущее состояние
 * и (в новых прошивках) цветовую температуру в Кельвинах.
 *
 * Один UDP-пакет на 255.255.255.255:38899 → ответы от всех ламп в сети.
 */
@Singleton
class WizIdentityProbe @Inject constructor(
    private val lanNetwork: LanNetwork
) : IdentityProbe {

    override val protocol: String = "WIZ"

    companion object {
        const val PORT = 38899
        private const val BROADCAST_ADDR = "255.255.255.255"
        private const val MAX_PACKET = 4096
        private const val GET_PILOT = "{\"method\":\"getPilot\",\"params\":{}}"
        private const val GET_CONFIG = "{\"method\":\"getSystemConfig\",\"params\":{}}"
    }

    /** WiZ не слушает TCP, поэтому пробник имеет смысл только для хостов без открытых портов. */
    override fun accepts(openPorts: List<Int>): Boolean = openPorts.isEmpty()

    /**
     * Broadcast-опрос всех WiZ в сети.
     *
     * @return по одному IdentityHit на устройство (с MAC и состоянием)
     */
    suspend fun broadcastProbe(timeoutMs: Int = 1500): List<IdentityHit> = withContext(Dispatchers.IO) {
        val hits = LinkedHashMap<String, IdentityHit>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs.coerceAtLeast(100)
            }
            // Привязка к Wi-Fi обязательна: при активном VPN broadcast уходит в туннель
            lanNetwork.bind(socket)
            val payload = GET_PILOT.toByteArray(Charsets.UTF_8)
            // Broadcast подсети надёжнее глобального 255.255.255.255
            val targets = listOfNotNull(lanNetwork.broadcastAddress(), BROADCAST_ADDR).distinct()
            for (target in targets) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(target), PORT))
                } catch (e: Exception) {
                    println("WizIdentityProbe: broadcast to \$target failed — \${e.message}")
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(MAX_PACKET)
            while (true) {
                val left = (deadline - System.currentTimeMillis()).toInt()
                if (left <= 0) break
                try {
                    socket.soTimeout = left
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val ip = packet.address?.hostAddress ?: continue
                    val hit = parsePilot(ip, String(packet.data, 0, packet.length, Charsets.UTF_8), "wiz-broadcast")
                    if (hit != null) hits[ip] = hit
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            println("WizIdentityProbe: broadcast error — ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
        println("WizIdentityProbe: broadcast found ${hits.size} lamps")
        hits.values.toList()
    }

    /**
     * Адресный опрос одного хоста (на случай, если broadcast режет точка доступа).
     * Дополнительно забирает getSystemConfig — оттуда приходит model/roomId.
     */
    suspend fun unicastProbe(ip: String, timeoutMs: Int = 600): IdentityHit? = withContext(Dispatchers.IO) {
        val pilot = sendAndReceive(ip, GET_PILOT, timeoutMs)
        val config = sendAndReceive(ip, GET_CONFIG, timeoutMs)
        val hit = pilot?.let { parsePilot(ip, it, "wiz-unicast") }
        if (hit == null) return@withContext null

        val cfg = config?.let { runCatching { JSONObject(it).optJSONObject("result") }.getOrNull() }
        val model = cfg?.optString("moduleName", null)?.takeIf { it.isNotBlank() }
        val fw = cfg?.optString("fwVersion", null)?.takeIf { it.isNotBlank() }
        val roomId = cfg?.optString("roomId", null)?.takeIf { it.isNotBlank() }
        val extra: Map<String, String> = if (roomId != null) mapOf("roomId" to roomId) else emptyMap()
        val merged = hit.state?.let { state -> state.copy(extra = state.extra + extra) }

        hit.copy(model = model ?: hit.model, firmware = fw ?: hit.firmware, state = merged)
    }

    private fun sendAndReceive(ip: String, payload: String, timeoutMs: Int): String? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply { soTimeout = timeoutMs }
            lanNetwork.bind(socket)
            val data = payload.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), PORT))
            val buffer = ByteArray(MAX_PACKET)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            String(packet.data, 0, packet.length, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    private fun parsePilot(ip: String, raw: String, source: String): IdentityHit? {
        return try {
            val result = JSONObject(raw).optJSONObject("result") ?: return null
            val mac = MacUtil.normalize(result.optString("mac", null))
            if (mac == null && !result.has("state")) return null

            // Старые прошивки WiZ отдают temp в процентах (0-100), новые — в Кельвинах.
            val rawTemp = if (result.has("temp")) result.optInt("temp", -1) else -1
            val kelvin = if (rawTemp in 1000..10000) rawTemp else null

            val rgb = if (result.has("r") && result.has("g") && result.has("b")) {
                Triple(
                    result.optInt("r", 0).coerceIn(0, 255),
                    result.optInt("g", 0).coerceIn(0, 255),
                    result.optInt("b", 0).coerceIn(0, 255)
                )
            } else null

            val state = DeviceState(
                power = if (result.has("state")) result.optBoolean("state") else null,
                brightness = if (result.has("dimming")) result.optInt("dimming", 0) else null,
                colorTempKelvin = kelvin,
                rgb = rgb
            )

            IdentityHit(
                ip = ip,
                protocol = protocol,
                port = PORT,
                mac = mac,
                name = null,
                capabilities = listOf("on_off", "brightness", "color_temp", "rgb"),
                state = state,
                source = source
            )
        } catch (e: Exception) {
            null
        }
    }
}
