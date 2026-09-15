package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Тип устройства (расширяемый, хранится как String для гибкости).
 */
enum class DeviceType(val displayName: String) {
    LIGHT("Свет"),
    VACUUM("Пылесос"),
    HUMIDIFIER("Увлажнитель"),
    SPEAKER("Колонка"),
    TV("Телевизор"),
    ESP_DEVICE("ESP-устройство"),
    ROUTER("Роутер"),
    COMPUTER("Компьютер"),
    PHONE("Телефон"),
    WEARABLE("Носимый гаджет"),
    OTHER("Другое");

    companion object {
        fun fromString(value: String): DeviceType {
            return entries.find { it.name == value } ?: OTHER
        }
    }
}

/**
 * Протокол управления устройством.
 */
enum class DeviceProtocol(val displayName: String) {
    WIZ("WiZ"),
    YEELIGHT("Yeelight"),
    TASMOTA("Tasmota"),
    ESPHOME("ESPHome"),
    MIIO("Xiaomi MiIO"),
    ROBOROCK("Roborock"),
    SHELLY("Shelly"),
    GENERIC_HTTP("Generic HTTP"),
    WLED("WLED"),
    MQTT("MQTT"),
    TPLINK_KASA("TP-Link Kasa/Tapo"),
    YANDEX("Yandex"),
    UNKNOWN("Неизвестно");

    companion object {
        fun fromString(value: String): DeviceProtocol {
            return entries.find { it.name == value } ?: UNKNOWN
        }
    }
}

/**
 * Устройство умного дома, обнаруженное при сканировании сети.
 *
 * [mac] — уникальный идентификатор устройства (аппаратный MAC-адрес).
 * [ip] — текущий IP (может меняться от сканирования к сканированию).
 * [networkId] — FK на smart_home_network.
 * [deviceType] — тип устройства (DeviceType.name), хранится как String.
 * [protocol] — протокол управления (DeviceProtocol.name), хранится как String.
 * [capabilities] — JSON-массив поддерживаемых функций, например ["on_off","brightness"].
 * [metadata] — JSON с дополнительными данными (firmware, модель, UPnP-детали и т.д.).
 */
@Entity(
    tableName = "smart_home_devices",
    foreignKeys = [
        ForeignKey(
            entity = SmartHomeNetwork::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mac"], unique = true),
        Index(value = ["networkId"]),
        Index(value = ["deviceType"]),
        Index(value = ["protocol"]),
        Index(value = ["present"])
    ]
)
data class SmartHomeDevice(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val networkId: String,
    val mac: String,                        // уникальный идентификатор
    val ip: String,                         // текущий IP
    val hostname: String = "",              // оригинальный hostname из сканера
    val displayName: String = "",           // понятное имя (авто-генерация или пользовательское)
    val deviceType: String = "OTHER",       // DeviceType.name
    val protocol: String = "UNKNOWN",       // DeviceProtocol.name
    val port: Int = 0,                      // порт управления (0 = неизвестно)
    val capabilities: String = "[]",        // JSON-массив строк
    val present: Boolean = true,            // true = устройство в сети сейчас
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val vendor: String = "",                // MAC OUI vendor
    val metadata: String = "{}",             // JSON-объект с расширенными данными
    val deviceConfig: String = "{}"          // JSON с конфигурацией управления (token и т.д.)
)
