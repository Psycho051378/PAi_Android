package com.pai.android.agent.tools

import com.pai.android.agent.AgentTool
import com.pai.android.agent.ToolResult
import com.pai.android.agent.Logger as AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Инструмент чтения датчиков телефона.
 *
 * Позволяет получать показания:
 * - Температура батареи
 * - Температура окружающей среды (если есть датчик)
 * - Освещённость (люкс)
 * - Атмосферное давление (гПа)
 * - Приближение
 * - Влажность
 *
 * Для аппаратных датчиков используется одноразовый Listener с таймаутом.
 * Значения проверяются на валидность: NaN, Infinity, разумные диапазоны.
 */
@Singleton
class DeviceSensorTool @Inject constructor(
    @ApplicationContext private val context: Context
) : AgentTool {

    override val name: String = "device_sensors"
    override val description: String =
        "Phone hardware sensor readings: battery temperature, ambient temperature, light level (lux), pressure/barometer (hPa), proximity, humidity. Use action=all for everything, or action=specific_name for one sensor."

    override val parametersSchema: String = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["all", "battery_temp", "ambient_temp", "light", "pressure", "proximity", "humidity"],
                "description": "all = read all available sensors; battery_temp = battery temperature (°C); ambient_temp = ambient temperature (°C); light = light level (lux); pressure = barometer (hPa); proximity = proximity sensor; humidity = relative humidity (%)"
            }
        },
        "required": ["action"]
    }"""

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = (params["action"] as? String)?.lowercase() ?: "all"
        AppLogger.i("DeviceSensorTool", "execute: action=$action")

        return try {
            when (action) {
                "all" -> readAllSensors()
                "battery_temp" -> readSingleSensor("battery_temp")
                "ambient_temp" -> readSingleSensor("ambient_temp")
                "light" -> readSingleSensor("light")
                "pressure" -> readSingleSensor("pressure")
                "proximity" -> readSingleSensor("proximity")
                "humidity" -> readSingleSensor("humidity")
                else -> ToolResult.Error(
                    error = "Unknown action: $action. Available: all, battery_temp, ambient_temp, light, pressure, proximity, humidity"
                )
            }
        } catch (e: Exception) {
            AppLogger.e("DeviceSensorTool", "Sensor error: ${e.message}")
            ToolResult.Error(error = "Sensor error: ${e.message ?: "unknown"}")
        }
    }

    // ================ Reasonable value ranges per sensor type ================

    companion object {
        /** Максимальная разумная температура батареи в °C */
        private const val MAX_BATTERY_TEMP = 80f
        /** Диапазон температуры окружающей среды: -50..+60°C  */
        private val AMBIENT_TEMP_RANGE = -50f..60f
        /** Диапазон освещённости: 0..200000 люкс */
        private val LIGHT_RANGE = 0f..200000f
        /** Диапазон давления: 300..1100 гПа */
        private val PRESSURE_RANGE = 300f..1100f
        /** Диапазон влажности: 0..100% */
        private val HUMIDITY_RANGE = 0f..100f
        /** Диапазон приближения: 0..100 см */
        private val PROXIMITY_RANGE = 0f..100f
    }

    /** Проверяет, что значение физически возможно. */
    private fun isValidSensorValue(value: Float, range: ClosedFloatingPointRange<Float>): Boolean {
        return !value.isNaN() && !value.isInfinite() && value in range
    }

    // ================ All sensors ================

    private suspend fun readAllSensors(): ToolResult = withContext(Dispatchers.IO) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val batteryTemp = readBatteryTemperature()

        val sb = StringBuilder()
        sb.appendLine("📱 **Датчики телефона**")
        sb.appendLine()

        // Battery temperature
        if (batteryTemp != null && batteryTemp in 0f..MAX_BATTERY_TEMP) {
            sb.appendLine("🔋 **Батарея:** ${"%.1f".format(batteryTemp)}°C")
        } else {
            sb.appendLine("🔋 **Батарея:** недоступно")
        }

        if (sensorManager == null) {
            sb.appendLine("⚠️ SensorManager недоступен")
            return@withContext ToolResult.Success(output = sb.toString())
        }

        // Ambient temperature
        val ambientTemp = readHardwareSensor(sensorManager, Sensor.TYPE_AMBIENT_TEMPERATURE)
        if (ambientTemp != null && isValidSensorValue(ambientTemp, AMBIENT_TEMP_RANGE)) {
            sb.appendLine("🌡️ **Окружающая температура:** ${"%.1f".format(ambientTemp)}°C")
        }

        // Light
        val light = readHardwareSensor(sensorManager, Sensor.TYPE_LIGHT)
        if (light != null && isValidSensorValue(light, LIGHT_RANGE) ) {
            val luxDesc = when {
                light < 10 -> "🕯️ (очень темно)"
                light < 50 -> "🌙 (сумрачно)"
                light < 300 -> "💡 (комнатное освещение)"
                light < 1000 -> "☀️ (яркий свет)"
                else -> "🌞 (очень ярко)"
            }
            sb.appendLine("💡 **Освещённость:** ${light.toInt()} люкс $luxDesc")
        }

        // Pressure
        val pressure = readHardwareSensor(sensorManager, Sensor.TYPE_PRESSURE)
        if (pressure != null && isValidSensorValue(pressure, PRESSURE_RANGE)) {
            val hPa = "%.1f".format(pressure)
            sb.appendLine("🌀 **Давление:** $hPa гПа")
        }

        // Proximity
        val proximity = readHardwareSensor(sensorManager, Sensor.TYPE_PROXIMITY)
        if (proximity != null && isValidSensorValue(proximity, PROXIMITY_RANGE)) {
            val proxDesc = if (proximity < 1f) "📞 (объект близко)" else "📱 (далеко)"
            sb.appendLine("📏 **Приближение:** ${"%.1f".format(proximity)} см $proxDesc")
        }

        // Humidity
        val humidity = readHardwareSensor(sensorManager, Sensor.TYPE_RELATIVE_HUMIDITY)
        if (humidity != null && isValidSensorValue(humidity, HUMIDITY_RANGE) ) {
            sb.appendLine("💧 **Влажность:** ${"%.0f".format(humidity)}%")
        }

        // Available sensors summary
        val availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        if (availableSensors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("*Всего датчиков: ${availableSensors.size}*")
        }

        ToolResult.Success(output = sb.toString())
    }

    // ================ Single sensor ================

    private suspend fun readSingleSensor(sensorName: String): ToolResult = withContext(Dispatchers.IO) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        val result = when (sensorName) {
            "battery_temp" -> {
                val temp = readBatteryTemperature()
                if (temp != null && temp in 0f..MAX_BATTERY_TEMP) "🔋 **Температура батареи:** ${"%.1f".format(temp)}°C"
                else "❌ Температура батареи недоступна"
            }
            "ambient_temp" -> {
                val value = sensorManager?.let { readHardwareSensor(it, Sensor.TYPE_AMBIENT_TEMPERATURE) }
                if (value != null && isValidSensorValue(value, AMBIENT_TEMP_RANGE)) "🌡️ **Окружающая температура:** ${"%.1f".format(value)}°C"
                else "❌ Датчик температуры окружающей среды не найден или недоступен"
            }
            "light" -> {
                val value = sensorManager?.let { readHardwareSensor(it, Sensor.TYPE_LIGHT) }
                if (value != null && isValidSensorValue(value, LIGHT_RANGE) ) "💡 **Освещённость:** ${value.toInt()} люкс"
                else "❌ Датчик освещённости не найден или недоступен"
            }
            "pressure" -> {
                val value = sensorManager?.let { readHardwareSensor(it, Sensor.TYPE_PRESSURE) }
                if (value != null && isValidSensorValue(value, PRESSURE_RANGE)) "🌀 **Давление:** ${"%.1f".format(value)} гПа"
                else "❌ Барометр не найден или недоступен"
            }
            "proximity" -> {
                val value = sensorManager?.let { readHardwareSensor(it, Sensor.TYPE_PROXIMITY) }
                if (value != null && isValidSensorValue(value, PROXIMITY_RANGE)) "📏 **Приближение:** ${"%.1f".format(value)} см"
                else "❌ Датчик приближения не найден"
            }
            "humidity" -> {
                val value = sensorManager?.let { readHardwareSensor(it, Sensor.TYPE_RELATIVE_HUMIDITY) }
                if (value != null && isValidSensorValue(value, HUMIDITY_RANGE) ) "💧 **Влажность:** ${"%.0f".format(value)}%"
                else "❌ Датчик влажности не найден или недоступен"
            }
            else -> "❌ Неизвестный датчик: $sensorName"
        }

        ToolResult.Success(output = result)
    }

    // ================ Low-level sensor readers ================

    /**
     * Читает температуру батареи синхронно через BatteryManager.
     */
    private fun readBatteryTemperature(): Float? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE)
                if (rawTemp != Integer.MIN_VALUE) rawTemp / 10f else null
            } else null
        } catch (e: Exception) {
            AppLogger.e("DeviceSensorTool", "Battery temp error: ${e.message}")
            null
        }
    }

    /**
     * Читает показания аппаратного датчика через одноразовый Listener.
     * @param sensorType тип датчика из Sensor.TYPE_*
     * @return значение первого семпла, или null если датчика нет/таймаут/невалидное значение
     */
    private fun readHardwareSensor(sensorManager: SensorManager, sensorType: Int): Float? {
        val sensor = sensorManager.getDefaultSensor(sensorType) ?: return null

        val latch = CountDownLatch(1)
        val result = AtomicReference<Float>(Float.NaN)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) {
                    val value = event.values[0]
                    // Отбрасываем NaN, Infinity — эмуляторные заглушки
                    if (!value.isNaN() && !value.isInfinite()) {
                        result.set(value)
                        latch.countDown()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // не используется
            }
        }

        return try {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            val received = latch.await(2, TimeUnit.SECONDS)
            sensorManager.unregisterListener(listener)

            if (received) {
                val value = result.get()
                if (value.isNaN() || value.isInfinite()) null else value
            } else null
        } catch (e: Exception) {
            sensorManager.unregisterListener(listener)
            AppLogger.e("DeviceSensorTool", "Sensor read error (type=$sensorType): ${e.message}")
            null
        }
    }
}
