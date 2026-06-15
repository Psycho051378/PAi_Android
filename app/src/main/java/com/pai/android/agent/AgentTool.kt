package com.pai.android.agent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow

/**
 * Интерфейс инструмента агента.
 * Каждый инструмент представляет собой конкретное действие, которое может выполнять агент.
 */
interface AgentTool {
    /**
     * Уникальное имя инструмента (латинскими буквами, нижний регистр, подчёркивания).
     * Пример: "create_folder", "read_file", "analyze_documents"
     */
    val name: String
    
    /**
     * Описание инструмента на естественном языке.
     * Используется AI для понимания, когда применять инструмент.
     */
    val description: String
    
    /**
     * Параметры инструмента в формате JSON Schema.
     * Определяет, какие параметры нужны для вызова инструмента.
     */
    val parametersSchema: String
    
    /**
     * Требуется ли подтверждение пользователя перед выполнением.
     * Для опасных операций (удаление, отправка вовне) должно быть true.
     */
    val requiresConfirmation: Boolean
    
    /**
     * Выполняет инструмент с заданными параметрами.
     * @param params Параметры в виде Map<String, Any>
     * @return Результат выполнения (текстовое описание + любые данные)
     */
    suspend fun execute(params: Map<String, Any>): ToolResult

    /**
     * Возвращает нативное определение инструмента (JSON Schema) для DeepSeek/OpenAI tool calling API.
     */
    fun toNativeToolDefinition(): Map<String, Any>? {
        return try {
            val gson = Gson()
            val paramsMap: Map<String, Any>? = if (parametersSchema.isNotBlank()) {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                gson.fromJson(parametersSchema, type)
            } else {
                null
            }
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to name,
                    "description" to description,
                    "parameters" to (paramsMap ?: mapOf(
                        "type" to "object",
                        "properties" to emptyMap<String, Any>()
                    ))
                )
            )
        } catch (e: Exception) {
            println("⚠️ Failed to parse schema for '$name': ${e.message}")
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to name,
                    "description" to description
                )
            )
        }
    }
}

/**
 * Результат выполнения инструмента.
 */
sealed class ToolResult {
    /**
     * Успешное выполнение.
     * @param output Текстовое описание результата (для AI)
     * @param data Любые дополнительные данные (опционально)
     */
    data class Success(
        val output: String,
        val data: Map<String, Any>? = null
    ) : ToolResult()
    
    /**
     * Ошибка выполнения.
     * @param error Описание ошибки
     * @param recoverable Можно ли повторить попытку
     */
    data class Error(
        val error: String,
        val recoverable: Boolean = true
    ) : ToolResult()
    
    /**
     * Инструмент требует подтверждения пользователя.
     * @param question Вопрос для пользователя
     * @param confirmAction Действие для подтверждения (параметры уже включены)
     */
    data class ConfirmationRequired(
        val question: String,
        val confirmAction: String // JSON с действием для подтверждения
    ) : ToolResult()
}

/**
 * Базовая реализация инструмента с валидацией параметров.
 */
abstract class BaseAgentTool : AgentTool {
    /**
     * Валидирует параметры перед выполнением.
     */
    protected open fun validateParams(params: Map<String, Any>): ValidationResult {
        // Базовая реализация всегда проходит валидацию
        // Наследники могут переопределить для конкретной схемы
        return ValidationResult.Valid
    }
    
    /**
     * Извлекает параметр с приведением типа.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <T> getParam(params: Map<String, Any>, key: String, defaultValue: T? = null): T {
        return params[key] as? T ?: defaultValue ?: 
            throw IllegalArgumentException("Параметр '$key' не найден или имеет неверный тип")
    }
    
    /**
     * Извлекает строковый параметр.
     */
    protected fun getStringParam(params: Map<String, Any>, key: String): String {
        return getParam<String>(params, key)
    }
    
    /**
     * Извлекает булев параметр.
     */
    protected fun getBooleanParam(params: Map<String, Any>, key: String, defaultValue: Boolean = false): Boolean {
        return params[key] as? Boolean ?: defaultValue
    }
}

/**
 * Результат валидации параметров.
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}