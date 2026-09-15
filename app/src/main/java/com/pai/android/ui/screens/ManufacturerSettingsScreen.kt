package com.pai.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
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
import com.pai.android.ui.viewmodel.ManufacturerSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManufacturerSettingsScreen(
    navController: NavController,
    viewModel: ManufacturerSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manufacturer_auth_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Инструкция
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.manufacturer_auth_screen_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.manufacturer_auth_token_instruction),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.devices.isEmpty()) {
                // Нет устройств
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        stringResource(R.string.manufacturer_auth_no_devices),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Карточка Xiaomi со списком устройств
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.manufacturer_auth_mi_home),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.manufacturer_auth_enter_tokens),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        state.devices.forEachIndexed { index, entry ->
                            val hasToken = entry.token.isNotBlank()
                            val savedToken = try {
                                org.json.JSONObject(entry.device.deviceConfig)
                                    .optString("miio_token", "")
                            } catch (e: Exception) { "" }
                            val tokenChanged = entry.token != savedToken

                            DeviceTokenCard(
                                entry = entry,
                                hasToken = hasToken,
                                tokenChanged = tokenChanged,
                                onTokenChange = { viewModel.updateToken(entry.device.id, it) },
                                onSave = { viewModel.saveToken(entry.device.id) },
                                onClear = { viewModel.clearToken(entry.device.id) }
                            )

                            if (index < state.devices.lastIndex) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }

                // Кнопка обновить список
                OutlinedButton(
                    onClick = { viewModel.loadDevices() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.manufacturer_auth_refresh))
                }
            }

            // TP-Link Kasa/Tapo
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💡", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.manufacturer_auth_tplink_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Text(
                        stringResource(R.string.manufacturer_auth_tplink_description),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    // Username
                    OutlinedTextField(
                        value = state.tpLink.username,
                        onValueChange = { viewModel.updateTpLinkUsername(it) },
                        label = { Text(stringResource(R.string.manufacturer_auth_tplink_email_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("you@example.com") }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Password
                    var showPassword by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = state.tpLink.password,
                        onValueChange = { viewModel.updateTpLinkPassword(it) },
                        label = { Text(stringResource(R.string.manufacturer_auth_tplink_password_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(
                                    if (showPassword) stringResource(R.string.manufacturer_auth_tplink_hide)
                                    else stringResource(R.string.manufacturer_auth_tplink_show),
                                    fontSize = 12.sp
                                )
                            }
                        },
                        placeholder = { Text("••••••••") }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Кнопки
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (state.tpLink.enabled) {
                            TextButton(
                                onClick = { viewModel.clearTpLink() },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.manufacturer_auth_tplink_clear))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Button(
                            onClick = { viewModel.saveTpLink() },
                            enabled = state.tpLink.username.isNotBlank()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.manufacturer_auth_tplink_save))
                        }
                    }
                }
            }

            // Заглушка Яндекс
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔜", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Яндекс", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Text(
                        stringResource(R.string.manufacturer_auth_yandex_placeholder),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceTokenCard(
    entry: com.pai.android.ui.viewmodel.MiioDeviceEntry,
    hasToken: Boolean,
    tokenChanged: Boolean,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    Column {
        // Имя устройства
        Text(
            text = entry.device.displayName.ifBlank { entry.device.hostname },
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Text(
            text = "${entry.device.ip} • ${entry.device.modelName()}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Поле ввода токена
        OutlinedTextField(
            value = entry.token,
            onValueChange = onTokenChange,
            label = { Text("Device Token (32 hex)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("abcdef1234567890abcdef1234567890") },
            supportingText = if (hasToken) {
                { Text(stringResource(R.string.manufacturer_auth_token_saved), fontSize = 11.sp) }
            } else null,
            isError = entry.token.isNotBlank() && entry.token.length != 32
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasToken) {
                TextButton(
                    onClick = onClear,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.manufacturer_auth_clear))
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onSave,
                enabled = tokenChanged && entry.token.length == 32,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.manufacturer_auth_save))
            }
        }
    }
}

/** Вспомогательное расширение для отображения модели устройства. */
private fun com.pai.android.data.model.SmartHomeDevice.modelName(): String {
    try {
        val meta = org.json.JSONObject(metadata)
        return meta.optString("model", "").ifBlank { deviceType }
    } catch (e: Exception) { return deviceType }
}
