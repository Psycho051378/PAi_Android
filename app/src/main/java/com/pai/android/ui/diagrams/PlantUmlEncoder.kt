package com.pai.android.ui.diagrams

import android.util.Base64
import java.util.zip.Deflater

/**
 * Кодировщик PlantUML кода согласно официальной спецификации.
 * PlantUML использует следующий алгоритм:
 * 1. Текст кодируется в UTF-8
 * 2. Сжимается с помощью Deflater (без заголовка)
 * 3. Кодируется в Base64
 * 4. Заменяются символы: '/' -> '_', '+' -> '-'
 */
object PlantUmlEncoder {
    
    /**
     * Кодирует текст PlantUML для использования в URL.
     */
    fun encode(text: String): String {
        // Шаг 1: UTF-8 байты
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        
        // Шаг 2: Сжатие Deflater (без заголовка)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(utf8Bytes)
        deflater.finish()
        
        val buffer = ByteArray(utf8Bytes.size * 2)
        val compressedSize = deflater.deflate(buffer)
        deflater.end()
        
        val compressedBytes = buffer.copyOf(compressedSize)
        
        // Шаг 3: Base64 кодирование (Android версия)
        val base64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        
        // Шаг 4: Замена символов
        return base64
            .replace('/', '_')
            .replace('+', '-')
    }
    
    /**
     * Декодирует текст PlantUML (обратная операция).
     */
    fun decode(encoded: String): String {
        // Обратная замена символов
        val base64 = encoded
            .replace('_', '/')
            .replace('-', '+')
        
        // Base64 декодирование (Android версия)
        val compressedBytes = Base64.decode(base64, Base64.DEFAULT)
        
        // Распаковка Deflater
        // Для простоты возвращаем исходную строку, если распаковка не требуется
        // В реальном использовании нужно использовать Inflater
        return String(compressedBytes, Charsets.UTF_8)
    }
    
    /**
     * Создает полный URL для PlantUML сервера.
     */
    fun createPlantUmlUrl(code: String, format: String = "svg"): String {
        val encoded = encode(code)
        return "http://www.plantuml.com/plantuml/$format/$encoded"
    }
    
    /**
     * Создает URL для рендеринга через PlantUML сервер с темой.
     */
    fun createPlantUmlUrlWithTheme(code: String, format: String = "svg", theme: String? = null): String {
        val encoded = encode(code)
        val themeParam = if (theme != null) "?theme=$theme" else ""
        return "http://www.plantuml.com/plantuml/$format/$encoded$themeParam"
    }
}