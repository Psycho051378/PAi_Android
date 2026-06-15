package com.pai.android.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Тип вложения.
 */
enum class AttachmentType {
    IMAGE,       // Изображения (PNG, JPEG, GIF, WEBP)
    DOCUMENT,    // Документы (PDF, Word, Excel, PowerPoint)
    TEXT,        // Текстовые файлы (TXT, RTF, MD)
    AUDIO,       // Аудио файлы (MP3, WAV, OGG)
    OTHER        // Другие типы файлов
}

/**
 * Вложение к сообщению (изображение, документ и т.д.).
 * 
 * Аналогично полю `image_path` в таблице `messages` веб-версии,
 * но с расширенной поддержкой различных типов файлов.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = Message::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["messageId"])]
)
data class Attachment(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    /** ID сообщения, к которому прикреплено вложение */
    val messageId: String,
    
    /** Тип вложения */
    val type: AttachmentType,
    
    /** Имя файла */
    val fileName: String,
    
    /** MIME-тип файла (например, image/png, application/pdf) */
    val mimeType: String,
    
    /** Содержимое файла в base64 (для небольших файлов) */
    val contentBase64: String? = null,
    
    /** Путь к файлу в локальном хранилище (для больших файлов) */
    val localPath: String? = null,
    
    /** Размер файла в байтах */
    val fileSize: Long = 0,
    
    /** Метаданные (например, размеры изображения, количество страниц в документе) */
    val metadata: Map<String, String> = emptyMap(),
    
    /** Дата создания */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Создаёт вложение для изображения из base64.
         */
        fun createImageAttachment(
            messageId: String,
            fileName: String,
            mimeType: String = "image/jpeg",
            contentBase64: String,
            width: Int? = null,
            height: Int? = null
        ): Attachment {
            val metadata = mutableMapOf<String, String>()
            width?.let { metadata["width"] = it.toString() }
            height?.let { metadata["height"] = it.toString() }
            
            return Attachment(
                messageId = messageId,
                type = AttachmentType.IMAGE,
                fileName = fileName,
                mimeType = mimeType,
                contentBase64 = contentBase64,
                metadata = metadata,
                fileSize = estimateBase64Size(contentBase64)
            )
        }
        
        /**
         * Создаёт вложение для документа с текстовым содержимым.
         */
        fun createDocumentAttachment(
            messageId: String,
            fileName: String,
            mimeType: String,
            contentText: String
        ): Attachment {
            return Attachment(
                messageId = messageId,
                type = AttachmentType.DOCUMENT,
                fileName = fileName,
                mimeType = mimeType,
                contentBase64 = contentText.takeIf { it.isNotBlank() },
                metadata = mapOf("pageCount" to "1"),
                fileSize = contentText.length.toLong()
            )
        }
        
        /**
         * Создаёт вложение для файла с локальным путём.
         */
        fun createLocalFileAttachment(
            messageId: String,
            fileName: String,
            mimeType: String,
            localPath: String,
            fileSize: Long,
            type: AttachmentType = AttachmentType.OTHER
        ): Attachment {
            return Attachment(
                messageId = messageId,
                type = type,
                fileName = fileName,
                mimeType = mimeType,
                localPath = localPath,
                fileSize = fileSize
            )
        }
        
        /**
         * Оценивает размер файла в байтах по base64 строке.
         */
        private fun estimateBase64Size(base64: String): Long {
            // Base64 использует 4 символа для 3 байтов
            val base64Length = base64.length
            val paddingCount = base64.takeLast(2).count { it == '=' }
            return (base64Length * 3 / 4 - paddingCount).toLong()
        }
        
        /**
         * Определяет тип вложения по MIME-типу или расширению файла.
         */
        fun determineType(mimeType: String?, fileName: String?): AttachmentType {
            val mime = mimeType?.lowercase() ?: ""
            val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
            
            return when {
                mime.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> 
                    AttachmentType.IMAGE
                
                mime.startsWith("audio/") || ext in listOf("mp3", "wav", "ogg", "m4a", "flac") -> 
                    AttachmentType.AUDIO
                
                mime.startsWith("text/") || ext in listOf("txt", "md", "rtf", "html", "xml", "json") -> 
                    AttachmentType.TEXT
                
                mime.contains("pdf") || ext == "pdf" -> 
                    AttachmentType.DOCUMENT
                
                mime.contains("document") || mime.contains("word") || mime.contains("excel") || 
                mime.contains("powerpoint") || ext in listOf("doc", "docx", "xls", "xlsx", "ppt", "pptx") -> 
                    AttachmentType.DOCUMENT
                
                else -> AttachmentType.OTHER
            }
        }
    }
    
    /** Проверяет, является ли вложение изображением */
    val isImage: Boolean get() = type == AttachmentType.IMAGE
    
    /** Проверяет, является ли вложение документом */
    val isDocument: Boolean get() = type == AttachmentType.DOCUMENT
    
    /** Проверяет, является ли вложение текстовым файлом */
    val isText: Boolean get() = type == AttachmentType.TEXT
    
    /** Проверяет, является ли вложение аудио файлом */
    val isAudio: Boolean get() = type == AttachmentType.AUDIO
    
    /** Получает содержимое как текст (если это текстовый файл) */
    fun getContentAsText(): String? {
        return if (type == AttachmentType.TEXT || type == AttachmentType.DOCUMENT) {
            contentBase64
        } else {
            null
        }
    }
    
    /** Получает размер изображения (ширина x высота) */
    fun getImageDimensions(): Pair<Int, Int>? {
        if (type != AttachmentType.IMAGE) return null
        
        val width = metadata["width"]?.toIntOrNull() ?: 0
        val height = metadata["height"]?.toIntOrNull() ?: 0
        
        return if (width > 0 && height > 0) width to height else null
    }
    
    /** Получает количество страниц в документе */
    fun getPageCount(): Int {
        return if (type == AttachmentType.DOCUMENT) {
            metadata["pageCount"]?.toIntOrNull() ?: 1
        } else {
            1
        }
    }
    
    /** Форматирует размер файла в читаемом виде (KB, MB) */
    fun formattedSize(): String {
        return when {
            fileSize <= 0 -> "0 Б"
            fileSize < 1024 -> "$fileSize Б"
            fileSize < 1024 * 1024 -> "${fileSize / 1024} КБ"
            fileSize < 1024 * 1024 * 1024 -> "${fileSize / (1024 * 1024)} МБ"
            else -> "${fileSize / (1024 * 1024 * 1024)} ГБ"
        }
    }
}