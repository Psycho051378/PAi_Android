package com.pai.android.ui.diagrams

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * API сервисы для рендеринга диаграмм.
 */
interface DiagramApiService {
    
    /**
     * Mermaid.ink API - рендеринг Mermaid диаграмм в SVG.
     * Формат: https://mermaid.ink/svg/{base64_encoded_code}
     */
    @GET
    fun renderMermaidSvg(@Url url: String): Call<ResponseBody>
    
    /**
     * Mermaid.ink API - рендеринг Mermaid диаграмм в PNG.
     * Формат: https://mermaid.ink/img/{base64_encoded_code}
     */
    @GET
    fun renderMermaidPng(@Url url: String): Call<ResponseBody>
    
    /**
     * PlantUML API - рендеринг PlantUML диаграмм.
     * Формат: http://www.plantuml.com/plantuml/svg/{encoded_code}
     */
    @GET
    fun renderPlantUmlSvg(@Url url: String): Call<ResponseBody>
    
    /**
     * PlantUML API - рендеринг PlantUML диаграмм в PNG.
     * Формат: http://www.plantuml.com/plantuml/png/{encoded_code}
     */
    @GET
    fun renderPlantUmlPng(@Url url: String): Call<ResponseBody>
    
    companion object {
        private const val MERMAID_BASE_URL = "https://mermaid.ink"
        private const val PLANTUML_BASE_URL = "http://www.plantuml.com/plantuml"
        
        /**
         * Создает URL для рендеринга Mermaid диаграммы.
         * Для SVG: https://mermaid.ink/svg/{base64}
         * Для PNG/JPG/WebP: https://mermaid.ink/img/{base64}?type={format}
         * PDF не поддерживается (убрано по запросу пользователя)
         */
        fun createMermaidUrl(code: String, format: String = "svg"): String {
            val base64 = android.util.Base64.encodeToString(
                code.toByteArray(Charsets.UTF_8),
                android.util.Base64.DEFAULT
            ).trim().replace("\n", "")
            
            return when (format.lowercase()) {
                "png", "jpg", "jpeg", "webp" -> {
                    "$MERMAID_BASE_URL/img/$base64?type=${format.lowercase()}"
                }
                else -> {
                    "$MERMAID_BASE_URL/$format/$base64"
                }
            }
        }
        
        /**
         * Кодирует PlantUML код в формат, используемый PlantUML API.
         * Используется алгоритм кодирования PlantUML.
         */
        fun encodePlantUmlCode(code: String): String {
            return PlantUmlEncoder.encode(code)
        }
        
        /**
         * Создает URL для рендеринга PlantUML диаграммы.
         */
        fun createPlantUmlUrl(code: String, format: String = "svg"): String {
            return PlantUmlEncoder.createPlantUmlUrl(code, format)
        }
    }
}