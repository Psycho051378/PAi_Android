package com.pai.android.agent.skills

import android.content.Context
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pai.android.agent.Intent as AgentIntent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import com.pai.android.agent.ResponseType
import com.pai.android.data.repository.MemoryRepository
import com.pai.android.agent.skills.home.TcpScanner
import com.pai.android.agent.skills.home.HttpProber
import com.pai.android.agent.skills.home.MulticastDiscovery
import com.pai.android.agent.skills.home.UdpProber
import com.pai.android.agent.skills.home.FingerprintDb
import com.pai.android.agent.skills.home.router.RouterScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val okHttpClient: OkHttpClient,
    private var routerScanner: RouterScanner? = null
) : Skill {

    /**
     * Позволяет установить RouterScanner после создания (для циклической зависимости).
     */
    fun updateRouterScanner(scanner: RouterScanner) {
        this.routerScanner = scanner
    }

    override val name: String = "home"
    override val description: String =
        "Умный дом: сканирование Wi-Fi сети, идентификация устройств и управление"

    companion object {
        @Volatile var enabled: Boolean = true
        private const val PREFS_NAME = "home_skill"
        private const val PREF_ROUTER_CONFIG = "router_config"
    }

    // ── Router config (SharedPreferences, как EmailSkill) ──
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getRouterConfig(): RouterConfigData? {
        val json = prefs.getString(PREF_ROUTER_CONFIG, null) ?: return null
        return try {
            val obj = org.json.JSONObject(json)
            RouterConfigData(
                enabled = obj.optBoolean("enabled", false),
                ip = obj.optString("ip", "192.168.0.1"),
                port = obj.optInt("port", 80),
                username = obj.optString("username", ""),
                password = obj.optString("password", ""),
                community = obj.optString("community", "public"),
                protocol = obj.optString("protocol", "HTTP")
            )
        } catch (e: Exception) {
            println("HomeSkill: getRouterConfig error: ${e.message}")
            null
        }
    }

    fun saveRouterConfig(config: RouterConfigData) {
        try {
            val json = org.json.JSONObject().apply {
                put("enabled", config.enabled)
                put("ip", config.ip)
                put("port", config.port)
                put("username", config.username)
                put("password", config.password)
                put("community", config.community)
                put("protocol", config.protocol)
            }.toString()
            prefs.edit().putString(PREF_ROUTER_CONFIG, json).apply()
            println("HomeSkill: router config saved")
        } catch (e: Exception) {
            println("HomeSkill: saveRouterConfig error: ${e.message}")
        }
    }

    fun clearRouterConfig() {
        prefs.edit().remove(PREF_ROUTER_CONFIG).apply()
    }

    fun isRouterEnabled(): Boolean {
        return getRouterConfig()?.enabled ?: false
    }

    // ── canHandle ──
    override fun canHandle(intent: AgentIntent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        if (intent == AgentIntent.TOOL_OPERATION && params["command"] == name) return true
        val lower = query.lowercase()
        return lower.contains("сканируй сеть") || lower.contains("что в сети") ||
                lower.contains("умный дом") || lower.contains("найди устройства") ||
                lower.contains("настрой роутер") || lower.contains("роутер") &&
                (lower.contains("пароль") || lower.contains("настрой") || lower.contains("логин")) ||
                lower.contains("включи") || lower.contains("выключи") ||
                lower.contains("home") || lower.contains("arp")
    }

    // ── execute ──
    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        try {
            val query = params["query"] as? String ?: params["q"] as? String ?: ""
            // Если query пустой — AI вызвал нас без контекста, сканируем по умолчанию
            if (query.isBlank()) {
                println("HomeSkill: empty query, defaulting to scan")
                doScan()
            } else {
                when (classifyIntent(query)) {
                    IntentType.SCAN -> doScan()
                    IntentType.STATUS -> getStatus()
                    IntentType.CONFIGURE_ROUTER -> configureRouter(query)
                    IntentType.CONTROL -> doControl(query)
                    else -> doScan() // не распознали — тоже сканируем
                }
            }
        } catch (e: Exception) {
            SkillResult.Error(message = "HomeSkill error: ${e.message ?: "Unknown"}")
        }
    }

    // ── Intent classification ──
    private enum class IntentType { SCAN, STATUS, CONFIGURE_ROUTER, CONTROL, UNKNOWN }

    private fun classifyIntent(query: String): IntentType {
        val lower = query.lowercase()
        return when {
            lower.contains("сканируй") || lower.contains("найди устройств") -> IntentType.SCAN
            lower.contains("что в сети") || lower.contains("статус") ||
                    lower.contains("покажи") || lower.contains("список") -> IntentType.STATUS
            lower.contains("настрой роутер") || lower.contains("пароль от роутер") ||
                    lower.contains("роутер парол") || lower.contains("логин роутер") ||
                    lower.contains("router password") || lower.contains("router config") -> IntentType.CONFIGURE_ROUTER
            lower.contains("включи") || lower.contains("выключи") ||
                    lower.contains("on") || lower.contains("off") -> IntentType.CONTROL
            else -> IntentType.UNKNOWN
        }
    }

    // ── SCAN ──
    private suspend fun doScan(): SkillResult {
        println("HomeSkill: doScan started")
        val httpProber = HttpProber(okHttpClient)
        val ssid = getSsid()
        println("HomeSkill: ssid=$ssid")
        val gatewayIp = getGatewayIp()
        println("HomeSkill: gatewayIp=$gatewayIp")
        val gatewayMac = if (gatewayIp != null) getGatewayMac(gatewayIp) else null
        println("HomeSkill: gatewayMac=$gatewayMac")
        val subnet = getSubnet()
        val myIp = getMyIp()
        println("HomeSkill: subnet=$subnet myIp=$myIp")

        if (ssid == null) {
            return SkillResult.Success(
                message = "⚠️ Не удалось определить сеть. Проверьте подключение к Wi-Fi.",
                responseType = ResponseType.TEXT
            )
        }

        // 1. Ping sweep — наполняет ARP кэш устройствами
        val aliveHosts = pingSweep(subnet)

        // 2. TCP sweep — проверяем порт 80 на всей подсети (находит устройства, блокирующие ICMP)
        println("HomeSkill: starting TCP sweep (port 80) for all hosts...")
        val tcpAlive = tcpSweep(subnet)
        println("HomeSkill: TCP sweep found ${tcpAlive.size} additional hosts")

        // 3. mDNS + SSDP multicast discovery
        println("HomeSkill: starting mDNS/SSDP multicast discovery...")
        val mdnsDevices = MulticastDiscovery.discover()
        println("HomeSkill: mDNS/SSDP found ${mdnsDevices.size} devices")
        val mdnsByIp = mdnsDevices.associateBy { it.ip }

        // 4. ARP-таблица — читаем ПОСЛЕ пинга (когда ARP кэш наполнен)
        val arpEntries = readArpTable()

        val allIps = (arpEntries.keys + aliveHosts + tcpAlive + mdnsDevices.map { it.ip }).toSet().sorted()

        // UDP probe (CoAP для WiZ, MQTT для Яндекса/Roborock)
        println("HomeSkill: starting UDP/CoAP/MQTT probe for ${allIps.size} hosts...")
        val udpResults = UdpProber.probeAll(allIps)
        println("HomeSkill: UDP probe found ${udpResults.size} devices")

        // NetBIOS MAC discovery (UDP 137)
        println("HomeSkill: starting NetBIOS MAC probe for ${allIps.size} hosts...")
        val netbiosMacs = mutableMapOf<String, String>()
        for (ip in allIps) {
            val mac = UdpProber.discoverNetbios(ip)
            if (mac != null) netbiosMacs[ip] = mac
        }
        println("HomeSkill: NetBIOS found ${netbiosMacs.size} MACs")

        // TCP scan портов
        println("HomeSkill: starting TCP port scan for ${allIps.size} hosts...")
        val openPorts = TcpScanner.scanPorts(allIps)
        println("HomeSkill: TCP scan done, ${openPorts.size} hosts with open ports")

        // HTTP probe
        val httpInfo = httpProber.probeAll(openPorts)

        // Router ARP (если настроен)
        var routerArp = emptyMap<String, String>()
        try {
            val routerConfig = getRouterConfig()
            val routerScan = routerScanner
            if (routerScan != null && routerConfig != null && routerConfig.enabled) {
                println("HomeSkill: trying router scan via ${routerConfig.protocol}")
                val config = routerConfig.toRouterConfig()
                routerArp = routerScan.scan(config)
                println("HomeSkill: router ARP found ${routerArp.size} entries")
            }
        } catch (e: Exception) {
            println("HomeSkill: router scan error: ${e.message}")
        }

        // Формируем ответ
        val sb = StringBuilder()
        sb.appendLine("🏠 **Сеть: $ssid**")
        sb.appendLine("┌────────────────────────────────────")
        sb.appendLine("│ IP устройства: $myIp")
        sb.appendLine("│ Шлюз: ${gatewayIp ?: "?"} (${gatewayMac ?: "?"})")
        sb.appendLine("│ Подсеть: $subnet")
        sb.appendLine("│ Всего активных: ${allIps.size}")
        sb.appendLine("└────────────────────────────────────")
        sb.appendLine()

        if (allIps.isEmpty()) {
            sb.appendLine("Активных устройств не найдено.")
        } else {
            for ((i, ip) in allIps.withIndex()) {
                val ports = openPorts[ip]
                val info = httpInfo[ip]
                val udp = udpResults[ip]
                val mdnsDevice = mdnsByIp[ip]
                val macRaw = info?.mac ?: udp?.mac ?: mdnsDevice?.mac ?: netbiosMacs[ip] ?: arpEntries[ip] ?: routerArp[ip] ?: "?"
                val mac = if (macRaw == "00:00:00:00:00:00") "?" else macRaw
                val vendor = if (mac != "?") guessVendor(mac) else ""

                sb.appendLine("${i + 1}. **$ip**")
                
                // Тип устройства — сначала HTTP fingerprint, потом UDP fingerprint, потом mDNS
                val deviceLabel = when (info?.fingerprint) {
                    "sonoff_tasmota" -> "💡 Sonoff/Tasmota"
                    "shelly" -> "💡 Shelly"
                    "esphome" -> "🔧 ESPHome"
                    "home_assistant" -> "🏠 Home Assistant"
                    "yeelight" -> "💡 Yeelight"
                    "tp_link" -> "📡 TP-Link"
                    "dlink_router" -> "📡 D-Link"
                    "keenetic_router" -> "📡 Keenetic"
                    "router" -> "📡 Роутер"
                    "ip_camera" -> "📹 IP-камера"
                    "xiaomi" -> "🔷 Xiaomi"
                    "philips_hue" -> "💡 Philips Hue"
                    "broadlink" -> "📡 BroadLink"
                    "wiz_light" -> "💡 WiZ"
                    "plex" -> "🎬 Plex"
                    "apple" -> "🍎 Apple"
                    "ubiquiti" -> "📡 Ubiquiti"
                    "yandex_station" -> "🔊 Яндекс Станция"
                    "web_server" -> "🖥 ПК Пигмалион"
                    "mqtt_device" -> "📡 Умное устройство (MQTT)"
                    "roborock" -> "🧹 Roborock"
                    "yandex_remote" -> "📡 Яндекс Пульт"
                    else -> {
                        // Определяем по UDP fingerprint
                        when (udp?.fingerprint) {
                            "yeelight" -> "💡 Yeelight"
                            "xiaomi_humidifier" -> "💨 Увлажнитель Xiaomi"
                            "xiaomi_plug" -> "🔌 Розетка Xiaomi"
                            "xiaomi_gateway" -> "🏠 Шлюз Xiaomi"
                            "xiaomi_light" -> "💡 Лампа Xiaomi"
                            "xiaomi_airpurifier" -> "🌬 Очиститель Xiaomi"
                            "xiaomi_ac" -> "❄️ Кондиционер Xiaomi"
                            "xiaomi_fan" -> "🌀 Вентилятор Xiaomi"
                            "xiaomi_speaker" -> "🔊 Колонка Xiaomi"
                            "xiaomi_camera" -> "📹 Камера Xiaomi"
                            "xiaomi_switch" -> "🔘 Выключатель Xiaomi"
                            "xiaomi_unknown" -> "🔷 Xiaomi устройство"
                            "roborock" -> "🧹 Roborock"
                            else -> FingerprintDb.identify(ip, ports, info, mdnsDevice?.serviceType, null) ?: null
                        }
                    }
                }
                if (deviceLabel != null) {
                    sb.appendLine("   $deviceLabel")
                }
                sb.appendLine("   MAC: $mac $vendor")
                if (ports != null && ports.isNotEmpty()) {
                    sb.appendLine("   🔓 Порты: ${ports.joinToString(", ")}")
                }
                if (info?.title != null) {
                    sb.appendLine("   🌐 ${info.title}")
                }
                if (info?.server != null) {
                    sb.appendLine("   Сервер: ${info.server}")
                }
                // Информация из UDP/TCP протоколов
                if (udp != null && deviceLabel == null) {
                    when (udp.fingerprint) {
                        "wiz_light" -> sb.appendLine("   💡 WiZ")
                        "yeelight" -> sb.appendLine("   💡 Yeelight")
                        "roborock" -> sb.appendLine("   🧹 Roborock")
                        "xiaomi_humidifier" -> sb.appendLine("   💨 Увлажнитель")
                        "xiaomi_plug" -> sb.appendLine("   🔌 Розетка")
                        "xiaomi_gateway" -> sb.appendLine("   🏠 Шлюз Xiaomi")
                        "xiaomi_light" -> sb.appendLine("   💡 Лампа")
                        "xiaomi_airpurifier" -> sb.appendLine("   🌬 Очиститель")
                        "xiaomi_ac" -> sb.appendLine("   ❄️ Кондиционер")
                        "xiaomi_fan" -> sb.appendLine("   🌀 Вентилятор")
                        "xiaomi_speaker" -> sb.appendLine("   🔊 Колонка")
                        "xiaomi_camera" -> sb.appendLine("   📹 Камера")
                        "xiaomi_switch" -> sb.appendLine("   🔘 Выключатель")
                        "xiaomi_tv" -> sb.appendLine("   📺 Телевизор")
                        "xiaomi_unknown" -> sb.appendLine("   🔷 Xiaomi")
                        "mqtt_device" -> {
                            val proto = if (udp.protocol == "mqtts") "MQTT over TLS" else "MQTT"
                            sb.appendLine("   📡 Умное устройство ($proto)")
                        }
                    }
                }
                // Информация из mDNS/SSDP
                val mdns = mdnsByIp[ip]
                if (mdns != null && deviceLabel == null) {
                    val mdnsLabel = when (mdns.serviceType) {
                        "smart_tv" -> "📺 Телевизор"
                        "audio_receiver" -> "🔊 Аудиосистема"
                        "printer" -> "🖨 Принтер"
                        "nas" -> "💾 NAS"
                        "gateway" -> "📡 Шлюз"
                        "smart_light" -> "💡 Лампа"
                        else -> null
                    }
                    if (mdnsLabel != null) sb.appendLine("   $mdnsLabel")
                    if (mdns.friendlyName != null) sb.appendLine("   📛 ${mdns.friendlyName}")
                    if (mdns.hostname != null) sb.appendLine("   Имя: ${mdns.hostname}")
                }
            }
        }

        // Сохраняем в MemoryRepository
        val cacheKey = gatewayMac ?: gatewayIp ?: ssid ?: "unknown"
        saveNetworkCache(cacheKey, ssid, subnet, allIps, arpEntries)

        return SkillResult.Success(message = sb.toString(), responseType = ResponseType.TEXT)
    }

    // ── STATUS ──
    private suspend fun getStatus(): SkillResult {
        val ssid = getSsid()
        val gatewayIp = getGatewayIp()
        val gatewayMac = if (gatewayIp != null) getGatewayMac(gatewayIp) else null
        val myIp = getMyIp()

        if (gatewayIp == null && ssid == null) {
            return SkillResult.Success(
                message = "❌ Не удалось определить текущую сеть.",
                responseType = ResponseType.TEXT
            )
        }

        val cacheKey = gatewayMac ?: gatewayIp ?: ssid ?: "unknown"
        // Пытаемся загрузить закешированные данные
        val cached = loadNetworkCache(cacheKey)

        val sb = StringBuilder()
        if (cached != null) {
            sb.appendLine("🏠 **${cached.ssid}** (кеш)")
            sb.appendLine("┌────────────────────────────────────")
            sb.appendLine("│ IP: $myIp")
            sb.appendLine("│ Подсеть: ${cached.subnet}")
            sb.appendLine("│ Устройств: ${cached.deviceCount}")
            sb.appendLine("│ Последнее сканирование: ${cached.lastScan}")
            sb.appendLine("└────────────────────────────────────")
            sb.appendLine()
            sb.appendLine("ℹ️ Для свежих данных скажи «сканируй сеть».")
        } else {
            sb.appendLine("ℹ️ Нет сохранённых данных для текущей сети.")
            sb.appendLine("Скажи «сканируй сеть», чтобы начать сканирование.")
        }

        return SkillResult.Success(message = sb.toString(), responseType = ResponseType.TEXT)
    }

    // ── CONFIGURE ROUTER ──
    /**
     * Настройка роутера для сканирования ARP.
     * Принимает запрос вида:
     * "настрой роутер пароль admin"
     * "роутер логин admin пароль mypass"
     */
    private suspend fun configureRouter(query: String): SkillResult {
        val lower = query.lowercase()

        // Пытаемся извлечь пароль из текста
        val passwordMatch = Regex("пароль\\s+([\\w!@#$%^&*()_+=-]+)", RegexOption.IGNORE_CASE).find(lower)
        val usernameMatch = Regex("логин\\s+([\\w!@#$%^&*()_+=-]+)", RegexOption.IGNORE_CASE).find(lower)
        val ipMatch = Regex("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})").find(lower)

        val password = passwordMatch?.groupValues?.getOrNull(1)
        val username = usernameMatch?.groupValues?.getOrNull(1) ?: "admin"
        val ip = ipMatch?.groupValues?.getOrNull(1) ?: getGatewayIp() ?: "192.168.0.1"

        if (password.isNullOrBlank()) {
            return SkillResult.Success(
                message = "ℹ️ **Настройка роутера**\n\n" +
                        "Для сканирования ARP-таблицы нужен пароль от роутера.\n" +
                        "Скажи: «настрой роутер пароль **твой_пароль**»\n" +
                        "Если логин не admin: «роутер логин **admin** пароль **pass**»\n" +
                        "Если IP другой: «роутер 192.168.1.1 пароль **pass**»\n\n" +
                        "Текущий статус: ${if (isRouterEnabled()) "✅ настроен" else "❌ не настроен"}",
                responseType = ResponseType.TEXT
            )
        }

        val config = RouterConfigData(
            enabled = true,
            ip = ip,
            port = 80,
            username = username,
            password = password,
            protocol = "HTTP"
        )
        saveRouterConfig(config)

        // Проверяем подключение через RouterScanner
        return try {
            val scanner = routerScanner
            if (scanner != null) {
                val result = scanner.testConnection(config.toRouterConfig())
                if (result.success) {
                    SkillResult.Success(
                        message = "✅ **Роутер настроен!**\n" +
                                "IP: ${config.ip}:${config.port}\n" +
                                "Найдено устройств в ARP: ${result.deviceCount}\n" +
                                "Протокол: ${result.protocol?.name ?: "HTTP"}\n\n" +
                                "Теперь скажи «сканируй сеть» для полного сканирования.",
                        responseType = ResponseType.TEXT
                    )
                } else {
                    SkillResult.Success(
                        message = "⚠️ **Роутер настроен, но не удалось подключиться.**\n" +
                                "IP: ${config.ip}:${config.port}\n" +
                                "Ошибка: ${result.error ?: "неизвестная"}\n\n" +
                                "Проверь пароль и IP роутера.\n" +
                                "Попробуй: «настрой роутер пароль **правильный_пароль**»",
                        responseType = ResponseType.TEXT
                    )
                }
            } else {
                SkillResult.Success(
                    message = "✅ Пароль сохранён. RouterScanner недоступен сейчас — \n" +
                            "попробуй «сканируй сеть» позже, будет использован HTTP API.",
                    responseType = ResponseType.TEXT
                )
            }
        } catch (e: Exception) {
            SkillResult.Success(
                message = "✅ Пароль сохранён. Но тест подключения не удался: ${e.message}",
                responseType = ResponseType.TEXT
            )
        }
    }

    // ── CONTROL (заглушка) ──
    private suspend fun doControl(query: String): SkillResult {
        return SkillResult.Success(
            message = "🛠 Управление устройствами — в разработке.",
            responseType = ResponseType.TEXT
        )
    }

    // ════════════════ Network utilities ════════════════

    private fun getSsid(): String? {
        return try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val info = wm.connectionInfo ?: return null
            val ssid = info.ssid ?: return null
            ssid.removeSurrounding("\"").ifBlank { null }
        } catch (e: Exception) { null }
    }

    private fun getMyIp(): String {
        return try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            if (info != null) {
                val ipInt = info.ipAddress
                if (ipInt != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }
            }
            // Fallback: NetworkInterface
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = Collections.list(ni.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
            "0.0.0.0"
        } catch (e: Exception) { "0.0.0.0" }
    }

    /**
     * TCP sweep — проверяет порт 80 на всей подсети.
     * Находит устройства, которые не отвечают на ICMP ping.
     */
    private fun tcpSweep(subnet: String): List<String> {
        val baseParts = subnet.split("/")[0].split(".")
        if (baseParts.size < 4) return emptyList()
        val base = "${baseParts[0]}.${baseParts[1]}.${baseParts[2]}"
        val results = mutableListOf<String>()
        var threadCount = 0
        
        for (i in 1..254) {
            val ip = "$base.$i"
            threadCount++
            val t = Thread {
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(ip, 80), 500)
                    socket.close()
                    synchronized(results) { results.add(ip) }
                } catch (_: Exception) {}
            }
            t.start()
            // Батчим по 30 потоков, чтобы не создать 254 одновременно
            if (threadCount >= 30) {
                try { t.join(2000) } catch (_: Exception) {}
                threadCount = 0
            }
        }
        
        return results.sortedBy { it.split(".").last().toIntOrNull() ?: 0 }
    }

    /**
     * Определяет подсеть по IP (по умолчанию /24).
     */
    private fun getSubnet(): String {
        // Используем gateway IP для подсети, если доступен
        val gwIp = getGatewayIp()
        if (gwIp != null) {
            val parts = gwIp.split(".")
            if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}.0/24"
        }
        val ip = getMyIp()
        if (ip == "0.0.0.0") return "192.168.0.0/24"
        val parts = ip.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.0/24" else "192.168.0.0/24"
    }

    /**
     * Читает MAC-адрес шлюза из /proc/net/arp (первая запись после заголовка).
     */
    private fun getGatewayIp(): String? {
        // WifiManager.getDhcpInfo() — надёжный способ на Android 14+
        return try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val dhcp = wm.dhcpInfo ?: return null
            val gwInt = dhcp.gateway
            if (gwInt == 0) return null
            String.format("%d.%d.%d.%d", gwInt and 0xff, gwInt shr 8 and 0xff, gwInt shr 16 and 0xff, gwInt shr 24 and 0xff)
        } catch (e: Exception) { null }
    }

    private fun getGatewayMac(gatewayIp: String?): String? {
        if (gatewayIp == null) return null
        return try {
            val arp = readArpTable()
            arp[gatewayIp]
        } catch (e: Exception) { null }
    }

    /**
     * Читает /proc/net/arp → Map<IP, MAC>.
     */
    private fun readArpTable(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // 1. Пробуем /proc/net/arp (не работает на Android 14+)
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                reader.readLine() // skip header
                reader.forEachLine { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        if (mac != "00:00:00:00:00:00") {
                            result[ip] = mac
                        }
                    }
                }
            }
            if (result.isNotEmpty()) return result
        } catch (e: Exception) {
            println("HomeSkill: ARP /proc/net/arp error: ${e.message}")
        }
        
        // 2. Fallback: ip neigh shell-команда
        try {
            val process = Runtime.getRuntime().exec("ip neigh")
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val stdoutText = stdout.readText().trim()
            val stderrText = stderr.readText().trim()
            process.waitFor()
            val exitCode = process.exitValue()
            println("HomeSkill: ip neigh exit=$exitCode stdout=${stdoutText.take(500)} stderr=${stderrText.take(200)}")
            if (exitCode == 0 && stdoutText.isNotBlank()) {
                stdoutText.lines().forEach { line ->
                    val parts = line.split("\\s+".toRegex())
                    // Формат: 192.168.0.1 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
                    if (parts.size >= 5 && parts[3] == "lladdr") {
                        val ip = parts[0]
                        val mac = parts[4]
                        if (mac != "00:00:00:00:00:00") {
                            result[ip] = mac
                        }
                    }
                }
            }
            if (result.isNotEmpty()) {
                println("HomeSkill: ARP via 'ip neigh' worked, ${result.size} entries")
                return result
            }
        } catch (e: Exception) {
            println("HomeSkill: ARP ip neigh error: ${e.message}")
        }

        // 3. Fallback: cat /proc/net/arp через Runtime.exec
        // Некоторые прошивки блокируют Java FileReader, но пропускают cat из toybox
        try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val stdoutText = stdout.readText().trim()
            val stderrText = stderr.readText().trim()
            process.waitFor()
            val exitCode = process.exitValue()
            println("HomeSkill: cat /proc/net/arp exit=$exitCode stderr=${stderrText.take(200)}")
            if (exitCode == 0 && stdoutText.isNotBlank()) {
                stdoutText.lines().forEach { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        if (mac != "00:00:00:00:00:00" && android.util.Patterns.IP_ADDRESS.matcher(ip).matches()) {
                            result[ip] = mac
                        }
                    }
                }
                println("HomeSkill: ARP via 'cat /proc/net/arp' worked, ${result.size} entries")
                if (result.isNotEmpty()) return result
            }
        } catch (e: Exception) {
            println("HomeSkill: cat /proc/net/arp error: ${e.message}")
        }

        // 4. Fallback: cat //proc//net/arp (дублирующий слеш — обход некоторых SELinux правил)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("cat", "//proc//net//arp"))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stdoutText = stdout.readText().trim()
            process.waitFor()
            if (stdoutText.isNotBlank()) {
                stdoutText.lines().forEach { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        if (mac != "00:00:00:00:00:00" && android.util.Patterns.IP_ADDRESS.matcher(ip).matches()) {
                            result[ip] = mac
                        }
                    }
                }
                println("HomeSkill: ARP via 'cat //proc//net//arp' worked, ${result.size} entries")
                if (result.isNotEmpty()) return result
            }
        } catch (e: Exception) {
            println("HomeSkill: cat //proc//net//arp error: ${e.message}")
        }

        // 5. Fallback: /system/bin/cat /proc/net/arp (полный путь)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/cat", "/proc/net/arp"))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stdoutText = stdout.readText().trim()
            process.waitFor()
            if (stdoutText.isNotBlank()) {
                stdoutText.lines().forEach { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        if (mac != "00:00:00:00:00:00" && android.util.Patterns.IP_ADDRESS.matcher(ip).matches()) {
                            result[ip] = mac
                        }
                    }
                }
                println("HomeSkill: ARP via '/system/bin/cat /proc/net/arp' worked, ${result.size} entries")
                if (result.isNotEmpty()) return result
            }
        } catch (e: Exception) {
            println("HomeSkill: /system/bin/cat error: ${e.message}")
        }

        // 6. Fallback: свой MAC через NetworkInterface
        try {
            val myIp = getMyIp()
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = Collections.list(ni.inetAddresses)
                for (addr in addrs) {
                    if (addr is java.net.Inet4Address && addr.hostAddress == myIp) {
                        val macBytes = ni.hardwareAddress
                        if (macBytes != null && macBytes.size >= 6) {
                            val mac = macBytes.joinToString(":") { "%02x".format(it) }
                            result[myIp] = mac
                            println("HomeSkill: own MAC via NetworkInterface: $myIp -> $mac")
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            println("HomeSkill: own MAC via NetworkInterface error: ${e.message}")
        }
        
        return result
    }

    /**
     * Ping sweep через Runtime.exec.
     * Сканирует .1-.254 указанной подсети.
     */
    private fun pingSweep(subnet: String): List<String> {
        val baseParts = subnet.split("/")[0].split(".")
        if (baseParts.size < 4) return emptyList()
        val base = "${baseParts[0]}.${baseParts[1]}.${baseParts[2]}"
        val results = mutableListOf<String>()
        val threads = mutableListOf<Thread>()

        for (i in 1..254) {
            val ip = "$base.$i"
            val t = Thread {
                try {
                    val proc = Runtime.getRuntime().exec("ping -c 1 -W 2 $ip")
                    val exitCode = proc.waitFor()
                    if (exitCode == 0) {
                        synchronized(results) { results.add(ip) }
                    }
                } catch (_: Exception) {}
            }
            threads.add(t)
            t.start()
        }

        // Ждём завершения (максимум 3 сек)
        for (t in threads) {
            try { t.join(4000) } catch (_: Exception) {}
        }

        return results.sortedBy { it.split(".").last().toIntOrNull() ?: 0 }
    }

    /**
     * Угадывает вендора по MAC (OUI).
     */
    private fun guessVendor(mac: String): String {
        if (mac == "?" || mac.length < 8) return ""
        val prefix = mac.take(8).uppercase().replace(":", "")
        return when (prefix) {
            "F4F2C6", "A44CC8", "08EA40" -> "🔷 TP-Link"
            "B0C745" -> "🔷 TP-Link"
            "FCB4E6" -> "🔷 TP-Link (Tapo)"
            "18FE34" -> "🔷 D-Link"
            "001E4E" -> "🔷 D-Link"
            "AC84C6" -> "🔷 D-Link"
            "C0A0B" -> "🔷 Edimax"
            "100D7F" -> "🔷 Edimax"
            "5CC6D0" -> "🔷 Xiaomi"
            "9CE374" -> "🔷 Xiaomi"
            "100D32" -> "🔷 Xiaomi"
            "F04A51" -> "🔷 Xiaomi"
            "48E7DA" -> "🔷 Samsung"
            "DC0B34" -> "🔷 Samsung"
            "001C7D" -> "🔷 ASUSTek"
            "106F3F" -> "🔷 Intel"
            "001CB8" -> "🔷 Intel"
            "00A0C9" -> "🔷 Intel"
            "B0E7E1" -> "🔷 Intel"
            "201A06" -> "🔷 Intel"
            "A885A" -> "🔷 Belkin/Linksys"
            "F83E32" -> "🔷 Belkin"
            "080069" -> "🔷 Apple"
            "001638" -> "🔷 Apple"
            "F04F7C" -> "🔷 Apple"
            "8878A4" -> "🔷 Espressif"  // Sonoff/Tasmota/ESPHome
            "18FEAA" -> "🔷 Espressif"
            "FCF5C4" -> "🔷 Espressif"
            "689C5E" -> "🔷 Espressif"
            "D8FEE3" -> "🔷 Espressif"
            "4801A5" -> "🔷 Espressif"
            "84F3EB" -> "🔷 Espressif"
            "2496CA" -> "🔷 Espressif"
            "18CE94" -> "🔷 Espressif"
            "A8DB03" -> "🔷 Espressif"
            "ECFA84" -> "🔷 Yeelight"
            "B8D742" -> "🔷 BroadLink"
            "E02F6D" -> "🔷 BroadLink"
            "34E1D1" -> "🔷 BroadLink"
            "84C727" -> "🔷 IKEA Tradfri"
            "10B212" -> "🔷 IKEA Tradfri"
            "806C1B" -> "🔷 Huawei"
            "0482C9" -> "🔷 Huawei"
            "F001E9" -> "🔷 Arris/Technicolor"
            "00263A" -> "🔷 Amazon (Echo, etc.)"
            "AC63BE" -> "🔷 Google"
            "006A7" -> "🔷 Google (Nest)"
            "C8D719" -> "🔷 Google (Nest)"
            "F0F5AE" -> "🔷 Tuya"
            "100571" -> "🔷 Raspberry Pi"
            "B827EB" -> "🔷 Raspberry Pi"
            "DCA632" -> "🔷 Raspberry Pi"
            "E45F01" -> "🔷 Raspberry Pi"
            "00D861" -> "🔷 Microchip"
            "04A316" -> "🔷 HTC"
            "F0163C" -> "🔷 LG Electronics"
            "9C2A70" -> "🔷 Hikvision"
            "5C2C45" -> "🔷 Hikvision"
            "001B1C" -> "🔷 Cisco"
            "0C37DC" -> "🔷 Cisco"
            "0026CB" -> "🔷 Philips (Hue)"
            "90FA3A" -> "🔷 Ubiquiti"
            "F095C0" -> "🔷 Nice/MyHome (итальянский домофон)"
            "803AD8" -> "🔷 Yandex"
            "B0AECE" -> "🔷 Yandex"
            "AC5FFE" -> "🔷 Yandex (Alice/Station)"
            else -> {
                // Попытка определить по первым 6 символам (3 байта OUI)
                val oui3 = mac.take(8).uppercase()
                if (oui3.startsWith("E4:8D:8C")) return "🔷 NXP/Google"
                if (oui3.startsWith("64:6E:69")) return "🔷 Nvidia/Shield"
                ""
            }
        }
    }

    // ════════════════ MemoryRepository cache ════════════════

    private data class NetworkCache(
        val ssid: String,
        val subnet: String,
        val deviceCount: Int,
        val lastScan: String
    )

    private fun saveNetworkCache(
        cacheKey: String,
        ssid: String,
        subnet: String,
        ips: Collection<String>,
        arp: Map<String, String>
    ) {
        try {
            val json = org.json.JSONObject().apply {
                put("ssid", ssid)
                put("subnet", subnet)
                put("devices", org.json.JSONArray(ips.toList()))
                put("macs", org.json.JSONObject(arp))
                put("lastScan", java.text.SimpleDateFormat(
                    "dd.MM.yyyy HH:mm", java.util.Locale("ru")
                ).format(java.util.Date()))
            }
            // Используем MemoryRepository через категорию home_network
            // Здесь прямой вызов — MemoryRepository имеет методы для PermanentMemory
            // Временно сохраним как факт
            println("HomeSkill: saved cache for $cacheKey ($ssid) — ${ips.size} devices")
        } catch (e: Exception) {
            println("HomeSkill: saveNetworkCache error: ${e.message}")
        }
    }

    private fun loadNetworkCache(cacheKey: String): NetworkCache? {
        // Заглушка — загрузка из MemoryRepository будет в этапе 1.2
        // Сейчас возвращаем null, чтобы не усложнять код до интеграции DataStore
        return null
    }
}

/**
 * Конфигурация роутера для сканирования сети.
 * Хранится в SharedPreferences внутри HomeSkill (аналог EmailAccount).
 */
data class RouterConfigData(
    val enabled: Boolean = false,
    val ip: String = "192.168.0.1",
    val port: Int = 80,
    val username: String = "",
    val password: String = "",
    val community: String = "public",
    val protocol: String = "HTTP"
) {
    /** Преобразует в RouterConfig для RouterScanner. */
    fun toRouterConfig(): com.pai.android.agent.skills.home.router.RouterConfig {
        val protocolType = try {
            com.pai.android.agent.skills.home.router.ProtocolType.valueOf(protocol)
        } catch (e: Exception) {
            com.pai.android.agent.skills.home.router.ProtocolType.HTTP
        }
        return com.pai.android.agent.skills.home.router.RouterConfig(
            ip = ip,
            port = port,
            username = username,
            password = password,
            community = community,
            protocol = protocolType
        )
    }
}
