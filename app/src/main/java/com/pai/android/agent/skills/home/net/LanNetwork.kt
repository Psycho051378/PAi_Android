package com.pai.android.agent.skills.home.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Привязка сокетов к Wi-Fi сети.
 *
 * Зачем это нужно.
 *
 * Если на телефоне активен VPN (Cloudflare WARP, WireGuard, корпоративный
 * «always-on»), то маршрут по умолчанию указывает в туннель. Пакеты на
 * 255.255.255.255 и на multicast-адреса уходят **в туннель**, а не в Wi-Fi,
 * и никакие устройства в локальной сети их не получают. Снаружи это выглядит
 * как «сканирование ничего не находит», хотя сеть полна устройств.
 *
 * Проверено на реальной сети: без привязки WiZ-broadcast даёт 0 ответов,
 * с привязкой к Wi-Fi — ответы от всех ламп.
 *
 * [Network.bindSocket] (API 23/24) привязывает конкретный сокет к конкретной
 * сети, не затрагивая остальной процесс.
 */
@Singleton
class LanNetwork @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivity: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Активная Wi-Fi сеть (не VPN, не мобильная).
     * null — если Wi-Fi выключен или приложение не имеет доступа к состоянию сети.
     */
    fun wifiNetwork(): Network? {
        return try {
            val cm = connectivity ?: return null
            cm.allNetworks.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        } catch (e: Exception) {
            println("LanNetwork: wifiNetwork error — \${e.message}")
            null
        }
    }

    /** IPv4-адрес телефона в Wi-Fi сети. */
    fun wifiIpv4(): String? {
        return try {
            val cm = connectivity ?: return null
            val network = wifiNetwork() ?: return null
            val link = cm.getLinkProperties(network) ?: return null
            val address: LinkAddress = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
            address.address.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Широковещательный адрес подсети (192.168.0.255 для 192.168.0.0/24).
     *
     * Надёжнее, чем 255.255.255.255: часть точек доступа и VPN-стеков
     * отбрасывают глобальный broadcast, но пропускают broadcast своей подсети.
     */
    fun broadcastAddress(): String? {
        val ip = wifiIpv4() ?: return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return "\${parts[0]}.\${parts[1]}.\${parts[2]}.255"
    }

    /** Привязать UDP-сокет к Wi-Fi. Возвращает true, если привязка удалась. */
    fun bind(socket: DatagramSocket): Boolean {
        val network = wifiNetwork() ?: return false
        return try {
            network.bindSocket(socket)
            true
        } catch (e: Exception) {
            println("LanNetwork: bind(DatagramSocket) error — \${e.message}")
            false
        }
    }

    /** Привязать TCP-сокет к Wi-Fi. */
    fun bind(socket: Socket): Boolean {
        val network = wifiNetwork() ?: return false
        return try {
            network.bindSocket(socket)
            true
        } catch (e: Exception) {
            println("LanNetwork: bind(Socket) error — \${e.message}")
            false
        }
    }
}
