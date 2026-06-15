package com.pai.android.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.model.Role
import com.pai.android.data.repository.RoleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние экрана управления ролями.
 */
data class RoleListState(
    val roles: List<Role> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedRole: Role? = null,
    val isEditing: Boolean = false
)

/**
 * ViewModel для управления ролями AI.
 */
@HiltViewModel
class RoleViewModel @Inject constructor(
    private val roleRepository: RoleRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RoleListState())
    val state: StateFlow<RoleListState> = _state.asStateFlow()

    init {
        loadRoles()
        initializeDefaultRoles()
    }

    /**
     * Загружает все роли из репозитория.
     */
    fun loadRoles() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // Используем Flow для наблюдения за изменениями в реальном времени
                roleRepository.observeAllRoles().collect { roles ->
                    _state.update { it.copy(roles = roles, isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(
                    isLoading = false,
                    errorMessage = "Не удалось загрузить роли: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Инициализирует стандартные роли, если таблица пуста.
     */
    private fun initializeDefaultRoles() {
        viewModelScope.launch {
            try {
                roleRepository.initializeDefaultRoles()
            } catch (e: Exception) {
                // Игнорируем ошибки инициализации (возможно, роли уже существуют)
            }
        }
    }

    /**
     * Создаёт новую роль.
     */
    fun createRole(
        name: String,
        description: String? = null,
        systemPrompt: String,
        temperature: Float? = null,
        maxTokens: Int? = null,
        isDefault: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                roleRepository.createRole(
                    name = name,
                    description = description,
                    systemPrompt = systemPrompt,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    isDefault = isDefault
                )
                // Flow автоматически обновится через observeAllRoles()
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось создать роль: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Обновляет существующую роль.
     */
    fun updateRole(role: Role) {
        viewModelScope.launch {
            try {
                roleRepository.updateRole(role)
                // Flow автоматически обновится через observeAllRoles()
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось обновить роль: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Удаляет роль.
     */
    fun deleteRole(roleId: String) {
        viewModelScope.launch {
            try {
                roleRepository.deleteRole(roleId)
                // Flow автоматически обновится через observeAllRoles()
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось удалить роль: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Устанавливает роль по умолчанию.
     */
    fun setDefaultRole(roleId: String) {
        viewModelScope.launch {
            try {
                roleRepository.setDefaultRole(roleId)
                // Flow автоматически обновится через observeAllRoles()
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось установить роль по умолчанию: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Выбирает роль для редактирования.
     */
    fun selectRoleForEditing(role: Role?) {
        _state.update { it.copy(selectedRole = role, isEditing = role != null) }
    }

    /**
     * Отменяет редактирование.
     */
    fun cancelEditing() {
        _state.update { it.copy(selectedRole = null, isEditing = false) }
    }

    /**
     * Очищает ошибку.
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Ищет роли по названию.
     */
    fun searchRoles(query: String) {
        viewModelScope.launch {
            try {
                val results = roleRepository.searchRoles(query)
                _state.update { it.copy(roles = results) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Ошибка поиска: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Сбрасывает поиск и загружает все роли.
     */
    fun resetSearch() {
        loadRoles()
    }
}