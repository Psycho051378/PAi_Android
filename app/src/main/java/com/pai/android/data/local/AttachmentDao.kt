package com.pai.android.data.local

import androidx.room.*
import com.pai.android.data.model.Attachment
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с вложениями к сообщениям.
 */
@Dao
interface AttachmentDao {
    
    /**
     * Вставляет новое вложение.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: Attachment): Long
    
    /**
     * Вставляет несколько вложений.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<Attachment>)
    
    /**
     * Обновляет существующее вложение.
     */
    @Update
    suspend fun update(attachment: Attachment)
    
    /**
     * Удаляет вложение по ID.
     */
    @Delete
    suspend fun delete(attachment: Attachment)
    
    /**
     * Удаляет вложение по ID.
     */
    @Query("DELETE FROM attachments WHERE id = :attachmentId")
    suspend fun deleteById(attachmentId: String)
    
    /**
     * Удаляет все вложения сообщения.
     */
    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)
    
    /**
     * Получает вложение по ID.
     */
    @Query("SELECT * FROM attachments WHERE id = :attachmentId")
    suspend fun getById(attachmentId: String): Attachment?
    
    /**
     * Получает все вложения сообщения.
     */
    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY createdAt")
    suspend fun getByMessageId(messageId: String): List<Attachment>
    
    /**
     * Получает Flow всех вложений сообщения.
     */
    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY createdAt")
    fun getByMessageIdFlow(messageId: String): Flow<List<Attachment>>
    
    /**
     * Получает все вложения сообщения (БЕЗ contentBase64 — для списков, чтобы избежать CursorWindow overflow).
     */
    @Query("SELECT id, messageId, type, fileName, mimeType, localPath, fileSize, metadata, createdAt FROM attachments WHERE messageId = :messageId ORDER BY createdAt")
    suspend fun getByMessageIdWithoutContent(messageId: String): List<Attachment>

    /**
     * Получает только contentBase64 для конкретного вложения.
     */
    @Query("SELECT contentBase64 FROM attachments WHERE id = :attachmentId")
    suspend fun getContentById(attachmentId: String): String?

    /**
     * Получает все вложения сообщения определённого типа.
     */
    @Query("SELECT * FROM attachments WHERE messageId = :messageId AND type = :type ORDER BY createdAt")
    suspend fun getByMessageIdAndType(messageId: String, type: String): List<Attachment>
    
    /**
     * Получает количество вложений сообщения.
     */
    @Query("SELECT COUNT(*) FROM attachments WHERE messageId = :messageId")
    suspend fun countByMessageId(messageId: String): Int
    
    /**
     * Получает все изображения сообщения.
     */
    @Query("SELECT * FROM attachments WHERE messageId = :messageId AND type = 'IMAGE' ORDER BY createdAt")
    suspend fun getImagesByMessageId(messageId: String): List<Attachment>
    
    /**
     * Получает все документы сообщения.
     */
    @Query("SELECT * FROM attachments WHERE messageId = :messageId AND type = 'DOCUMENT' ORDER BY createdAt")
    suspend fun getDocumentsByMessageId(messageId: String): List<Attachment>
    
    /**
     * Получает все текстовые файлы сообщения.
     */
    @Query("SELECT * FROM attachments WHERE messageId = :messageId AND type = 'TEXT' ORDER BY createdAt")
    suspend fun getTextFilesByMessageId(messageId: String): List<Attachment>
    
    /**
     * Получает все вложения, размер которых превышает указанный лимит.
     */
    @Query("SELECT * FROM attachments WHERE fileSize > :sizeLimit ORDER BY fileSize DESC")
    suspend fun getLargeAttachments(sizeLimit: Long): List<Attachment>
    
    /**
     * Получает общий размер всех вложений сообщения.
     */
    @Query("SELECT SUM(fileSize) FROM attachments WHERE messageId = :messageId")
    suspend fun getTotalSizeByMessageId(messageId: String): Long?
    
    /**
     * Проверяет, есть ли у сообщения вложения.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM attachments WHERE messageId = :messageId)")
    suspend fun hasAttachments(messageId: String): Boolean
    
    /**
     * Очищает все вложения сообщения.
     */
    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun clearMessageAttachments(messageId: String)
    
    /**
     * Получает все вложения, которые хранятся как base64 (не как локальные файлы).
     */
    @Query("SELECT * FROM attachments WHERE contentBase64 IS NOT NULL AND contentBase64 != ''")
    suspend fun getBase64Attachments(): List<Attachment>
    
    /**
     * Получает все вложения, которые хранятся как локальные файлы.
     */
    @Query("SELECT * FROM attachments WHERE localPath IS NOT NULL AND localPath != ''")
    suspend fun getLocalFileAttachments(): List<Attachment>
    
    /**
     * Обновляет содержимое вложения (base64).
     */
    @Query("UPDATE attachments SET contentBase64 = :contentBase64, fileSize = :fileSize WHERE id = :attachmentId")
    suspend fun updateContent(attachmentId: String, contentBase64: String, fileSize: Long)
    
    /**
     * Обновляет локальный путь к файлу.
     */
    @Query("UPDATE attachments SET localPath = :localPath, fileSize = :fileSize WHERE id = :attachmentId")
    suspend fun updateLocalPath(attachmentId: String, localPath: String, fileSize: Long)
    
    /**
     * Удаляет все вложения, которые ссылаются на несуществующие сообщения.
     */
    @Query("DELETE FROM attachments WHERE messageId NOT IN (SELECT id FROM messages)")
    suspend fun deleteOrphanedAttachments()
}