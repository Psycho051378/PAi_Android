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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfficeSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiRepository: AiRepository
) : Skill {

    override val name: String = "office"
    override val description: String =
        "Создание/чтение MS Office: Word (.docx), Excel (.xlsx), PowerPoint (.pptx). " +
        "Пример: создай таблицу excel: Имя, Возраст, Город"

    companion object {
        @Volatile var enabled: Boolean = true
        private var pythonInited = false
        private var scriptCode: String? = null
    }

    override fun canHandle(intent: AgentIntent, query: String, params: Map<String, Any>): Boolean {
        if (!enabled) return false
        if (intent == AgentIntent.TOOL_OPERATION && params["command"] == name) return true
        val lower = query.lowercase()
        return lower.contains("документ") || lower.contains("word") ||
                lower.contains("таблиц") || lower.contains("презентац") || lower.contains("pptx") ||
                lower.contains("docx") || lower.contains("xlsx") || lower.contains("excel") || lower.contains("powerpoint")
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        try {
            val inputJson = buildCommand(params) ?: return@withContext SkillResult.Success(
                message = "Примеры:\n• «создай таблицу excel: Имя, Возраст, Город»\n• «создай презентацию на 3 слайда»\n• «создай документ с таблицей»",
                responseType = ResponseType.TEXT
            )

            val action = inputJson.optString("action", "")
            val data = inputJson.optJSONObject("data") ?: JSONObject()
            val path = data.optString("path", "")

            // PPTX: generate using template-based approach with proper DEFLATE + styling
            if (action == "pptx_create") {
                val result = createPptx(data)
                if (result != null) return@withContext result
                // If Kotlin fails, fall through to Python
            }

            // Word: generate AI content, then use Python
            if (action == "word_create") {
                val rawQuery = data.optString("raw_query", "")
                val wordContent = generateWordContentAsJson(rawQuery)
                if (wordContent != null) {
                    val wordObj = wordContent.optJSONObject("content")
                    if (wordObj != null) {
                        for (key in wordObj.keys()) {
                            data.put(key, wordObj.get(key))
                        }
                    }
                }
            }

            // Excel: generate AI content, then use Python
            if (action == "excel_create") {
                val rawQuery = data.optString("raw_query", "")
                val excelContent = generateExcelContentAsJson(rawQuery)
                if (excelContent != null) {
                    val sheetsArr = excelContent.optJSONArray("sheets")
                    if (sheetsArr != null) {
                        data.put("sheets", sheetsArr)
                    }
                }
            }

            // Word/Excel: use Python
            if (!pythonInited) initPython()
            val rawResult = runPython(inputJson.toString())
            val parsed = JSONObject(rawResult)

            if (parsed.optString("status") == "ok") {
                val resPath = parsed.optString("path", path)
                val preview = parsed.optString("preview", "")
                val desc = parsed.optString("description", "")
                val sb = StringBuilder()
                sb.appendLine("✅ **$desc**")
                if (preview.isNotBlank()) sb.appendLine("\n$preview")
                if (resPath.isNotBlank()) sb.appendLine("\n📁 $resPath")
                SkillResult.Success(message = sb.toString(), data = mapOf("path" to resPath, "output" to rawResult), responseType = ResponseType.TEXT)
            } else {
                SkillResult.Error(message = "❌ ${parsed.optString("error", "Неизвестная ошибка")}")
            }
        } catch (e: Exception) {
            SkillResult.Error(message = "❌ Office ошибка: ${e.message ?: "Неизвестная"}")
        }
    }



    private suspend fun createPptx(data: JSONObject): SkillResult? {
        val outputPath = data.optString("path", "") ?: return null
        val slideCount = data.optInt("slideCount", minOf(data.optInt("slideCount", 3), 12))
        val rawQuery = data.optString("raw_query", "")

        // Extract template from assets
        val tmplCacheFile = File(context.cacheDir, "pptx_template.pptx")
        if (!tmplCacheFile.exists()) {
            try {
                context.assets.open("python/pptx_template.pptx").use { input ->
                    tmplCacheFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                println("OfficeSkill: cannot extract template: ${e.message}")
                return null
            }
        }

        try {
            val outputFile = File(outputPath)

            // Read all entries from template
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(FileInputStream(tmplCacheFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val bytes = zis.readBytes()
                    entries[entry.name] = bytes
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Generate slide content with AI
            val slidesJson = generatePptxContentAsJson(rawQuery, slideCount)
            val actualSlideCount = if (slidesJson != null && slidesJson.length() > 0) slidesJson.length() else minOf(slideCount, 5)

            // Get template slide names
            val templateSlideNames = entries.keys.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") && !it.contains("rels") }.sorted()
            if (templateSlideNames.isEmpty()) return null

            // Parse AI content or use defaults
            data class SlideContent(val title: String, val subtitle: String, val bullets: List<String>)
            val slidesContent = mutableListOf<SlideContent>()
            for (i in 0 until actualSlideCount) {
                if (slidesJson != null && i < slidesJson.length()) {
                    val obj = slidesJson.getJSONObject(i)
                    val title = obj.optString("title", "Слайд ${i+1}")
                    val subtitle = obj.optString("subtitle", "")
                    val bullets = mutableListOf<String>()
                    val arr = obj.optJSONArray("bullets")
                    if (arr != null) {
                        for (j in 0 until arr.length()) bullets.add(arr.optString(j, ""))
                    }
                    while (bullets.size < 4) bullets.add("")
                    slidesContent.add(SlideContent(title, subtitle, bullets))
                } else {
                    val idx = i % 5
                    val defaults = listOf(
                        SlideContent("Презентация", "Подзаголовок", listOf("Тема 1", "Тема 2", "Тема 3", "Тема 4")),
                        SlideContent("Раздел 1", "", listOf("Пункт 1", "Пункт 2", "Пункт 3", "Пункт 4")),
                        SlideContent("Раздел 2", "", listOf("Пункт 1", "Пункт 2", "Пункт 3", "Пункт 4")),
                        SlideContent("Раздел 3", "", listOf("Пункт 1", "Пункт 2", "Пункт 3", "Пункт 4")),
                        SlideContent("Заключение", "", listOf("Итог 1", "Итог 2", "Итог 3", "Итог 4"))
                    )
                    slidesContent.add(defaults[idx])
                }
            }

            // Colors for alternating slides
            val bgColors = arrayOf("0D0D2B", "1A1A3E", "0D1B2A", "1B0D2A", "0D2A1B", "2A1B0D", "1A2A0D")
            val titleColors = arrayOf("00D4FF", "00FF88", "FF6B6B", "FFD700", "00E5FF", "FF69B4", "7B68EE")

            // Local helper: escape XML
            fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

            // Generate each slide with styling
            for (i in 0 until actualSlideCount) {
                val srcSlide = templateSlideNames[i % templateSlideNames.size]
                val dstName = "ppt/slides/slide${i + 1}.xml"
                var xml = String(entries[srcSlide] ?: continue, Charsets.UTF_8)

                val content = slidesContent[i]
                val bgColor = bgColors[i % bgColors.size]
                val titleColor = titleColors[i % titleColors.size]

                // Add background color to slide
                        // Add background color to slide
                val bgTag = "<p:bg><a:solidFill><a:srgbClr val=\"" + bgColor + "\"/></a:solidFill></p:bg>"
                xml = xml.replace("<p:cSld>", "<p:cSld>" + bgTag)

                // Build styled title
                val titleRun = "<a:r><a:rPr sz=\"4400\" b=\"1\"><a:solidFill><a:srgbClr val=\"" + titleColor + "\"/></a:solidFill></a:rPr><a:t>" + esc(content.title) + "</a:t></a:r>"
                val subRun = if (content.subtitle.isNotBlank()) {
                    "<a:r><a:rPr sz=\"2400\" b=\"0\"><a:solidFill><a:srgbClr val=\"88CCFF\"/></a:solidFill></a:rPr><a:t>" + esc(content.subtitle) + "</a:t></a:r>"
                } else null
                val bulletRuns = content.bullets.filter { it.isNotBlank() }.map { b ->
                    "<a:r><a:rPr sz=\"1800\" b=\"0\"><a:solidFill><a:srgbClr val=\"D0D0D0\"/></a:solidFill></a:rPr><a:t>" + esc(b) + "</a:t></a:r>"
                }

                // Replace <a:r> blocks (not <a:t>) with styled versions
                val runRegex = Regex("<a:r>.*?</a:r>", RegexOption.DOT_MATCHES_ALL)
                val allReps = mutableListOf<String?>()
                allReps.add(titleRun)
                allReps.add(subRun)
                allReps.addAll(bulletRuns)
                val runMatches = runRegex.findAll(xml).toList()
                val runReplacements = mutableListOf<Pair<IntRange, String>>()
                for (ri in 0 until minOf(allReps.size, runMatches.size)) {
                    val rep = allReps[ri]
                    if (rep != null) {
                        runReplacements.add(runMatches[ri].range to rep)
                    }
                }
                for ((range, rep) in runReplacements.reversed()) {
                    xml = xml.substring(0, range.first) + rep + xml.substring(range.last + 1)
                }

                entries[dstName] = xml.toByteArray(Charsets.UTF_8)

                // Ensure slide rels exists
                val relsName = "ppt/slides/_rels/slide${i + 1}.xml.rels"
                if (relsName !in entries) {
                    val trels = "ppt/slides/_rels/" + templateSlideNames[i % templateSlideNames.size]
                        .substringAfterLast("/").replace(".xml", ".xml.rels")
                    if (trels in entries) {
                        entries[relsName] = entries[trels]!!
                    } else {
                        val mr = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>" +
                            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                            "</Relationships>"
                        entries[relsName] = mr.toByteArray(Charsets.UTF_8)
                    }
                }
            }

            // Remove extra slides
            val toRemove = mutableListOf<String>()
            for (key in entries.keys) {
                if (key.startsWith("ppt/slides/")) {
                    val num = Regex("""slide(\d+)""").find(key)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    if (num > actualSlideCount) toRemove.add(key)
                }
            }
            for (key in toRemove) entries.remove(key)

            // Update presentation.xml
            val presXml = String(entries["ppt/presentation.xml"] ?: return null, Charsets.UTF_8)
            val slds = buildString {
                append("<p:sldIdLst>")
                for (i in 0 until actualSlideCount) {
                    append("<p:sldId id=\"${i + 1}\" r:id=\"rId${i + 7}\"/>")
                }
                append("</p:sldIdLst>")
            }
            entries["ppt/presentation.xml"] = presXml.replace(
                Regex("<p:sldIdLst>.*?</p:sldIdLst>", RegexOption.DOT_MATCHES_ALL), slds
            ).toByteArray(Charsets.UTF_8)

            // Update [Content_Types].xml
            val ctXml = String(entries["[Content_Types].xml"] ?: return null, Charsets.UTF_8)
            val ctClean = ctXml.replace(Regex("""<Override PartName="/ppt/slides/slide\d+\.xml" ContentType="[^"]+"/>"""), "").trim()
            val ctAdd = buildString {
                for (i in 1..actualSlideCount) {
                    append("<Override PartName=\"/ppt/slides/slide$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
                }
            }
            entries["[Content_Types].xml"] = ctClean.replace("</Types>", ctAdd + "</Types>").toByteArray(Charsets.UTF_8)

            // Update presentation.xml.rels
            val presRels = String(entries["ppt/_rels/presentation.xml.rels"] ?: return null, Charsets.UTF_8)
            val relsClean = presRels.replace(Regex("""<Relationship Id="rId\d+" Type="[^"]*/slide" Target="[^"]*"/>"""), "")
            val relsAdd = buildString {
                for (i in 0 until actualSlideCount) {
                    append("<Relationship Id=\"rId${i + 7}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${i + 1}.xml\"/>")
                }
            }
            entries["ppt/_rels/presentation.xml.rels"] = relsClean.replace("</Relationships>", relsAdd + "</Relationships>")
                .toByteArray(Charsets.UTF_8)

            // Write ZIP with STORED (pre-computed CRC to avoid data descriptor issues in PowerPoint)
            val orderedNames = mutableListOf("[Content_Types].xml")
            orderedNames.addAll(entries.keys.filter { it != "[Content_Types].xml" }.sorted())
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                for (name in orderedNames) {
                    val bytes = entries[name]!!
                    val entry = ZipEntry(name)
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().let { crc -> crc.update(bytes); crc.value }
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }

            val preview = slidesContent.mapIndexed { idx, sc -> "**${idx + 1}. ${sc.title}**" }.joinToString("\n")
            return SkillResult.Success(
                message = "✅ Presentation with $actualSlideCount slide(s)\n\n$preview\n📁 $outputPath",
                data = mapOf("path" to outputPath),
                responseType = ResponseType.TEXT
            )
        } catch (e: Exception) {
            println("OfficeSkill: PPTX error: ${e.message}")
            return null
        }
    }



    private suspend fun buildCommand(params: Map<String, Any>): JSONObject? {
        val query = params["query"] as? String ?: params["q"] as? String ?: return null
        val lower = query.lowercase()
        val cmd = JSONObject()
        val action = when {
            lower.contains("word") || lower.contains("документ") || lower.contains("docx") -> "word_create"
            lower.contains("excel") || lower.contains("xlsx") || lower.contains("таблиц") -> "excel_create"
            lower.contains("презентац") || lower.contains("pptx") || lower.contains("powerpoint") -> "pptx_create"
            lower.contains("прочитай") || lower.contains("открой") -> {
                when {
                    lower.contains("docx") -> "word_read"
                    lower.contains("xlsx") -> "excel_read"
                    else -> "word_read"
                }
            }
            else -> "word_create"
        }
        cmd.put("action", action)
        val data = JSONObject()
        data.put("raw_query", query)

        val pathMatch = Regex("""([a-zA-Z]:[/\\][^\s,;]+)""").find(query)
        if (pathMatch != null) {
            data.put("path", pathMatch.value)
        } else {
            val docsDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "Office")
            if (!docsDir.exists()) docsDir.mkdirs()
            val ext = when (action) {
                "word_create", "word_read" -> "docx"
                "excel_create", "excel_read" -> "xlsx"
                "pptx_create" -> "pptx"
                else -> "docx"
            }
            val timestamp = System.currentTimeMillis() / 1000
            val filename = generateFilename(query, action) + "_$timestamp.$ext"
            data.put("path", File(docsDir, filename).absolutePath)
        }

        val titleMatch = Regex("""(?:(?:с заголовком|под названием|на тему)\s*[""]?)?([А-Яа-яA-Za-z\s]{2,50})[""]?(?:\s|,|\.|$)""").find(query)
        if (titleMatch != null) data.put("title", titleMatch.groupValues[1].trim())

        if (action == "pptx_create") {
            val slideCount = Regex("""(\d+)\s*слайд""", RegexOption.IGNORE_CASE).find(lower)
            data.put("slideCount", slideCount?.groupValues?.getOrNull(1)?.toInt() ?: 3)
        }

        if (action in listOf("word_create", "excel_create")) {
            val tableSize = Regex("""таблиц[еей]?\s*(\d+)\s*[xх×]\s*(\d+)""", RegexOption.IGNORE_CASE).find(lower)
            if (tableSize != null) {
                data.put("tableRows", tableSize.groupValues[1].toInt())
                data.put("tableCols", tableSize.groupValues[2].toInt())
            }
        }
        cmd.put("data", data)
        return cmd
    }

    private fun generateFilename(query: String, action: String): String {
        val lower = query.lowercase()
        return when {
            lower.contains("отчёт") || lower.contains("отчет") -> "Report"
            lower.contains("расход") -> "Expenses"
            lower.contains("смет") -> "Estimate"
            lower.contains("документ") -> "Document"
            lower.contains("презентац") || lower.contains("powerpoint") -> "Presentation"
            lower.contains("таблиц") -> "Table"
            else -> "Office"
        }
    }

    private fun initPython() {
        try {
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(context))
            }
            val scriptText = try {
                context.assets.open("python/office_tools.py").bufferedReader().readText()
            } catch (_: Exception) {
                val file = File(context.filesDir.parentFile?.parentFile, "app/src/main/python/office_tools.py")
                if (file.exists()) file.readText() else null
            }
            if (scriptText != null) {
                scriptCode = scriptText
                val py = com.chaquo.python.Python.getInstance()
                py.getModule("builtins").callAttr("compile", scriptText, "office_tools.py", "exec")
                pythonInited = true
            } else throw Exception("office_tools.py not found")
        } catch (e: Exception) {
            println("OfficeSkill: init error: ${e.message}")
            pythonInited = false
            throw e
        }
    }

    private fun runPython(jsonArg: String): String {
        val py = com.chaquo.python.Python.getInstance()
        val builtins = py.getModule("builtins")
        val mainDict = py.getModule("__main__").get("__dict__")
        val scriptFile = File(context.cacheDir, "_office_script.py")
        val resultFile = File(context.cacheDir, "_office_result.txt")
        val argsFile = File(context.cacheDir, "_office_args.json")
        scriptFile.writeText(scriptCode ?: "", Charsets.UTF_8)
        argsFile.writeText(jsonArg, Charsets.UTF_8)
        val scriptPath = scriptFile.absolutePath.replace("\\", "/")
        val argsPath = argsFile.absolutePath.replace("\\", "/")
        val resultPath = resultFile.absolutePath.replace("\\", "/")

        val fullCode = buildString {
            append("import sys, json, os\n")
            append("os.chdir('" + scriptFile.parentFile.absolutePath.replace("\\", "/") + "')\n")
            append("_pai_script_path = '$scriptPath'\n")
            append("_pai_args_path = '$argsPath'\n")
            append("with open(_pai_script_path, 'r', encoding='utf-8') as _pai_f:\n")
            append(" _pai_script = _pai_f.read()\n")
            append("with open(_pai_args_path, 'r', encoding='utf-8') as _pai_f:\n")
            append(" _pai_args = _pai_f.read()\n")
            append("sys.argv = ['office_tools.py', _pai_args]\n")
            append("exec(_pai_script)\n")
            append("import io as _pai_io\n")
            append("_pai_old_stdout = sys.stdout\n")
            append("_pai_buf = _pai_io.StringIO()\n")
            append("sys.stdout = _pai_buf\n")
            append("try:\n")
            append(" main()\n")
            append("except Exception as _pai_e:\n")
            append(" import traceback\n")
            append(" print(json.dumps({'status':'error','error':str(_pai_e)+chr(10)+traceback.format_exc()}, ensure_ascii=False))\n")
            append("sys.stdout = _pai_old_stdout\n")
            append("_pai_out = _pai_buf.getvalue()\n")
            append("with open('$resultPath','w',encoding='utf-8') as _pai_f:\n")
            append(" _pai_f.write(_pai_out)\n")
        }
        try {
            builtins.callAttr("exec", fullCode, mainDict)
            scriptFile.delete(); argsFile.delete()
            if (resultFile.exists()) {
                val content = resultFile.readText(Charsets.UTF_8).trim()
                resultFile.delete()
                if (content.isNotBlank()) return content
            }
            return "{\\\"status\\\":\\\"error\\\",\\\"error\\\":\\\"No output from Python script\\\"}"
        } catch (e: Exception) {
            scriptFile.delete(); argsFile.delete()
            val errMsg = e.message?.replace("\"", "\\\"") ?: "Unknown"
            return "{\\\"status\\\":\\\"error\\\",\\\"error\\\":\\\"$errMsg\\\"}"
        }
    }
    /**
     * Uses AI to generate slide content as JSONArray.
     */
    private suspend fun generatePptxContentAsJson(rawQuery: String, slideCount: Int): JSONArray? {
        try {
            val prompt = """Generate a PowerPoint presentation in Russian based on this request: "$rawQuery"
Return ONLY valid JSON array. Each element: {"title":"slide title","subtitle":"slide subtitle","bullets":["bullet1","bullet2","bullet3","bullet4"]}
IMPORTANT: 
- "title" = the actual slide heading text, NOT a design description
- "subtitle" = the actual slide subtitle text
- "bullets" = 4 actual bullet points with real content
- All text MUST be content, NOT descriptions of colors/fonts/effects
- The design (colors, fonts, backgrounds) is added automatically by the system
- Do NOT describe visual effects — only write the actual slide text
Generate $slideCount slides. Use Russian language. No markdown, no code blocks, only JSON array."""
            val msg = Message.createUserMessage("pptgen", prompt)
            val resp = aiRepository.sendMessage(
                messages = listOf(msg),
                systemPrompt = "You generate JSON for PowerPoint slides. Write only actual content (headings, subtitles, bullet points). NEVER describe visual design, colors, or effects — those are handled automatically. Output ONLY valid JSON array. Each slide must have exactly 4 bullets.",
                memoryContext = ""
            )
            if (resp.isSuccess) {
                val text = resp.getOrThrow().text.trim()
                val cleaned = text.replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*```$", RegexOption.IGNORE_CASE), "")
                    .trim()
                val json = JSONArray(cleaned)
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val arr = obj.optJSONArray("bullets")
                    val padded = JSONArray()
                    if (arr != null) {
                        for (j in 0 until arr.length()) padded.put(arr.optString(j, ""))
                    }
                    while (padded.length() < 4) padded.put("")
                    obj.put("bullets", padded)
                }
                if (json.length() > 0) return json
            }
        } catch (e: Exception) {
            println("OfficeSkill: AI content generation error: ${e.message}")
        }
        return null
    }

    /**
     * Uses AI to generate Word document content as JSON.
     * Returns JSONObject with: title, heading1, heading2, paragraphs[], bullets[], numbered[]
     */
    private suspend fun generateWordContentAsJson(rawQuery: String): JSONObject? {
        try {
            val prompt = """Generate a Word document in Russian based on this request: "$rawQuery"
Return ONLY valid JSON object with this structure:
{
  "content": {
    "title": "Document title",
    "heading1": "Main section heading",
    "heading2": "Subsection heading (optional)",
    "paragraphs": ["Paragraph 1 text", "Paragraph 2 text"],
    "bullets": ["Bullet point 1", "Bullet point 2"],
    "numbered": ["Step 1", "Step 2"]
  }
}
IMPORTANT: Write actual content, NOT descriptions of formatting. 3-5 paragraphs, 3-5 bullets. Use Russian language. No markdown."""
            val msg = Message.createUserMessage("wordgen", prompt)
            val resp = aiRepository.sendMessage(
                messages = listOf(msg),
                systemPrompt = "You generate JSON for Word documents. Write only actual content. NEVER describe colors/fonts. Output ONLY valid JSON.",
                memoryContext = ""
            )
            if (resp.isSuccess) {
                val text = resp.getOrThrow().text.trim()
                val cleaned = text.replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*```$", RegexOption.IGNORE_CASE), "")
                    .trim()
                return JSONObject(cleaned)
            }
        } catch (e: Exception) {
            println("OfficeSkill: Word content generation error: ${e.message}")
        }
        return null
    }

    /**
     * Uses AI to generate Excel content as JSON.
     * Returns JSONObject with: sheets[{name, rows[[cells]]}]
     */
    private suspend fun generateExcelContentAsJson(rawQuery: String): JSONObject? {
        try {
            val prompt = """Generate an Excel spreadsheet in Russian based on this request: "$rawQuery"
Return ONLY valid JSON object with this structure:
{
  "sheets": [
    {
      "name": "Sheet name (e.g. Январь)",
      "rows": [
        ["Header1", "Header2", "Header3"],
        ["value1", "value2", "value3"],
        ["value4", "value5", "value6"]
      ]
    }
  ]
}
IMPORTANT: Write actual data. First row is headers. Include 3-5 data rows per sheet. Use Russian language. No markdown."""
            val msg = Message.createUserMessage("excelgen", prompt)
            val resp = aiRepository.sendMessage(
                messages = listOf(msg),
                systemPrompt = "You generate JSON for Excel spreadsheets. Write only actual data. Output ONLY valid JSON.",
                memoryContext = ""
            )
            if (resp.isSuccess) {
                val text = resp.getOrThrow().text.trim()
                val cleaned = text.replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*```$", RegexOption.IGNORE_CASE), "")
                    .trim()
                return JSONObject(cleaned)
            }
        } catch (e: Exception) {
            println("OfficeSkill: Excel content generation error: ${e.message}")
        }
        return null
    }
}
