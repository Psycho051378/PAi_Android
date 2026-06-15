package com.pai.android.agent

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import org.json.JSONArray

/**
 * Навык погоды с гибридным источником данных:
 * - wttr.in (любой город, до 7 дней, подробно)
 * - Open-Meteo (предзаданные города, до 16 дней)
 * - >7 дней: комбинирует оба источника
 */
class WeatherSkill : Skill {

    override val name: String = "weather"

    override val description: String = "Get weather forecast: temperature, precipitation, wind for any city"

    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        return params["command"] == "weather"
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        return try {
            val queryFromParams = (params["query"] as? String)?.lowercase() ?: ""
            
            // Определяем количество дней
            val days = when {
                "месяц" in queryFromParams || "month" in queryFromParams -> 16
                "выходн" in queryFromParams || "weekend" in queryFromParams -> calculateWeekendDays()
                else -> when (val d = params["days"]) {
                    is Int -> d.coerceIn(1, 16)
                    is String -> d.toIntOrNull()?.coerceIn(1, 16) ?: 3
                    else -> 3
                }
            }

            // Город: нормализуем
            val rawCity = extractCity(params)
            val city = normalizeCityName(rawCity)
            
            println("🌤️ WeatherSkill: получаю погоду для '$city' на $days дней")

            // 1. Пытаемся wttr.in (любой город, но может вернуть < запрошенных дней)
            val wttrData = if (days <= 7) fetchWttrJson(city) else null
            
            if (wttrData != null) {
                val wttrDays = wttrData.optJSONArray("weather")?.length() ?: 0
                val rawJson = wttrData.toString()
                
                // Если wttr дал достаточно дней — используем только его
                if (wttrDays >= days) {
                    val formatted = formatWttrWeather(wttrData, city, days)
                    return SkillResult.Success(
                        message = formatted,
                        data = mapOf("city" to city, "source" to "wttr.in", "raw_json" to rawJson),
                        responseType = ResponseType.TEXT
                    )
                }
                
                // wttr дал меньше дней — пытаемся добить Open-Meteo
                val omData = fetchOpenMeteoByCity(city, days)
                if (omData != null) {
                    val formatted = formatHybridWeather(wttrData, omData, city, days)
                    val hybridRaw = """{"wttr":$rawJson,"open_meteo":${omData.toString()}}"""
                    return SkillResult.Success(
                        message = formatted,
                        data = mapOf("city" to city, "source" to "hybrid", "raw_json" to hybridRaw),
                        responseType = ResponseType.TEXT
                    )
                }
                
                // Open-Meteo недоступен — отдаём что есть от wttr
                val formatted = formatWttrWeather(wttrData, city, days)
                return SkillResult.Success(
                    message = formatted,
                    data = mapOf("city" to city, "source" to "wttr.in", "raw_json" to rawJson),
                    responseType = ResponseType.TEXT
                )
            }
            
            println("⚠️ wttr.in недоступен, fallback на Open-Meteo")

            // 2. >7 дней → гибрид wttr.in + Open-Meteo
            if (days > 7) {
                val wttrData2 = fetchWttrJson(city)
                val omData2 = fetchOpenMeteoByCity(city, 16)
                if (wttrData2 != null || omData2 != null) {
                    val formatted = formatHybridWeather(wttrData2, omData2, city, days)
                    var hybridRaw = ""
                    if (wttrData2 != null) hybridRaw += """{"wttr":${wttrData2.toString()}""" else hybridRaw += """{"wttr":null"""
                    if (omData2 != null) hybridRaw += ""","open_meteo":${omData2.toString()}}""" else hybridRaw += ""","open_meteo":null}"""
                    return SkillResult.Success(
                        message = formatted,
                        data = mapOf("city" to city, "source" to "hybrid", "raw_json" to hybridRaw),
                        responseType = ResponseType.TEXT
                    )
                }
            }

            // 3. Fallback на Open-Meteo
            val coords = getCityCoordinates(city)
            if (coords == null) {
                return SkillResult.Error(
                    message = "Не удалось определить координаты города '$city'",
                    details = "Попробуйте указать город в формате: 'погода Москва' или 'погода Санкт-Петербург'"
                )
            }
            val weatherData = fetchOpenMeteo(coords.first, coords.second, days)
            if (weatherData != null) {
                val formatted = formatOpenMeteoWeather(weatherData, city, days)
                SkillResult.Success(
                    message = formatted,
                    data = mapOf(
                        "city" to city,
                        "weather" to weatherData.toString(),
                        "source" to "open-meteo",
                        "raw_json" to weatherData.toString()
                    ),
                    responseType = ResponseType.TEXT
                )
            } else {
                SkillResult.Error(
                    message = "Не удалось получить данные о погоде для '$city'",
                    details = "Попробуйте позже"
                )
            }
        } catch (e: Exception) {
            println("❌ WeatherSkill ошибка: ${e.message}")
            SkillResult.Error(
                message = "Ошибка при получении погоды",
                details = e.message ?: "Неизвестная ошибка"
            )
        }
    }

    // ──────────────────────────────────────────────
    //  wttr.in
    // ──────────────────────────────────────────────

    /**
     * Запрашивает JSON с wttr.in (до 7 дней).
     */
    private fun fetchWttrJson(city: String): JSONObject? {
        return try {
            val encoded = URLEncoder.encode(city, "UTF-8")
            val url = URL("https://wttr.in/$encoded?format=j1")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            
            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                val json = buildString {
                    var line: String? = reader.readLine()
                    while (line != null) { append(line); line = reader.readLine() }
                }
                reader.close()
                connection.disconnect()
                JSONObject(json)
            } else {
                println("⚠️ wttr.in вернул код: ${connection.responseCode}")
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            println("⚠️ wttr.in ошибка: ${e.message}")
            null
        }
    }

    /**
     * Форматирует данные wttr.in в читаемый текст.
     */
    private fun formatWttrWeather(data: JSONObject, city: String, days: Int): String {
        val sb = StringBuilder()
        val displayCity = data.optJSONArray("nearest_area")
            ?.optJSONObject(0)
            ?.optJSONArray("areaName")
            ?.optJSONObject(0)
            ?.optString("value", city) ?: city
        sb.append("🌤️ **Погода в ${displayCity}:**\n\n")

        // Текущая погода
        try {
            val current = data.getJSONArray("current_condition").getJSONObject(0)
            val temp = current.getString("temp_C")
            val feels = current.getString("FeelsLikeC")
            val humidity = current.getString("humidity")
            val desc = current.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
            val wind = current.optString("windspeedKmph", "0")
            val windDirDeg = current.optString("winddirDegree", "0").toIntOrNull() ?: 0
            
            sb.append("**Сейчас:** $temp°C (ощущается как $feels°C)\n")
            sb.append("$desc\n")
            sb.append("💧 Влажность: $humidity% | 💨 Ветер: ${wind} км/ч (${wttrWindDirection(windDirDeg)})\n")
            sb.append("\n")
        } catch (e: Exception) {
            println("⚠️ wttr.in парсинг current: ${e.message}")
        }

        // Прогноз по дням
        try {
            val weather = data.getJSONArray("weather")
            val count = minOf(weather.length(), days)
            
            val daysLabel = russianDaysLabel(count)
            sb.append("**Прогноз на $count $daysLabel:**\n\n")
            
            for (i in 0 until count) {
                val day = weather.getJSONObject(i)
                val date = day.getString("date")
                val max = day.getString("maxtempC")
                val min = day.getString("mintempC")
                val noon = day.getJSONArray("hourly").optJSONObject(5)
                val desc = noon?.optJSONArray("weatherDesc")
                    ?.optJSONObject(0)
                    ?.optString("value", "—") ?: "—"
                
                val dayName = formatDate(date)
                sb.append("📅 *$dayName*\n")
                sb.append("🌡️ ${min}°C…${max}°C | $desc\n")
                
                val rain = noon?.optString("precipMM", "0")?.toDoubleOrNull() ?: 0.0
                val wind = noon?.optString("windspeedKmph", "0") ?: "0"
                if (rain > 0) sb.append("🌧️ $rain мм | ")
                sb.append("💨 Ветер $wind км/ч\n\n")
            }
        } catch (e: Exception) {
            println("⚠️ wttr.in парсинг forecast: ${e.message}")
        }

        sb.append("📊 *Источник: wttr.in (Open-Meteo + WWO)*")
        return sb.toString()
    }

    // ──────────────────────────────────────────────
    //  Гибрид: wttr.in + Open-Meteo
    // ──────────────────────────────────────────────

    /**
     * Комбинирует данные из двух источников в один отчёт.
     */
    private fun formatHybridWeather(
        wttrData: JSONObject?,
        omData: JSONObject?,
        city: String,
        days: Int
    ): String {
        val sb = StringBuilder()
        sb.append("🌤️ **Прогноз на $days дней — ${city.replaceFirstChar { it.uppercase() }}:**\n\n")

        val hasWttr = wttrData != null
        val hasOm = omData != null

        // Текущая погода (wttr приоритет)
        if (hasWttr) {
            try {
                val current = wttrData!!.getJSONArray("current_condition").getJSONObject(0)
                val temp = current.getString("temp_C")
                val feels = current.getString("FeelsLikeC")
                val desc = current.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
                sb.append("**Сейчас:** $temp°C (ощущается как $feels°C) — $desc\n\n")
            } catch (_: Exception) {}
        } else if (hasOm) {
            try {
                val current = omData!!.getJSONObject("current")
                val temp = current.getDouble("temperature_2m")
                val feels = current.getDouble("apparent_temperature")
                val code = current.getInt("weather_code")
                sb.append("**Сейчас:** ${temp}°C (ощущается как ${feels}°C) — ${weatherDescription(code)}\n\n")
            } catch (_: Exception) {}
        }

        // Дни 1-N из wttr.in (по факту, сколько вернул API)
        var wttrDayCount = 0
        if (hasWttr) {
            try {
                val weather = wttrData!!.getJSONArray("weather")
                wttrDayCount = minOf(weather.length(), 7, days)
                if (wttrDayCount > 0) {
                    val label = russianDaysLabel(wttrDayCount)
                    sb.append("**📊 Ближайшие $wttrDayCount $label (wttr.in):**\n\n")
                    for (i in 0 until wttrDayCount) {
                        val day = weather.getJSONObject(i)
                        val date = day.getString("date")
                        val max = day.getString("maxtempC")
                        val min = day.getString("mintempC")
                        val noon = day.getJSONArray("hourly").optJSONObject(5)
                        val desc = noon?.optJSONArray("weatherDesc")
                            ?.optJSONObject(0)?.optString("value", "—") ?: "—"
                        val rain = noon?.optString("precipMM", "0")?.toDoubleOrNull() ?: 0.0
                        val wind = noon?.optString("windspeedKmph", "0") ?: "0"

                        val dayName = formatDate(date)
                        sb.append("📅 *$dayName*: ${min}°C…${max}°C | $desc")
                        if (rain > 0) sb.append(" | 🌧️ $rain мм")
                        sb.append(" | 💨 $wind км/ч\n")
                    }
                    sb.append("\n")
                }
            } catch (_: Exception) {}
        }

        // Оставшиеся дни из Open-Meteo (начиная с индекса wttrDayCount)
        if (hasOm && wttrDayCount < days) {
            try {
                val daily = omData!!.getJSONObject("daily")
                val dates = daily.getJSONArray("time")
                val tempsMax = daily.getJSONArray("temperature_2m_max")
                val tempsMin = daily.getJSONArray("temperature_2m_min")
                val codes = daily.getJSONArray("weather_code")

                val start = wttrDayCount
                val end = minOf(dates.length(), days)
                val omCount = end - start
                
                if (omCount > 0) {
                    val label = russianDaysLabel(omCount)
                    sb.append("**🔮 Ещё $omCount $label (Open-Meteo):**\n\n")
                    for (i in start until end) {
                        val date = dates.getString(i)
                        val max = tempsMax.getDouble(i)
                        val min = tempsMin.getDouble(i)
                        val code = codes.getInt(i)
                        val dayName = formatDate(date)
                        sb.append("📅 *$dayName*: ${min}°C…${max}°C | ${weatherDescription(code)}\n")
                    }
                    sb.append("\n")
                }
            } catch (_: Exception) {}
        }

        if (!hasWttr && !hasOm) {
            sb.append("❌ Не удалось получить данные о погоде.")
        }

        sb.append("📊 *Источники: wttr.in + Open-Meteo*")
        return sb.toString()
    }

    // ──────────────────────────────────────────────
    //  Open-Meteo (оригинальные методы)
    // ──────────────────────────────────────────────

    /**
     * Запрашивает Open-Meteo по имени города (через карту координат).
     */
    private fun fetchOpenMeteoByCity(city: String, days: Int): JSONObject? {
        val coords = getCityCoordinates(city) ?: return null
        return fetchOpenMeteo(coords.first, coords.second, days)
    }

    /**
     * Запрашивает данные о погоде через Open-Meteo API.
     */
    private fun fetchOpenMeteo(lat: Double, lon: Double, days: Int): JSONObject? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?" +
                "latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_direction_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,wind_speed_10m_max" +
                "&timezone=auto" +
                "&forecast_days=$days"
            )
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "PaiAndroid/1.0")

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                val json = buildString {
                    var line: String? = reader.readLine()
                    while (line != null) {
                        append(line)
                        line = reader.readLine()
                    }
                }
                reader.close()
                connection.disconnect()
                JSONObject(json)
            } else {
                println("⚠️ Open-Meteo вернул код: $responseCode")
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            println("❌ Open-Meteo ошибка: ${e.message}")
            null
        }
    }

    /**
     * Форматирует данные Open-Meteo (оригинальный формат).
     */
    private fun formatOpenMeteoWeather(data: JSONObject, city: String, days: Int): String {
        val sb = StringBuilder()
        sb.append("🌤️ **Погода в ${city.replaceFirstChar { it.uppercase() }}:**\n\n")

        // Текущая погода
        try {
            val current = data.getJSONObject("current")
            val currentUnits = data.getJSONObject("current_units")
            
            val temp = current.getDouble("temperature_2m")
            val feelsLike = current.getDouble("apparent_temperature")
            val humidity = current.getInt("relative_humidity_2m")
            val windSpeed = current.getDouble("wind_speed_10m")
            val windDir = current.getInt("wind_direction_10m")
            val precip = current.getDouble("precipitation")
            val weatherCode = current.getInt("weather_code")
            
            val tempUnit = currentUnits.getString("temperature_2m")
            val speedUnit = currentUnits.getString("wind_speed_10m")
            
            sb.append("**Сейчас:** ${temp}$tempUnit (ощущается как ${feelsLike}$tempUnit)\n")
            sb.append("${weatherDescription(weatherCode)}\n")
            sb.append("💧 Влажность: $humidity% | 💨 Ветер: ${windSpeed}$speedUnit (${windDirection(windDir)})\n")
            if (precip > 0) sb.append("🌧️ Осадки: ${precip} мм\n")
            sb.append("\n")
        } catch (e: Exception) {
            println("⚠️ Ошибка парсинга current: ${e.message}")
        }

        // Прогноз по дням
        try {
            val daily = data.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val tempsMax = daily.getJSONArray("temperature_2m_max")
            val tempsMin = daily.getJSONArray("temperature_2m_min")
            val precips = daily.getJSONArray("precipitation_sum")
            val weatherCodes = daily.getJSONArray("weather_code")
            val windSpeeds = daily.getJSONArray("wind_speed_10m_max")

            val daysLabel = russianDaysLabel(days)
            sb.append("**Прогноз на ${days} $daysLabel:**\n\n")
            for (i in 0 until dates.length()) {
                val date = dates.getString(i)
                val max = tempsMax.getDouble(i)
                val min = tempsMin.getDouble(i)
                val rain = precips.getDouble(i)
                val code = weatherCodes.getInt(i)
                val wind = windSpeeds.getDouble(i)

                val dayName = formatDate(date)
                sb.append("📅 *$dayName*\n")
                sb.append("🌡️ ${min}°C…${max}°C | ${weatherDescription(code)}\n")
                sb.append("💨 Ветер до ${wind} м/с")
                if (rain > 0) sb.append(" | 🌧️ $rain мм")
                sb.append("\n\n")
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка парсинга daily: ${e.message}")
        }

        sb.append("📊 *Данные: Open-Meteo.com*")
        return sb.toString()
    }

    // ──────────────────────────────────────────────
    //  Помощники
    // ──────────────────────────────────────────────

    /**
     * Нормализует название города: ищет в карте известных городов (через частичное совпадение),
     * иначе пробует убрать русские падежные окончания.
     */
    private fun normalizeCityName(raw: String): String {
        val lower = raw.lowercase().trim()
        // Ищем в карте городов через уже существующую getCityCoordinates
        // (используем её внутреннюю логику частичного совпадения)
        
        // Сначала пробуем получить каноническое имя из карты координат
        val cityKeys = listOf(
            "санкт-петербург", "спб", "петербург", "петергоф",
            "москва", "мск",
            "казань", "екатеринбург", "новосибирск",
            "нижний новгород", "самара", "омск",
            "ростов-на-дону", "ростов", "уфа",
            "красноярск", "воронеж", "пермь",
            "волгоград", "калининград", "владивосток",
            "сочи", "минск", "киев",
            "алматы", "нур-султан", "астана",
            "ташкент", "баку", "тбилиси",
            "erevan", "ереван",
            "london", "лондон",
            "paris", "париж",
            "берлин", "berlin",
            "tokyo", "токио",
            "new york", "нью-йорк", "нью йорк", "nyc",
            "dubai", "дубай"
        )
        // Точное совпадение
        for (key in cityKeys) {
            if (lower == key) return key.replaceFirstChar { it.uppercase() }
        }
        // Частичное совпадение
        for (key in cityKeys) {
            if (lower.contains(key) || key.contains(lower))
                return key.replaceFirstChar { it.uppercase() }
        }
        // Пробуем падежные окончания
        return when {
            lower.endsWith("е") && lower.length > 4 -> raw.dropLast(1)
            lower.endsWith("ом") && lower.length > 4 -> raw.dropLast(2)
            lower.endsWith("ой") && lower.length > 4 -> raw.dropLast(2) + "ий"
            else -> raw
        }
    }

    /**
     * Определяет координаты города.
     */
    private fun getCityCoordinates(city: String): Pair<Double, Double>? {
        val lower = city.lowercase().trim()
        
        val cities = mapOf(
            "санкт-петербург" to Pair(59.9343, 30.3351),
            "спб" to Pair(59.9343, 30.3351),
            "петербург" to Pair(59.9343, 30.3351),
            "петергоф" to Pair(59.8805, 29.9087),
            "москва" to Pair(55.7558, 37.6173),
            "мск" to Pair(55.7558, 37.6173),
            "казань" to Pair(55.7961, 49.1064),
            "екатеринбург" to Pair(56.8389, 60.6057),
            "новосибирск" to Pair(55.0302, 82.9204),
            "нижний новгород" to Pair(56.2965, 43.9361),
            "самара" to Pair(53.1958, 50.1002),
            "омск" to Pair(54.9924, 73.3686),
            "ростов-на-дону" to Pair(47.2357, 39.7015),
            "ростов" to Pair(47.2357, 39.7015),
            "уфа" to Pair(54.7431, 55.9678),
            "красноярск" to Pair(56.0086, 92.9245),
            "воронеж" to Pair(51.6720, 39.1843),
            "пермь" to Pair(58.0105, 56.2502),
            "волгоград" to Pair(48.7080, 44.5133),
            "калининград" to Pair(54.7065, 20.5109),
            "владивосток" to Pair(43.1332, 131.9113),
            "сочи" to Pair(43.5855, 39.7231),
            "минск" to Pair(53.9045, 27.5615),
            "киев" to Pair(50.4501, 30.5234),
            "алматы" to Pair(43.2220, 76.8512),
            "нур-султан" to Pair(51.1605, 71.4704),
            "астана" to Pair(51.1605, 71.4704),
            "ташкент" to Pair(41.2995, 69.2401),
            "баку" to Pair(40.4093, 49.8671),
            "тбилиси" to Pair(41.7151, 44.8271),
            "erevan" to Pair(40.1792, 44.4991),
            "ереван" to Pair(40.1792, 44.4991),
            "london" to Pair(51.5074, -0.1278),
            "лондон" to Pair(51.5074, -0.1278),
            "paris" to Pair(48.8566, 2.3522),
            "париж" to Pair(48.8566, 2.3522),
            "берлин" to Pair(52.5200, 13.4050),
            "berlin" to Pair(52.5200, 13.4050),
            "tokyo" to Pair(35.6762, 139.6503),
            "токио" to Pair(35.6762, 139.6503),
            "new york" to Pair(40.7128, -74.0060),
            "нью-йорк" to Pair(40.7128, -74.0060),
            "нью йорк" to Pair(40.7128, -74.0060),
            "nyc" to Pair(40.7128, -74.0060),
            "dubai" to Pair(25.2048, 55.2708),
            "дубай" to Pair(25.2048, 55.2708)
        )

        cities[lower]?.let { return it }
        for ((name, coords) in cities) {
            if (lower.contains(name) || name.contains(lower)) {
                return coords
            }
        }
        return null
    }

    /**
     * Вычисляет количество дней до конца выходных.
     */
    private fun calculateWeekendDays(): Int {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_WEEK)
        return when (today) {
            java.util.Calendar.SATURDAY -> 2
            java.util.Calendar.SUNDAY -> 1
            else -> (java.util.Calendar.SUNDAY - today + 7) % 7 + 1
        }
    }

    /**
     * Извлекает название города из params, отфильтровывая ключевые слова.
     */
    private fun extractCity(params: Map<String, Any>): String {
        val raw = (params["city"] as? String)?.lowercase()?.trim() ?: return "Санкт-Петербург"
        val keywords = listOf("выходн", "сегодня", "сейчас", "завтра", "недел", "ближайш", "погод", "месяц", "month")
        if (raw.isBlank() || keywords.any { raw.contains(it) }) {
            return "Санкт-Петербург"
        }
        return params["city"] as String
    }

    /**
     * Русское склонение для «день/дня/дней».
     */
    private fun russianDaysLabel(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "день"
        n % 10 in 2..4 && (n % 100 !in 12..14) -> "дня"
        else -> "дней"
    }

    /**
     * Описание погоды по коду WMO (Open-Meteo).
     */
    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "☀️ Ясно"
        1 -> "🌤️ Преимущественно ясно"
        2 -> "⛅ Переменная облачность"
        3 -> "☁️ Облачно"
        45, 48 -> "🌫️ Туман"
        51, 53, 55 -> "🌦️ Морось"
        56, 57 -> "🌧️ Ледяная морось"
        61, 63, 65 -> "🌧️ Дождь"
        66, 67 -> "🌧️ Ледяной дождь"
        71, 73, 75 -> "🌨️ Снегопад"
        77 -> "❄️ Снежные зёрна"
        80, 81, 82 -> "🌦️ Ливень"
        85, 86 -> "🌨️ Снегопад (ливень)"
        95 -> "⛈️ Гроза"
        96, 99 -> "⛈️ Гроза с градом"
        else -> "🌥️ $code"
    }

    /**
     * Направление ветра по градусам.
     */
    private fun windDirection(degrees: Int): String = when {
        degrees < 22 || degrees >= 338 -> "⬆️ С"
        degrees < 67 -> "↗️ СВ"
        degrees < 112 -> "➡️ В"
        degrees < 157 -> "↘️ ЮВ"
        degrees < 202 -> "⬇️ Ю"
        degrees < 247 -> "↙️ ЮЗ"
        degrees < 292 -> "⬅️ З"
        degrees < 338 -> "↖️ СЗ"
        else -> "⬆️ С"
    }

    /**
     * Направление ветра для wttr.in (градусы, север 0° → CW).
     */
    private fun wttrWindDirection(degrees: Int): String = windDirection(degrees)

    /**
     * Форматирует дату из YYYY-MM-DD в читаемый вид.
     */
    private fun formatDate(dateStr: String): String {
        return try {
            val parts = dateStr.split("-")
            if (parts.size == 3) {
                val months = listOf(
                    "", "янв", "фев", "мар", "апр", "мая", "июн",
                    "июл", "авг", "сен", "окт", "ноя", "дек"
                )
                val day = parts[2].trimStart('0')
                val month = parts[1].trimStart('0').toIntOrNull() ?: return dateStr
                if (month in 1..12) "${day} ${months[month]}" else dateStr
            } else dateStr
        } catch (e: Exception) { dateStr }
    }
}
