package com.pai.android.agent

/**
 * Чистое решение по шагу проекта (без Android) — чтобы логику можно было
 * покрыть unit-тестами отдельно от тяжёлого TaskQueue.
 */
internal object StepVerdict {

    enum class Outcome { DONE, RETRY, FAILED }

    /**
     * @param success      генератор кода отчитался об успехе
     * @param stepIndex    индекс шага (0 — первый: он создаёт проект)
     * @param filesBefore  снимок файлов ДО генерации (path -> lastModified)
     * @param filesAfter   снимок файлов ПОСЛЕ генерации
     * @param attempt      номер текущей попытки (с 1)
     * @param maxAttempts  максимум попыток
     */
    fun decide(
        success: Boolean,
        stepIndex: Int,
        filesBefore: Map<String, Long>,
        filesAfter: Map<String, Long>,
        attempt: Int,
        maxAttempts: Int
    ): Outcome {
        val filesChanged = filesAfter.size != filesBefore.size ||
            filesAfter.any { (path, time) -> filesBefore[path] != time }
        // Первый шаг создаёт проект — файлов "до" нет, поэтому изменений не требуем.
        val ok = success && (stepIndex == 0 || filesChanged)
        if (ok) return Outcome.DONE
        return if (attempt < maxAttempts) Outcome.RETRY else Outcome.FAILED
    }
}
