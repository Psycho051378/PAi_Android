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
import com.pai.android.data.repository.SmartHomeRepository
import com.pai.android.data.model.SmartHomeNetwork
import com.pai.android.data.model.SmartHomeDevice
import com.pai.android.data.model.DeviceType
import com.pai.android.data.model.DeviceProtocol
import com.pai.android.agent.skills.home.detector.DeviceTypeDetector
import com.pai.android.agent.skills.home.detector.DeviceNameGenerator
import com.pai.android.agent.skills.home.device.SmartHomeDispatcher
import com.pai.android.agent.skills.home.device.BlindDispatcher
import com.pai.android.agent.skills.home.device.CapabilityRegistry
import com.pai.android.agent.skills.home.device.DeviceCommand
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.Message
import com.pai.android.agent.skills.home.TcpScanner
import com.pai.android.agent.skills.home.HttpProber
import com.pai.android.agent.skills.home.MulticastDiscovery
import com.pai.android.agent.skills.home.UdpProber
import com.pai.android.agent.skills.home.FingerprintDb
import com.pai.android.agent.skills.home.router.RouterScanner
import com.pai.android.agent.skills.home.router.RouterScannerPython
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
    private val smartHomeRepository: SmartHomeRepository,
    private val smartHomeDispatcher: SmartHomeDispatcher,
    private val blindDispatcher: BlindDispatcher,
    private val aiRepository: AiRepository,
    private val okHttpClient: OkHttpClient,
    private var routerScanner: RouterScanner? = null,
    private val routerScannerPython: RouterScannerPython? = null
) : Skill {

    fun updateRouterScanner(scanner: RouterScanner) {
        this.routerScanner = scanner
    }

    override val name: String = "home"
    override val description: String =
        "Умный дом: сканирование Wi-Fi сети, идентификация устройств и управление"

    override fun getToolSchema(): String = """
{
  "type": "object",
  "properties": {
    "command": {
      "type": "string",
      "enum": ["home_scan", "home_control"],
      "description": "home_scan — просканировать Wi-Fi сеть и найти новые устройства. ОБЫЧНО НЕ НУЖНО, устройства уже найдены. Используй только если пользователь явно попросил сканировать.\nhome_control — запрос на управление устройствами. Используй для ЛЮБЫХ запросов про умный дом: включить/выключить, изменить цвет/яркость, запросить статус, переименовать"
    },
    "query": {
      "type": "string",
      "description": "Текст запроса пользователя. Примеры: «включи свет», «выключи пылесос», «сделай лампу 2 зелёной», «что с пылесосом», «отправь на базу», «статус», «сколько заряда», «переименуй пылесос в Уборщик», «сделай теплее», «яркость 70%»"
    }
  },
  "required": ["command"]
}
""".trimIndent()

    companion object {
        @Volatile var enabled: Boolean = true
        private const val PREFS_NAME = "home_skill"
        private const val PREF_ROUTER_CONFIG = "router_config"
    }

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

    override fun canHandle(intent: AgentIntent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        if (intent == AgentIntent.TOOL_OPERATION && params["command"] == name) return true
        val lower = query.lowercase()
        // Russian + English keywords for home automation
        return lower.contains("сканируй сеть") || lower.contains("что в сети") ||
                lower.contains("умный дом") || lower.contains("найди устройства") || lower.contains("найди устройств") ||
                lower.contains("настрой роутер") || lower.contains("роутер") &&
                (lower.contains("пароль") || lower.contains("настрой") || lower.contains("логин")) ||
                lower.contains("включи") || lower.contains("выключи") ||
                lower.contains("уборк") || lower.contains("clean") || lower.contains("vacuum") ||
                lower.contains("увлажнитель") || lower.contains("humidifier") ||
                lower.contains("пылесос") || lower.contains("запусти") ||
                lower.contains("на базу") || lower.contains("на зарядк") || lower.contains("заряжай") || lower.contains("зарядк") || lower.contains("заряд") ||
                lower.contains("статус") || lower.contains("яркость") || lower.contains("brightness") ||
                lower.contains("цвет") || lower.contains("color") ||
                lower.contains("lamp") || lower.contains("ламп") || lower.contains("свет") || lower.contains("light") ||
                lower.contains("тепл") || lower.contains("cold") || lower.contains("warm") ||
                lower.contains("home") || lower.contains("arp") || lower.contains("smart home") ||
                lower.contains("scan network") || lower.contains("scan") ||
                lower.contains("find devices") || lower.contains("discover") ||
                lower.contains("turn on") || lower.contains("turn off") || lower.contains("switch on") || lower.contains("switch off") ||
                lower.contains("start") || lower.contains("begin") ||
                lower.contains("configure router") || lower.contains("router password") || lower.contains("router setup") || lower.contains("router config") ||
                lower.contains("show devices") || lower.contains("list devices") || lower.contains("what devices") || lower.contains("status")
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        try {
            // Собираем query из params: либо явный query, либо command + остальные поля
            val command = params["command"] as? String ?: ""
            val query = params["query"] as? String ?: params["q"] as? String ?: ""

            // Если явный запрос есть — классифицируем
            if (query.isNotBlank()) {
                println("HomeSkill: execute with query='$query'")
                return@withContext when (classifyIntent(query)) {
                    IntentType.SCAN -> doScan()
                    IntentType.STATUS -> getStatus()
                    IntentType.CONFIGURE_ROUTER -> configureRouter(query)
                    IntentType.CONTROL -> doControl(query)
                    else -> doControl(query)
                }
            }

            // Если query пустой, но есть command — строим из params
            val deviceName = params["device_name"] as? String ?: params["name"] as? String ?: ""
            val deviceIp = params["device_ip"] as? String ?: params["ip"] as? String ?: ""

            val computedQuery = when (command) {
                "home_scan" -> return@withContext doScan()
                "home_control" -> return@withContext doControl(deviceName)
                "get_status" -> "статус ${deviceName.ifBlank { deviceIp }}"
                "turn_on" -> "включи ${deviceName.ifBlank { deviceIp }}"
                "turn_off" -> "выключи ${deviceName.ifBlank { deviceIp }}"
                "charge" -> "отправь на базу ${deviceName.ifBlank { deviceIp }}"
                "set_brightness" -> "яркость ${params["level"] ?: 50} ${deviceName.ifBlank { deviceIp }}"
                "set_rgb" -> "цвет ${params["color"] ?: params["r"] ?: ""} ${deviceName.ifBlank { deviceIp }}"
                else -> {
                    // Неизвестная команда — собираем из всех params
                    params.filterKeys { it !in listOf("command", "tool_name", "toolCallId") }
                        .map { "${it.key}=${it.value}" }
                        .joinToString(" ")
                }
            }

            return@withContext if (computedQuery.isNotBlank()) {
                println("HomeSkill: computed query from params: '$computedQuery'")
                doControl(computedQuery)
            } else {
                println("HomeSkill: empty query and unknown command, defaulting to scan")
                doScan()
            }
        } catch (e: Exception) {
            SkillResult.Error(message = "HomeSkill error: ${e.message ?: "Unknown"}")
        }
    }

    private enum class IntentType { SCAN, STATUS, CONFIGURE_ROUTER, CONTROL, UNKNOWN }

    private fun classifyIntent(query: String): IntentType {
        val lower = query.lowercase()
        return when {
            // SCAN — Russian + English
            lower.contains("сканируй") || lower.contains("найди устройств") ||
            lower.contains("scan") || lower.contains("discover") || lower.contains("find devices") ||
            lower.contains("network scan") -> IntentType.SCAN
            // STATUS — Russian + English
            lower.contains("что в сети") || lower.contains("статус") ||
            lower.contains("покажи") || lower.contains("список") ||
            lower.contains("status") || lower.contains("show devices") || lower.contains("list devices") ||
            lower.contains("what devices") || lower.contains("what's in the network") ||
            lower.contains("show me devices") -> IntentType.STATUS
            // CONFIGURE ROUTER — Russian + English
            lower.contains("настрой роутер") || lower.contains("пароль от роутер") ||
            lower.contains("роутер парол") || lower.contains("логин роутер") ||
            lower.contains("configure router") || lower.contains("router password") ||
            lower.contains("router setup") || lower.contains("router config") -> IntentType.CONFIGURE_ROUTER
            // CONTROL — Russian + English
            lower.contains("включи") || lower.contains("выключи") ||
            lower.contains("уборк") || lower.contains("clean") || lower.contains("vacuum") ||
            lower.contains("увлажнитель") || lower.contains("humidifier") ||
            lower.contains("пылесос") || lower.contains("запусти") ||
            lower.contains("turn on") || lower.contains("turn off") ||
            lower.contains("switch on") || lower.contains("switch off") ||
            lower.contains("start") || lower.contains("begin") ||
            lower.contains("on") || lower.contains("off") -> IntentType.CONTROL
            else -> IntentType.UNKNOWN
        }
    }

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

        val aliveHosts = pingSweep(subnet)
        println("HomeSkill: starting TCP sweep (port 80) for all hosts...")
        val tcpAlive = tcpSweep(subnet)
        println("HomeSkill: TCP sweep found ${tcpAlive.size} additional hosts")
        println("HomeSkill: starting mDNS/SSDP multicast discovery...")
        val mdnsDevices = MulticastDiscovery.discover()
        println("HomeSkill: mDNS/SSDP found ${mdnsDevices.size} devices")
        val mdnsByIp = mdnsDevices.associateBy { it.ip }
        val arpEntries = readArpTable()
        val allIps = (arpEntries.keys + aliveHosts + tcpAlive + mdnsDevices.map { it.ip }).toSet().sorted()

        println("HomeSkill: starting UDP/CoAP/MQTT probe for ${allIps.size} hosts...")
        val udpResults = UdpProber.probeAll(allIps)
        println("HomeSkill: UDP probe found ${udpResults.size} devices")

        println("HomeSkill: starting NetBIOS MAC probe for ${allIps.size} hosts...")
        val netbiosMacs = mutableMapOf<String, String>()
        for (ip in allIps) {
            val mac = UdpProber.discoverNetbios(ip)
            if (mac != null) netbiosMacs[ip] = mac
        }
        println("HomeSkill: NetBIOS found ${netbiosMacs.size} MACs")

        println("HomeSkill: starting TCP port scan for ${allIps.size} hosts...")
        val openPorts = TcpScanner.scanPorts(allIps)
        println("HomeSkill: TCP scan done, ${openPorts.size} hosts with open ports")

        val httpInfo = httpProber.probeAll(openPorts)

        var routerArp = emptyMap<String, String>()
        var deviceNames = emptyMap<String, String>()
        try {
            val routerConfig = getRouterConfig()
            val routerScan = routerScanner
            if (routerScan != null && routerConfig != null && routerConfig.enabled) {
                println("HomeSkill: trying router scan via ${routerConfig.protocol}")
                val config = routerConfig.toRouterConfig()
                routerArp = routerScan.scan(config)
                println("HomeSkill: router ARP found ${routerArp.size} entries")
                try {
                    val py = routerScannerPython
                    if (py != null) {
                        val detailed = py.scanDetailed(config)
                        if (detailed.isNotEmpty()) {
                            deviceNames = detailed.associate { it.ip to it.name }
                            println("HomeSkill: got ${deviceNames.size} device names from Python")
                        }
                    }
                } catch (e: Exception) {
                    println("HomeSkill: Python detailed scan error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("HomeSkill: router scan error: ${e.message}")
        }

        val sb = StringBuilder()
        val deviceEntries = mutableListOf<DeviceEntry>()

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
                val httpProbeResult = httpInfo[ip]
                val udpProbeResult = udpResults[ip]
                val mdnsDevice = mdnsByIp[ip]
                val macRaw = httpProbeResult?.mac ?: udpProbeResult?.mac ?: mdnsDevice?.mac
                    ?: netbiosMacs[ip] ?: arpEntries[ip] ?: routerArp[ip] ?: "?"
                val mac = if (macRaw == "00:00:00:00:00:00") "?" else macRaw
                val vendor = if (mac != "?") guessVendor(mac) else ""

                val hostname = deviceNames[ip] ?: ""
                val httpFingerprint = httpProbeResult?.fingerprint
                val udpFingerprint = udpProbeResult?.fingerprint

                val typeInfo = DeviceTypeDetector.detect(
                    hostname = hostname,
                    openPorts = ports ?: emptyList(),
                    udpFingerprint = udpFingerprint,
                    httpFingerprint = httpFingerprint
                )
                val controlPort = when (typeInfo.protocol) {
                    DeviceProtocol.WIZ -> 38899
                    DeviceProtocol.YEELIGHT -> 55443
                    else -> ports?.firstOrNull() ?: 0
                }
                val capabilities = DeviceTypeDetector.getCapabilities(typeInfo.deviceType, typeInfo.protocol)

                // Собираем метаданные как простой JSON-строку
                val metaJson = org.json.JSONObject().apply {
                    put("httpTitle", httpProbeResult?.title ?: "")
                    put("httpServer", httpProbeResult?.server ?: "")
                    put("httpFingerprint", httpFingerprint ?: "")
                    put("udpFingerprint", udpFingerprint ?: "")
                    put("udpProtocol", udpProbeResult?.protocol ?: "")
                    put("mdnsServiceType", mdnsDevice?.serviceType ?: "")
                    put("mdnsFriendlyName", mdnsDevice?.friendlyName ?: "")
                    put("mdnsHostname", mdnsDevice?.hostname ?: "")
                }.toString()

                deviceEntries.add(
                    DeviceEntry(
                        ip = ip,
                        mac = mac,
                        hostname = hostname,
                        deviceTypeName = typeInfo.deviceType.name,
                        protocolName = typeInfo.protocol.name,
                        port = controlPort,
                        capabilitiesJson = org.json.JSONArray(capabilities).toString(),
                        vendor = vendor.trim(),
                        metadataJson = metaJson
                    )
                )

                sb.appendLine("${i + 1}. **$ip**")
                val deviceLabel = when (typeInfo.deviceType) {
                    DeviceType.LIGHT -> "💡 Свет"
                    DeviceType.VACUUM -> "🧹 Пылесос"
                    DeviceType.HUMIDIFIER -> "💨 Увлажнитель"
                    DeviceType.SPEAKER -> "🔊 Колонка"
                    DeviceType.TV -> "📺 Телевизор"
                    DeviceType.ESP_DEVICE -> "🔧 ESP-устройство"
                    DeviceType.ROUTER -> "📡 Роутер"
                    DeviceType.COMPUTER -> "🖥 Компьютер"
                    DeviceType.PHONE -> "📱 Телефон"
                    DeviceType.WEARABLE -> "⌚️ Гаджет"
                    DeviceType.OTHER -> {
                        when (httpFingerprint) {
                            "sonoff_tasmota" -> "💡 Sonoff/Tasmota"
                            "shelly" -> "💡 Shelly"
                            "home_assistant" -> "🏠 Home Assistant"
                            "ip_camera" -> "📹 IP-камера"
                            "xiaomi" -> "🔷 Xiaomi"
                            "philips_hue" -> "💡 Philips Hue"
                            "broadlink" -> "📡 BroadLink"
                            "plex" -> "🎬 Plex"
                            "apple" -> "🍎 Apple"
                            "mqtt_device" -> "📡 MQTT устройство"
                            "web_server" -> "🖥 Веб-сервер"
                            else -> when (udpFingerprint) {
                                "xiaomi_plug" -> "🔌 Розетка Xiaomi"
                                "xiaomi_gateway" -> "🏠 Шлюз Xiaomi"
                                "xiaomi_airpurifier" -> "🌬 Очиститель"
                                "xiaomi_ac" -> "❄️ Кондиционер"
                                "xiaomi_fan" -> "🌀 Вентилятор"
                                "xiaomi_switch" -> "🔘 Выключатель"
                                "xiaomi_unknown" -> "🔷 Xiaomi"
                                else -> FingerprintDb.identify(ip, ports, httpProbeResult, mdnsDevice?.serviceType, null) ?: "📡 Устройство"
                            }
                        }
                    }
                }
                sb.appendLine("   $deviceLabel")
                sb.appendLine("   MAC: $mac $vendor")
                if (hostname.isNotBlank() && hostname != "?") {
                    sb.appendLine("   📛 $hostname")
                }
                if (ports != null && ports.isNotEmpty()) {
                    sb.appendLine("   🔓 Порты: ${ports.joinToString(", ")}")
                }
                if (httpProbeResult?.title != null) {
                    sb.appendLine("   🌐 ${httpProbeResult.title}")
                }
                if (httpProbeResult?.server != null) {
                    sb.appendLine("   Сервер: ${httpProbeResult.server}")
                }
                val mdns = mdnsByIp[ip]
                if (mdns != null && deviceLabel == "📡 Устройство") {
                    when (mdns.serviceType) {
                        "smart_tv" -> sb.appendLine("   📺 Телевизор")
                        "audio_receiver" -> sb.appendLine("   🔊 Аудиосистема")
                        "printer" -> sb.appendLine("   🖨 Принтер")
                        "nas" -> sb.appendLine("   💾 NAS")
                        "gateway" -> sb.appendLine("   📡 Шлюз")
                    }
                }
                if (mdns?.friendlyName != null) sb.appendLine("   📛 ${mdns.friendlyName}")
                if (mdns?.hostname != null) sb.appendLine("   Имя: ${mdns.hostname}")
            }
        }

        try {
            saveScanResults(ssid, gatewayIp, subnet, deviceEntries)
        } catch (e: Exception) {
            println("HomeSkill: saveScanResults error: ${e.message}")
        }

        return SkillResult.Success(message = sb.toString(), responseType = ResponseType.TEXT)
    }

    private data class DeviceEntry(
        val ip: String,
        val mac: String,
        val hostname: String,
        val deviceTypeName: String,
        val protocolName: String,
        val port: Int,
        val capabilitiesJson: String,
        val vendor: String,
        val metadataJson: String
    )

    private suspend fun saveScanResults(
        ssid: String,
        gatewayIp: String?,
        subnet: String?,
        deviceEntries: List<DeviceEntry>
    ) {
        var network = smartHomeRepository.getNetworkBySsid(ssid)
        val now = System.currentTimeMillis()
        if (network != null) {
            network = network.copy(lastScan = now, gatewayIp = gatewayIp, subnet = subnet)
        } else {
            network = SmartHomeNetwork(
                ssid = ssid,
                gatewayIp = gatewayIp,
                subnet = subnet,
                lastScan = now
            )
        }
        smartHomeRepository.saveNetwork(network)
        val networkId = network.id
        println("HomeSkill: saved network '$ssid' (id=$networkId)")

        val existingDevices = smartHomeRepository.getDevices(networkId)
        val allDevices = mutableListOf<SmartHomeDevice>()

        val llmCall: (suspend (String) -> String?)? = { prompt ->
            try {
                val response = aiRepository.sendMessage(
                    messages = listOf(Message.createUserMessage("naming", prompt)),
                    systemPrompt = "Ты генератор имён для устройств. Отвечай только именем.",
                    memoryContext = ""
                )
                response.getOrNull()?.text?.trim()
            } catch (e: Exception) {
                println("HomeSkill: LLM naming error: ${e.message}")
                null
            }
        }

        for (entry in deviceEntries) {
            if (entry.mac == "?" || entry.mac.isBlank()) continue

            val existing = existingDevices.find { it.mac.equals(entry.mac, ignoreCase = true) }
            val displayName = if (existing != null && existing.displayName.isNotBlank()) {
                existing.displayName
            } else {
                DeviceNameGenerator.generateName(
                    deviceType = entry.deviceTypeName,
                    existingDevices = existingDevices + allDevices,
                    metadata = entry.metadataJson,
                    llmCall = llmCall
                )
            }

            val device = SmartHomeDevice(
                networkId = networkId,
                mac = entry.mac.uppercase().replace("-", ":"),
                ip = entry.ip,
                hostname = entry.hostname,
                displayName = displayName,
                deviceType = entry.deviceTypeName,
                protocol = entry.protocolName,
                port = entry.port,
                capabilities = entry.capabilitiesJson,
                present = true,
                firstSeen = existing?.firstSeen ?: now,
                lastSeen = now,
                vendor = entry.vendor,
                metadata = entry.metadataJson,
                // Сохраняем deviceConfig (токены, настройки) из старой записи,
                // иначе каждое сканирование будет стирать токен!
                deviceConfig = existing?.deviceConfig ?: "{}"
            )
            allDevices.add(device)
        }

        smartHomeRepository.updateFromScan(networkId, allDevices)
        println("HomeSkill: saved ${allDevices.size} devices to DB")
    }

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

        val network = if (ssid != null) smartHomeRepository.getNetworkBySsid(ssid) else null
        val deviceCount = if (network != null) smartHomeRepository.getDeviceCount(network.id) else 0

        val sb = StringBuilder()
        if (network != null && deviceCount > 0) {
            sb.appendLine("🏠 **${network.ssid}** (БД)")
            sb.appendLine("┌────────────────────────────────────")
            sb.appendLine("│ IP: $myIp")
            sb.appendLine("│ Подсеть: ${network.subnet ?: getSubnet()}")
            sb.appendLine("│ Устройств в БД: $deviceCount")
            sb.appendLine("│ Последнее сканирование: ${
                java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru")
                ).format(java.util.Date(network.lastScan))}")
            sb.appendLine("└────────────────────────────────────")
            sb.appendLine()

            // Выводим список устройств из БД
            val devices = smartHomeRepository.getDevices(network.id)
            val typeEmojis = mapOf(
                "LIGHT" to "💡",
                "VACUUM" to "🧹",
                "HUMIDIFIER" to "💨",
                "SPEAKER" to "🔊",
                "TV" to "📺",
                "ESP_DEVICE" to "🔧",
                "ROUTER" to "📡",
                "COMPUTER" to "🖥",
                "PHONE" to "📱",
                "WEARABLE" to "⌚️",
                "OTHER" to "📡"
            )
            var idx = 1
            for (device in devices) {
                val emoji = typeEmojis[device.deviceType] ?: "📡"
                val ip = device.ip
                val name = device.displayName.ifBlank { device.hostname.ifBlank { ip } }
                val typeLabel = try {
                    DeviceType.valueOf(device.deviceType).displayName
                } catch (e: Exception) { device.deviceType }
                val proto = try {
                    DeviceProtocol.valueOf(device.protocol).displayName
                } catch (e: Exception) { "" }

                sb.append("$idx. $emoji **$name**")
                if (proto.isNotBlank()) sb.append(" ($proto)")
                sb.appendLine("")
                sb.appendLine("   IP: $ip")
                if (device.vendor.isNotBlank()) sb.appendLine("   ${device.vendor}")
                idx++
            }
            sb.appendLine()
            sb.appendLine("ℹ️ Для свежих данных скажи «сканируй сеть».")
        } else {
            sb.appendLine("ℹ️ Нет сохранённых данных для текущей сети.")
            sb.appendLine("Скажи «сканируй сеть», чтобы начать сканирование.")
        }

        return SkillResult.Success(message = sb.toString(), responseType = ResponseType.TEXT)
    }

    private suspend fun configureRouter(query: String): SkillResult {
        val lower = query.lowercase()
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

    private suspend fun doControl(query: String): SkillResult {
        val ssid = getSsid()
        if (ssid == null) {
            return SkillResult.Success(
                message = "❌ Не удалось определить текущую сеть.",
                responseType = ResponseType.TEXT
            )
        }
        val network = smartHomeRepository.getNetworkBySsid(ssid)

        // ==================== ADDRESSED MODE ====================
        // Если есть сохранённая сеть с управляемыми устройствами — используем её
        if (network != null) {
            try {
                val devices = smartHomeRepository.getManageableDevices(network.id)
                if (devices.isNotEmpty()) {
                    // 1. LLM-driven dispatch
                    val commands = generateLLMCommands(query, devices)
                    if (commands != null && commands.isNotEmpty()) {
                        val result = smartHomeDispatcher.dispatchCommands(commands)
                        println("HomeSkill: LLM dispatch result: ${result.take(100)}")
                        return SkillResult.Success(message = result, responseType = ResponseType.TEXT)
                    }

                    // 2. Fallback к ControlIntentParser
                    println("HomeSkill: LLM dispatch failed, fallback to ControlIntentParser")
                    val result = smartHomeDispatcher.dispatch(query, network.id)
                    return SkillResult.Success(message = result, responseType = ResponseType.TEXT)
                }
                // Устройства есть в БД, но ни одно не управляемое — проваливаемся в Blind
                println("HomeSkill: network exists but no manageable devices, trying blind mode")
            } catch (e: Exception) {
                println("HomeSkill: addressed dispatch error: ${e.message}")
            }
        }

        // ==================== BLIND MODE ====================
        // Нет базы для сети или нет управляемых устройств → работаем вслепую
        return doBlindControl(query)
    }

    /**
     * Управление устройствами вслепую — без базы устройств.
     * - WiZ: broadcast на 255.255.255.255:38899
     * - Yeelight: ping sweep подсети + probe на порт 55443
     */
    private suspend fun doBlindControl(query: String): SkillResult {
        return try {
            println("HomeSkill: blind control: query='$query'")

            // Получаем список IP для Yeelight probe (если есть)
            val subnet = getSubnet()
            val ips = if (subnet.isNotBlank()) {
                val baseParts = subnet.split("/")[0].split(".")
                if (baseParts.size == 4) {
                    (1..254).map { "${baseParts[0]}.${baseParts[1]}.${baseParts[2]}.$it" }
                } else emptyList()
            } else emptyList()

            val result = blindDispatcher.dispatchQuery(query, ips)
            SkillResult.Success(message = result, responseType = ResponseType.TEXT)
        } catch (e: Exception) {
            SkillResult.Success(
                message = "❌ Blind-управление: ${e.message ?: "неизвестная ошибка"}",
                responseType = ResponseType.TEXT
            )
        }
    }

    /**
     * Сгенерировать команды управления через LLM.
     * Отправляет в AI описание устройств + запрос пользователя,
     * получает JSON-массив DeviceCommand.
     */
    private suspend fun generateLLMCommands(
        query: String,
        devices: List<SmartHomeDevice>
    ): List<DeviceCommand>? {
        val devicesPrompt = CapabilityRegistry.buildDevicesPrompt(devices)

        val systemPrompt = """
Ты — система управления умным домом. Твоя задача — преобразовать запрос пользователя в JSON-команды для устройств.

ПРАВИЛА:
1. Всегда отвечай ТОЛЬКО JSON-массивом команд.
2. Никаких пояснений, markdown, комментариев — только JSON.
3. Если пользователь сказал «включи свет» без уточнений — включи ВСЕ устройства типа «Свет».
4. Если пользователь указал конкретное имя (например «лампу 2») — найди устройство с таким именем.
5. Поддерживаются названия цветов: красный, синий, зелёный, жёлтый, фиолетовый, белый, тёплый, и т.д.
6. Для изменения температуры: «теплее» = 3000K, «холоднее» = 5500K, «тёплый свет» = 2700K, «холодный свет» = 6500K.
7. Если пользователь просит переименовать — используй "set_name".
        8. «отправь на базу», «заряжайся», «возвращайся на базу», «заряди», «на зарядку», «зарядить» → "charge".
9. «статус», «что с [устройством]», «сколько заряда», «проверь [устройство]» → "get_status".

ФОРМАТ ОТВЕТА:
[
  {
    "deviceId": "MAC-адрес-устройства",
    "action": "turn_on|turn_off|set_brightness|set_color_temp|set_rgb|set_mode|set_fan_speed|charge|get_status|set_name",
    "params": {
      // для set_rgb: "color": "зелёный" или "r": 0, "g": 255, "b": 0
      // для set_brightness: "level": 70
      // для set_color_temp: "temp": 3500
      // для charge: {} (без параметров)
      // для get_status: {} (без параметров)
      // для set_name: "name": "Новое имя"
    }
  }
]
""".trimIndent()

        val prompt = buildString {
            appendLine(devicesPrompt)
            appendLine()
            appendLine("Запрос пользователя: \"$query\"")
            appendLine()
            appendLine("Ответь ТОЛЬКО JSON-массивом команд:")
        }

        return try {
            val response = aiRepository.sendMessage(
                messages = listOf(Message.createUserMessage("home_control", prompt)),
                systemPrompt = systemPrompt,
                memoryContext = ""
            )

            val text = response.getOrNull()?.text ?: ""
            if (text.isBlank()) {
                println("HomeSkill: LLM вернул пустой ответ")
                return null
            }

            println("HomeSkill: LLM response: ${text.take(500)}")
            val commands = smartHomeDispatcher.parseCommandsFromJson(text)
            println("HomeSkill: parsed ${commands.size} commands")
            commands
        } catch (e: Exception) {
            println("HomeSkill: LLM command generation error: ${e.message}")
            null
        }
    }

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
            if (threadCount >= 30) {
                try { t.join(2000) } catch (_: Exception) {}
                threadCount = 0
            }
        }
        return results.sortedBy { it.split(".").last().toIntOrNull() ?: 0 }
    }

    private fun getSubnet(): String {
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

    private fun getGatewayIp(): String? {
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

    private fun readArpTable(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                reader.readLine()
                reader.forEachLine { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        if (mac != "00:00:00:00:00:00") result[ip] = mac
                    }
                }
            }
            if (result.isNotEmpty()) return result
        } catch (e: Exception) {
            println("HomeSkill: ARP /proc/net/arp error: ${e.message}")
        }
        try {
            val process = Runtime.getRuntime().exec("ip neigh")
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val stdoutText = stdout.readText().trim()
            val stderrText = stderr.readText().trim()
            process.waitFor()
            if (process.exitValue() == 0 && stdoutText.isNotBlank()) {
                stdoutText.lines().forEach { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 5 && parts[3] == "lladdr") {
                        val ip = parts[0]
                        val mac = parts[4]
                        if (mac != "00:00:00:00:00:00") result[ip] = mac
                    }
                }
            }
            if (result.isNotEmpty()) return result
        } catch (e: Exception) {
            println("HomeSkill: ARP ip neigh error: ${e.message}")
        }
        try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            stdout.readText().trim().also { stdoutText ->
                val stderrText = stderr.readText().trim()
                process.waitFor()
                if (process.exitValue() == 0 && stdoutText.isNotBlank()) {
                    stdoutText.lines().forEach { line ->
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 4) {
                            val ip = parts[0]
                            val mac = parts[3]
                            if (mac != "00:00:00:00:00:00" && android.util.Patterns.IP_ADDRESS.matcher(ip).matches()) result[ip] = mac
                        }
                    }
                }
            }
            if (result.isNotEmpty()) return result
        } catch (e: Exception) {
            println("HomeSkill: cat /proc/net/arp error: ${e.message}")
        }
        try {
            val process = Runtime.getRuntime().exec(arrayOf("cat", "//proc//net//arp"))
            val stdoutText = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            process.waitFor()
            if (stdoutText.isNotBlank()) {
                stdoutText.lines().forEach { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        if (mac != "00:00:00:00:00:00" && android.util.Patterns.IP_ADDRESS.matcher(ip).matches()) result[ip] = mac
                    }
                }
            }
        } catch (e: Exception) {
            println("HomeSkill: cat //proc//net//arp error: ${e.message}")
        }
        return result
    }

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
                    if (proc.waitFor() == 0) synchronized(results) { results.add(ip) }
                } catch (_: Exception) {}
            }
            threads.add(t)
            t.start()
        }
        for (t in threads) try { t.join(4000) } catch (_: Exception) {}
        return results.sortedBy { it.split(".").last().toIntOrNull() ?: 0 }
    }

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
            "5CC6D0", "9CE374", "100D32", "F04A51" -> "🔷 Xiaomi"
            "48E7DA", "DC0B34" -> "🔷 Samsung"
            "001C7D" -> "🔷 ASUSTek"
            "106F3F", "001CB8", "00A0C9", "B0E7E1", "201A06" -> "🔷 Intel"
            "A885A" -> "🔷 Belkin/Linksys"
            "F83E32" -> "🔷 Belkin"
            "080069", "001638", "F04F7C" -> "🔷 Apple"
            "8878A4", "18FEAA", "FCF5C4", "689C5E", "D8FEE3",
            "4801A5", "84F3EB", "2496CA", "18CE94", "A8DB03" -> "🔷 Espressif"
            "ECFA84" -> "🔷 Yeelight"
            "B8D742", "E02F6D", "34E1D1" -> "🔷 BroadLink"
            "84C727", "10B212" -> "🔷 IKEA Tradfri"
            "806C1B", "0482C9" -> "🔷 Huawei"
            "F001E9" -> "🔷 Arris/Technicolor"
            "00263A" -> "🔷 Amazon"
            "AC63BE", "006A7", "C8D719" -> "🔷 Google"
            "F0F5AE" -> "🔷 Tuya"
            "100571", "B827EB", "DCA632", "E45F01" -> "🔷 Raspberry Pi"
            "00D861" -> "🔷 Microchip"
            "04A316" -> "🔷 HTC"
            "F0163C" -> "🔷 LG Electronics"
            "9C2A70", "5C2C45" -> "🔷 Hikvision"
            "001B1C", "0C37DC" -> "🔷 Cisco"
            "0026CB" -> "🔷 Philips (Hue)"
            "90FA3A" -> "🔷 Ubiquiti"
            "803AD8", "B0AECE", "AC5FFE" -> "🔷 Yandex"
            else -> ""
        }
    }

    // ════════════════ Cache (deprecated) ════════════════

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
        println("HomeSkill: saveNetworkCache deprecated, using SmartHomeRepository")
    }

    private fun loadNetworkCache(cacheKey: String): NetworkCache? {
        return null
    }
}

data class RouterConfigData(
    val enabled: Boolean = false,
    val ip: String = "192.168.0.1",
    val port: Int = 80,
    val username: String = "",
    val password: String = "",
    val community: String = "public",
    val protocol: String = "HTTP"
) {
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
