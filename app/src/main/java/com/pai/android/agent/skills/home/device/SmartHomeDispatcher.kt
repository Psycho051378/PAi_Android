package com.pai.android.agent.skills.home.device

import com.pai.android.data.repository.SmartHomeRepository
import org.json.JSONObject
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Диспетчер управления умным домом.
 *
 * Два режима:
 * 1. LLM-driven: принимает список DeviceCommand (сгенерированных LLM).
 * 2. Fallback: принимает текстовую команду, парсит через ControlIntentParser.
 *
 * LLM-режим предпочтительнее — он понимает естественный язык,
 * а не только заскриптованные фразы.
 */
@Singleton
class SmartHomeDispatcher @Inject constructor(
    private val repository: SmartHomeRepository,
    private val router: DeviceControllerRouter
) {

    /**
     * Выполнить команды управления от LLM.
     * Предпочтительный метод — LLM сада разобрала запрос в структурированные команды.
     *
     * @param commands список команд от LLM
     * @return человекочитаемый результат
     */
    suspend fun dispatchCommands(commands: List<DeviceCommand>): String {
        if (commands.isEmpty()) {
            return "❌ LLM не сгенерировала команд для выполнения."
        }

        val results = mutableListOf<String>()

        for (command in commands) {
            val device = repository.getDeviceByMac(command.deviceId)
            if (device == null) {
                results.add("❌ Устройство с ID '${command.deviceId}' не найдено в БД.")
                continue
            }

            val controller = router.getController(device.protocol)
            if (controller == null) {
                results.add("⚠️ ${device.displayName}: протокол ${device.protocol} не поддерживается")
                continue
            }

            val controlPort = device.port.let { if (it > 0) it else 0 }

            // Для MiIO-устройств — подкладываем токен из deviceConfig
            if (controller is MiioController) {
                val token = parseMiioToken(device.deviceConfig)
                if (token.isNullOrBlank()) {
                    results.add("❌ ${device.displayName}: не получен token. Введи токен в настройках.")
                    continue
                }
                controller.setToken(device.ip, token)
            }

            val result = executeCommand(controller, device.ip, controlPort, command)
            if (result.success) {
                results.add("✅ ${device.displayName}: ${result.message.ifBlank { actionLabel(command.action) }}")
            } else {
                results.add("❌ ${device.displayName}: ${result.error ?: "ошибка"}")
            }
        }

        return results.joinToString("\n")
    }

    /**
     * Выполнить одну команду контроллера.
     */
    private suspend fun executeCommand(
        controller: DeviceController,
        ip: String,
        port: Int,
        command: DeviceCommand
    ): ControlResult {
        return when (command.action) {
            "turn_on" -> controller.turnOn(ip, port)
            "turn_off" -> controller.turnOff(ip, port)
            "start" -> controller.turnOn(ip, port)
            "stop" -> controller.turnOff(ip, port)
            "set_brightness" -> {
                val level = extractIntParam(command.params, "level", 50)
                controller.setBrightness(ip, port, level)
            }
            "set_color_temp" -> {
                val temp = extractIntParam(command.params, "temp", 3500)
                controller.setColorTemp(ip, port, temp)
            }
            "set_rgb" -> {
                // Поддержка имени цвета или RGB
                val colorName = command.params["color"] as? String
                if (colorName != null) {
                    val rgb = ColorNameMapper.toRgb(colorName)
                    if (rgb != null) {
                        controller.setRGB(ip, port, rgb.first, rgb.second, rgb.third)
                    } else {
                        ControlResult(false, error = "Неизвестный цвет: $colorName")
                    }
                } else {
                    val r = extractIntParam(command.params, "r", 255)
                    val g = extractIntParam(command.params, "g", 255)
                    val b = extractIntParam(command.params, "b", 255)
                    controller.setRGB(ip, port, r, g, b)
                }
            }
            "set_mode" -> {
                val mode = command.params["mode"] as? String ?: "auto"
                controller.customCommand(ip, port, "set_mode", mapOf("mode" to mode))
            }
            "set_fan_speed" -> {
                val speed = command.params["speed"] as? String ?: "balanced"
                controller.customCommand(ip, port, "fan_speed", mapOf("speed" to speed))
            }
            "get_status" -> {
                val online = controller.ping(ip, port)
                if (online) {
                    controller.customCommand(ip, port, "status", emptyMap())
                } else {
                    ControlResult(false, error = "Устройство недоступно")
                }
            }
            "charge" -> {
                controller.customCommand(ip, port, "charge", emptyMap())
            }
            "set_name" -> {
                val newName = command.params["name"] as? String
                if (newName != null && newName.isNotBlank()) {
                    val device = repository.getDeviceByMac(command.deviceId)
                    if (device != null) {
                        repository.renameDevice(device.id, newName)
                        ControlResult(true, message = "Переименовано в «$newName»")
                    } else {
                        ControlResult(false, error = "Устройство не найдено")
                    }
                } else {
                    ControlResult(false, error = "Не указано новое имя")
                }
            }
            else -> ControlResult(false, error = "Неизвестное действие: ${command.action}")
        }
    }

    /**
     * Разобрать JSON-строку со списком команд от LLM в DeviceCommand[].
     * Поддерживает как массив JSON, так и одиночный объект.
     */
    fun parseCommandsFromJson(json: String): List<DeviceCommand> {
        val trimmed = json.trim()
        return try {
            if (trimmed.startsWith("[")) {
                // Массив команд
                val arr = JSONArray(trimmed)
                (0 until arr.length()).map { i ->
                    parseSingleCommand(arr.getJSONObject(i))
                }
            } else if (trimmed.startsWith("{")) {
                // Одиночная команда
                listOf(parseSingleCommand(JSONObject(trimmed)))
            } else {
                // Попытка найти JSON внутри текста
                val jsonStart = trimmed.indexOf('[')
                val jsonEnd = trimmed.lastIndexOf(']')
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    parseCommandsFromJson(trimmed.substring(jsonStart, jsonEnd + 1))
                } else {
                    val objStart = trimmed.indexOf('{')
                    val objEnd = trimmed.lastIndexOf('}')
                    if (objStart >= 0 && objEnd > objStart) {
                        parseCommandsFromJson(trimmed.substring(objStart, objEnd + 1))
                    } else {
                        println("SmartHomeDispatcher: не удалось найти JSON в ответе LLM: $trimmed")
                        emptyList()
                    }
                }
            }
        } catch (e: Exception) {
            println("SmartHomeDispatcher: parseCommandsFromJson error: ${e.message}")
            println("SmartHomeDispatcher: raw json: $json")
            emptyList()
        }
    }

    // ========== Fallback: старый dispatch через ControlIntentParser ==========

    /**
     * Выполнить команду управления по тексту (fallback-режим).
     *
     * @param query текст запроса пользователя
     * @param networkId ID сети
     * @return человекочитаемый результат
     */
    suspend fun dispatch(query: String, networkId: String): String {
        val intent = ControlIntentParser.parse(query)
        if (intent == null) {
            return "❌ Не могу понять, что нужно сделать. Попробуй:\n" +
                    "• «включи свет»\n" +
                    "• «выключи лампу 2»\n" +
                    "• «поставь яркость 50%»\n" +
                    "• «включи пылесос»\n" +
                    "• «turn on vacuum»\n" +
                    "• «set brightness to 75%»"
        }

        val devices = repository.resolveIntent(networkId, intent.targetType, intent.targetName)

        if (devices.isEmpty()) {
            val typeMsg = intent.targetType?.let { " типа $it" } ?: ""
            val nameMsg = intent.targetName?.let { " с именем '$it'" } ?: ""
            return "❌ Не найдено устройств$typeMsg$nameMsg в текущей сети."
        }

        val manageable = devices.filter { it.protocol != "UNKNOWN" }
        if (manageable.isEmpty()) {
            return "⚠️ Найденные устройства не поддерживают управление (нет протокола)."
        }

        val results = mutableListOf<String>()
        for (device in manageable) {
            val controller = router.getController(device.protocol)
            if (controller == null) {
                results.add("⚠️ ${device.displayName}: протокол ${device.protocol} не поддерживается")
                continue
            }

            val controlPort = device.port.let { if (it > 0) it else 0 }

            if (controller is MiioController) {
                val token = parseMiioToken(device.deviceConfig)
                if (token.isNullOrBlank()) {
                    results.add("❌ ${device.displayName}: не получен token. Введи токен в настройках.")
                    continue
                }
                controller.setToken(device.ip, token)
            }

            val result = when (intent.action) {
                "turn_on" -> controller.turnOn(device.ip, controlPort)
                "turn_off" -> controller.turnOff(device.ip, controlPort)
                "start" -> controller.turnOn(device.ip, controlPort)
                "stop" -> controller.turnOff(device.ip, controlPort)
                "set_brightness" -> {
                    val level = (intent.params["level"] as? Int) ?: 50
                    controller.setBrightness(device.ip, controlPort, level)
                }
                "set_color_temp" -> {
                    val temp = (intent.params["temp"] as? Int) ?: 50
                    controller.setColorTemp(device.ip, controlPort, temp)
                }
                "set_rgb" -> {
                    val r = (intent.params["r"] as? Int) ?: 255
                    val g = (intent.params["g"] as? Int) ?: 255
                    val b = (intent.params["b"] as? Int) ?: 255
                    controller.setRGB(device.ip, controlPort, r, g, b)
                }
                "set_mode" -> {
                    val mode = intent.params["mode"] as? String ?: "auto"
                    controller.customCommand(device.ip, controlPort, "set_mode", mapOf("mode" to mode))
                }
                "set_fan_speed" -> {
                    val speed = intent.params["speed"] as? String ?: "medium"
                    controller.customCommand(device.ip, controlPort, "fan_speed", mapOf("speed" to speed))
                }
                "charge" -> {
                    controller.customCommand(device.ip, controlPort, "charge", emptyMap())
                }
                "get_status" -> {
                    val online = controller.ping(device.ip, controlPort)
                    if (online) {
                        controller.customCommand(device.ip, controlPort, "status", emptyMap())
                    } else {
                        ControlResult(false, error = "Устройство недоступно")
                    }
                }
                else -> ControlResult(false, error = "Неизвестное действие: ${intent.action}")
            }

            if (result.success) {
                results.add("✅ ${device.displayName}: ${actionLabel(intent.action)}")
            } else {
                results.add("❌ ${device.displayName}: ${result.error ?: "ошибка"}")
            }
        }

        return results.joinToString("\n")
    }

    // ========== Утилиты ==========

    private fun parseSingleCommand(obj: JSONObject): DeviceCommand {
        val deviceId = obj.getString("deviceId")
        val action = obj.getString("action")
        val params = mutableMapOf<String, Any>()

        if (obj.has("params")) {
            val p = obj.getJSONObject("params")
            for (key in p.keys()) {
                val value = p.get(key)
                params[key] = when (value) {
                    is Int -> value
                    is Long -> value.toInt()
                    is Double -> value.toInt()
                    is Boolean -> value
                    is String -> value
                    else -> value.toString()
                }
            }
        }

        return DeviceCommand(deviceId = deviceId, action = action, params = params)
    }

    private fun extractIntParam(params: Map<String, Any>, key: String, default: Int): Int {
        val raw = params[key] ?: return default
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun parseMiioToken(deviceConfig: String): String? {
        return try {
            val json = JSONObject(deviceConfig)
            json.optString("miio_token", null)
        } catch (e: Exception) {
            null
        }
    }

    private fun actionLabel(action: String): String = when (action) {
        "turn_on", "start" -> "включен(а)"
        "turn_off", "stop" -> "выключен(а)"
        "set_brightness" -> "яркость изменена"
        "set_color_temp" -> "температура изменена"
        "set_rgb" -> "цвет изменён"
        "set_mode" -> "режим изменён"
        "set_fan_speed" -> "скорость изменена"
        "get_status" -> "статус получен"
        "charge" -> "отправлен(а) на зарядку"
        "set_name" -> "переименовано"
        else -> action
    }
}
