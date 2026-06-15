package com.pai.android.ui.screens

import android.app.Activity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.ui.res.stringResource
import com.pai.android.R
import com.pai.android.agent.ScheduledTask
import com.pai.android.agent.TaskScheduler
import dagger.hilt.android.EntryPointAccessors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerTasksScreen(navController: NavHostController) {
    val context = LocalContext.current
    val activity = context as? Activity
    val taskScheduler = remember(activity) {
        if (activity != null) {
            try {
                val entry = EntryPointAccessors.fromApplication(activity.applicationContext, SchedulerEntryPoint::class.java)
                entry.taskScheduler
            } catch (e: Exception) {
                println("SchedulerScreen: injection failed: ${e.message}")
                null
            }
        } else null
    }

    var tasks by remember { mutableStateOf<List<ScheduledTask>>(emptyList()) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<ScheduledTask?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deletingTask by remember { mutableStateOf<ScheduledTask?>(null) }

    LaunchedEffect(taskScheduler) {
        taskScheduler?.let { scheduler ->
            tasks = scheduler.tasks.value
            scheduler.tasks.collect { updated -> tasks = updated }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scheduler_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingTask = null
                showEditDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.scheduler_add_task))
            }
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.scheduler_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    val isSystemTask = task.id == "heartbeat" || task.id == "daily_monitoring" || task.id == "daily_summary"
                    TaskCard(
                        task = task,
                        canDelete = !isSystemTask,
                        onEdit = { editingTask = task; showEditDialog = true },
                        onToggle = { enabled ->
                            taskScheduler?.addTask(task.copy(enabled = enabled))
                            tasks = taskScheduler?.tasks?.value ?: emptyList()
                        },
                        onDelete = { deletingTask = task; showDeleteConfirm = true }
                    )
                }
            }
        }
    }

    if (showDeleteConfirm && deletingTask != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false; deletingTask = null },
            title = { Text(stringResource(R.string.scheduler_delete_title)) },
            text = { Text(stringResource(R.string.scheduler_delete_message, deletingTask!!.name)) },
            confirmButton = {
                TextButton(onClick = {
                    taskScheduler?.removeTask(deletingTask!!.id)
                    tasks = taskScheduler?.tasks?.value ?: emptyList()
                    showDeleteConfirm = false
                    deletingTask = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false; deletingTask = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showEditDialog) {
        TaskEditDialog(
            task = editingTask,
            onSave = { name, prompt, cron, interval ->
                val scheduler = taskScheduler ?: return@TaskEditDialog
                val id = editingTask?.id ?: "task_${System.currentTimeMillis()}_${name.filter { it.isLetterOrDigit() }.take(20)}"
                val newTask = if (interval > 0) {
                    ScheduledTask(id = id, name = name, prompt = prompt, cronExpression = "", enabled = true, intervalMinutes = interval)
                } else {
                    ScheduledTask(id = id, name = name, prompt = prompt, cronExpression = cron, enabled = true)
                }
                scheduler.addTask(newTask)
                showEditDialog = false
                editingTask = null
            },
            onDismiss = { showEditDialog = false; editingTask = null }
        )
    }
}

@Composable
private fun TaskCard(task: ScheduledTask, canDelete: Boolean = true, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val cron = task.cronExpression
    val parts = cron.split(",").map { it.trim() }
    val first = parts.firstOrNull()?.split(" ")?.filter { it.isNotBlank() } ?: listOf(cron)
    val label = if (task.isInterval) {
        stringResource(R.string.scheduler_every_n_min, task.intervalMinutes)
    } else {
        when {
            first.size == 1 -> stringResource(R.string.scheduler_daily_at, first[0])
            first[0].contains("-") -> stringResource(R.string.scheduler_on_s, first[0])
            first[0].length == 3 && first[0][0].isUpperCase() -> stringResource(R.string.scheduler_weekly_s_at, first[0], first.last())
            else -> stringResource(R.string.scheduler_monthly_s_th, first[0])
        }
    }
    // TODO: заменить на stringResource
    val times = if (parts.size > 1) " (${parts.size} times)" else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val alpha = if (task.enabled) 1f else 0.4f
                // TODO: заменить на stringResource
                Text(text = task.name.ifBlank { "Unnamed" }, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = task.prompt.take(80), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = label + times, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = alpha))
            }
            Switch(
                checked = task.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(end = 4.dp)
            )
            // TODO: заменить на stringResource
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary) }
            if (canDelete) {
                // TODO: заменить на stringResource
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditDialog(
    task: ScheduledTask?,
    onSave: (name: String, prompt: String, cron: String, interval: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(task) { mutableStateOf(task?.name ?: "") }
    var prompt by remember(task) { mutableStateOf(task?.prompt ?: "") }
    var intervalMinutes by remember(task) { mutableStateOf(task?.intervalMinutes ?: 0) }
    var useInterval by remember(task) { mutableStateOf(task?.isInterval ?: false) }

    val cron = task?.cronExpression ?: "09:00"
    val cronParts = cron.split(",").map { it.trim() }
    val firstCron = cronParts.firstOrNull() ?: "09:00"
    val firstParts = firstCron.split(" ").filter { it.isNotBlank() }

    var recurrence by remember { mutableStateOf(
        if (task?.isInterval == true) 0 else when {
            firstParts.size == 1 -> 0 // daily
            firstParts[0].contains("-") -> 3 // one-time date
            firstParts[0].length == 3 && firstParts[0][0].isUpperCase() -> 1 // weekly
            else -> 2 // monthly
        }
    )}

    val baseTime = if (firstParts.size >= 1) firstParts.last() else "09:00"
    val baseHour = baseTime.split(":").getOrNull(0)?.toIntOrNull() ?: 9
    val baseMin = baseTime.split(":").getOrNull(1)?.toIntOrNull() ?: 0
    var selectedHour by remember { mutableStateOf(baseHour) }
    var selectedMin by remember { mutableStateOf(baseMin) }

    val weekDays = listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")
    var selectedWeekDay by remember { mutableStateOf(
        if (recurrence == 1 && firstParts.size >= 2) firstParts[0] else "Mon"
    )}
    var monthDay by remember { mutableStateOf(
        if (recurrence == 2 && firstParts.size >= 2) firstParts[0].toIntOrNull()?.toString() ?: "1" else "1"
    )}
    var oneTimeDate by remember { mutableStateOf(
        if (recurrence == 3 && firstParts.size >= 2) firstParts[0]
        else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
    )}

    var showTimePicker by remember { mutableStateOf(false) }

    fun buildCron(): String {
        val time = "%02d:%02d".format(selectedHour, selectedMin)
        return when (recurrence) {
            0 -> time
            1 -> "$selectedWeekDay $time"
            2 -> "$monthDay $time"
            3 -> "$oneTimeDate $time"
            else -> time
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) stringResource(R.string.scheduler_new_task) else stringResource(R.string.scheduler_edit_task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.scheduler_name_label)) }, singleLine = true)
                OutlinedTextField(value = prompt, onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.scheduler_prompt_label)) }, maxLines = 3)

                // Интервальный режим (каждые N минут)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = useInterval, onCheckedChange = { useInterval = it })
                    Text(stringResource(R.string.scheduler_interval_label), style = MaterialTheme.typography.labelLarge)
                }

                if (useInterval) {
                    Text(stringResource(R.string.scheduler_interval_minutes_label), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = intervalMinutes.toFloat(),
                        onValueChange = { intervalMinutes = it.toInt() },
                        valueRange = 1f..120f,
                        steps = 119
                    )
                    Text(stringResource(R.string.scheduler_every_n_min, intervalMinutes), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.scheduler_recurrence_label), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(stringResource(R.string.scheduler_daily) to 0, stringResource(R.string.scheduler_weekly) to 1, stringResource(R.string.scheduler_monthly) to 2, stringResource(R.string.scheduler_once) to 3).forEach { (label, idx) ->
                            FilterChip(selected = recurrence == idx, onClick = { recurrence = idx },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.scheduler_at), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        Text("%02d:%02d".format(selectedHour, selectedMin))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }

                if (recurrence == 1) {
                    Text(stringResource(R.string.scheduler_on_label), style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        weekDays.forEach { day ->
                            FilterChip(selected = selectedWeekDay == day, onClick = { selectedWeekDay = day },
                                label = { Text(day, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }

                if (recurrence == 2) {
                    OutlinedTextField(value = monthDay, onValueChange = { monthDay = it },
                        label = { Text(stringResource(R.string.scheduler_day_of_month)) }, singleLine = true)
                }

                if (recurrence == 3) {
                    OutlinedTextField(value = oneTimeDate, onValueChange = { oneTimeDate = it },
                        label = { Text(stringResource(R.string.scheduler_date_format)) }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onSave(name, prompt, buildCron(), intervalMinutes)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = selectedHour, initialMinute = selectedMin,
            onConfirm = { h, m -> selectedHour = h; selectedMin = m; showTimePicker = false },
            onDismiss = { showTimePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialHour: Int, initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduler_select_time)) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SchedulerEntryPoint {
    val taskScheduler: TaskScheduler
}
