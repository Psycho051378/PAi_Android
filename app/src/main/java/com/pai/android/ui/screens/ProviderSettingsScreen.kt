package com.pai.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.pai.android.R
import com.pai.android.ui.utils.focusAnimation
import com.pai.android.ui.utils.pressAnimation
import com.pai.android.ui.utils.toggleSlideAnimation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pai.android.data.local.model.ModelManager
import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.ProviderSettings
import com.pai.android.presentation.settings.ProviderSettingsViewModel
import com.pai.android.ui.navigation.Screen
import com.pai.android.ui.components.HintText
import com.pai.android.ui.utils.toggleSlideAnimation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    navController: NavController? = null,
    viewModel: ProviderSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // Показываем ошибки в snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            scope.launch {
                snackbarHostState.showSnackbar(error)
                viewModel.clearError()
            }
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { HintText(text = stringResource(R.string.provider_settings_title), hintResId = R.string.hint_settings_ai_providers) },
                navigationIcon = {
                    if (navController != null) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {},
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.startEditingSettings(ProviderSettings(provider = AiProvider.CUSTOM)) }
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.provider_add_settings))
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.editingSettings != null -> {
                    EditProviderSettingsScreen(
                        settings = state.editingSettings,
                        compatibilityResult = state.compatibilityResult,
                        isCheckingCompatibility = state.isCheckingCompatibility,
                        downloadProgress = state.downloadProgress,
                        isDownloading = state.isDownloading,
                        isDownloaded = state.isDownloaded,
                        downloadError = state.downloadError,
                        onSave = { provider, apiKey, baseUrl, modelName, isDefault, maxTokens, thinkingModeEnabled, contextManagement, modelMaxContext, modelMaxOutput, contextBufferPercent, useCustomParams, temperature, topP, useGpuBackend ->
                            viewModel.saveSettings(provider, apiKey, baseUrl, modelName, isDefault, maxTokens, thinkingModeEnabled, contextManagement, modelMaxContext, modelMaxOutput, contextBufferPercent, useCustomParams, temperature, topP, useGpuBackend)
                        },
                        onCancel = { viewModel.cancelEditing() },
                        onCheckCompatibility = { modelId -> viewModel.checkCompatibility(modelId) },
                        onCheckIfDownloaded = { modelId -> viewModel.checkIfModelDownloaded(modelId) },
                        onDownloadModel = { modelId -> viewModel.downloadModel(modelId) },
                    onDeleteModel = { modelId -> viewModel.deleteModel(modelId) }
                    )
                }
                state.providers.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.provider_settings_empty),
                        fontSize = 18.sp,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        state.providers.forEach { provider ->
                            item {
                                ProviderSection(
                                    provider = provider,
                                    settings = state.settings[provider] ?: emptyList(),
                                    defaultSettings = state.defaultSettings,
                                    onSelect = { viewModel.selectProvider(provider) },
                                    onEdit = { settings -> viewModel.startEditingSettings(settings) },
                                    onDelete = { settingsId -> viewModel.deleteSettings(settingsId) },
                                    onTest = { settings -> viewModel.testConnection(settings) },
                                    onToggleDefault = { settingsId, isDefault ->
                                        viewModel.toggleDefaultSettings(settingsId, isDefault)
                                    }
                                )
                            }
                        }
                        // Smart Router — навигация
                        item {
                            SmartRouterNavCard(navController = navController)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProviderSection(
    provider: com.pai.android.data.model.AiProvider,
    settings: List<com.pai.android.data.model.ProviderSettings>,
    defaultSettings: com.pai.android.data.model.ProviderSettings?,
    onSelect: () -> Unit,
    onEdit: (com.pai.android.data.model.ProviderSettings) -> Unit,
    onDelete: (String) -> Unit,
    onTest: (com.pai.android.data.model.ProviderSettings) -> Unit,
    onToggleDefault: (String, Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxSize()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = provider.localizedName(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${stringResource(R.string.provider_url_label)}: ${provider.defaultBaseUrl}",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Text(
                text = "${stringResource(R.string.provider_default_model_label)}: ${provider.defaultModel}",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Text(
                text = "${stringResource(R.string.provider_requires_key_label)}: ${
                    if (provider.requiresApiKey) stringResource(R.string.provider_requires_key_yes)
                    else stringResource(R.string.provider_requires_key_no)
                }",
                fontSize = 12.sp,
                color = Color.Gray
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            if (settings.isEmpty()) {
                Text(
                    text = stringResource(R.string.provider_no_settings),
                    fontSize = 14.sp,
                    color = Color.LightGray,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.provider_settings_count, settings.size),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                settings.forEach { setting ->
                    ProviderSettingItem(
                        setting = setting,
                        isDefault = defaultSettings?.id == setting.id,
                        onEdit = { onEdit(setting) },
                        onDelete = { onDelete(setting.id) },
                        onTest = { onTest(setting) },
                        onToggleDefault = { isDefault -> onToggleDefault(setting.id, isDefault) }
                    )
                }
            }
            
            TextButton(
                onClick = { onSelect() },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.provider_add_settings))
            }
        }
    }
}

@Composable
fun ProviderSettingItem(
    setting: com.pai.android.data.model.ProviderSettings,
    isDefault: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onToggleDefault: (Boolean) -> Unit
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxSize(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isDefault) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            androidx.compose.foundation.layout.Row {
                Text(
                    text = setting.modelName ?: stringResource(R.string.provider_model_unnamed),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (isDefault) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.provider_default),
                        tint = Color.Green
                    )
                }
            }
            
            if (!setting.apiKey.isNullOrBlank()) {
                Text(
                    text = "${stringResource(R.string.provider_api_key_label)}: ${"*".repeat(8)}${setting.apiKey.takeLast(4)}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            
            if (!setting.baseUrl.isNullOrBlank() && setting.baseUrl != setting.provider.defaultBaseUrl) {
                Text(
                    text = "${stringResource(R.string.provider_url_label)}: ${setting.baseUrl}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            
            Text(
                text = stringResource(if (setting.isEnabled && setting.isDefault) R.string.provider_status_enabled_default else R.string.provider_status_disabled_normal),
                fontSize = 11.sp,
                color = Color.LightGray
            )
            
            // Кнопки действий
            androidx.compose.foundation.layout.Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.provider_edit))
                }
                IconButton(onClick = onTest) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.provider_test))
                }
                IconButton(onClick = { onToggleDefault(!isDefault) }) {
                    Icon(
                        if (isDefault) Icons.Default.Close else Icons.Default.Check,
                        contentDescription = if (isDefault) stringResource(R.string.provider_unset_default) else stringResource(R.string.provider_set_default)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = Color.Red)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProviderSettingsScreen(
    settings: com.pai.android.data.model.ProviderSettings?,
    compatibilityResult: com.pai.android.data.local.model.CompatibilityResult? = null,
    isCheckingCompatibility: Boolean = false,
    downloadProgress: Float = 0f,
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    downloadError: String? = null,
    onSave: (
        provider: com.pai.android.data.model.AiProvider,
        apiKey: String?,
        baseUrl: String?,
        modelName: String?,
        isDefault: Boolean,
        maxTokens: Int?,
        thinkingModeEnabled: Boolean,
        contextManagement: String,
        modelMaxContext: Int?,
        modelMaxOutput: Int?,
        contextBufferPercent: Int,
        useCustomParams: Boolean,
        temperature: Double?,
        topP: Double?,
        useGpuBackend: Boolean
    ) -> Unit,
    onCancel: () -> Unit,
    onCheckCompatibility: (String) -> Unit = {},
    onCheckIfDownloaded: (String) -> Unit = {},
    onDownloadModel: (String) -> Unit = {},
    onDeleteModel: (String) -> Unit = {}
) {
    var selectedProvider by remember { mutableStateOf(settings?.provider ?: com.pai.android.data.model.AiProvider.OPENROUTER) }
    var apiKey by remember { mutableStateOf(settings?.apiKey ?: "") }
    var baseUrl by remember { mutableStateOf(settings?.baseUrl ?: selectedProvider.defaultBaseUrl) }
    var modelName by remember { mutableStateOf(settings?.modelName ?: selectedProvider.defaultModel) }
    var isDefault by remember { mutableStateOf(settings?.isDefault ?: false) }
    var showApiKey by remember { mutableStateOf(false) }
    
    // Новые параметры
    var maxTokensText by remember { mutableStateOf(settings?.maxTokens?.toString() ?: "") }
    var thinkingEnabled by remember { mutableStateOf(settings?.thinkingModeEnabled ?: false) }
    var contextMgmt by remember { mutableStateOf(settings?.contextManagement ?: "truncate") }
    var modelMaxContextText by remember { mutableStateOf(settings?.modelMaxContext?.toString() ?: "") }
    var modelMaxOutputText by remember { mutableStateOf(settings?.modelMaxOutput?.toString() ?: "") }
    var contextBufferText by remember { mutableStateOf(settings?.contextBufferPercent?.toString() ?: "90") }
    var useCustomParams by remember { mutableStateOf(settings?.useCustomParams ?: false) }
    var temperatureValue by remember { mutableStateOf(settings?.temperature?.toFloat() ?: 0.7f) }
    var topPValue by remember { mutableStateOf(settings?.topP?.toFloat() ?: 1.0f) }
    var useGpuBackend by remember { mutableStateOf(settings?.useGpuBackend ?: true) }
    
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.padding(16.dp).verticalScroll(scrollState)) {
        Text(
            text = if (settings == null) stringResource(R.string.provider_add_title) else stringResource(R.string.provider_edit_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Выбор провайдера (только при создании нового)
        if (settings == null) {
            Text(stringResource(R.string.provider_select_label), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            com.pai.android.data.model.AiProvider.values().forEach { provider ->
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxSize(),
                    onClick = { selectedProvider = provider }
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(provider.localizedName(), fontWeight = FontWeight.Medium)
                        Text(provider.defaultBaseUrl, fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }
        
        // ══ LITE_RT: адаптированный UI ══
        val isLiteRt = selectedProvider == com.pai.android.data.model.AiProvider.LITE_RT
        
        if (!isLiteRt) {
            val apiKeyInteractionSource = remember { MutableInteractionSource() }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .focusAnimation(apiKeyInteractionSource),
                label = { HintText(
                    text = stringResource(R.string.provider_api_key_label),
                    hintResId = when (selectedProvider) {
                        com.pai.android.data.model.AiProvider.DEEPSEEK -> R.string.hint_provider_api_key_deepseek
                        com.pai.android.data.model.AiProvider.OPENAI -> R.string.hint_provider_api_key_openai
                        else -> R.string.hint_provider_api_key
                    }
                ) },
                interactionSource = apiKeyInteractionSource,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) stringResource(R.string.provider_hide_key) else stringResource(R.string.provider_show_key)
                        )
                    }
                }
            )
            
            val baseUrlInteractionSource = remember { MutableInteractionSource() }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .focusAnimation(baseUrlInteractionSource),
                label = { HintText(
                    text = stringResource(R.string.provider_base_url_label),
                    hintResId = R.string.hint_provider_base_url
                ) },
                interactionSource = baseUrlInteractionSource,
                placeholder = { Text(selectedProvider.defaultBaseUrl) }
            )
        } else {
            // LITE_RT: выбор модели + совместимость + скачивание
            Text(stringResource(R.string.provider_local_model_title), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.provider_local_model_desc), fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            
            Text(stringResource(R.string.provider_local_model_select), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            com.pai.android.data.local.model.LocalModelInfo.AVAILABLE_MODELS.forEach { model ->
                val isSelected = modelName == model.id
                Card(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth().clickable {
                    modelName = model.id
                    onCheckIfDownloaded(model.id)
                },
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.displayName, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.provider_local_model_size, model.sizeMb) + " · " + stringResource(R.string.provider_local_model_ram, model.minRamMb), fontSize = 12.sp, color = Color.Gray)
                        }
                        if (isSelected && isDownloaded) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            IconButton(onClick = { onDeleteModel(model.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.provider_local_model_delete),
                                    tint = Color(0xFFFF5252)
                                )
                            }
                        }
                        if (isSelected && !isDownloaded && !isDownloading) {
                            IconButton(onClick = { onDownloadModel(model.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.provider_download_model),
                                    tint = Color(0xFF2196F3)
                                )
                            }
                        }
                    }
                }
            }
            
            OutlinedButton(onClick = { onCheckCompatibility(modelName) }, enabled = !isCheckingCompatibility && modelName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(if (isCheckingCompatibility) stringResource(R.string.provider_local_model_checking) else stringResource(R.string.provider_local_model_check))
            }
            
            compatibilityResult?.let { result ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (result.compatible) Color(0xFF1B5E20) else Color(0xFFB71C1C))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (result.compatible) stringResource(R.string.provider_local_model_compatible) else stringResource(R.string.provider_local_model_incompatible), fontWeight = FontWeight.Bold, color = Color.White)
                        Text(result.details, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // Переключатель GPU/CPU для локальной модели
            Text(stringResource(R.string.provider_accelerator_title), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.provider_use_gpu), modifier = Modifier.weight(1f))
                Switch(
                    checked = useGpuBackend,
                    onCheckedChange = { useGpuBackend = it }
                )
            }
            Text(
                text = if (useGpuBackend) stringResource(R.string.provider_gpu_desc) else stringResource(R.string.provider_cpu_desc),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (isDownloaded) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.provider_local_model_downloaded), fontWeight = FontWeight.Bold, color = Color.White)
                        OutlinedButton(
                            onClick = { onDeleteModel(modelName) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.provider_local_model_delete), color = Color(0xFFFF5252))
                        }
                    }
                }
            } else if (isDownloading) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF37474F))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("⏬ Скачивание...", fontWeight = FontWeight.Bold, color = Color.White)
                        LinearProgressIndicator(progress = downloadProgress, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Text("${(downloadProgress * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            } else if (compatibilityResult?.compatible == true) {
                OutlinedButton(onClick = { onDownloadModel(modelName) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("📥 Скачать модель")
                }
            }
            
            downloadError?.let {
                Text("❌ $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
        
        val modelNameInteractionSource = remember { MutableInteractionSource() }
        OutlinedTextField(
            value = modelName,
            onValueChange = { modelName = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .focusAnimation(modelNameInteractionSource),
            label = { HintText(
                text = stringResource(R.string.provider_model_label),
                hintResId = when (selectedProvider) {
                    com.pai.android.data.model.AiProvider.DEEPSEEK -> R.string.hint_provider_model_deepseek
                    com.pai.android.data.model.AiProvider.OPENAI -> R.string.hint_provider_model_openai
                    else -> R.string.hint_provider_model
                }
            ) },
            interactionSource = modelNameInteractionSource,
            placeholder = { Text(selectedProvider.defaultModel) }
        )
        
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        
        Text(stringResource(R.string.provider_model_params_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        
        val maxTokensInteractionSource = remember { MutableInteractionSource() }
        OutlinedTextField(
            value = maxTokensText,
            onValueChange = { maxTokensText = it.filter { c -> c.isDigit() } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .focusAnimation(maxTokensInteractionSource),
            label = { Text(stringResource(R.string.provider_max_tokens_label)) },
            interactionSource = maxTokensInteractionSource,
            placeholder = { Text("2000") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.provider_thinking_label), modifier = Modifier.weight(1f))
            Switch(
                checked = thinkingEnabled,
                onCheckedChange = { thinkingEnabled = it },
                modifier = Modifier.toggleSlideAnimation(isChecked = thinkingEnabled, slideDistance = 8.dp)
            )
        }
        Text(
            text = if (thinkingEnabled) stringResource(R.string.provider_thinking_on) else stringResource(R.string.provider_thinking_off),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.provider_context_mgmt_label), modifier = Modifier.weight(1f))
            FilterChip(
                selected = contextMgmt == "truncate",
                onClick = { contextMgmt = "truncate" },
                label = { Text(stringResource(R.string.provider_context_truncate)) },
                modifier = Modifier.padding(end = 4.dp)
            )
            FilterChip(
                selected = contextMgmt == "summarize",
                onClick = { contextMgmt = "summarize" },
                label = { Text(stringResource(R.string.provider_context_summarize)) }
            )
        }
        
        val modelMaxCtxInteractionSource = remember { MutableInteractionSource() }
        OutlinedTextField(
            value = modelMaxContextText,
            onValueChange = { modelMaxContextText = it.filter { c -> c.isDigit() } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .focusAnimation(modelMaxCtxInteractionSource),
            label = { Text(stringResource(R.string.provider_context_window_label)) },
            interactionSource = modelMaxCtxInteractionSource,
            placeholder = { Text(stringResource(R.string.provider_model_max_context_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        
        val modelMaxOutInteractionSource = remember { MutableInteractionSource() }
        OutlinedTextField(
            value = modelMaxOutputText,
            onValueChange = { modelMaxOutputText = it.filter { c -> c.isDigit() } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .focusAnimation(modelMaxOutInteractionSource),
            label = { Text(stringResource(R.string.provider_max_output_label)) },
            interactionSource = modelMaxOutInteractionSource,
            placeholder = { Text(stringResource(R.string.provider_model_max_output_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.provider_context_buffer_label), modifier = Modifier.weight(1f))
            Text("$contextBufferText%", fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
            Slider(
                value = contextBufferText.toFloatOrNull()?.coerceIn(0f, 100f) ?: 90f,
                onValueChange = { contextBufferText = it.toInt().toString() },
                valueRange = 0f..100f,
                steps = 9,
                modifier = Modifier.width(120.dp)
            )
        }
        
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.provider_gen_params_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.provider_use_custom_params), modifier = Modifier.weight(1f))
            Switch(
                checked = useCustomParams,
                onCheckedChange = { useCustomParams = it }
            )
        }
        Text(
            text = if (useCustomParams) stringResource(R.string.provider_custom_params_desc) else stringResource(R.string.provider_default_params_desc),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        if (useCustomParams) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.provider_temperature_label), modifier = Modifier.weight(1f))
                Text("%.1f".format(temperatureValue), fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                Slider(
                    value = temperatureValue,
                    onValueChange = { temperatureValue = it },
                    valueRange = 0.0f..2.0f,
                    steps = 19,
                    modifier = Modifier.width(200.dp)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.provider_top_p_label), modifier = Modifier.weight(1f))
                Text("%.2f".format(topPValue), fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                Slider(
                    value = topPValue,
                    onValueChange = { topPValue = it },
                    valueRange = 0.0f..1.0f,
                    steps = 19,
                    modifier = Modifier.width(200.dp)
                )
            }
        }
        
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        
        Switch(
            checked = isDefault,
            onCheckedChange = { isDefault = it },
            modifier = Modifier
                .padding(vertical = 8.dp)
                .toggleSlideAnimation(isChecked = isDefault, slideDistance = 8.dp)
        )
        Text(stringResource(R.string.provider_use_as_default), fontSize = 14.sp)
        
        androidx.compose.foundation.layout.Row {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.cancel))
            }
            TextButton(
                onClick = {
                    onSave(selectedProvider, apiKey, baseUrl, modelName, isDefault,
                        maxTokensText.toIntOrNull(),
                        thinkingEnabled,
                        contextMgmt,
                        modelMaxContextText.toIntOrNull(),
                        modelMaxOutputText.toIntOrNull(),
                        contextBufferText.toIntOrNull() ?: 90,
                        useCustomParams,
                        temperatureValue.toDouble(),
                        topPValue.toDouble(),
                        useGpuBackend
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

/**
 * Карточка Smart Router в списке провайдеров.
 */
@Composable
private fun SmartRouterNavCard(navController: NavController?) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clickable {
                if (navController != null) {
                    navController.navigate(Screen.SmartRouterSettings.route)
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_smart_router),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(R.string.settings_smart_router_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/** Локализованное название провайдера */
@Composable
fun AiProvider.localizedName(): String = when (this) {
    com.pai.android.data.model.AiProvider.LITE_RT -> stringResource(R.string.provider_name_lite_rt)
    com.pai.android.data.model.AiProvider.CUSTOM -> stringResource(R.string.provider_name_custom)
    com.pai.android.data.model.AiProvider.OPENROUTER -> stringResource(R.string.provider_name_openrouter)
    com.pai.android.data.model.AiProvider.DEEPSEEK -> stringResource(R.string.provider_name_deepseek)
    com.pai.android.data.model.AiProvider.OPENAI -> stringResource(R.string.provider_name_openai)
    com.pai.android.data.model.AiProvider.OLLAMA -> stringResource(R.string.provider_name_ollama)
}

@Preview(showBackground = true)
@Composable
fun ProviderSettingsScreenPreview() {
    MaterialTheme {
        ProviderSettingsScreen()
    }
}