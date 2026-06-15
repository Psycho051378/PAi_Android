package com.pai.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pai.android.presentation.memory.MemoryManagementViewModel
import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.model.DailyMemory
import com.pai.android.data.export.MemoryImporter
import com.pai.android.R
import com.pai.android.ui.components.ConfirmDialog
import com.pai.android.ui.components.FactEditDialog
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import com.pai.android.ui.components.FactItem
import com.pai.android.ui.components.DailyMemoryItem
import com.pai.android.ui.components.HintText
import java.text.SimpleDateFormat
import java.util.*

/**
 * Панель поиска для экрана управления памятью.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.memory_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(
                    onClick = { onQueryChange("") }
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.memory_clear_search))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large
    )
}

/**
 * AppBar для режима выбора.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionModeAppBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancelSelection: () -> Unit,
    navController: NavController? = null
) {
    CenterAlignedTopAppBar(
        title = { Text(stringResource(R.string.memory_selected_count, selectedCount), fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        navigationIcon = {
            IconButton(
                onClick = onCancelSelection
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.memory_cancel_selection))
            }
        },
        actions = {
            // Кнопка "Выбрать все"
            IconButton(
                onClick = onSelectAll
            ) {
                Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.memory_select_all))
            }
            // Кнопка "Удалить выбранное"
            IconButton(
                onClick = onDeleteSelected
            ) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.memory_delete_selected), tint = MaterialTheme.colorScheme.error)
            }
        }
    )
}

/**
 * Экран управления памятью (факты и дневные записи).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MemoryManagementScreen(
    navController: NavController? = null,
    viewModel: MemoryManagementViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var hintDialogVisible by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            if (state.isSelectionMode) {
                SelectionModeAppBar(
                    selectedCount = state.selectedFactIds.size,
                    onSelectAll = { viewModel.selectAllFacts() },
                    onDeleteSelected = { viewModel.showBulkDeleteDialog() },
                    onCancelSelection = { viewModel.exitSelectionMode() },
                    navController = navController
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.memory_title), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    navigationIcon = {
                        if (navController != null) {
                            IconButton(
                                onClick = { navController.popBackStack() }
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        // Кнопка входа в режим выбора (только на вкладке фактов)
                        if (state.selectedTab == 0 && state.facts.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.enterSelectionMode() }
                            ) {
                                Icon(Icons.Default.CheckBox, contentDescription = stringResource(R.string.memory_select))
                            }
                        }
                        IconButton(
                            onClick = { viewModel.loadData() }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.memory_refresh))
                        }
                        IconButton(
                            onClick = { hintDialogVisible = !hintDialogVisible }
                        ) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.hint_icon_label))
                        }
                        
                        // Меню экспорта/импорта
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { expanded = true }
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.memory_more))
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memory_export)) },
                                    onClick = {
                                        expanded = false
                                        viewModel.exportMemory()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.FileDownload, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memory_import)) },
                                    onClick = {
                                        expanded = false
                                        viewModel.showImportDialog()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.FileUpload, contentDescription = null)
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Поиск
                    SearchBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery
                    )
                    
                    // Вкладки: факты / дневные записи
                    TabRow(selectedTabIndex = state.selectedTab) {
                        Tab(
                            selected = state.selectedTab == 0,
                            onClick = { viewModel.selectTab(0) },
                            text = { Text(stringResource(R.string.memory_tab_facts)) },
                            icon = { Icon(Icons.Default.Memory, contentDescription = null) }
                        )
                        Tab(
                            selected = state.selectedTab == 1,
                            onClick = { viewModel.selectTab(1) },
                            text = { Text(stringResource(R.string.memory_tab_daily)) },
                            icon = { Icon(Icons.Default.Book, contentDescription = null) }
                        )
                    }
                    
                    when (state.selectedTab) {
                        0 -> FactListScreen(state, viewModel)
                        1 -> DailyMemoryScreen(state, viewModel)
                    }
                }
            }
            
            // Сообщения об ошибках
            state.errorMessage?.let { error ->
                LaunchedEffect(error) {
                    // TODO: Показать Snackbar
                }
            }
            
            // Диалоги
            if (state.showDeleteDialog) {
                ConfirmDialog(
                    title = stringResource(R.string.memory_delete_fact_title),
                    message = stringResource(R.string.memory_delete_fact_message, state.factToDelete?.key ?: ""),
                    onConfirm = {
                        state.factToDelete?.let { viewModel.deleteFact(it) }
                    },
                    onDismiss = viewModel::closeDeleteDialog
                )
            }
            
            if (state.showBulkDeleteDialog) {
                ConfirmDialog(
                    title = stringResource(R.string.memory_bulk_delete_title),
                    // TODO: заменить на stringResource c format
                message = stringResource(R.string.memory_bulk_delete_message, state.selectedFactIds.size),
                    onConfirm = {
                        viewModel.deleteSelectedFacts()
                    },
                    onDismiss = viewModel::closeBulkDeleteDialog
                )
            }
            
            if (state.showEditDialog) {
                FactEditDialog(
                    fact = state.factToEdit,
                    key = state.editKey,
                    value = state.editValue,
                    confidence = state.editConfidence,
                    onKeyChange = viewModel::updateEditKey,
                    onValueChange = viewModel::updateEditValue,
                    onConfidenceChange = viewModel::updateEditConfidence,
                    onSave = viewModel::saveEditedFact,
                    onDismiss = viewModel::closeEditDialog
                )
            }
            
            // Диалог экспорта
            if (state.showExportDialog) {
                ExportDialog(
                    markdown = state.exportMarkdown,
                    onDismiss = viewModel::closeExportDialog
                )
            }
            
            // Диалог импорта
            if (state.showImportDialog) {
                ImportDialog(
                    importText = state.importText,
                    importResult = state.importResult,
                    onImportTextChange = viewModel::updateImportText,
                    onImport = viewModel::importMemory,
                    onDismiss = viewModel::closeImportDialog,
                    onClearResult = viewModel::clearImportResult
                )
            }
            
            if (hintDialogVisible) {
                com.pai.android.ui.components.HintDialog(
                    hintText = stringResource(R.string.hint_settings_memory),
                    onDismiss = { hintDialogVisible = false }
                )
            }
        }
    }
}

/**
 * Экран списка фактов.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FactListScreen(
    state: com.pai.android.presentation.memory.MemoryManagementState,
    viewModel: MemoryManagementViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Фильтры по scope
        ScopeFilterBar(state.selectedScope, viewModel::selectScope)
        
        // Список фактов
        if (state.facts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.memory_facts_empty),
                    fontSize = 18.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(state.facts) { fact ->
                    FactItem(
                        fact = fact,
                        onEditClick = { viewModel.showEditDialog(fact) },
                        onDeleteClick = { viewModel.showDeleteDialog(fact) },
                        onConfirmClick = { viewModel.confirmFact(fact) },
                        isSelectionMode = state.isSelectionMode,
                        isSelected = fact.id in state.selectedFactIds,
                        onSelectToggle = {
                            if (state.isSelectionMode) {
                                viewModel.toggleFactSelection(fact.id)
                            }
                        },
                        onLongClick = {
                            if (!state.isSelectionMode) {
                                viewModel.enterSelectionMode(fact.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Экран дневных записей.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyMemoryScreen(
    state: com.pai.android.presentation.memory.MemoryManagementState,
    viewModel: MemoryManagementViewModel
) {
    if (state.dailyEntries.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.memory_daily_empty),
                fontSize = 18.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(state.dailyEntries) { daily ->
                DailyMemoryItem(
                    daily = daily,
                    onDeleteClick = { viewModel.deleteDailyEntry(daily.date) }
                )
            }
        }
    }
}

/**
 * Панель фильтрации по scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeFilterBar(
    selectedScope: String,
    onScopeSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            "all" to stringResource(R.string.memory_scope_all),
            "user" to stringResource(R.string.memory_scope_user),
            "ai" to stringResource(R.string.memory_scope_ai),
            "global" to stringResource(R.string.memory_scope_global)
        ).forEach { (scope, label) ->
            FilterChip(
                selected = selectedScope == scope,
                onClick = { onScopeSelected(scope) },
                label = { Text(label) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * Диалог экспорта памяти.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportDialog(
    markdown: String?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 600.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Заголовок
                Text(
                    text = stringResource(R.string.memory_export_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // Информация
                Text(
                    text = stringResource(R.string.memory_export_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Поле с Markdown (только для чтения)
                OutlinedTextField(
                    value = markdown ?: stringResource(R.string.loading),
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    readOnly = true,
                    label = { Text(stringResource(R.string.memory_export_markdown_label)) },
                    singleLine = false,
                    maxLines = 20,
                    shape = MaterialTheme.shapes.medium
                )
                
                // Кнопки действий
                val context = LocalContext.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.close))
                    }
                    
                    Button(
                        onClick = {
                            markdown?.let { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Pai Memory Export", text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, context.getString(R.string.memory_export_copied), Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        },
                        enabled = markdown != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.memory_export_copy))
                    }
                }
            }
        }
    }
}

/**
 * Диалог импорта памяти.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportDialog(
    importText: String,
    importResult: MemoryImporter.ImportResult?,
    onImportTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    onClearResult: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 300.dp, max = 700.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Заголовок
                Text(
                    text = stringResource(R.string.memory_import_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // Информация
                Text(
                    text = stringResource(R.string.memory_import_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Результат импорта (если есть)
                importResult?.let { result ->
                    ImportResultView(result = result, onClear = onClearResult)
                }
                
                // Поле для ввода Markdown
                OutlinedTextField(
                    value = importText,
                    onValueChange = onImportTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    label = { Text(stringResource(R.string.memory_import_text_label)) },
                    placeholder = { Text(stringResource(R.string.memory_import_placeholder)) },
                    singleLine = false,
                    maxLines = 15,
                    shape = MaterialTheme.shapes.medium
                )
                
                // Кнопки действий
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    
                    Button(
                        onClick = onImport,
                        enabled = importText.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.memory_import_button))
                    }
                }
            }
        }
    }
}

/**
 * Отображение результата импорта.
 */
@Composable
private fun ImportResultView(
    result: MemoryImporter.ImportResult,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.isSuccess) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (result.isSuccess) stringResource(R.string.memory_import_success) else stringResource(R.string.memory_import_errors),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (result.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }
            
            Text(
                text = stringResource(R.string.memory_import_stats, result.facts.size, result.dailyEntries.size),
                style = MaterialTheme.typography.bodySmall
            )
            
            if (result.errors.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.memory_import_errors_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                result.errors.forEach { error ->
                    Text(
                        text = "• $error",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}