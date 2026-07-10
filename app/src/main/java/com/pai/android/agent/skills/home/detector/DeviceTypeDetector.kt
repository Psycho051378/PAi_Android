package com.pai.android.agent.skills.home.detector

import com.pai.android.data.model.DeviceProtocol
import com.pai.android.data.model.DeviceType

/**
 * Результат определения типа устройства.
 */
data class DeviceTypeInfo(
    val deviceType: DeviceType,
    val protocol: DeviceProtocol,
    val port: Int = 0
) {
    companion object {
        fun LIGHT(protocol: String, port: Int = 0) = DeviceTypeInfo(
            deviceType = DeviceType.LIGHT,
            protocol = protocolFromString(protocol),
            port = port
        )
        fun VACUUM(protocol: String) = DeviceTypeInfo(
            deviceType = DeviceType.VACUUM,
            protocol = protocolFromString(protocol)
        )
        fun HUMIDIFIER(protocol: String) = DeviceTypeInfo(
            deviceType = DeviceType.HUMIDIFIER,
            protocol = protocolFromString(protocol)
        )
        fun SPEAKER(protocol: String) = DeviceTypeInfo(
            deviceType = DeviceType.SPEAKER,
            protocol = protocolFromString(protocol)
        )
        fun TV(protocol: String) = DeviceTypeInfo(
            deviceType = DeviceType.TV,
            protocol = protocolFromString(protocol)
        )
        fun ESP_DEVICE(protocol: String) = DeviceTypeInfo(
            deviceType = DeviceType.ESP_DEVICE,
            protocol = protocolFromString(protocol)
        )
        fun ROUTER() = DeviceTypeInfo(
            deviceType = DeviceType.ROUTER,
            protocol = DeviceProtocol.UNKNOWN
        )
        fun OTHER(protocol: String = "unknown") = DeviceTypeInfo(
            deviceType = DeviceType.OTHER,
            protocol = protocolFromString(protocol)
        )
        fun COMPUTER(protocol: String = "unknown") = DeviceTypeInfo(
            deviceType = DeviceType.COMPUTER,
            protocol = protocolFromString(protocol)
        )

        private fun protocolFromString(s: String): DeviceProtocol {
            return DeviceProtocol.entries.find { it.name.equals(s, ignoreCase = true) }
                ?: DeviceProtocol.UNKNOWN
        }
    }
}

/**
 * Stateless детектор типа устройства.
 *
 * Определяет тип на основе:
 * - Hostname (имя устройства в сети)
 * - Открытых портов
 * - UDP fingerprint (WiZ, Yeelight, Xiaomi и т.д.)
 * - HTTP fingerprint (Tasmota, Shelly, ESPHome и т.д.)
 *
 * Маппинг полностью расширяемый — добавляй новые паттерны в maps.
 */
object DeviceTypeDetector {

    /**
     * Паттерны hostname → тип устройства.
     * Ключ — подстрока hostname (case-insensitive).
     */
    private val hostnamePatterns: Map<String, DeviceTypeInfo> = mapOf(
        "yeelink" to DeviceTypeInfo.LIGHT("yeelight", 55443),
        "yeelight" to DeviceTypeInfo.LIGHT("yeelight", 55443),
        "wiz_" to DeviceTypeInfo.LIGHT("wiz", 0),
        "wiz-" to DeviceTypeInfo.LIGHT("wiz", 0),
        "roborock-vacuum" to DeviceTypeInfo.VACUUM("roborock"),
        "roborock" to DeviceTypeInfo.VACUUM("roborock"),
        "zhimi-humidifier" to DeviceTypeInfo.HUMIDIFIER("xiaomi_miio"),
        "zhimi" to DeviceTypeInfo.HUMIDIFIER("xiaomi_miio"),
        "esp_" to DeviceTypeInfo.ESP_DEVICE("tasmota"),
        "esp-" to DeviceTypeInfo.ESP_DEVICE("tasmota"),
        "tasmota" to DeviceTypeInfo.ESP_DEVICE("tasmota"),
        "esphome" to DeviceTypeInfo.ESP_DEVICE("esphome"),
        "sonoff" to DeviceTypeInfo.ESP_DEVICE("tasmota"),
        "yandexstation" to DeviceTypeInfo.SPEAKER("yandex"),
        "yandex-mini" to DeviceTypeInfo.SPEAKER("yandex"),
        "yandex" to DeviceTypeInfo.SPEAKER("yandex"),
        "xiaomi" to DeviceTypeInfo.OTHER("xiaomi_miio"),
        "shelly" to DeviceTypeInfo.OTHER("tasmota"),
        "philips" to DeviceTypeInfo.LIGHT("unknown"),
        "hue" to DeviceTypeInfo.LIGHT("unknown"),
        "broadlink" to DeviceTypeInfo.OTHER("unknown"),
        "lamp" to DeviceTypeInfo.LIGHT("unknown"),
        "light" to DeviceTypeInfo.LIGHT("unknown"),
        "tv-" to DeviceTypeInfo.TV("unknown"),
        "smart-tv" to DeviceTypeInfo.TV("unknown"),
        "roku" to DeviceTypeInfo.TV("unknown"),
        "printer" to DeviceTypeInfo.OTHER("unknown"),
        "router" to DeviceTypeInfo.ROUTER(),
        "ap-" to DeviceTypeInfo.ROUTER(),
        "gateway" to DeviceTypeInfo.ROUTER(),
        "tp-link" to DeviceTypeInfo.ROUTER(),
        "dlink" to DeviceTypeInfo.ROUTER(),
        "keenetic" to DeviceTypeInfo.ROUTER(),
        "mikrotik" to DeviceTypeInfo.ROUTER(),
        "asus" to DeviceTypeInfo.ROUTER(),
    )

    /**
     * Паттерны открытых портов → тип устройства.
     * Ключ — номер порта.
     */
    private val portPatterns: Map<Int, DeviceTypeInfo> = mapOf(
        55443 to DeviceTypeInfo.LIGHT("yeelight", 55443),
        38899 to DeviceTypeInfo.LIGHT("wiz", 38899),
        80 to DeviceTypeInfo.OTHER("unknown"),
        443 to DeviceTypeInfo.OTHER("unknown"),
        8080 to DeviceTypeInfo.OTHER("unknown"),
        1883 to DeviceTypeInfo.OTHER("mqtt"),
        8883 to DeviceTypeInfo.OTHER("mqtt"),
        8123 to DeviceTypeInfo.OTHER("unknown"), // Home Assistant
        32400 to DeviceTypeInfo.TV("unknown"),   // Plex
        8200 to DeviceTypeInfo.TV("unknown"),    // Plex
        1900 to DeviceTypeInfo.OTHER("unknown"), // UPnP/SSDP
        5353 to DeviceTypeInfo.OTHER("unknown"), // mDNS
    )

    /**
     * Определить тип устройства по всем доступным данным.
     *
     * @param hostname hostname устройства (может быть пустым)
     * @param openPorts список открытых портов
     * @param udpFingerprint UDP fingerprint (из UdpProber), может быть null
     * @param httpFingerprint HTTP fingerprint (из HttpProber), может быть null
     * @return DeviceTypeInfo с определённым типом и протоколом
     */
    fun detect(
        hostname: String = "",
        openPorts: List<Int> = emptyList(),
        udpFingerprint: String? = null,
        httpFingerprint: String? = null
    ): DeviceTypeInfo {
        // 1. UDP fingerprint — самый точный (активный пробник)
        if (udpFingerprint != null) {
            val fromUdp = fromUdpFingerprint(udpFingerprint)
            if (fromUdp != null) return fromUdp
        }

        // 2. HTTP fingerprint
        if (httpFingerprint != null) {
            val fromHttp = fromHttpFingerprint(httpFingerprint)
            if (fromHttp != null) return fromHttp
        }

        // 3. Hostname patterns
        if (hostname.isNotBlank()) {
            val lower = hostname.lowercase()
            for ((pattern, info) in hostnamePatterns) {
                if (lower.contains(pattern)) return info
            }
        }

        // 4. Port patterns (если есть характерные порты)
        for (port in openPorts) {
            val info = portPatterns[port]
            if (info != null && info.deviceType != DeviceType.OTHER) return info
        }

        // 5. Порт 80 + HTTP title может быть web-сервером
        if (openPorts.any { it in listOf(80, 443, 8080) }) {
            // Скорее всего ПК или ноутбук
            return DeviceTypeInfo(DeviceType.COMPUTER, DeviceProtocol.UNKNOWN)
        }

        // 6. Ничего не определили
        return DeviceTypeInfo(DeviceType.OTHER, DeviceProtocol.UNKNOWN)
    }

    /**
     * Определить протокол управления по протокол-специфичным портам.
     */
    fun detectProtocol(openPorts: List<Int>, udpFingerprint: String? = null): DeviceProtocol {
        if (udpFingerprint != null) {
            val info = fromUdpFingerprint(udpFingerprint)
            if (info != null) return info.protocol
        }

        return when {
            openPorts.contains(38899) -> DeviceProtocol.WIZ
            openPorts.contains(55443) -> DeviceProtocol.YEELIGHT
            openPorts.any { it in listOf(1883, 8883) } -> DeviceProtocol.MQTT
            else -> DeviceProtocol.UNKNOWN
        }
    }

    /**
     * Получить список возможностей устройства (capabilities) по его типу и протоколу.
     */
    fun getCapabilities(deviceType: DeviceType, protocol: DeviceProtocol): List<String> {
        return when (deviceType) {
            DeviceType.LIGHT -> when (protocol) {
                DeviceProtocol.WIZ -> listOf("on_off", "brightness", "color_temp", "rgb")
                DeviceProtocol.YEELIGHT -> listOf("on_off", "brightness", "color_temp", "rgb")
                DeviceProtocol.TASMOTA -> listOf("on_off", "brightness")
                DeviceProtocol.ESPHOME -> listOf("on_off", "brightness")
                else -> listOf("on_off")
            }
            DeviceType.VACUUM -> listOf("start", "stop", "pause", "dock")
            DeviceType.HUMIDIFIER -> listOf("on_off", "set_humidity")
            DeviceType.SPEAKER -> listOf("on_off", "volume", "play", "pause")
            DeviceType.TV -> listOf("on_off", "volume", "input")
            DeviceType.ESP_DEVICE -> listOf("on_off")
            DeviceType.ROUTER -> emptyList()
            DeviceType.COMPUTER -> emptyList()
            DeviceType.PHONE -> emptyList()
            DeviceType.WEARABLE -> emptyList()
            DeviceType.OTHER -> listOf("on_off")
        }
    }

    // ==================== Private helpers ====================

    private fun fromUdpFingerprint(fingerprint: String): DeviceTypeInfo? {
        return when (fingerprint) {
            "wiz_light" -> DeviceTypeInfo.LIGHT("wiz", 38899)
            "yeelight" -> DeviceTypeInfo.LIGHT("yeelight", 55443)
            "roborock" -> DeviceTypeInfo.VACUUM("roborock")
            "xiaomi_humidifier" -> DeviceTypeInfo.HUMIDIFIER("xiaomi_miio")
            "xiaomi_plug" -> DeviceTypeInfo.OTHER("xiaomi_miio")
            "xiaomi_gateway" -> DeviceTypeInfo.OTHER("xiaomi_miio")
            "xiaomi_light" -> DeviceTypeInfo.LIGHT("xiaomi_miio")
            "xiaomi_airpurifier" -> DeviceTypeInfo.HUMIDIFIER("xiaomi_miio")
            "xiaomi_ac" -> DeviceTypeInfo.OTHER("xiaomi_miio")
            "xiaomi_fan" -> DeviceTypeInfo.OTHER("xiaomi_miio")
            "xiaomi_speaker" -> DeviceTypeInfo.SPEAKER("xiaomi_miio")
            "xiaomi_camera" -> DeviceTypeInfo.OTHER("xiaomi_miio")
            "xiaomi_switch" -> DeviceTypeInfo.OTHER("xiaomi_miio")
            "xiaomi_tv" -> DeviceTypeInfo.TV("xiaomi_miio")
            "xiaomi_unknown" -> DeviceTypeInfo.OTHER("xiaomi_miio")
            "mqtt_device" -> DeviceTypeInfo.OTHER("mqtt")
            else -> null
        }
    }

    private fun fromHttpFingerprint(fingerprint: String): DeviceTypeInfo? {
        return when (fingerprint) {
            "sonoff_tasmota" -> DeviceTypeInfo.ESP_DEVICE("tasmota")
            "tasmota" -> DeviceTypeInfo.ESP_DEVICE("tasmota")
            "shelly" -> DeviceTypeInfo.OTHER("tasmota")
            "esphome" -> DeviceTypeInfo.ESP_DEVICE("esphome")
            "home_assistant" -> DeviceTypeInfo.OTHER("unknown")
            "yeelight" -> DeviceTypeInfo.LIGHT("yeelight", 80)
            "tp_link" -> DeviceTypeInfo.ROUTER()
            "dlink_router" -> DeviceTypeInfo.ROUTER()
            "keenetic_router" -> DeviceTypeInfo.ROUTER()
            "router" -> DeviceTypeInfo.ROUTER()
            "ip_camera" -> DeviceTypeInfo.OTHER("unknown")
            "xiaomi" -> DeviceTypeInfo.OTHER("xiaomi_miio")
            "philips_hue" -> DeviceTypeInfo.LIGHT("unknown")
            "broadlink" -> DeviceTypeInfo.OTHER("unknown")
            "wiz_light" -> DeviceTypeInfo.LIGHT("wiz", 80)
            "plex" -> DeviceTypeInfo.TV("unknown")
            "apple" -> DeviceTypeInfo.OTHER("unknown")
            "ubiquiti" -> DeviceTypeInfo.ROUTER()
            "yandex_station" -> DeviceTypeInfo.SPEAKER("yandex")
            "web_server" -> DeviceTypeInfo.COMPUTER("unknown")
            "mqtt_device" -> DeviceTypeInfo.OTHER("mqtt")
            "roborock" -> DeviceTypeInfo.VACUUM("roborock")
            "yandex_remote" -> DeviceTypeInfo.OTHER("yandex")
            else -> null
        }
    }
}
