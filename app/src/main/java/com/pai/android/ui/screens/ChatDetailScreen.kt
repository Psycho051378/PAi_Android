package com.pai.android.ui.screens

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment as UiAlignment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Removed CodeView dependency - using custom syntax highlighting
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import com.pai.android.ui.utils.CodeBlockParser
import com.pai.android.ui.utils.MarkdownRenderer
import com.pai.android.ui.diagrams.DiagramDetector
import com.pai.android.ui.diagrams.DiagramType
import com.pai.android.ui.diagrams.DiagramCard
import com.pai.android.ui.utils.gradientBackground
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import com.pai.android.data.model.Attachment
import com.pai.android.ui.utils.FilePickerHelper
import com.pai.android.ui.utils.rememberFilePickerLauncher
import com.pai.android.ui.utils.pressAnimation
import com.pai.android.ui.utils.rotateAnimation
import com.pai.android.ui.utils.softShadow
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.pai.android.data.model.Role
import com.pai.android.presentation.camera.CameraViewModel
import com.pai.android.presentation.chat.ChatDetailViewModel
import com.pai.android.presentation.voice.VoiceRecognitionViewModel
import com.pai.android.ui.navigation.Screen
import com.pai.android.ui.components.RoleDropdownMenu
import com.pai.android.ui.components.SyntaxHighlightedCode
import com.pai.android.ui.components.FactConfirmationDialog
import com.pai.android.R
import com.pai.android.ui.utils.MessagePart
import android.widget.Toast
import androidx.compose.foundation.combinedClickable



@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ChatDetailScreen(
    chatId: String,
    navController: NavController? = null,
    viewModel: ChatDetailViewModel = hiltViewModel()
) {
    val state = viewModel.state.collectAsState().value
    val voiceViewModel = hiltViewModel<VoiceRecognitionViewModel>()
    val cameraViewModel = hiltViewModel<CameraViewModel>()
    val voiceState = voiceViewModel.state.collectAsState().value
    val cameraState = cameraViewModel.state.collectAsState().value
    val recordAudioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val appContext = LocalContext.current
    
    // Ланчер для выбора файлов
    val filePickerLauncher = rememberFilePickerLauncher(
        onFileSelected = { uri ->
            scope.launch {
                try {
                    // Создаём временный messageId для вложения
                    // При отправке сообщения будет создан реальный messageId
                    val tempMessageId = "temp_${System.currentTimeMillis()}"
                    val attachment = FilePickerHelper.createAttachmentFromUri(
                        context = appContext,
                        uri = uri,
                        messageId = tempMessageId
                    )
                    viewModel.addAttachment(attachment)
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(appContext.getString(R.string.chat_detail_file_error, e.message))
                }
            }
        }
    )
    
    // Переменная для сохранения контента перед записью в файл
    var contentToSave by remember { mutableStateOf<String?>(null) }
    
    // Переменные для сохранения кода перед записью в файл
    var codeToSave by remember { mutableStateOf<String?>(null) }
    var codeLanguage by remember { mutableStateOf<String?>(null) }
    
    // Состояние для диалога тестирования памяти

    
    // Ланчер для сохранения файла
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null && contentToSave != null) {
            scope.launch {
                try {
                    // Записываем контент в выбранный URI
                    appContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(contentToSave!!.toByteArray())
                    }
                    // TODO: заменить на stringResource
                    snackbarHostState.showSnackbar("Файл сохранён: ${uri.lastPathSegment}")
                } catch (e: Exception) {
                    // TODO: заменить на stringResource
                    snackbarHostState.showSnackbar("Ошибка сохранения файла: ${e.message}")
                } finally {
                    contentToSave = null
                }
            }
        } else {
            contentToSave = null
        }
    }
    
    // Ланчер для сохранения блоков кода
    val saveCodeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null && codeToSave != null) {
            scope.launch {
                try {
                    appContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(codeToSave!!.toByteArray())
                    }
                    // TODO: заменить на stringResource
                    snackbarHostState.showSnackbar("Код сохранён: ${uri.lastPathSegment}")
                } catch (e: Exception) {
                    // TODO: заменить на stringResource
                    snackbarHostState.showSnackbar("Ошибка сохранения кода: ${e.message}")
                } finally {
                    codeToSave = null
                    codeLanguage = null
                }
            }
        } else {
            codeToSave = null
            codeLanguage = null
        }
    }
    
    // Прокручиваем список к концу при новых сообщениях
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }
    
    // Показываем ошибки в snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            scope.launch {
                snackbarHostState.showSnackbar(error)
                viewModel.clearError()
            }
        }
    }
    
    // Обработка распознанного текста из голосового ввода
    LaunchedEffect(voiceState.recognizedText) {
        val recognized = voiceState.recognizedText
        if (recognized.isNotEmpty()) {
            viewModel.updateInputText(recognized)
            // Очищаем распознанный текст после использования
            voiceViewModel.clearResult()
        }
    }
    
    // Показываем ошибки голосового ввода
    LaunchedEffect(voiceState.error) {
        voiceState.error?.let { error ->
            scope.launch {
                // TODO: заменить на stringResource
                    snackbarHostState.showSnackbar("Голосовой ввод: $error")
                voiceViewModel.clearResult()
            }
        }
    }
    
    // Обработка результата из камеры (захваченное изображение в формате URI - устаревший формат)
    LaunchedEffect(navController) {
        snapshotFlow {
            navController?.currentBackStackEntry?.savedStateHandle?.get<String?>("captured_image_uri")
        }.collect { uriString ->
            uriString?.let { uri ->
                // Обработка URI (устаревший формат)
                viewModel.updateInputText("Изображение: $uri")
                // Очищаем сохранённый URI
                navController?.currentBackStackEntry?.savedStateHandle?.set(
                    "captured_image_uri", null
                )
            }
        }
    }
    
    // Обработка base64 изображения с камеры как вложение
    LaunchedEffect(navController) {
        snapshotFlow {
            navController?.currentBackStackEntry?.savedStateHandle?.get<String?>("captured_image_base64")
        }.collect { base64 ->
            base64?.let { imageBase64 ->
                // Создаём вложение для изображения
                val tempMessageId = "temp_${System.currentTimeMillis()}"
                val attachment = Attachment.createImageAttachment(
                    messageId = tempMessageId,
                    fileName = "camera_${System.currentTimeMillis()}.jpg",
                    mimeType = "image/jpeg",
                    contentBase64 = imageBase64
                )
                viewModel.addAttachment(attachment)
                // Очищаем сохранённый base64
                navController?.currentBackStackEntry?.savedStateHandle?.set(
                    "captured_image_base64", null
                )
            }
        }
    }
    
    // Обработчики действий с сообщениями
    
    fun copyToClipboard(text: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Сообщение чата", text)
        clipboard.setPrimaryClip(clip)
        scope.launch {
            // TODO: заменить на stringResource
            snackbarHostState.showSnackbar("Сообщение скопировано")
        }
    }
    
    fun saveToFile(content: String) {
        if (content.isEmpty()) {
            scope.launch {
                // TODO: заменить на stringResource
                snackbarHostState.showSnackbar("Нет содержимого для сохранения")
            }
            return
        }
        
        // Генерируем имя файла на основе текущей даты и времени
        val timestamp = System.currentTimeMillis()
        val fileName = "сообщение_${timestamp}.txt"
        
        // Сохраняем контент для записи
        contentToSave = content
        
        // Запускаем ланчер для выбора места сохранения
        saveFileLauncher.launch(fileName)
    }
    
    fun copyCode(code: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Код", code)
        clipboard.setPrimaryClip(clip)
        scope.launch {
            // TODO: заменить на stringResource
            snackbarHostState.showSnackbar("Код скопирован в буфер обмена")
        }
    }
    
    fun saveCodeToFile(language: String, code: String) {
        if (code.isEmpty()) {
            scope.launch {
                // TODO: заменить на stringResource
                snackbarHostState.showSnackbar("Нет кода для сохранения")
            }
            return
        }
        
        // Генерируем имя файла на основе языка и времени
        val extension = CodeBlockParser.getFileExtension(language)
        val fileName = "код_${System.currentTimeMillis()}$extension"
        
        // Сохраняем код для записи
        codeToSave = code
        codeLanguage = language
        
        // Запускаем ланчер для выбора места сохранения
        saveCodeLauncher.launch(fileName)
    }
    
    fun resendMessage(message: com.pai.android.data.model.Message) {
        viewModel.resendMessage(message)
        scope.launch {
            // TODO: заменить на stringResource
            snackbarHostState.showSnackbar("Сообщение отправлено повторно")
        }
    }
    
    fun regenerateResponse(message: com.pai.android.data.model.Message) {
        viewModel.regenerateResponse(message)
        scope.launch {
            // TODO: заменить на stringResource
            snackbarHostState.showSnackbar("Ответ регенерируется...")
        }
    }
    
    fun onMessageLongClick(message: com.pai.android.data.model.Message) {
        scope.launch {
            // При долгом нажатии сохраняем тестовый факт
            viewModel.saveConfirmedFactToMemory(
                message = message,
                category = "personal_info",
                key = "long_click_test",
                value = "Сообщение сохранено при долгом нажатии: ${message.content.take(50)}..."
            )
            // TODO: заменить на stringResource
            snackbarHostState.showSnackbar("Факт сохранён в память при долгом нажатии")
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Row(
                        verticalAlignment = UiAlignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            state.chat?.title?.ifEmpty { stringResource(R.string.chat_detail_unnamed) } ?: stringResource(R.string.loading),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        RoleDropdownMenu(
                            roles = state.availableRoles,
                            selectedRole = state.selectedRole,
                            onRoleSelected = { role ->
                                viewModel.selectRole(role)
                            },
                            onManageRoles = {
                                navController?.navigate(Screen.RoleList.route)
                            },
                            modifier = Modifier.width(200.dp)
                        )
                    }
                },
                navigationIcon = {
                    if (navController != null) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
        ) {
            // Индикатор контекста + Smart Router статус
            if (state.contextUsagePercent != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = UiAlignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = (state.contextUsagePercent ?: 0) / 100f,
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = when {
                                state.contextUsagePercent!! > 85 -> Color(0xFFE53935) // Красный
                                state.contextUsagePercent!! > 60 -> Color(0xFFFB8C00) // Оранжевый
                                else -> Color(0xFF43A047) // Зелёный
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            text = state.contextLabel,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    // Индикатор Smart Router
                    Row(
                        verticalAlignment = UiAlignment.CenterVertically,
                        modifier = Modifier.padding(top = 1.dp)
                    ) {
                        val routerColor = if (state.smartRouterEnabled) Color(0xFF43A047) else Color(0xFFE53935)
                        val routerText = if (state.smartRouterEnabled) "Smart Router: вкл" else "Smart Router: выкл"
                        Text(
                            text = "● ",
                            fontSize = 8.sp,
                            color = routerColor
                        )
                        Text(
                            text = routerText,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            
            // Список сообщений
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(UiAlignment.Center))
                    }
                    state.messages.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.chat_detail_empty),
                            fontSize = 18.sp,
                            color = Color.Gray,
                            modifier = Modifier.align(UiAlignment.Center),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            reverseLayout = false
                        ) {
                            items(state.messages) { message ->
                                MessageItemWithActions(
                                    message = message,
                                    attachments = state.messageAttachments[message.id] ?: emptyList(),
                                    onCopy = ::copyToClipboard,
                                    onResend = ::resendMessage,
                                    onRegenerate = ::regenerateResponse,
                                    onSave = ::saveToFile,
                                    onCopyCode = ::copyCode,
                                    onSaveCode = ::saveCodeToFile,
                                    onLongClick = ::onMessageLongClick
                                )
                            }
                        }
                    }
                }
            }
            
            // Панель ввода с кнопками (расширяемая)
            var toolsExpanded by remember { mutableStateOf(false) }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Строка с кнопками инструментов (показывается при расширении)
                AnimatedVisibility(
                    visible = toolsExpanded,
                    enter = fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth / 2 },
                        animationSpec = tween(300)
                    ),
                    exit = fadeOut(animationSpec = tween(200)) + slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth / 2 },
                        animationSpec = tween(200)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Кнопка голосового ввода
                        // TODO: add HintText hintResId = R.string.hint_chat_detail_voice near this button
                        IconButton(
                            onClick = {
                                if (recordAudioPermission.status.isGranted) {
                                    if (voiceState.isListening) {
                                        voiceViewModel.stopListening()
                                    } else {
                                        voiceViewModel.startListening()
                                    }
                                } else {
                                    recordAudioPermission.launchPermissionRequest()
                                }
                                // После выбора инструмента сворачиваем панель
                                toolsExpanded = false
                            },
                            enabled = !state.isSending,
                            modifier = Modifier.pressAnimation()
                        ) {
                            Icon(
                                if (voiceState.isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (voiceState.isListening) stringResource(R.string.chat_detail_stop_recording) else stringResource(R.string.chat_detail_voice_input),
                                tint = if (recordAudioPermission.status.isGranted) MaterialTheme.colorScheme.onSurface 
                                       else MaterialTheme.colorScheme.error
                            )
                        }
                        
                        // Кнопка камеры
                        // TODO: add HintText hintResId = R.string.hint_chat_detail_camera near this button
                        IconButton(
                            onClick = {
                                if (cameraPermission.status.isGranted) {
                                    navController?.navigate(Screen.Camera.route)
                                } else {
                                    cameraPermission.launchPermissionRequest()
                                }
                                toolsExpanded = false
                            },
                            enabled = !state.isSending,
                            modifier = Modifier.pressAnimation()
                        ) {
                            Icon(
                                Icons.Default.Camera,
                                contentDescription = stringResource(R.string.chat_detail_camera),
                                tint = if (cameraPermission.status.isGranted) MaterialTheme.colorScheme.onSurface 
                                       else MaterialTheme.colorScheme.error
                            )
                        }
                        
                        // Кнопка прикрепления файла
                        // TODO: add HintText hintResId = R.string.hint_chat_detail_file near this button
                        IconButton(
                            onClick = { 
                                filePickerLauncher()
                                toolsExpanded = false
                            },
                            enabled = !state.isSending,
                            modifier = Modifier.pressAnimation()
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = stringResource(R.string.chat_detail_attach_file),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        // Spacer для выравнивания
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // Кнопка сворачивания панели
                        IconButton(
                            onClick = { toolsExpanded = false },
                            modifier = Modifier.pressAnimation()
                        ) {
                            Icon(
                                Icons.Default.ArrowBack,
                                // TODO: заменить на stringResource
                            contentDescription = "Свернуть панель инструментов",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                
                // Строка с полем ввода
                // Work indicator
                AnimatedVisibility(
                    visible = state.workStatus.isNotEmpty(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = UiAlignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = state.workStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.activeModelName.isNotBlank()) {
                                Text(
                                    text = state.activeModelName,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = UiAlignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Кнопка разворачивания панели инструментов
                    IconButton(
                        onClick = { toolsExpanded = !toolsExpanded },
                        enabled = !state.isSending,
                        modifier = Modifier
                            .pressAnimation()
                            .rotateAnimation(isRotated = toolsExpanded, rotationAngle = 45f)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            // TODO: заменить на stringResource
                            contentDescription = if (toolsExpanded) "Свернуть инструменты" else "Показать инструменты",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    
                    // Поле ввода (теперь занимает почти всю ширину)
                    OutlinedTextField(
                        value = state.inputText,
                        onValueChange = { viewModel.updateInputText(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.chat_detail_input_hint)) },
                        trailingIcon = {
                            if (state.isSending || state.isProcessingVoice || state.workStatus.isNotBlank()) {
                                // Кнопка отмены во время генерации ответа
                                IconButton(
                                    onClick = { viewModel.cancelSending() },
                                    modifier = Modifier.pressAnimation()
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = stringResource(R.string.cancel),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { viewModel.sendMessage() },
                                    enabled = state.inputText.trim().isNotEmpty(),
                                    modifier = Modifier.pressAnimation()
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = stringResource(R.string.chat_detail_send))
                                }
                            }
                        },
                        singleLine = false,
                        maxLines = 3
                    )
                }
            }
            
            // Отображение превью вложений
            if (state.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.attachments.forEach { attachment ->
                        AttachmentPreview(
                            attachment = attachment,
                            onRemove = { viewModel.removeAttachment(attachment.id) }
                        )
                    }
                }
            }
            
            // Диалог подтверждения факта
            if (state.factConfirmation.showDialog) {
                FactConfirmationDialog(
                    category = state.factConfirmation.category,
                    key = state.factConfirmation.key,
                    originalValue = state.factConfirmation.value,
                    confidence = state.factConfirmation.confidence,
                    scope = state.factConfirmation.scope,
                    tags = state.factConfirmation.tags,
                    onConfirm = { viewModel.confirmFact() },
                    onCorrect = { correctedValue ->
                        viewModel.updateFactCorrectionText(correctedValue)
                        viewModel.correctFact()
                    },
                    onReject = { viewModel.rejectFact() },
                    onDismiss = { viewModel.closeFactConfirmationDialog() }
                )
            }
        } // закрытие Box
    } // закрытие Scaffold
    }
}

@Composable
fun MessageItemWithActions(
    message: com.pai.android.data.model.Message,
    attachments: List<com.pai.android.data.model.Attachment> = emptyList(),
    onCopy: (String) -> Unit,
    onResend: (com.pai.android.data.model.Message) -> Unit,
    onRegenerate: (com.pai.android.data.model.Message) -> Unit,
    onSave: (String) -> Unit,
    onCopyCode: (String) -> Unit = onCopy,
    onSaveCode: (String, String) -> Unit = { _, code -> onSave(code) },
    onLongClick: (com.pai.android.data.model.Message) -> Unit = {}
) {
    val isUser = message.isFromUser()
    val backgroundColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val alignment = if (isUser) UiAlignment.CenterEnd else UiAlignment.CenterStart
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        val gradientColors = if (isUser) {
            listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.primary
            )
        } else {
            listOf(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.secondary
            )
        }
        
        Card(
    modifier = Modifier
        .fillMaxWidth(0.92f),
    colors = CardDefaults.cardColors(
        containerColor = backgroundColor
    ),
    shape = MaterialTheme.shapes.medium
) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Заголовок и действия
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = UiAlignment.CenterVertically
                ) {
                    Text(
                        text = if (isUser) stringResource(R.string.chat_detail_you) else stringResource(R.string.chat_detail_assistant),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    
                    // Кнопки действий
                    Row {
                        // TODO: add HintText hintResId = R.string.hint_chat_detail_code near code copy/save buttons
                // Кнопка копирования (для всех сообщений)
                        IconButton(
                            onClick = { onCopy(message.content) },
                            modifier = Modifier.pressAnimation().size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.chat_detail_copy),
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        
                        if (isUser) {
                            // Для пользовательских сообщений: повторная отправка
                            IconButton(
                                onClick = { onResend(message) },
                                modifier = Modifier.pressAnimation().size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Redo,
                                    contentDescription = stringResource(R.string.chat_detail_resend),
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            // Для сообщений ассистента: регенерация и сохранение
                            IconButton(
                                onClick = { onRegenerate(message) },
                                modifier = Modifier.pressAnimation().size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.chat_detail_regenerate),
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = { onSave(message.content) },
                                modifier = Modifier.pressAnimation().size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = stringResource(R.string.chat_detail_save_to_file),
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                
                // Содержимое сообщения
                val context1 = LocalContext.current
                
                // Проверяем, содержит ли сообщение диаграмму
                val diagramCode = DiagramDetector.extractDiagramCode(message.content)
                val diagramType = if (diagramCode != null) DiagramDetector.detectDiagramType(message.content) else DiagramType.UNKNOWN
                
                if (diagramCode != null && diagramType != DiagramType.UNKNOWN) {
                    // Отображаем карточку диаграммы
                    DiagramCard(
                        diagramCode = diagramCode,
                        diagramType = diagramType,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    // Отображаем обычное сообщение
                    RichMessageContent(
                        text = message.content,
                        context = context1,
                        onCopyCode = onCopyCode,
                        onSaveCode = onSaveCode,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                // Время сообщения
                if (attachments.isNotEmpty()) {
                    attachments.forEach { att ->
                        AttachmentRow(attachment = att)
                    }
                }
                Text(
                    text = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(message.timestamp)),
                    fontSize = 10.sp,
                    color = Color.LightGray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun MessageItem(
    message: com.pai.android.data.model.Message,
    onLongClick: (com.pai.android.data.model.Message) -> Unit = {}
) {
    val isUser = message.isFromUser()
    val backgroundColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val alignment = if (isUser) UiAlignment.CenterEnd else UiAlignment.CenterStart
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        val gradientColors = if (isUser) {
            listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.primary
            )
        } else {
            listOf(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.secondary
            )
        }
        
        Card(
    modifier = Modifier
        .fillMaxWidth(0.92f),
    colors = CardDefaults.cardColors(
        containerColor = backgroundColor
    ),
    shape = MaterialTheme.shapes.medium
) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isUser) stringResource(R.string.chat_detail_you) else stringResource(R.string.chat_detail_assistant),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                // Содержимое сообщения
                val context = LocalContext.current
                
                // Проверяем, содержит ли сообщение диаграмму
                val diagramCode = DiagramDetector.extractDiagramCode(message.content)
                val diagramType = if (diagramCode != null) DiagramDetector.detectDiagramType(message.content) else DiagramType.UNKNOWN
                
                if (diagramCode != null && diagramType != DiagramType.UNKNOWN) {
                    // Отображаем карточку диаграммы
                    DiagramCard(
                        diagramCode = diagramCode,
                        diagramType = diagramType,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    // Отображаем обычное сообщение
                    RichMessageContent(
                        text = message.content,
                        context = context,
                        onCopyCode = { code ->
                            // Заглушка для копирования кода в MessageItem
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Код", code)
                            clipboard.setPrimaryClip(clip)
                        },
                        onSaveCode = { language, code ->
                            // Заглушка для сохранения кода в MessageItem
                            // Ничего не делаем, так как это предпросмотр
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    text = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(message.timestamp)),
                    fontSize = 10.sp,
                    color = Color.LightGray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Отображает вложение в сообщении (read-only, с возможностью открыть).
 */
@Composable
fun AttachmentRow(attachment: com.pai.android.data.model.Attachment) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable {
                openAttachment(context, attachment)
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = UiAlignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when (attachment.type) {
                    com.pai.android.data.model.AttachmentType.IMAGE -> Icons.Default.Camera
                    com.pai.android.data.model.AttachmentType.DOCUMENT -> Icons.Default.Description
                    com.pai.android.data.model.AttachmentType.TEXT -> Icons.Default.TextFields
                    com.pai.android.data.model.AttachmentType.AUDIO -> Icons.Default.AudioFile
                    else -> Icons.Default.AttachFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = attachment.formattedSize(),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = stringResource(R.string.chat_detail_open),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Открывает вложение через Intent.
 */
private fun openAttachment(context: android.content.Context, attachment: com.pai.android.data.model.Attachment) {
    val uri = attachment.localPath?.let { path ->
        // Build candidate paths dynamically
        val candidates = mutableListOf<java.io.File>()
        candidates.add(java.io.File(path))
        // Internal files dir
        try {
            candidates.add(java.io.File(context.getFilesDir(), "workspace/" + path))
        } catch (_: Exception) {}
        // External files dir
        try {
            val ext = context.getExternalFilesDir(null)
            if (ext != null) candidates.add(java.io.File(ext, "workspace/" + path))
        } catch (_: Exception) {}
        // Try first 3 chars of package data dir
        candidates.add(java.io.File("/data/data/com.pai.android/files/workspace", path))
        candidates.add(java.io.File("/storage/emulated/0/Android/data/com.pai.android/files/workspace", path))
        
        val file = candidates.firstOrNull { f -> f.isFile() }
        if (file != null) {
            try {
                androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            } catch (_: Exception) {
                android.net.Uri.fromFile(file)
            }
        } else {
            println("openAttachment FAILED for: " + path)
            for ((i, f) in candidates.withIndex()) {
                println("  candidate[" + i + "]: " + f.getAbsolutePath() + " exists=" + f.exists())
            }
            null
        }
    }
    if (uri != null) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, context.getString(R.string.chat_detail_cant_open_file, e.getLocalizedMessage()), android.widget.Toast.LENGTH_LONG).show()
        }
    } else {
        android.widget.Toast.makeText(context, context.getString(R.string.chat_detail_file_not_found, attachment.fileName), android.widget.Toast.LENGTH_LONG).show()
    }
}

/**
 * Превью вложения (карточка с иконкой, именем файла и кнопкой удаления).
 */
@Composable
fun AttachmentPreview(
    attachment: com.pai.android.data.model.Attachment,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = UiAlignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Иконка в зависимости от типа файла
            Icon(
                imageVector = when (attachment.type) {
                    com.pai.android.data.model.AttachmentType.IMAGE -> Icons.Default.Camera
                    com.pai.android.data.model.AttachmentType.DOCUMENT -> Icons.Default.Description
                    com.pai.android.data.model.AttachmentType.TEXT -> Icons.Default.TextFields
                    com.pai.android.data.model.AttachmentType.AUDIO -> Icons.Default.AudioFile
                    else -> Icons.Default.AttachFile
                },
                contentDescription = "Тип файла: ${attachment.type.name}",
                modifier = Modifier.size(24.dp)
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName ?: stringResource(R.string.chat_detail_unnamed_file),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = "${attachment.type.name.lowercase()} • ${attachment.formattedSize()}",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
            
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.chat_detail_remove_attachment),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Отображает сообщение с поддержкой блоков кода.
 * Блоки кода выделяются в отдельные карточки с кнопками копирования и сохранения.
 */
@Composable
fun RichMessageContent(
    text: String,
    context: Context,
    onCopyCode: (String) -> Unit,
    onSaveCode: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val parts = remember(text) { CodeBlockParser.parse(text) }
    
    Column(modifier = modifier) {
        parts.forEachIndexed { index, part ->
            when (part) {
                is MessagePart.TextPart -> {
                    if (part.text.isNotBlank()) {
                        ClickableTextWithLinks(
                            text = part.text,
                            context = context,
                            modifier = Modifier.padding(bottom = if (index < parts.size - 1) 8.dp else 0.dp),
                            textSize = 16.sp
                        )
                    }
                }
                is MessagePart.CodeBlockPart -> {
                    CodeBlockCard(
                        language = part.language,
                        code = part.code,
                        context = context,
                        onCopyCode = onCopyCode,
                        onSaveCode = onSaveCode,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                else -> {
                    // This should never happen, but makes when exhaustive
                }
            }
        }
    }
}

/**
 * Карточка для отображения блока кода с кнопками копирования и сохранения.
 */
@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    context: Context,
    onCopyCode: (String) -> Unit,
    onSaveCode: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Заголовок с языком и кнопками действий
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = UiAlignment.CenterVertically
            ) {
                Text(
                    text = language.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Row {
                    // Кнопка копирования
                    IconButton(
                        onClick = { onCopyCode(code) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.chat_detail_copy_code),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    
                    // Кнопка сохранения
                    IconButton(
                        onClick = { onSaveCode(language, code) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(R.string.chat_detail_save_code),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            // Блок с кодом с подсветкой синтаксиса
            SyntaxHighlightedCode(
                code = code,
                language = language,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                isDarkTheme = isSystemInDarkTheme()
            )
        }
    }
    

}

/**
 * Отображает текст с автоматически кликабельными ссылками (URL).
 */
@Composable
fun ClickableTextWithLinks(
    text: String,
    context: Context,
    modifier: Modifier = Modifier,
    textSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    maxLines: Int = Int.MAX_VALUE
) {
    val annotatedString = remember(text) {
        // 1. Сначала рендерим MD-разметку (жирный, курсив, код, ссылки [text](url))
        val mdAnnotated = MarkdownRenderer.render(text)
        // Текст без MD-синтаксиса (только то, что реально отображается)
        val displayText = mdAnnotated.toString()
        
        // 2. Затем добавляем bare URL https://... и домены site.com поверх
        buildAnnotatedString {
            append(mdAnnotated)
            
            if (displayText.length < 10) return@buildAnnotatedString
            
            // Собираем уже существующие URL-аннотации от MarkdownRenderer
            val existingAnnotations = mdAnnotated.getStringAnnotations("URL", 0, displayText.length)
            
            // URL с протоколом: https://site.com/path
            val urlPattern = Regex("""https?://[a-zA-Z0-9а-яА-ЯёЁ./?=&%_#@!~()-]+""")
            for (match in urlPattern.findAll(displayText)) {
                var url = match.value
                val origStart = match.range.first
                
                // 1. Убираем пунктуацию с начала (скобки, кавычки)
                url = url.trimStart('(', '.', ',', '\'', '"', '«')
                val trimStart = match.value.length - url.length
                
                // 2. Убираем пунктуацию с конца (точки, запятые, восклицательные/вопросит. знаки)
                url = url.trimEnd('.', ',', '!', '?', ';', ':', '\'', '"', '»', '«')
                // 3. Балансируем скобки: если закрывающих больше — отрезаем лишние
                while (url.count { it == '(' } < url.count { it == ')' }) {
                    url = url.dropLast(1)
                    url = url.trimEnd('.', ',', '!', '?', ';', ':', '\'', '"', '»', '«')
                }
                
                if (url.length < 10) continue
                val start = origStart + trimStart
                val end = start + url.length
                
                // Пропускаем, если уже есть аннотация от MarkdownRenderer
                val alreadyLinked = existingAnnotations.any { 
                    start >= it.start && end <= it.end
                }
                if (!alreadyLinked) {
                    addStringAnnotation(tag = "URL", annotation = url, start = start, end = end)
                    addStyle(
                        style = SpanStyle(color = Color(0xFF1976D2), textDecoration = TextDecoration.Underline),
                        start = start, end = end
                    )
                }
            }
            
            // Домены без протокола: site.com, site.ru и т.д.
            val bareDomainPattern = Regex("""(?<!\w)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+(?:ru|com|org|net|io|app|me|info|xyz|online|tech|site|рф|su)(?:/[a-zA-Z0-9а-яА-ЯёЁ./?=&%_#@!~()-]*)?""")
            for (match in bareDomainPattern.findAll(displayText)) {
                var domain = match.value
                val origStart = match.range.first
                
                // 1. Убираем пунктуацию с начала
                domain = domain.trimStart('(', '.', ',', '\'', '"', '«')
                val trimStart = match.value.length - domain.length
                
                // 2. Убираем пунктуацию с конца, балансируем скобки
                domain = domain.trimEnd('.', ',', '!', '?', ';', ':', '\'', '"', '»', '«')
                while (domain.count { it == '(' } < domain.count { it == ')' }) {
                    domain = domain.dropLast(1)
                    domain = domain.trimEnd('.', ',', '!', '?', ';', ':', '\'', '"', '»', '«')
                }
                
                if (domain.length < 5) continue
                val start = origStart + trimStart
                val end = start + domain.length
                
                val alreadyLinked = existingAnnotations.any {
                    start >= it.start && end <= it.end
                }
                if (!alreadyLinked) {
                    val fullUrl = if (domain.startsWith("http")) domain else "https://$domain"
                    addStringAnnotation(tag = "URL", annotation = fullUrl, start = start, end = end)
                    addStyle(
                        style = SpanStyle(color = Color(0xFF1976D2), textDecoration = TextDecoration.Underline),
                        start = start, end = end
                    )
                }
            }
        }
    }
    
    ClickableText(
        text = annotatedString,
        modifier = modifier,
        maxLines = maxLines,
        onClick = { offset ->
            annotatedString.getStringAnnotations("URL", offset, offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        println("⚠️ Не удалось открыть ссылку: ${e.message}")
                    }
                }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun ChatDetailScreenPreview() {
    MaterialTheme {
        ChatDetailScreen(chatId = "test")
    }
}
