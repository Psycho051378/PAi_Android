package com.pai.android.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Находит пути/имена файлов, упомянутые в ответе агента (в бэктиках, с расширением). */
private val AGENT_FILE_PATH_REGEX = Regex("`([^`]+)`")

internal fun extractAgentFilePaths(text: String): List<String> =
    AGENT_FILE_PATH_REGEX.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { cand ->
            !cand.contains("://") &&
                !cand.startsWith("http") &&
                cand.none { it == ' ' || it == '\n' || it == '\t' } &&
                !cand.contains('{') && !cand.contains('(') &&
                cand.substringAfterLast('.', "").length in 1..6
        }
        .distinct()
        .toList()

private fun workspaceRoot(context: Context): File =
    File(context.getExternalFilesDir(null), "workspace")

/** Находит файл в workspace: сначала точный путь, затем поиск по имени (рекурсивно). */
internal fun resolveAgentFile(context: Context, relativePath: String): File? {
    val root = workspaceRoot(context)
    val direct = File(root, relativePath)
    if (direct.exists() && direct.isFile) return direct
    val name = relativePath.substringAfterLast('/')
    if (name.isBlank()) return null
    return root.walkTopDown()
        .firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
}

/** Открывает файл из workspace во внешнем приложении (или сообщает, если не нашлось). */
internal fun openAgentWorkspaceFile(context: Context, relativePath: String) {
    val file = resolveAgentFile(context, relativePath)
    if (file == null) {
        Toast.makeText(context, "Файл не найден: " + relativePath, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeForName(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "Нет приложения для этого типа файла", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось открыть: " + (e.message ?: "?"), Toast.LENGTH_SHORT).show()
    }
}

private fun mimeForName(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "txt", "md", "json", "xml", "csv", "log", "py", "kt", "js", "css" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        else -> "*/*"
    }