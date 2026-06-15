package com.pai.android.data.repository

import com.pai.android.data.local.AttachmentDao
import com.pai.android.data.model.Attachment
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Репозиторий для работы с вложениями к сообщениям.
 */
class AttachmentRepository @Inject constructor(
    private val attachmentDao: AttachmentDao
) {
    
    /**
     * Вставляет новое вложение.
     */
    suspend fun insert(attachment: Attachment): Long {
        return attachmentDao.insert(attachment)
    }
    
    /**
     * Вставляет несколько вложений.
     */
    suspend fun insertAll(attachments: List<Attachment>) {
        attachmentDao.insertAll(attachments)
    }
    
    /**
     * Обновляет существующее вложение.
     */
    suspend fun update(attachment: Attachment) {
        attachmentDao.update(attachment)
    }
    
    /**
     * Удаляет вложение по ID.
     */
    suspend fun delete(attachmentId: String) {
        attachmentDao.deleteById(attachmentId)
    }
    
    /**
     * Удаляет вложение.
     */
    suspend fun delete(attachment: Attachment) {
        attachmentDao.delete(attachment)
    }
    
    /**
     * Удаляет все вложения сообщения.
     */
    suspend fun deleteByMessageId(messageId: String) {
        attachmentDao.deleteByMessageId(messageId)
    }
    
    /**
     * Получает вложение по ID.
     */
    suspend fun getById(attachmentId: String): Attachment? {
        return attachmentDao.getById(attachmentId)
    }
    
    /**
     * Получает все вложения сообщения.
     */
    suspend fun getByMessageId(messageId: String): List<Attachment> {
        return attachmentDao.getByMessageId(messageId)
    }

    /**
     * Получает все вложения сообщения БЕЗ contentBase64 (для списков, чтобы избежать CursorWindow overflow).
     */
    suspend fun getByMessageIdWithoutContent(messageId: String): List<Attachment> {
        return attachmentDao.getByMessageIdWithoutContent(messageId)
    }

    /**
     * Получает только base64-содержимое вложения.
     */
    suspend fun getContentById(attachmentId: String): String? {
        return attachmentDao.getContentById(attachmentId)
    }
    
    /**
     * Получает Flow всех вложений сообщения.
     */
    fun getByMessageIdFlow(messageId: String): Flow<List<Attachment>> {
        return attachmentDao.getByMessageIdFlow(messageId)
    }
    
    /**
     * Получает все изображения сообщения.
     */
    suspend fun getImagesByMessageId(messageId: String): List<Attachment> {
        return attachmentDao.getImagesByMessageId(messageId)
    }
    
    /**
     * Получает все документы сообщения.
     */
    suspend fun getDocumentsByMessageId(messageId: String): List<Attachment> {
        return attachmentDao.getDocumentsByMessageId(messageId)
    }
    
    /**
     * Получает все текстовые файлы сообщения.
     */
    suspend fun getTextFilesByMessageId(messageId: String): List<Attachment> {
        return attachmentDao.getTextFilesByMessageId(messageId)
    }
    
    /**
     * Получает количество вложений сообщения.
     */
    suspend fun countByMessageId(messageId: String): Int {
        return attachmentDao.countByMessageId(messageId)
    }
    
    /**
     * Проверяет, есть ли у сообщения вложения.
     */
    suspend fun hasAttachments(messageId: String): Boolean {
        return attachmentDao.hasAttachments(messageId)
    }
    
    /**
     * Очищает все вложения сообщения.
     */
    suspend fun clearMessageAttachments(messageId: String) {
        attachmentDao.clearMessageAttachments(messageId)
    }
    
    /**
     * Получает общий размер всех вложений сообщения.
     */
    suspend fun getTotalSizeByMessageId(messageId: String): Long? {
        return attachmentDao.getTotalSizeByMessageId(messageId)
    }
    
    /**
     * Удаляет все вложения, которые ссылаются на несуществующие сообщения.
     */
    suspend fun deleteOrphanedAttachments() {
        attachmentDao.deleteOrphanedAttachments()
    }
    
    /**
     * Сохраняет вложения для сообщения.
     * Создаёт новые вложения с указанным messageId.
     */
    suspend fun saveAttachmentsForMessage(messageId: String, attachments: List<Attachment>): List<Attachment> {
        val attachmentsWithMessageId = attachments.map { it.copy(messageId = messageId) }
        attachmentDao.insertAll(attachmentsWithMessageId)
        return attachmentsWithMessageId
    }
    
    /**
     * Копирует временные вложения (без messageId) и связывает их с сообщением.
     */
    suspend fun attachTemporaryAttachments(messageId: String, temporaryAttachments: List<Attachment>): List<Attachment> {
        // Фильтруем вложения, которые ещё не привязаны к сообщению
        val attachmentsToSave = temporaryAttachments.filter { it.messageId.isEmpty() }
            .map { it.copy(messageId = messageId) }
        
        if (attachmentsToSave.isNotEmpty()) {
            attachmentDao.insertAll(attachmentsToSave)
        }
        
        return attachmentsToSave
    }
}