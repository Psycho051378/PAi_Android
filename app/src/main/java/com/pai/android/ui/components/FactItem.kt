package com.pai.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pai.android.R
import com.pai.android.data.model.PermanentMemory

/**
 * Компонент для отображения факта в списке.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FactItem(
    fact: PermanentMemory,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onConfirmClick: () -> Unit,
    // Массовые операции
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(
                when {
                    isSelectionMode && onSelectToggle != null -> 
                        Modifier.clickable { onSelectToggle() }
                    onLongClick != null -> 
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = onLongClick
                        )
                    else -> Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = when (fact.scope) {
                "user" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                "ai" -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                "global" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Первая строка: ключ, категория и scope (с чекбоксом в режиме выбора)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // В режиме выбора показываем чекбокс слева
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectToggle?.invoke() },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                
                Text(
                    text = fact.key,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Иконка scope
                    Text(
                        text = when (fact.scope) {
                            "user" -> "👤"
                            "ai" -> "🤖"
                            "global" -> "🌍"
                            else -> "❓"
                        },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    
                    // Категория
                    Text(
                        text = fact.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Значение факта
            Text(
                text = fact.value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Метаданные и действия
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Метаданные: уверенность и теги
                Column {
                    // Уверенность
                    ConfidenceIndicator(confidence = fact.confidence)
                    
                    // Теги
                    if (!fact.tags.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "🏷️ ${fact.tags}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                
                // В режиме выбора скрываем кнопки действий, показываем только метаданные
                if (!isSelectionMode) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Кнопка подтверждения (только если уверенность < 1.0)
                        if (fact.confidence < 1.0f) {
                            IconButton(
                                onClick = onConfirmClick,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.fact_confirm_confirm_button),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        // Кнопка редактирования
                        IconButton(
                            onClick = onEditClick,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        
                        // Кнопка удаления
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Индикатор уверенности в факте.
 */
@Composable
private fun ConfidenceIndicator(confidence: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Цвет в зависимости от уверенности
        val color = when {
            confidence >= 0.9f -> Color.Green
            confidence >= 0.7f -> Color(0xFFFB8C00) // Оранжевый
            else -> Color.Red
        }
        
        // Текст
        Text(
            text = stringResource(R.string.fact_edit_confidence_label, (confidence * 100).toInt()),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}