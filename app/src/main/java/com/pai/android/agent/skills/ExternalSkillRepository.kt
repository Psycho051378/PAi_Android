package com.pai.android.agent.skills

import com.pai.android.data.repository.MemoryRepository
import com.pai.android.agent.SkillRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalSkillRepository @Inject constructor(
    private val memoryRepository: MemoryRepository
) {
    var pythonSkill: com.pai.android.agent.Skill? = null
    var skillsDirectory: String = ""
    private var skillRegistry: SkillRegistry? = null
    
    fun attachRegistry(registry: SkillRegistry) {
        skillRegistry = registry
    }
    companion object {
        // Базовый URL репозитория навыков на сайте пользователя
        var repositoryBaseUrl: String = "https://pai.com.ru/skills"
        private const val LOCAL_URL = "http://10.0.2.2:8005/skills"
        private const val STORAGE_CATEGORY = "external_skills"
        private const val STORAGE_KEY = "installed"
    }

    /**
     * Загружает список доступных навыков из репозитория.
     */
    suspend fun fetchAvailableSkills(): List<SkillIndexEntry> = withContext(Dispatchers.IO) {
        val urls = listOf("$repositoryBaseUrl/index.json", "$LOCAL_URL/index.json")
        for (urlStr in urls) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val text = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val arr = json.getJSONArray("skills")
                val result = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    SkillIndexEntry(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        version = obj.getString("version"),
                        description = obj.optString("description", ""),
                        manifest_url = obj.getString("manifest_url")
                    )
                }
                return@withContext result
            } catch (e: Exception) {
                println("ExternalSkillRepo: fetch failed: $urlStr — ${e.message}")
            }
        }
        emptyList()
    }

    /**
     * Загружает манифест конкретного навыка.
     */
    suspend fun fetchManifest(manifestUrl: String): SkillManifest? = withContext(Dispatchers.IO) {
        val urls = listOf(
            manifestUrl,
            manifestUrl.replace("pai.com.ru/skills", "http://10.0.2.2:8005/skills"),
            manifestUrl.replace("https://pai.com.ru/skills", "http://10.0.2.2:8005/skills")
        )
        for (urlStr in urls) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val text = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val manifest = SkillManifest(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    version = json.getString("version"),
                    description = json.optString("description", ""),
                    author = json.optString("author", ""),
                    instructions = json.getString("instructions"),
                    endpoint = json.optString("endpoint", ""),
                    type = json.optString("type", "php"),
                    mainScript = json.optString("mainScript", ""),
                    triggers = json.optJSONArray("triggers")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    params = json.optJSONObject("params")?.let { obj ->
                        obj.keys().asSequence().associateWith { obj.getString(it) }
                    } ?: emptyMap()
                )
                return@withContext manifest
            } catch (e: Exception) {
                println("ExternalSkillRepo: fetchManifest failed: $urlStr - ${e.message}")
            }
        }
        null
    }

    /**
     * Сохраняет установленный навык в постоянную память.
     */
    suspend fun saveInstalledSkill(manifest: SkillManifest) {
        val existing = getInstalledSkills().toMutableList()
        existing.removeAll { it.id == manifest.id }
        existing.add(manifest)
        saveInstalledList(existing)
        skillRegistry?.let { reloadExtSkills(it) }
    }

    /**
     * Удаляет навык.
     */
    suspend fun removeInstalledSkill(skillId: String) {
        val existing = getInstalledSkills().toMutableList()
        existing.removeAll { it.id == skillId }
        saveInstalledList(existing)
        skillRegistry?.let { reloadExtSkills(it) }
    }

    /**
     * Возвращает список установленных навыков.
     */
    suspend fun getInstalledSkills(): List<SkillManifest> {
        val fact = memoryRepository.getFactByCategoryAndKey(STORAGE_CATEGORY, STORAGE_KEY) ?: return emptyList()
        return try {
            val arr = JSONArray(fact.value)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SkillManifest(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    version = obj.getString("version"),
                    description = obj.optString("description", ""),
                    author = obj.optString("author", ""),
                    instructions = obj.getString("instructions"),
                    endpoint = obj.optString("endpoint", ""),
                    type = obj.optString("type", "php"),
                    mainScript = obj.optString("mainScript", ""),
                    triggers = obj.optJSONArray("triggers")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    } ?: emptyList(),
                    params = obj.optJSONObject("params")?.let { o ->
                        o.keys().asSequence().associateWith { o.getString(it) }
                    } ?: emptyMap(),
                    skip_words = obj.optJSONArray("skip_words")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    } ?: emptyList(),
                    timeout = obj.optInt("timeout", 30),
                    enabled = obj.optBoolean("enabled", true)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun saveInstalledList(skills: List<SkillManifest>) {
        val arr = JSONArray()
        skills.forEach { s ->
            val obj = JSONObject()
            obj.put("id", s.id); obj.put("name", s.name); obj.put("version", s.version)
            obj.put("description", s.description); obj.put("author", s.author)
            obj.put("instructions", s.instructions); obj.put("endpoint", s.endpoint)
            obj.put("triggers", JSONArray(s.triggers))
            obj.put("params", JSONObject(s.params.toMap()))
            obj.put("type", s.type)
            obj.put("mainScript", s.mainScript)
            obj.put("enabled", s.enabled)
            arr.put(obj)
        }
        memoryRepository.savePermanentFactFull(
            category = STORAGE_CATEGORY, key = STORAGE_KEY,
            value = arr.toString(2), confidence = 0.5f, scope = "user", tags = "skills"
        )
    }

    /**
     * Обновляет статус enabled для навыка.
     */
    suspend fun toggleSkill(skillId: String, enabled: Boolean) {
        val skills = getInstalledSkills().toMutableList()
        val idx = skills.indexOfFirst { it.id == skillId }
        if (idx >= 0) {
            skills[idx] = skills[idx].copy(enabled = enabled)
            saveInstalledList(skills)
        }
        skillRegistry?.let { reloadExtSkills(it) }
    }
    suspend fun registerInstalledSkills(skillRegistry: SkillRegistry) {
        val installed = getInstalledSkills()
        for (manifest in installed) {
            if (manifest.enabled) {
                try {
                    val adapter = ExternalSkillAdapter(manifest, pythonSkill, skillsDirectory)
                    skillRegistry.register(adapter)
                    println("ExternalSkillRepo: registered '${adapter.name}' from PC")
                } catch (e: Exception) {
                    println("ExternalSkillRepo: register '${manifest.name}' failed: ${e.message}")
                }
            }
        }
        scanLocalSkills(skillRegistry)
    }
    
    /** Re-scan local skills (called from UI or after file changes) */
    suspend fun refreshLocalSkills() {
        try {
            println("refreshLocalSkills: skillsDirectory='" + skillsDirectory + "'")
            val registry = skillRegistry
            if (registry == null) { println("refreshLocalSkills: skillRegistry is null"); return }
            println("refreshLocalSkills: registry available")
            // Unregister local skills whose files were deleted
            val localSkills = registry.getAllSkills().filter { it.name.startsWith("ext_") }
            println("refreshLocalSkills: found " + localSkills.size + " ext_ skills in registry")
            for (sk in localSkills) {
                val file = java.io.File(skillsDirectory, sk.name.removePrefix("ext_") + ".json")
                if (!file.exists()) {
                    registry.unregister(sk.name)
                    println("refreshLocalSkills: unregistered missing skill '" + sk.name + "'")
                }
            }
            scanLocalSkills(registry)
        } catch (e: Exception) {
            println("refreshLocalSkills error: " + e.message)
        }
    }

    /** Scan workspace\/skills\/ for local .json manifests */
    private suspend fun scanLocalSkills(skillRegistry: SkillRegistry) {
        if (skillsDirectory.isBlank()) { println("scanLocalSkills: skillsDirectory is blank"); return }
        println("scanLocalSkills: scanning " + skillsDirectory)
        try {
            val dir = java.io.File(skillsDirectory)
            if (!dir.exists() || !dir.isDirectory) { println("scanLocalSkills: directory not found: " + skillsDirectory); return }
            dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                try {
                    val text = file.readText()
                    val json = org.json.JSONObject(text)
                    val manifest = SkillManifest(
                        id = json.getString("name"),
                        name = json.optString("name", file.nameWithoutExtension),
                        version = json.optString("version", "1.0"),
                        description = json.optString("description", ""),
                        instructions = json.optString("instructions", ""),
                        endpoint = json.optString("endpoint", ""),
                        type = json.optString("type", "php"),
                        mainScript = json.optString("mainScript", ""),
                        triggers = json.optJSONArray("triggers")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: emptyList(),
                        timeout = json.optInt("timeout", 30),
                        enabled = json.optBoolean("enabled", true)
                    )
                    // Respect the saved toggle state from installed list
                    val savedState = getInstalledSkills().find { it.id == manifest.id }?.enabled
                    val finalEnabled = savedState ?: manifest.enabled
                    val effectiveManifest = manifest.copy(enabled = finalEnabled)
                    if (effectiveManifest.enabled) {
                        val localAdapter = ExternalSkillAdapter(effectiveManifest, pythonSkill, skillsDirectory)
                        skillRegistry.register(localAdapter)
                        // Add to installed list without reload (avoids recursion)
                        val existing = getInstalledSkills().toMutableList()
                        existing.removeAll { it.id == effectiveManifest.id }
                        existing.add(effectiveManifest)
                        saveInstalledList(existing)
                        println("ExternalSkillRepo: registered local '" + localAdapter.name + "'")
                    }
                } catch (e: Exception) {
                    println("ExternalSkillRepo: scan local failed for " + file.name + ": " + e.message)
                }
            }
        } catch (e: Exception) {
            println("ExternalSkillRepo: scanLocalSkills error: " + e.message)
        }
    }
    
    suspend fun reloadExtSkills(skillRegistry: SkillRegistry) {
        // Unregister all existing ext_ skills
        val extSkills = skillRegistry.getAllSkills().filter { it.name.startsWith("ext_") }
        for (skill in extSkills) {
            skillRegistry.unregister(skill.name)
        }
        // Re-register from PC
        registerInstalledSkills(skillRegistry)
    }

}



