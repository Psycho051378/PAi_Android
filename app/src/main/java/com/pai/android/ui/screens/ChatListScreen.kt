package com.pai.android.ui.screens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.pai.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pai.android.data.model.Chat
import com.pai.android.presentation.chat.ChatListViewModel
import kotlinx.coroutines.launch

/**
 * Группа чатов с заголовком (например, "Сегодня", "Вчера" и т.д.)
 */
sealed class ChatGroup(val id: String, @StringRes val titleRes: Int) {
    data class Today(val chats: List<Chat>) : ChatGroup("today", R.string.chat_list_today)
    data class Yesterday(val chats: List<Chat>) : ChatGroup("yesterday", R.string.chat_list_yesterday)
    data class ThisWeek(val chats: List<Chat>) : ChatGroup("this_week", R.string.chat_list_this_week)
    data class ThisMonth(val chats: List<Chat>) : ChatGroup("this_month", R.string.chat_list_this_month)
    data class Earlier(val chats: List<Chat>) : ChatGroup("earlier", R.string.chat_list_earlier)
    data class Archived(val chats: List<Chat>) : ChatGroup("archived", R.string.chat_list_archive)
}

/**
 * Группирует чаты по дате создания для удобной навигации.
 * Отдельно выделяет архивные чаты.
 */
private fun groupChatsByDate(chats: List<Chat>): List<ChatGroup> {
    // Получаем текущий календарь
    val nowCal = java.util.Calendar.getInstance()
    val todayStartCal = java.util.Calendar.getInstance().apply {
        timeInMillis = nowCal.timeInMillis
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val todayStart = todayStartCal.timeInMillis
    
    // Разделяем архивные и активные чаты
    val (archived, active) = chats.partition { it.isArchived }
    
    // Группируем активные чаты по дате
    val groups = mutableListOf<ChatGroup>()
    
    val todayChats = mutableListOf<Chat>()
    val yesterdayChats = mutableListOf<Chat>()
    val thisWeekChats = mutableListOf<Chat>()
    val thisMonthChats = mutableListOf<Chat>()
    val earlierChats = mutableListOf<Chat>()
    
    for (chat in active) {
        val chatCal = java.util.Calendar.getInstance().apply {
            timeInMillis = chat.createdAt
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        
        val daysBetween = ((todayStart - chatCal.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()
        
        when {
            daysBetween == 0 -> todayChats.add(chat)
            daysBetween == 1 -> yesterdayChats.add(chat)
            daysBetween <= 7 -> thisWeekChats.add(chat)
            chatCal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
                    chatCal.get(java.util.Calendar.MONTH) == nowCal.get(java.util.Calendar.MONTH) -> thisMonthChats.add(chat)
            else -> earlierChats.add(chat)
        }
    }
    
    // Добавляем группы только если в них есть чаты
    if (todayChats.isNotEmpty()) groups.add(ChatGroup.Today(todayChats))
    if (yesterdayChats.isNotEmpty()) groups.add(ChatGroup.Yesterday(yesterdayChats))
    if (thisWeekChats.isNotEmpty()) groups.add(ChatGroup.ThisWeek(thisWeekChats))
    if (thisMonthChats.isNotEmpty()) groups.add(ChatGroup.ThisMonth(thisMonthChats))
    if (earlierChats.isNotEmpty()) groups.add(ChatGroup.Earlier(earlierChats))
    if (archived.isNotEmpty()) groups.add(ChatGroup.Archived(archived))
    
    return groups
}

/**
 * Отображает группу чатов с заголовком-аккордеоном.
 */
@Composable
private fun ChatGroupSection(
    group: ChatGroup,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onChatClick: (String) -> Unit,
    onArchiveClick: (String, Boolean) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        // Заголовок группы с кнопкой аккордеона
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) stringResource(R.string.chat_list_collapse) else stringResource(R.string.chat_list_expand),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(group.titleRes),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }
        
        // Чаты в группе (показываются только если группа раскрыта)
        if (isExpanded) {
            val chats = when (group) {
                is ChatGroup.Today -> group.chats
                is ChatGroup.Yesterday -> group.chats
                is ChatGroup.ThisWeek -> group.chats
                is ChatGroup.ThisMonth -> group.chats
                is ChatGroup.Earlier -> group.chats
                is ChatGroup.Archived -> group.chats
            }
            
            chats.forEach { chat ->
                ChatItem(
                    chat = chat,
                    onChatClick = { onChatClick(chat.id) },
                    onArchiveClick = { onArchiveClick(chat.id, !chat.isArchived) },
                    onDeleteClick = { onDeleteClick(chat.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: ChatListViewModel = hiltViewModel(),
    onChatClick: (String) -> Unit = {}
) {
    val state = viewModel.state.collectAsState().value
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
                title = { Text(stringResource(R.string.chat_list_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    if (navController != null) {
                        // Единая кнопка настроек (ведущая на главный экран настроек)
                        IconButton(
                            onClick = { navController.navigate(com.pai.android.ui.navigation.Screen.Settings.route) }
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.createNewChat() }
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_list_new))
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
                state.chats.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.chat_list_empty),
                        fontSize = 18.sp,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = TextAlign.Center
                    )
                }
                else -> {
                    val chatGroups = groupChatsByDate(state.chats)
                    val expandedGroups = state.expandedGroups
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(chatGroups) { group ->
                            ChatGroupSection(
                                group = group,
                                isExpanded = expandedGroups[group.id] ?: true,
                                onToggle = { viewModel.toggleGroupExpanded(group.id) },
                                onChatClick = onChatClick,
                                onArchiveClick = { chatId, isArchived -> 
                                    viewModel.toggleArchiveChat(chatId, isArchived)
                                },
                                onDeleteClick = { chatId ->
                                    viewModel.showDeleteConfirmation(chatId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Диалог подтверждения удаления чата
    if (state.chatToDelete != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            title = { Text(stringResource(R.string.chat_list_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.chat_list_delete_message,
                        state.chatToDelete.title.ifEmpty { stringResource(R.string.chat_list_unnamed) }
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteChat() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text(stringResource(R.string.delete), modifier = Modifier.padding(start = 4.dp))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissDeleteConfirmation() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatItem(
    chat: Chat,
    onChatClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxSize(),
        onClick = onChatClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = chat.title.ifEmpty { stringResource(R.string.chat_list_unnamed) },
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.chat_item_messages, chat.messageCount, if (chat.isArchived) stringResource(R.string.chat_item_archived) else stringResource(R.string.chat_item_active)),
                fontSize = 14.sp,
                color = Color.Gray
            )
            Text(
                text = stringResource(R.string.chat_item_updated, java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(chat.updatedAt))),
                fontSize = 12.sp,
                color = Color.LightGray
            )
            
            // Кнопки действий
            Row {
                IconButton(onClick = onArchiveClick) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = if (chat.isArchived) stringResource(R.string.chat_item_unarchive) else stringResource(R.string.chat_item_archive),
                        tint = if (chat.isArchived) Color.Blue else Color.Gray
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = Color.Red
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ChatListScreenPreview() {
    MaterialTheme {
        ChatListScreen()
    }
}