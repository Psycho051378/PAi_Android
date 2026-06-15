package com.pai.android.ui.diagrams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Диалог выбора формата сохранения диаграммы.
 */
@Composable
fun SaveFormatDialog(
    onDismissRequest: () -> Unit,
    onFormatSelected: (SaveFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Выберите формат сохранения",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SaveFormat.values().forEach { format ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        RadioButton(
                            selected = format == SaveFormat.SVG, // По умолчанию SVG выбран
                            onClick = { onFormatSelected(format) }
                        )
                        Icon(
                            imageVector = format.icon,
                            contentDescription = format.name,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = format.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = format.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onFormatSelected(SaveFormat.SVG) // По умолчанию SVG
                    onDismissRequest()
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text("Отмена")
            }
        },
        modifier = modifier.width(400.dp)
    )
}

/**
 * Форматы сохранения диаграмм.
 */
enum class SaveFormat(
    val displayName: String,
    val fileExtension: String,
    val mimeType: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    SVG(
        displayName = "SVG (векторный)",
        fileExtension = "svg",
        mimeType = "image/svg+xml",
        description = "Масштабируемая векторная графика",
        icon = Icons.Default.Style
    ),
    PNG(
        displayName = "PNG (растровый)",
        fileExtension = "png",
        mimeType = "image/png",
        description = "Растровое изображение высокого качества",
        icon = Icons.Default.Image
    );
    
    companion object {
        fun fromExtension(extension: String): SaveFormat? {
            return values().find { it.fileExtension.equals(extension, ignoreCase = true) }
        }
    }
}