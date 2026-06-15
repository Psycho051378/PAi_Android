package com.pai.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pai.android.R
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pai.android.agent.skills.ExternalSkillRepository
import com.pai.android.agent.skills.SkillIndexEntry
import com.pai.android.agent.skills.SkillManifest
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillStoreScreen(navController: NavHostController) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val repository = remember(activity) {
        activity?.let {
            try {
                val entry = EntryPointAccessors.fromApplication(
                    it.applicationContext, SkillStoreEntryPoint::class.java
                )
                entry.externalSkillRepository
            } catch (e: Exception) { null }
        }
    }

    var available by remember { mutableStateOf<List<SkillIndexEntry>>(emptyList()) }
    var installed by remember { mutableStateOf<List<SkillManifest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var installingId by remember { mutableStateOf<String?>(null) }
    var deletingSkill by remember { mutableStateOf<SkillManifest?>(null) }
    val scope = rememberCoroutineScope()

    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        repository?.let {
            it.refreshLocalSkills()
            installed = it.getInstalledSkills()
            available = it.fetchAvailableSkills()
            loading = false
        }
    }

    // Диалог подтверждения удаления
    if (deletingSkill != null) {
        AlertDialog(
            onDismissRequest = { deletingSkill = null },
            title = { Text(stringResource(R.string.skill_store_delete_title)) },
            text = { Text(stringResource(R.string.skill_store_delete_message, deletingSkill!!.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val skill = deletingSkill!!
                    deletingSkill = null
                    scope.launch {
                        repository?.removeInstalledSkill(skill.id)
                        installed = repository?.getInstalledSkills() ?: emptyList()
                        available = repository?.fetchAvailableSkills() ?: emptyList()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingSkill = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skill_store_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.close))
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(stringResource(R.string.skill_store_available), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }

                if (available.isEmpty()) {
                    item {
                        Text(stringResource(R.string.skill_store_no_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                items(available) { entry ->
                    val isInstalled = installed.any { it.id == entry.id }
                    val isInstalling = installingId == entry.id
                    SkillStoreCard(
                        entry = entry,
                        isInstalled = isInstalled,
                        isInstalling = isInstalling,
                        onInstall = {
                            installingId = entry.id
                            scope.launch {
                                val manifest = repository?.fetchManifest(entry.manifest_url)
                                if (manifest != null) {
                                    repository?.saveInstalledSkill(manifest)
                                    installed = repository?.getInstalledSkills() ?: emptyList()
                                }
                                installingId = null
                            }
                        }
                    )
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.skill_store_builtin), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }
                
                item {
                    var pyEnabled by remember { mutableStateOf(com.pai.android.agent.skills.PythonSkill.enabled) }
                    BuiltinSkillCard(
                        name = stringResource(R.string.skill_store_python_name),
                        description = stringResource(R.string.skill_store_python_desc),
                        version = "1.0",
                        enabled = pyEnabled,
                        onToggle = {
                            pyEnabled = !pyEnabled
                            com.pai.android.agent.skills.PythonSkill.enabled = pyEnabled
                        }
                    )
                }

                item {
                    var homeEnabled by remember { mutableStateOf(com.pai.android.agent.skills.HomeSkill.enabled) }
                    BuiltinSkillCard(
                        name = stringResource(R.string.skill_store_home_name),
                        description = stringResource(R.string.skill_store_home_desc),
                        version = "1.0",
                        enabled = homeEnabled,
                        onToggle = {
                            homeEnabled = !homeEnabled
                            com.pai.android.agent.skills.HomeSkill.enabled = homeEnabled
                        },
                        onConfigure = { navController.navigate("router_settings") }
                    )
                }

                item {
                    var emailEnabled by remember { mutableStateOf(com.pai.android.agent.skills.EmailSkill.enabled) }
                    var showEmailDialog by remember { mutableStateOf(false) }
                    BuiltinSkillCard(
                        name = stringResource(R.string.skill_store_email_name),
                        description = stringResource(R.string.skill_store_email_desc),
                        version = "1.0",
                        enabled = emailEnabled,
                        onToggle = {
                            emailEnabled = !emailEnabled
                            com.pai.android.agent.skills.EmailSkill.enabled = emailEnabled
                        },
                        onConfigure = { showEmailDialog = true }
                    )
                    if (showEmailDialog) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val emSkill = remember { com.pai.android.agent.skills.EmailSkill(ctx) }
                        EmailAccountsDialog(emailSkill = emSkill, onDismiss = { showEmailDialog = false })
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.skill_store_installed_header), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }

                if (installed.isEmpty()) {
                    item {
                        Text(stringResource(R.string.skill_store_no_installed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                items(installed) { skill ->
                    InstalledSkillCard(
                        skill = skill,
                        onToggle = {
                            scope.launch {
                                repository?.toggleSkill(skill.id, !skill.enabled)
                                installed = repository?.getInstalledSkills() ?: emptyList()
                            }
                        },
                        onRemove = {
                            deletingSkill = skill
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BuiltinSkillCard(
    name: String,
    description: String,
    version: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    onConfigure: (() -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("v$version" + " · " + stringResource(R.string.skill_store_builtin_tag), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(checked = enabled, onCheckedChange = { onToggle() })
                if (onConfigure != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onConfigure, modifier = Modifier.height(28.dp)) {
                        Text(stringResource(R.string.skill_store_configure), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillStoreCard(
    entry: SkillIndexEntry,
    isInstalled: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                Text(entry.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("v${entry.version}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (isInstalled) {
                Text(stringResource(R.string.skill_store_installed), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium)
            } else if (isInstalling) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                TextButton(onClick = onInstall) { Text(stringResource(R.string.skill_store_install)) }
            }
        }
    }
}

@Composable
private fun InstalledSkillCard(
    skill: SkillManifest,
    onToggle: () -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.titleSmall,
                    color = if (skill.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Text(skill.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = skill.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmailAccountsDialog(
    emailSkill: com.pai.android.agent.skills.EmailSkill,
    onDismiss: () -> Unit
) {
    var accounts by remember { mutableStateOf(emailSkill.listAccounts()) }
    var showAdd by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<com.pai.android.agent.skills.EmailAccount?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_store_email_accounts_title)) },
        text = {
            Column {
                if (accounts.isEmpty()) {
                    Text(stringResource(R.string.skill_store_email_no_accounts))
                } else {
                    accounts.forEach { acc ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(acc.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(acc.username, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editingAccount = acc }) {
                                Icon(Icons.Default.Create, stringResource(R.string.skill_store_email_add_account), tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = {
                                emailSkill.removeAccount(acc.id)
                                accounts = emailSkill.listAccounts()
                            }) {
                                Icon(Icons.Default.Delete, "", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.skill_store_email_add_account))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.skill_store_email_done)) } }
    )

    if (editingAccount != null) {
        val editAcc = editingAccount!!
        var editName by remember(editAcc) { mutableStateOf(editAcc.displayName) }
        var editEmail by remember(editAcc) { mutableStateOf(editAcc.username) }
        var editPass by remember(editAcc) { mutableStateOf(editAcc.password) }
        var showPwdEdit by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { editingAccount = null },
            title = { Text(stringResource(R.string.skill_store_email_edit_title)) },
            text = {
                Column {
                    OutlinedTextField(value = editName, onValueChange = { editName = it },
                        label = { Text(stringResource(R.string.skill_store_email_name_label)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = editEmail, onValueChange = { editEmail = it },
                        label = { Text(stringResource(R.string.skill_store_email_label)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = editPass, onValueChange = { editPass = it },
                        label = { Text(stringResource(R.string.skill_store_email_app_password)) },
                        visualTransformation = if (showPwdEdit) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showPwdEdit = !showPwdEdit }) {
                                Icon(if (showPwdEdit) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = editAcc.copy(
                        displayName = editName.ifBlank { editEmail },
                        username = editEmail,
                        password = editPass
                    )
                    emailSkill.addAccount(updated)
                    accounts = emailSkill.listAccounts()
                    editingAccount = null
                }, enabled = editEmail.isNotBlank() && editPass.isNotBlank()) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editingAccount = null }) { Text("Отмена") } }
        )
    }

    if (showAdd) {
        var displayName by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var showPwd by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.skill_store_email_new_title)) },
            text = {
                Column {
                    OutlinedTextField(value = displayName, onValueChange = { displayName = it },
                        label = { Text(stringResource(R.string.skill_store_email_name_label)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = email, onValueChange = { email = it },
                        label = { Text(stringResource(R.string.skill_store_email_label)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text(stringResource(R.string.skill_store_email_app_password)) },
                        visualTransformation = if (showPwd) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showPwd = !showPwd }) {
                                Icon(if (showPwd) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val acc = com.pai.android.agent.skills.EmailAccount(
                        id = email.substringBefore("@"),
                        displayName = displayName.ifBlank { email },
                        imapServer = "imap.mail.ru",
                        smtpServer = "smtp.mail.ru",
                        username = email,
                        password = password
                    )
                    emailSkill.addAccount(acc)
                    accounts = emailSkill.listAccounts()
                    showAdd = false
                }, enabled = email.isNotBlank() && password.isNotBlank()) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SkillStoreEntryPoint {
    val externalSkillRepository: ExternalSkillRepository
}
