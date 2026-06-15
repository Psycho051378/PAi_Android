package com.pai.android.agent.skills.home

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Многопоточный TCP сканер портов.
 * Проверяет список популярных портов на указанных IP-адресах.
 */
object TcpScanner {

    private const val TAG = "TcpScanner"
    private const val TIMEOUT_MS = 1500
    private const val THREADS = 50

    /** Популярные порты для умного дома и сетевых устройств */
    val COMMON_PORTS = listOf(
        21,    // FTP
        22,    // SSH
        23,    // Telnet
        80,    // HTTP
        443,   // HTTPS
        554,   // RTSP
        8080,  // HTTP альтернативный
        8123,  // Home Assistant
        1883,  // MQTT
        8883,  // MQTT over TLS
        9999,  // TP-Link Kasa
        6053,  // Sonoff DIY
        8443,  // HTTPS альтернативный
        55443, // Yeelight
        32400, // Plex
        8200,  // Kodi
        22_222,// Amazon Echo
        5353,  // mDNS
        5683,  // CoAP
        389,   // LDAP
        1720,  // H.323
        7070,  // RealServer
        5000,  // UPnP
        5001,  // UPnP alt
        49000, // SSDP
        1900   // SSDP
    )

    /**
     * Сканирует указанные IP на открытые порты.
     * @param ips список IP для сканирования
     * @param ports порты для проверки (по умолчанию COMMON_PORTS)
     * @return Map<IP, List<открытых портов>>
     */
    fun scanPorts(
        ips: Collection<String>,
        ports: List<Int> = COMMON_PORTS
    ): Map<String, List<Int>> {
        val result = mutableMapOf<String, MutableList<Int>>()
        val executor = Executors.newFixedThreadPool(THREADS)
        val lock = Any()

        for (ip in ips) {
            for (port in ports) {
                executor.submit {
                    if (isPortOpen(ip, port)) {
                        synchronized(lock) {
                            result.getOrPut(ip) { mutableListOf() }.add(port)
                        }
                    }
                }
            }
        }

        executor.shutdown()
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "scan interrupted: ${e.message}")
        }

        // Сортируем порты для каждого IP
        return result.mapValues { (_, ports) -> ports.sorted() }
    }

    /**
     * Проверяет, открыт ли порт на указанном IP.
     */
    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), TIMEOUT_MS)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
