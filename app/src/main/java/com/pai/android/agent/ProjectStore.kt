package com.pai.android.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Файловое хранилище проектов.
 * Заменяет хранение проектов «фактами» в SQLite (которое падало на CursorWindow
 * при больших снапшотах). Каждый проект — отдельный JSON-файл.
 */
@Singleton
class ProjectStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File
        get() = File(context.filesDir, "projects").apply { if (!exists()) mkdirs() }

    private fun fileFor(id: String) = File(dir, id + ".json")

    fun read(id: String): String? {
        val f = fileFor(id)
        return try { if (f.exists()) f.readText(Charsets.UTF_8) else null } catch (e: Exception) { null }
    }

    fun write(id: String, json: String): Boolean = try {
        fileFor(id).writeText(json, Charsets.UTF_8); true
    } catch (e: Exception) {
        println("⚠️ ProjectStore: write failed for " + id + ": " + e.message); false
    }

    fun delete(id: String): Boolean = try { fileFor(id).delete() } catch (e: Exception) { false }

    fun listIds(): List<String> =
        (dir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
            .map { it.nameWithoutExtension }
            .sorted()
}
