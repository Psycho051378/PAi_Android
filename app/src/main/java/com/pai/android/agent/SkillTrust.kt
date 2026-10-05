package com.pai.android.agent

import java.security.MessageDigest

/**
 * Чистая логика доверия к навыкам (без Android) — покрыта unit-тестами.
 * Используется для проверки SHA-256 скачанного/локального скрипта навыка.
 */
internal object SkillTrust {

    fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * true, только если ожидаемый хеш задан и совпадает с содержимым.
     * Отсутствие подписи (null/blank) трактуется как «не подтверждено».
     */
    fun verify(content: ByteArray, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrBlank()) return false
        val expected = expectedSha256.trim().lowercase()
        return sha256(content) == expected
    }
}
