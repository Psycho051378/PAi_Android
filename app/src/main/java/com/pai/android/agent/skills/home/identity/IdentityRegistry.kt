package com.pai.android.agent.skills.home.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реестр пробников идентичности.
 *
 * Задача слоя: получить от устройств то, что раньше приходилось вытаскивать
 * из роутера — MAC, модель, имя и состояние — обычным сетевым запросом.
 *
 * Два этапа:
 * 1. [broadcastScan] — один пакет на протокол, отвечают все устройства сразу
 *    (WiZ, Yeelight). Дёшево и быстро, не требует списка IP.
 * 2. [identify] — адресный опрос хостов с известными открытыми портами
 *    (Shelly, Tasmota, Yeelight по TCP).
 *
 * Оба этапа ограничены таймаутом: сканирование сети не должно «зависать».
 */
@Singleton
class IdentityRegistry @Inject constructor(
    private val wiz: WizIdentityProbe,
    private val yeelight: YeelightIdentityProbe,
    private val shelly: ShellyIdentityProbe,
    private val tasmota: TasmotaIdentityProbe
) {

    companion object {
        /** Сколько хостов опрашивать одновременно. */
        const val DEFAULT_CONCURRENCY = 16
    }

    /**
     * Этап 1: broadcast/multicast опрос.
     *
     * @param timeoutMs сколько ждать ответов
     * @param subnetHosts адреса подсети для unicast-fallback (Yeelight)
     * @return найденные устройства (может быть пусто — это нормально)
     */
    suspend fun broadcastScan(
        timeoutMs: Int = 1400,
        subnetHosts: List<String> = emptyList()
    ): List<IdentityHit> = coroutineScope {
        val wizJob = async(Dispatchers.IO) {
            runCatching { wiz.broadcastProbe(timeoutMs) }.getOrDefault(emptyList())
        }
        val yeelightJob = async(Dispatchers.IO) {
            runCatching { yeelight.multicastProbe(timeoutMs) }.getOrDefault(emptyList())
        }
        // Лампы, не отвечающие на multicast (IGMP snooping, перезагрузка), ловятся unicast'ом
        val yeelightUnicastJob = async(Dispatchers.IO) {
            if (subnetHosts.isEmpty()) {
                emptyList<IdentityHit>()
            } else {
                runCatching { yeelight.unicastSweep(subnetHosts) }.getOrDefault(emptyList())
            }
        }

        val merged = LinkedHashMap<String, IdentityHit>()
        val all = wizJob.await() + yeelightJob.await() + yeelightUnicastJob.await()
        for (hit in all) {
            merged[hit.ip] = merge(merged[hit.ip], hit)
        }
        merged.values.toList()
    }

    /**
     * Этап 2: адресный опрос конкретных хостов.
     *
     * @param hosts IP → список открытых TCP-портов (пустой список = порты неизвестны)
     * @param httpFingerprints подсказки от HttpProber: IP → fingerprint ("shelly", "tasmota", ...)
     * @param broadcastHits результаты этапа 1 — эти хосты повторно не опрашиваются
     * @param concurrency сколько хостов опрашивать параллельно
     * @param budgetMs общий бюджет времени на этап
     * @return IP → IdentityHit
     */
    suspend fun identify(
        hosts: Map<String, List<Int>>,
        httpFingerprints: Map<String, String> = emptyMap(),
        broadcastHits: List<IdentityHit> = emptyList(),
        concurrency: Int = DEFAULT_CONCURRENCY,
        budgetMs: Long = 8000L
    ): Map<String, IdentityHit> {
        val result = LinkedHashMap<String, IdentityHit>()
        for (hit in broadcastHits) result[hit.ip] = hit

        val pending = hosts.filterKeys { it !in result }
        if (pending.isEmpty()) return result

        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val probed = withTimeoutOrNull(budgetMs) {
            coroutineScope {
                pending.map { (ip, ports) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            runCatching { probeHost(ip, ports, httpFingerprints[ip]) }.getOrNull()
                        }
                    }
                }.awaitAll()
            }
        }.orEmpty()

        for (hit in probed) {
            if (hit == null) continue
            val existing = result[hit.ip]
            if (existing == null || hit.confidence >= existing.confidence) {
                result[hit.ip] = merge(existing, hit)
            }
        }
        return result
    }

    /**
     * Опрос одного хоста: пробники идут от самого дешёвого и точного
     * к самому общему. Первый ответ считается идентификацией.
     */
    suspend fun probeHost(
        ip: String,
        openPorts: List<Int>,
        httpFingerprint: String? = null
    ): IdentityHit? {
        val hint = httpFingerprint?.lowercase().orEmpty()
        val httpPort = pickHttpPort(openPorts)

        if (httpPort != null) {
            // Shelly: /rpc/Shelly.GetDeviceInfo или /shelly
            if (shelly.accepts(openPorts) && (hint.isEmpty() || hint.contains("shelly"))) {
                shelly.probe(ip, httpPort)?.let { return it }
            }
            // Tasmota: /cm?cmnd=Status 0
            if (tasmota.accepts(openPorts) &&
                (hint.isEmpty() || hint.contains("tasmota") || hint.contains("sonoff") || hint.contains("esphome"))
            ) {
                tasmota.probe(ip, httpPort)?.let { return it }
            }
        }

        // Yeelight по TCP (если порт открыт или порты неизвестны)
        if (yeelight.accepts(openPorts)) {
            yeelight.tcpProbe(ip)?.let { return it }
        }

        // WiZ по UDP — только для хостов, у которых вообще нет открытых TCP-портов
        if (openPorts.isEmpty() || hint.contains("wiz")) {
            wiz.unicastProbe(ip)?.let { return it }
        }

        return null
    }

    /**
     * Слияние двух попаданий по одному IP: берём более полную информацию.
     */
    fun merge(existing: IdentityHit?, fresh: IdentityHit): IdentityHit {
        if (existing == null) return fresh
        return fresh.copy(
            mac = fresh.mac ?: existing.mac,
            nativeId = fresh.nativeId ?: existing.nativeId,
            model = fresh.model ?: existing.model,
            firmware = fresh.firmware ?: existing.firmware,
            name = fresh.name ?: existing.name,
            capabilities = (fresh.capabilities + existing.capabilities).distinct(),
            state = fresh.state ?: existing.state
        )
    }

    /** Порт, по которому есть смысл пробовать HTTP-протоколы. */
    private fun pickHttpPort(openPorts: List<Int>): Int? {
        return when {
            openPorts.isEmpty() -> 80
            80 in openPorts -> 80
            8080 in openPorts -> 8080
            8081 in openPorts -> 8081
            else -> null
        }
    }
}
