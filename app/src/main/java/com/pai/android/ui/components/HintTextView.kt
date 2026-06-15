package com.pai.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Компонент с основным текстом и иконкой подсказки [ⓘ].
 * При нажатии на иконку открывается диалог с пояснением.
 *
 * @param text — основной текст (лейбл)
 * @param hintResId — resource ID строки подсказки
 * @param modifier — модификатор для внешней настройки
 */
@Composable
fun HintText(
    text: String,
    hintResId: Int,
    modifier: Modifier = Modifier
) {
    var showHintDialog by remember { mutableStateOf(false) }
    val hintText = stringResource(hintResId)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = { showHintDialog = true },
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(com.pai.android.R.string.hint_icon_label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }

    if (showHintDialog) {
        HintDialog(
            hintText = hintText,
            onDismiss = { showHintDialog = false }
        )
    }
}

/**
 * Диалог с текстом подсказки.
 */
@Composable
fun HintDialog(
    hintText: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(com.pai.android.R.string.hint_dialog_title),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Text(text = hintText)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.pai.android.R.string.ok))
            }
        }
    )
}
