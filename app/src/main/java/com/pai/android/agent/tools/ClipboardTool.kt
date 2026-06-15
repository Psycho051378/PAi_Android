package com.pai.android.agent.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.pai.android.agent.AgentTool
import com.pai.android.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Инструмент для работы с буфером обмена Android.
 *
 * Позволяет:
 * - Читать текущее содержимое буфера обмена
 * - Записывать текст в буфер обмена
 * - Проверять статус буфера (пуст/не пуст, тип данных)
 */
@Singleton
class ClipboardTool @Inject constructor(
    @ApplicationContext private val context: Context
) : AgentTool {

    override val name: String = "clipboard"
    override val description: String = "Read/write device clipboard content. Use action=read when user asks about clipboard/buffer/pasted text content. Provides the actual text currently in clipboard."
    override val parametersSchema: String = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["read", "write", "status"],
                "description": "read = get clipboard content (use when user asks about clipboard/buffer/pasted text), write = put text to clipboard, status = check clipboard state"
            },
            "text": {
                "type": "string",
                "description": "Text to write to clipboard (required for action=write)"
            }
        },
        "required": ["action"]
    }"""
    override val requiresConfirmation: Boolean = false

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString() ?: "read"

        return when (action) {
            "read" -> readClipboard()
            "write" -> writeClipboard(params["text"]?.toString())
            "status" -> clipboardStatus()
            else -> ToolResult.Error(error = "Unknown clipboard action: $action")
        }
    }

    /**
     * Читает текущее содержимое буфера обмена.
     */
    private fun readClipboard(): ToolResult {
        return try {
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                return ToolResult.Success(
                    output = "📋 Буфер обмена пуст.",
                    data = mapOf("has_content" to "false", "content" to "")
                )
            }

            val item = clip.getItemAt(0)
            val text = item.text?.toString() ?: item.uri?.toString() ?: item.htmlText ?: ""
            val label = clip.description.label ?: ""
            val mimeType = clip.description.getMimeType(0) ?: "text/plain"

            val contentPreview = if (text.length > 500) text.take(500) + "…" else text

            val sb = StringBuilder()
            sb.appendLine("📋 **Буфер обмена:**")
            if (label.isNotBlank()) sb.appendLine("   Метка: $label")
            sb.appendLine("   Тип: $mimeType")
            sb.appendLine("   Содержимое (${text.length} симв.):")
            sb.appendLine("   ```")
            sb.appendLine("   $contentPreview")
            sb.appendLine("   ```")

            ToolResult.Success(
                output = sb.toString().trimEnd(),
                data = mapOf(
                    "has_content" to "true",
                    "mime_type" to mimeType,
                    "length" to text.length.toString(),
                    "label" to label
                )
            )
        } catch (e: Exception) {
            ToolResult.Error(error = "Не удалось прочитать буфер обмена: ${e.message}")
        }
    }

    /**
     * Записывает текст в буфер обмена.
     */
    private fun writeClipboard(text: String?): ToolResult {
        if (text.isNullOrBlank()) {
            return ToolResult.Error(error = "Не указан текст для записи в буфер обмена")
        }

        return try {
            val clip = ClipData.newPlainText("Pai_Android", text)
            clipboard.setPrimaryClip(clip)

            val preview = if (text.length > 100) text.take(100) + "…" else text
            ToolResult.Success(
                output = "📋 Текст скопирован в буфер обмена (${text.length} симв.):\n```\n$preview\n```",
                data = mapOf(
                    "written" to "true",
                    "length" to text.length.toString()
                )
            )
        } catch (e: Exception) {
            ToolResult.Error(error = "Не удалось записать в буфер обмена: ${e.message}")
        }
    }

    /**
     * Проверяет состояние буфера обмена.
     */
    private fun clipboardStatus(): ToolResult {
        return try {
            val clip = clipboard.primaryClip
            val hasContent = clip != null && clip.itemCount > 0
            val text = if (hasContent) clip!!.getItemAt(0).text?.toString() ?: "" else ""

            val sb = StringBuilder()
            sb.appendLine("📋 **Статус буфера обмена:**")
            sb.appendLine("   Содержимое: ${if (hasContent) "✅ есть" else "❌ пуст"}")
            if (hasContent) {
                sb.appendLine("   Тип: ${clip!!.description.getMimeType(0) ?: "text/plain"}")
                sb.appendLine("   Символов: ${text.length}")
            }

            ToolResult.Success(
                output = sb.toString().trimEnd(),
                data = mapOf(
                    "has_content" to hasContent.toString(),
                    "length" to text.length.toString()
                )
            )
        } catch (e: Exception) {
            ToolResult.Error(error = "Не удалось проверить буфер обмена: ${e.message}")
        }
    }
}
