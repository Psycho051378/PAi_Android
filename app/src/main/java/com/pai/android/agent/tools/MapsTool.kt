package com.pai.android.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pai.android.agent.AgentTool
import com.pai.android.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MapsTool - работа с картами без внешних API-ключей.
 *
 * Использует экосистему OpenStreetMap:
 * - Nominatim (геокодинг) - бесплатно, без ключа
 * - Overpass API (поиск POI) - бесплатно, без ключа
 * - OSRM (маршруты) - бесплатно, без ключа
 * - Android Intent (открыть в картах) - системный
 *
 * Действия:
 * - action=geocode: адрес → координаты
 * - action=reverse_geocode: координаты → адрес
 * - action=places_search: POI рядом с точкой
 * - action=route: маршрут между точками
 * - action=maps_open: открыть в картах
 */
@Singleton
class MapsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : AgentTool {

    override val name: String = "maps"
    override val description: String = "Maps & navigation: geocode (address to coordinates), reverse_geocode (coords to address), places_search (find POIs near location - fuel, cafe, atm, restaurant, pharmacy, hospital, supermarket, parking), route (driving directions between two points), maps_open (open location in Google Maps or Yandex Maps app). No API keys required - uses OpenStreetMap ecosystem."

    override val parametersSchema: String = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["geocode", "reverse_geocode", "places_search", "route", "maps_open"],
                "description": "Action to perform"
            },
            "query": {
                "type": "string",
                "description": "Address or place name for geocode/places_search"
            },
            "lat": {
                "type": "string",
                "description": "Latitude for reverse_geocode/places_search/route origin"
            },
            "lon": {
                "type": "string",
                "description": "Longitude for reverse_geocode/places_search/route origin"
            },
            "to_lat": {
                "type": "string",
                "description": "Destination latitude for route"
            },
            "to_lon": {
                "type": "string",
                "description": "Destination longitude for route"
            },
            "type": {
                "type": "string",
                "description": "POI type for places_search: fuel, cafe, atm, restaurant, pharmacy, hospital, supermarket, parking, hotel, school"
            },
            "radius": {
                "type": "string",
                "description": "Search radius in meters for places_search (default: 1000)"
            },
            "limit": {
                "type": "string",
                "description": "Max results (default: 5)"
            }
        },
        "required": ["action"]
    }"""

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString() ?: return ToolResult.Error(error = "Missing action parameter")

        return when (action) {
            "geocode" -> doGeocode(params)
            "reverse_geocode" -> doReverseGeocode(params)
            "places_search" -> doPlacesSearch(params)
            "route" -> doRoute(params)
            "maps_open" -> doMapsOpen(params)
            else -> ToolResult.Error(error = "Unknown action: $action")
        }
    }

    // ======================== 1. GEOCODE (адрес → координаты) ========================

    /**
     * Forward geocoding via Nominatim.
     * Параметры: query (адрес или название места)
     * Результат: lat, lon, display_name
     */
    private suspend fun doGeocode(params: Map<String, Any>): ToolResult {
        val query = params["query"]?.toString()?.trim()
            ?: return ToolResult.Error(error = "Missing query parameter (address or place name)")

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=5&addressdetails=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "PaiAndroid/1.0 (maps_tool)")

                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val response = reader.readText()
                reader.close()
                conn.disconnect()

                val jsonArray = JSONArray(response)
                if (jsonArray.length() == 0) {
                    return@withContext ToolResult.Success(
                        output = "?? Ничего не найдено по запросу: $query",
                        data = mapOf("found" to "false", "query" to query)
                    )
                }

                val results = mutableListOf<Map<String, String>>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    results.add(mapOf(
                        "lat" to item.optString("lat", "0"),
                        "lon" to item.optString("lon", "0"),
                        "display_name" to item.optString("display_name", ""),
                        "type" to item.optString("type", ""),
                        "category" to item.optString("category", "")
                    ))
                }

                val sb = StringBuilder("?? **Результаты геокодирования:**\n")
                val first = results.first()
                sb.appendLine("📍 ${first["display_name"]}")
                sb.appendLine("?? Координаты: ${first["lat"]}, ${first["lon"]}")
                if (results.size > 1) {
                    sb.appendLine("\n?? **Другие варианты (${results.size - 1}):**")
                    results.drop(1).forEachIndexed { idx, r ->
                        sb.appendLine("${idx + 2}. ${r["display_name"]} - ${r["lat"]}, ${r["lon"]}")
                    }
                }

                ToolResult.Success(
                    output = sb.toString().trimEnd(),
                    data = mapOf(
                        "found" to "true",
                        "count" to results.size.toString(),
                        "lat" to (first["lat"] ?: "0"),
                        "lon" to (first["lon"] ?: "0"),
                        "display_name" to (first["display_name"] ?: ""),
                        "results" to results.joinToString("|") { r ->
                            "${r["lat"]},${r["lon"]}|${r["display_name"]}"
                        }
                    )
                )
            } catch (e: Exception) {
                ToolResult.Error(error = "Geocode error: ${e.message}")
            }
        }
    }

    // ======================== 2. REVERSE GEOCODE (координаты → адрес) ========================

    /**
     * Reverse geocoding via Nominatim.
     * Параметры: lat, lon
     * Результат: display_name, address components
     */
    private suspend fun doReverseGeocode(params: Map<String, Any>): ToolResult {
        val lat = params["lat"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.Error(error = "Missing or invalid lat parameter")
        val lon = params["lon"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.Error(error = "Missing or invalid lon parameter")

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&addressdetails=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "PaiAndroid/1.0 (maps_tool)")

                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val response = reader.readText()
                reader.close()
                conn.disconnect()

                val json = JSONObject(response)
                if (json.has("error")) {
                    return@withContext ToolResult.Success(
                        output = "?? Не удалось определить адрес для координат $lat, $lon",
                        data = mapOf("found" to "false")
                    )
                }

                val displayName = json.optString("display_name", "")
                val address = json.optJSONObject("address")
                val addressParts = mutableListOf<String>()
                if (address != null) {
                    val fields = listOf("road", "house_number", "city", "town", "village",
                        "state", "country", "postcode")
                    for (field in fields) {
                        address.optString(field)?.takeIf { it.isNotBlank() }?.let { addressParts.add(it) }
                    }
                }

                val sb = StringBuilder("?? **Обратный геокодинг:**\n")
                sb.appendLine("📍 $displayName")
                sb.appendLine("?? Координаты: $lat, $lon")
                if (addressParts.isNotEmpty()) {
                    sb.appendLine("?? Адрес: ${addressParts.joinToString(", ")}")
                }

                ToolResult.Success(
                    output = sb.toString().trimEnd(),
                    data = mapOf(
                        "found" to "true",
                        "display_name" to displayName,
                        "address" to addressParts.joinToString(", "),
                        "lat" to lat.toString(),
                        "lon" to lon.toString()
                    )
                )
            } catch (e: Exception) {
                ToolResult.Error(error = "Reverse geocode error: ${e.message}")
            }
        }
    }

    // ======================== 3. PLACES SEARCH (поиск POI рядом) ========================

    /**
     * Search points of interest near a location via Overpass API.
     * Параметры: lat, lon, type (POI type), radius (meters), limit
     *
     * Типы POI (overpass tags):
     *   fuel → amenity=fuel
     *   cafe → amenity=cafe
     *   atm → amenity=atm, amenity=bank
     *   restaurant → amenity=restaurant
     *   pharmacy → amenity=pharmacy
     *   hospital → amenity=hospital
     *   supermarket → shop=supermarket
     *   parking → amenity=parking
     *   hotel → tourism=hotel
     *   school → amenity=school
     */
    private suspend fun doPlacesSearch(params: Map<String, Any>): ToolResult {
        val lat = params["lat"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.Error(error = "Missing or invalid lat parameter")
        val lon = params["lon"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.Error(error = "Missing or invalid lon parameter")
        val type = params["type"]?.toString()?.lowercase()?.trim()
            ?: return ToolResult.Error(error = "Missing type parameter (e.g. fuel, cafe, atm, restaurant)")
        val radius = params["radius"]?.toString()?.toIntOrNull() ?: 1000
        val limit = params["limit"]?.toString()?.toIntOrNull() ?: 5

        // Маппинг типов POI → Overpass query
        val overpassQuery = buildOverpassQuery(type, lat, lon, radius)
        if (overpassQuery == null) {
            return ToolResult.Error(error = "Unknown POI type: $type. Supported: fuel, cafe, atm, restaurant, pharmacy, hospital, supermarket, parking, hotel, school")
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://overpass-api.de/api/interpreter")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("User-Agent", "PaiAndroid/1.0 (maps_tool)")

                val postData = "data=${URLEncoder.encode(overpassQuery, "UTF-8")}"
                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(postData)
                writer.flush()
                writer.close()

                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val response = reader.readText()
                reader.close()
                val responseCode = conn.responseCode
                conn.disconnect()

                println("?? MapsTool: Overpass response code=$responseCode, length=${response.length}")
                val json = JSONObject(response)
                val elements = json.optJSONArray("elements")
                if (elements != null) println("?? MapsTool: Overpass elements=${elements.length()}")
                if (elements == null || elements.length() == 0) {
                    return@withContext ToolResult.Success(
                        output = "?? Ничего не найдено в радиусе ${radius}м от $lat, $lon\nТип: $type\nПопробуйте увеличить радиус или изменить тип поиска.",
                        data = mapOf(
                            "found" to "false",
                            "type" to type,
                            "lat" to lat.toString(),
                            "lon" to lon.toString(),
                            "nearest_lat" to lat.toString(),
                            "nearest_lon" to lon.toString(),
                            "nearest_name" to "-"
                        )
                    )
                }

                val results = mutableListOf<Map<String, String>>()
                for (i in 0 until minOf(elements.length(), limit)) {
                    val el = elements.getJSONObject(i)
                    val elLat = el.optDouble("lat", el.optJSONObject("center")?.optDouble("lat", 0.0) ?: 0.0)
                    val elLon = el.optDouble("lon", el.optJSONObject("center")?.optDouble("lon", 0.0) ?: 0.0)
                    val tags = el.optJSONObject("tags")
                    val name = tags?.optString("name", "") ?: ""
                    val operator = tags?.optString("operator", "") ?: ""
                    val addrStreet = tags?.optString("addr:street", "") ?: ""
                    val addrHouse = tags?.optString("addr:housenumber", "") ?: ""
                    val phone = tags?.optString("phone", "") ?: ""

                    val distance = haversine(lat, lon, elLat, elLon)

                    val addressStr = buildString {
                        if (addrStreet.isNotBlank()) {
                            append(addrStreet)
                            if (addrHouse.isNotBlank()) append(", $addrHouse")
                        }
                        if (operator.isNotBlank() && name.isBlank()) {
                            if (isNotEmpty()) append(" - ")
                            append("($operator)")
                        }
                    }

                    results.add(mapOf(
                        "name" to (name.ifBlank { type.replaceFirstChar { it.uppercase() } }),
                        "lat" to String.format("%.6f", elLat),
                        "lon" to String.format("%.6f", elLon),
                        "address" to addressStr,
                        "distance" to distance,
                        "operator" to operator,
                        "phone" to phone
                    ))
                }

                val typeLabels = mapOf(
                    "fuel" to "?? АЗС",
                    "cafe" to "☕ Кафе",
                    "atm" to "?? Банкоматы",
                    "restaurant" to "?? Рестораны",
                    "pharmacy" to "?? Аптеки",
                    "hospital" to "?? Больницы",
                    "supermarket" to "?? Супермаркеты",
                    "parking" to "?? Парковки",
                    "hotel" to "?? Отели",
                    "school" to "?? Школы"
                )
                val label = typeLabels[type] ?: "📍 $type"

                val sb = StringBuilder("$label рядом с вами (радиус ${radius}м):\n\n")
                results.forEachIndexed { idx, r ->
                    val dist = r["distance"] ?: "?"
                    val name = r["name"] ?: "?"
                    val addr = r["address"]?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
                    sb.appendLine("${idx + 1}. **$name**${addr}")
                    sb.appendLine("   ?? ~${dist} · ?? ${r["lat"]}, ${r["lon"]}")
                }

                sb.appendLine("\n?? Для маршрута: maps action=route from_lat=$lat from_lon=$lon to_lat=${results.first()["lat"]} to_lon=${results.first()["lon"]}")
                sb.append("?? Открыть в картах: maps action=maps_open lat=${results.first()["lat"]} lon=${results.first()["lon"]}")

                ToolResult.Success(
                    output = sb.toString().trimEnd(),
                    data = mapOf(
                        "found" to "true",
                        "count" to results.size.toString(),
                        "type" to type,
                        "lat" to (results.first()["lat"] ?: "0"),
                        "lon" to (results.first()["lon"] ?: "0"),
                        "name" to (results.first()["name"] ?: ""),
                        "results" to results.joinToString("|") { r ->
                            "${r["name"]}|${r["lat"]},${r["lon"]}|${r["distance"]}|${r["address"]}"
                        },
                        "nearest_lat" to (results.first()["lat"] ?: "0"),
                        "nearest_lon" to (results.first()["lon"] ?: "0"),
                        "nearest_name" to (results.first()["name"] ?: "")
                    )
                )
            } catch (e: Exception) {
                ToolResult.Error(error = "Places search error: ${e.message}")
            }
        }
    }

    /**
     * Строит Overpass QL запрос для заданного типа POI.
     */
    private fun buildOverpassQuery(type: String, lat: Double, lon: Double, radius: Int): String? {
        val tags = when (type) {
            "fuel" -> """["amenity"="fuel"]"""
            "cafe" -> """["amenity"="cafe"]"""
            "atm" -> """["amenity"~"atm|bank"]"""
            "restaurant" -> """["amenity"="restaurant"]"""
            "pharmacy" -> """["amenity"="pharmacy"]"""
            "hospital" -> """["amenity"="hospital"]"""
            "supermarket" -> """["shop"="supermarket"]"""
            "parking" -> """["amenity"="parking"]"""
            "hotel" -> """["tourism"="hotel"]"""
            "school" -> """["amenity"="school"]"""
            else -> return null
        }
        return "[out:json];(node(around:$radius,$lat,$lon)$tags;way(around:$radius,$lat,$lon)$tags;);out center $radius;"
    }

    // ======================== 4. ROUTE (маршрут) ========================

    /**
     * Маршрут между двумя точками через OSRM.
     * Параметры: from_lat, from_lon, to_lat, to_lon (или lat, lon, to_lat, to_lon)
     * Результат: расстояние, время, полилиния
     */
    private suspend fun doRoute(params: Map<String, Any>): ToolResult {
        val fromLat = params["from_lat"]?.toString()?.toDoubleOrNull()
            ?: params["lat"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.Error(error = "Missing from_lat (or lat)")
        val fromLon = params["from_lon"]?.toString()?.toDoubleOrNull()
            ?: params["lon"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.Error(error = "Missing from_lon (or lon)")
        val toLat = params["to_lat"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.Error(error = "Missing to_lat")
        val toLon = params["to_lon"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.Error(error = "Missing to_lon")

        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&steps=true&alternatives=false")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "PaiAndroid/1.0 (maps_tool)")

                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val response = reader.readText()
                reader.close()
                conn.disconnect()

                val json = JSONObject(response)
                if (json.optString("code") != "Ok") {
                    val msg = json.optString("message", "Unknown error")
                    return@withContext ToolResult.Error(error = "Route error: $msg")
                }

                val route = json.getJSONArray("routes").getJSONObject(0)
                val distanceMeters = route.optDouble("distance", 0.0)
                val durationSeconds = route.optDouble("duration", 0.0)
                val geometry = route.optString("geometry", "")  // Encoded polyline

                val distanceKm = distanceMeters / 1000.0
                val durationMin = (durationSeconds / 60.0).toInt()

                val durationStr = buildString {
                    val hours = durationMin / 60
                    val mins = durationMin % 60
                    if (hours > 0) append("${hours}ч ")
                    append("${mins}мин")
                }

                val steps = route.optJSONArray("legs")?.optJSONObject(0)?.optJSONArray("steps")
                val instructions = mutableListOf<String>()
                if (steps != null) {
                    for (i in 0 until minOf(steps.length(), 8)) {
                        val step = steps.getJSONObject(i)
                        val instr = step.optString("maneuver", "")
                            ?.let { step.optJSONObject(it)?.optString("modifier", "") }
                            ?: ""
                        val text = step.optString("name", "")?.takeIf { it.isNotBlank() }
                            ?: step.optString("ref", "")?.takeIf { it.isNotBlank() }
                            ?: ""
                        val distance = step.optDouble("distance", 0.0)
                        if (text.isNotBlank() && distance > 10) {
                            instructions.add("   ?? ${distance.toInt()}м - $text")
                        }
                    }
                }

                val sb = StringBuilder("?? **Маршрут**\n")
                sb.appendLine("📍 ${String.format("%.5f", fromLat)}, ${String.format("%.5f", fromLon)}")
                sb.appendLine("   ➡️  ${String.format("%.5f", toLat)}, ${String.format("%.5f", toLon)}")
                sb.appendLine()
                sb.appendLine("?? Расстояние: **${String.format("%.1f", distanceKm)} км**")
                sb.appendLine("?? Время в пути: **$durationStr**")
                if (instructions.isNotEmpty()) {
                    sb.appendLine("\n?? **Краткий маршрут:**")
                    instructions.take(5).forEach { sb.appendLine(it) }
                    if (instructions.size > 5) {
                        sb.appendLine("   ... и ещё ${instructions.size - 5} шагов")
                    }
                }
                sb.appendLine("\n?? Открыть в Google Maps:")
                sb.appendLine("   maps action=maps_open to_lat=${String.format("%.5f", toLat)} to_lon=${String.format("%.5f", toLon)}")

                ToolResult.Success(
                    output = sb.toString().trimEnd(),
                    data = mapOf(
                        "distance_meters" to distanceMeters.toInt().toString(),
                        "distance_km" to String.format("%.1f", distanceKm),
                        "duration_seconds" to durationSeconds.toInt().toString(),
                        "duration_minutes" to durationMin.toString(),
                        "duration_text" to durationStr,
                        "from_lat" to fromLat.toString(),
                        "from_lon" to fromLon.toString(),
                        "to_lat" to toLat.toString(),
                        "to_lon" to toLon.toString(),
                        "geometry" to geometry
                    )
                )
            } catch (e: Exception) {
                ToolResult.Error(error = "Route error: ${e.message}")
            }
        }
    }

    // ======================== 5. MAPS OPEN (открыть в картах) ========================

    /**
     * Открыть координаты/адрес в приложении карт.
     * Приоритет: Яндекс Карты (если установлены) → Google Maps.
     * Параметры: lat, lon (или address)
     * Опционально: label - название точки
     */
    private suspend fun doMapsOpen(params: Map<String, Any>): ToolResult {
        val lat = params["lat"]?.toString()?.toDoubleOrNull()
        val lon = params["lon"]?.toString()?.toDoubleOrNull()
        val fromLat = params["from_lat"]?.toString()?.toDoubleOrNull()
        val fromLon = params["from_lon"]?.toString()?.toDoubleOrNull()
        val toLat = params["to_lat"]?.toString()?.toDoubleOrNull() ?: lat
        val toLon = params["to_lon"]?.toString()?.toDoubleOrNull() ?: lon
        val address = params["address"]?.toString()?.trim()

        return withContext(Dispatchers.IO) {
            try {
                val hasRoute = fromLat != null && fromLon != null && toLat != null && toLon != null

                // Яндекс Карты (с маршрутом если есть from + to)
                val yandexIntent = try {
                    val uri = if (hasRoute) {
                        "yandexmaps://maps.yandex.ru/?rtext=$fromLat,$fromLon~$toLat,$toLon&rtt=auto"
                    } else if (toLat != null && toLon != null) {
                        "yandexmaps://maps.yandex.ru/?pt=$toLon,$toLat&z=16"
                    } else if (address != null) {
                        "yandexmaps://maps.yandex.ru/?text=${URLEncoder.encode(address, "UTF-8")}"
                    } else null
                    uri?.let {
                        Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    }
                } catch (_: Exception) { null }

                if (yandexIntent != null && yandexIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(yandexIntent)
                    return@withContext ToolResult.Success(
                        output = "?? Открыто в Яндекс Картах с маршрутом",
                        data = mapOf("app" to "yandex_maps")
                    )
                }

                // Google Maps directions (если Яндекс не установлен)
                if (hasRoute) {
                    val dirsUri = "https://www.google.com/maps/dir/?api=1&origin=$fromLat,$fromLon&destination=$toLat,$toLon&travelmode=driving"
                    val dirsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dirsUri)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(dirsIntent)
                    return@withContext ToolResult.Success(
                        output = "?? Открыт маршрут в Google Картах",
                        data = mapOf("app" to "google_maps_directions")
                    )
                }

                // Без маршрута - просто пин
                val googleUri = buildString {
                    append("https://maps.google.com/maps?")
                    if (toLat != null && toLon != null) {
                        append("q=$toLat,$toLon")
                    } else if (address != null) {
                        append("q=${URLEncoder.encode(address, "UTF-8")}")
                    } else {
                        append("q=current+location")
                    }
                }

                val gmmIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = if (toLat != null && toLon != null) {
                        Uri.parse("geo:$toLat,$toLon?q=$toLat,$toLon")
                    } else {
                        Uri.parse("geo:0,0?q=${URLEncoder.encode(address ?: "location", "UTF-8")}")
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                if (gmmIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(gmmIntent)
                    return@withContext ToolResult.Success(
                        output = "?? Открыто в Google Картах (пин)",
                        data = mapOf("app" to "google_maps")
                    )
                }

                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(googleUri)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                ToolResult.Success(
                    output = "?? Открыто в браузере (Google Maps)",
                    data = mapOf("app" to "browser")
                )
            } catch (e: Exception) {
                ToolResult.Error(error = "Failed to open maps: ${e.message}")
            }
        }
    }

    // ======================== УТИЛИТЫ ========================

    /**
     * Haversine distance между двумя координатами (в метрах).
     */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        val dist = (R * c).toInt()

        return if (dist < 1000) "${dist}м"
        else String.format("%.1fкм", dist / 1000.0)
    }
}
