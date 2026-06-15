package com.pai.android.ui.diagrams

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.pai.android.R
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Карточка для отображения диаграммы с кнопками управления.
 */
@Composable
fun DiagramCard(
    diagramCode: String,
    diagramType: DiagramType,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val diagramRenderer = remember { DiagramRenderer(context) }
    // ImageLoader с поддержкой SVG
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                add(coil.decode.SvgDecoder.Factory())
            }
            .build()
    }
    
    // Состояния для управления UI
    var imagePath by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSuccessToast by remember { mutableStateOf(false) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showFileNameDialog by remember { mutableStateOf(false) }
    var selectedFormat by remember { mutableStateOf<SaveFormat?>(null) }
    var tempRenderedPath by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    
    // Загружаем диаграмму при первом рендере
    LaunchedEffect(diagramCode, diagramType) {
        isLoading = true
        errorMessage = null
        
        diagramRenderer.renderDiagram(
            code = diagramCode,
            diagramType = diagramType,
            format = "svg",
            onSuccess = { path ->
                imagePath = path
                isLoading = false
            },
            onError = { error ->
                errorMessage = error
                isLoading = false
            }
        )
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
        ) {
            // Заголовок удалён по запросу пользователя
            
            // Область контента: загрузка, ошибка или изображение
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Рендеринг диаграммы...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    errorMessage != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Ошибка",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = errorMessage ?: "Неизвестная ошибка",
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    // Повторная попытка
                                    isLoading = true
                                    errorMessage = null
                                    imagePath = null
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Повторить"
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Повторить")
                            }
                        }
                    }
                    
                    imagePath != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(File(imagePath))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Диаграмма",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                imageLoader = imageLoader,
                                onState = { painterState ->
                                    if (painterState is AsyncImagePainter.State.Error) {
                                        errorMessage = "Ошибка загрузки изображения"
                                    }
                                }
                            )
                            
                            // Кнопки в правом верхнем углу
                            if (!isLoading && errorMessage == null) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Кнопка копирования кода
                                    IconButton(
                                        onClick = {
                                            copyToClipboard(context, diagramCode)
                                            showSuccessToast = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Копировать код",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    
                                    // Кнопка сохранения файла
                                    IconButton(
                                        onClick = {
                                            showFormatDialog = true
                                        },
                                        enabled = imagePath != null && !isSaving,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Сохранить файл",
                                            tint = if (imagePath != null) MaterialTheme.colorScheme.primary else Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Код диаграммы удалён по запросу пользователя - достаточно кнопки копирования
            
            // Диалог выбора формата сохранения
            if (showFormatDialog) {
                SaveFormatDialog(
                    onDismissRequest = { showFormatDialog = false },
                    onFormatSelected = { format ->
                        showFormatDialog = false
                        selectedFormat = format
                        // Предлагаем имя по умолчанию
                        val timestamp = System.currentTimeMillis()
                        fileName = "${diagramType.name.lowercase()}_diagram_$timestamp.${format.fileExtension}"
                        showFileNameDialog = true
                    }
                )
            }
            
            // Диалог ввода имени файла
            if (showFileNameDialog && selectedFormat != null) {
                FileNameDialog(
                    currentFileName = fileName,
                    onDismissRequest = { showFileNameDialog = false },
                    onFileNameConfirmed = { newFileName ->
                        showFileNameDialog = false
                        saveDiagramWithFormatAndName(
                            context = context,
                            diagramRenderer = diagramRenderer,
                            diagramCode = diagramCode,
                            diagramType = diagramType,
                            format = selectedFormat!!,
                            fileName = newFileName,
                            currentImagePath = imagePath,
                            onSavingStart = { isSaving = true },
                            onSavingComplete = { isSaving = false },
                            onError = { error ->
                                errorMessage = "Ошибка сохранения: $error"
                            }
                        )
                    }
                )
            }
        }
    }
}

/**
 * Копирует текст в буфер обмена.
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Код диаграммы", text)
    clipboard.setPrimaryClip(clip)
}


@Composable
private fun FileNameDialog(
    currentFileName: String,
    onDismissRequest: () -> Unit,
    onFileNameConfirmed: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var fileName by remember { mutableStateOf(currentFileName) }
    
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Введите имя файла",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Имя файла") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    text = "Файл будет сохранён в папке Downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (fileName.isNotBlank()) {
                        onFileNameConfirmed(fileName)
                    }
                },
                enabled = fileName.isNotBlank()
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text("Отмена")
            }
        },
        modifier = modifier.width(400.dp)
    )
}

/**
 * Сохраняет диаграмму в выбранном формате с указанным именем файла.
 */
private fun saveDiagramWithFormatAndName(
    context: Context,
    diagramRenderer: DiagramRenderer,
    diagramCode: String,
    diagramType: DiagramType,
    format: SaveFormat,
    fileName: String,
    currentImagePath: String?,
    onSavingStart: () -> Unit,
    onSavingComplete: () -> Unit,
    onError: (String) -> Unit
) {
    // Если формат SVG и уже есть SVG файл, просто копируем его
    if (format == SaveFormat.SVG && currentImagePath != null) {
        saveDiagramToDownloadsWithName(context, currentImagePath, fileName)
        onSavingComplete()
        return
    }
    
    onSavingStart()
    
    // Определяем формат для API
    val apiFormat = when (format) {
        SaveFormat.SVG -> "svg"
        SaveFormat.PNG -> "png"
        else -> "svg" // Резервный формат
    }
    
    // Рендерим диаграмму в выбранном формате
    diagramRenderer.renderDiagram(
        code = diagramCode,
        diagramType = diagramType,
        format = apiFormat,
        onSuccess = { renderedPath ->
            try {
                saveDiagramToDownloadsWithName(context, renderedPath, fileName)
                onSavingComplete()
            } catch (e: Exception) {
                onError("Ошибка сохранения файла: ${e.message}")
                onSavingComplete()
            }
        },
        onError = { error ->
            onError("Ошибка рендеринга: $error")
            onSavingComplete()
        }
    )
}

/**
 * Сохраняет файл в публичную папку Downloads с указанным именем.
 * Для Android < 29 использует прямой доступ к файлам.
 * Для Android >= 29 использует MediaStore API.
 */
private fun saveDiagramToDownloadsWithName(context: Context, sourceFilePath: String, fileName: String) {
    try {
        val sourceFile = File(sourceFilePath)
        
        // Убедимся, что имя файла имеет правильное расширение
        val finalFileName = if (fileName.contains('.')) fileName else "$fileName.${sourceFile.extension}"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+): используем MediaStore
            saveFileViaMediaStore(context, sourceFile, finalFileName)
        } else {
            // Android < 10: используем прямой доступ к файловой системе
            saveFileViaFileSystem(context, sourceFile, finalFileName)
        }
        
        // Показываем уведомление об успешном сохранении (можно реализовать через Toast)
        // Toast.makeText(context, "Диаграмма сохранена: $finalFileName", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        throw e
    }
}

/**
 * Сохраняет файл через MediaStore (Android 10+).
 */
@Suppress("DEPRECATION")
private fun saveFileViaMediaStore(context: Context, sourceFile: File, fileName: String) {
    val resolver = context.contentResolver
    val contentValues = android.content.ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(fileName))
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        ?: throw Exception("Не удалось создать файл в MediaStore")
    
    resolver.openOutputStream(uri)?.use { outputStream ->
        sourceFile.inputStream().use { inputStream ->
            inputStream.copyTo(outputStream)
        }
    } ?: throw Exception("Не удалось открыть поток для записи")
}

/**
 * Сохраняет файл через прямую файловую систему (Android < 10).
 */
@Suppress("DEPRECATION")
private fun saveFileViaFileSystem(context: Context, sourceFile: File, fileName: String) {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!downloadsDir.exists()) {
        downloadsDir.mkdirs()
    }
    
    val destFile = File(downloadsDir, fileName)
    sourceFile.copyTo(destFile, overwrite = true)
}

/**
 * Определяет MIME-тип по расширению файла.
 */
private fun getMimeType(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}