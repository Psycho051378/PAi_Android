package com.pai.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.pai.android.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import com.pai.android.ui.utils.gradientBackground
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pai.android.data.model.Role
import com.pai.android.presentation.settings.RoleViewModel
import com.pai.android.ui.components.ConfirmDialog
import com.pai.android.ui.components.RoleEditDialog
import com.pai.android.ui.utils.pressAnimation
import com.pai.android.ui.utils.softShadow

/**
 * Экран списка ролей AI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleListScreen(
    onBackClick: () -> Unit,
    viewModel: RoleViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    
    // Диалог подтверждения удаления
    var showDeleteDialog by remember { mutableStateOf(false) }
    var roleToDelete by remember { mutableStateOf<String?>(null) }
    
    // Диалог редактирования/создания роли
    var showEditDialog by remember { mutableStateOf(false) }
    var roleToEdit by remember { mutableStateOf<Role?>(null) }
    
    LaunchedEffect(Unit) {
        viewModel.loadRoles()
    }
    
    // Обработчик сохранения роли
    val onSaveRole = { role: Role ->
        if (role.id.isBlank()) {
            // Новая роль (id будет сгенерирован в Role модели)
            viewModel.createRole(
                name = role.name,
                description = role.description,
                systemPrompt = role.systemPrompt,
                temperature = role.temperature,
                maxTokens = role.maxTokens,
                isDefault = role.isDefault
            )
        } else {
            // Обновление существующей роли
            viewModel.updateRole(role)
        }
        showEditDialog = false
        roleToEdit = null
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.roles_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    roleToEdit = null
                    showEditDialog = true
                },
                modifier = Modifier.pressAnimation(),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
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
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                
                state.roles.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.roles_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.roles_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = paddingValues
                    ) {
                        items(state.roles, key = { it.id }) { role ->
                            RoleCard(
                                role = role,
                                onEditClick = {
                                    roleToEdit = role
                                    showEditDialog = true
                                },
                                onDeleteClick = {
                                    roleToDelete = role.id
                                    showDeleteDialog = true
                                },
                                onSetDefaultClick = { viewModel.setDefaultRole(role.id) },
                                modifier = Modifier
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(80.dp)) // Для FAB
                        }
                    }
                }
            }
        }
        
        // Диалог подтверждения удаления
        if (showDeleteDialog && roleToDelete != null) {
            ConfirmDialog(
                title = stringResource(R.string.delete) + "?",
                message = stringResource(R.string.roles_delete_message),
                onConfirm = {
                    viewModel.deleteRole(roleToDelete!!)
                    showDeleteDialog = false
                    roleToDelete = null
                },
                onDismiss = {
                    showDeleteDialog = false
                    roleToDelete = null
                }
            )
        }
        
        // Диалог редактирования/создания роли
        if (showEditDialog) {
            RoleEditDialog(
                role = roleToEdit,
                onSave = onSaveRole,
                onDismiss = {
                    showEditDialog = false
                    roleToEdit = null
                }
            )
        }
    }
}

/**
 * Карточка роли.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleCard(
    role: com.pai.android.data.model.Role,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSetDefaultClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onEditClick,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Заголовок с иконкой роли по умолчанию
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = role.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                IconButton(
                    onClick = onSetDefaultClick,
                    modifier = Modifier.pressAnimation().size(24.dp)
                ) {
                    Icon(
                        imageVector = if (role.isDefault) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (role.isDefault) stringResource(R.string.role_default) else stringResource(R.string.role_make_default),
                        tint = if (role.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }
            
            // Описание (если есть)
            role.description?.let { description ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Системный промпт (сокращённый)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = role.systemPrompt.take(100) + if (role.systemPrompt.length > 100) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            // Параметры роли (temperature, maxTokens)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                role.temperature?.let { temp ->
                    Text(
                        text = "${stringResource(R.string.role_temperature)}: ${"%.1f".format(temp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                role.maxTokens?.let { tokens ->
                    Text(
                        text = "${stringResource(R.string.role_max_tokens)}: $tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // Кнопки действий
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onEditClick,
                    modifier = Modifier.pressAnimation()
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                }
                
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.pressAnimation()
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }
}