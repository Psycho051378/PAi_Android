package com.pai.android.agent.skills.home.router

/**
 * Протокол подключения к роутеру.
 */
enum class ProtocolType {
    HTTP,
    SSH,
    SNMP
}

/**
 * Универсальный интерфейс для подключения к роутеру через различные протоколы.
 * Поддерживает HTTP (веб-интерфейс), SSH (терминальный доступ) и SNMP (v2c).
 */
interface RouterClient {
    /** Человекочитаемое название клиента (например, "TP-Link HTTP" или "SNMP v2c"). */
    val name: String

    /** Тип протокола, который использует этот клиент. */
    val protocolType: ProtocolType

    /**
     * Проверяет соединение с роутером по указанным параметрам конфигурации.
     * @return true, если соединение успешно и роутер отвечает.
     */
    suspend fun testConnection(): Boolean

    /**
     * Получает ARP-таблицу (таблицу соответствия IP → MAC-адресов) с роутера.
     * @return Map, где ключ — IP-адрес, значение — MAC-адрес устройства.
     */
    suspend fun getArpTable(): Map<String, String>
}
