package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Гео-задача: напоминание, привязанное к координатам.
 *
 * Срабатывает, когда пользователь оказывается в радиусе [radiusMeters] от точки.
 * Если [oneShot] = true — триггернется один раз и деактивируется.
 * [lastTriggeredAt] предотвращает повторные срабатывания чаще раза в 30 минут.
 */
@Entity(tableName = "geo_tasks")
data class GeoTask(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val label: String,                          // "Купить молоко"
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,                // "Пятёрочка, Ленинградская 1"
    val radiusMeters: Int = 300,                // радиус срабатывания
    val isActive: Boolean = true,
    val oneShot: Boolean = true,                // сработать один раз
    val lastTriggeredAt: Long? = null,          // время последнего триггера
    val lastTriggeredAddress: String? = null,   // адрес при последнем триггере
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Можно ли триггерить сейчас (не было триггера в последние 30 минут).
     */
    fun canTrigger(): Boolean {
        val last = lastTriggeredAt ?: return true
        return System.currentTimeMillis() - last > TRIGGER_COOLDOWN_MS
    }

    companion object {
        /** Защита от повторных срабатываний: 30 минут */
        const val TRIGGER_COOLDOWN_MS = 30 * 60 * 1000L
    }
}
