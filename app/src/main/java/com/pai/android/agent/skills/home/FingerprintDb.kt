package com.pai.android.agent.skills.home

/**
 * База сигнатур устройств для идентификации в локальной сети.
 * Собрана на основе данных DeepSeek + Gemini.
 *
 * Каждая запись содержит:
 * - vendor:   производитель
 * - models:   возможные модели
 * - tcp:      TCP порты для сканирования
 * - udp:      UDP порты для probe
 * - http:     HTTP fingerprint (title, server, body)
 * - mdns:     mDNS сервисные типы
 * - ssdp:     SSDP ST (Service Type) для UPnP
 * - protocol: специальные протоколы (coap, mqtt, miio и т.д.)
 */
object FingerprintDb {

    data class Entry(
        val vendor: String,
        val label: String,         // для вывода пользователю
        val emoji: String = "",
        val tcp: List<Int> = emptyList(),
        val udp: List<Int> = emptyList(),
        val httpTitle: List<String> = emptyList(),      // по <title>
        val httpServer: List<String> = emptyList(),     // по Server:
        val httpBody: List<String> = emptyList(),       // по содержимому HTML
        val mdnsService: List<String> = emptyList(),    // mDNS сервисные типы
        val ssdpSt: List<String> = emptyList(),         // SSDP ST заголовки
        val protocol: String? = null                    // "wiz", "yeelight", "coap", "miio", "mqtt"
    )

    /** Все записи в базе */
    val entries: List<Entry> = listOf(

        // ═══════ СВЕТ ═══════

        Entry("Yeelight", "Yeelight 💡", "💡",
            tcp = listOf(55443),
            httpBody = listOf("yeelight"),
            protocol = "yeelight"),

        Entry("WiZ / Gauss", "WiZ 💡", "💡",
            udp = listOf(38899),
            httpServer = listOf("lighttpd"),
            httpBody = listOf("wiz", "gauss"),
            protocol = "wiz"),

        Entry("Philips Hue", "Philips Hue 💡", "💡",
            tcp = listOf(80, 443),
            udp = listOf(1900, 5353),
            mdnsService = listOf("_hue._tcp"),
            ssdpSt = listOf("hue")),

        Entry("TP-Link Kasa", "TP-Link Kasa 💡", "💡",
            tcp = listOf(9999),
            protocol = "kasa"),

        Entry("LIFX", "LIFX 💡", "💡",
            udp = listOf(56700),
            protocol = "lifx"),

        Entry("IKEA TRADFRI", "IKEA TRADFRI 💡", "💡",
            tcp = listOf(80),
            udp = listOf(1900, 5353),
            mdnsService = listOf("_coap._udp")),

        Entry("Shelly", "Shelly 💡", "💡",
            tcp = listOf(80, 8081, 8082),
            udp = listOf(5683),
            httpServer = listOf("shelly"),
            httpBody = listOf("shelly")),

        Entry("Sonoff / Tasmota", "Sonoff/Tasmota 🔧", "🔧",
            tcp = listOf(80, 8080),
            httpBody = listOf("tasmota"),
            httpServer = listOf("tasmota")),

        Entry("ESPHome", "ESPHome 🔧", "🔧",
            tcp = listOf(80, 6053),
            udp = listOf(6053),
            httpBody = listOf("esphome"),
            httpServer = listOf("esphome")),

        Entry("BroadLink", "BroadLink 📡", "📡",
            tcp = listOf(80, 8080, 54321),
            httpBody = listOf("broadlink")),

        // ═══════ РОУТЕРЫ ═══════

        Entry("TP-Link Router", "TP-Link 📡", "📡",
            tcp = listOf(80, 443, 1900),
            udp = listOf(1900, 5353),
            httpTitle = listOf("tplink", "tp-link", "archer", "deco"),
            httpServer = listOf("tp-link", "tplink"),
            ssdpSt = listOf("tp-link", "miniu***pd")),

        Entry("D-Link Router", "D-Link 📡", "📡",
            tcp = listOf(80, 443),
            httpServer = listOf("httpd"),
            httpTitle = listOf("dir")),

        Entry("Keenetic Router", "Keenetic 📡", "📡",
            httpTitle = listOf("keenetic")),

        Entry("Router (generic)", "Роутер 📡", "📡",
            tcp = listOf(80, 443),
            httpTitle = listOf("router", "шлюз", "маршрутизатор", "wi-fi")),

        Entry("Ubiquiti", "Ubiquiti 📡", "📡",
            httpBody = listOf("ubiquiti"),
            httpServer = listOf("ubnt")),

        // ═══════ SMART TV ═══════

        Entry("Sony TV (Android TV)", "Sony TV 📺", "📺",
            tcp = listOf(80, 8080, 5555),
            udp = listOf(1900, 5353),
            ssdpSt = listOf("sony", "scalarwebapi"),
            httpServer = listOf("fedora", "sony")),

        Entry("Samsung TV", "Samsung TV 📺", "📺",
            tcp = listOf(8001, 8002, 55000),
            udp = listOf(1900, 5353),
            mdnsService = listOf("_samsung"),
            ssdpSt = listOf("samsung")),

        Entry("LG TV", "LG TV 📺", "📺",
            tcp = listOf(8080, 3000, 9000),
            udp = listOf(1900, 5353)),

        Entry("Chromecast", "Chromecast 📺", "📺",
            tcp = listOf(8008, 8009, 8443),
            udp = listOf(5353, 1900),
            mdnsService = listOf("_googlecast._tcp")),

        Entry("Apple TV", "Apple TV 🍎", "🍎",
            tcp = listOf(7000, 7100, 3689),
            udp = listOf(5353),
            mdnsService = listOf("_airplay._tcp", "_appletv")),

        Entry("TV (generic UPnP)", "Телевизор 📺", "📺",
            udp = listOf(1900, 5353),
            ssdpSt = listOf("mediarenderer", "tv")),

        // ═══════ УМНЫЙ ДОМ ═══════

        Entry("Home Assistant", "Home Assistant 🏠", "🏠",
            tcp = listOf(8123, 1883, 8883),
            udp = listOf(5353, 1900),
            mdnsService = listOf("_home-assistant._tcp"),
            httpTitle = listOf("home assistant")),

        Entry("Yandex Station", "Яндекс Станция 🔊", "🔊",
            tcp = listOf(80, 443, 3889, 8889),
            udp = listOf(5353, 1900),
            mdnsService = listOf("_yandexstation._tcp", "_yandex-quasar._tcp"),
            ssdpSt = listOf("yandex")),

        Entry("Amazon Echo", "Amazon Echo 🔊", "🔊",
            tcp = listOf(80, 443, 8883),
            udp = listOf(5353, 1900),
            mdnsService = listOf("_amzn"),
            httpBody = listOf("amazon", "echo")),

        Entry("Google Nest", "Google Nest 🔊", "🔊",
            tcp = listOf(80, 443, 8009),
            udp = listOf(5353, 1900)),

        // ═══════ IP-КАМЕРЫ ═══════

        Entry("Hikvision Camera", "Hikvision 📹", "📹",
            tcp = listOf(80, 443, 554),
            httpBody = listOf("hikvision"),
            httpServer = listOf("hikvision")),

        Entry("Dahua Camera", "Dahua 📹", "📹",
            tcp = listOf(80, 443, 554),
            httpBody = listOf("dahua"),
            httpServer = listOf("dahua")),

        Entry("Camera (generic RTSP)", "IP-камера 📹", "📹",
            tcp = listOf(554),
            httpTitle = listOf("camera", "камера")),

        // ═══════ ПЫЛЕСОСЫ / КЛИНИНГ ═══════

        Entry("Roborock", "Roborock 🧹", "🧹",
            tcp = listOf(80, 8080, 2123, 8883),
            udp = listOf(5353),
            httpBody = listOf("roborock", "rockrobo"),
            httpTitle = listOf("roborock")),

        Entry("Xiaomi Vacuum", "Xiaomi 🧹", "🧹",
            tcp = listOf(80),
            httpBody = listOf("xiaomi", "rockrobo")),

        // ═══════ УВЛАЖНИТЕЛИ / КЛИМАТ ═══════

        Entry("Xiaomi Miio", "Xiaomi Mi 🔷", "🔷",
            tcp = listOf(54321, 9898),
            udp = listOf(54321, 9898),
            protocol = "miio"),

        // ═══════ MQTT ═══════

        Entry("MQTT Device", "MQTT 📡", "📡",
            tcp = listOf(1883, 8883),
            protocol = "mqtt"),

        // ═══════ РАЗНОЕ ═══════

        Entry("Plex Media Server", "Plex 🎬", "🎬",
            tcp = listOf(32400),
            udp = listOf(1900, 5353),
            httpTitle = listOf("plex")),

        Entry("Pigmalion (PC)", "ПК Пигмалион 🖥", "🖥",
            tcp = listOf(80, 443, 8080),
            httpTitle = listOf("пигмалион", "pai"),
            httpServer = listOf("apache")),

        Entry("Printer", "Принтер 🖨", "🖨",
            udp = listOf(5353, 1900),
            ssdpSt = listOf("printer"))
    )

    /**
     * Ищет запись по HTTP fingerprint (title, server, body).
     */
    fun findByHttp(title: String?, server: String?, body: String?): Entry? {
        val t = title?.lowercase() ?: ""
        val s = server?.lowercase() ?: ""
        val b = body?.lowercase() ?: ""

        return entries.firstOrNull { entry ->
            entry.httpTitle.any { t.contains(it) } ||
            entry.httpServer.any { s.contains(it) } ||
            entry.httpBody.any { b.contains(it) }
        }
    }

    /**
     * Ищет запись по mDNS сервису.
     */
    fun findByMdns(serviceType: String?): Entry? {
        val st = serviceType?.lowercase() ?: return null
        return entries.firstOrNull { entry ->
            entry.mdnsService.any { st.contains(it.lowercase()) }
        }
    }

    /**
     * Ищет запись по SSDP ST.
     */
    fun findBySsdp(st: String?): Entry? {
        val s = st?.lowercase() ?: return null
        return entries.firstOrNull { entry ->
            entry.ssdpSt.any { s.contains(it.lowercase()) }
        }
    }

    /**
     * Ищет запись по открытым портам (TCP или UDP).
     * Исключает слишком общие порты (80, 443) — они есть у всех.
     */
    fun findByPorts(openTcp: List<Int>?, openUdp: List<Int>?): Entry? {
        val tcp = openTcp ?: emptyList()
        val udp = openUdp ?: emptyList()
        val allPorts = tcp + udp
        
        // Только общие порты — недостаточно для идентификации
        val genericPorts = setOf(80, 443, 8080, 8443)
        val specificPorts = allPorts.filter { it !in genericPorts }
        if (specificPorts.isEmpty()) return null

        // Сначала ищем по совпадению всех специфичных портов
        for (entry in entries) {
            val entryPorts = (entry.tcp + entry.udp).filter { it !in genericPorts }
            if (entryPorts.isEmpty()) continue
            if (entryPorts.all { it in specificPorts }) {
                return entry
            }
        }
        // Fallback: хотя бы один специфичный порт совпадает
        for (entry in entries) {
            val entryPorts = (entry.tcp + entry.udp).filter { it !in genericPorts }
            if (entryPorts.any { it in specificPorts }) {
                return entry
            }
        }
        return null
    }

    /**
     * Определяет полное описание устройства.
     */
    fun identify(
        ip: String,
        openTcp: List<Int>?,
        httpInfo: HttpProber.HttpInfo?,
        mdnsServiceType: String?,
        ssdpSt: String?
    ): String? {
        // 1. HTTP fingerprint (точнее всего)
        if (httpInfo != null) {
            val byHttp = findByHttp(httpInfo.title, httpInfo.server, null)
            if (byHttp != null && byHttp.label != "ПК Пигмалион 🖥") return byHttp.label
            // PC only if title matches
            if (byHttp != null) return byHttp.label
        }

        // 2. mDNS fingerprint
        if (mdnsServiceType != null) {
            val byMdns = findByMdns(mdnsServiceType)
            if (byMdns != null) return byMdns.label
        }

        // 3. SSDP fingerprint
        if (ssdpSt != null) {
            val bySsdp = findBySsdp(ssdpSt)
            if (bySsdp != null) return bySsdp.label
        }

        // 4. Порты
        if (openTcp != null) {
            val byPorts = findByPorts(openTcp, null)
            if (byPorts != null) return byPorts.label
        }

        return null
    }
}
