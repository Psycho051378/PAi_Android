package com.pai.android.agent.skills

import android.content.Context
import com.pai.android.agent.Intent as AgentIntent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import com.pai.android.agent.ResponseType
import com.pai.android.data.model.Message
import com.pai.android.data.repository.AiRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PythonSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiRepository: AiRepository
) : Skill {

    override val name: String = "python"
    override val description: String = "Execute Python scripts and tools in-app"

    companion object {
        @Volatile var enabled: Boolean = true
        private var initialized = false
        private var initError: String? = null
    }

    override fun canHandle(intent: AgentIntent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        if (intent == AgentIntent.TOOL_OPERATION && params["command"] == name) return true
        // Handle LLM-generated Python execution commands
        if (intent == AgentIntent.COMMAND && 
            (params["action"] == "execute_python" || params["command"] == "run_python")) return true
        return false
    }
override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        try {
            if (!initialized) { initPy() }
            if (initError != null)
                return@withContext SkillResult.Error(message = "Python init failed: $initError")

            val code = buildCode(params)
            if (code.isBlank())
                return@withContext SkillResult.Error(message = "No Python code provided")

            val raw = runPython(code)
            val formatted = formatResult(raw, code)

            // For raw output mode (external skills), extract only stdout without LLM interpretation
            if (params["raw_output"] == "true") {
                val outMatch = Regex("out = '([^']*)'").find(raw)
                val cleanOut = outMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.trim() ?: raw.take(200)
                return@withContext SkillResult.Success(message = cleanOut, responseType = ResponseType.TEXT)
            }
            // Try LLM interpretation; fallback to raw
            try {
                val q = params["query"] as? String ?: params["q"] as? String ?: ""
                val prompt = "User asked: $q\n\nPython output:\n$raw\n\nInterpret naturally. Quote actual output."
                val resp = aiRepository.sendMessage(
                    messages = listOf(Message.createUserMessage("pyint", prompt)),
                    systemPrompt = "You interpret Python output. Quote the real result.",
                    memoryContext = ""
                )
                if (resp.isSuccess) {
                    val t = resp.getOrThrow().text.trim()
                    if (t.isNotBlank()) {
                        val d = if (formatted is SkillResult.Success) formatted.data else mapOf()
                        return@withContext SkillResult.Success(message = t, data = d, responseType = ResponseType.RICH_TEXT)
                    }
                }
            } catch (_: Exception) {}
            formatted
        } catch (e: com.chaquo.python.PyException) { SkillResult.Error(message = "Python: ${e.message}")
        } catch (e: Exception) { SkillResult.Error(message = "Error: ${e.message ?: "unknown"}") }
    }

    private fun initPy() {
        try {
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(context))
            }
            initialized = true; initError = null
        } catch (e: Exception) { initError = e.message }
    }

    private suspend fun buildCode(params: Map<String, Any>): String {
        val raw = params["code"] as? String ?: params["script"] as? String
            ?: params["command"] as? String ?: params["query"] as? String
            ?: params["q"] as? String ?: return ""
        var s = raw.trim()
        // Strip instruction prefixes (handles both spaces and newlines after prefix)
        val prefixes = listOf("напиши python скрипт", "напиши python-скрипт", "напиши скрипт питон",
            "выполни python", "запусти python", "напиши скрипт", "создай скрипт", "python3", "python")
        for (p in prefixes) {
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.removePrefix(p).trim()
                break
            }
        }
        // If it's already code, use directly
        val isCode = s.contains("=") || s.contains("print(") || s.contains("import ") ||
            s.contains("def ") || s.contains("class ") || s.contains("if ") || s.contains("for ") ||
            s.contains("while ") || s.startsWith("#") || s.startsWith("try:")
        if (isCode) {
            // If also has natural language after code, clean via LLM
            val rest = s.substringAfter("=").substringAfter(")").substringAfter(" ").trim()
            if (rest.length > 10 && (rest.contains("а") || rest.contains("е"))) { /* has russian -> use LLM */ }
            else return s
        }
        // Generate code from description via LLM
        try {
            val resp = aiRepository.sendMessage(
                messages = listOf(Message.createUserMessage("pycode", "Generate ONLY Python code. Task: $s")),
                systemPrompt = "Output ONLY Python code, no markdown.",
                memoryContext = ""
            )
            if (resp.isSuccess) {
                var g = resp.getOrThrow().text.trim()
                g = g.replace(Regex("```[\\w]*\\n?"), "").trim()
                if (g.isNotBlank()) return g
            }
        } catch (_: Exception) {}
        return s
    }

    
    
    
    
    
    
        private fun runPython(code: String): String {
        val resultFile = java.io.File(context.cacheDir, "_pai_result.txt")
        val py = com.chaquo.python.Python.getInstance()
        val builtins = py.getModule("builtins")
        val mainDict = py.getModule("__main__").get("__dict__")

        try {
            // Write-to-file approach: bypass all JNI variable reading issues
            val resultPath = resultFile.absolutePath.replace("\\", "/")
            val dump = buildString {
                append("import types as _t,json\n")
                append("d={}\n")
                append("for k,v in list(globals().items()):\n")
                append(" if k.startswith('_'): continue\n")
                append(" if isinstance(v,(_t.ModuleType,type)): continue\n")
                append(" try: d[k]=repr(v)\n")
                append(" except: pass\n")
                append("for k in ['h','result','x','output','res','out','val','data','d']:\n")
                append(" if k in globals() and k not in d:\n")
                append("  try: d[k]=repr(globals()[k])\n")
                append("  except: pass\n")
                append("with open('" + resultPath + "','w') as f: json.dump(d,f,ensure_ascii=False)\n")
            }
            // Capture stdout by wrapping code
            // Build full code with stdout capture
            val fullCode = """import sys, io as _pai_sio
_pai_sout_old = sys.stdout
_pai_buf = _pai_sio.StringIO()
sys.stdout = _pai_buf
""" + code + """
sys.stdout = _pai_sout_old
out = _pai_buf.getvalue()
""" + dump
            builtins.callAttr("exec", fullCode, mainDict)

            if (resultFile.exists()) {
                val content = resultFile.readText(Charsets.UTF_8).trim()
                resultFile.delete()
                if (content.isNotBlank() && content != "{}") {
                    try {
                        val json = org.json.JSONObject(content)
                        val lines = json.keys().asSequence().map { k -> "$k = " + json.get(k).toString() }.toList()
                        return lines.joinToString("\n") + "\nEXIT: 0"
                    } catch (_: Exception) {
                        return content + "\nEXIT: 0"
                    }
                }
            }
            return "EXIT: 0"
        } catch (e: Exception) {
            val errMsg = e.message ?: e.javaClass.simpleName
            return "EXIT: 1\nerror: $errMsg"
        }
    }private fun formatResult(result: String, code: String): SkillResult {
        val trimmed = result.trim()
        val hasError = trimmed.contains("EXIT: 1")
        val output = trimmed.replace(Regex("\nEXIT: .*"), "").trim()
        val display = if (code.length > 200) code.take(200) + "..." else code
        val msg = when {
            hasError -> "\u274C Python error:\n\n$output"
            output.isBlank() -> "\u2705 Done\n\n`$display`"
            output.length > 5000 -> "\u2705 Done\n\n${output.take(5000)}\n..."
            else -> "\u2705 Done\n\n$output"
        }
        return SkillResult.Success(message = msg, data = mapOf("output" to output, "code" to code), responseType = ResponseType.RICH_TEXT)
    }
}
