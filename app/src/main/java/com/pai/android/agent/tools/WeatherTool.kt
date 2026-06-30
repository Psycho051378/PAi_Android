package com.pai.android.agent.tools

import com.pai.android.agent.BaseAgentTool
import com.pai.android.agent.ToolResult
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.Message
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Инструмент погоды. Использует wttr.in (без API ключа) + Open-Meteo (fallback).
 * Поддерживает AI-форматирование сложных прогнозов (>1 день).
 */
class WeatherTool(
    private val aiRepository: AiRepository? = null
) : BaseAgentTool() {

    override val name: String = "weather"
    override val description: String = "Get weather forecast for any city"
    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["forecast"],
                    "description": "Получить прогноз"
                },
                "city": {
                    "type": "string",
                    "description": "Название города"
                },
                "days": {
                    "type": "integer",
                    "description": "Количество дней (1-7)",
                    "default": 1
                }
            },
            "required": ["city"]
        }
    """.trimIndent()
    override val requiresConfirmation: Boolean = false

    private val cityCoords = mapOf(
        "санкт-петербург" to Pair(59.93, 30.31), "петербург" to Pair(59.93, 30.31), "петергоф" to Pair(59.88, 29.91),
        "спб" to Pair(59.93, 30.31), "москва" to Pair(55.75, 37.61), "msk" to Pair(55.75, 37.61),
        "новосибирск" to Pair(55.03, 82.92), "екатеринбург" to Pair(56.83, 60.60),
        "казань" to Pair(55.79, 49.10), "нижний новгород" to Pair(56.32, 44.00),
        "челябинск" to Pair(55.15, 61.42), "омск" to Pair(54.99, 73.36),
        "самара" to Pair(53.19, 50.10), "ростов-на-дону" to Pair(47.23, 39.71),
        "уфа" to Pair(54.73, 55.96), "красноярск" to Pair(56.01, 92.85),
        "пермь" to Pair(58.01, 56.25), "воронеж" to Pair(51.66, 39.19),
        "волгоград" to Pair(48.70, 44.51), "краснодар" to Pair(45.03, 38.97)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val city = (params["city"] as? String ?: params["query"] as? String)
                ?: return ToolResult.Error("Не указан город")
            val days = (params["days"] as? Number)?.toInt()?.coerceIn(1, 16) ?: 7
            val normalized = city.lowercase().trim()

            // 1. wttr.in (только <=7 дней) (только для <=7 дней)
            try {
                val wttrJson = fetchWttr(normalized, days)
                if (wttrJson != null) {
                    val json = org.json.JSONObject(wttrJson)
                    val wttrDays = json.optJSONArray("weather")?.length() ?: 0
                    if (wttrDays >= days || wttrDays > 0) {
                        var formatted = formatResult(json, city, days, "wttr.in")
                        if (days > 1) { formatted = formatWithAI(formatted, city, days, "") }
                        return ToolResult.Success(output = formatted,
                            data = mapOf("city" to city, "source" to "wttr.in", "raw_json" to wttrJson, "goal_achieved" to true))
                    }
                }
            } catch (_: Exception) {}

            // 5. Только Open-Meteo (fallback)
            val omFallback = fetchOpenMeteo(normalized, days)
            if (omFallback != null) {
                var formatted = formatOpenMeteoResult(omFallback, city, days)
                if (days > 1) { formatted = formatWithAI(formatted, city, days, "") }
                return ToolResult.Success(output = formatted,
                    data = mapOf("city" to city, "source" to "open-meteo", "raw_json" to omFallback, "goal_achieved" to true))
            }

            ToolResult.Error("Не удалось получить погоду для '$city'. Попробуйте другой город.") 
        } catch (e: Exception) {
            ToolResult.Error("Ошибка получения погоды: ${e.message}")
        }
    }
    
    /**
     * AI-форматирование для сложных прогнозов (>1 день).
     * Вызывается из execute, если aiRepository передан и days > 1.
     */
    private suspend fun formatWithAI(rawOutput: String, city: String, days: Int, query: String): String {
        if (days <= 1) return rawOutput
        val ai = aiRepository ?: return rawOutput
        return try {
            val prompt = """
                Ты — метеоролог-аналитик. На основе сырых данных составь красивый прогноз погоды.
                Город: $city
                Дней: $days
                Сырые данные:
                ```
                ${rawOutput.take(3000)}
                ```
                Требования:
                1. Используй эмодзи (☀️ 🌤 ⛅ ☁️ 🌧 🌨 ⛈ 🌡 💨 💧)
                2. Каждый день — отдельный блок
                3. В конце — краткое резюме (самый тёплый/холодный день)
                4. Reply in user language. Max 20 lines.
            """.trimIndent()
            val response = ai.sendMessage(
                messages = listOf(Message.createUserMessage("weather_format", prompt)),
                systemPrompt = "You are a meteorologist. Format the weather forecast.",
                memoryContext = ""
            )
            response.getOrThrow().text.ifBlank { rawOutput.take(2000) }
        } catch (e: Exception) {
            rawOutput.take(2000)
        }
    }

    private fun fetchWttr(city: String, days: Int = 7): String? {
        val encoded = URLEncoder.encode(city, "UTF-8")
        val url = URL("https://wttr.in/$encoded?format=j1&lang=ru&days=$days")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        if (conn.responseCode == 200) {
            return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        }
        conn.disconnect()
        return null
    }

    private fun fetchOpenMeteo(cityOrLat: String, days: Int): String? {
        // Определяем координаты города или используем как есть
        val lower = cityOrLat.lowercase()
        val coords = cityCoords[lower]
            ?: cityCoords.entries.find { lower.contains(it.key) || it.key.contains(lower) }?.value
        if (coords == null) return null
        return fetchOpenMeteoByCoords(coords.first, coords.second, days)
    }
    
    private fun fetchOpenMeteoByCoords(lat: Double, lon: Double, days: Int): String? {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,weathercode&current_weather=true&timezone=auto&forecast_days=$days")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        if (conn.responseCode == 200) {
            return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        }
        conn.disconnect()
        return null
    }

    private fun formatHybrid(wttr: org.json.JSONObject, om: org.json.JSONObject, city: String, days: Int): String {
        val sb = StringBuilder()
        sb.appendLine("🌤 **Погода в $city — сводный прогноз на $days дн.**\n")
        
        // Текущая погода из wttr
        try {
            val current = wttr.getJSONArray("current_condition").getJSONObject(0)
            val temp = current.optString("temp_C", "?")
            val feels = current.optString("FeelsLikeC", "?")
            val desc = current.optJSONArray("lang_ru")?.getJSONObject(0)?.optString("value", "?") ?: "?"
            sb.appendLine("**Сейчас:** $temp°C (ощущается $feels°C) — $desc\n")
        } catch (_: Exception) {}
        
        // Дни из wttr.in
        try {
            val weather = wttr.getJSONArray("weather")
            val wCount = minOf(weather.length(), 7, days)
            if (wCount > 0) {
                sb.appendLine("**📊 Ближайшие $wCount дн. (wttr.in):**\n")
                for (i in 0 until wCount) {
                    val day = weather.getJSONObject(i)
                    val date = day.optString("date", "?")
                    val max = day.optString("maxtempC", "?")
                    val min = day.optString("mintempC", "?")
                    val noon = day.optJSONArray("hourly")?.optJSONObject(5)
                    val desc = noon?.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "—") ?: "—"
                    val rain = noon?.optString("precipMM", "0")?.toDoubleOrNull() ?: 0.0
                    val wind = noon?.optString("windspeedKmph", "0") ?: "0"
                    val dayName = try {
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale("ru"))
                        val d = fmt.parse(date)
                        java.text.SimpleDateFormat("dd.MM EEE", java.util.Locale("ru")).format(d)
                    } catch (_: Exception) { date }
                    sb.appendLine("  $dayName: $min..$max°C, $desc${if (rain > 0) " 🌧$rain мм" else ""} 💨$wind км/ч")
                }
                sb.appendLine()
            }
        } catch (_: Exception) {}
        
        // Оставшиеся дни из Open-Meteo
        try {
            val daily = om.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val tempsMax = daily.getJSONArray("temperature_2m_max")
            val tempsMin = daily.getJSONArray("temperature_2m_min")
            val precip = daily.getJSONArray("precipitation_sum")
            val wind = daily.getJSONArray("wind_speed_10m_max")
            val codes = daily.getJSONArray("weathercode")
            
            val start = minOf(wttr.optJSONArray("weather")?.length() ?: 0, 7)
            val total = minOf(dates.length(), days)
            
            if (total > start) {
                sb.appendLine("**🔮 Ещё ${total - start} дн. (Open-Meteo):**\n")
                sb.appendLine("| Дата | 🌡 | 🌧 | 💨 | ☁️ |")
                sb.appendLine("|------|:---:|:---:|:---:|:---:|")
                for (i in start until total) {
                    val date = dates.optString(i, "?")
                    val max = String.format("%.1f", tempsMax.getDouble(i))
                    val min = String.format("%.1f", tempsMin.getDouble(i))
                    val p = precip.optDouble(i, 0.0)
                    val w = String.format("%.1f", wind.getDouble(i))
                    val code = codes.optInt(i, -1)
                    val dayName = try {
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale("ru"))
                        val d = fmt.parse(date)
                        java.text.SimpleDateFormat("dd.MM EEE", java.util.Locale("ru")).format(d)
                    } catch (_: Exception) { date }
                    val pStr = if (p > 0) "${p}мм" else "—"
                    sb.appendLine("| $dayName | $min..$max°C | $pStr | ${w}км/ч | ${weatherEmoji(code)} |")
                }
                sb.appendLine()
            }
        } catch (_: Exception) {}
        
        // Общее резюме
        sb.appendLine("📊 **Резюме:** Город $city, прогноз на $days дн.")
        sb.appendLine("Источники: wttr.in + Open-Meteo")
        return sb.toString()
    }

    private fun formatResult(json: org.json.JSONObject, city: String, days: Int, source: String): String {
        val current = json.optJSONArray("current_condition")?.optJSONObject(0)
        val forecast = json.optJSONArray("weather")
        val sb = StringBuilder()
        sb.appendLine("🌤 **Погода в $city:**\n")
        if (current != null) {
            sb.appendLine("Сейчас: ${current.optJSONArray("lang_ru")?.optJSONObject(0)?.optString("value", "?")}, " +
                "${current.optString("temp_C", "?")}°C (ощущается ${current.optString("FeelsLikeC", "?")}°C)")
            sb.appendLine("Ветер: ${current.optString("winddir16Point", "?")} ${current.optString("windspeedKmph", "?")} км/ч")
            sb.appendLine("Влажность: ${current.optString("humidity", "?")}% | Видимость: ${current.optString("visibilityKM", "?")} км\n")
        }
        if (forecast != null) {
            sb.appendLine("Прогноз на ${days.coerceAtMost(forecast.length())} дн.:")
            for (i in 0 until days.coerceAtMost(forecast.length())) {
                val day = forecast.getJSONObject(i)
                val hourly = day.optJSONArray("hourly")?.optJSONObject(4)
                val desc = hourly?.optJSONArray("lang_ru")?.optJSONObject(0)?.optString("value", "?") ?: "?"
                sb.appendLine("  ${day.optString("date", "?")}: ${day.optString("mintempC", "?")}..${day.optString("maxtempC", "?")}°C, $desc")
            }
        }
        sb.appendLine("\nИсточник: $source")
        return sb.toString()
    }

    private fun formatOpenMeteoResult(json: String, city: String, days: Int): String {
        val root = org.json.JSONObject(json)
        val current = root.optJSONObject("current_weather")
        val daily = root.optJSONObject("daily")
        val sb = StringBuilder()
        sb.appendLine("🌤 **Погода в $city**\n")
        if (current != null) {
            val temp = current.optString("temperature", "?")
            val wind = current.optString("windspeed", "?")
            val wcode = current.optInt("weathercode", -1)
            sb.appendLine("**Сейчас:** ${weatherEmoji(wcode)} ${weatherDesc(wcode)}, $temp°C, ветер $wind км/ч\n")
        }
        if (daily != null) {
            val dates = daily.optJSONArray("time")
            val maxT = daily.optJSONArray("temperature_2m_max")
            val minT = daily.optJSONArray("temperature_2m_min")
            val precip = daily.optJSONArray("precipitation_sum")
            val wind = daily.optJSONArray("wind_speed_10m_max")
            val codes = daily.optJSONArray("weathercode")
            sb.appendLine("**📅 Прогноз на $days дн.:**\n")
            sb.appendLine("| Дата | 🌡 | 🌧 | 💨 | ☁️ |")
            sb.appendLine("|------|:---:|:---:|:---:|:---:|")
            val totalDays = days.coerceAtMost(dates?.length() ?: 0)
            for (i in 0 until totalDays) {
                val date = dates?.optString(i, "?") ?: "?"
                val max = maxT?.optString(i, "?") ?: "?"
                val min = minT?.optString(i, "?") ?: "?"
                val p = precip?.optString(i, "0") ?: "0"
                val w = wind?.optString(i, "?") ?: "?"
                val code = codes?.optInt(i, -1) ?: -1
                val dayName = date.let { 
                    try {
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale("ru"))
                        val d = fmt.parse(date) ?: date
                        java.text.SimpleDateFormat("dd.MM EEE", java.util.Locale("ru")).format(d)
                    } catch (_: Exception) { date }
                }
                val pStr = if (p.toFloatOrNull() ?: 0f > 0) "${p}мм" else "—"
                sb.appendLine("| $dayName | $min..$max°C | $pStr | ${w}км/ч | ${weatherEmoji(code)} |")
            }
        }
        sb.appendLine("\n📊 **Резюме:** Погода для города $city на ${days} дн.")
        sb.appendLine("Источник: Open-Meteo")
        return sb.toString()
    }
    
    private fun weatherDesc(code: Int): String = when (code) {
        0 -> "Ясно"
        1, 2, 3 -> "Облачно"
        45, 48 -> "Туман"
        51, 53, 55 -> "Морось"
        56, 57 -> "Ледяная морось"
        61, 63, 65 -> "Дождь"
        66, 67 -> "Ледяной дождь"
        71, 73, 75 -> "Снег"
        77 -> "Снежная крупа"
        80, 81, 82 -> "Ливень"
        85, 86 -> "Снегопад"
        95 -> "Гроза"
        96, 99 -> "Гроза с градом"
        else -> "Разная облачность"
    }
    
    private fun weatherEmoji(code: Int): String = when (code) {
        0 -> "☀️"
        1 -> "🌤"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫"
        51, 53, 55 -> "🌦"
        56, 57 -> "🌧"
        61, 63, 65 -> "🌧"
        66, 67 -> "🌧"
        71, 73, 75 -> "🌨"
        77 -> "🌨"
        80, 81, 82 -> "🌧"
        85, 86 -> "🌨"
        95 -> "⛈"
        96, 99 -> "⛈"
        else -> "☁️"
    }
}
