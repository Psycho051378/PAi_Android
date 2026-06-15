package com.pai.android.agent

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Singleton

/**
 * File system manager for the agent.
 * Manages workspace directory and file operations.
 */
@Singleton
class FileManager constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        const val WORKSPACE_DIR = "workspace"
        const val DOCUMENTS_DIR = "documents"
        const val REPORTS_DIR = "reports"
        const val DATA_DIR = "data"
        const val TEMP_DIR = "temp"
    }
    
    val workspaceRoot: File
        get() = File(context.getExternalFilesDir(null), WORKSPACE_DIR)
    
    fun initWorkspace() {
        ensureDirectory(workspaceRoot)
        ensureDirectory(getDocumentsDir())
        ensureDirectory(getReportsDir())
        ensureDirectory(getDataDir())
        ensureDirectory(getTempDir())
        
        val readmeFile = File(workspaceRoot, "README.md")
        if (!readmeFile.exists()) {
            writeFile(
                relativePath = "README.md",
                content = "# Workspace\n\nThis is the workspace directory for the AI assistant.\n\n## Structure\n- `documents/` - created documents\n- `reports/` - analysis reports\n- `data/` - structured data\n- `temp/` - temporary files\n- `projects/` - project folders\n"
            )
        }
    }
    
    private fun ensureDirectory(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }
    
    fun getDocumentsDir(): File = File(workspaceRoot, DOCUMENTS_DIR)
    fun getReportsDir(): File = File(workspaceRoot, REPORTS_DIR)
    fun getDataDir(): File = File(workspaceRoot, DATA_DIR)
    fun getTempDir(): File = File(workspaceRoot, TEMP_DIR)
    
    fun createDirectory(relativePath: String): File? {
        return try {
            val normalized = normalizePath(relativePath)
            val dir = File(workspaceRoot, normalized)
            ensureDirectory(dir)
            dir
        } catch (e: Exception) {
            null
        }
    }
    
    fun writeFile(relativePath: String, content: String, append: Boolean = false): Boolean {
        return try {
            val normalized = normalizePath(relativePath)
            val file = File(workspaceRoot, normalized)
            ensureDirectory(file.parentFile)
            if (append) {
                FileWriter(file, true).use { it.write(content) }
            } else {
                file.writeText(content, Charsets.UTF_8)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun readFile(relativePath: String): String? {
        return try {
            val normalized = normalizePath(relativePath)
            val file = File(workspaceRoot, normalized)
            if (file.exists() && file.isFile) {
                file.readText(Charsets.UTF_8)
            } else {
                // Try in incoming/ subdirectory
                val incomingFile = File(File(workspaceRoot, "incoming"), normalized)
                if (incomingFile.exists() && incomingFile.isFile) {
                    incomingFile.readText(Charsets.UTF_8)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    fun delete(relativePath: String, recursive: Boolean = true): Boolean {
        return try {
            val normalized = normalizePath(relativePath)
            val file = File(workspaceRoot, normalized)
            if (file.isDirectory) {
                if (recursive) file.deleteRecursively() else file.delete()
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun move(sourcePath: String, targetPath: String, overwrite: Boolean = false): Boolean {
        return try {
            val sourceFile = File(workspaceRoot, sourcePath)
            val targetFile = File(workspaceRoot, targetPath)
            if (!sourceFile.exists()) return false
            if (targetFile.exists() && !overwrite) return false
            ensureDirectory(targetFile.parentFile)
            sourceFile.renameTo(targetFile)
        } catch (e: Exception) {
            false
        }
    }
    
    fun listFiles(relativePath: String = "", recursive: Boolean = false): List<FileInfo> {
        val normalized = normalizePath(relativePath)
        val dir = if (normalized.isBlank()) workspaceRoot else File(workspaceRoot, normalized)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = mutableListOf<FileInfo>()
        val children = dir.listFiles() ?: return emptyList()
        for (file in children) {
            try {
                val fileRelativePath = file.relativeTo(workspaceRoot).path
                files.add(
                    FileInfo(
                        path = fileRelativePath,
                        name = file.name,
                        size = file.length(),
                        isDirectory = file.isDirectory,
                        lastModified = file.lastModified()
                    )
                )
                if (recursive && file.isDirectory) {
                    files.addAll(listFiles(fileRelativePath, true))
                }
            } catch (_: Exception) {
                // Skip files/dirs that cause errors (permission, encoding, etc.)
                continue
            }
        }
        return files
    }
    
    fun analyzeDirectory(relativePath: String = ""): DirectoryAnalysis {
        val files = listFiles(relativePath, recursive = true)
        val byExtension = files.filter { !it.isDirectory }
        val fileTypes = byExtension.groupBy { it.name.substringAfterLast('.', "no_ext") }
            .mapValues { it.value.size }
        val totalSize = byExtension.sumOf { it.size }
        val lastModified = files.maxOfOrNull { it.lastModified } ?: 0L
        val analysisPath = if (relativePath.isBlank()) "workspace" else relativePath
        return DirectoryAnalysis(
            path = analysisPath,
            totalFiles = byExtension.size,
            totalDirectories = files.count { it.isDirectory },
            totalSizeBytes = totalSize,
            fileTypes = fileTypes,
            lastModified = lastModified
        )
    }
    
    fun getFullFile(relativePath: String): File? {
        val normalized = normalizePath(relativePath)
        val file = File(workspaceRoot, normalized)
        return if (file.exists()) file else null
    }
    
    fun currentDir(): String = ""
    
    fun quickList(maxFiles: Int = 20): String {
        val files = listFiles(currentDir(), recursive = false)
        if (files.isEmpty()) return "пусто"
        val displayList = files.take(maxFiles).map { file ->
            if (file.isDirectory) "${file.name}/" else file.name
        }
        val result = displayList.joinToString(", ")
        if (files.size > maxFiles) {
            return "$result, ... (ещё ${files.size - maxFiles})"
        }
        return result
    }

    /**
     * Normalizes a relative path: strips leading / and .
     */
    private fun normalizePath(path: String): String {
        var p = path.trim()
        if (p == "/" || p == ".") return ""
        while (p.startsWith("/") || p.startsWith(".")) {
            p = p.removePrefix("/").removePrefix(".")
        }
        return p.trimStart('/')
    }

    /**
     * Generates a directory tree structure (linux tree style).
     */
    fun generateTree(relativePath: String = "", maxDepth: Int = 5): String {
        val normalized = normalizePath(relativePath)
        val rootDir = if (normalized.isBlank()) workspaceRoot else File(workspaceRoot, normalized)
        if (!rootDir.exists()) return "[Path not found: $relativePath]"
        val sb = StringBuilder()
        sb.append(rootDir.name).append("/\n")
        buildTree(rootDir, "", sb, maxDepth, 0)
        return sb.toString()
    }

    private fun buildTree(dir: File, prefix: String, sb: StringBuilder, maxDepth: Int, depth: Int) {
        if (depth > maxDepth) {
            sb.append(prefix).append("└── ...\n")
            return
        }
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (i in children.indices) {
            val isLast = i == children.lastIndex
            val connector = if (isLast) "└── " else "├── "
            val nextPrefix = if (isLast) "$prefix    " else "$prefix│   "
            val file = children[i]
            if (file.isDirectory) {
                sb.append(prefix).append(connector).append(file.name).append("/\n")
                buildTree(file, nextPrefix, sb, maxDepth, depth + 1)
            } else {
                val size = when {
                    file.length() < 1024 -> "${file.length()} B"
                    file.length() < 1024 * 1024 -> "${file.length() / 1024} KB"
                    else -> "${file.length() / (1024 * 1024)} MB"
                }
                sb.append(prefix).append(connector).append(file.name).append(" ($size)\n")
            }
        }
    }

    /**
     * Reads ALL files in a directory recursively and returns content as Markdown.
     */
    fun readDirectory(relativePath: String = "", maxFileSize: Long = 50000, maxTotalChars: Int = 30000): String {
        val normalized = normalizePath(relativePath)
        val rootDir = if (normalized.isBlank()) workspaceRoot else File(workspaceRoot, normalized)
        if (!rootDir.exists()) return "[Path not found: $relativePath]"
        val files = listFiles(normalized, recursive = true)
        val sb = StringBuilder()
        var totalChars = 0
        var skippedCount = 0
        for (fi in files) {
            if (fi.isDirectory) continue
            if (fi.size > maxFileSize) { skippedCount++; continue }
            val content = readFile(fi.path) ?: continue
            val header = "--- ${fi.path} (${fi.sizeFormatted}) ---\n"
            if (totalChars + header.length + content.length > maxTotalChars) { skippedCount++; break }
            sb.append(header)
            sb.append(content)
            if (!content.endsWith("\n")) sb.append("\n")
            sb.append("\n")
            totalChars += header.length + content.length
        }
        if (skippedCount > 0) sb.append("... and $skippedCount more files (skipped due to size)\n")
        return sb.toString()
    }

    fun createReport(content: String, format: String = "md", category: String = "analysis"): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val filename = "report_${category}_$timestamp.$format"
        val relativePath = "$REPORTS_DIR/$filename"
        if (writeFile(relativePath, content)) {
            return relativePath
        }
        throw IllegalStateException("Failed to create report")
    }

    fun getFileInfo(relativePath: String): FileInfo? {
        val normalized = normalizePath(relativePath)
        val file = File(workspaceRoot, normalized)
        if (!file.exists()) return null
        return FileInfo(
            path = relativePath,
            name = file.name,
            size = if (file.isDirectory) 0 else file.length(),
            isDirectory = file.isDirectory,
            lastModified = file.lastModified()
        )
    }
}

/**
 * Information about a file or directory.
 */
data class FileInfo(
    val path: String,
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long
) {
    val sizeFormatted: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    
    val lastModifiedFormatted: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastModified))
}

/**
 * Directory analysis result.
 */
data class DirectoryAnalysis(
    val path: String,
    val totalFiles: Int,
    val totalDirectories: Int,
    val totalSizeBytes: Long,
    val fileTypes: Map<String, Int>,
    val lastModified: Long
) {
    val totalSizeFormatted: String
        get() = when {
            totalSizeBytes < 1024 -> "$totalSizeBytes B"
            totalSizeBytes < 1024 * 1024 -> "${totalSizeBytes / 1024} KB"
            totalSizeBytes < 1024 * 1024 * 1024 -> "${totalSizeBytes / (1024 * 1024)} MB"
            else -> "${totalSizeBytes / (1024 * 1024 * 1024)} GB"
        }

    fun getSummary(): String {
        return """
        Path: $path
        Files: $totalFiles
        Directories: $totalDirectories
        Total size: $totalSizeFormatted
        File types: ${fileTypes.entries.joinToString { "${it.key}: ${it.value}" }}
        """.trimIndent()
    }
}
