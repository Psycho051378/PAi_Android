package com.pai.android.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.model.Chat
import com.pai.android.data.repository.ChatAccordionPreferencesRepository
import com.pai.android.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние экрана списка чатов.
 */
data class ChatListState(
    val chats: List<Chat> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val expandedGroups: Map<String, Boolean> = emptyMap(),
    val chatToDelete: Chat? = null // Чат, ожидающий подтверждения удаления
)

/**
 * ViewModel для экрана списка чатов.
 */
@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val accordionPreferencesRepository: ChatAccordionPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ChatListState())
    val state: StateFlow<ChatListState> = _state.asStateFlow()

    init {
        loadChats()
        loadAccordionStates()
    }

    /**
     * Загружает состояния аккордеона из репозитория.
     */
    private fun loadAccordionStates() {
        viewModelScope.launch {
            accordionPreferencesRepository.expandedGroups.collect { expandedGroups ->
                _state.update { it.copy(expandedGroups = expandedGroups) }
            }
        }
    }

    /**
     * Загружает все чаты из репозитория.
     */
    fun loadChats() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // Используем Flow из репозитория для наблюдения за изменениями в реальном времени
                chatRepository.observeAllChats().collect { chats ->
                    _state.update { it.copy(chats = chats, isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(
                    isLoading = false,
                    errorMessage = "Не удалось загрузить чаты: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Создаёт новый чат с заданным заголовком.
     */
    fun createNewChat(title: String = "Новый чат") {
        viewModelScope.launch {
            try {
                chatRepository.createChat(title)
                // Flow автоматически обновится через observeAllChats()
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось создать чат: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Показывает диалог подтверждения удаления чата.
     */
    fun showDeleteConfirmation(chatId: String) {
        val chat = state.value.chats.find { it.id == chatId }
        _state.update { it.copy(chatToDelete = chat) }
    }

    /**
     * Подтверждает удаление чата.
     */
    fun confirmDeleteChat() {
        viewModelScope.launch {
            val chatId = state.value.chatToDelete?.id ?: return@launch
            try {
                chatRepository.deleteChat(chatId)
                _state.update { it.copy(chatToDelete = null) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    chatToDelete = null,
                    errorMessage = "Не удалось удалить чат: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Отменяет удаление чата.
     */
    fun dismissDeleteConfirmation() {
        _state.update { it.copy(chatToDelete = null) }
    }

    /**
     * Архивирует или разархивирует чат.
     */
    fun toggleArchiveChat(chatId: String, isArchived: Boolean) {
        viewModelScope.launch {
            try {
                chatRepository.archiveChat(chatId, isArchived)
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось изменить статус архивации: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Обновляет заголовок чата.
     */
    fun updateChatTitle(chatId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                val chat = chatRepository.getChat(chatId)
                chat?.let { updatedChat ->
                    chatRepository.updateChat(updatedChat.updateTitle(newTitle))
                }
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось обновить заголовок: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Переключает состояние раскрытия группы аккордеона.
     */
    fun toggleGroupExpanded(groupId: String) {
        viewModelScope.launch {
            try {
                val currentExpanded = state.value.expandedGroups[groupId] ?: true
                accordionPreferencesRepository.setGroupExpanded(groupId, !currentExpanded)
                // Состояние автоматически обновится через Flow expandedGroups
            } catch (e: Exception) {
                _state.update { it.copy(
                    errorMessage = "Не удалось изменить состояние аккордеона: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Очищает ошибку.
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}