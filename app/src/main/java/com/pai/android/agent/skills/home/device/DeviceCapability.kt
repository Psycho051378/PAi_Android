package com.pai.android.agent.skills.home.device

import com.pai.android.data.model.DeviceType
import com.pai.android.data.model.DeviceProtocol
import com.pai.android.data.model.SmartHomeDevice

/**
 * Тип capability (возможности) устройства.
 * Используется как универсальный словарь для LLM.
 */
enum class CapabilityType(val displayName: String, val description: String) {
    POWER("Вкл/Выкл", "Включение и выключение устройства"),
    BRIGHTNESS("Яркость", "Регулировка яркости от 1 до 100%"),
    COLOR_TEMP("Цветовая температура", "Изменение цветовой температуры в Кельвинах (2700K тёплый — 6500K холодный)"),
    RGB("Цвет RGB", "Установка произвольного цвета через RGB (0-255) или название цвета"),
    MODE("Режим", "Переключение режимов работы (auto, silent, turbo, manual, sleep и т.д.)"),
    FAN_SPEED("Скорость вентилятора", "Регулировка скорости вращения вентилятора"),
    STATUS("Статус", "Получение статуса устройства (убирается, заряжается, температура и т.д.)"),
    BATTERY("Уровень батареи", "Уровень заряда аккумулятора 0-100%"),
    HUMIDITY("Влажность", "Текущий уровень влажности (показание датчика)"),
    TEMPERATURE("Температура", "Текущая температура (показание датчика)"),
    CHARGE("Зарядка", "Отправить устройство на зарядную базу"),
    NAME("Переименование", "Изменение отображаемого имени устройства")
}

/**
 * Описание одной возможности устройства с диапазонами параметров.
 */
data class Capability(
    val type: CapabilityType,
    val description: String,
    /** Параметры: для BRIGHTNESS — {"min":1,"max":100}, для COLOR_TEMP — {"min":2700,"max":6500} */
    val params: Map<String, Any> = emptyMap()
)

/**
 * Описание устройства для LLM.
 * LLM получает список DeviceDescriptor и сама решает, какие действия выполнить.
 */
data class DeviceDescriptor(
    val id: String,
    val name: String,
    val deviceType: String,
    val protocol: String,
    val ip: String,
    val capabilities: List<Capability>
)

/**
 * Команда для управления устройством, сгенерированная LLM.
 *
 * action — одна из: "turn_on", "turn_off", "set_brightness", "set_color_temp",
 *   "set_rgb", "set_mode", "set_fan_speed", "get_status", "set_name"
 * params — параметры команды:
 *   set_brightness → {"level": 50}
 *   set_color_temp → {"temp": 4000}
 *   set_rgb → {"r": 0, "g": 255, "b": 0} или {"color": "green"}
 *   set_mode → {"mode": "auto"}
 *   set_fan_speed → {"speed": "quiet"}
 *   set_name → {"name": "Лампа на кухне"}
 */
data class DeviceCommand(
    val deviceId: String,
    val action: String,
    val params: Map<String, Any> = emptyMap()
)

/**
 * Реестр возможностей по типам устройств.
 * Статическое описание того, что умеет устройство каждого типа.
 * LLM использует эту информацию для генерации корректных команд.
 */
object CapabilityRegistry {

    /** Получить список возможностей для типа устройства. */
    fun getCapabilities(deviceType: String, protocol: String): List<Capability> {
        val base = getBaseCapabilities(deviceType)
        val protoFeatures = getProtocolFeatures(protocol)
        return (base + protoFeatures).distinctBy { it.type }
    }

    /** Преобразовать SmartHomeDevice в DeviceDescriptor для LLM. */
    fun toDeviceDescriptor(device: SmartHomeDevice): DeviceDescriptor {
        val type = try {
            DeviceType.valueOf(device.deviceType)
        } catch (e: Exception) {
            DeviceType.OTHER
        }
        val protocol = try {
            DeviceProtocol.valueOf(device.protocol)
        } catch (e: Exception) {
            DeviceProtocol.UNKNOWN
        }
        return DeviceDescriptor(
            id = device.mac,
            name = device.displayName.ifBlank { device.hostname.ifBlank { device.ip } },
            deviceType = type.displayName,
            protocol = protocol.displayName,
            ip = device.ip,
            capabilities = getCapabilities(device.deviceType, device.protocol)
        )
    }

    /** Создать промпт для LLM со списком устройств. */
    fun buildDevicesPrompt(devices: List<SmartHomeDevice>): String {
        if (devices.isEmpty()) return "Нет доступных устройств."

        val sb = StringBuilder()
        sb.appendLine("Доступные устройства умного дома:")
        sb.appendLine()

        for ((i, device) in devices.withIndex()) {
            val desc = toDeviceDescriptor(device)
            sb.appendLine("${i + 1}. **${desc.name}** (${desc.deviceType}, ${desc.protocol})")
            sb.appendLine("   ID: ${desc.id}")
            sb.appendLine("   IP: ${desc.ip}")
            sb.appendLine("   Возможности:")
            for (cap in desc.capabilities) {
                sb.append("   • ${cap.description}")
                if (cap.params.isNotEmpty()) {
                    sb.append(" [${cap.params.entries.joinToString(", ") { (k, v) -> "$k=$v" }}]")
                }
                sb.appendLine()
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun getBaseCapabilities(deviceType: String): List<Capability> {
        val base: List<Capability> = when (DeviceType.fromString(deviceType)) {
            DeviceType.LIGHT -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить свет"),
                Capability(CapabilityType.BRIGHTNESS, "Установить яркость", mapOf("min" to 1, "max" to 100)),
                Capability(CapabilityType.COLOR_TEMP, "Установить цветовую температуру", mapOf("min" to 2700, "max" to 6500)),
                Capability(CapabilityType.RGB, "Установить цвет (по названию или RGB)"),
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.VACUUM -> listOf(
                Capability(CapabilityType.POWER, "Запустить/остановить уборку"),
                Capability(CapabilityType.MODE, "Переключить режим уборки", mapOf("modes" to listOf("auto", "silent", "turbo", "edge", "spot"))),
                Capability(CapabilityType.FAN_SPEED, "Установить мощность всасывания"),
                Capability(CapabilityType.STATUS, "Проверить статус пылесоса"),
                Capability(CapabilityType.BATTERY, "Уровень заряда"),
                Capability(CapabilityType.CHARGE, "Отправить на зарядную базу"),
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.HUMIDIFIER -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить увлажнитель"),
                Capability(CapabilityType.MODE, "Переключить режим", mapOf("modes" to listOf("auto", "low", "mid", "high", "sleep"))),
                Capability(CapabilityType.HUMIDITY, "Уровень влажности (показание датчика)"),
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.SPEAKER -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить колонку"),
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.TV -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить телевизор"),
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.ESP_DEVICE -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить"),
                Capability(CapabilityType.STATUS, "Получить статус/показания датчика"),
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.ROUTER -> listOf(
                Capability(CapabilityType.STATUS, "Статус роутера"),
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.COMPUTER -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить (WOL)"),
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.PHONE -> listOf(
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.WEARABLE -> listOf(
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
            DeviceType.OTHER -> listOf(
                Capability(CapabilityType.STATUS, "Получить информацию об устройстве"),
                Capability(CapabilityType.NAME, "Переименовать устройство")
            )
        }
        return base
    }

    private fun getProtocolFeatures(protocol: String): List<Capability> {
        return when (DeviceProtocol.fromString(protocol)) {
            DeviceProtocol.YEELIGHT -> listOf(
                Capability(CapabilityType.RGB, "Цвет Yeelight (RGB или название цвета)"),
                Capability(CapabilityType.BRIGHTNESS, "Яркость 1-100", mapOf("min" to 1, "max" to 100)),
                Capability(CapabilityType.COLOR_TEMP, "Цветовая температура 1700-6500K", mapOf("min" to 1700, "max" to 6500))
            )
            DeviceProtocol.WIZ -> listOf(
                Capability(CapabilityType.RGB, "Цвет WiZ (RGB или название цвета)"),
                Capability(CapabilityType.BRIGHTNESS, "Яркость 10-100", mapOf("min" to 10, "max" to 100)),
                Capability(CapabilityType.COLOR_TEMP, "Цветовая температура 2700-6500K", mapOf("min" to 2700, "max" to 6500))
            )
            DeviceProtocol.MIIO, DeviceProtocol.ROBOROCK -> listOf(
                Capability(CapabilityType.STATUS, "Статус уборки (заряжается, убирается, припаркован)"),
                Capability(CapabilityType.BATTERY, "Уровень заряда батареи 0-100%"),
                Capability(CapabilityType.FAN_SPEED, "Скорость вентилятора", mapOf("levels" to listOf("quiet", "balanced", "turbo", "max")))
            )
            DeviceProtocol.SHELLY -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить (реле Shelly)"),
                Capability(CapabilityType.BRIGHTNESS, "Яркость 1-100", mapOf("min" to 1, "max" to 100)),
                Capability(CapabilityType.RGB, "Цвет Shelly (RGBW2 / Bulb)"),
                Capability(CapabilityType.COLOR_TEMP, "Цветовая температура 2700-6500K", mapOf("min" to 2700, "max" to 6500)),
                Capability(CapabilityType.STATUS, "Статус устройства")
            )
            DeviceProtocol.TASMOTA -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить (реле Tasmota)"),
                Capability(CapabilityType.BRIGHTNESS, "Яркость 0-100", mapOf("min" to 0, "max" to 100)),
                Capability(CapabilityType.RGB, "Цвет Tasmota (HEX)"),
                Capability(CapabilityType.COLOR_TEMP, "Цветовая температура 2000-6500K", mapOf("min" to 2000, "max" to 6500)),
                Capability(CapabilityType.STATUS, "Статус и показания датчика")
            )
            DeviceProtocol.ESPHOME -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить"),
                Capability(CapabilityType.STATUS, "Статус и показания датчика")
            )
            DeviceProtocol.WLED -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить (WLED)"),
                Capability(CapabilityType.BRIGHTNESS, "Яркость 0-255", mapOf("min" to 0, "max" to 255)),
                Capability(CapabilityType.RGB, "Цвет WLED (RGB)"),
                Capability(CapabilityType.STATUS, "Статус WLED (эффект, яркость)")
            )
            DeviceProtocol.GENERIC_HTTP -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить (HTTP)"),
                Capability(CapabilityType.STATUS, "Статус")
            )
            DeviceProtocol.TPLINK_KASA -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить"),
                Capability(CapabilityType.BRIGHTNESS, "Яркость 0-100", mapOf("min" to 0, "max" to 100)),
                Capability(CapabilityType.RGB, "Цвет (HSV)"),
                Capability(CapabilityType.COLOR_TEMP, "Цветовая температура 2500-6500K", mapOf("min" to 2500, "max" to 6500)),
                Capability(CapabilityType.STATUS, "Статус устройства")
            )
            DeviceProtocol.YANDEX -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить через Yandex"),
                Capability(CapabilityType.BRIGHTNESS, "Яркость (если поддерживается)")
            )
            DeviceProtocol.MQTT -> listOf(
                Capability(CapabilityType.POWER, "Включить/выключить через MQTT")
            )
            DeviceProtocol.UNKNOWN -> emptyList()
        }
    }
}
