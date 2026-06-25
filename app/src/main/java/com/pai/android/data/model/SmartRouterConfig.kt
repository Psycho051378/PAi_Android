package com.pai.android.data.model

/**
 * Настройки Smart Router — распределение запросов между локальной и сетевой моделью.
 */
data class SmartRouterConfig(
    val enabled: Boolean = false,
    val networkProviderSettingsId: String = "",     // ID настроек сетевого провайдера
    val complexityThreshold: Float = 0.5f,           // порог сложности 0.0-1.0
    val maxLocalTokens: Int = 512,                   // макс токенов для локалки при фолбэке
    val routeMultimodalToLocal: Boolean = true,       // изображения/аудио → локалка
    val enableFallback: Boolean = true,               // фолбэк на локалку при недоступности сети
    val enableHybrid: Boolean = false                 // гибридный режим (экспериментальный)
)
