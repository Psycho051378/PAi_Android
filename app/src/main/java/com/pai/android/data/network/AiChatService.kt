package com.pai.android.data.network

import com.pai.android.data.network.model.ChatRequest
import com.pai.android.data.network.model.ChatResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * Универсальный сервис для взаимодействия с AI API.
 */
interface AiChatService {
    
    @POST("chat/completions")
    @Headers("Content-Type: application/json")
    suspend fun sendMessage(
        @Body request: ChatRequest
    ): Response<ChatResponse>
    
    // Примечание: Для разных провайдеров могут быть разные эндпоинты и заголовки.
    // Мы будем создавать отдельные экземпляры Retrofit с разными baseUrl и interceptors.
}