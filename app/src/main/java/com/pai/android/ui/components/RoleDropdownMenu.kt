package com.pai.android.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.pai.android.data.model.Role

/**
 * Выпадающее меню для выбора роли AI.
 * Упрощённая версия для демонстрации.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleDropdownMenu(
    roles: List<Role> = emptyList(),
    selectedRole: Role? = null,
    onRoleSelected: (Role) -> Unit = {},
    onManageRoles: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .menuAnchor()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Assistant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = selectedRole?.name ?: "Выберите роль",
                style = MaterialTheme.typography.labelMedium,
                color = if (selectedRole != null) MaterialTheme.colorScheme.onSurface 
                       else MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = if (expanded) "Скрыть меню" else "Показать меню",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize()
        ) {
            if (roles.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Нет доступных ролей") },
                    onClick = { expanded = false }
                )
            } else {
                roles.forEach { role ->
                    DropdownMenuItem(
                        text = { 
                            Text(
                                text = role.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            onRoleSelected(role)
                            expanded = false
                        },
                        trailingIcon = {
                            if (role.isDefault) {
                                Icon(
                                    imageVector = Icons.Filled.Assistant,
                                    contentDescription = "Роль по умолчанию",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    )
                }
            }
            
            // Разделитель и ссылка на управление ролями
            DropdownMenuItem(
                text = { 
                    Text(
                        text = "Управление ролями...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = {
                    expanded = false
                    onManageRoles()
                }
            )
        }
    }
}