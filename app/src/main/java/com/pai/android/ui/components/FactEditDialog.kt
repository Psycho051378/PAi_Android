package com.pai.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.stringResource
import com.pai.android.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pai.android.data.model.PermanentMemory

/**
 * Диалог редактирования факта.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactEditDialog(
    fact: PermanentMemory?,
    key: String,
    value: String,
    confidence: Float,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onConfidenceChange: (Float) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    if (fact == null) return
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Заголовок
                Text(
                    text = stringResource(R.string.fact_edit_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = MaterialTheme.typography.titleLarge.fontWeight,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // Информация о факте
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.fact_edit_category, fact.category),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = stringResource(R.string.fact_edit_scope, fact.scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                
                // Поле ключа
                OutlinedTextField(
                    value = key,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(R.string.fact_edit_key_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                // Поле значения
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.fact_edit_value_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3
                )
                
                // Слайдер уверенности
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.fact_edit_confidence_label, (confidence * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = confidence,
                        onValueChange = onConfidenceChange,
                        valueRange = 0f..1f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.fact_edit_confidence_min), style = MaterialTheme.typography.labelSmall)
                        Text(stringResource(R.string.fact_edit_confidence_max), style = MaterialTheme.typography.labelSmall)
                    }
                }
                
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
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}