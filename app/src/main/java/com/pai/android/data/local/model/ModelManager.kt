package com.pai.android.data.local.model

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class LocalModelInfo(
    val id: String,
    val displayName: String,
    val sizeMb: Int,
    val minRamMb: Int,
    val requiresGpu: Boolean,
    val downloadUrl: String
) {
    companion object {
        private const val LITERT_COMMUNITY = "https://huggingface.co/litert-community"
        val AVAILABLE_MODELS = listOf(
            LocalModelInfo("gemma-4-e2b", "Gemma 4 E2B (2.6 ГБ)", 2660, 8192, true,
                "$LITERT_COMMUNITY/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"),
            LocalModelInfo("gemma-4-e4b", "Gemma 4 E4B (3.6 ГБ)", 3600, 12288, true,
                "$LITERT_COMMUNITY/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"),
            LocalModelInfo("qwen3-4b-mixed-int4", "Qwen3 4B (int4, ~4 ГБ)", 4000, 6144, false,
                "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm"),
            LocalModelInfo("qwen3-4b-int8", "Qwen3 4B (int8, ~8 ГБ)", 8000, 8192, false,
                "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_channelwise_int8_float32kv.litertlm"),
            LocalModelInfo("qwen3-0.6b-mixed-int4", "Qwen3 0.6B (int4, ~300 МБ)", 300, 2048, false,
                "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm")
        )
        fun fromId(id: String): LocalModelInfo? = AVAILABLE_MODELS.find { it.id == id }
    }
}

data class CompatibilityResult(
    val compatible: Boolean,
    val hasEnoughRam: Boolean,
    val hasGpu: Boolean,
    val hasDiskSpace: Boolean,
    val details: String
) {
    companion object {
        val UNKNOWN = CompatibilityResult(false, false, false, false, "Совместимость не проверена")
    }
}

class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "litemodels"
        private const val DISK_SPACE_MULTIPLIER = 2.0
    }

    fun checkCompatibility(modelInfo: LocalModelInfo): CompatibilityResult {
        val ramInfo = checkRam(modelInfo)
        val gpuInfo = checkGpu()
        val diskInfo = checkDiskSpace(modelInfo)
        val compatible = ramInfo && gpuInfo && diskInfo
        val details = buildString {
            append("RAM: ${formatRam(getTotalRamMb())}}/${modelInfo.minRamMb} МБ ${passFail(ramInfo)}\n")
            append("GPU: ${if (gpuInfo) "Доступен" else "Недоступен"} ${passFail(gpuInfo)}\n")
            append("Место: ${formatDisk(getFreeDiskMb())}/${modelInfo.sizeMb} МБ ${passFail(diskInfo)}")
        }
        return CompatibilityResult(compatible, ramInfo, gpuInfo, diskInfo, details)
    }

    fun getModelDir(): File {
        val baseDir = context.filesDir
        val safeBase = if (baseDir.absolutePath.contains("/dev/null")) context.cacheDir else baseDir
        val dir = File(safeBase, MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isModelDownloaded(modelId: String): Boolean = File(getModelDir(), modelIdToFilename(modelId)).exists()

    fun getModelPath(modelId: String): File? {
        val file = File(getModelDir(), modelIdToFilename(modelId))
        return if (file.exists()) file else null
    }

    suspend fun downloadModel(
        modelInfo: LocalModelInfo,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val targetFile = File(getModelDir(), modelIdToFilename(modelInfo.id))
            if (targetFile.exists()) return@withContext Result.success(targetFile)
            Log.i(TAG, "Скачивание ${modelInfo.id} из ${modelInfo.downloadUrl}")
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES).build()
            val resp = client.newCall(Request.Builder().url(modelInfo.downloadUrl).build()).execute()
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            FileOutputStream(targetFile).use { out ->
                val buf = ByteArray(8 * 1024); var total = 0L; var lastPct = -1f
                var bytes = resp.body!!.byteStream().read(buf)
                while (bytes >= 0) {
                    out.write(buf, 0, bytes); total += bytes
                    val pct = total.toFloat() / resp.body!!.contentLength()
                    if (pct - lastPct >= 0.02f) { onProgress(pct); lastPct = pct }
                    bytes = resp.body!!.byteStream().read(buf)
                }
            }
            onProgress(1f); Result.success(targetFile)
        } catch (e: Exception) { Log.e(TAG, "Ошибка скачивания", e); Result.failure(e) }
    }

    fun deleteModel(modelId: String): Boolean = File(getModelDir(), modelIdToFilename(modelId)).delete()

    private fun modelIdToFilename(id: String) = id.replace("-", "_") + ".litertlm"
    private fun checkRam(m: LocalModelInfo) = getTotalRamMb() >= m.minRamMb
    private fun checkGpu(): Boolean = true
    private fun checkDiskSpace(m: LocalModelInfo) = getFreeDiskMb() >= m.sizeMb * DISK_SPACE_MULTIPLIER
    private fun getTotalRamMb(): Int = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let {
        val mi = ActivityManager.MemoryInfo(); it.getMemoryInfo(mi); (mi.totalMem / (1024 * 1024)).toInt()
    }
    private fun getFreeDiskMb(): Long = StatFs(context.cacheDir.path).let {
        it.availableBlocksLong * it.blockSizeLong / (1024 * 1024)
    }
    private fun passFail(ok: Boolean) = if (ok) "✅" else "❌"
    private fun formatRam(mb: Int) = if (mb >= 1024) "%.1f".format(mb / 1024.0) + " ГБ" else "$mb МБ"
    private fun formatDisk(mb: Long) = if (mb >= 1024) "%.1f".format(mb / 1024.0) + " ГБ" else "$mb МБ"
}
