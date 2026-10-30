package com.pai.android.agent.skills.home

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Многопоточный TCP сканер портов.
 *
 * Раньше здесь была ошибка: сканирование возвращало результат по
 * awaitTermination(10s), не дожидаясь задач — на большой подсети карта
 * приходила полупустой, и устройства «пропадали» из скана.
 * Теперь используются invokeAll с бюджетом: незавершённые задачи отменяются,
 * а признак отмены логируется (если отмен много — бюджет мал).
 */
object TcpScanner {

    private const val TAG = "TcpScanner"

    /** Таймаут одного connect. В локальной сети хватает 300 мс. */
    private const val CONNECT_TIMEOUT_MS = 300
    private const val THREADS = 96
    private const val MAX_BUDGET_MS = 45_000L

    /**
     * Быстрый набор портов: покрывает идентификацию домашних устройств.
     * Полный список — [COMMON_PORTS], его имеет смысл гонять только по запросу.
     */
    val FAST_PORTS = listOf(
        80,      // HTTP: Shelly, Tasmota, WLED, ESPHome, роутеры
        443,     // HTTPS
        8080,    // HTTP alt
        55443,   // Yeelight
        9999,    // TP-Link Kasa
        8123,    // Home Assistant
        1883,    // MQTT
        554,     // RTSP (камеры)
        6053,    // ESPHome native API
        8008,    // Chromecast
        8009,    // Chromecast
        6668,    // Tuya (зашифрованный локальный протокол: видно устройство, но не имя)
        22       // SSH (роутеры/NAS)
    )

    /** Расширенный набор — для «глубокого» сканирования по запросу. */
    val COMMON_PORTS = listOf(
        21, 22, 23, 80, 443, 554, 1883, 3000, 5000, 5001, 5353, 6053, 7000,
        8008, 8009, 8080, 8123, 8200, 8443, 8883, 9000, 9001, 9100, 9999,
        22222, 32400, 38899, 4321, 49000, 54321, 55443
    )

    /**
     * Сканирует указанные IP на открытые порты.
     *
     * @param ips список IP
     * @param ports порты (по умолчанию FAST_PORTS)
     * @param timeoutMs таймаут одного connect
     * @return Map<IP, List<открытых портов>>
     */
    fun scanPorts(
        ips: Collection<String>,
        ports: List<Int> = FAST_PORTS,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
        network: android.net.Network? = null
    ): Map<String, List<Int>> {
        if (ips.isEmpty() || ports.isEmpty()) return emptyMap()

        val found = ConcurrentHashMap<String, MutableList<Int>>()
        val tasks = ArrayList<Callable<Unit>>(ips.size * ports.size)

        for (ip in ips) {
            for (port in ports) {
                tasks.add(Callable {
                    if (isPortOpen(ip, port, timeoutMs, network)) {
                        found.computeIfAbsent(ip) { java.util.Collections.synchronizedList(mutableListOf()) }
                            .add(port)
                    }
                })
            }
        }

        val executor = Executors.newFixedThreadPool(THREADS)
        val budget = ((tasks.size + THREADS - 1) / THREADS) * timeoutMs + 2_000L
        var cancelled = 0
        try {
            val futures = executor.invokeAll(tasks, budget.coerceAtMost(MAX_BUDGET_MS), TimeUnit.MILLISECONDS)
            cancelled = futures.count { it.isCancelled }
        } catch (e: InterruptedException) {
            Log.w(TAG, "scanPorts interrupted: ${e.message}")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "scanPorts error: ${e.message}")
        } finally {
            executor.shutdownNow()
        }

        if (cancelled > 0) {
            Log.w(TAG, "scanPorts: $cancelled из ${tasks.size} задач не успели за $budget мс — результат может быть неполным")
        }
        Log.d(TAG, "scanPorts: ${found.size} hosts с открытыми портами из ${ips.size} проверенных")

        return found.mapValues { (_, openPorts) -> openPorts.sorted() }
    }

    /**
     * Быстрая проверка «хост вообще живой» — по любому из типовых портов.
     */
    fun aliveHosts(
        ips: Collection<String>,
        ports: List<Int> = listOf(80, 443, 55443, 8080),
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
        network: android.net.Network? = null
    ): List<String> = scanPorts(ips, ports, timeoutMs, network).keys.toList()

    /**
     * Проверяет, открыт ли порт на указанном IP.
     */
    fun isPortOpen(
        ip: String,
        port: Int,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
        network: android.net.Network? = null
    ): Boolean {
        return try {
            Socket().use { socket ->
                // Привязка к Wi-Fi: при активном VPN TCP к локальным адресам может уйти в туннель
                try { network?.bindSocket(socket) } catch (_: Exception) { }
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
