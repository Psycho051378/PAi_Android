package com.pai.android.agent.skills.home.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SSH клиент для подключения к роутеру.
 *
 * ⚠️ ЗАГЛУШКА: Полноценная реализация SSH требует внешней библиотеки (JSch или sshj).
 *
 * Android SDK не включает SSH-клиент, поэтому для v1 реализован интерфейс-заглушка.
 *
 * Зависимости для v2 (добавить в build.gradle.kts):
 * - implementation("com.hierynomus:sshj:0.38.0")
 *   или
 * - implementation("com.jcraft:jsch:0.1.55")
 *
 * Формат ARP-команды на роутере (типичный):
 * - show arp (Cisco-like)
 * - arp -a (Linux-based, OpenWrt)
 * - /usr/sbin/arp (DD-WRT, OpenWrt)
 */
class SshRouterClient(
    private val routerConfig: RouterConfig
) : RouterClient {

    override val name: String = "SSH (заглушка)"
    override val protocolType: ProtocolType = ProtocolType.SSH

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        println("SSH: testConnection — заглушка. Требуется JSch/sshj для реализации.")
        // В v1 возвращаем false, так как SSH без библиотеки не работает.
        println("SSH: подключение к ${routerConfig.ip}:${routerConfig.port} не выполнено — нужна библиотека sshj")
        false
    }

    override suspend fun getArpTable(): Map<String, String> = withContext(Dispatchers.IO) {
        println("SSH: getArpTable — заглушка. Требуется JSch/sshj для реализации.")
        println("SSH: команда будет выполнена на ${routerConfig.ip}: arp -a")
        emptyMap()
    }
}
