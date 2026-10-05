package com.pai.android.agent

import java.io.File

/**
 * Чистые (без Android) помощники для безопасной работы с путями рабочей папки.
 * Вынесено отдельным объектом, чтобы логику можно было покрыть unit-тестами.
 */
internal object WorkspacePaths {

    /**
     * Нормализует относительный путь: убирает ведущие "/", коллапсирует разделители
     * и ВЫБРАСЫВАЕТ сегменты "." и "..", поэтому путь не может выйти за пределы workspace.
     */
    fun normalize(path: String): String {
        val p = path.trim().replace('\\', '/')
        if (p.isEmpty() || p == "/" || p == ".") return ""
        return p.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
    }

    /**
     * true, если targetPath лежит внутри rootPath (или совпадает с ним).
     * Разделители приводим к '/', чтобы одинаково работать и на Windows-JVM (тесты),
     * и на Android (где File.separator == '/').
     */
    fun isInside(rootPath: String, targetPath: String): Boolean {
        val root = rootPath.replace('\\', '/').trimEnd('/')
        val target = targetPath.replace('\\', '/')
        return target == root || target.startsWith(root + "/")
    }
}
