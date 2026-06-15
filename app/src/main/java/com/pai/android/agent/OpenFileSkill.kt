package com.pai.android.agent

import android.content.Context
import android.content.Intent as AndroidIntent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Навык открытия файлов в соответствующих приложениях на Android.
 * Использует Android Intent system для открытия файлов в подходящих приложениях.
 */
class OpenFileSkill(
    private val context: Context,
    private val fileManager: FileManager
) : Skill {

    override val name: String = "open_file"

    override val description: String = "Open files: open files in appropriate applications, launch files"

    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        // Принимаем FILE_OPERATION, COMMAND и любой другой intent, если команда совпадает
        return params["command"] == "open_file"
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        return try {
            val path = params["path"] as? String ?: return SkillResult.Error(
                message = "Не указан путь к файлу для открытия",
                details = "Используйте параметр 'path' для указания пути к файлу"
            )

            println("📂 OpenFileSkill: открываю файл '$path'")

            var fileInfo = fileManager.getFileInfo(path)
            
            // Если файл не найден по точному пути — ищем рекурсивно по имени
            if (fileInfo == null) {
                println("📂 OpenFileSkill: файл '$path' не найден по точному пути, ищу рекурсивно...")
                val fileName = path.substringAfterLast('/').substringAfterLast('\\')
                if (fileName.isNotBlank()) {
                    val allFiles = fileManager.listFiles("", recursive = true)
                    val found = allFiles.firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
                    if (found != null) {
                        println("📂 OpenFileSkill: файл найден рекурсивно: ${found.path}")
                        fileInfo = found
                    }
                }
            }
            
            if (fileInfo == null) {
                return SkillResult.Error(
                    message = "Файл '$path' не найден в workspace",
                    details = "Проверьте правильность пути или создайте файл"
                )
            }

            if (fileInfo.isDirectory) {
                return SkillResult.Error(
                    message = "Нельзя открыть папку как файл",
                    details = "Укажите путь к файлу, а не к папке"
                )
            }

            val opened = openFile(fileInfo.path)
            
            if (opened) {
                SkillResult.Success(
                    message = "📂 **Файл открыт:** `${fileInfo.path}`",
                    data = mapOf("path" to fileInfo.path, "action" to "open"),
                    responseType = ResponseType.TEXT
                )
            } else {
                // Если не удалось открыть через Intent, пробуем прочитать текст для показа в чате
                val extension = fileInfo.name.substringAfterLast('.', "").lowercase()
                val textExtensions = listOf("txt", "md", "json", "xml", "csv", "html", "htm", 
                    "css", "js", "py", "kt", "java", "yaml", "yml", "toml", "ini", "cfg",
                    "log", "sh", "bat", "ps1", "sql", "r", "php", "pl", "rb", "swift")
                
                if (extension in textExtensions) {
                    val content = fileManager.readFile(fileInfo.path)
                    if (content != null) {
                        val preview = if (content.length > 1000) {
                            content.take(1000) + "\n\n... (${content.length - 1000} символов ещё)"
                        } else {
                            content
                        }
                        
                        return SkillResult.Success(
                            message = "📂 **Не удалось открыть файл в приложении** (не найдено подходящего приложения на устройстве).\n\n📄 **Содержимое '${fileInfo.path}':**\n\n```\n$preview\n```",
                            data = mapOf("path" to fileInfo.path, "content" to content, "action" to "read"),
                            responseType = ResponseType.RICH_TEXT
                        )
                    }
                }
                
                SkillResult.Error(
                    message = "Не удалось открыть файл '${fileInfo.path}'",
                    details = "Не найдено подходящее приложение для открытия этого типа файла"
                )
            }
        } catch (e: Exception) {
            println("❌ OpenFileSkill ошибка: ${e.message}")
            SkillResult.Error(
                message = "Ошибка при открытии файла",
                details = e.message ?: "Неизвестная ошибка"
            )
        }
    }

    /**
     * Открывает файл в соответствующем приложении через Android Intent.
     */
    private fun openFile(relativePath: String): Boolean {
        val file = fileManager.getFullFile(relativePath) ?: return false
        
        if (!file.exists()) return false

        try {
            val uri = getFileUri(file)
            val mimeType = getMimeType(file.name)
            
            val intent = AndroidIntent(AndroidIntent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(AndroidIntent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Проверяем, есть ли приложения для обработки Intent
            val packageManager = context.packageManager
            if (intent.resolveActivity(packageManager) != null) {
                context.startActivity(intent)
                return true
            }

            // Если не нашли приложение для конкретного MIME, пробуем открыть как текст
            val fallbackIntent = AndroidIntent(AndroidIntent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(AndroidIntent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (fallbackIntent.resolveActivity(packageManager) != null) {
                context.startActivity(fallbackIntent)
                return true
            }

            return false
        } catch (e: Exception) {
            println("❌ OpenFileSkill: ошибка открытия: ${e.message}")
            return false
        }
    }

    /**
     * Получает URI для файла.
     * Использует FileProvider, если доступен, иначе возвращает file:// URI.
     */
    private fun getFileUri(file: File): Uri {
        return try {
            // Пытаемся использовать FileProvider
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            // Fallback: file:// URI (может не работать на Android 11+)
            Uri.fromFile(file)
        }
    }

    /**
     * Определяет MIME-тип по расширению файла.
     */
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }
}
