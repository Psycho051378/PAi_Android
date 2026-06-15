package com.pai.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.pai.android.R
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pai.android.agent.skills.EmailAccount
import com.pai.android.agent.skills.EmailSkill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailSettingsScreen(
    emailSkill: EmailSkill? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val skill = remember(emailSkill, context) { emailSkill ?: EmailSkill(context) }
    var accounts by remember { mutableStateOf(skill.listAccounts()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<EmailAccount?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.email_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
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
            Text(
                stringResource(R.string.email_settings_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            if (accounts.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        stringResource(R.string.email_settings_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                accounts.forEach { account ->
                    EmailAccountCard(
                        account = account,
                        onEdit = { editingAccount = account },
                        onDelete = {
                            skill.removeAccount(account.id)
                            accounts = skill.listAccounts() ?: emptyList()
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.email_settings_add))
            }
        }
    }

    if (showAddDialog) {
        EmailAccountDialog(
            account = null,
            onDismiss = { showAddDialog = false },
            onSave = { account ->
                skill.addAccount(account)
                accounts = skill.listAccounts() ?: emptyList()
                showAddDialog = false
            }
        )
    }

    if (editingAccount != null) {
        EmailAccountDialog(
            account = editingAccount,
            onDismiss = { editingAccount = null },
            onSave = { account ->
                skill.addAccount(account)
                accounts = skill.listAccounts() ?: emptyList()
                editingAccount = null
            }
        )
    }
}

@Composable
private fun EmailAccountCard(
    account: EmailAccount,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(account.displayName, style = MaterialTheme.typography.titleSmall)
                Text(account.username, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("IMAP: ${account.imapServer}:${account.imapPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Create, stringResource(R.string.email_settings_edit), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmailAccountDialog(
    account: EmailAccount?,
    onDismiss: () -> Unit,
    onSave: (EmailAccount) -> Unit
) {
    val isEdit = account != null
    var displayName by remember(account) { mutableStateOf(account?.displayName ?: "") }
    var email by remember(account) { mutableStateOf(account?.username ?: "") }
    var password by remember(account) { mutableStateOf(account?.password ?: "") }
    var imapServer by remember(account) { mutableStateOf(account?.imapServer ?: "imap.mail.ru") }
    var imapPort by remember(account) { mutableStateOf((account?.imapPort ?: 993).toString()) }
    var smtpServer by remember(account) { mutableStateOf(account?.smtpServer ?: "smtp.mail.ru") }
    var smtpPort by remember(account) { mutableStateOf((account?.smtpPort ?: 465).toString()) }
    var showPassword by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) stringResource(R.string.email_settings_edit_title) else stringResource(R.string.email_settings_add_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.email_settings_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.email_settings_email_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.email_settings_password_label)) },
                    visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.email_settings_password_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) stringResource(R.string.email_settings_hide_advanced) else stringResource(R.string.email_settings_advanced))
                }

                if (showAdvanced) {
                    OutlinedTextField(
                        value = imapServer,
                        onValueChange = { imapServer = it },
                        label = { Text(stringResource(R.string.email_settings_imap_server)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = imapPort,
                        onValueChange = { imapPort = it },
                        label = { Text(stringResource(R.string.email_settings_imap_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = smtpServer,
                        onValueChange = { smtpServer = it },
                        label = { Text(stringResource(R.string.email_settings_smtp_server)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = smtpPort,
                        onValueChange = { smtpPort = it },
                        label = { Text(stringResource(R.string.email_settings_smtp_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = if (isEdit) account!!.id else email.substringBefore("@").ifBlank { "mail_${System.currentTimeMillis()}" }
                    val account = EmailAccount(
                        id = id,
                        displayName = displayName.ifBlank { email },
                        imapServer = imapServer,
                        imapPort = imapPort.toIntOrNull() ?: 993,
                        smtpServer = smtpServer,
                        smtpPort = smtpPort.toIntOrNull() ?: 465,
                        username = email,
                        password = password
                    )
                    onSave(account)
                },
                enabled = email.isNotBlank() && password.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
