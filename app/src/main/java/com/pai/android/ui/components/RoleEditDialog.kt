package com.pai.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import com.pai.android.R
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.pai.android.data.model.Role
import com.pai.android.ui.utils.focusAnimation
import com.pai.android.ui.utils.pressAnimation

/**
 * Диалог для создания или редактирования роли.
 */
@Composable
fun RoleEditDialog(
    role: Role? = null,
    onSave: (Role) -> Unit,
    onDismiss: () -> Unit
) {
    // Состояние формы
    var name by remember { mutableStateOf(role?.name ?: "") }
    var description by remember { mutableStateOf(role?.description ?: "") }
    var systemPrompt by remember { mutableStateOf(role?.systemPrompt ?: "") }
    var temperature by remember { mutableFloatStateOf(role?.temperature ?: 0.7f) }
    var maxTokens by remember { mutableIntStateOf(role?.maxTokens ?: 2000) }
    var isDefault by remember { mutableStateOf(role?.isDefault ?: false) }
    
    // Валидация
    val isNameValid = name.isNotBlank()
    val isPromptValid = systemPrompt.isNotBlank()
    val isTemperatureValid = temperature in 0.0f..2.0f
    val isMaxTokensValid = maxTokens in 1..100000
    val isFormValid = isNameValid && isPromptValid && isTemperatureValid && isMaxTokensValid
    
    // Фокус для первого поля
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (role == null) stringResource(R.string.role_create_title) else stringResource(R.string.role_edit_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Название роли
                val nameInteractionSource = remember { MutableInteractionSource() }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.role_name_label)) },
                    interactionSource = nameInteractionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusAnimation(nameInteractionSource)
                        .focusRequester(focusRequester),
                    isError = !isNameValid,
                    supportingText = {
                        if (!isNameValid) {
                            Text(stringResource(R.string.role_name_validation))
                        }
                    }
                )
                
                // Описание (необязательное)
                val descriptionInteractionSource = remember { MutableInteractionSource() }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.role_desc_label)) },
                    interactionSource = descriptionInteractionSource,
                    modifier = Modifier.fillMaxWidth().focusAnimation(descriptionInteractionSource),
                    maxLines = 2
                )
                
                // Системный промпт
                val promptInteractionSource = remember { MutableInteractionSource() }
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.role_system_prompt_label)) },
                    interactionSource = promptInteractionSource,
                    modifier = Modifier.fillMaxWidth().focusAnimation(promptInteractionSource),
                    isError = !isPromptValid,
                    supportingText = {
                        if (!isPromptValid) {
                            Text(stringResource(R.string.role_prompt_validation))
                        }
                    },
                    maxLines = 4
                )
                
                // Параметры в одной строке
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Температура
                    val temperatureInteractionSource = remember { MutableInteractionSource() }
                    OutlinedTextField(
                        value = temperature.toString(),
                        onValueChange = {
                            it.toFloatOrNull()?.let { value ->
                                if (value in 0.0f..2.0f) temperature = value
                            }
                        },
                        label = { Text(stringResource(R.string.role_temperature_label)) },
                        interactionSource = temperatureInteractionSource,
                        modifier = Modifier.weight(1f).focusAnimation(temperatureInteractionSource),
                        isError = !isTemperatureValid,
                        supportingText = {
                            if (!isTemperatureValid) {
                                Text("0.0 - 2.0")
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    
                    // Максимальное количество токенов
                    val maxTokensInteractionSource = remember { MutableInteractionSource() }
                    OutlinedTextField(
                        value = maxTokens.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { value ->
                                if (value in 1..100000) maxTokens = value
                            }
                        },
                        label = { Text(stringResource(R.string.role_max_tokens_label)) },
                        interactionSource = maxTokensInteractionSource,
                        modifier = Modifier.weight(1f).focusAnimation(maxTokensInteractionSource),
                        isError = !isMaxTokensValid,
                        supportingText = {
                            if (!isMaxTokensValid) {
                                Text("1 - 100000")
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                
                // Роль по умолчанию
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isDefault,
                        onCheckedChange = { isDefault = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.role_make_default),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // Предпросмотр промпта
                if (systemPrompt.isNotBlank()) {
                    Column {
                        Text(
                            text = stringResource(R.string.role_preview_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = systemPrompt.take(150) + if (systemPrompt.length > 150) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 3
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isFormValid) {
                        val newRole = Role(
                            id = role?.id ?: "",
                            name = name,
                            description = if (description.isBlank()) null else description,
                            systemPrompt = systemPrompt,
                            temperature = if (temperature != 0.7f) temperature else null,
                            maxTokens = if (maxTokens != 2000) maxTokens else null,
                            isDefault = isDefault,
                            createdAt = role?.createdAt ?: System.currentTimeMillis()
                        )
                        onSave(newRole)
                    }
                },
                enabled = isFormValid,
                modifier = Modifier.pressAnimation()
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.pressAnimation()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}