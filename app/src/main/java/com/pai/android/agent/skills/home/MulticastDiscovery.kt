package com.pai.android.agent.skills.home

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Обнаружение устройств в локальной сети через широковещательные протоколы:
 * - mDNS (Multicast DNS, порт 5353) — Bonjour/Avahi устройства
 * - SSDP (Simple Service Discovery Protocol, порт 1900) — UPnP устройства
 *
 * Позволяет найти даже те устройства, которые не отвечают на ping и не имеют открытых TCP-портов.
 */
object MulticastDiscovery {

    private const val TAG = "MulticastDiscovery"
    private const val TIMEOUT_MS = 2000L
    private const val MDNS_ADDR = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /** Известные OUI (первые 3 байта MAC) для верификации UPnP MAC. */
    private val KNOWN_MAC_PREFIXES = setOf(
        "F4F2C6", "A44CC8", "08EA40", "B0C745", "FCB4E6", "14EBB6",
        "D8FEE3", "4801A5", "84F3EB", "2496CA", "18CE94", "A8DB03",
        "18FE34", "001E4E", "AC84C6",
        "001C7D", "106F3F",
        "080069", "001638", "F04F7C", "8878A4",
        "00A0C9", "B0E7E1", "001CB8", "201A06",
        "5CC6D0", "9CE374", "100D32", "F04A51",
        "48E7DA", "DC0B34",
        "806C1B", "0482C9",
        "AC63BE", "006A7C", "C8D719",
        "00263A",
        "90FA3A",
        "803AD8", "B0AECE", "AC5FFE",
        "18FEA", "FCF5C4", "689C5E",
        "B8D742", "E02F6D",
        "B827EB", "DCA632", "E45F01",
        "0026CB",
        "9C2A70", "5C2C45",
        "F83E32",
        "4C5E0C", "D4CA6D", "E4C864",
        "D8D38C",
        "B8E937", "000E58", "5CF9DD",
        "F0163C",
        "E04B7B",
        "00E04C",
        "8C0CA8", "3C15C2",
        "C4A81D", "F04DA2",
        "001B1C", "0C37DC",
        "C0A0B", "100D7F"
    )

    /** Результат обнаружения */
    data class DiscoveredDevice(
        val ip: String,
        val mac: String? = null,
        val hostname: String? = null,
        val serviceType: String? = null,   // mDNS service type
        val friendlyName: String? = null,   // понятное имя устройства
        val source: String = ""             // "mdns", "ssdp"
    )

    /**
     * Запускает mDNS + SSDP обнаружение.
     * @param timeoutMs общий таймаут ожидания ответов
     * @return список обнаруженных устройств
     */
    fun discover(timeoutMs: Long = TIMEOUT_MS): List<DiscoveredDevice> {
        val results = mutableMapOf<String, DiscoveredDevice>()  // key = IP
        val threads = mutableListOf<Thread>()

        // mDNS
        threads.add(Thread {
            try {
                val mdnsDevices = discoverMdns(timeoutMs)
                synchronized(results) {
                    for (d in mdnsDevices) {
                        // Если IP уже есть — заменяем только если новое лучше (есть serviceType)
                        val existing = results[d.ip]
                        if (existing == null || (d.serviceType != null && existing.serviceType == null)) {
                            results[d.ip] = d
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mDNS error: ${e.message}")
            }
        })

        // SSDP
        threads.add(Thread {
            try {
                val ssdpDevices = discoverSsdp(timeoutMs)
                synchronized(results) {
                    for (d in ssdpDevices) {
                        // Если IP уже есть — заменяем только если новое лучше (есть serviceType)
                        val existing = results[d.ip]
                        if (existing == null || (d.serviceType != null && existing.serviceType == null)) {
                            results[d.ip] = d
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSDP error: ${e.message}")
            }
        })

        threads.forEach { it.start() }
        threads.forEach { try { it.join(timeoutMs + 2000) } catch (_: Exception) {} }

        Log.d(TAG, "discover: found ${results.size} devices via mDNS/SSDP")
        return results.values.toList()
    }

    /**
     * mDNS discovery: отправляет PTR запрос _services._dns-sd._udp.local
     * и слушает ответы от всех mDNS-совместимых устройств.
     */
    private fun discoverMdns(timeoutMs: Long): List<DiscoveredDevice> {
        val result = mutableListOf<DiscoveredDevice>()
        val socket = DatagramSocket(null)
        
        try {
            socket.broadcast = true
            socket.reuseAddress = true
            socket.soTimeout = timeoutMs.toInt()
            socket.bind(InetSocketAddress(MDNS_PORT))
            
            // Присоединяемся к mDNS multicast группе на wlan0
            val wifiIface = findWifiInterface()
            if (wifiIface != null) {
                val mcastAddr = InetAddress.getByName(MDNS_ADDR)
                val membership = java.net.MulticastSocket(
                    InetSocketAddress(mcastAddr, MDNS_PORT)
                ).apply {
                    setInterface(wifiIface)
                    joinGroup(InetAddress.getByName(MDNS_ADDR))
                    soTimeout = timeoutMs.toInt()
                }
                
                // Запрос PTR для всех сервисов
                val query = buildMdnsQuery()
                membership.send(DatagramPacket(query, query.size, InetAddress.getByName(MDNS_ADDR), MDNS_PORT))
                
                // Слушаем ответы
                val buf = ByteArray(4096)
                while (true) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        membership.receive(packet)
                        val data = packet.data.copyOf(packet.length)
                        val device = parseMdnsResponse(data, packet.address.hostAddress ?: "")
                        if (device != null) {
                            result.add(device)
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                }
                
                try { membership.leaveGroup(InetAddress.getByName(MDNS_ADDR)) } catch (_: Exception) {}
                membership.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discover error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
        
        return result.groupBy { it.ip }.map { (_, devices) ->
            devices.maxByOrNull { d ->
                when (d.serviceType) {
                    null -> 0
                    "router" -> 1
                    "ip_camera" -> 2
                    "smart_light" -> 3
                    "smart_tv" -> 4
                    "yandex_station" -> 5
                    else -> 6
                }
            } ?: devices.first()
        }
    }

    /**
     * SSDP discovery: отправляет M-SEARCH запрос
     * и слушает ответы от всех UPnP устройств.
     */
    private fun discoverSsdp(timeoutMs: Long): List<DiscoveredDevice> {
        val result = mutableListOf<DiscoveredDevice>()
        val socket = DatagramSocket()
        
        try {
            socket.broadcast = true
            socket.soTimeout = timeoutMs.toInt()
            
            // M-SEARCH запрос — ищем все UPnP устройства
            val searchMsg = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: ssdp:all\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
            
            val ssdpAddr = InetAddress.getByName(SSDP_ADDR)
            socket.send(DatagramPacket(searchMsg, searchMsg.size, ssdpAddr, SSDP_PORT))
            
            // Слушаем ответы
            val buf = ByteArray(4096)
            while (true) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val ip = packet.address.hostAddress ?: ""
                    val device = parseSsdpResponse(response, ip)
                    if (device != null) {
                        result.add(device)
                    }
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP discover error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
        
        return result.groupBy { it.ip }.map { (_, devices) ->
            devices.maxByOrNull { d ->
                when (d.serviceType) {
                    null -> 0
                    "router" -> 1
                    "ip_camera" -> 2
                    "smart_light" -> 3
                    "smart_tv" -> 4
                    "yandex_station" -> 5
                    else -> 6
                }
            } ?: devices.first()
        }
    }

    /**
     * Строит mDNS PTR запрос для поиска всех сервисов.
     */
    private fun buildMdnsQuery(): ByteArray {
        val query = byteArrayOf(
            0x00, 0x00, // Transaction ID
            0x00, 0x00, // Flags: standard query
            0x00, 0x01, // Questions: 1
            0x00, 0x00, // Answer RRs
            0x00, 0x00, // Authority RRs
            0x00, 0x00  // Additional RRs
        )
        // _services._dns-sd._udp.local
        val nameParts = "_services._dns-sd._udp.local".split(".")
        val nameBytes = mutableListOf<Byte>()
        for (part in nameParts) {
            nameBytes.add(part.length.toByte())
            nameBytes.addAll(part.toByteArray(Charsets.UTF_8).toList())
        }
        nameBytes.add(0x00) // null terminator

        val question = nameBytes.toByteArray() + byteArrayOf(
            0x00, 0x0C, // QTYPE: PTR
            0x00, 0x01  // QCLASS: IN
        )

        return query + question
    }

    /**
     * Парсит mDNS ответ. Извлекает hostname, serviceType, MAC.
     *
     * MAC может быть:
     * - В hostname вида: esp-123abc.local (ESP32/ESP8266, первые 6 символов после esp- = последние 3 байта MAC)
     * - В hostname вида: Nodemcu-XXXXXX
     * - В TXT-записях (некоторые устройства кладут MAC туда)
     */
    private fun parseMdnsResponse(data: ByteArray, sourceIp: String): DiscoveredDevice? {
        try {
            val text = String(data, Charsets.UTF_8)
            // Ищем PTR записи
            val ptrMatch = Regex("\\x0C\\x00\\x0C\\x00\\x01").find(text)
            if (ptrMatch == null) return null

            // Извлекаем имя устройства из ответа
            val parts = text.split(".")
            var name: String? = null
            var serviceType: String? = null
            for (part in parts) {
                if (part.startsWith(" ")) continue
                if (part.startsWith("_")) {
                    if (part != "_services" && part != "_dns-sd" && part != "_udp" && part != "_tcp" && part != "_local") {
                        serviceType = part
                    }
                } else if (part.length > 3 && part != "_services" && part != "_dns-sd" && part != "_udp" && part != "local") {
                    name = part
                }
            }

            // Извлекаем MAC из hostname
            val mac = extractMacFromMdnsHostname(name ?: "")

            // Определяем тип устройства по serviceType
            val deviceType = guessMdnsType(serviceType)

            return DiscoveredDevice(
                ip = sourceIp,
                mac = mac,
                hostname = name,
                serviceType = deviceType,
                friendlyName = name,
                source = "mdns"
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Извлекает MAC-адрес из mDNS hostname.
     *
     * Форматы:
     * - ESP32: esp-123abc.local или ESP_XXXXXX (иногда = последние 3 байта или полный MAC)
     * - ESPHome: esp-XXXXXX.local — но MAC не всегда в hostname
     * - Tasmota: tasmota-XXXX-XXXX.local
     * - Shelly: shelly1-XXXXXX
     */
    private fun extractMacFromMdnsHostname(hostname: String): String? {
        val lower = hostname.lowercase()

        // ESP32/ESP8266: esp-XXXXXX или ESP_XXXXXX — часто 6 hex символов = последние 3 байта
        val espMatch = Regex("""(?:esp|nodemcu)[-_]([0-9a-f]{6})""").find(lower)
        if (espMatch != null) {
            val suffix = espMatch.groupValues[1]
            // Это только последние 3 байта MAC. Не можем восстановить полный MAC.
            return null // Недостаточно данных
        }

        // Полный MAC в hostname: некоторые ESPHome/ESP32 кладут полный MAC
        val macPattern = Regex("""((?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})""")
        val macMatch = macPattern.find(lower)
        if (macMatch != null) {
            return macMatch.value.lowercase()
        }

        // 12 hex-символов в hostname (возможный полный MAC)
        val hex12 = Regex("""(?:[0-9a-f]{12})""")
        val hexMatch = hex12.find(lower)
        if (hexMatch != null) {
            val hex = hexMatch.value
            // Проверяем, что это не часть длинного имени, а именно MAC (проверим по OUI)
            if (hex.length == 12 && !lower.contains("device") && !lower.contains("serial")) {
                return hex.chunked(2).joinToString(":")
            }
        }

        return null
    }

    /**
     * Определяет тип устройства по mDNS service type.
     */
    private fun guessMdnsType(serviceType: String?): String? {
        if (serviceType == null) return null
        val lower = serviceType.lowercase()
        return when {
            lower.contains("_esphomelib") || lower.contains("_esphome") -> "esphome"
            lower.contains("_http") || lower.contains("_web") -> "web_server"
            lower.contains("_printer") || lower.contains("_ipp") || lower.contains("_pdl-datastream") -> "printer"
            lower.contains("_airplay") || lower.contains("_raop") || lower.contains("_apple-tv") -> "apple_tv"
            lower.contains("_homekit") -> "homekit"
            lower.contains("_googlecast") || lower.contains("_cast") -> "chromecast"
            lower.contains("_spotify") || lower.contains("_spotify-connect") -> "spotify"
            lower.contains("_sonos") -> "sonos"
            lower.contains("_hap") -> "homekit_accessory"
            lower.contains("_ssh") -> "ssh_server"
            lower.contains("_smb") || lower.contains("_afpovertcp") || lower.contains("_netatalk") -> "nas"
            lower.contains("_sftp") || lower.contains("_ftp") -> "ftp_server"
            lower.contains("_mqtt") -> "mqtt_broker"
            lower.contains("_tasmota") -> "tasmota"
            lower.contains("_shelly") -> "shelly"
            lower.contains("_zigbee") || lower.contains("_zha") -> "zigbee_gateway"
            lower.contains("_miio") || lower.contains("_xiaomi") -> "xiaomi"
            else -> null
        }
    }

    /**
     * Парсит SSDP ответ. Извлекает Location, USN, ST и MAC-адрес из USN/UUID.
     *
     * Многие UPnP устройства включают MAC в UUID:
     * uuid:XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXX
     *        |--oui--|    |--nic--|
     * Последние 12 hex символов = MAC (6 байт)
     *
     * Также TP-Link, D-Link, ASUS часто пишут MAC в USN:
     * uuid:XXXXXXXX-XXXX-XXXX-XXXXXXXXXX::urn:schemas-upnp-org:device:InternetGatewayDevice:1
     *
     * Xiaomi/Philips Hue: MAC в serialNumber XML описания (Location).
     */
    private fun parseSsdpResponse(response: String, ip: String): DiscoveredDevice? {
        if (!response.contains("HTTP/1.1 200 OK") && !response.contains("NOTIFY")) return null

        val server = extractHeader(response, "SERVER")
        val location = extractHeader(response, "LOCATION")
        val usn = extractHeader(response, "USN")
        val st = extractHeader(response, "ST")
        val name = extractHeader(response, "FriendlyName") ?: extractHeader(response, "friendlyName")

        // Определяем тип устройства по ST или SERVER
        val serviceType = guessSsdpType(st ?: server ?: "")

        // Извлекаем MAC из USN/UUID
        val mac = extractMacFromUpnpUsn(usn ?: "")
        
        Log.d(TAG, "SSDP from $ip: server=$server st=$st type=$serviceType name=$name mac=$mac")

        return DiscoveredDevice(
            ip = ip,
            mac = mac,
            hostname = name ?: server,
            serviceType = serviceType,
            friendlyName = name,
            source = "ssdp"
        )
    }

    /**
     * Извлекает MAC-адрес из UPnP USN (UUID).
     *
     * Форматы USN:
     * uuid:XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXX::urn:...
     * uuid:XXXXXXXX-XXXX-XXXX-XXXXXXXXXX::urn:...
     *
     * MAC кодируется в последнем сегменте UUID:
     * - Полный UUID: последние 12 hex символов = MAC
     * - Сокращённый: последние 12 hex символов = MAC
     *
     * Некоторые устройства (TP-Link) используют MAC как имя:
     * uuid:14ebb65883e4::urn:...
     */
    private fun extractMacFromUpnpUsn(usn: String): String? {
        val lower = usn.lowercase()

        // Проверка: похож ли hex на реальный MAC?
        // У реального MAC: LSB первого байта = 0 (unicast), первые 3 байта — зарегистрированный OUI
        fun looksLikeMac(mac: String): Boolean {
            if (mac == "00:00:00:00:00:00" || mac == "ff:ff:ff:ff:ff:ff") return false
            val firstByte = mac.take(2).toIntOrNull(16) ?: return false
            // LSB = 0 → unicast (реальный MAC). Если LSB = 1 → multicast/случайный UUID
            if ((firstByte and 0x01) != 0) return false
            // Проверяем первые 3 байта по базе OUI (только если они есть)
            val oui = mac.take(8).uppercase().replace(":", "")
            if (oui in KNOWN_MAC_PREFIXES) return true
            // Неизвестный OUI — всё равно пропускаем (может быть малоизвестный вендор),
            // но если первые 3 байта = 00:00:00, это фейк
            if (mac.startsWith("00:00:00")) return false
            return true
        }

        // Только короткий формат: uuid:XXXXXXXXXXXX::urn:...
        // (полные UUID в формате XXXX-XXXX-XXXX-XXXX-XXXXXXXXXX редко содержат MAC)
        // Некоторые TP-Link, D-Link, Keenetic реально пишут MAC в этом поле:
        // uuid:14ebb65883e4::urn:schemas-upnp-org:device:InternetGatewayDevice:1
        val shortUuidMatch = Regex("""uuid:([0-9a-f]{12})::""").find(lower)
        if (shortUuidMatch != null) {
            val hex = shortUuidMatch.groupValues[1]
            val mac = hex.chunked(2).joinToString(":")
            if (looksLikeMac(mac)) {
                Log.d(TAG, "SSDP MAC from short UUID: $mac")
                return mac
            }
        }

        return null
    }

    private fun extractHeader(response: String, header: String): String? {
        val regex = Regex("$header:\\s*(.+)\\r?\\n", RegexOption.IGNORE_CASE)
        return regex.find(response)?.groupValues?.get(1)?.trim()
    }

    private fun guessSsdpType(st: String): String? {
        val lower = st.lowercase()
        return when {
            lower.contains("mediarenderer") || lower.contains("tv") || lower.contains("sony") -> "smart_tv"
            lower.contains("yamaha") || lower.contains("denon") || lower.contains("onkyo") -> "audio_receiver"
            lower.contains("printer") -> "printer"
            lower.contains("wlanap") || lower.contains("router") -> "router"
            lower.contains("camera") -> "ip_camera"
            lower.contains("nas") || lower.contains("storage") -> "nas"
            lower.contains("gateway") -> "gateway"
            lower.contains("light") -> "smart_light"
            else -> null
        }
    }

    /**
     * Находит Wi-Fi сетевой интерфейс.
     */
    private fun findWifiInterface(): InetAddress? {
        return try {
            val interfaces = java.util.Collections.list(
                java.net.NetworkInterface.getNetworkInterfaces()
            )
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback || ni.name.contains("rmnet") || ni.name.contains("p2p")) continue
                val addrs = java.util.Collections.list(ni.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }
}
