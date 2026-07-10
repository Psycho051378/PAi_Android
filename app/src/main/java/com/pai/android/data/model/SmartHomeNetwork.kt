package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Запись о Wi-Fi сети, в которой проводилось сканирование.
 * Позволяет различать устройства по домашней/гостевой сети и т.д.
 */
@Entity(tableName = "smart_home_network")
data class SmartHomeNetwork(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val ssid: String,
    val gatewayIp: String? = null,
    val subnet: String? = null,
    val lastScan: Long = System.currentTimeMillis()
) {
    companion object {
        /** Максимальное количество сохранённых сетей. */
        const val MAX_NETWORKS = 10
    }
}
