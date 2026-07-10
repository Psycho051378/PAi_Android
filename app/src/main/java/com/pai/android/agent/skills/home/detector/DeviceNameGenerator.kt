package com.pai.android.agent.skills.home.detector

import com.pai.android.data.model.DeviceType
import com.pai.android.data.model.SmartHomeDevice
import org.json.JSONObject

/**
 * Генератор понятных имён для устройств.
 *
 * Режимы:
 * 1. LLM-генерация — если есть метаданные (hostname, HTTP title, mDNS friendly name, vendor).
 *    Формирует осмысленное имя: "Лампа на кухне", "Пылесос Roborock S7", "Выключатель в коридоре"
 * 2. Fallback — старый хардкод: "Lamp 1", "Lamp 2", "Vacuum 1" (если LLM недоступна
 *    или недостаточно метаданных)
 */
object DeviceNameGenerator {

    /**
     * Префиксы для каждого типа устройства (fallback).
     */
    private val typePrefixes: Map<String, String> = mapOf(
        DeviceType.LIGHT.name to "Lamp",
        DeviceType.VACUUM.name to "Vacuum",
        DeviceType.HUMIDIFIER.name to "Humidifier",
        DeviceType.SPEAKER.name to "Speaker",
        DeviceType.TV.name to "TV",
        DeviceType.ESP_DEVICE.name to "ESP",
        DeviceType.ROUTER.name to "Router",
        DeviceType.COMPUTER.name to "PC",
        DeviceType.PHONE.name to "Phone",
        DeviceType.WEARABLE.name to "Wearable",
        DeviceType.OTHER.name to "Device"
    )

    /**
     * Сгенерировать имя для нового устройства.
     * Использует LLM при наличии метаданных, иначе — fallback.
     *
     * @param deviceType тип устройства (DeviceType.name)
     * @param existingDevices все уже известные устройства (для подсчёта индекса)
     * @param metadata JSON-строка с метаданными (hostname, HTTP title, mDNS, vendor...)
     * @param llmCall suspend-функция для вызова LLM: prompt → response. Если null — сразу fallback
     * @return сгенерированное имя
     */
    suspend fun generateName(
        deviceType: String,
        existingDevices: List<SmartHomeDevice> = emptyList(),
        metadata: String = "{}",
        llmCall: (suspend (String) -> String?)? = null
    ): String {
        // Пробуем LLM-генерацию, если есть метаданные и llmCall
        if (llmCall != null) {
            val meta = try {
                if (metadata.isNotBlank()) JSONObject(metadata) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
            val hasMeta = listOf(
                meta.optString("httpTitle", ""),
                meta.optString("httpFingerprint", ""),
                meta.optString("udpFingerprint", ""),
                meta.optString("mdnsFriendlyName", ""),
                meta.optString("mdnsHostname", ""),
                meta.optString("httpServer", "")
            ).any { it.isNotBlank() }

            if (hasMeta) {
                val llmName = generateNameViaLLM(deviceType, meta, llmCall)
                if (llmName != null && llmName.isNotBlank()) {
                    // Проверяем, что имя уникально
                    val isUnique = existingDevices.none {
                        it.displayName.equals(llmName, ignoreCase = true)
                    }
                    if (isUnique) return llmName
                    // Если не уникально — добавляем номер
                    val suffix = existingDevices.count {
                        it.displayName.startsWith(llmName, ignoreCase = true)
                    } + 1
                    return "$llmName $suffix"
                }
            }
        }

        // Fallback: "Lamp 3", "Vacuum 1"
        return generateFallbackName(deviceType, existingDevices)
    }

    /**
     * Сгенерировать имя через LLM по метаданным.
     */
    private suspend fun generateNameViaLLM(
        deviceType: String,
        meta: JSONObject,
        llmCall: suspend (String) -> String?
    ): String? {
        val typeLabel = DeviceType.fromString(deviceType).displayName

        val prompt = buildString {
            appendLine("Ты — генератор имён для устройств умного дома.")
            appendLine("По метаданным устройства придумай короткое понятное имя (2-5 слов, на русском).")
            appendLine()
            appendLine("Тип устройства: $typeLabel")
            appendLine()
            appendLine("Метаданные:")
            for (key in meta.keys()) {
                val value = meta.optString(key, "")
                if (value.isNotBlank()) {
                    appendLine("  $key: $value")
                }
            }
            appendLine()
            appendLine("Правила:")
            appendLine("- Коротко: 'Лампа в зале', 'Пылесос Roborock', 'Выключатель', 'Увлажнитель'")
            appendLine("- Не используй смайлики, скобки, кавычки")
            appendLine("- Если метаданные указывают на конкретную модель — используй её")
            appendLine("- Ответь ТОЛЬКО именем, без пояснений")
        }

        return try {
            val response = llmCall(prompt)
            if (response != null) {
                // Очищаем ответ от лишнего
                response.trim()
                    .removePrefix("\"")
                    .removeSuffix("\"")
                    .removePrefix("'")
                    .removeSuffix("'")
                    .take(50)
                    .ifBlank { null }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fallback-генерация: "Lamp 3", "Vacuum 1".
     */
    private fun generateFallbackName(
        deviceType: String,
        existingDevices: List<SmartHomeDevice>
    ): String {
        val prefix = typePrefixes[deviceType] ?: "Device"
        val count = existingDevices.count {
            it.deviceType == deviceType && it.displayName.startsWith(prefix)
        }
        return "$prefix ${count + 1}"
    }

    /**
     * Сгенерировать имена для списка устройств без displayName.
     */
    suspend fun generateMissingNames(
        devices: List<SmartHomeDevice>,
        llmCall: (suspend (String) -> String?)? = null
    ): List<SmartHomeDevice> {
        val nameCounts = mutableMapOf<String, Int>()
        val result = mutableListOf<SmartHomeDevice>()

        // Сначала считаем уже именованные
        for (device in devices) {
            if (device.displayName.isNotBlank()) {
                val prefix = typePrefixes[device.deviceType] ?: "Device"
                if (device.displayName.startsWith(prefix)) {
                    val num = device.displayName.removePrefix(prefix).trim().toIntOrNull()
                    if (num != null && num > (nameCounts[device.deviceType] ?: 0)) {
                        nameCounts[device.deviceType] = num
                    }
                }
            }
        }

        // Заполняем пропуски
        for (device in devices) {
            if (device.displayName.isNotBlank()) {
                result.add(device)
            } else {
                val name = generateName(
                    deviceType = device.deviceType,
                    existingDevices = devices.filter { it.mac != device.mac },
                    metadata = device.metadata,
                    llmCall = llmCall
                )
                if (name.isBlank() || isAutoGenerated(name)) {
                    // Если LLM не дала имя или дала сгенерированное — используем нумерацию
                    val currentCount = nameCounts[device.deviceType] ?: 0
                    val newCount = currentCount + 1
                    nameCounts[device.deviceType] = newCount
                    val prefix = typePrefixes[device.deviceType] ?: "Device"
                    result.add(device.copy(displayName = "$prefix $newCount"))
                } else {
                    result.add(device.copy(displayName = name))
                }
            }
        }

        return result
    }

    /**
     * Проверить, является ли имя автогенерированным (содержит базовый префикс + номер).
     */
    fun isAutoGenerated(displayName: String): Boolean {
        return typePrefixes.values.any { prefix ->
            displayName.startsWith(prefix) && displayName.drop(prefix.length).trim().toIntOrNull() != null
        }
    }
}
