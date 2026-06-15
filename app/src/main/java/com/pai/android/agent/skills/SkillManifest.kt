package com.pai.android.agent.skills

/**
 * Манифест внешнего навыка.
 */
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "",
    val instructions: String,
    val endpoint: String = "",  // URL для PHP-навыков
    val type: String = "php",  // "php" (HTTP) или "python" (локальный)
    val mainScript: String = "",  // имя .py файла для type=python
    val triggers: List<String> = emptyList(),  // ключевые слова для активации
    val skip_words: List<String> = emptyList(),  // слова для очистки из запроса
    val timeout: Int = 30,  // таймаут выполнения в секундах (по умолчанию 30)
    val params: Map<String, String> = emptyMap(),  // paramName -> type
    val enabled: Boolean = true
)

data class SkillIndex(
    val skills: List<SkillIndexEntry>
)

data class SkillIndexEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val manifest_url: String
)
