package com.pai.android.agent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pai.android.data.network.model.NativeFunctionDefinition
import com.pai.android.data.network.model.NativeToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реестр инструментов агента.
 * Регистрирует все доступные инструменты и позволяет их находить.
 */
@Singleton
class ToolRegistry constructor() {
    private val tools = mutableMapOf<String, AgentTool>()
    
    /**
     * Регистрирует инструмент.
     */
    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }
    
    /**
     * Регистрирует несколько инструментов.
     */
    fun registerAll(vararg tools: AgentTool) {
        tools.forEach { register(it) }
    }
    
    /**
     * Находит инструмент по имени.
     */
    fun getTool(name: String): AgentTool? {
        return tools[name]
    }
    
    /**
     * Возвращает все зарегистрированные инструменты.
     */
    fun getAllTools(): List<AgentTool> {
        return tools.values.toList()
    }
    
    /**
     * Получает описания всех инструментов для AI промпта.
     * Формат: "name: description (параметры: schema)"
     */
    fun getToolsDescription(): String {
        return tools.values.joinToString("\n") { tool ->
            "- ${tool.name}: ${tool.description}\n  Параметры: ${tool.parametersSchema}"
        }
    }
    
    /**
     * Находит подходящие инструменты для задачи.
     * Использует семантический поиск по описанию.
     */
    suspend fun findToolsForTask(task: String): List<AgentTool> {
        // Пока простой поиск по ключевым словам
        // В будущем можно добавить эмбеддинги
        val keywords = task.lowercase().split(" ", ",", ".", "!", "?")
        
        return tools.values.filter { tool ->
            val toolText = "${tool.name} ${tool.description}".lowercase()
            keywords.any { keyword ->
                keyword.length > 3 && toolText.contains(keyword)
            }
        }
    }

    /**
     * Возвращает нативные определения инструментов для DeepSeek/OpenAI tool calling API.
     * @param filterNames если не null, фильтрует только указанные инструменты
     */
    fun getNativeToolDefinitions(filterNames: Set<String>? = null): List<NativeToolDefinition> {
        val gson = Gson()
        val filtered = if (filterNames != null) {
            tools.values.filter { it.name in filterNames }
        } else {
            tools.values.toList()
        }
        return filtered.mapNotNull { tool ->
            try {
                if (tool.name == "clipboard") {
                    println("?? getNativeToolDefinitions: clipboard schema=" + tool.parametersSchema)
                }
                val paramsMap: Map<String, Any>? = if (tool.parametersSchema.isNotBlank()) {
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    gson.fromJson(tool.parametersSchema, type)
                } else {
                    null
                }
                if (tool.name == "clipboard") {
                    println("?? getNativeToolDefinitions: clipboard paramsMap=" + paramsMap?.toString()?.take(500))
                }
                NativeToolDefinition(
                    function = NativeFunctionDefinition(
                        name = tool.name,
                        description = tool.description,
                        parameters = paramsMap ?: mapOf(
                            "type" to "object",
                            "properties" to emptyMap<String, Any>()
                        )
                    )
                )
            } catch (e: Exception) {
                println("⚠️ Failed to build native def for '${tool.name}': ${e.message}")
                // Fallback: минимальное определение без параметров
                NativeToolDefinition(
                    function = NativeFunctionDefinition(
                        name = tool.name,
                        description = tool.description
                    )
                )
            }
        }
    }
}