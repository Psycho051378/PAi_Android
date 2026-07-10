package com.pai.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.pai.android.data.model.SmartHomeDevice
import com.pai.android.data.model.SmartHomeNetwork
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartHomeDao {

    // ==================== Networks ====================

    /** Сохранить или обновить сеть. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNetwork(network: SmartHomeNetwork)

    /** Обновить существующую сеть (без CASCADE-удаления). */
    @Update
    suspend fun updateNetwork(network: SmartHomeNetwork)

    /** Получить сеть по SSID (если несколько — самую свежую). */
    @Query("SELECT * FROM smart_home_network WHERE ssid = :ssid ORDER BY lastScan DESC LIMIT 1")
    suspend fun getNetworkBySsid(ssid: String): SmartHomeNetwork?

    /** Получить сеть по ID. */
    @Query("SELECT * FROM smart_home_network WHERE id = :networkId")
    suspend fun getNetworkById(networkId: String): SmartHomeNetwork?

    /** Все сохранённые сети (наблюдение). */
    @Query("SELECT * FROM smart_home_network ORDER BY lastScan DESC")
    fun observeNetworks(): Flow<List<SmartHomeNetwork>>

    /** Все сохранённые сети (однократно). */
    @Query("SELECT * FROM smart_home_network ORDER BY lastScan DESC")
    suspend fun getAllNetworks(): List<SmartHomeNetwork>

    /** Удалить сеть и все её устройства (CASCADE). */
    @Query("DELETE FROM smart_home_network WHERE id = :networkId")
    suspend fun deleteNetwork(networkId: String)

    /** Удалить старые сети, оставив не более [keepCount]. */
    @Transaction
    suspend fun trimNetworks(keepCount: Int = SmartHomeNetwork.MAX_NETWORKS) {
        val all = getAllNetworks()
        if (all.size > keepCount) {
            val toDelete = all.drop(keepCount)
            for (net in toDelete) {
                deleteNetwork(net.id)
            }
        }
    }

    // ==================== Devices ====================

    /**
     * Вставить новое устройство или обновить существующее по MAC.
     * Использует REPLACE — конфликт по уникальному индексу mac.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: SmartHomeDevice)

    /** Массовая вставка/обновление. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevices(devices: List<SmartHomeDevice>)

    /** Обновить существующее устройство (не REPLACE — только указанные поля). */
    @Update
    suspend fun updateDevice(device: SmartHomeDevice)

    /**
     * Обновить устройство при сканировании по MAC.
     * НЕ трогает deviceConfig, displayName, firstSeen.
     */
    @Query("""
        UPDATE smart_home_devices SET
            ip = :ip,
            hostname = CASE WHEN :hostname != '' THEN :hostname ELSE hostname END,
            deviceType = CASE WHEN deviceType != 'OTHER' THEN deviceType ELSE :deviceType END,
            protocol = CASE WHEN protocol != 'UNKNOWN' THEN protocol ELSE :protocol END,
            port = CASE WHEN :port > 0 THEN :port ELSE port END,
            capabilities = CASE WHEN :capabilities != '[]' THEN :capabilities ELSE capabilities END,
            present = 1,
            lastSeen = :lastSeen,
            vendor = CASE WHEN :vendor != '' THEN :vendor ELSE vendor END,
            metadata = CASE WHEN :metadata != '{}' THEN :metadata ELSE metadata END
        WHERE mac = :mac
    """)
    suspend fun updateDeviceFromScan(
        mac: String,
        ip: String,
        hostname: String,
        deviceType: String,
        protocol: String,
        port: Int,
        capabilities: String,
        lastSeen: Long,
        vendor: String,
        metadata: String
    )

    /** Получить устройство по ID. */
    @Query("SELECT * FROM smart_home_devices WHERE id = :deviceId")
    suspend fun getDeviceById(deviceId: String): SmartHomeDevice?

    /** Получить устройство по MAC. */
    @Query("SELECT * FROM smart_home_devices WHERE mac = :mac")
    suspend fun getDeviceByMac(mac: String): SmartHomeDevice?

    /** Все устройства в сети (наблюдение). */
    @Query("SELECT * FROM smart_home_devices WHERE networkId = :networkId ORDER BY deviceType, displayName")
    fun observeDevices(networkId: String): Flow<List<SmartHomeDevice>>

    /** Все устройства в сети (однократно). */
    @Query("SELECT * FROM smart_home_devices WHERE networkId = :networkId ORDER BY deviceType, displayName")
    suspend fun getDevices(networkId: String): List<SmartHomeDevice>

    /** Все устройства указанного типа. */
    @Query("SELECT * FROM smart_home_devices WHERE networkId = :networkId AND deviceType = :deviceType ORDER BY displayName")
    suspend fun getDevicesByType(networkId: String, deviceType: String): List<SmartHomeDevice>

    /** Все устройства с указанным протоколом. */
    @Query("SELECT * FROM smart_home_devices WHERE networkId = :networkId AND protocol = :protocol ORDER BY displayName")
    suspend fun getDevicesByProtocol(networkId: String, protocol: String): List<SmartHomeDevice>

    /** Все типы устройств, присутствующие в сети. */
    @Query("SELECT DISTINCT deviceType FROM smart_home_devices WHERE networkId = :networkId ORDER BY deviceType")
    suspend fun getDeviceTypes(networkId: String): List<String>

    /** Устройства, которые присутствуют сейчас (present = true). */
    @Query("SELECT * FROM smart_home_devices WHERE networkId = :networkId AND present = 1 ORDER BY deviceType, displayName")
    suspend fun getPresentDevices(networkId: String): List<SmartHomeDevice>

    /** Устройства, доступные для управления (present = true AND protocol != 'UNKNOWN'). */
    @Query("SELECT * FROM smart_home_devices WHERE networkId = :networkId AND present = 1 AND protocol != 'UNKNOWN' ORDER BY deviceType, displayName")
    suspend fun getManageableDevices(networkId: String): List<SmartHomeDevice>

    /** Обновить displayName устройства. */
    @Query("UPDATE smart_home_devices SET displayName = :newName WHERE id = :deviceId")
    suspend fun renameDevice(deviceId: String, newName: String)

    /** Отметить все устройства сети как absent (перед обновлением). */
    @Query("UPDATE smart_home_devices SET present = 0 WHERE networkId = :networkId")
    suspend fun markAllAbsent(networkId: String)

    /** Удалить устройство. */
    @Query("DELETE FROM smart_home_devices WHERE id = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    /** Удалить отсутствующие устройства сети (present = 0). */
    @Query("DELETE FROM smart_home_devices WHERE networkId = :networkId AND present = 0")
    suspend fun cleanAbsentDevices(networkId: String)

    /** Количество устройств в сети. */
    @Query("SELECT COUNT(*) FROM smart_home_devices WHERE networkId = :networkId")
    suspend fun getDeviceCount(networkId: String): Int
}
