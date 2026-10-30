package com.pai.android.agent.skills.home.identity

import com.pai.android.agent.skills.home.net.LanNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Идентичность ламп Yeelight.
 *
 * Два независимых канала:
 * 1. SSDP M-SEARCH на 239.255.255.250:1982 (ST: wifi_bulb) — устройство отвечает
 *    unicast-пакетом с id, model, fw_ver, power, bright и (в новых прошивках) name.
 * 2. TCP 55443 get_prop — отдаёт power/bright/ct/rgb/name/model напрямую.
 *
 * ВАЖНО: канал 2 работает только при включённом «LAN Control» в приложении Yeelight.
 * Канал 1 работает всегда, пока лампа в сети.
 */
@Singleton
class YeelightIdentityProbe @Inject constructor(
    private val lanNetwork: LanNetwork
) : IdentityProbe {

    override val protocol: String = "YEELIGHT"

    companion object {
        const val TCP_PORT = 55443
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1982
        private const val MAX_PACKET = 4096
        private const val PROPS = "[\"power\",\"bright\",\"ct\",\"rgb\",\"name\",\"model\"]"
    }

    override fun accepts(openPorts: List<Int>): Boolean =
        openPorts.isEmpty() || openPorts.contains(TCP_PORT) || openPorts.contains(80)

    /**
     * SSDP-опрос всех Yeelight в сети.
     */
    suspend fun multicastProbe(timeoutMs: Int = 1500, rounds: Int = 2): List<IdentityHit> = withContext(Dispatchers.IO) {
        val hits = LinkedHashMap<String, IdentityHit>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs.coerceAtLeast(100)
            }
            // Без привязки к Wi-Fi multicast уходит в VPN-туннель
            lanNetwork.bind(socket)
            val request = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1982\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "ST: wifi_bulb\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
            val buffer = ByteArray(MAX_PACKET)
            val roundBudget = (timeoutMs / rounds.coerceAtLeast(1)).coerceAtLeast(200)
            for (round in 0 until rounds.coerceAtLeast(1)) {
                // Повтор M-SEARCH: один запрос легко теряется (Wi-Fi, IGMP snooping)
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(SSDP_ADDR), SSDP_PORT))
                val roundDeadline = System.currentTimeMillis() + roundBudget
                while (true) {
                    val left = (roundDeadline - System.currentTimeMillis()).toInt()
                    if (left <= 0) break
                    try {
                        socket.soTimeout = left
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val ip = packet.address?.hostAddress ?: continue
                        val hit = parseSsdp(ip, String(packet.data, 0, packet.length, Charsets.UTF_8))
                        if (hit != null) {
                            hits[ip] = merge(hits[ip], hit)
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            println("YeelightIdentityProbe: ssdp error — ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
        println("YeelightIdentityProbe: ssdp found ${hits.size} lamps")
        hits.values.toList()
    }

    /**
     * Unicast M-SEARCH каждому адресу подсети.
     *
     * Зачем: лампа может не отвечать на multicast (не вступила в группу после
     * перезагрузки, IGMP snooping на точке доступа), но отлично отвечает на
     * unicast. На реальной сети (3 лампы Yeelight) multicast нашёл 2,
     * unicast добавил третью.
     */
    suspend fun unicastSweep(
        ips: Collection<String>,
        timeoutMs: Int = 1200
    ): List<IdentityHit> = withContext(Dispatchers.IO) {
        if (ips.isEmpty()) return@withContext emptyList()
        val hits = LinkedHashMap<String, IdentityHit>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 120
            }
            lanNetwork.bind(socket)

            val request = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1982\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "ST: wifi_bulb\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
            val address = InetAddress.getByName(SSDP_ADDR)

            for (ip in ips) {
                try {
                    socket.send(DatagramPacket(request, request.size, InetAddress.getByName(ip), SSDP_PORT))
                } catch (_: Exception) { }
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
                    val hit = parseSsdp(ip, String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (hit != null) hits[ip] = merge(hits[ip], hit)
                } catch (e: SocketTimeoutException) {
                    break
                } catch (e: java.net.PortUnreachableException) {
                    continue
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            println("YeelightIdentityProbe: unicast sweep error — \${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
        println("YeelightIdentityProbe: unicast sweep found \${hits.size} lamps")
        hits.values.toList()
    }

    /**
     * Прямой опрос по TCP 55443 (нужен включённый LAN Control).
     */
    suspend fun tcpProbe(ip: String, timeoutMs: Int = 800): IdentityHit? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            lanNetwork.bind(socket)
            socket.connect(InetSocketAddress(ip, TCP_PORT), timeoutMs)
            socket.soTimeout = timeoutMs

            val request = "{\"id\":1,\"method\":\"get_prop\",\"params\":$PROPS}\r\n"
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write(request)
            writer.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return@withContext null
            parseTcpProps(ip, line)
        } catch (e: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    private fun parseTcpProps(ip: String, raw: String): IdentityHit? {
        return try {
            val array: JSONArray = org.json.JSONObject(raw).optJSONArray("result") ?: return null
            fun value(index: Int): String? =
                if (index < array.length()) array.optString(index, null)?.takeIf { it.isNotBlank() } else null

            val power = value(0)?.equals("on", ignoreCase = true)
            val brightness = value(1)?.toIntOrNull()
            val ct = value(2)?.toIntOrNull()?.takeIf { it in 1000..10000 }
            val rgbInt = value(3)?.toIntOrNull()
            val name = value(4)
            val model = value(5)

            val rgb = if (rgbInt != null && rgbInt > 0) {
                Triple((rgbInt shr 16) and 0xFF, (rgbInt shr 8) and 0xFF, rgbInt and 0xFF)
            } else null

            IdentityHit(
                ip = ip,
                protocol = protocol,
                port = TCP_PORT,
                // get_prop не отдаёт уникальный id — стабильную идентичность
                // даёт только SSDP-ответ (поле id) или MAC из ARP.
                nativeId = null,
                model = model,
                name = name,
                capabilities = listOf("on_off", "brightness", "color_temp", "rgb"),
                state = DeviceState(power = power, brightness = brightness, colorTempKelvin = ct, rgb = rgb),
                source = "yeelight-tcp"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSsdp(ip: String, raw: String): IdentityHit? {
        return try {
            val headers = raw.lineSequence()
                .mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
                }
                .toMap()

            val id = headers["id"]?.takeIf { it.isNotBlank() && it != "0x0000000000000000" }
            val model = headers["model"]?.takeIf { it.isNotBlank() }
            // Если нет ни id, ни model — это не Yeelight, а случайный SSDP-ответ
            if (id == null && model == null) return null
            if (!headers.containsKey("support") && !headers.containsKey("location") && model == null) return null

            val power = headers["power"]?.equals("on", ignoreCase = true)
            val brightness = headers["bright"]?.toIntOrNull()
            val ct = headers["ct"]?.toIntOrNull()?.takeIf { it in 1000..10000 }
            val rgbInt = headers["rgb"]?.toIntOrNull()
            val rgb = if (rgbInt != null && rgbInt > 0) {
                Triple((rgbInt shr 16) and 0xFF, (rgbInt shr 8) and 0xFF, rgbInt and 0xFF)
            } else null

            IdentityHit(
                ip = ip,
                protocol = protocol,
                port = TCP_PORT,
                nativeId = id?.lowercase(),
                model = model,
                firmware = headers["fw_ver"],
                name = headers["name"]?.takeIf { it.isNotBlank() },
                capabilities = listOf("on_off", "brightness", "color_temp", "rgb"),
                state = DeviceState(power = power, brightness = brightness, colorTempKelvin = ct, rgb = rgb),
                source = "yeelight-ssdp"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun merge(existing: IdentityHit?, fresh: IdentityHit): IdentityHit {
        if (existing == null) return fresh
        return fresh.copy(
            nativeId = fresh.nativeId ?: existing.nativeId,
            mac = fresh.mac ?: existing.mac,
            model = fresh.model ?: existing.model,
            firmware = fresh.firmware ?: existing.firmware,
            name = fresh.name ?: existing.name,
            state = fresh.state ?: existing.state
        )
    }
}
