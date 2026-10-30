package com.pai.android.agent.skills.home.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Идентичность устройств на прошивке Tasmota (Sonoff и прочие ESP8266/ESP32).
 *
 * GET /cm?cmnd=Status%200 возвращает всё сразу:
 * - StatusNET.Mac        — MAC устройства
 * - StatusNET.Hostname   — hostname
 * - StatusFWR.Version    — версия прошивки (содержит «tasmota»)
 * - StatusFWR.Hardware   — модуль (ESP8266EX / ESP32)
 * - Status.DeviceName / Status.FriendlyName — имя
 * - StatusSTS.POWER/Dimmer/CT/Color — состояние
 */
@Singleton
class TasmotaIdentityProbe @Inject constructor() : IdentityProbe {

    override val protocol: String = "TASMOTA"

    companion object {
        const val DEFAULT_PORT = 80
    }

    override fun accepts(openPorts: List<Int>): Boolean =
        openPorts.isEmpty() || openPorts.any { it in listOf(80, 8080) }

    suspend fun probe(ip: String, port: Int = DEFAULT_PORT): IdentityHit? = withContext(Dispatchers.IO) {
        val effectivePort = if (port > 0) port else DEFAULT_PORT
        val body = ProbeHttp.get("http://$ip:$effectivePort/cm?cmnd=Status%200") ?: return@withContext null

        // Отсекаем чужие JSON-ответы: Tasmota всегда возвращает один из этих блоков
        val looksLikeTasmota = body.contains("StatusFWR", ignoreCase = true) ||
            body.contains("StatusSTS", ignoreCase = true) ||
            body.contains("StatusNET", ignoreCase = true) ||
            body.contains("Tasmota", ignoreCase = true)
        if (!looksLikeTasmota) return@withContext null

        val json = try {
            org.json.JSONObject(body)
        } catch (e: Exception) {
            return@withContext null
        }

        val net = json.optJSONObject("StatusNET")
        val status = json.optJSONObject("Status")
        val fwr = json.optJSONObject("StatusFWR")
        val sts = json.optJSONObject("StatusSTS")

        val friendly = status?.optJSONArray("FriendlyName")?.optString(0, null)?.takeIf { it.isNotBlank() }
        val deviceName = status?.optString("DeviceName", null)?.takeIf { it.isNotBlank() }

        val power = sts?.optString("POWER", null)?.let { it.equals("ON", ignoreCase = true) }
            ?: sts?.optInt("POWER", -1)?.let { if (it < 0) null else it == 1 }
        val dimmer = sts?.optInt("Dimmer", -1)?.takeIf { it in 0..100 }
        val ct = sts?.optInt("CT", -1)?.takeIf { it in 150..500 }
            ?.let { ((it - 200).coerceAtLeast(0) * 4500 / 300) + 2000 } // 200..500 → 2000..6500K
        val colorHex = sts?.optString("Color", null)?.takeIf { it.length >= 6 }
        val rgb = colorHex?.let {
            try {
                Triple(
                    it.substring(0, 2).toInt(16),
                    it.substring(2, 4).toInt(16),
                    it.substring(4, 6).toInt(16)
                )
            } catch (e: Exception) {
                null
            }
        }

        IdentityHit(
            ip = ip,
            protocol = protocol,
            port = effectivePort,
            mac = MacUtil.normalize(net?.optString("Mac", null)),
            nativeId = net?.optString("Hostname", null)?.takeIf { it.isNotBlank() },
            model = fwr?.optString("Hardware", null)?.takeIf { it.isNotBlank() }
                ?: fwr?.optString("Module", null)?.takeIf { it.isNotBlank() },
            firmware = fwr?.optString("Version", null)?.takeIf { it.isNotBlank() },
            name = friendly ?: deviceName,
            capabilities = buildList {
                add("on_off")
                add("status")
                if (dimmer != null) add("brightness")
                if (ct != null) add("color_temp")
                if (rgb != null) add("rgb")
            },
            state = DeviceState(power = power, brightness = dimmer, colorTempKelvin = ct, rgb = rgb),
            source = "tasmota"
        )
    }
}
