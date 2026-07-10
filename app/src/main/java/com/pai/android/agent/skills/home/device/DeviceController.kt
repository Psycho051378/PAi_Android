package com.pai.android.agent.skills.home.device

/**
 * Результат управления устройством.
 */
data class ControlResult(
    val success: Boolean,
    val message: String = "",
    val error: String? = null
)

/**
 * Интерфейс контроллера устройства.
 * Каждый протокол (WiZ, Yeelight, Tasmota...) реализует этот интерфейс.
 *
 * Методы возвращают ControlResult, а не Boolean, чтобы передавать
 * человекочитаемые сообщения об ошибках.
 */
interface DeviceController {
    /** Название протокола (должен совпадать с DeviceProtocol.name). */
    val protocol: String

    /** Включить устройство. */
    suspend fun turnOn(ip: String, port: Int): ControlResult

    /** Выключить устройство. */
    suspend fun turnOff(ip: String, port: Int): ControlResult

    /** Установить яркость (0-100). */
    suspend fun setBrightness(ip: String, port: Int, level: Int): ControlResult

    /** Установить цветовую температуру (в Кельвинах, если поддерживается). */
    suspend fun setColorTemp(ip: String, port: Int, temp: Int): ControlResult

    /** Установить RGB цвет. */
    suspend fun setRGB(ip: String, port: Int, r: Int, g: Int, b: Int): ControlResult

    /** Проверить, доступно ли устройство. */
    suspend fun ping(ip: String, port: Int): Boolean

    /** Выполнить произвольную команду (для расширения). */
    suspend fun customCommand(ip: String, port: Int, command: String, params: Map<String, Any> = emptyMap()): ControlResult
}
