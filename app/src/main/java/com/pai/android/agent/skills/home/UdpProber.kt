package com.pai.android.agent.skills.home

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress

/**
 * UDP/TCP обнаружение устройств в локальной сети.
 * - WiZ (UDP 38899): WiZ/Gauss лампы (через broadcast JSON)
 * - Yeelight (UDP 4321): Yeelight лампы
 * - MiIO (UDP 54321): Xiaomi устройства (увлажнитель, Roborock, розетки)
 * - MQTT (TCP 1883/8883): проверка на MQTT брокеры
 * - NetBIOS (UDP 137): MAC-адреса через Node Status Request
 */
object UdpProber {

    private const val TAG = "UdpProber"
    private const val TIMEOUT_MS = 2000

    data class ProbeResult(
        val protocol: String,
        val fingerprint: String,
        val mac: String? = null
    )

    fun probeAll(ips: Collection<String>): Map<String, ProbeResult> {
        val results = mutableMapOf<String, ProbeResult>()

        // 1. WiZ broadcast (UDP 38899)
        val wizDevices = discoverWizBroadcast()
        for ((ip, pr) in wizDevices) {
            results[ip] = pr
            Log.d(TAG, "WiZ found: $ip mac=${pr.mac}")
        }

        // 2. MiIO broadcast (UDP 54321) — Xiaomi умный дом
        val miioDevices = discoverMiioBroadcast()
        for ((ip, pr) in miioDevices) {
            if (ip !in results) {
                results[ip] = pr
                Log.d(TAG, "MiIO found: $ip mac=${pr.mac}")
            }
        }

        // 3. Yeelight multicast (UDP 4321)
        val yeelightDevices = discoverYeelightMulticast()
        for ((ip, pr) in yeelightDevices) {
            if (ip !in results) {
                results[ip] = pr
                Log.d(TAG, "Yeelight found: $ip mac=${pr.mac}")
            }
        }

        // 4. MQTT probe (TCP 1883/8883)
        for (ip in ips) {
            if (ip in results) continue
            val mqttFp = checkMqtt(ip)
            if (mqttFp != null) results[ip] = mqttFp
        }

        Log.d(TAG, "probeAll: found ${results.size} devices")
        return results
    }

    /**
     * WiZ broadcast discovery на порт 38899.
     * Все WiZ лампы в сети отвечают на один broadcast.
     */
    private fun discoverWizBroadcast(): Map<String, ProbeResult> {
        val result = mutableMapOf<String, ProbeResult>()
        try {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1500

            val json = "{\"method\":\"registration\",\"params\":{\"phoneMac\":\"AAAAAAAAAAAA\",\"register\":false,\"phoneIp\":\"1.2.3.4\",\"id\":\"1\"}}"
            val data = json.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 38899))

            val buf = ByteArray(2048)
            val endTime = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < endTime) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val ip = packet.address.hostAddress ?: ""
                    Log.d(TAG, "WiZ response from $ip: ${response.take(200)}")
                    val mac = extractWizMac(response)
                    result[ip] = ProbeResult("wiz", "wiz_light", mac = mac)
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
            socket.close()
            Log.d(TAG, "WiZ broadcast: found ${result.size} lamps")
        } catch (e: Exception) {
            Log.w(TAG, "WiZ error: ${e.message}")
        }
        return result
    }

    private fun extractWizMac(json: String): String? {
        try {
            val obj = org.json.JSONObject(json)
            val res = obj.optJSONObject("result")
            val mac = res?.optString("mac", null)
            if (mac != null && mac.length == 12 && mac != "000000000000") {
                return mac.chunked(2).joinToString(":").uppercase()
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * MiIO broadcast discovery (UDP 54321).
     * Xiaomi протокол: отправляем "hello" broadcast, устройства отвечают с MAC, model, ip.
     *
     * Типичный ответ от Roborock/Xiaomi увлажнителя:
     * {"result":{"life":1234,"mac":"aa:bb:cc:dd:ee:ff","model":"roborock.vacuum.s5e","fw_ver":"3.5.8","ip":"192.168.0.50"}}
     */
    private fun discoverMiioBroadcast(): Map<String, ProbeResult> {
        val result = mutableMapOf<String, ProbeResult>()
        try {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 2000

            // MiIO hello пакет: {"id":1,"method":"_sync.get_sysinfo"}
            val helloJson = """{"id":$SYSTEM_OFFSET_ID,"method":"_sync.get_sysinfo"}""".trimIndent()
            val data = helloJson.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 54321))

            val buf = ByteArray(2048)
            val endTime = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < endTime) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val ip = packet.address.hostAddress ?: ""
                    Log.d(TAG, "MiIO response from $ip: ${response.take(300)}")

                    val parsed = parseMiioResponse(response)
                    if (parsed != null) {
                        result[ip] = parsed
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
            socket.close()
            Log.d(TAG, "MiIO broadcast: found ${result.size} devices")
        } catch (e: Exception) {
            Log.w(TAG, "MiIO error: ${e.message}")
        }
        return result
    }

    /**
     * Парсит MiIO ответ: извлекает MAC, определяет тип устройства по model.
     */
    private fun parseMiioResponse(json: String): ProbeResult? {
        return try {
            val obj = JSONObject(json)
            val resultObj = obj.optJSONObject("result") ?: return null
            val mac = resultObj.optString("mac", "")
            val model = resultObj.optString("model", "")
            val ip = resultObj.optString("ip", "")

            if (mac.isBlank() || mac == "00:00:00:00:00:00") return null

            // Определяем тип по model
            val fingerprint = guessMiioType(model)
            val macStr = normalizeMiioMac(mac)

            Log.d(TAG, "MiIO: $model @ $ip -> $macStr")
            ProbeResult(protocol = "miio", fingerprint = fingerprint, mac = macStr)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Определяет тип Xiaomi устройства по model.
     */
    private fun guessMiioType(model: String): String {
        val lower = model.lowercase()
        return when {
            lower.contains("vacuum") || lower.contains("roborock") || lower.contains("dreame") -> "roborock"
            lower.contains("humidifier") || lower.contains("zhimi.humidifier") || lower.contains("deerma.humidifier") -> "xiaomi_humidifier"
            lower.contains("plug") || lower.contains("socket") || lower.contains("zimi.plug") -> "xiaomi_plug"
            lower.contains("light") || lower.contains("yeelink") || lower.contains("philips") -> "xiaomi_light"
            lower.contains("gateway") || lower.contains("lumi.gateway") || lower.contains("xiaomi.gateway") -> "xiaomi_gateway"
            lower.contains("airpurifier") || lower.contains("zhimi.airpurifier") -> "xiaomi_airpurifier"
            lower.contains("aircondition") || lower.contains("airconditioner") || lower.contains("ktkl.airconditioner") -> "xiaomi_ac"
            lower.contains("fan") || lower.contains("zhimi.fan") || lower.contains("dmaker.fan") -> "xiaomi_fan"
            lower.contains("tv") || lower.contains("miot") || lower.contains("xiaomi.tv") -> "xiaomi_tv"
            lower.contains("camera") || lower.contains("chuangmi.camera") || lower.contains("mijia.camera") -> "xiaomi_camera"
            lower.contains("switch") || lower.contains("lumi.ctrl_neutral") || lower.contains("lumi.ctrl_ln") -> "xiaomi_switch"
            lower.contains("pedometer") || lower.contains("mibarscale") || lower.contains("huami") -> "xiaomi_misc"
            lower.contains("speaker") || lower.contains("xiaomi.wifi") || lower.contains("l09g") -> "xiaomi_speaker"
            else -> "xiaomi_unknown"
        }
    }

    private fun normalizeMiioMac(mac: String): String {
        val cleaned = mac.lowercase().replace("-", ":").replace(" ", "")
        return if (cleaned.length == 17) cleaned
        else if (cleaned.length == 12) cleaned.chunked(2).joinToString(":")
        else cleaned
    }

    /** Счётчик ID для MiIO запросов (чтобы id был уникальным). */
    private var miioIdCounter: Int = (System.currentTimeMillis() % 10000).toInt()
    private val SYSTEM_OFFSET_ID: Long get() {
        miioIdCounter = (miioIdCounter + 1) % 10000
        return 10000L + miioIdCounter
    }

    /**
     * Yeelight multicast discovery (UDP 4321).
     * Отправляем M-SEARCH, Yeelight лампы отвечают с Location, который ведёт на HTTP API.
     * Из HTTP API можно получить MAC.
     *
     * Yeelight не отдают MAC в самом M-SEARCH ответе, поэтому после получения Location
     * делаем HTTP GET по Location и парсим MAC из XML или JSON описания.
     */
    private fun discoverYeelightMulticast(): Map<String, ProbeResult> {
        val result = mutableMapOf<String, ProbeResult>()
        val socket = DatagramSocket()

        try {
            socket.broadcast = true
            socket.soTimeout = 1500

            val searchMsg = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1982\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "ST: wifi_bulb\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)

            socket.send(DatagramPacket(searchMsg, searchMsg.size, InetAddress.getByName("239.255.255.250"), 4321))

            val buf = ByteArray(2048)
            val endTime = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < endTime) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val ip = packet.address.hostAddress ?: ""
                    Log.d(TAG, "Yeelight response from $ip: ${response.take(200)}")

                    // Извлекаем Location (HTTP API устройства)
                    val location = extractLocation(response)
                    if (location != null) {
                        // Пробуем получить информацию через HTTP API (порт 55443)
                        val mac = fetchYeelightMac(ip, location)
                        result[ip] = ProbeResult(
                            protocol = "yeelight",
                            fingerprint = "yeelight",
                            mac = mac
                        )
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Yeelight error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }

        Log.d(TAG, "Yeelight discovery: found ${result.size} lamps")
        return result
    }

    /**
     * Пробует получить MAC Yeelight лампы через HTTP API.
     * Yeelight HTTP API (порт 55443): POST /...
     */
    private fun fetchYeelightMac(ip: String, location: String): String? {
        // Сначала пробуем Yeelight HTTP API (порт 55443)
        try {
            val url = java.net.URL("http://$ip:55443/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                // Ищем MAC в JSON или HTML ответе
                val macPattern = Regex("""([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}""")
                val macMatch = macPattern.find(body)
                if (macMatch != null) {
                    return macMatch.value.lowercase()
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Извлекает Location из HTTP ответа.
     */
    private fun extractLocation(response: String): String? {
        val match = Regex("LOCATION:\\s*(.*)", RegexOption.IGNORE_CASE).find(response)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    /**
     * NetBIOS probe (UDP 137): запрос статуса узла.
     * Последние 6 байт ответа = MAC-адрес устройства.
     * Работает для Windows, Samba, некоторых ТВ и Linux.
     */
    fun discoverNetbios(ip: String): String? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = 1500

            // NetBIOS Node Status Request (RFC 1002)
            val request = byteArrayOf(
                0x00, 0x00, // Transaction ID
                0x00, 0x00, // Flags
                0x00, 0x01, // Questions: 1
                0x00, 0x00, // Answer RRs
                0x00, 0x00, // Authority RRs
                0x00, 0x00, // Additional RRs
                0x20,       // Name length: 32
                // Name: *<00><00> (redirect to local)
                0x43, 0x4B, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x00,       // Name type: NBSTAT
                0x00, 0x21, // Type: NBSTAT (0x21)
                0x00, 0x01  // Class: IN
            )

            socket.send(DatagramPacket(request, request.size, InetAddress.getByName(ip), 137))

            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            socket.close()

            // Последние 6 байт ответа = MAC
            val data = packet.data
            val len = packet.length
            if (len >= 6) {
                val macBytes = data.sliceArray((len - 6) until len)
                val mac = macBytes.joinToString(":") { "%02x".format(it) }.uppercase()
                Log.d(TAG, "NetBIOS MAC from $ip: $mac")
                return mac
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * MQTT probe: подключается к TCP порту 1883/8883.
     */
    private fun checkMqtt(ip: String): ProbeResult? {
        for (port in listOf(1883, 8883)) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 1000)

                // MQTT CONNECT packet
                val connect = byteArrayOf(
                    0x10, 0x1E, // CONNECT, remaining length=30
                    0x00, 0x04, "MQTT".toByteArray(Charsets.UTF_8)[0], "MQTT".toByteArray(Charsets.UTF_8)[1],
                    "MQTT".toByteArray(Charsets.UTF_8)[2], "MQTT".toByteArray(Charsets.UTF_8)[3],
                    0x04,       // protocol level 3.1.1
                    0x02,       // clean session
                    0x00, 0x3C, // keepalive 60s
                    0x00, 0x0A, "PaiAndroid1".toByteArray(Charsets.UTF_8)[0],
                    'P'.code.toByte(), 'a'.code.toByte(), 'i'.code.toByte(),
                    'A'.code.toByte(), 'n'.code.toByte(), 'd'.code.toByte(),
                    'r'.code.toByte(), 'o'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte()
                )

                socket.getOutputStream().write(connect)
                val buf = ByteArray(4)
                val len = socket.getInputStream().read(buf)
                socket.close()

                if (len >= 4 && buf[0] == 0x20.toByte()) {
                    val proto = if (port == 8883) "mqtts" else "mqtt"
                    Log.d(TAG, "$ip:$port -> MQTT device")
                    return ProbeResult(proto, "mqtt_device")
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
