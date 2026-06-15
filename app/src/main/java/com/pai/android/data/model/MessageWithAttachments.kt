package com.pai.android.data.model

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Сообщение с его вложениями. Используется для загрузки из Room через @Relation.
 */
data class MessageWithAttachments(
    @Embedded
    val message: Message,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId"
    )
    val attachments: List<Attachment> = emptyList()
)
