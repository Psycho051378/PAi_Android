package com.pai.android.agent.skills.home.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP клиент для веб-интерфейса роутера.
 *
 * Поддерживает несколько вендоров через механизм RouterHttpHandler.
 * Пытается определить тип роутера по HTML страницы логина или заголовкам ответа.
 *
 * Текущие реализации:
 * - TP-Link (Archer серия, Omada) — полная
 * - D-Link — заглушка
 * - ASUS — заглушка
 * - Keenetic — заглушка
 * - Zyxel/Keenetic — заглушка
 */
class HttpRouterClient(
    private val routerConfig: RouterConfig,
    private val okHttpClient: OkHttpClient
) : RouterClient {

    override val name: String get() = "HTTP (${detectedVendor ?: "автоопределение"})"
    override val protocolType: ProtocolType = ProtocolType.HTTP

    companion object {
        private const val TIMEOUT_MS = 10000L
        /** Единый CookieJar для всех сессий HttpRouterClient. */
        private val sharedCookieJar = InMemoryCookieJar()

        /** Создаёт OkHttpClient с trust-all SSL и CookieJar для роутеров с самоподписанными сертификатами. */
        private fun createUnsafeOkHttpClient(base: OkHttpClient): OkHttpClient {
            return try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                base.newBuilder()
                    .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                    .hostnameVerifier(HostnameVerifier { _, _ -> true })
                    .cookieJar(InMemoryCookieJar())
                    .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .followRedirects(true)
                    .build()
            } catch (e: Exception) {
                println("HTTP: createUnsafeOkHttpClient error: ${e.message}")
                base.newBuilder().cookieJar(InMemoryCookieJar()).build()
            }
        }
    }

    private var detectedVendor: String? = null
    private var baseUrl: String = "http://${routerConfig.ip}:${routerConfig.port}"

    /** Реестр обработчиков для разных вендоров. */
    private val handlers: Map<String, RouterHttpHandler> = mapOf(
        "tp_link" to TpLinkHttpHandler(),
        "d_link" to DLinkHttpHandler(),
        "asus" to AsusHttpHandler(),
        "keenetic" to KeeneticHttpHandler()
    )

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Пробуем HTTP и HTTPS
            val urlsToTry = listOf(
                "http://${routerConfig.ip}:${routerConfig.port}",
                "https://${routerConfig.ip}:443",
                "http://${routerConfig.ip}:80",
                "https://${routerConfig.ip}:${routerConfig.port}"
            ).distinct()

            for (url in urlsToTry) {
                try {
                    val httpClient = createUnsafeOkHttpClient(okHttpClient)

                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string() ?: ""
                    response.close()

                    println("HTTP: testConnection to $url, status=${response.code}")

                    if (response.code in 200..399) {
                        baseUrl = url // запоминаем рабочий URL
                        detectedVendor = identifyVendor(body, response.headers.joinToString())
                        println("HTTP: detected vendor: $detectedVendor at $baseUrl")
                        return@withContext true
                    }
                } catch (e: Exception) {
                    println("HTTP: $url failed: ${e.message}")
                }
            }

            false
        } catch (e: Exception) {
            println("HTTP testConnection error: ${e.message}")
            false
        }
    }

    override suspend fun getArpTable(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val vendor = detectedVendor ?: "tp_link" // По умолчанию пробуем TP-Link

            val handler = handlers[vendor]
            if (handler == null) {
                println("HTTP: no handler for vendor $vendor")
                return@withContext emptyMap()
            }

            // Создаём HTTP-клиент с CookieJar — cookies сохранятся между login и getArpTable
            val httpClient = createUnsafeOkHttpClient(okHttpClient)

            // Пробуем логин через handler текущего вендора
            val loginResult = handler.login(httpClient, baseUrl, routerConfig.username, routerConfig.password)
            
            if (loginResult != null) {
                println("HTTP: login successful via $vendor")
                return@withContext handler.getArpTable(httpClient, baseUrl, loginResult)
            }

            // Если вендор определён, но логин не удался — пробуем все остальные
            println("HTTP: $vendor login failed, trying all handlers...")
            for ((name, h) in handlers) {
                if (name == vendor) continue // Уже пробовали
                try {
                    println("HTTP: trying $name handler...")
                    // Создаём свежий клиент для каждого handler (новая сессия)
                    val freshClient = createUnsafeOkHttpClient(okHttpClient)
                    val token = h.login(freshClient, baseUrl, routerConfig.username, routerConfig.password)
                    if (token != null) {
                        println("HTTP: login successful via $name")
                        detectedVendor = name
                        return@withContext h.getArpTable(freshClient, baseUrl, token)
                    }
                } catch (e: Exception) {
                    println("HTTP: $name handler error: ${e.message}")
                }
            }

            println("HTTP: all handlers failed")
            return@withContext emptyMap()
        } catch (e: Exception) {
            println("HTTP getArpTable error: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Пытается определить вендора роутера по содержимому страницы логина.
     */
    private fun identifyVendor(html: String, headers: String): String? {
        val lowerHtml = html.lowercase()
        val lowerHeaders = headers.lowercase()

        return when {
            lowerHtml.contains("tp-link") || lowerHtml.contains("tplink") ||
                    lowerHeaders.contains("tp-link") || lowerHeaders.contains("tplink") ||
                    lowerHtml.contains("archer") || lowerHtml.contains("ax73") ||
                    lowerHtml.contains("ax72") || lowerHtml.contains("ax55") ||
                    lowerHtml.contains("deco") || lowerHtml.contains("omada") ||
                    lowerHtml.contains("miniu pnp") || lowerHtml.contains("tp_secure") ||
                    lowerHeaders.contains("tp_secure") -> "tp_link"
            lowerHtml.contains("d-link") || lowerHtml.contains("dlink") ||
                    lowerHeaders.contains("d-link") -> "d_link"
            lowerHtml.contains("asus") || lowerHeaders.contains("asus") -> "asus"
            lowerHtml.contains("keenetic") || lowerHeaders.contains("keenetic") ||
                    lowerHtml.contains("zyxel") || lowerHeaders.contains("zyxel") -> "keenetic"
            lowerHtml.contains("mikrotik") || lowerHeaders.contains("mikrotik") ||
                    lowerHtml.contains("routeros") -> "mikrotik"
            else -> null
        }
    }
}

// ════════════════════ Handler interface ════════════════════

/**
 * Обработчик HTTP-авторизации и получения ARP для конкретного вендора роутеров.
 */
interface RouterHttpHandler {
    /** Проверяет, подходит ли этот обработчик для данной страницы логина. */
    fun matches(loginPageHtml: String): Boolean

    /**
     * Выполняет логин и возвращает токен сессии (stok, cookie, session id).
     * @return токен для последующих запросов, или null при ошибке.
     */
    suspend fun login(
        client: OkHttpClient,
        baseUrl: String,
        username: String,
        password: String
    ): String?

    /**
     * Получает ARP-таблицу с роутера используя токен сессии.
     */
    suspend fun getArpTable(
        client: OkHttpClient,
        baseUrl: String,
        token: String
    ): Map<String, String>
}

// ════════════════════ InMemoryCookieJar ════════════════════

/**
 * Простой in-memory CookieJar для OkHttp.
 * Хранит куки в памяти и возвращает их для соответствующих URL.
 */
class InMemoryCookieJar : CookieJar {
    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val existing = cookieStore[host] ?: emptyList()
        // Объединяем, заменяя старые куки с тем же именем
        val merged = mutableListOf<Cookie>()
        merged.addAll(cookies)
        for (cookie in existing) {
            if (cookies.none { it.name == cookie.name }) {
                merged.add(cookie)
            }
        }
        cookieStore[host] = merged
        if (cookies.isNotEmpty()) {
            println("CookieJar: saved ${cookies.size} cookies for $host")
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookies = cookieStore[url.host] ?: emptyList<Cookie>()
        // Фильтруем просроченные и не подходящие по пути
        val valid = cookies.filter { cookie ->
            cookie.matches(url) && cookie.expiresAt > System.currentTimeMillis()
        }
        if (cookies.isNotEmpty() && valid.isEmpty()) {
            // Все куки для этого хоста просрочены — очищаем
            cookieStore.remove(url.host)
        }
        return valid
    }

    /** Очищает все сохранённые куки. */
    fun clear() {
        cookieStore.clear()
    }
}

// ════════════════════ TP-Link Handler ════════════════════

/**
 * Обработчик для TP-Link роутеров (Archer серия, Deco, Omada).
 *
 * Типичный API flow:
 * 1. GET / → страница логина
 * 2. POST /cgi-bin/luci/;stok=/login с JSON { "method": "do", "login": { ... } }
 *    или POST / с JSON { "method": "do", "login": { "username": "...", "password": "..." } }
 * 3. В ответ приходит stok в JSON: { "stok": "...", ... }
 * 4. GET /{stok}/admin/device?form=arp → JSON с ARP-таблицей
 *
 * Альтернативный flow (старые прошивки):
 * 2. POST /userRpm/LoginRpm.htm с login + password
 * 3. PARSE redirect URL для извлечения stok
 * 4. GET /userRpm/AssociateDeviceRpm.htm
 */
class TpLinkHttpHandler : RouterHttpHandler {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override fun matches(loginPageHtml: String): Boolean {
        val lower = loginPageHtml.lowercase()
        return lower.contains("tp-link") || lower.contains("tplink") ||
                lower.contains("archer") || lower.contains("deco") ||
                lower.contains("omada")
    }

    override suspend fun login(
        client: OkHttpClient,
        baseUrl: String,
        username: String,
        password: String
    ): String? {
        // ════════════════════ Попытка 1: Archer AX73 / Deco (JSON API, только пароль) ════════════════════
        // Новые TP-Link (Archer AX73, Deco) отправляют POST с паролем и получают stok
        // Не требуют username — в JSON отправляется только password
        try {
            val jsonBody = JSONObject().apply {
                put("method", "do")
                put("login", JSONObject().apply {
                    put("password", password)
                })
            }

            val request = Request.Builder()
                .url("$baseUrl/")
                .post(RequestBody.create(JSON_MEDIA_TYPE, jsonBody.toString()))
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            response.close()

            println("TP-Link: login AX73 v2 (no username) response code=${response.code}")
            println("TP-Link: login AX73 v2 body (first 500): " + responseBody.take(500))

            if (response.code == 200 && responseBody.isNotBlank()) {
                val stok = extractStokFromJson(responseBody)
                if (stok != null) return stok
            }
        } catch (e: Exception) {
            println("TP-Link: AX73 v2 login failed: ${e.message}")
        }

        // ════════════════════ Попытка 2: v2 API (JSON) с username + password ════════════════════
        try {
            val jsonBody = JSONObject().apply {
                put("method", "do")
                put("login", JSONObject().apply {
                    put("username", username.ifBlank { "admin" })
                    put("password", password)
                })
            }

            val request = Request.Builder()
                .url("$baseUrl/")
                .post(RequestBody.create(JSON_MEDIA_TYPE, jsonBody.toString()))
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            response.close()

            println("TP-Link: login v2 (with username) response code=${response.code}")
            println("TP-Link: login v2 body (first 500): " + responseBody.take(500))

            if (response.code == 200 && responseBody.isNotBlank()) {
                val stok = extractStokFromJson(responseBody)
                if (stok != null) return stok
            }
        } catch (e: Exception) {
            println("TP-Link: v2 login failed: ${e.message}")
        }

        // Пробуем v1 API (userRpm)
        try {
            val formBody = FormBody.Builder()
                .add("username", username.ifBlank { "admin" })
                .add("password", password)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/userRpm/LoginRpm.htm")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val location = response.header("Location")
            response.close()

            println("TP-Link: login v1 response code=${response.code}, location=$location")

            // В Location может быть stok: /stok=xxxxxxx/userRpm/...
            if (location != null) {
                val stokMatch = Regex("stok=(\\w+)").find(location)
                if (stokMatch != null) {
                    return stokMatch.groupValues[1]
                }
                // Или /xxxx/userRpm/
                val pathMatch = Regex("/(\\w+)/userRpm/").find(location)
                if (pathMatch != null) {
                    return pathMatch.groupValues[1]
                }
            }

            // Пробуем cgi-bin/luci
            val cgiRequest = Request.Builder()
                .url("$baseUrl/cgi-bin/luci/;stok=/login")
                .post(jsonBodyForLogin(username, password))
                .header("Content-Type", "application/json")
                .build()

            val cgiResponse = client.newCall(cgiRequest).execute()
            val cgiBody = cgiResponse.body?.string() ?: ""
            cgiResponse.close()

            if (cgiBody.isNotBlank()) {
                try {
                    val json = JSONObject(cgiBody)
                    if (json.has("stok")) return json.getString("stok")
                    if (json.has("data")) {
                        val data = json.optJSONObject("data")
                        if (data?.has("stok") == true) return data.getString("stok")
                    }
                } catch (e: Exception) {
                    println("TP-Link: v2 JSON parse error: ${e.message}")
                }
            } else {
                println("TP-Link: v2 response empty or code=${response.code}")
            }
        } catch (e: Exception) {
            println("TP-Link: v1/cgi login failed: ${e.message}")
        }

        // Пробуем старый Archer: POST /password=***
        try {
            val formBody = FormBody.Builder()
                .add("password", password)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val setCookie = response.header("Set-Cookie", "") ?: ""
            response.close()

            println("TP-Link: old Archer login response code=${response.code}")
            println("TP-Link: old Archer Set-Cookie=${setCookie?.take(200)}")
            println("TP-Link: old Archer body (first 300): ${body.take(300)}")

            // Если есть cookie — считаем что логин успешен
            if (setCookie.isNotBlank() || body.contains("index.html") || !body.contains("password")) {
                val sessionId = setCookie?.substringBefore(";") ?: "session_${System.currentTimeMillis()}"
                println("TP-Link: old Archer login successful, session=$sessionId")
                // Возвращаем Set-Cookie значение как токен
                return setCookie
            }
        } catch (e: Exception) {
            println("TP-Link: old Archer login failed: ${e.message}")
        }

        return null
    }

    override suspend fun getArpTable(
        client: OkHttpClient,
        baseUrl: String,
        token: String
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // Определяем тип токена:
        // - Если похож на stok (16+ hex-символов) → используем в URL path /{stok}/admin/...
        // - Если содержит '=' → это Cookie/Set-Cookie строка
        // - Иначе → пробуем как stok в URL
        val isStok = token.matches(Regex("^[a-fA-F0-9]{16,}$"))
        val isCookie = token.contains("=")
        
        println("TP-Link: token type: ${if (isStok) "stok" else if (isCookie) "cookie" else "unknown"}")

        // Строим эндпоинты: если stok — версия с stok в URL, иначе старые эндпоинты
        val endpoints: List<String> = if (isStok) {
            listOf(
                "/$token/admin/device?form=arp",
                "/$token/admin/network.json",
                "/$token/admin/wireless?form=station",
                "/$token/admin/dhcps?form=dhcp",
                "/userRpm/AssociateDeviceRpm.htm",    // fallback
                "/userRpm/WlanStationRpm.htm"           // fallback
            )
        } else {
            listOf(
                "/userRpm/AssociateDeviceRpm.htm",
                "/userRpm/WlanStationRpm.htm",
                "/userRpm/StatusRpm.htm",
                "/userRpm/WlanNetworkRpm.htm",
                "/userRpm/LanDhcpServerRpm.htm",
                "/userRpm/LanUserRpm.htm",
                "/webpages/AssociateDeviceRpm.htm",
                "/webpages/index.html",
                "/cgi-bin/status",
                "/StatusRpm.htm"
            )
        }

        for (endpoint in endpoints) {
            try {
                val requestBuilder = Request.Builder().url("$baseUrl$endpoint").get()

                // Для cookie-токена добавляем Cookie header;
                // для stok Cookie не нужен — сессия уже сохранена в CookieJar клиента
                if (isCookie) {
                    requestBuilder.header("Cookie", token)
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val body = response.body?.string() ?: ""
                val ct = response.header("Content-Type", "")
                response.close()

                println("TP-Link: GET $endpoint status=${response.code}")

                if (response.code != 200) continue
                if (body.isBlank()) continue

                // Парсим JSON
                if ((ct?.contains("json") == true) || body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                    val arp = parseArpFromTpLinkJson(body)
                    result.putAll(arp)
                    if (arp.isNotEmpty()) {
                        println("TP-Link: found ${arp.size} ARP entries from $endpoint")
                    }
                } else {
                    // Парсим HTML-таблицу
                    val arp = parseArpFromHtml(body)
                    result.putAll(arp)
                    if (arp.isNotEmpty()) {
                        println("TP-Link: found ${arp.size} ARP entries from HTML $endpoint")
                    }
                }

                if (result.isNotEmpty()) break
            } catch (e: Exception) {
                println("TP-Link: GET $endpoint error: ${e.message}")
            }
        }

        return result
    }

    /**
     * Перегруженная версия parseArpFromTpLinkJson — принимает строку JSON.
     */
    private fun parseArpFromTpLinkJson(jsonString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            // Может быть JSONObject или JSONArray
            val trimmed = jsonString.trimStart()
            if (trimmed.startsWith("[")) {
                val array = org.json.JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    parseArpDeviceEntry(entry, result)
                }
            } else {
                val json = JSONObject(jsonString)
                parseArpFromTpLinkJson(json, result)
            }
        } catch (e: Exception) {
            println("TP-Link: parseArpFromTpLinkJson string error: ${e.message}")
        }
        return result
    }

    /**
     * Извлекает stok из JSON ответа TP-Link.
     * Форматы:
     * { "stok": "xxx", "success": true }
     * { "data": { "stok": "xxx" } }
     * { "success": true, "data": { "stok": "xxx" } }
     */
    private fun extractStokFromJson(jsonString: String): String? {
        return try {
            val json = JSONObject(jsonString)
            if (json.has("stok")) {
                val stok = json.getString("stok")
                if (stok.isNotBlank() && stok.length >= 8) return stok
            }
            // { "error_code": 0, "data": { "stok": "..." } }
            if (json.has("data")) {
                val data = json.optJSONObject("data")
                if (data?.has("stok") == true) {
                    val stok = data.getString("stok")
                    if (stok.isNotBlank() && stok.length >= 8) return stok
                }
                // Альтернатива: data.token (некоторые версии API)
                if (data?.has("token") == true) {
                    val token = data.getString("token")
                    if (token.isNotBlank() && token.length >= 8) return token
                }
            }
            // { "result": { "stok": "..." } }
            if (json.has("result")) {
                val result = json.optJSONObject("result")
                if (result?.has("stok") == true) {
                    val stok = result.getString("stok")
                    if (stok.isNotBlank() && stok.length >= 8) return stok
                }
            }
            // Некоторые TP-Link отдают stok в Header Set-Cookie как stok=xxx
            null
        } catch (e: Exception) {
            println("TP-Link: extractStokFromJson error: ${e.message}")
            null
        }
    }

    private fun jsonBodyForLogin(username: String, password: String): RequestBody {
        val json = JSONObject().apply {
            put("method", "do")
            put("login", JSONObject().apply {
                put("username", username.ifBlank { "admin" })
                put("password", password)
            })
        }
        return RequestBody.create(JSON_MEDIA_TYPE, json.toString())
    }

    /**
     * Парсит ARP-таблицу из JSON ответа TP-Link.
     *
     * Типичные форматы Archer AX73:
     * { "device": [ { "mac": "aa:bb:cc:dd:ee:ff", "ip": "192.168.0.100" }, ... ] }
     * или
     * { "data": { "arp_table": [ { "mac": "...", "ip": "..." } ] } }
     * или
     * { "success": true, "data": { "device": [...] } }
     */
    private fun parseArpFromTpLinkJson(json: JSONObject, result: MutableMap<String, String>) {
        try {
            // Если есть success-флаг, идём внутрь data
            val dataObj = if (json.has("success") && json.has("data")) {
                json.getJSONObject("data")
            } else {
                json
            }

            parseArpDeviceFromContainer(dataObj, "device", result)
            parseArpDeviceFromContainer(dataObj, "station", result)

            if (dataObj.has("arp_table")) {
                parseArpDeviceFromContainer(dataObj, "arp_table", result)
            }
        } catch (e: Exception) {
            println("TP-Link: JSON parse error: ${e.message}")
        }
    }

    /**
     * Парсит контейнер (JSONArray) с устройствами.
     */
    private fun parseArpDeviceFromContainer(
        obj: JSONObject,
        key: String,
        result: MutableMap<String, String>
    ) {
        if (!obj.has(key)) return
        val arr = obj.getJSONArray(key)
        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            parseArpDeviceEntry(entry, result)
        }
    }

    /**
     * Парсит один элемент устройства — извлекает MAC и IP.
     * Поддерживает разные названия полей.
     */
    private fun parseArpDeviceEntry(entry: JSONObject, result: MutableMap<String, String>) {
        // Возможные названия полей для MAC
        val macField = entry.optString("mac", null) ?:
            entry.optString("MAC", null) ?:
            entry.optString("macAddress", null) ?:
            entry.optString("MacAddress", null) ?:
            entry.optString("physAddress", null) ?:
            entry.optString("PhysAddress", null) ?:
            entry.optString("wlan_mac", null) ?:
            return // не нашли MAC

        // Возможные названия полей для IP
        val ipField = entry.optString("ip", null) ?:
            entry.optString("IP", null) ?:
            entry.optString("ipAddress", null) ?:
            entry.optString("IpAddress", null) ?:
            entry.optString("lan_ip", null) ?:
            return // не нашли IP

        val mac = macField.lowercase()
        val ip = ipField.trim()

        if (mac.isNotBlank() && ip.isNotBlank() &&
            android.util.Patterns.IP_ADDRESS.matcher(ip).matches()
        ) {
            result[ip] = normalizeMac(mac)
        }
    }

    /**
     * Парсит ARP/клиентов из HTML-таблицы (старые TP-Link прошивки).
     */
    private fun parseArpFromHtml(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        try {
            // Ищем строки с MAC и IP в HTML
            // Формат: <td>aa-bb-cc-dd-ee-ff</td><td>192.168.0.100</td>
            val macPattern = Regex("""([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}""")
            val ipPattern = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

            val macs = macPattern.findAll(html).map { it.value }.toList()
            val ips = ipPattern.findAll(html).map { it.value }.toList()

            // Если MAC и IP поровну — сопоставляем
            if (macs.isNotEmpty() && macs.size == ips.size) {
                for (i in macs.indices) {
                    result[ips[i]] = normalizeMac(macs[i])
                }
            }
        } catch (e: Exception) {
            println("TP-Link: HTML parse error: ${e.message}")
        }

        return result
    }

    private fun normalizeMac(mac: String): String {
        val cleaned = mac.lowercase().replace("-", ":")
        return if (cleaned.length == 17) cleaned
        else if (cleaned.length == 12) cleaned.chunked(2).joinToString(":")
        else cleaned
    }
}

// ════════════════════ D-Link Handler (заглушка) ════════════════════

/**
 * Обработчик для D-Link роутеров.
 *
 * ⚠️ ЗАГЛУШКА — реализация будет добавлена в v2.
 *
 * Типичный API D-Link:
 * 1. GET /login.html
 * 2. POST /cgi-bin/webproc с параметрами: getpage=html/index.html, login=..., pass=...
 * 3. Сессия через cookie
 * 4. GET /cgi-bin/webproc?getpage=html/status/device.htm для списка клиентов
 */
class DLinkHttpHandler : RouterHttpHandler {

    override fun matches(loginPageHtml: String): Boolean {
        val lower = loginPageHtml.lowercase()
        return lower.contains("d-link") || lower.contains("dlink") ||
                lower.contains("dir-") || lower.contains("dwr-")
    }

    override suspend fun login(
        client: OkHttpClient,
        baseUrl: String,
        username: String,
        password: String
    ): String? {
        println("D-Link: login — заглушка. Реализация будет в v2.")
        return null
    }

    override suspend fun getArpTable(
        client: OkHttpClient,
        baseUrl: String,
        token: String
    ): Map<String, String> {
        println("D-Link: getArpTable — заглушка. Реализация будет в v2.")
        return emptyMap()
    }
}

// ════════════════════ ASUS Handler (заглушка) ════════════════════

/**
 * Обработчик для ASUS роутеров.
 *
 * ⚠️ ЗАГЛУШКА — реализация будет добавлена в v2.
 *
 * ASUS использует httpd + /login.cgi для авторизации.
 * ARP таблица доступна через: /appGet.cgi?hook=arp_table()
 */
class AsusHttpHandler : RouterHttpHandler {

    override fun matches(loginPageHtml: String): Boolean {
        val lower = loginPageHtml.lowercase()
        return lower.contains("asus") || lower.contains("rt-") ||
                lower.contains("wifi") && lower.contains("router")
    }

    override suspend fun login(
        client: OkHttpClient,
        baseUrl: String,
        username: String,
        password: String
    ): String? {
        println("ASUS: login — заглушка. Реализация будет в v2.")
        return null
    }

    override suspend fun getArpTable(
        client: OkHttpClient,
        baseUrl: String,
        token: String
    ): Map<String, String> {
        println("ASUS: getArpTable — заглушка. Реализация будет в v2.")
        return emptyMap()
    }
}

// ════════════════════ Keenetic Handler (заглушка) ════════════════════

/**
 * Обработчик для Keenetic (Zyxel) роутеров.
 *
 * ⚠️ ЗАГЛУШКА — реализация будет добавлена в v2.
 *
 * Keenetic использует:
 * 1. GET /a?username=...&password=... для basic auth
 * 2. GET /rci/ip/hotspot для списка клиентов
 */
class KeeneticHttpHandler : RouterHttpHandler {

    override fun matches(loginPageHtml: String): Boolean {
        val lower = loginPageHtml.lowercase()
        return lower.contains("keenetic") || lower.contains("zyxel") ||
                lower.contains("ndms")
    }

    override suspend fun login(
        client: OkHttpClient,
        baseUrl: String,
        username: String,
        password: String
    ): String? {
        println("Keenetic: login — заглушка. Реализация будет в v2.")
        return null
    }

    override suspend fun getArpTable(
        client: OkHttpClient,
        baseUrl: String,
        token: String
    ): Map<String, String> {
        println("Keenetic: getArpTable — заглушка. Реализация будет в v2.")
        return emptyMap()
    }
}


