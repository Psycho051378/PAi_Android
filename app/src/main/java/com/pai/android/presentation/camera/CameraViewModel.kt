package com.pai.android.presentation.camera

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.service.CameraService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel для управления камерой и захватом изображений.
 * Обрабатывает состояние камеры, ошибки и результаты захвата.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraService: CameraService
) : ViewModel() {
    
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()
    
    /**
     * Инициализирует камеру и настраивает предпросмотр.
     * @param previewView View для отображения предпросмотра
     * @param lifecycleOwner Владелец жизненного цикла
     * @param cameraSelector Селектор камеры (по умолчанию задняя)
     */
    fun initializeCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    ) {
        if (_state.value.isInitialized) return
        
        viewModelScope.launch {
            try {
                _state.update { it.copy(isLoading = true, error = null) }
                
                cameraService.initializeCamera(previewView, lifecycleOwner, cameraSelector)
                
                _state.update { it.copy(
                    isInitialized = true,
                    isLoading = false,
                    currentCamera = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                        CameraType.FRONT
                    } else {
                        CameraType.BACK
                    }
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    error = "Ошибка инициализации камеры: ${e.message}",
                    isLoading = false,
                    isInitialized = false
                ) }
            }
        }
    }
    
    /**
     * Захватывает изображение и сохраняет его как base64.
     */
    fun captureImage() {
        if (!_state.value.isInitialized || _state.value.isCapturing) return
        
        viewModelScope.launch {
            try {
                _state.update { it.copy(isCapturing = true, error = null) }
                
                val imageBase64 = cameraService.captureImageAsBase64()
                
                _state.update { it.copy(
                    lastCapturedBase64 = imageBase64,
                    lastCapturedUri = null, // Очищаем URI, так как теперь используем base64
                    isCapturing = false,
                    captureCount = it.captureCount + 1
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    error = "Ошибка захвата изображения: ${e.message}",
                    isCapturing = false
                ) }
            }
        }
    }
    
    /**
     * Захватывает изображение и возвращает URI (устаревший метод, оставлен для совместимости).
     */
    fun captureImageAsUri() {
        if (!_state.value.isInitialized || _state.value.isCapturing) return
        
        viewModelScope.launch {
            try {
                _state.update { it.copy(isCapturing = true, error = null) }
                
                val imageUri = cameraService.captureImage()
                
                _state.update { it.copy(
                    lastCapturedUri = imageUri,
                    lastCapturedBase64 = null,
                    isCapturing = false,
                    captureCount = it.captureCount + 1
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    error = "Ошибка захвата изображения: ${e.message}",
                    isCapturing = false
                ) }
            }
        }
    }
    
    /**
     * Переключает между передней и задней камерой.
     */
    fun switchCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner
    ) {
        if (!_state.value.isInitialized) return
        
        viewModelScope.launch {
            try {
                _state.update { it.copy(isLoading = true, error = null) }
                
                cameraService.switchCamera(previewView, lifecycleOwner)
                
                val newCameraType = when (cameraService.getCurrentCameraSelector()) {
                    CameraSelector.DEFAULT_FRONT_CAMERA -> CameraType.FRONT
                    else -> CameraType.BACK
                }
                
                _state.update { it.copy(
                    currentCamera = newCameraType,
                    isLoading = false
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    error = "Ошибка переключения камеры: ${e.message}",
                    isLoading = false
                ) }
            }
        }
    }
    
    /**
     * Очищает последнее захваченное изображение.
     */
    fun clearLastCapture() {
        _state.update { it.copy(lastCapturedUri = null) }
    }
    
    /**
     * Очищает ошибки.
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }
    
    /**
     * Освобождает ресурсы камеры.
     */
    fun releaseCamera() {
        cameraService.releaseCamera()
        _state.update { CameraState() }
    }
    
    override fun onCleared() {
        super.onCleared()
        releaseCamera()
    }
}

/**
 * Состояние камеры.
 */
data class CameraState(
    val isInitialized: Boolean = false,
    val isLoading: Boolean = false,
    val isCapturing: Boolean = false,
    val currentCamera: CameraType = CameraType.BACK,
    val lastCapturedUri: Uri? = null,
    val lastCapturedBase64: String? = null,
    val error: String? = null,
    val captureCount: Int = 0
)

/**
 * Тип камеры.
 */
enum class CameraType {
    FRONT, BACK
}