@file:OptIn(ExperimentalMaterial3Api::class)

package com.pai.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.compose.ui.res.stringResource
import com.pai.android.R
import com.pai.android.agent.LogLevel
import com.pai.android.agent.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Экран терминала логов.
 * Показывает кольцевой буфер логов с фильтрацией, поиском и экспортом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTerminalScreen(navController: NavHostController) {
    var logs by remember { mutableStateOf(Logger.getEntries()) }
    var autoScroll by remember { mutableStateOf(true) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Подписка на обновления логов
    DisposableEffect(Unit) {
        val listener = { logs = Logger.getEntries() }
        Logger.addListener(listener)
        onDispose { Logger.removeListener(listener) }
    }

    // Применение фильтров к Logger
    LaunchedEffect(searchQuery, selectedLevel) {
        Logger.filterQuery = searchQuery
        Logger.filterLevel = selectedLevel
        logs = Logger.getEntries()
    }

    // Автоскролл
    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Цвета для уровней
    fun levelColor(level: LogLevel): Color = when (level) {
        LogLevel.DEBUG -> Color(0xFF9E9E9E)
        LogLevel.INFO -> Color(0xFF4CAF50)
        LogLevel.WARN -> Color(0xFFFF9800)
        LogLevel.ERROR -> Color(0xFFF44336)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_terminal_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        // TODO: заменить на stringResource
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Очистить
                    IconButton(onClick = { Logger.clear(); logs = emptyList() }) {
                        // TODO: заменить на stringResource
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                    // Экспорт (копирует логи в буфер + открывает Share Intent)
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                val text = Logger.export()
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("logs", text))
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, text.take(50000))
                                }
                                context.startActivity(android.content.Intent.createChooser(shareIntent, context.getString(R.string.log_terminal_export_title)))
                            } catch (_: Exception) {}
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.log_terminal_export))
                    }
                    // Автоскролл: вниз = ON, стоп = OFF
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            if (autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.Close,
                            contentDescription = if (autoScroll) stringResource(R.string.log_terminal_autoscroll_on) else stringResource(R.string.log_terminal_autoscroll_off),
                            tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Панель фильтров
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Поле поиска
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f).height(52.dp),
                    placeholder = { Text(stringResource(R.string.log_terminal_search_hint), fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        { IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.log_terminal_search_clear), modifier = Modifier.size(18.dp))
                        } }
                    } else null
                )

                // Фильтр уровня
                Box {
                    FilterChip(
                        selected = selectedLevel != null,
                        onClick = { showFilterMenu = !showFilterMenu },
                        label = { Text(selectedLevel?.tag ?: stringResource(R.string.log_terminal_filter_all), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.FilterList, null, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.log_terminal_filter_all)) }, onClick = { selectedLevel = null; showFilterMenu = false })
                        LogLevel.values().forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level.tag, color = levelColor(level)) },
                                onClick = { selectedLevel = level; showFilterMenu = false }
                            )
                        }
                    }
                }

                // Статус
                Text(
                    "${logs.size}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Список логов
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1A)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(logs, key = { System.identityHashCode(it) }) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: com.pai.android.agent.LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> Color(0xFF9E9E9E)
        LogLevel.INFO -> Color(0xFF4CAF50)
        LogLevel.WARN -> Color(0xFFFF9800)
        LogLevel.ERROR -> Color(0xFFF44336)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(
                when (entry.level) {
                    LogLevel.ERROR -> Color(0x33F44336)
                    LogLevel.WARN -> Color(0x33FF9800)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(2.dp)
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Время
        Text(
            text = entry.formattedTime,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF666666),
            modifier = Modifier.width(72.dp)
        )
        // Уровень
        Text(
            text = "[${entry.level.tag}]",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = levelColor,
            modifier = Modifier.width(48.dp)
        )
        // Тег
        Text(
            text = entry.tag,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF64B5F6),
            modifier = Modifier.width(80.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // Сообщение
        Text(
            text = entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFFE0E0E0),
            modifier = Modifier.weight(1f),
            maxLines = 5,
            overflow = TextOverflow.Ellipsis
        )
    }
}
