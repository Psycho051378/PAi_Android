package com.pai.android.agent.skills.home.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

/**
 * UPnP клиент для получения списка устройств в сети через роутер.
 *
 * Не требует пароля — работает через UPnP протокол (MiniUPnPd).
 * Поддерживается любым роутером с UPnP (TP-Link, D-Link, ASUS, Keenetic...).
 *
 * Flow:
 * 1. Получаем device description XML (rootDesc.xml)
 * 2. Парсим, находим URL для управления сервисом
 * 3. Шлём SOAP запрос для получения списка клиентов
 */
class UpnpRouterClient(
    private val routerConfig: RouterConfig
) : RouterClient {

    override val name: String = "UPnP"
    override val protocolType: ProtocolType = ProtocolType.HTTP

    companion object {
        private const val TIMEOUT_MS = 5000
        private val UPnP_PORTS = listOf(5000, 80, 49152, 49153, 49154)
        private val ROOT_DESC_PATHS = listOf("/rootDesc.xml", "/upnp/rootDesc.xml", "/device.xml", "/description.xml")
    }

    private var controlUrl: String? = null

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootXml = fetchRootDescription()
            if (rootXml != null) {
                println("UPnP: device description found")
                return@withContext true
            }
            println("UPnP: no device description found")
            false
        } catch (e: Exception) {
            println("UPnP testConnection error: ${e.message}")
            false
        }
    }

    override suspend fun getArpTable(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val rootXml = fetchRootDescription()
            if (rootXml == null) {
                println("UPnP: cannot get device description")
                return@withContext emptyMap()
            }

            // Парсим URL для управления сервисом
            val serviceUrls = parseControlUrls(rootXml)
            println("UPnP: found service URLs: $serviceUrls")

            // Пробуем каждый сервис для получения клиентов
            for ((serviceType, url) in serviceUrls) {
                try {
                    val result = when {
                        serviceType.contains("WANCommonInterfaceConfig") -> getViaWanCommonInterface(url)
                        serviceType.contains("WANIPConnection") -> getViaWanIpConnection(url)
                        serviceType.contains("Layer3Forwarding") -> getViaLayer3Forwarding(url)
                        else -> null
                    }
                    if (result != null && result.isNotEmpty()) {
                        println("UPnP: got ${result.size} entries via $serviceType")
                        return@withContext result
                    }
                } catch (e: Exception) {
                    println("UPnP: $serviceType error: ${e.message}")
                }
            }

            // Fallback: пробуем DHCP-клиентов через SOAP
            for ((serviceType, url) in serviceUrls) {
                if (serviceType.contains("WANCommonInterfaceConfig") || serviceType.contains("WANIPConnection")) {
                    try {
                        val result = getDhcpClients(url)
                        if (result.isNotEmpty()) {
                            println("UPnP: got ${result.size} DHCP clients via $serviceType")
                            return@withContext result
                        }
                    } catch (_: Exception) {}
                }
            }

            println("UPnP: all services failed")
            emptyMap()
        } catch (e: Exception) {
            println("UPnP getArpTable error: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Пробует получить rootDesc.xml через M-SEARCH (SSDP), затем через стандартные порты.
     */
    private fun fetchRootDescription(): String? {
        val ip = routerConfig.ip

        // Сначала пробуем M-SEARCH discovery — получим точный LOCATION от роутера
        try {
            val location = discoverViaMsSearch()
            if (location != null) {
                println("UPnP: discovered location: $location")
                val conn = URL(location).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val xml = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    if (xml.isNotBlank()) {
                        println("UPnP: got device description from M-SEARCH")
                        return xml
                    }
                }
                conn.disconnect()
            } else {
                println("UPnP: M-SEARCH returned no location")
            }
        } catch (e: Exception) {
            println("UPnP: M-SEARCH error: ${e.message}")
        }

        // Fallback: стандартные порты и пути
        for (port in UPnP_PORTS) {
            for (path in ROOT_DESC_PATHS) {
                try {
                    val url = URL("http://$ip:$port$path")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.requestMethod = "GET"

                    if (conn.responseCode == 200) {
                        val xml = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        if (xml.isNotBlank() && xml.contains("root")) {
                            println("UPnP: found rootDesc at http://$ip:$port$path")
                            return xml
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Парсит XML описание устройства, извлекает URL для управления сервисами.
     */

    /**
     * Делает M-SEARCH запрос через SSDP, получает LOCATION устройства.
     */
    private fun discoverViaMsSearch(): String? {
        return try {
            val socket = java.net.DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 3000

            val searchMsg = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)

            socket.send(java.net.DatagramPacket(searchMsg, searchMsg.size, java.net.InetAddress.getByName("239.255.255.250"), 1900))

            val buf = ByteArray(4096)
            val endTime = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < endTime) {
                try {
                    val packet = java.net.DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    // Ищем LOCATION в ответе
                    val locationMatch = Regex("LOCATION:\\s*(.*)", RegexOption.IGNORE_CASE).find(response)
                    if (locationMatch != null) {
                        val location = locationMatch.groupValues[1].trim()
                        if (location.isNotBlank()) {
                            socket.close()
                            return location
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
            socket.close()
            null
        } catch (e: Exception) {
            println("UPnP: M-SEARCH error: ")
            null
        }
    }

    private fun parseControlUrls(xml: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            // Парсим вручную (без XML парсера, для совместимости)
            // Ищем <service> блоки
            val serviceRegex = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
            val matches = serviceRegex.findAll(xml)
            
            for (match in matches) {
                val serviceBlock = match.groupValues[1]
                val serviceType = extractXmlTag(serviceBlock, "serviceType")
                val controlPath = extractXmlTag(serviceBlock, "controlURL")
                
                if (serviceType != null && controlPath != null) {
                    // Определяем base URL из controlUrl
                    val base = getBaseUrl(xml, controlPath)
                    result[serviceType] = base + controlPath
                }
            }
        } catch (e: Exception) {
            println("UPnP: parseControlUrls error: ${e.message}")
        }
        return result
    }

    /**
     * Извлекает значение XML тега.
     */
    private fun extractXmlTag(xml: String, tag: String): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    /**
     * Определяет базовый URL из XML или controlURL.
     */
    private fun getBaseUrl(xml: String, controlPath: String): String {
        // Если controlPath уже полный URL — используем его
        if (controlPath.startsWith("http")) return ""
        
        // Пробуем взять URLBase из XML
        val urlBase = extractXmlTag(xml, "URLBase")
        if (urlBase != null) return urlBase.trimEnd('/')
        
        // Иначе используем IP роутера и порт 1900 (MiniUPnPd)
        return "http://${routerConfig.ip}:1900"
    }

    /**
     * Получает список клиентов через WANCommonInterfaceConfig:1
     * SOAP действие: GetCommonLinkProperties
     */
    private fun getViaWanCommonInterface(controlUrl: String): Map<String, String>? {
        val soapBody = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <m:GetCommonLinkProperties xmlns:m="urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1" />
  </s:Body>
</s:Envelope>"""
        
        return sendSoapAndParse(controlUrl, "urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1#GetCommonLinkProperties", soapBody)
    }

    /**
     * Получает список клиентов через WANIPConnection:1
     * SOAP действие: GetStatusInfo
     */
    private fun getViaWanIpConnection(controlUrl: String): Map<String, String>? {
        val soapBody = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <m:GetStatusInfo xmlns:m="urn:schemas-upnp-org:service:WANIPConnection:1" />
  </s:Body>
</s:Envelope>"""
        
        return sendSoapAndParse(controlUrl, "urn:schemas-upnp-org:service:WANIPConnection:1#GetStatusInfo", soapBody)
    }

    /**
     * Получает список клиентов через Layer3Forwarding:1
     */
    private fun getViaLayer3Forwarding(controlUrl: String): Map<String, String>? {
        val soapBody = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <m:GetForwardingTable xmlns:m="urn:schemas-upnp-org:service:Layer3Forwarding:1" />
  </s:Body>
</s:Envelope>"""
        
        return sendSoapAndParse(controlUrl, "urn:schemas-upnp-org:service:Layer3Forwarding:1#GetForwardingTable", soapBody)
    }

    /**
     * Получает DHCP-клиентов через SOAP.
     */
    private fun getDhcpClients(controlUrl: String): Map<String, String> {
        // Пробуем разные SOAP действия для получения информации о клиентах
        val actions = listOf(
            "GetStatusInfo" to "urn:schemas-upnp-org:service:WANIPConnection:1",
            "GetCommonLinkProperties" to "urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1",
            "GetGenericPortMappingEntry" to "urn:schemas-upnp-org:service:WANIPConnection:1"
        )

        for ((action, service) in actions) {
            try {
                val soapBody = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <m:$action xmlns:m="$service" />
  </s:Body>
</s:Envelope>"""
                
                val result = sendSoap(controlUrl, "$service#$action", soapBody)
                if (result != null && (result.contains("NewDevice") || result.contains("Client"))) {
                    println("UPnP: DHCP info from $action (first 300): ${result.take(300)}")
                }
            } catch (_: Exception) {}
        }
        
        return emptyMap()
    }

    /**
     * Отправляет SOAP запрос и парсит ответ.
     */
    private fun sendSoapAndParse(url: String, soapAction: String, body: String): Map<String, String>? {
        val response = sendSoap(url, soapAction, body) ?: return null
        
        // Парсим MAC-адреса из ответа
        val result = mutableMapOf<String, String>()
        
        // Ищем IP и MAC в ответе (разные форматы)
        val macIpPattern = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}).*?([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}""")
        val macIpMatches = macIpPattern.findAll(response)
        for (match in macIpMatches) {
            val ip = match.groupValues[1]
            val mac = match.value.substringAfter(ip).trim().lowercase().replace("-", ":")
            if (mac.length == 17) result[ip] = mac
        }
        
        // Ищем только MAC-адреса
        val macPattern = Regex("""([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}""")
        val macMatches = macPattern.findAll(response)
        
        if (result.isEmpty() && macMatches.none()) {
            // Ничего не нашли, но ответ есть — возвращаем пустую мапу
            println("UPnP: no MAC/IP found in response, but got ${response.length} chars")
        }
        
        return result
    }

    /**
     * Отправляет SOAP запрос и возвращает тело ответа.
     */
    private fun sendSoap(url: String, soapAction: String, body: String): String? {
        try {
            val fullUrl = if (url.startsWith("http")) url else "http://${routerConfig.ip}:1900$url"
            
            val conn = URL(fullUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPAction", soapAction)
            
            // Отправляем тело
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            conn.outputStream.flush()
            conn.outputStream.close()
            
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                return response
            }
            
            // Читаем ошибку, если есть
            try {
                val errorBody = conn.errorStream?.bufferedReader()?.readText()
                if (errorBody != null && errorBody.isNotBlank()) {
                    println("UPnP: SOAP error ($responseCode): ${errorBody.take(200)}")
                }
            } catch (_: Exception) {}
            
            conn.disconnect()
            return null
        } catch (e: Exception) {
            println("UPnP: SOAP request error: ${e.message}")
            return null
        }
    }
}




