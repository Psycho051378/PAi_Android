package com.pai.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Notifications

import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pai.android.R
import com.pai.android.presentation.settings.LanguageSettingsViewModel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pai.android.ui.navigation.Screen
import kotlinx.coroutines.launch

/**
 * Модель пункта настроек.
 */
data class SettingsItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
    val color: Color
)

/**
 * Главный экран настроек приложения.
 * Содержит список всех разделов настроек для удобной навигации.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController
) {
    // Список всех разделов настроек
    val settingsItems = listOf(
        SettingsItem(
            id = "permissions",
            title = stringResource(R.string.settings_permissions),
            description = stringResource(R.string.settings_permissions_desc),
            icon = Icons.Default.Security,
            route = Screen.PermissionsSettings.route,
            color = Color(0xFFE91E63)
        ),
        SettingsItem(
            id = "ai_providers",
            title = stringResource(R.string.settings_ai_providers),
            description = stringResource(R.string.settings_ai_providers_desc),
            icon = Icons.Default.SettingsApplications,
            route = Screen.ProviderSettings.route,
            color = Color(0xFF6200EE) // Material Purple 700
        ),
        SettingsItem(
            id = "ai_roles",
            title = stringResource(R.string.settings_ai_roles),
            description = stringResource(R.string.settings_ai_roles_desc),
            icon = Icons.Default.Person,
            route = Screen.RoleList.route,
            color = Color(0xFF03DAC6) // Material Teal 200
        ),
        SettingsItem(
            id = "web_search",
            title = stringResource(R.string.settings_web_search),
            description = stringResource(R.string.settings_web_search_desc),
            icon = Icons.Default.Search,
            route = Screen.WebSearchSettings.route,
            color = Color(0xFF4CAF50)
        ),
        SettingsItem(
            id = "appearance",
            title = stringResource(R.string.settings_appearance),
            description = stringResource(R.string.settings_appearance_desc),
            icon = Icons.Default.Palette,
            route = Screen.AppearanceSettings.route,
            color = Color(0xFF9C27B0)
        ),
        SettingsItem(
            id = "proactive",
            title = stringResource(R.string.settings_proactive),
            description = stringResource(R.string.settings_proactive_desc),
            icon = Icons.Default.Notifications,
            route = Screen.ProactiveSettings.route,
            color = Color(0xFFFF9800)
        ),
        // TODO: В будущем добавить эти разделы
        /*
        SettingsItem(
            id = "network",
            title = "Сеть и подключение",
            description = "Прокси, таймауты, сетевые настройки",
            icon = Icons.Default.Wifi,
            route = "network_settings",
            color = Color(0xFF2196F3)
        ),
        SettingsItem(
            id = "storage",
            title = "Хранилище",
            description = "Кэш, история, управление данными",
            icon = Icons.Default.Storage,
            route = "storage_settings",
            color = Color(0xFF795548)
        ),
        SettingsItem(
            id = "security",
            title = "Безопасность",
            description = "Шифрование, приватность",
            icon = Icons.Default.Security,
            route = "security_settings",
            color = Color(0xFFF44336)
        ),
        SettingsItem(
            id = "developer",
            title = "Для разработчиков",
            description = "Отладка, логи, экспериментальные функции",
            icon = Icons.Default.DeveloperMode,
            route = "developer_settings",
            color = Color(0xFF607D8B)
        ),
        */
        SettingsItem(
            id = "memory_management",
            title = stringResource(R.string.settings_memory_management),
            description = stringResource(R.string.settings_memory_management_desc),
            icon = Icons.Default.Memory,
            route = Screen.MemoryManagement.route,
            color = Color(0xFF8BC34A)
        ),
        SettingsItem(
            id = "scheduler_tasks",
            title = stringResource(R.string.settings_scheduler_tasks),
            description = stringResource(R.string.settings_scheduler_tasks_desc),
            icon = Icons.Default.DateRange,
            route = Screen.SchedulerTasks.route,
            color = Color(0xFFFF9800)
        ),
        SettingsItem(
            id = "skill_store",
            title = stringResource(R.string.settings_skill_store),
            description = stringResource(R.string.settings_skill_store_desc),
            icon = Icons.Default.Extension,
            route = Screen.SkillStore.route,
            color = Color(0xFF9C27B0)
        ),
        SettingsItem(
            id = "voice_settings",
            title = stringResource(R.string.settings_voice),
            description = stringResource(R.string.settings_voice_desc),
            icon = Icons.Default.Mic,
            route = Screen.VoiceSettings.route,
            color = Color(0xFF00BCD4)
        ),
        SettingsItem(
            id = "log_terminal",
            title = stringResource(R.string.settings_log_terminal),
            description = stringResource(R.string.settings_log_terminal_desc),
            icon = Icons.Default.Storage,
            route = Screen.LogTerminal.route,
            color = Color(0xFF607D8B)
        ),
        SettingsItem(
            id = "about",
            title = stringResource(R.string.settings_about),
            description = stringResource(R.string.settings_about_desc),
            icon = Icons.Default.Info,
            route = Screen.About.route,
            color = Color(0xFF00BCD4)
        )
    )
    
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.settings_title), 
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
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
        val context = LocalContext.current
        val languageViewModel: LanguageSettingsViewModel = hiltViewModel()
        val currentLang = languageViewModel.getCurrentLang()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {

                // Основные настройки
                items(settingsItems) { item ->
                    SettingsItemCard(
                        item = item,
                        onClick = {
                            when (item.route) {
                                Screen.ProviderSettings.route,
                                Screen.RoleList.route,
                                Screen.WebSearchSettings.route,
                                Screen.AppearanceSettings.route,
                                Screen.PermissionsSettings.route,
                                Screen.ProactiveSettings.route,
                                Screen.MemoryManagement.route,
                                Screen.SchedulerTasks.route,
                                Screen.SkillStore.route,
                                Screen.LogTerminal.route,
                                Screen.VoiceSettings.route,
                                Screen.About.route -> {
                                    navController.navigate(item.route)
                                }
                            }
                        }
                    )
                }

                // Language switch section
                item {
                    LanguageToggleCard(
                        currentLang = currentLang,
                        onLanguageSelected = { lang ->
                            if (lang != currentLang) {
                                languageViewModel.setLanguage(lang, context as android.app.Activity)
                            }
                        }
                    )
                }

            }
        }
    }
}

@Composable
fun LanguageToggleCard(
    currentLang: String,
    onLanguageSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_language),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = { onLanguageSelected("ru") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "🇷🇺 ${stringResource(R.string.language_russian)}",
                        fontWeight = if (currentLang == "ru") FontWeight.Bold else FontWeight.Normal,
                        color = if (currentLang == "ru")
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
                TextButton(
                    onClick = { onLanguageSelected("en") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "🇬🇧 ${stringResource(R.string.language_english)}",
                        fontWeight = if (currentLang == "en") FontWeight.Bold else FontWeight.Normal,
                        color = if (currentLang == "en")
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Карточка пункта настроек.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsItemCard(
    item: SettingsItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = item.color,
                    modifier = Modifier.size(32.dp)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = item.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = item.description,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.settings_navigate),
                    tint = Color.Gray
                )
            }
        }
    }
}

/**
 * Вспомогательная функция для превью.
 */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    MaterialTheme {
        // Preview без навигации
        Box(modifier = Modifier.fillMaxSize()) {
            Text("SettingsScreen Preview")
        }
    }
}
