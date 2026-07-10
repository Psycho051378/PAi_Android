package com.pai.android.agent.skills.home.device

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Роутер, который по протоколу устройства возвращает подходящий контроллер.
 * Контроллеры регистрируются в мапе при создании.
 */
@Singleton
class DeviceControllerRouter @Inject constructor(
    private val wizController: WizController,
    private val yeelightController: YeelightController,
    val miioController: MiioController
) {
    private val controllers: Map<String, DeviceController> = mapOf(
        "WIZ" to wizController,
        "YEELIGHT" to yeelightController,
        "MIIO" to miioController,
        "ROBOROCK" to miioController
    )

    /**
     * Получить контроллер для указанного протокола.
     * @return контроллер или null, если протокол не поддерживается
     */
    fun getController(protocol: String): DeviceController? {
        return controllers[protocol.uppercase()]
    }

    /**
     * Список поддерживаемых протоколов.
     */
    fun getSupportedProtocols(): Set<String> = controllers.keys

    /**
     * Проверить, поддерживается ли протокол.
     */
    fun supports(protocol: String): Boolean = controllers.containsKey(protocol.uppercase())
}
