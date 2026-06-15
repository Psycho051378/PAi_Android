package com.pai.android.agent.skills.home.router

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Результат проверки соединения с роутером.
 *
 * @param success true если соединение успешно и роутер отвечает.
 * @param deviceCount количество устройств в ARP-таблице.
 * @param protocol протокол, через который удалось подключиться.
 * @param error сообщение об ошибке (если success = false).
 */
data class TestResult(
    val success: Boolean,
    val deviceCount: Int = 0,
    val protocol: ProtocolType? = null,
    val error: String? = null
)

/**
 * Оркестратор для сканирования сети через роутер.
 *
 * Пробует все протоколы по очереди (HTTP → SNMP → SSH).
 * Конфигурация передаётся напрямую (хранится в HomeSkill через SharedPreferences).
 */
@Singleton
class RouterScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "RouterScanner"
    }

    /**
     * Сканирует сеть через роутер по заданной конфигурации.
     * Пробует протоколы: HTTP, SNMP, SSH (в порядке приоритета).
     */
    suspend fun scan(config: RouterConfig): Map<String, String> = withContext(Dispatchers.IO) {
        println("RouterScanner: scan started for ${config.ip} via ${config.protocol}")
        scanWithConfig(config)
    }

    /**
     * Сканирует с конкретной конфигурацией.
     * Пробует протоколы: HTTP, SNMP, SSH (в порядке надёжности).
     */
    private suspend fun scanWithConfig(config: RouterConfig): Map<String, String> {
        // Сначала пробуем UPnP (не требует пароля, работает на любом роутере)
        try {
            println("RouterScanner: trying UPnP first (no password needed)...")
            val upnpClient = UpnpRouterClient(config)
            if (upnpClient.testConnection()) {
                println("RouterScanner: connected via UPnP")
                val arp = upnpClient.getArpTable()
                if (arp.isNotEmpty()) {
                    println("RouterScanner: got ${arp.size} entries via UPnP")
                    return arp
                } else {
                    println("RouterScanner: UPnP returned empty ARP, falling back")
                }
            } else {
                println("RouterScanner: UPnP connection failed")
            }
        } catch (e: Exception) {
            println("RouterScanner: UPnP error: ${e.message}")
        }

        val protocolsToTry = when (config.protocol) {
            ProtocolType.HTTP -> listOf(ProtocolType.HTTP, ProtocolType.SNMP, ProtocolType.SSH)
            ProtocolType.SNMP -> listOf(ProtocolType.SNMP, ProtocolType.HTTP, ProtocolType.SSH)
            ProtocolType.SSH -> listOf(ProtocolType.SSH, ProtocolType.SNMP, ProtocolType.HTTP)
        }

        println("RouterScanner: trying protocols: ${protocolsToTry.joinToString { it.name }}")

        for (protocol in protocolsToTry) {
            try {
                val client = createClient(config, protocol)
                val connected = client.testConnection()
                if (connected) {
                    println("RouterScanner: connected via ${protocol.name}")
                    val arp = client.getArpTable()
                    println("RouterScanner: got ${arp.size} entries via ${protocol.name}")
                    return arp
                } else {
                    println("RouterScanner: ${protocol.name} connection failed")
                }
            } catch (e: Exception) {
                println("RouterScanner: ${protocol.name} error: ${e.message}")
            }
        }

        println("RouterScanner: all protocols failed")
        return emptyMap()
    }

    /**
     * Тестирует соединение с роутером по заданной конфигурации.
     * Возвращает TestResult с подробной информацией.
     */
    suspend fun testConnection(config: RouterConfig): TestResult = withContext(Dispatchers.IO) {
        println("RouterScanner: testConnection to ${config.ip} via ${config.protocol}")

        try {
            val client = createClient(config, config.protocol)
            val connected = client.testConnection()

            if (connected) {
                println("RouterScanner: connection OK, getting ARP table...")
                val arp = client.getArpTable()
                println("RouterScanner: ARP table size = ${arp.size}")

                TestResult(
                    success = true,
                    deviceCount = arp.size,
                    protocol = config.protocol
                )
            } else {
                TestResult(
                    success = false,
                    error = "Не удалось подключиться к ${config.ip}:${config.port} " +
                            "по протоколу ${config.protocol.name}"
                )
            }
        } catch (e: Exception) {
            println("RouterScanner: testConnection error: ${e.message}")
            TestResult(
                success = false,
                error = "Ошибка: ${e.message}"
            )
        }
    }

    /**
     * Получает текущий SSID сети.
     */
    suspend fun getCurrentSsid(): String? = withContext(Dispatchers.IO) {
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@withContext null
            val info = wm.connectionInfo ?: return@withContext null
            val ssid = info.ssid ?: return@withContext null
            ssid.removeSurrounding("\"").ifBlank { null }
        } catch (e: Exception) {
            println("RouterScanner: getCurrentSsid error: ${e.message}")
            null
        }
    }

    /**
     * Получает IP шлюза (роутера) из DHCP.
     */
    suspend fun getGatewayIp(): String? = withContext(Dispatchers.IO) {
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@withContext null
            val dhcp = wm.dhcpInfo ?: return@withContext null
            val gwInt = dhcp.gateway
            if (gwInt == 0) return@withContext null
            String.format(
                "%d.%d.%d.%d",
                gwInt and 0xff,
                gwInt shr 8 and 0xff,
                gwInt shr 16 and 0xff,
                gwInt shr 24 and 0xff
            )
        } catch (e: Exception) { null }
    }

    /**
     * Создаёт соответствующий RouterClient на основе протокола.
     */
    private fun createClient(config: RouterConfig, protocol: ProtocolType): RouterClient {
        val protoConfig = when (protocol) {
            ProtocolType.HTTP -> config.copy(port = if (config.port == 22 || config.port == 161) 80 else config.port)
            ProtocolType.SSH -> config.copy(port = if (config.port == 80 || config.port == 161) 22 else config.port)
            ProtocolType.SNMP -> config.copy(port = if (config.port == 80 || config.port == 22) 161 else config.port)
        }

        return when (protocol) {
            ProtocolType.HTTP -> HttpRouterClient(protoConfig, okHttpClient)
            ProtocolType.SSH -> SshRouterClient(protoConfig)
            ProtocolType.SNMP -> SnmpRouterClient(protoConfig)
        }
    }
}
