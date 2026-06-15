package com.pai.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.pai.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pai.android.data.model.ThemeMode
import com.pai.android.presentation.settings.ThemeViewModel
import com.pai.android.ui.components.HintText
import com.pai.android.ui.utils.toggleSlideAnimation
import kotlinx.coroutines.launch

/**
 * Экран настроек внешнего вида приложения.
 * Позволяет настраивать тему, цвета и другие визуальные параметры.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    navController: NavController? = null,
    viewModel: ThemeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // Показываем ошибки в snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            scope.launch {
                snackbarHostState.showSnackbar(error)
            }
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.appearance_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (navController != null) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Раздел: Тема
                HintText(
                    text = stringResource(R.string.appearance_theme_section),
                    hintResId = R.string.hint_settings_appearance,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeOptionItem(
                            icon = Icons.Default.BrightnessAuto,
                            title = stringResource(R.string.appearance_theme_system),
                            description = stringResource(R.string.appearance_theme_system_desc),
                            themeMode = ThemeMode.SYSTEM,
                            currentThemeMode = state.themeMode,
                            onThemeSelected = { viewModel.setThemeMode(it) }
                        )
                        
                        Divider()
                        
                        ThemeOptionItem(
                            icon = Icons.Default.BrightnessHigh,
                            title = stringResource(R.string.appearance_theme_light),
                            description = stringResource(R.string.appearance_theme_light_desc),
                            themeMode = ThemeMode.LIGHT,
                            currentThemeMode = state.themeMode,
                            onThemeSelected = { viewModel.setThemeMode(it) }
                        )
                        
                        Divider()
                        
                        ThemeOptionItem(
                            icon = Icons.Default.BrightnessLow,
                            title = stringResource(R.string.appearance_theme_dark),
                            description = stringResource(R.string.appearance_theme_dark_desc),
                            themeMode = ThemeMode.DARK,
                            currentThemeMode = state.themeMode,
                            onThemeSelected = { viewModel.setThemeMode(it) }
                        )
                    }
                }
                
                // Раздел: Цвета
                // TODO: add string resource for "Цвета"
                Text(
                    text = stringResource(R.string.appearance_colors_section),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Динамические цвета (Material You)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.ColorLens,
                                    contentDescription = stringResource(R.string.appearance_dynamic_colors),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                
                                Column {
                                    Text(
                                        text = stringResource(R.string.appearance_dynamic_colors),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.appearance_dynamic_colors_desc),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            
                            Switch(
                                checked = state.useDynamicColor,
                                onCheckedChange = { viewModel.setUseDynamicColor(it) },
                                modifier = Modifier.toggleSlideAnimation(
                                    isChecked = state.useDynamicColor,
                                    slideDistance = 8.dp
                                )
                            )
                        }
                    }
                }
                
                // Раздел: Сброс
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.appearance_reset),
                                tint = MaterialTheme.colorScheme.error
                            )
                            
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.appearance_reset),
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = stringResource(R.string.appearance_reset_defaults),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            
                            IconButton(
                                onClick = { viewModel.resetToDefaults() }
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.appearance_reset),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                
                // Информация о текущей теме
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.appearance_info_section),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.appearance_current_theme))
                            Text(
                                text = when (state.themeMode) {
                                    com.pai.android.data.model.ThemeMode.LIGHT -> stringResource(R.string.appearance_theme_light)
                                    com.pai.android.data.model.ThemeMode.DARK -> stringResource(R.string.appearance_theme_dark)
                                    com.pai.android.data.model.ThemeMode.SYSTEM -> stringResource(R.string.appearance_theme_system)
                                },
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.appearance_modern_colors_status))
                            Text(
                                text = if (state.useModernColors) stringResource(R.string.appearance_enabled) else stringResource(R.string.appearance_disabled),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.appearance_dynamic_colors_status))
                            Text(
                                text = if (state.useDynamicColor) stringResource(R.string.appearance_enabled) else stringResource(R.string.appearance_disabled),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Элемент выбора темы с радио-кнопкой.
 */
@Composable
private fun ThemeOptionItem(
    icon: ImageVector,
    title: String,
    description: String,
    themeMode: ThemeMode,
    currentThemeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = currentThemeMode == themeMode,
            onClick = { onThemeSelected(themeMode) }
        )
        
        Icon(
            icon,
            contentDescription = title,
            modifier = Modifier.padding(start = 8.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun AppearanceSettingsScreenPreview() {
    MaterialTheme {
        AppearanceSettingsScreen()
    }
}