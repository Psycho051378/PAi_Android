package com.pai.android.data.repository

import com.pai.android.data.local.SmartHomeDao
import com.pai.android.data.model.SmartHomeDevice
import com.pai.android.data.model.SmartHomeNetwork
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единый источник данных для умного дома.
 *
 * Отвечает за:
 * - Сохранение результатов сканирования сети
 * - Управление устройствами (переименование, удаление)
 * - Предоставление данных для управления
 */
@Singleton
class SmartHomeRepository @Inject constructor(
    private val dao: SmartHomeDao
) {
    // ==================== Networks ====================

    suspend fun saveNetwork(network: SmartHomeNetwork) {
        // 🔥 Важно: НЕ используем @Insert(REPLACE) — он удаляет старую сеть
        // и CASCADE удаляет все её устройства! Используем UPDATE для существующих.
        val existing = dao.getNetworkBySsid(network.ssid)
        if (existing != null) {
            dao.updateNetwork(network.copy(id = existing.id))
        } else {
            dao.insertNetwork(network)
        }
        dao.trimNetworks()
    }

    suspend fun getNetworkBySsid(ssid: String): SmartHomeNetwork? {
        return dao.getNetworkBySsid(ssid)
    }

    suspend fun getNetworkById(networkId: String): SmartHomeNetwork? {
        return dao.getNetworkById(networkId)
    }

    fun observeNetworks(): Flow<List<SmartHomeNetwork>> {
        return dao.observeNetworks()
    }

    suspend fun getAllNetworks(): List<SmartHomeNetwork> {
        return dao.getAllNetworks()
    }

    suspend fun deleteNetwork(networkId: String) {
        dao.deleteNetwork(networkId)
    }

    // ==================== Devices ====================

    /**
     * Обновить результаты сканирования устройств сети.
     *
     * 1. Все существующие устройства сети → absent (present = false).
     * 2. Обнаруженные устройства — upsert (вновь найденные создаются,
     *    существующие обновляют IP/protocol/present/lastSeen).
     * 3. Отсутствующие устройства НЕ удаляются — остаются с present=false
     *    для отображения «было в сети, но сейчас офлайн».
     *    Удаление — только по запросу пользователя.
     */
    suspend fun updateFromScan(networkId: String, rawDevices: List<SmartHomeDevice>) {
        println("🔍 updateFromScan: network=$networkId, scanFound=${rawDevices.size} devices")

        // 1. Все устройства сети → absent
        dao.markAllAbsent(networkId)
        val beforeCount = dao.getDeviceCount(networkId)
        println("🔍 updateFromScan: markAllAbsent done, total devices in network=$beforeCount")

        // 2. Обновляем найденные устройства (прямой UPDATE по MAC — не трогает deviceConfig/displayName)
        var updated = 0
        var inserted = 0
        val now = System.currentTimeMillis()
        for (device in rawDevices) {
            val existing = dao.getDeviceByMac(device.mac)
            if (existing != null) {
                // Прямой SQL UPDATE по MAC — меняем ТОЛЬКО поля сканирования
                dao.updateDeviceFromScan(
                    mac = device.mac,
                    ip = device.ip,
                    hostname = device.hostname,
                    deviceType = device.deviceType,
                    protocol = device.protocol,
                    port = device.port,
                    capabilities = device.capabilities,
                    lastSeen = now,
                    vendor = device.vendor,
                    metadata = device.metadata
                )
                updated++
            } else {
                // Новое устройство — INSERT
                println("🔍 updateFromScan: NEW device mac=${device.mac} name=${device.displayName}")
                dao.upsertDevice(device)
                inserted++
            }
        }

        val afterCount = dao.getDeviceCount(networkId)
        println("🔍 updateFromScan: done — updated=$updated, inserted=$inserted, total=$afterCount (was $beforeCount)")

        // 3. НЕ удаляем absent — устройства с present=false остаются в БД
        // Пользователь может удалить их через интерфейс.
    }

    suspend fun getDeviceById(deviceId: String): SmartHomeDevice? {
        return dao.getDeviceById(deviceId)
    }

    suspend fun getDeviceByMac(mac: String): SmartHomeDevice? {
        return dao.getDeviceByMac(mac)
    }

    fun observeDevices(networkId: String): Flow<List<SmartHomeDevice>> {
        return dao.observeDevices(networkId)
    }

    suspend fun getDevices(networkId: String): List<SmartHomeDevice> {
        return dao.getDevices(networkId)
    }

    suspend fun getDevicesByType(networkId: String, deviceType: String): List<SmartHomeDevice> {
        return dao.getDevicesByType(networkId, deviceType)
    }

    suspend fun getDevicesByProtocol(networkId: String, protocol: String): List<SmartHomeDevice> {
        return dao.getDevicesByProtocol(networkId, protocol)
    }

    suspend fun getDeviceTypes(networkId: String): List<String> {
        return dao.getDeviceTypes(networkId)
    }

    suspend fun getPresentDevices(networkId: String): List<SmartHomeDevice> {
        return dao.getPresentDevices(networkId)
    }

    /** Устройства, которыми можно управлять (есть протокол). */
    suspend fun getManageableDevices(networkId: String): List<SmartHomeDevice> {
        return dao.getManageableDevices(networkId)
    }

    suspend fun renameDevice(deviceId: String, newName: String) {
        dao.renameDevice(deviceId, newName)
    }

    suspend fun deleteDevice(deviceId: String) {
        dao.deleteDevice(deviceId)
    }

    suspend fun getDeviceCount(networkId: String): Int {
        return dao.getDeviceCount(networkId)
    }

    /**
     * Разрешить интент управления: найти устройства по типу и/или имени.
     *
     * @return список устройств, подходящих под запрос
     */
    suspend fun resolveIntent(
        networkId: String,
        targetType: String?,
        targetName: String?
    ): List<SmartHomeDevice> {
        val candidates = if (targetType != null) {
            getDevicesByType(networkId, targetType)
        } else {
            getManageableDevices(networkId)
        }

        if (targetName.isNullOrBlank()) return candidates

        val lowerName = targetName.lowercase()
        return candidates.filter {
            it.displayName.lowercase().contains(lowerName) ||
            it.hostname.lowercase().contains(lowerName)
        }
    }
}
