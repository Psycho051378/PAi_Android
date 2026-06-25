package com.pai.android.di

import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.ProviderSettings
import com.pai.android.data.network.AiChatService
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Фабрика для создания AI сервисов с учётом настроек провайдера.
 */
class AiChatServiceFactory @Inject constructor() {

    /**
     * Создаёт AI сервис для указанных настроек провайдера.
     */
    fun createService(settings: ProviderSettings): AiChatService {
        val baseUrl = settings.getEffectiveBaseUrl()
        val apiKey = settings.apiKey
        
        // LITE_RT: локальная модель — не HTTP-провайдер
        if (settings.provider == com.pai.android.data.model.AiProvider.LITE_RT) {
            throw LocalModelNotReadyException()
        }
        
        // Создаём HTTP клиент с заголовками авторизации
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
        
        // Добавляем интерцептор для авторизации
        apiKey?.let { key ->
            clientBuilder.addInterceptor(createAuthInterceptor(settings.provider, key))
        }
        
        // Добавляем логирование (только для debug)
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        clientBuilder.addInterceptor(loggingInterceptor)
        
        // Создаём Retrofit экземпляр
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        return retrofit.create(AiChatService::class.java)
    }

    /**
     * Создаёт тестовый сервис для проверки подключения.
     */
    fun createTestService(baseUrl: String, apiKey: String?): AiChatService {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
        
        apiKey?.let { key ->
            // Для тестового сервиса используем стандартный интерцептор
            clientBuilder.addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                
                // Некоторые провайдеры требуют дополнительные заголовки
                if (baseUrl.contains("openrouter.ai")) {
                    requestBuilder.header("HTTP-Referer", "https://pai-android.app")
                    requestBuilder.header("X-Title", "Pai Android")
                }
                
                chain.proceed(requestBuilder.build())
            })
        }
        
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        return retrofit.create(AiChatService::class.java)
    }

    private fun createAuthInterceptor(provider: AiProvider, apiKey: String): Interceptor {
        return Interceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            
            when (provider) {
                AiProvider.OPENROUTER -> {
                    requestBuilder
                        .header("Authorization", "Bearer $apiKey")
                        .header("HTTP-Referer", "https://pai-android.app")
                        .header("X-Title", "Pai Android")
                }
                
                AiProvider.DEEPSEEK -> {
                    requestBuilder.header("Authorization", "Bearer $apiKey")
                }
                
                AiProvider.OPENAI -> {
                    requestBuilder.header("Authorization", "Bearer $apiKey")
                }
                
                AiProvider.OLLAMA -> {
                    // Ollama не требует авторизации
                }
                
                AiProvider.LITE_RT -> {
                    // Локальная модель — без авторизации
                }
                
                AiProvider.CUSTOM -> {
                    // Для кастомного провайдера используем стандартный Bearer токен
                    if (apiKey.isNotBlank()) {
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }
                }
            }
            
            // Общие заголовки
            requestBuilder.header("Content-Type", "application/json")
            
            chain.proceed(requestBuilder.build())
        }
    }
}

class LocalModelNotReadyException(message: String = "Локальная модель ещё не интегрирована с AI-агентом") : Exception(message)