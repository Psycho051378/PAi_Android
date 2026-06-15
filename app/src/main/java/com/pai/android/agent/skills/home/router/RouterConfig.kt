package com.pai.android.agent.skills.home.router

/**
 * Конфигурация для подключения к роутеру.
 *
 * @param ip IP-адрес роутера в локальной сети (по умолчанию 192.168.0.1).
 * @param port Порт для подключения (80 для HTTP, 22 для SSH, 161 для SNMP).
 * @param username Имя пользователя для HTTP/SSH авторизации.
 * @param password Пароль для HTTP/SSH авторизации.
 * @param community SNMP community string (по умолчанию "public").
 * @param protocol Протокол подключения (HTTP, SSH, SNMP).
 * @param ssid SSID Wi-Fi сети, к которой относится эта конфигурация (ключ для БД).
 */
data class RouterConfig(
    val ip: String = "192.168.0.1",
    val port: Int = 80,
    val username: String = "",
    val password: String = "",
    val community: String = "public",
    val protocol: ProtocolType = ProtocolType.HTTP,
    val ssid: String = ""
)
