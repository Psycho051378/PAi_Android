package com.pai.android.agent.skills.home.device

import com.pai.android.agent.skills.home.identity.DeviceState

/**
 * Контроллер, который умеет не только писать, но и читать состояние.
 *
 * Нужен для двух вещей:
 * 1. Верификация: после команды перечитываем состояние и честно говорим,
 *    применилась ли она (раньше «✅» означало лишь «пакет ушёл»).
 * 2. Идемпотентность: не отправлять команду, если устройство уже в этом состоянии.
 */
interface StatefulController : DeviceController {

    /**
     * Прочитать текущее состояние устройства.
     *
     * @return null, если состояние прочитать нельзя (нет API, устройство офлайн,
     *         протокол не поддерживает)
     */
    suspend fun readState(ip: String, port: Int): DeviceState?
}
