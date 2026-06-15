package com.pai.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.pai.android.R
import com.pai.android.ui.utils.focusAnimation
import com.pai.android.ui.utils.pressAnimation

/**
 * Диалог подтверждения извлечённого факта.
 * Пользователь может подтвердить, исправить или отклонить факт.
 */
@Composable
fun FactConfirmationDialog(
    category: String,
    key: String,
    originalValue: String,
    confidence: Float,
    scope: String,
    tags: String?,
    onConfirm: () -> Unit,
    onCorrect: (correctedValue: String) -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit
) {
    var correctionText by remember { mutableStateOf(originalValue) }
    var isEditing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusInteractionSource = remember { MutableInteractionSource() }
    
    // При открытии диалога фокусируемся на поле ввода, если включено редактирование
    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
        }
    }
    
    // Функция для отображения уверенности в виде текста
    val confidenceText = when {
        confidence >= 0.9f -> stringResource(R.string.fact_confirm_high_confidence)
        confidence >= 0.7f -> stringResource(R.string.fact_confirm_medium_confidence)
        else -> stringResource(R.string.fact_confirm_low_confidence)
    }
    
    // Функция для отображения scope в виде эмодзи и текста
    val scopeDisplay = when (scope.lowercase()) {
        "user" -> stringResource(R.string.fact_confirm_user_scope)
        "ai" -> stringResource(R.string.fact_confirm_ai_scope)
        "global" -> stringResource(R.string.fact_confirm_global_scope)
        else -> scope
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.fact_confirm_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Категория и ключ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.fact_confirm_category_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = category,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.fact_confirm_key_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Scope и уверенность
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.fact_confirm_context_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = scopeDisplay,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.fact_confirm_confidence_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$confidenceText (${(confidence * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Поле для отображения/редактирования значения
                if (isEditing) {
                    OutlinedTextField(
                        value = correctionText,
                        onValueChange = { correctionText = it },
                        label = { Text(stringResource(R.string.fact_confirm_corrected_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .focusAnimation(focusInteractionSource),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            keyboardType = KeyboardType.Text
                        ),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.fact_confirm_corrected_placeholder)) }
                    )
                } else {
                    Column {
                        Text(
                            text = stringResource(R.string.fact_confirm_extracted_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = originalValue,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                
                // Теги (если есть)
                tags?.takeIf { it.isNotBlank() }?.let { tagsString ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.fact_confirm_tags_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = tagsString,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Кнопка подтверждения (используется только если не редактируется)
            if (!isEditing) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.pressAnimation()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Text(stringResource(R.string.fact_confirm_confirm_button), modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                Button(
                    onClick = { onCorrect(correctionText) },
                    modifier = Modifier.pressAnimation(),
                    enabled = correctionText.isNotBlank()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Text(stringResource(R.string.fact_confirm_save_correction), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Кнопка редактирования/отмены редактирования
                if (isEditing) {
                    TextButton(
                        onClick = { 
                            isEditing = false
                            correctionText = originalValue
                        },
                        modifier = Modifier.pressAnimation()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text(stringResource(R.string.cancel), modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    TextButton(
                        onClick = { isEditing = true },
                        modifier = Modifier.pressAnimation()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Text(stringResource(R.string.fact_confirm_correct_button), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                
                // Кнопка отклонения
                TextButton(
                    onClick = onReject,
                    modifier = Modifier.pressAnimation()
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Text(stringResource(R.string.fact_confirm_reject_button), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    )
}