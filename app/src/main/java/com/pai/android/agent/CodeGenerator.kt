package com.pai.android.agent

import com.pai.android.data.model.Message
import com.pai.android.data.repository.AiRepository
import com.pai.android.agent.skills.PythonSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CodeGenerator @Inject constructor(
    private val aiRepository: AiRepository,
    private val fileManager: FileManager,
    private val skillRegistry: SkillRegistry
) {
    suspend fun generate(
        stepDescription: String,
        projectContext: String = "",
        existingFiles: String = "",
        projectDir: String = ""
    ): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val fileContents = StringBuilder()
                var foundFiles = false
                val targetPaths = mutableListOf<String>()

                try {
                    if (projectDir.isNotBlank()) {
                        val files = fileManager.listFiles(projectDir)
                        for (fi in files) {
                            if (fi.isDirectory) continue
                            val content = fileManager.readFile(fi.path)
                            if (content != null) {
                                fileContents.append("\n--- ").append(fi.path).append(" ---\n")
                                fileContents.append(content)
                                if (!content.endsWith("\n")) fileContents.append("\n")
                                targetPaths.add(fi.path)
                                foundFiles = true
                            }
                        }
                    }
                    if (!foundFiles && existingFiles.isNotBlank()) {
                        fileContents.append(existingFiles)
                        val pathPattern = Regex("""projects/[\w/\-]+\.[\w]+""")
                        targetPaths.addAll(pathPattern.findAll(stepDescription).map { it.value }.distinct())
                    }
                } catch (e: Exception) {
                    if (existingFiles.isNotBlank()) fileContents.append(existingFiles)
                }

                val hasPython = try { skillRegistry.getSkill("python") != null } catch (_: Exception) { false }

                val prompt = buildString {
                    append("TASK: ").append(stepDescription).append("\n")
                    if (projectContext.isNotBlank()) append("CONTEXT: ").append(projectContext).append("\n")
                    append("\n")
                    if (fileContents.isNotEmpty()) {
                        append("EXISTING CODE:\n").append(fileContents).append("\n")
                        append("INSTRUCTIONS:\n")
                        append("1. Read the EXISTING CODE above.\n")
                        append("2. MODIFY it to fulfill the TASK.\n")
                        append("3. Output each file as: --- path/to/file.extension --- followed by COMPLETE code.\n")
                        append("4. Include ENTIRE file — no placeholders, no \"...rest unchanged\".\n")
                    } else {
                        append("INSTRUCTIONS:\n")
                        append("1. Create the necessary file(s) for the TASK.\n")
                        append("2. Output: --- path/to/file.extension --- followed by COMPLETE code.\n")
                    }
                    if (hasPython) {
                        append("\nIMPORTANT: Python execution is available!\n")
                        append("If the task asks to RUN or EXECUTE the script, you MUST add an exec block.\n")
                        append("Format: --- exec: python3 ").append(projectDir.takeIf { it.isNotBlank() } ?: "path/to").append("/filename.py ---\n")
                        append("The system will execute it and include the output.\n")
                    }
                }

                println("🎯 CodeGenerator: generating for step...")

                val sysPrompt = buildString {
                    append("You are a code generator. Output file blocks (--- path ---) and exec blocks (--- exec: ---). ")
                    append("Each file block has complete code. No placeholders. ")
                    if (hasPython) append("If the user asks to run/execute the script, ALWAYS add --- exec: python3 path --- after the file. ")
                    append("Execute blocks run the code and show results. ")
                }

                val response = aiRepository.sendMessage(
                    messages = listOf(Message.createUserMessage("codegen", prompt)),
                    systemPrompt = sysPrompt,
                    memoryContext = ""
                )

                if (!response.isSuccess)
                    return@withContext Pair(false, "AI call failed: ${response.exceptionOrNull()?.message}")

                val text = response.getOrThrow().text.trim()
                if (text.isBlank()) return@withContext Pair(false, "Empty response from AI")

                // Parse file blocks: --- path/to/file.extension ---
                val fileRegex = Regex("""^---\s+(.+?)\.(\w+)\s+---\s*$""", setOf(RegexOption.MULTILINE))
                val fileMatches = fileRegex.findAll(text).toList()

                // Parse exec blocks: --- exec: command ---
                val execRegex = Regex("""^---\s+exec:\s*(.+?)\s+---\s*$""", setOf(RegexOption.MULTILINE))
                val execMatches = execRegex.findAll(text).toList()

                var successCount = 0
                var failCount = 0
                val errors = mutableListOf<String>()
                val execOutputs = mutableListOf<String>()

                // Write file blocks
                if (fileMatches.isNotEmpty()) {
                    for ((index, match) in fileMatches.withIndex()) {
                        val filePath = match.groupValues[1] + "." + match.groupValues[2]
                        val cs = match.range.last + 1
                        val ce = if (index + 1 < fileMatches.size) fileMatches[index + 1].range.first else text.length
                        val content = text.substring(cs, ce).trim()
                        if (content.isBlank()) { errors.add("$filePath: empty"); failCount++; continue }
                        if (fileManager.writeFile(filePath, content)) {
                            println("✅ CodeGenerator wrote: $filePath (${content.length} chars)")
                            successCount++
                        } else { errors.add("$filePath: write failed"); failCount++ }
                    }
                } else {
                    val defaultPath = targetPaths.firstOrNull() ?: "projects/output.html"
                    if (fileManager.writeFile(defaultPath, text)) {
                        println("✅ CodeGenerator wrote: $defaultPath (${text.length} chars)")
                        successCount++
                    } else { errors.add("$defaultPath: write failed"); failCount++ }
                }

                // Execute exec blocks via PythonSkill
                val pySkill = try { skillRegistry.getSkill("python") } catch (_: Exception) { null }
                for (execMatch in execMatches) {
                    val cmd = execMatch.groupValues[1].trim()
                    if (cmd.startsWith("python3") || cmd.startsWith("python")) {
                        if (pySkill != null && com.pai.android.agent.skills.PythonSkill.enabled) {
                            try {
                                // Extract file path from command and read its content
                                val pyPath = cmd.substringAfter("python3 ").substringAfter("python ").trim()
                                val pyCode = if (pyPath.isNotBlank()) {
                                    try { fileManager.readFile(pyPath) ?: cmd } catch (_: Exception) { cmd }
                                } else cmd
                                println("⚡ CodeGenerator executing Python: $pyPath (${pyCode.length} chars)")
                                val result = pySkill.execute(mapOf("code" to pyCode))
                                when (result) {
                                    is SkillResult.Success -> {
                                        val out = result.data?.get("output") as? String ?: result.message
                                        execOutputs.add("$ $cmd\n$out")
                                    }
                                    is SkillResult.Error -> execOutputs.add("$ $cmd\n[Error] ${result.message}")
                                    else -> {}
                                }
                            } catch (e: Exception) {
                                execOutputs.add("$ $cmd\n[Failed] ${e.message}")
                            }
                        } else {
                            execOutputs.add("$ $cmd\n[Skip] Python skill disabled or missing")
                        }
                    }
                }

                val resultMsg = buildString {
                    if (successCount > 0) append("✅ Written $successCount file(s)")
                    if (failCount > 0) append(" ⚠️ $failCount failed: ${errors.joinToString("; ")}")
                    if (execOutputs.isNotEmpty()) {
                        append("\n\n=== Execution Results ===\n")
                        append(execOutputs.joinToString("\n\n"))
                    }
                }

                Pair(failCount == 0 || successCount > 0, resultMsg)
            } catch (e: Exception) {
                println("❌ CodeGenerator error: ${e.message}")
                Pair(false, "Error: ${e.message}")
            }
        }
    }
}
