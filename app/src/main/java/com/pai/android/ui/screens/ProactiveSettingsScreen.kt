package com.pai.android.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pai.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import com.pai.android.ui.components.HintText
import com.pai.android.agent.ProactiveSettings
import com.pai.android.agent.DecisionEngine
import com.pai.android.data.local.AppDatabase
import com.pai.android.data.model.GeoTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveSettingsScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("proactive_prefs", Context.MODE_PRIVATE)

    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", true)) }
    var batteryAlerts by remember { mutableStateOf(prefs.getBoolean("battery_alerts", true)) }
    var calendarReminders by remember { mutableStateOf(prefs.getBoolean("calendar_reminders", true)) }
    var forwardNotifs by remember { mutableStateOf(prefs.getBoolean("forward_notifications", true)) }
    var notificationDigest by remember { mutableStateOf(prefs.getBoolean("notification_digest", true)) }
    var locationHints by remember { mutableStateOf(prefs.getBoolean("location_hints", false)) }
    var quietStart by remember { mutableStateOf(prefs.getInt("quiet_start", 23)) }
    var quietEnd by remember { mutableStateOf(prefs.getInt("quiet_end", 8)) }
    var minPriority by remember { mutableStateOf(prefs.getInt("min_priority", 30)) }

    fun save() {
        prefs.edit().apply {
            putBoolean("enabled", enabled)
            putBoolean("battery_alerts", batteryAlerts)
            putBoolean("calendar_reminders", calendarReminders)
            putBoolean("forward_notifications", forwardNotifs)
            putBoolean("notification_digest", notificationDigest)
            putBoolean("location_hints", locationHints)
            putInt("quiet_start", quietStart)
            putInt("quiet_end", quietEnd)
            putInt("min_priority", minPriority)
            apply()
            DecisionEngine.proactiveAllowed = enabled && forwardNotifs
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { HintText(text = stringResource(R.string.proactive_title), hintResId = R.string.hint_settings_proactive) },
                navigationIcon = {
                    TextButton(onClick = { save(); onBack() }) {
                        Text("← " + stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Вкл/выкл
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.proactive_master_switch), fontWeight = FontWeight.Bold)
                            Text(
                                text = stringResource(R.string.proactive_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it; save() })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Категории
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.proactive_categories_section), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                    CategoryRow(stringResource(R.string.proactive_battery_title), stringResource(R.string.proactive_battery_desc), batteryAlerts) {
                        batteryAlerts = it; save()
                    }
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    CategoryRow(stringResource(R.string.proactive_calendar_title), stringResource(R.string.proactive_calendar_desc), calendarReminders) {
                        calendarReminders = it; save()
                    }
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    CategoryRow(stringResource(R.string.proactive_notifications_title), stringResource(R.string.proactive_notifications_desc), notificationDigest) {
                        notificationDigest = it; save()
                    }
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    CategoryRow(stringResource(R.string.proactive_forward_to_chat_title), stringResource(R.string.proactive_forward_to_chat_desc), forwardNotifs) {
                        forwardNotifs = it; save()
                    }
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    CategoryRow(stringResource(R.string.proactive_geo_title), stringResource(R.string.proactive_geo_desc), locationHints) {
                        locationHints = it; save()
                    }

                    if (locationHints) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        GeoTaskList(context = context)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Тихий режим
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.proactive_quiet_hours_section), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    Text(
                        text = stringResource(R.string.proactive_quiet_hours_format, quietStart, quietEnd),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.proactive_quiet_start))
                            TextButton(onClick = {
                                quietStart = if (quietStart <= 0) 23 else quietStart - 1; save()
                            }) { Text("-") }
                            Text("$quietStart:00", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = {
                                quietStart = if (quietStart >= 23) 0 else quietStart + 1; save()
                            }) { Text("+") }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.proactive_quiet_end))
                            TextButton(onClick = {
                                quietEnd = if (quietEnd <= 0) 23 else quietEnd - 1; save()
                            }) { Text("-") }
                            Text("$quietEnd:00", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = {
                                quietEnd = if (quietEnd >= 23) 0 else quietEnd + 1; save()
                            }) { Text("+") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Порог важности
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.proactive_min_priority), fontWeight = FontWeight.Bold)
                            Text(
                                text = stringResource(R.string.proactive_min_priority_desc, minPriority),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            minPriority = maxOf(0, minPriority - 10); save()
                        }) { Text("-10") }
                        Text("$minPriority", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = {
                            minPriority = minOf(100, minPriority + 10); save()
                        }) { Text("+10") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Инфо
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                Text(
                    text = stringResource(R.string.proactive_bottom_hint),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Список активных гео-задач с возможностью удаления.
 */
@Composable
private fun GeoTaskList(context: Context) {
    val scope = rememberCoroutineScope()
    val dao = remember {
        Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).build().geoTaskDao()
    }

    var tasks by remember { mutableStateOf<List<GeoTask>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Загружаем задачи при первом показе
    LaunchedEffect(Unit) {
        loading = true
        tasks = withContext(Dispatchers.IO) { dao.getActive() }
        loading = false
    }

    // Обновляем задачи через Flow
    LaunchedEffect(Unit) {
        dao.observeActive().collect { list ->
            tasks = list
        }
    }

    if (loading) {
        Text(
            text = stringResource(R.string.proactive_geo_loading),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else if (tasks.isEmpty()) {
        Text(
            text = stringResource(R.string.proactive_geo_empty),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        tasks.forEach { task ->
            GeoTaskRow(task = task, onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dao.deactivate(task.id)
                    }
                }
            })
            if (task != tasks.last()) {
                Divider(modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun GeoTaskRow(task: GeoTask, onDelete: () -> Unit) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editLabel by remember { mutableStateOf(task.label) }
    var editAddress by remember { mutableStateOf(task.address ?: "") }
    var editRadius by remember { mutableStateOf(task.radiusMeters.toString()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dao = remember {
        Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).build().geoTaskDao()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { showEditDialog = true },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            task.address?.let { addr ->
                Text(
                    text = addr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Text(
                text = stringResource(R.string.proactive_geo_radius_format, task.radiusMeters,
                    if (task.oneShot) stringResource(R.string.proactive_geo_one_shot) else stringResource(R.string.proactive_geo_multi)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        Row {
            TextButton(onClick = { showEditDialog = true }) {
                Text("✎", color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onDelete) {
                Text("✕", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.proactive_geo_edit_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editLabel,
                        onValueChange = { editLabel = it },
                        label = { Text(stringResource(R.string.proactive_geo_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editAddress,
                        onValueChange = { editAddress = it },
                        label = { Text(stringResource(R.string.proactive_geo_address)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editRadius,
                        onValueChange = { editRadius = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.proactive_geo_radius)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val radius = editRadius.toIntOrNull() ?: task.radiusMeters
                            val addr = editAddress.ifBlank { null }
                            val updated = task.copy(
                                label = editLabel,
                                address = addr,
                                radiusMeters = radius,
                                lastTriggeredAt = null
                            )
                            dao.insert(updated)
                        }
                    }
                    showEditDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
