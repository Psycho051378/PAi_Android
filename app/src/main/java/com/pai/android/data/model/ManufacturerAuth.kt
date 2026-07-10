package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Учётные данные для авторизации у производителей устройств.
 * Используется для получения device token'ов (Xiaomi MiIO) и других
 * облачных API (Yandex, и т.д.) для локального управления устройствами.
 *
 * credentials — JSON вида {"login":"...","password":"***"} (может быть зашифрован).
 * В будущем может хранить OAuth-токены.
 */
@Entity(tableName = "manufacturer_auth")
data class ManufacturerAuth(
    @PrimaryKey val manufacturer: String,  // "xiaomi", "yandex" и т.д.
    val displayName: String = "",          // Человекочитаемое имя для UI
    val authType: String = "login_password", // "login_password", "oauth", "token"
    val credentials: String = "{}",        // JSON с учётными данными
    val enabled: Boolean = true,
    val lastSynced: Long = 0               // когда в последний раз получали токены
)
