package com.pai.android.ui.diagrams

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Рендерер диаграмм через внешние API.
 * Поддерживает Mermaid и PlantUML.
 */
class DiagramRenderer(private val context: Context) {
    
    private val tag = "DiagramRenderer"
    
    // Кэш для загруженных изображений (код диаграммы -> путь к файлу)
    private val imageCache = mutableMapOf<String, String>()
    
    // Общий OkHttpClient с настройками
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", "Pai-Android-App/1.0")
                    .header("Accept", "image/*, */*")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor { chain ->
                var response = chain.proceed(chain.request())
                var retryCount = 0
                val maxRetries = 3
                while (!response.isSuccessful && response.code in 500..599 && retryCount < maxRetries) {
                    response.close()
                    retryCount++
                    Log.d(tag, "Повторная попытка $retryCount для ${chain.request().url}")
                    Thread.sleep(1000L * retryCount) // Экспоненциальная задержка
                    response = chain.proceed(chain.request())
                }
                response
            }
            .build()
    }
    
    // Retrofit клиент для API запросов
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://mermaid.ink") // Базовый URL, но используется динамически
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    private val apiService: DiagramApiService by lazy {
        retrofit.create(DiagramApiService::class.java)
    }
    
    /**
     * Рендерит диаграмму и возвращает путь к локальному файлу изображения.
     */
    fun renderDiagram(
        code: String,
        diagramType: DiagramType,
        format: String = "svg",
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // Проверяем кэш
        val cacheKey = "$diagramType:$code:$format"
        imageCache[cacheKey]?.let { cachedPath ->
            if (File(cachedPath).exists()) {
                onSuccess(cachedPath)
                return
            } else {
                imageCache.remove(cacheKey)
            }
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val imageUrl = when (diagramType) {
                    DiagramType.MERMAID -> DiagramApiService.createMermaidUrl(code, format)
                    DiagramType.PLANTUML -> DiagramApiService.createPlantUmlUrl(code, format)
                    DiagramType.GRAPHVIZ -> {
                        // Graphviz пока не поддерживается через API
                        onError("Graphviz рендеринг через API временно не поддерживается")
                        return@launch
                    }
                    DiagramType.UNKNOWN -> {
                        onError("Неизвестный тип диаграммы")
                        return@launch
                    }
                }
                
                Log.d(tag, "Загрузка диаграммы из: $imageUrl")
                
                // Загружаем изображение с помощью общего OkHttpClient
                val request = Request.Builder()
                    .url(imageUrl)
                    .get()
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    response.body?.let { body ->
                        // Сохраняем изображение во временный файл
                        val tempFile = createTempFile(context, code, format)
                        saveResponseToFile(body, tempFile)
                        
                        // Сохраняем в кэш
                        imageCache[cacheKey] = tempFile.absolutePath
                        
                        withContext(Dispatchers.Main) {
                            onSuccess(tempFile.absolutePath)
                        }
                    } ?: run {
                        onError("Пустой ответ от сервера")
                    }
                } else {
                    onError("Ошибка HTTP ${response.code}: ${response.message}")
                }
                
                response.close()
            } catch (e: Exception) {
                Log.e(tag, "Ошибка рендеринга диаграммы", e)
                onError("Ошибка рендеринга: ${e.message}")
            }
        }
    }
    
    /**
     * Создает временный файл для изображения.
     */
    private fun createTempFile(context: Context, code: String, format: String): File {
        val hash = code.hashCode().toString()
        val timestamp = System.currentTimeMillis()
        val fileName = "diagram_${hash}_${timestamp}.${format.lowercase()}"
        
        return File(context.cacheDir, fileName).apply {
            createNewFile()
        }
    }
    
    /**
     * Сохраняет ответ от сервера в файл.
     */
    private fun saveResponseToFile(body: ResponseBody, file: File) {
        FileOutputStream(file).use { outputStream ->
            body.byteStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }
    
    /**
     * Очищает кэш изображений.
     */
    fun clearCache() {
        imageCache.values.forEach { filePath ->
            File(filePath).delete()
        }
        imageCache.clear()
    }
    
    /**
     * Получает размер кэша в байтах.
     */
    fun getCacheSize(): Long {
        return imageCache.values.sumOf { filePath ->
            File(filePath).length()
        }
    }
}