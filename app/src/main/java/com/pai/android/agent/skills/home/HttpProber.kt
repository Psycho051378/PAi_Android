package com.pai.android.agent.skills.home

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP-опрос устройств: GET /, парсинг title + Server + fingerprint.
 */
class HttpProber(
    private val client: OkHttpClient
) {
    private val TAG = "HttpProber"
    
    /** OkHttpClient без проверки SSL-сертификатов (для самоподписанных) */
    private val trustAllClient: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        client.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    data class HttpInfo(
        val title: String?,
        val server: String?,
        val fingerprint: String?,       // идентифицированный тип устройства
        val mac: String? = null         // MAC из UPnP description.xml
    )

    /** Порты, по которым стоит делать HTTP/HTTPS probe */
    private val PROBE_PORTS = setOf(80, 443, 8080, 8443, 55443, 81, 88, 888, 3000, 5000, 7000, 9090)

    /**
     * Опрашивает все IP с открытыми портами.
     */
    fun probeAll(openPorts: Map<String, List<Int>>): Map<String, HttpInfo> {
        val result = mutableMapOf<String, HttpInfo>()
        for ((ip, ports) in openPorts) {
            var identified = false
            for (port in ports) {
                // Специальная обработка для известных не-HTTP портов
                if (port == 55443) {
                    result[ip] = HttpInfo(title = null, server = null, fingerprint = "yeelight")
                    identified = true
                    Log.d(TAG, "$ip:$port -> yeelight (by port)")
                    break
                }
                if (port == 554) {
                    result[ip] = HttpInfo(title = null, server = null, fingerprint = "ip_camera")
                    identified = true
                    Log.d(TAG, "$ip:$port -> ip_camera (by port 554 RTSP)")
                    break
                }
                if (port in PROBE_PORTS) {
                    val info = probe(ip, port)
                    if (info != null) {
                        result[ip] = info
                        identified = true
                        break
                    }
                }
            }
            if (!identified) {
                // Fallback: если порт 55443 был, но мы его не обработали — всё равно Yeelight
                if (ports.contains(55443)) {
                    result[ip] = HttpInfo(title = null, server = null, fingerprint = "yeelight")
                }
            }
        }
        return result
    }

    /**
     * Делает HTTP GET запрос на {ip}:{port}/ и парсит ответ.
     */
    private fun probe(ip: String, port: Int): HttpInfo? {
        val isSecure = port == 443 || port == 8443 || port == 8883
        val httpClient = if (isSecure) trustAllClient else client
        
        return try {
            val url = if (isSecure) "https://$ip:$port/" else "http://$ip:$port/"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "PaiAndroid/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val server = response.header("Server")
            val title = extractTitle(body)
            val mac = extractMacFromUpnp(body)

            val fingerprint = fingerprintDevice(ip, port, server, title, body)

            response.close()

            Log.d(TAG, "$ip:$port -> title=$title server=$server fingerprint=$fingerprint mac=$mac")
            HttpInfo(title = title, server = server, fingerprint = fingerprint, mac = mac)
        } catch (e: Exception) {
            Log.d(TAG, "$ip:$port -> error: ${e.message?.take(80)}")
            null
        }
    }

    /**
     * Извлекает MAC-адрес из UPnP description.xml.
     * Многие устройства (роутеры, ТВ) хранят MAC в <serialNumber> или <uuid>.
     */
    private fun extractMacFromUpnp(body: String): String? {
        if (!body.contains("xml") && !body.contains("device")) return null
        
        // 1. Прямой поиск MAC-подобной строки в serialNumber
        val serialRegex = Regex("<serialNumber[^>]*>(.*?)</serialNumber>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val serialMatch = serialRegex.find(body)
        if (serialMatch != null) {
            val serial = serialMatch.groupValues[1].trim()
            // MAC часто выглядит как: 112233445566 или AA:BB:CC:DD:EE:FF
            val macRegex = Regex("([0-9A-Fa-f]{2}[:-]?){6}")
            val macMatch = macRegex.find(serial)
            if (macMatch != null) {
                val mac = macMatch.value
                if (mac.count { it == ':' || it == '-' } >= 5) return mac.uppercase()
                // Формат без разделителей: AABBCCDDEEFF → AA:BB:CC:DD:EE:FF
                if (mac.length == 12 && mac.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
                    return mac.chunked(2).joinToString(":").uppercase()
                }
            }
        }
        
        // 2. Прямой поиск MAC в UUID (первые 6 октетов часто = MAC)
        val uuidRegex = Regex("<UDN[^>]*>(.*?)</UDN>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val uuidMatch = uuidRegex.find(body)
        if (uuidMatch != null) {
            val uuid = uuidMatch.groupValues[1].trim()
            val macRegex = Regex("([0-9A-Fa-f]{2}[:-]?){6}")
            val macMatch = macRegex.find(uuid)
            if (macMatch != null) return macMatch.value.uppercase()
        }
        
        // 3. Любой MAC в XML
        val anyMac = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}").find(body)
        return anyMac?.value?.uppercase()
    }

    /**
     * Извлекает <title> из HTML.
     */
    private fun extractTitle(html: String): String? {
        val regex = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(html)?.groupValues?.get(1)?.trim()?.take(100)
    }

    /**
     * Идентифицирует устройство по сигнатурам.
     */
    private fun fingerprintDevice(
        ip: String,
        port: Int,
        server: String?,
        title: String?,
        body: String
    ): String? {
        val lowerTitle = title?.lowercase() ?: ""
        val lowerServer = server?.lowercase() ?: ""
        val lowerBody = body.lowercase()

        // Tasmota
        if (lowerBody.contains("tasmota") || lowerServer.contains("tasmota"))
            return "sonoff_tasmota"
        if (lowerBody.contains("shelly") || lowerServer.contains("shelly"))
            return "shelly"

        // Sonoff DIY
        if (lowerBody.contains("sonoff") || lowerTitle.contains("sonoff"))
            return "sonoff"

        // ESPHome
        if (lowerBody.contains("esphome") || lowerServer.contains("esphome"))
            return "esphome"
        if (lowerTitle.contains("esphome"))
            return "esphome"

        // Home Assistant
        if (lowerTitle.contains("home assistant"))
            return "home_assistant"

        // Yeelight (порт 55443) — специальный UDP/TCP протокол
        if (port == 55443) {
            if (lowerBody.contains("yeelight") || lowerServer?.contains("yeelight") == true)
                return "yeelight"
            // Если порт открыт, но HTTP не отвечает — всё равно Yeelight
            return "yeelight"
        }

        // WiZ — порт 38999 (UDP), но могут быть и на других
        if (lowerBody.contains("wiz") || lowerTitle.contains("wiz connected"))
            return "wiz_light"

        // TP-Link (Kasa, Archer, Deco)
        if (lowerTitle.contains("tplink") || lowerTitle.contains("tp-link") ||
            lowerTitle.contains("archer") || lowerTitle.contains("deco") ||
            lowerServer?.contains("tp-link") == true || lowerServer?.contains("tplink") == true)
            return "tp_link"
        if (lowerBody.contains("tplink") || lowerBody.contains("tp-link"))
            return "tp_link"
        // TP-Link с пустым title, но порты 80/443 открыты — вероятно роутер
        if (title == null && (port == 80 || port == 443) && ip.endsWith(".1"))
            return "router"

        // Роутеры
        if (lowerTitle.contains("router") || lowerTitle.contains("шлюз") ||
            lowerTitle.contains("маршрутизатор") || lowerTitle.contains("wi-fi"))
            return "router"
        if (lowerServer?.contains("httpd") == true && lowerTitle?.contains("dir") == true)
            return "dlink_router"
        if (lowerTitle.contains("keenetic"))
            return "keenetic_router"

        // IP-камеры
        if (lowerTitle.contains("camera") || lowerTitle.contains("камера") ||
            lowerBody.contains("hikvision") || lowerServer?.contains("hikvision") == true)
            return "ip_camera"
        if (lowerBody.contains("dahua") || lowerServer?.contains("dahua") == true)
            return "ip_camera"

        // Xiaomi
        if (lowerBody.contains("xiaomi") || lowerServer?.contains("xiaomi") == true)
            return "xiaomi"

        // Philips Hue
        if (lowerServer?.contains("hue") == true || lowerTitle.contains("philips hue"))
            return "philips_hue"

        // BroadLink RM
        if (lowerServer?.contains("rm") == true && lowerBody.contains("broadlink"))
            return "broadlink"

        // Plex
        if (lowerTitle.contains("plex"))
            return "plex"

        // Apple
        if (lowerServer?.contains("apple") == true)
            return "apple"

        // Ubiquiti
        if (lowerBody.contains("ubiquiti") || lowerServer?.contains("ubnt") == true)
            return "ubiquiti"

        // Yandex Smart Home / Station
        if (lowerBody.contains("yandex") && (lowerBody.contains("station") || lowerBody.contains("alice")))
            return "yandex_station"
        if (lowerServer?.contains("yandex") == true || lowerTitle?.contains("яндекс") == true)
            return "yandex_station"
        // Я.Станции могут давать пустой ответ на 8443, но не только они
        // Проверяем по SSDP: если устройство уже опознано как TV — не трогаем
        // (убрал эвристику "пустой 8443 = yandex" — слишком много ложных срабатываний)

        // ПК Пигмалион (веб-сервер с проектом)
        if (lowerTitle.contains("пигмалион") || lowerTitle.contains("pai"))
            return "web_server"

        // Roborock
        if (lowerBody.contains("roborock") || lowerTitle.contains("roborock") || lowerBody.contains("rockrobo"))
            return "roborock"

        return null
    }
}
