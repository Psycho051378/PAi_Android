package com.pai.android.agent.skills.home.identity

import kotlin.math.abs

/**
 * Снимок состояния устройства, полученный от самого устройства.
 *
 * Нужен для двух вещей:
 * 1. Идемпотентность — не отправлять команду, если устройство уже в нужном состоянии.
 * 2. Верификация — после команды перечитать состояние и честно сказать
 *    «подтверждено» или «отправлено, но не подтвердилось».
 */
data class DeviceState(
    val power: Boolean? = null,
    val brightness: Int? = null,
    /** Цветовая температура в Кельвинах. null, если протокол отдаёт свою шкалу. */
    val colorTempKelvin: Int? = null,
    val rgb: Triple<Int, Int, Int>? = null,
    val battery: Int? = null,
    val extra: Map<String, String> = emptyMap()
) {
    /**
     * Совпадает ли текущее состояние с целевым действием.
     *
     * @return true — уже в целевом состоянии, false — нет, null — проверить нельзя
     *         (нет нужного поля или действие не про состояние).
     */
    fun matches(action: String, params: Map<String, Any> = emptyMap()): Boolean? {
        return when (action) {
            "turn_on", "start" -> power?.let { it }
            "turn_off", "stop" -> power?.let { !it }
            "set_brightness" -> {
                val level = intParam(params, "level")
                val current = brightness
                if (level == null || current == null) null else abs(current - level) <= 3
            }
            "set_rgb" -> {
                val r = intParam(params, "r")
                val g = intParam(params, "g")
                val b = intParam(params, "b")
                val current = rgb
                if (r == null || g == null || b == null || current == null) {
                    null
                } else {
                    abs(current.first - r) <= 12 && abs(current.second - g) <= 12 && abs(current.third - b) <= 12
                }
            }
            "set_color_temp" -> {
                val temp = intParam(params, "temp")
                val current = colorTempKelvin
                if (temp == null || current == null) null else abs(current - temp) <= 250
            }
            else -> null
        }
    }

    private fun intParam(params: Map<String, Any>, key: String): Int? =
        (params[key] as? Number)?.toInt()

    /** Короткое человекочитаемое описание для логов и ответа пользователю. */
    fun describe(): String {
        val parts = mutableListOf<String>()
        power?.let { parts.add(if (it) "вкл" else "выкл") }
        brightness?.let { parts.add("ярк.$it%") }
        colorTempKelvin?.let { parts.add("${it}K") }
        rgb?.let { parts.add("rgb(${it.first},${it.second},${it.third})") }
        battery?.let { parts.add("батарея ${it}%") }
        return if (parts.isEmpty()) "нет данных" else parts.joinToString(", ")
    }
}

/**
 * Устройство, которое представилось само.
 *
 * Это центральная структура слоя идентичности: вместо «IP + догадка по портам»
 * мы получаем от устройства его собственный MAC, модель, имя и состояние.
 */
data class IdentityHit(
    val ip: String,
    val protocol: String,
    val port: Int,
    /** MAC в формате AA:BB:CC:DD:EE:FF, если устройство его сообщило. */
    val mac: String? = null,
    /** Внутренний идентификатор протокола (Yeelight id, Kasa device_id и т.п.). */
    val nativeId: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val name: String? = null,
    val capabilities: List<String> = emptyList(),
    val state: DeviceState? = null,
    /** 3 — ответ протокола, 2 — HTTP-фингерпринт, 1 — только порт. */
    val confidence: Int = 3,
    val source: String = "probe"
) {
    /**
     * Ключ устройства для БД: MAC, если известен, иначе стабильный id протокола.
     * null означает «стабильной идентичности нет» — такое устройство в БД не пишем.
     */
    val identityKey: String?
        get() = mac ?: nativeId?.let { "${protocol.lowercase()}:${it}" }

    /**
     * Ключ с fallback на IP. Используется только для отображения,
     * в БД попадает лишь [identityKey].
     */
    val stableId: String
        get() = identityKey ?: "ip:$ip"

    val hasStableIdentity: Boolean
        get() = identityKey != null

    fun describe(): String = buildString {
        append(protocol)
        model?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
        name?.takeIf { it.isNotBlank() }?.let { append(" «").append(it).append("»") }
    }
}

/**
 * Один протокол = один пробник.
 *
 * Контракт: пробник НЕ бросает исключений наружу и не должен занимать больше
 * отведённого таймаута. Возвращает null, если хост не его.
 */
interface IdentityProbe {
    val protocol: String

    /** Умеет ли пробник работать с хостом, у которого открыты такие порты. */
    fun accepts(openPorts: List<Int>): Boolean
}

/** Нормализация и проверка MAC-адресов, присланных устройствами. */
object MacUtil {

    private val HEX12 = Regex("^[0-9a-fA-F]{12}$")
    private val COLON = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
    private val DASH = Regex("^([0-9a-fA-F]{2}-){5}[0-9a-fA-F]{2}$")

    /** Приводит любой формат к AA:BB:CC:DD:EE:FF или возвращает null. */
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = when {
            HEX12.matches(value) -> value.chunked(2).joinToString(":")
            COLON.matches(value) -> value
            DASH.matches(value) -> value.replace('-', ':')
            else -> return null
        }.uppercase()
        return if (isUsable(candidate)) candidate else null
    }

    /**
     * Отсекает мусор: нулевой MAC, broadcast и multicast-адреса
     * (в UUID-подобных полях они встречаются как «случайные 6 байт»).
     */
    fun isUsable(mac: String): Boolean {
        if (mac == "00:00:00:00:00:00" || mac == "FF:FF:FF:FF:FF:FF") return false
        val firstByte = mac.take(2).toIntOrNull(16) ?: return false
        // LSB первого октета = 1 → multicast/group-адрес, это не MAC устройства
        return (firstByte and 0x01) == 0
    }
}
