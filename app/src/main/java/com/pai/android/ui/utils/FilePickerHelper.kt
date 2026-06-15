package com.pai.android.ui.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pai.android.data.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Вспомогательный класс для выбора файлов через Intent.
 */
object FilePickerHelper {
    
    /**
     * Типы файлов для выбора.
     */
    sealed class FileType(val mimeType: String) {
        object AllFiles : FileType("*/*")
        object Images : FileType("image/*")
        object Pdf : FileType("application/pdf")
        object Documents : FileType("application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        object Spreadsheets : FileType("application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        object Text : FileType("text/plain")
        object Audio : FileType("audio/*")
        
        companion object {
            /**
             * Получает MIME-тип по расширению файла.
             */
            fun getMimeTypeFromExtension(extension: String): String {
                return when (extension.lowercase()) {
                    "jpg", "jpeg", "png", "gif", "bmp", "webp" -> Images.mimeType
                    "pdf" -> Pdf.mimeType
                    "doc", "docx" -> Documents.mimeType
                    "xls", "xlsx" -> Spreadsheets.mimeType
                    "txt", "md", "json", "xml" -> Text.mimeType
                    "mp3", "wav", "ogg", "m4a" -> Audio.mimeType
                    else -> AllFiles.mimeType
                }
            }
            
            /**
             * Получает тип файла по MIME-типу.
             */
            fun getFileTypeFromMime(mimeType: String?): com.pai.android.data.model.AttachmentType {
                return when {
                    mimeType == null -> com.pai.android.data.model.AttachmentType.OTHER
                    mimeType.startsWith("image/") -> com.pai.android.data.model.AttachmentType.IMAGE
                    mimeType.startsWith("application/pdf") -> com.pai.android.data.model.AttachmentType.DOCUMENT
                    mimeType.contains("word") || mimeType.contains("document") -> com.pai.android.data.model.AttachmentType.DOCUMENT
                    mimeType.contains("excel") || mimeType.contains("spreadsheet") -> com.pai.android.data.model.AttachmentType.DOCUMENT
                    mimeType.startsWith("text/") -> com.pai.android.data.model.AttachmentType.TEXT
                    mimeType.startsWith("audio/") -> com.pai.android.data.model.AttachmentType.AUDIO
                    else -> com.pai.android.data.model.AttachmentType.OTHER
                }
            }
        }
    }
    
    /**
     * Информация о выбранном файле.
     */
    data class FileInfo(
        val uri: Uri,
        val fileName: String?,
        val fileSize: Long?,
        val mimeType: String?,
        val extension: String?
    )
    
    /**
     * Получает информацию о файле по URI.
     */
    suspend fun getFileInfo(context: Context, uri: Uri): FileInfo = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        
        // Получаем имя файла и размер
        val cursor = contentResolver.query(uri, null, null, null, null)
        var fileName: String? = null
        var fileSize: Long? = null
        
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                
                if (nameIndex != -1) fileName = it.getString(nameIndex)
                if (sizeIndex != -1) fileSize = it.getLong(sizeIndex)
            }
        }
        
        // Получаем MIME-тип
        val mimeType = contentResolver.getType(uri)
        
        // Определяем расширение файла
        val extension = fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        
        FileInfo(uri, fileName, fileSize, mimeType, extension)
    }
    
    /**
     * Читает содержимое текстового файла.
     */
    suspend fun readTextFile(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { reader ->
                reader.readText()
            }
        } ?: throw IllegalArgumentException("Не удалось открыть файл: $uri")
    }
    
    /**
     * Преобразует изображение в base64.
     */
    suspend fun imageToBase64(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val bytes = inputStream.readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } ?: throw IllegalArgumentException("Не удалось открыть изображение: $uri")
    }
    
    /**
     * Сохраняет файл во временное хранилище приложения.
     */
    suspend fun saveToTempFile(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val fileInfo = getFileInfo(context, uri)
        val fileName = fileInfo.fileName ?: "file_${System.currentTimeMillis()}"
        val tempFile = File(context.cacheDir, fileName)
        
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        
        tempFile
    }
    
    /**
     * Создаёт объект Attachment из выбранного файла.
     */
    suspend fun createAttachmentFromUri(
        context: Context,
        uri: Uri,
        messageId: String
    ): Attachment = withContext(Dispatchers.IO) {
        val fileInfo = getFileInfo(context, uri)
        val fileName = fileInfo.fileName ?: "file_${System.currentTimeMillis()}"
        val mimeType = fileInfo.mimeType ?: "application/octet-stream"
        val fileSize = fileInfo.fileSize ?: 0L
        val fileType = FileType.getFileTypeFromMime(mimeType)
        
        return@withContext when (fileType) {
            com.pai.android.data.model.AttachmentType.IMAGE -> {
                // Для изображений сохраняем base64
                val base64 = imageToBase64(context, uri)
                Attachment.createImageAttachment(
                    messageId = messageId,
                    fileName = fileName,
                    mimeType = mimeType,
                    contentBase64 = base64
                )
            }
            com.pai.android.data.model.AttachmentType.TEXT -> {
                // Для текстовых файлов сохраняем локально И читаем содержимое
                val tempFile = saveToTempFile(context, uri)
                val textContent = tempFile.readText()
                Attachment(
                    messageId = messageId,
                    type = com.pai.android.data.model.AttachmentType.TEXT,
                    fileName = fileName,
                    mimeType = mimeType,
                    contentBase64 = textContent,
                    localPath = tempFile.absolutePath,
                    fileSize = tempFile.length()
                )
            }
            com.pai.android.data.model.AttachmentType.DOCUMENT,
            com.pai.android.data.model.AttachmentType.AUDIO,
            com.pai.android.data.model.AttachmentType.OTHER -> {
                // Для документов и других файлов сохраняем локальный путь
                val tempFile = saveToTempFile(context, uri)
                Attachment.createLocalFileAttachment(
                    messageId = messageId,
                    fileName = fileName,
                    mimeType = mimeType,
                    localPath = tempFile.absolutePath,
                    fileSize = fileSize,
                    type = fileType
                )
            }
            else -> throw IllegalArgumentException("Неподдерживаемый тип файла: $fileType")
        }
    }
}

/**
 * Composable функция для создания ланчера выбора файлов.
 */
@Composable
fun rememberFilePickerLauncher(
    onFileSelected: (Uri) -> Unit,
    fileType: FilePickerHelper.FileType = FilePickerHelper.FileType.AllFiles
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { onFileSelected(it) }
        }
    )
    return remember {
        { launcher.launch(fileType.mimeType) }
    }
}

/**
 * Composable функция для создания ланчера выбора нескольких файлов.
 */
@Composable
fun rememberMultipleFilePickerLauncher(
    onFilesSelected: (List<Uri>) -> Unit,
    fileType: FilePickerHelper.FileType = FilePickerHelper.FileType.AllFiles
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                onFilesSelected(uris)
            }
        }
    )
    return remember {
        { launcher.launch(fileType.mimeType) }
    }
}