package com.pai.android.data.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.pai.android.data.model.Attachment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Сервис для работы с камерой устройства через CameraX.
 * Управляет предпросмотром, захватом изображений и сохранением.
 */
@Singleton
class CameraService @Inject constructor(
    private val context: Context
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService? = null
    private fun getOrCreateExecutor(): ExecutorService {
        return cameraExecutor?.takeIf { !it.isShutdown } ?: Executors.newSingleThreadExecutor().also {
            cameraExecutor = it
        }
    }
    
    /**
     * Инициализирует камеру и настраивает предпросмотр.
     * @param previewView View для отображения предпросмотра
     * @param lifecycleOwner Владелец жизненного цикла (обычно Fragment или Activity)
     * @param cameraSelector Селектор камеры (по умолчанию задняя)
     */
    suspend fun initializeCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    ) = withContext(Dispatchers.Main) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = suspendCancellableCoroutine { continuation ->
            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()
                continuation.resume(provider)
            }, ContextCompat.getMainExecutor(context))
        }
        
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        
        try {
            // Unbind use cases before rebinding
            cameraProvider?.unbindAll()
            
            // Bind use cases to camera
            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
        } catch (exc: Exception) {
            throw RuntimeException("Ошибка инициализации камеры", exc)
        }
    }
    
    /**
     * Захватывает изображение и сохраняет его в файл.
     * @return URI сохранённого файла изображения
     */
    suspend fun captureImage(): Uri = withContext(Dispatchers.IO) {
        val imageCapture = imageCapture ?: throw IllegalStateException("Камера не инициализирована")
        
        // Создаём файл для сохранения
        val photoFile = createImageFile()
        
        return@withContext suspendCancellableCoroutine { continuation ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            
            imageCapture.takePicture(
                outputOptions,
                getOrCreateExecutor(),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val savedUri = outputFileResults.savedUri ?: Uri.fromFile(photoFile)
                        continuation.resume(savedUri)
                    }
                    
                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(
                            RuntimeException("Ошибка захвата изображения", exception)
                        )
                    }
                }
            )
        }
    }
    
    /**
     * Захватывает изображение и возвращает его как Bitmap.
     * Внимание: Bitmap может быть большим, используйте с осторожностью.
     */
    suspend fun captureImageAsBitmap(): Bitmap = withContext(Dispatchers.IO) {
        val imageCapture = imageCapture ?: throw IllegalStateException("Камера не инициализирована")
        
        return@withContext suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                getOrCreateExecutor(),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                        // Конвертируем ImageProxy в Bitmap (упрощённо)
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        image.close()
                        
                        continuation.resume(bitmap)
                    }
                    
                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(
                            RuntimeException("Ошибка захвата изображения", exception)
                        )
                    }
                }
            )
        }
    }
    
    /**
     * Переключает между передней и задней камерой.
     */
    suspend fun switchCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner
    ) = withContext(Dispatchers.Main) {
        val currentSelector = getCurrentCameraSelector()
        val newSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        initializeCamera(previewView, lifecycleOwner, newSelector)
    }
    
    /**
     * Возвращает текущий селектор камеры.
     */
    fun getCurrentCameraSelector(): CameraSelector? {
        return cameraProvider?.let { provider ->
            val cameraInfo = provider.availableCameraInfos.firstOrNull() ?: return null
            cameraInfo.cameraSelector
        }
    }
    
    /**
     * Освобождает ресурсы камеры.
     */
    fun releaseCamera() {
        // Не закрываем executor, чтобы избежать RejectedExecutionException
        // cameraExecutor?.shutdown()
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
    }
    
    /**
     * Создаёт временный файл для сохранения изображения.
     */
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = context.getExternalFilesDir("images") ?: context.filesDir
        
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        ).apply {
            createNewFile()
        }
    }
    
    /**
     * Получает URI последнего сохранённого изображения из галереи.
     */
    suspend fun getLatestImageUri(): Uri? = withContext(Dispatchers.IO) {
        val projection = arrayOf(android.provider.MediaStore.Images.Media._ID)
        val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
        
        val cursor = context.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID))
                Uri.withAppendedPath(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
            } else {
                null
            }
        }
    }
    
    /**
     * Захватывает изображение и возвращает его в формате base64.
     * @return Base64 строка изображения в формате JPEG
     */
    suspend fun captureImageAsBase64(): String = withContext(Dispatchers.IO) {
        val bitmap = captureImageAsBitmap()
        
        return@withContext suspendCancellableCoroutine { continuation ->
            try {
                // Сжимаем Bitmap в JPEG с качеством 85%
                val outputStream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outputStream)
                val byteArray = outputStream.toByteArray()
                
                // Конвертируем в base64
                val base64 = android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
                
                // Освобождаем память
                bitmap.recycle()
                outputStream.close()
                
                continuation.resume(base64)
            } catch (e: Exception) {
                continuation.resumeWithException(
                    RuntimeException("Ошибка преобразования изображения в base64", e)
                )
            }
        }
    }
    
    /**
     * Захватывает изображение и возвращает объект Attachment.
     * @return Attachment с изображением в base64
     */
    suspend fun captureImageAsAttachment(messageId: String): Attachment = withContext(Dispatchers.IO) {
        val base64 = captureImageAsBase64()
        val fileName = "camera_${System.currentTimeMillis()}.jpg"
        
        return@withContext Attachment.createImageAttachment(
            messageId = messageId,
            fileName = fileName,
            mimeType = "image/jpeg",
            contentBase64 = base64
        )
    }
}