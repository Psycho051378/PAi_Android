package com.pai.android.data.service

import com.pai.android.data.model.WebSearchProvider
import com.pai.android.data.model.WebSearchSettings
import com.pai.android.data.repository.WebSearchRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Результат веб-поиска.
 */
data class SearchResult(
    val title: String,
    val link: String,
    val snippet: String,
    val source: String = "web"
)

/**
 * Сервис для выполнения веб-поиска через различные провайдеры.
 */
class WebSearchService @Inject constructor(
    private val webSearchRepository: WebSearchRepository,
    private val defaultDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient
) {
    
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        private const val GOOGLE_SEARCH_URL = "https://www.googleapis.com/customsearch/v1"
        private const val TAVILY_SEARCH_URL = "https://api.tavily.com/search"
    }
    
    /**
     * Выполняет поиск по запросу с использованием текущих настроек.
     * Возвращает список результатов или пустой список в случае ошибки.
     */
    suspend fun search(query: String, maxResults: Int = 5): List<SearchResult> {
        return withContext(defaultDispatcher) {
            try {
                val settings = webSearchRepository.getSettings()
                
                if (!settings.canPerformSearch()) {
                    return@withContext emptyList()
                }
                
                when (settings.provider) {
                    WebSearchProvider.GOOGLE -> performGoogleSearch(query, settings, maxResults)
                    WebSearchProvider.TAVILY -> performTavilySearch(query, settings, maxResults)
                    WebSearchProvider.DUCKDUCKGO -> performDuckDuckGoSearch(query, maxResults)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
    
    /**
     * Выполняет поиск через Google Custom Search API.
     */
    private suspend fun performGoogleSearch(
        query: String,
        settings: WebSearchSettings,
        maxResults: Int
    ): List<SearchResult> {
        val apiKey = settings.googleApiKey ?: return emptyList()
        val engineId = settings.googleSearchEngineId ?: return emptyList()
        
        val url = "$GOOGLE_SEARCH_URL?key=$apiKey&cx=$engineId&q=${query.encodeURL()}&num=$maxResults"
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return emptyList()
        }
        
        val json = response.body?.string() ?: return emptyList()
        val jsonObject = JSONObject(json)
        
        val items = jsonObject.optJSONArray("items") ?: return emptyList()
        val results = mutableListOf<SearchResult>()
        
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val title = item.optString("title", "")
            val link = item.optString("link", "")
            val snippet = item.optString("snippet", "")
            
            results.add(SearchResult(title, link, snippet, "Google"))
            
            if (results.size >= maxResults) break
        }
        
        return results
    }
    
    /**
     * Выполняет поиск через Tavily Search API.
     */
    private suspend fun performTavilySearch(
        query: String,
        settings: WebSearchSettings,
        maxResults: Int
    ): List<SearchResult> {
        val apiKey = settings.tavilyApiKey ?: return emptyList()
        
        val jsonBody = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("max_results", maxResults)
            put("include_answer", false)
            put("include_raw_content", false)
        }.toString()
        
        val request = Request.Builder()
            .url(TAVILY_SEARCH_URL)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), jsonBody))
            .build()
        
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return emptyList()
        }
        
        val json = response.body?.string() ?: return emptyList()
        val jsonObject = JSONObject(json)
        
        val resultsArray = jsonObject.optJSONArray("results") ?: return emptyList()
        val results = mutableListOf<SearchResult>()
        
        for (i in 0 until resultsArray.length()) {
            val item = resultsArray.getJSONObject(i)
            val title = item.optString("title", "")
            val link = item.optString("url", "")
            val snippet = item.optString("content", "")
            
            results.add(SearchResult(title, link, snippet, "Tavily"))
            
            if (results.size >= maxResults) break
        }
        
        return results
    }
    
    /**
     * Выполняет поиск через DuckDuckGo (использует публичное API).
     * DuckDuckGo не требует API ключа, но имеет ограничения по запросам.
     */
    private suspend fun performDuckDuckGoSearch(
        query: String,
        maxResults: Int
    ): List<SearchResult> {
        // DuckDuckGo Instant Answer API
        val url = "https://api.duckduckgo.com/?q=${query.encodeURL()}&format=json&no_html=1&skip_disambig=1"
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return emptyList()
        }
        
        val json = response.body?.string() ?: return emptyList()
        val jsonObject = JSONObject(json)
        
        val results = mutableListOf<SearchResult>()
        
        // Извлекаем Abstract (краткое описание)
        val abstract = jsonObject.optString("Abstract")
        val abstractUrl = jsonObject.optString("AbstractURL")
        val abstractSource = jsonObject.optString("AbstractSource")
        
        if (abstract.isNotEmpty() && abstractUrl.isNotEmpty()) {
            results.add(SearchResult(
                title = abstractSource,
                link = abstractUrl,
                snippet = abstract,
                source = "DuckDuckGo"
            ))
        }
        
        // Извлекаем RelatedTopics (похожие темы)
        val relatedTopics = jsonObject.optJSONArray("RelatedTopics")
        if (relatedTopics != null) {
            for (i in 0 until relatedTopics.length()) {
                val topic = relatedTopics.getJSONObject(i)
                val text = topic.optString("Text", "")
                val url = topic.optString("FirstURL", "")
                
                if (text.isNotEmpty() && url.isNotEmpty()) {
                    // Разделяем текст на заголовок и сниппет
                    val parts = text.split(" - ", limit = 2)
                    val title = if (parts.size > 1) parts[0] else ""
                    val snippet = if (parts.size > 1) parts[1] else text
                    
                    results.add(SearchResult(title, url, snippet, "DuckDuckGo"))
                    
                    if (results.size >= maxResults) break
                }
            }
        }
        
        return results
    }
    
    /**
     * Проверяет соединение с текущим провайдером.
     * Возвращает true если провайдер доступен и настройки корректны.
     */
    suspend fun testConnection(): Boolean {
        return try {
            val settings = webSearchRepository.getSettings()
            if (!settings.canPerformSearch()) return false
            
            // Выполняем тестовый запрос
            val results = search("test", maxResults = 1)
            results.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Кодирует строку для использования в URL.
     */
    private fun String.encodeURL(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}