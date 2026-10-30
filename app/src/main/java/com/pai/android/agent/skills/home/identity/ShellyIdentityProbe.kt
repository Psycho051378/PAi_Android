package com.pai.android.agent.skills.home.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Идентичность устройств Shelly (Gen1 и Gen2+).
 *
 * Gen2/Gen3: GET /rpc/Shelly.GetDeviceInfo → {"result":{"name","id","mac","model","gen","app"}}
 * Gen1:      GET /shelly                    → {"type":"SHSW-25","mac":"...","fw":"...","auth":false}
 *
 * Оба ответа содержат MAC — именно это и нужно, чтобы не зависеть от роутера.
 */
@Singleton
class ShellyIdentityProbe @Inject constructor() : IdentityProbe {

    override val protocol: String = "SHELLY"

    companion object {
        const val DEFAULT_PORT = 80
    }

    override fun accepts(openPorts: List<Int>): Boolean =
        openPorts.isEmpty() || openPorts.any { it in listOf(80, 8080, 8081) }

    suspend fun probe(ip: String, port: Int = DEFAULT_PORT): IdentityHit? = withContext(Dispatchers.IO) {
        val effectivePort = if (port > 0) port else DEFAULT_PORT

        // ── Gen2/Gen3 ──
        val gen2 = ProbeHttp.getJson("http://$ip:$effectivePort/rpc/Shelly.GetDeviceInfo")
        if (gen2 != null) {
            val info = gen2.optJSONObject("result") ?: gen2
            val app = info.optString("app", "")
            val isShelly = app.contains("shelly", ignoreCase = true) ||
                info.has("gen") ||
                info.has("fw_id") ||
                info.optString("id", "").startsWith("shelly", ignoreCase = true)
            if (isShelly) {
                return@withContext IdentityHit(
                    ip = ip,
                    protocol = protocol,
                    port = effectivePort,
                    mac = MacUtil.normalize(info.optString("mac", null)),
                    nativeId = info.optString("id", null)?.takeIf { it.isNotBlank() },
                    model = info.optString("model", null)?.takeIf { it.isNotBlank() },
                    firmware = info.optString("ver", null)?.takeIf { it.isNotBlank() }
                        ?: info.optString("fw_id", null)?.takeIf { it.isNotBlank() },
                    name = info.optString("name", null)?.takeIf { it.isNotBlank() },
                    capabilities = listOf("on_off", "brightness", "status"),
                    source = "shelly-gen2"
                )
            }
        }

        // ── Gen1 ──
        val gen1 = ProbeHttp.getJson("http://$ip:$effectivePort/shelly")
        if (gen1 != null) {
            val type = gen1.optString("type", "")
            val mac = MacUtil.normalize(gen1.optString("mac", null))
            if (type.isNotBlank() && mac != null) {
                return@withContext IdentityHit(
                    ip = ip,
                    protocol = protocol,
                    port = effectivePort,
                    mac = mac,
                    model = type,
                    firmware = gen1.optString("fw", null)?.takeIf { it.isNotBlank() },
                    name = ProbeHttp.getJson("http://$ip:$effectivePort/settings")
                        ?.optString("name", null)?.takeIf { it.isNotBlank() },
                    capabilities = listOf("on_off", "brightness", "status"),
                    source = "shelly-gen1"
                )
            }
        }

        null
    }
}
