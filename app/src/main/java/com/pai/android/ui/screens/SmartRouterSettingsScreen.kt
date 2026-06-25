package com.pai.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pai.android.R
import com.pai.android.ui.viewmodel.SmartRouterSettingsViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRouterSettingsScreen(
    navController: NavController,
    viewModel: SmartRouterSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(R.string.smart_router_title), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(scrollState).padding(16.dp)
        ) {
            // Description
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = stringResource(R.string.smart_router_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Enable switch
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.smart_router_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.smart_router_auto_select), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.enabled, onCheckedChange = { viewModel.setEnabled(it) })
                }
            }

            if (state.enabled) {
                Spacer(modifier = Modifier.height(16.dp))

                // Network provider
                Text(stringResource(R.string.smart_router_network_model), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                if (state.availableProviders.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.smart_router_no_providers), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    // Одна карточка на провайдера, внутри выбор модели через дропдаун
                    state.availableProviders.forEach { (provider, providerSettingsList) ->
                        val isSelected = providerSettingsList.any { it.id == state.networkProviderSettingsId }
                        var modelDropdownExpanded by remember { mutableStateOf(false) }

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                // Заголовок провайдера
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Cloud, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(provider.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        if (isSelected) {
                                            Text(state.networkModelName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    if (isSelected) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    if (!isSelected) {
                                        TextButton(onClick = { viewModel.selectProvider(provider) }) {
                                            Text("Выбрать", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                // Дропдаун моделей — только для выбранного провайдера
                                if (isSelected && providerSettingsList.size > 1) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Divider()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    ExposedDropdownMenuBox(expanded = modelDropdownExpanded, onExpandedChange = { modelDropdownExpanded = !modelDropdownExpanded }) {
                                        OutlinedTextField(
                                            value = state.networkModelName,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text(stringResource(R.string.smart_router_model_label)) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                        ExposedDropdownMenu(expanded = modelDropdownExpanded, onDismissRequest = { modelDropdownExpanded = false }) {
                                            providerSettingsList.forEach { settings ->
                                                DropdownMenuItem(
                                                    text = { Text(settings.getEffectiveModel()) },
                                                    onClick = {
                                                        viewModel.selectProviderSettings(settings.id)
                                                        modelDropdownExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Local model info
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.smart_router_local_model), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.smart_router_local_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Complexity threshold
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.smart_router_threshold), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.smart_router_threshold_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.smart_router_easier), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
                            Slider(value = state.complexityThreshold, onValueChange = { viewModel.setComplexityThreshold(it) }, valueRange = 0.0f..1.0f, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.smart_router_harder), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
                        }
                        Text(text = stringResource(R.string.smart_router_current_value, (state.complexityThreshold * 100).roundToInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Max fallback tokens
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.smart_router_max_tokens), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.smart_router_max_tokens_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Slider(value = (state.maxLocalTokens / 128f).coerceIn(1f, 16f), onValueChange = { viewModel.setMaxLocalTokens((it * 128).roundToInt()) }, valueRange = 1f..16f, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("${state.maxLocalTokens}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Extra checkboxes
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.smart_router_extra), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = state.routeMultimodalToLocal, onCheckedChange = { viewModel.setRouteMultimodalToLocal(it) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.smart_router_multimodal), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = state.enableFallback, onCheckedChange = { viewModel.setEnableFallback(it) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.smart_router_fallback), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = state.enableHybrid, onCheckedChange = { viewModel.setEnableHybrid(it) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.smart_router_hybrid), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (state.saved) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.smart_router_saved), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.smart_router_save))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
