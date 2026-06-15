package com.pai.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pai.android.data.model.WebSearchProvider
import com.pai.android.presentation.settings.ConnectionTestResult
import com.pai.android.presentation.settings.WebSearchSettingsViewModel
import androidx.compose.ui.res.stringResource
import com.pai.android.R
import com.pai.android.ui.navigation.Screen
import com.pai.android.ui.components.HintText
import com.pai.android.ui.utils.pressAnimation
import com.pai.android.ui.utils.softShadow
import com.pai.android.ui.utils.toggleSlideAnimation
import com.pai.android.ui.utils.gradientBackground
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchSettingsScreen(
    navController: NavController? = null,
    viewModel: WebSearchSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val testResult by viewModel.testConnectionResult.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // Показываем результат тестирования в snackbar
    LaunchedEffect(testResult) {
        when (val result = testResult) {
            is ConnectionTestResult.Success -> {
                scope.launch {
                    snackbarHostState.showSnackbar(result.message)
                }
            }
            is ConnectionTestResult.Error -> {
                scope.launch {
                    snackbarHostState.showSnackbar(result.message)
                }
            }
            else -> {}
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { HintText(text = stringResource(R.string.web_search_title), hintResId = R.string.hint_settings_web_search) },
                navigationIcon = {
                    if (navController != null) {
                        IconButton(
                            onClick = { navController.navigateUp() },
                            modifier = Modifier.pressAnimation()
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors()
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Включение внешних API
                Card(
    modifier = Modifier
        .fillMaxWidth(),
    colors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    shape = MaterialTheme.shapes.medium
) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.web_search_external_apis),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = stringResource(R.string.web_search_external_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.web_search_use_external))
                            Switch(
                                checked = uiState.enabled,
                                onCheckedChange = { viewModel.updateEnabled(it) },
                                modifier = Modifier.pressAnimation()
                            )
                        }
                    }
                }
                
                Divider()
                
                // Выбор провайдера
                Text(
                    text = stringResource(R.string.web_search_provider),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WebSearchProvider.values()
                        .filter { it != WebSearchProvider.DUCKDUCKGO } // DuckDuckGo — встроенный fallback
                        .forEach { provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = uiState.provider == provider,
                                    onClick = { viewModel.updateProvider(provider) },
                                    role = Role.RadioButton
                                )
                                .padding(8.dp)
                                .pressAnimation(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.provider == provider,
                                onClick = null
                            )
                            Column(
                                modifier = Modifier.padding(start = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = provider.displayName,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = when (provider) {
                                        WebSearchProvider.GOOGLE -> stringResource(R.string.web_search_google_fields_hint, stringResource(R.string.web_search_api_key_label), stringResource(R.string.web_search_engine_id_label))
                                        WebSearchProvider.TAVILY -> stringResource(R.string.web_search_api_key_label)
                                        WebSearchProvider.DUCKDUCKGO -> ""
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                // Настройки Google Custom Search
                if (uiState.provider == WebSearchProvider.GOOGLE) {
                    Card(
    modifier = Modifier
        .fillMaxWidth(),
    colors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    shape = MaterialTheme.shapes.medium
) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.web_search_google_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            
                            OutlinedTextField(
                                value = uiState.googleApiKey,
                                onValueChange = { viewModel.updateGoogleApiKey(it) },
                                label = { Text(stringResource(R.string.web_search_api_key_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.web_search_tavily_key_placeholder)) }
                            )
                            
                            OutlinedTextField(
                                value = uiState.googleSearchEngineId,
                                onValueChange = { viewModel.updateGoogleSearchEngineId(it) },
                                label = { Text(stringResource(R.string.web_search_engine_id_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.web_search_engine_id_placeholder)) }
                            )
                            
                            Text(
                                text = stringResource(R.string.web_search_google_help),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // Настройки Tavily Search
                if (uiState.provider == WebSearchProvider.TAVILY) {
                    Card(
    modifier = Modifier
        .fillMaxWidth(),
    colors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    shape = MaterialTheme.shapes.medium
) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.web_search_tavily_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            
                            OutlinedTextField(
                                value = uiState.tavilyApiKey,
                                onValueChange = { viewModel.updateTavilyApiKey(it) },
                                label = { Text(stringResource(R.string.web_search_api_key_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.web_search_tavily_key_placeholder)) }
                            )
                            
                            Text(
                                text = stringResource(R.string.web_search_tavily_help),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // DuckDuckGo — встроенный fallback, не требует настроек
                // Навык WebSearchSkill использует DuckDuckGo HTML автоматически,
                // когда внешние API (Google/Tavily) не настроены или отключены.
                
                // Кнопки действий
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.testConnection() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressAnimation(),
                        enabled = uiState.enabled && viewModel.canPerformSearch()
                    ) {
                        if (testResult is ConnectionTestResult.Testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(stringResource(R.string.web_search_test_connection), modifier = Modifier.padding(start = 8.dp))
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            onClick = { navController?.navigateUp() },
                            modifier = Modifier
                                .weight(1f)
                                .pressAnimation()
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                viewModel.saveSettings()
                                navController?.navigateUp()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .pressAnimation()
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Text(stringResource(R.string.save), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                
                // Статус
                if (!viewModel.canPerformSearch()) {
                    Card(
    modifier = Modifier
        .fillMaxWidth(),
    colors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
    ),
    shape = MaterialTheme.shapes.medium
) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.web_search_unsaved_changes),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun WebSearchSettingsScreenPreview() {
    MaterialTheme {
        WebSearchSettingsScreen()
    }
}