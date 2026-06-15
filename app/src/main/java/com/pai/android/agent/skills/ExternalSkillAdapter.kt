package com.pai.android.agent.skills

import com.pai.android.agent.Intent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Адаптер, превращающий внешний навык (манифест) в Skill для DecisionEngine.
 */
class ExternalSkillAdapter(
    val manifest: SkillManifest,
    private val pythonSkill: com.pai.android.agent.Skill? = null,
    private val skillsDir: String = ""
) : Skill {

    override val name: String = "ext_${manifest.id}"
    override val description: String = "[External] ${manifest.name}: ${manifest.description}"

    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        if (!manifest.enabled) return false
        if (intent == Intent.TOOL_OPERATION && params["command"] == name) return true
        return manifest.triggers.any { Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(query) }
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        try {
            val query = params["query"] as? String ?: params["q"] as? String ?: ""
            val skipPattern = if (manifest.skip_words.isNotEmpty()) manifest.skip_words.joinToString("|") { Regex.escape(it) } else ""
            var cleanQuery = if (skipPattern.isNotEmpty()) query.replace(Regex("(?i)(" + skipPattern + ")\\s*"), "").trim() else query.trim()
            val searchQuery = if (cleanQuery.length < 3) query else cleanQuery
            
            // Python mode: read and execute local script
            if (manifest.type == "python" && manifest.mainScript.isNotBlank() && pythonSkill != null) {
                val scriptFile = java.io.File(skillsDir, manifest.mainScript)
                if (scriptFile.exists()) {
                    try {
                        val scriptCode = scriptFile.readText()
                        val wrappedCode = "query = \"\"\"${searchQuery.replace("\"", "\\\"")}\n\"\"\"\n${scriptCode}"
                        val result = pythonSkill.execute(mapOf("code" to wrappedCode, "raw_output" to "true"))
                        if (result is SkillResult.Success) {
                            return@withContext SkillResult.Success(
                                message = "🐍 " + manifest.name + ": " + result.message,
                                responseType = com.pai.android.agent.ResponseType.TEXT
                            )
                        } else if (result is SkillResult.Error) {
                            return@withContext SkillResult.Error(message = "Python skill error: " + result.message)
                        }
                    } catch (e: Exception) {
                        return@withContext SkillResult.Error(message = "Python skill failed: " + (e.message ?: "Unknown"))
                    }
                } else {
                    return@withContext SkillResult.Error(message = "Python script not found: " + manifest.mainScript)
                }
            }
            
            val baseUrl = manifest.endpoint
            val urls = listOf(
                baseUrl,
                baseUrl.replace("https://pai.com.ru/skills", "http://10.0.2.2:8005/skills")
            ).distinct()

            var lastError: String? = null
            val timeoutsMs = manifest.timeout * 1000

            for (endpoint in urls) {
                try {
                    val urlStr = endpoint +
                        (if (endpoint.contains("?")) "&" else "?") +
                        "q=${URLEncoder.encode(searchQuery, "UTF-8")}&" +
                        "skill_id=${manifest.id}"
                    val url = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = timeoutsMs
                    conn.readTimeout = timeoutsMs
                    conn.instanceFollowRedirects = true
                    conn.requestMethod = "GET"
                    val responseCode = conn.responseCode
                    if (responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().readText()
                        if (text.isNotBlank()) {
                            return@withContext SkillResult.Success(
                                message = "⚡ " + manifest.name + ": " + text,
                                responseType = com.pai.android.agent.ResponseType.TEXT
                            )
                        }
                        lastError = "Empty response"
                    } else {
                        lastError = "HTTP $responseCode"
                    }
                } catch (e: Exception) {
                    lastError = e.message
                }
            }
            SkillResult.Error(message = "External skill failed: $lastError")
        } catch (e: Exception) {
            SkillResult.Error(
                message = "External skill failed",
                details = e.message ?: "Unknown error"
            )
        }
    }
}
