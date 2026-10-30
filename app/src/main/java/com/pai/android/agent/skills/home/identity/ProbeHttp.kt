package com.pai.android.agent.skills.home.identity

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Общий HTTP-клиент для пробников идентичности.
 *
 * Таймауты намеренно короткие: в локальной сети устройство отвечает за единицы
 * миллисекунд, а «молчащий» хост должен отваливаться за 400 мс, а не за 10 с.
 */
internal object ProbeHttp {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(900, TimeUnit.MILLISECONDS)
        .callTimeout(1200, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** GET с телом ответа или null при любой ошибке. */
    fun get(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "PAi/1.0 (identity probe)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** GET + разбор JSON. Возвращает null и на сетевую ошибку, и на не-JSON. */
    fun getJson(url: String): JSONObject? {
        val body = get(url) ?: return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }
}
