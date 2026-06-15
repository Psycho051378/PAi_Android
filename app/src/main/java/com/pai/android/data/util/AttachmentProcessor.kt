package com.pai.android.data.util

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.pai.android.data.model.Attachment
import com.pai.android.data.model.AiProvider
import com.pai.android.data.network.model.ChatMessage

/**
 * Утилита для обработки вложений и преобразования их в формат для AI API.
 */
object AttachmentProcessor {
    
    /**
     * Обрабатывает вложения и создаёт сообщение для AI API.
     * 
     * @param text Текст сообщения пользователя
     * @param attachments Список вложений
     * @param provider AI провайдер для определения формата
     * @return ChatMessage с обработанным контентом и изображениями
     */
    fun processAttachmentsForMessage(
        text: String,
        attachments: List<Attachment>,
        provider: AiProvider
    ): ChatMessage {
        // Если вложений нет, возвращаем простое текстовое сообщение
        if (attachments.isEmpty()) {
            return ChatMessage.createTextMessage(
                role = com.pai.android.data.model.MessageRole.USER,
                text = text
            )
        }
        
        // Определяем, поддерживает ли провайдер мультимодальные запросы
        val supportsImages = provider.supportsImages()
        
        // Фильтруем изображения для провайдеров с поддержкой vision
        val imageAttachments = attachments.filter { it.isImage && (it.contentBase64 != null || it.localPath != null) }
        val otherAttachments = attachments.filter { !it.isImage || it.contentBase64 == null }
        
        // Обрабатываем текстовые вложения (извлекаем текст)
        val extractedText = processTextAttachments(otherAttachments)
        
        // Создаём финальный текст (для провайдеров без поддержки vision или когда нет изображений)
        val finalText = buildString {
            append(text)
            if (extractedText.isNotEmpty()) {
                if (text.isNotEmpty()) append("\n\n")
                append("Прикреплённые файлы:\n")
                append(extractedText)
            }
        }.trim()
        
        // Если провайдер не поддерживает изображения или нет изображений
        if (!supportsImages || imageAttachments.isEmpty()) {
            // Добавляем информацию о изображениях для провайдеров без поддержки vision
            val textWithImageInfo = if (!supportsImages && imageAttachments.isNotEmpty()) {
                finalText + "\n\nПрикреплены изображения: " +
                imageAttachments.joinToString(", ") { it.fileName } +
                " (AI не видит содержимое изображений)"
            } else {
                finalText
            }
            
            return ChatMessage.createTextMessage(
                role = com.pai.android.data.model.MessageRole.USER,
                text = textWithImageInfo
            )
        }
        
        // Для провайдеров с поддержкой vision и с изображениями создаём мультимодальное сообщение
        // Используем формат OpenAI для OpenRouter и OpenAI, для DeepSeek тоже пробуем этот формат
        val imageBase64List = imageAttachments.mapNotNull { att ->
            if (att.contentBase64 != null) {
                att.contentBase64
            } else if (att.localPath != null) {
                try {
                    val file = java.io.File(att.localPath)
                    if (file.isFile()) {
                        val bytes = file.readBytes()
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    } else null
                } catch (e: Exception) {
                    println("AttachmentProcessor: read image file error: ${e.message}")
                    null
                }
            } else null
        }
        
        // Определяем MIME-тип первого изображения (предполагаем, что все изображения одного типа)
        val mimeType = imageAttachments.firstOrNull()?.mimeType ?: "image/jpeg"
        
        return ChatMessage.createMultimodalMessageForOpenAI(
            role = com.pai.android.data.model.MessageRole.USER,
            text = finalText,
            imageBase64List = imageBase64List,
            mimeType = mimeType
        )
    }
    
    /**
     * Обрабатывает текстовые вложения и извлекает текст.
     */
    private fun processTextAttachments(attachments: List<Attachment>): String {
        val result = StringBuilder()
        
        attachments.forEach { attachment ->
            when {
                attachment.isText || attachment.isDocument -> {
                    // Для текстовых файлов и документов пытаемся извлечь текст
                    val content = attachment.getContentAsText()
                    if (!content.isNullOrBlank()) {
                        result.append("\n[${attachment.fileName}]:\n")
                        result.append(content.take(2000)) // Ограничиваем длину
                        if (content.length > 2000) result.append("...")
                    } else {
                        result.append("\n[${attachment.fileName}]: файл прикреплён (текст недоступен)")
                    }
                }
                
                attachment.isAudio -> {
                    result.append("\n[${attachment.fileName}]: аудио файл (транскрипция не поддерживается)")
                }
                
                else -> {
                    result.append("\n[${attachment.fileName}]: файл прикреплён (тип: ${attachment.type})")
                }
            }
        }
        
        return result.toString().trim()
    }
    
    /**
     * Проверяет, поддерживает ли провайдер изображения.
     */
    private fun AiProvider.supportsImages(): Boolean {
        return when (this) {
            AiProvider.OPENAI -> true // GPT-4V
            AiProvider.DEEPSEEK -> true // DeepSeek-VL
            AiProvider.OPENROUTER -> true // Может проксировать к провайдерам с vision
            AiProvider.OLLAMA -> false // Зависит от модели, по умолчанию нет
            AiProvider.CUSTOM -> true // Кастомный endpoint — пользователь сам отвечает за совместимость
        }
    }
    
    /**
     * Определяет формат для отправки изображений для конкретного провайдера.
     */
    fun getImageFormat(provider: AiProvider): ImageFormat {
        return when (provider) {
            AiProvider.OPENAI -> ImageFormat.BASE64 // OpenAI GPT-4V принимает base64
            AiProvider.DEEPSEEK -> ImageFormat.BASE64 // DeepSeek-VL принимает base64
            AiProvider.OPENROUTER -> ImageFormat.BASE64 // OpenRouter принимает base64
            AiProvider.OLLAMA -> ImageFormat.NONE // Ollama не поддерживает
            AiProvider.CUSTOM -> ImageFormat.BASE64 // Кастомный endpoint — скорее всего OpenAI-совместимый
        }
    }
    
    /**
     * Формат изображений для AI API.
     */
    enum class ImageFormat {
        BASE64,    // Изображение отправляется как base64 строка
        URL,       // Изображение отправляется как URL
        MULTIPART, // Изображение отправляется как multipart/form-data
        NONE       // Изображения не поддерживаются
    }
}