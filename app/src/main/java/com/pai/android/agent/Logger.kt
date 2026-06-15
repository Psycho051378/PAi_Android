package com.pai.android.agent

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Уровень логирования.
 */
enum class LogLevel(val tag: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR")
}

/**
 * Одна запись в логе.
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Кольцевой буфер логов.
 * Хранит до [capacity] последних записей в памяти.
 * Заменяет println() — пишет и в logcat, и в буфер.
 */
object Logger {
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        System.setOut(object : java.io.PrintStream(System.out) {
            override fun println(msg: String) {
                // Не вызываем super.println — Logger.log() сам пишет в логкат
                val entry = parsePrintln(msg)
                log(entry.first, "SYSOUT", entry.second)
            }
            override fun println(x: Any?) {
                println(x?.toString() ?: "null")
            }
        })
    }

    private fun parsePrintln(msg: String): Pair<LogLevel, String> {
        return when {
            msg.startsWith("\u26A0") || msg.startsWith("\u274C") -> LogLevel.WARN to msg
            msg.startsWith("\uD83D\uDD25") || msg.startsWith("\uD83D\uDEA8") -> LogLevel.ERROR to msg
            else -> LogLevel.INFO to msg
        }
    }

    private const val DEFAULT_CAPACITY = 500
    private val buffer = mutableListOf<LogEntry>()
    private var capacity = DEFAULT_CAPACITY
    private val listeners = mutableListOf<() -> Unit>()

    // Текущие фильтры
    var filterLevel: LogLevel? = null  // null = показывать всё
    var filterQuery: String = ""

    /**
     * Устанавливает ёмкость буфера.
     */
    fun setCapacity(newCapacity: Int) {
        capacity = newCapacity.coerceIn(100, 2000)
        trimToCapacity()
    }

    /**
     * Добавляет запись в лог.
     */
    @JvmStatic
    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)

        // Пишем в logcat
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }

        // println removed to avoid recursion with System.out interceptor

        // Добавляем в буфер
        synchronized(buffer) {
            buffer.add(entry)
            trimToCapacity()
        }
        notifyListeners()
    }

    // Удобные обёртки
    @JvmStatic
    fun i(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)

    @JvmStatic
    fun w(tag: String, msg: String) = log(LogLevel.WARN, tag, msg)

    @JvmStatic
    fun e(tag: String, msg: String) = log(LogLevel.ERROR, tag, msg)

    @JvmStatic
    fun d(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg)

    /**
     * Возвращает копию текущих записей с применением фильтров.
     */
    fun getEntries(): List<LogEntry> {
        synchronized(buffer) {
            var result = buffer.toList()
            // Фильтр по уровню
            filterLevel?.let { level ->
                val levels = when (level) {
                    LogLevel.ERROR -> listOf(LogLevel.ERROR)
                    LogLevel.WARN -> listOf(LogLevel.WARN, LogLevel.ERROR)
                    LogLevel.INFO -> listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)
                    LogLevel.DEBUG -> LogLevel.values().toList()
                }
                result = result.filter { it.level in levels }
            }
            // Текстовый поиск
            if (filterQuery.isNotBlank()) {
                val q = filterQuery.lowercase()
                result = result.filter {
                    it.tag.lowercase().contains(q) ||
                    it.message.lowercase().contains(q)
                }
            }
            return result
        }
    }

    /**
     * Очищает буфер.
     */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
        notifyListeners()
    }

    /**
     * Экспорт логов в текстовую строку.
     */
    fun export(): String {
        return getEntries().joinToString("\n") { entry ->
            "${entry.formattedTime} [${entry.level.tag}] ${entry.tag}: ${entry.message}"
        }
    }

    /**
     * Подписка на изменения (для обновления UI).
     */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    private fun trimToCapacity() {
        while (buffer.size > capacity) {
            buffer.removeAt(0)
        }
    }
}

