package com.pai.android.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pai.android.R
import com.pai.android.agent.tools.LocaleManager
import com.pai.android.presentation.permissions.PermissionItem
import com.pai.android.presentation.permissions.PermissionState
import com.pai.android.presentation.permissions.PermissionsViewModel

/**
 * Экран онбординга разрешений.
 * Показывается при первом запуске, если пользователь ещё не завершил настройку.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val permissionStates by viewModel.permissionStates.collectAsStateWithLifecycle()
    val allGranted by viewModel.allGranted.collectAsStateWithLifecycle()

    var showSkipDialog by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }
    val localeManager = remember { LocaleManager(context) }

    // Единый launcher для ВСЕХ runtime-разрешений (1 и более)
    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        // Обновляем состояние по каждому выданному/запрещённому разрешению
        for ((permission, granted) in grantResults) {
            val itemId = findItemIdByPermission(permissions, permission)
            if (itemId != null) {
                viewModel.setPermissionState(
                    itemId,
                    if (granted) PermissionState.GRANTED else PermissionState.DENIED
                )
            }
        }
        // Финальная перепроверка всех разрешений
        viewModel.checkAllPermissions(context)
    }

    // Launcher для открытия системных настроек (NotificationListener)
    val systemSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.recheckSystemPermissions(context)
    }

    // Проверяем все разрешения при первом рендере
    LaunchedEffect(Unit) {
        viewModel.checkAllPermissions(context)
    }

    // Диалог подтверждения пропуска
    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text(stringResource(R.string.permissions_skip_warning_title)) },
            text = { Text(stringResource(R.string.permissions_skip_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showSkipDialog = false
                    viewModel.markAllAsGranted()
                    viewModel.completeOnboarding(context)
                    onSkip()
                }) {
                    Text(stringResource(R.string.permissions_skip_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_title)) },
                actions = {
                    Box {
                        TextButton(onClick = { showLangMenu = true }) {
                            Text(localeManager.getCurrentLang().uppercase(), fontWeight = FontWeight.Bold)
                        }
                        DropdownMenu(
                            expanded = showLangMenu,
                            onDismissRequest = { showLangMenu = false }
                        ) {
                            LocaleManager.SUPPORTED_LANGUAGES.forEach { (code, displayName) ->
                                DropdownMenuItem(
                                    text = { Text(displayName) },
                                    onClick = {
                                        showLangMenu = false
                                        if (code != localeManager.getCurrentLang()) {
                                            localeManager.setCurrentLang(code)
                                            (context as? Activity)?.recreate()
                                        }
                                    },
                                    leadingIcon = {
                                        if (code == localeManager.getCurrentLang()) {
                                            Text("✓", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (allGranted) {
                        Button(
                            onClick = {
                                viewModel.completeOnboarding(context)
                                onComplete()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.permissions_all_done),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                requestAllRuntimePermissions(
                                    context = context,
                                    viewModel = viewModel,
                                    permissions = permissions,
                                    permissionStates = permissionStates,
                                    launcher = runtimePermissionsLauncher
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.permissions_grant_all),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        TextButton(
                            onClick = { showSkipDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.permissions_skip))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.permissions_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp, top = 4.dp)
            )

            permissions.forEachIndexed { index, item ->
                val state = permissionStates[item.id] ?: PermissionState.UNKNOWN
                PermissionCard(
                    item = item,
                    state = state,
                    onRequest = {
                        requestSingleItemPermission(
                            context = context,
                            item = item,
                            viewModel = viewModel,
                            runtimeLauncher = runtimePermissionsLauncher,
                            systemSettingsLauncher = systemSettingsLauncher
                        )
                    }
                )
                if (index < permissions.lastIndex) {
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

// ─── PermissionCard ───────────────────────────────────────────────────────────

@Composable
private fun PermissionCard(
    item: PermissionItem,
    state: PermissionState,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                PermissionState.GRANTED ->
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                PermissionState.SYSTEM_SETTINGS_REQUIRED ->
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.icon,
                fontSize = 28.sp,
                modifier = Modifier.padding(end = 14.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.titleResId),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(item.descriptionResId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            when (state) {
                PermissionState.GRANTED -> {
                    Text(
                        text = stringResource(R.string.permissions_granted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                PermissionState.SYSTEM_SETTINGS_REQUIRED -> {
                    TextButton(
                        onClick = onRequest,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.permissions_system_settings),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp
                        )
                    }
                }
                PermissionState.DENIED -> {
                    FilledTonalButton(
                        onClick = onRequest,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.permissions_required),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                PermissionState.UNKNOWN -> {
                    FilledTonalButton(
                        onClick = onRequest,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        enabled = false
                    ) {
                        Text(text = "…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ─── Helper functions ─────────────────────────────────────────────────────────

/**
 * Найти id PermissionItem по имени Android-разрешения.
 */
private fun findItemIdByPermission(
    permissions: List<PermissionItem>,
    permissionName: String
): String? {
    for (item in permissions) {
        if (permissionName in item.runtimePermissions) {
            return item.id
        }
    }
    return null
}

/**
 * Запросить одно конкретное разрешение (клик по карточке).
 *
 * - Runtime-разрешения: через единый runtimePermissionsLauncher
 * - Системные настройки: открываем Intent
 */
private fun requestSingleItemPermission(
    context: Context,
    item: PermissionItem,
    viewModel: PermissionsViewModel,
    runtimeLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    systemSettingsLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    if (item.isSystemSetting) {
        val intent = item.systemSettingsIntent?.invoke(context)
        if (intent != null) {
            systemSettingsLauncher.launch(intent)
        }
        return
    }

    if (item.runtimePermissions.isEmpty()) {
        // Нечего запрашивать — отмечаем как granted
        viewModel.setPermissionState(item.id, PermissionState.GRANTED)
        return
    }

    // Запрашиваем все runtime-разрешения этого item (один или несколько)
    runtimeLauncher.launch(item.runtimePermissions.toTypedArray())
}

/**
 * «Разрешить всё»: запросить все невыданные runtime-разрешения одним вызовом.
 *
 * ⚠️ НЕ открывает системные настройки — это делается только по клику на
 * соответствующую карточку (NotificationListener).
 * ⚠️ BACKGROUND_LOCATION исключён — на Android 11+ его нужно запрашивать отдельно.
 */
private fun requestAllRuntimePermissions(
    context: Context,
    viewModel: PermissionsViewModel,
    permissions: List<PermissionItem>,
    permissionStates: Map<String, PermissionState>,
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val pendingRuntime = mutableListOf<String>()

    for (item in permissions) {
        // Пропускаем системные настройки — они выдаются только по клику на карточку
        if (item.isSystemSetting) continue

        val state = permissionStates[item.id] ?: PermissionState.UNKNOWN
        // UNKNOWN не запрашиваем — initial check ещё не прошёл
        if (state == PermissionState.GRANTED || state == PermissionState.UNKNOWN) continue
        if (item.runtimePermissions.isEmpty()) continue

        // Фильтруем background location — его нужно запрашивать отдельно
        val effectivePerms = item.runtimePermissions.filter { perm ->
            !(perm == android.Manifest.permission.ACCESS_BACKGROUND_LOCATION &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        }

        pendingRuntime.addAll(effectivePerms)
    }

    if (pendingRuntime.isNotEmpty()) {
        launcher.launch(pendingRuntime.toTypedArray())
    } else {
        // Нечего запрашивать — перепроверяем
        viewModel.checkAllPermissions(context)
    }
}
