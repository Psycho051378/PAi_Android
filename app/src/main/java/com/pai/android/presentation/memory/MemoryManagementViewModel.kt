package com.pai.android.presentation.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pai.android.data.repository.MemoryRepository
import com.pai.android.data.repository.ImportMergeStrategy
import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.model.DailyMemory
import com.pai.android.data.export.MemoryImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Состояние экрана управления памятью.
 */
data class MemoryManagementState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedScope: String = "user", // "user", "ai", "global", "all"
    val selectedTab: Int = 0, // 0 = факты, 1 = дневные записи
    val facts: List<PermanentMemory> = emptyList(),
    val dailyEntries: List<DailyMemory> = emptyList(),
    val selectedDate: String = SimpleDateFormat("yyyy-MM-dd").format(Date()),
    val searchQuery: String = "",
    val showDeleteDialog: Boolean = false,
    val factToDelete: PermanentMemory? = null,
    val showEditDialog: Boolean = false,
    val factToEdit: PermanentMemory? = null,
    val editKey: String = "",
    val editValue: String = "",
    val editConfidence: Float = 1.0f,
    // Массовые операции
    val isSelectionMode: Boolean = false,
    val selectedFactIds: Set<String> = emptySet(),
    val showBulkDeleteDialog: Boolean = false,
    // Экспорт/импорт
    val exportMarkdown: String? = null,
    val showExportDialog: Boolean = false,
    val importText: String = "",
    val showImportDialog: Boolean = false,
    val importResult: MemoryImporter.ImportResult? = null
)

/**
 * ViewModel для экрана управления памятью.
 */
@HiltViewModel
class MemoryManagementViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository
) : ViewModel() {
    
    private val _state = MutableStateFlow(MemoryManagementState())
    val state: StateFlow<MemoryManagementState> = _state.asStateFlow()
    
    private var searchJob: Job? = null
    
    init {
        loadData()
    }
    
    /**
     * Загружает факты и дневные записи в зависимости от текущего состояния.
     */
    fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                when (state.value.selectedTab) {
                    0 -> loadFacts()
                    1 -> loadDailyEntries()
                }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Ошибка загрузки: ${e.message}") }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
    
    /**
     * Загружает факты по выбранному scope.
     */
    private suspend fun loadFacts() {
        val scope = state.value.selectedScope
        val query = state.value.searchQuery
        val facts = if (query.isNotBlank()) {
            // Поиск с учётом scope
            if (scope == "all") {
                memoryRepository.searchPermanentFacts(query, limit = 50)
            } else {
                memoryRepository.searchFactsInScope(scope, query, limit = 50)
            }
        } else {
            // Обычная загрузка
            if (scope == "all") {
                // Загружаем все факты и сортируем по scope
                listOf(
                    memoryRepository.getFactsByScope("user"),
                    memoryRepository.getFactsByScope("ai"),
                    memoryRepository.getFactsByScope("global")
                ).flatten()
            } else {
                memoryRepository.getFactsByScope(scope)
            }
        }
        
        _state.update { it.copy(facts = facts) }
    }
    
    /**
     * Загружает дневные записи.
     */
    private suspend fun loadDailyEntries() {
        val query = state.value.searchQuery
        val dailyEntries = if (query.isNotBlank()) {
            memoryRepository.searchDailyEntries(query, limit = 50)
        } else {
            memoryRepository.getRecentDailyMemory(limit = 30)
        }
        _state.update { it.copy(dailyEntries = dailyEntries) }
    }
    
    /**
     * Обновляет выбранный scope и перезагружает факты.
     */
    fun selectScope(scope: String) {
        if (state.value.selectedScope != scope) {
            _state.update { it.copy(selectedScope = scope) }
            if (state.value.selectedTab == 0) {
                loadData()
            }
        }
    }
    
    /**
     * Переключает вкладку (факты / дневные записи).
     */
    fun selectTab(tabIndex: Int) {
        if (state.value.selectedTab != tabIndex) {
            _state.update { it.copy(selectedTab = tabIndex) }
            loadData()
        }
    }
    
    /**
     * Обновляет поисковый запрос.
     */
    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        
        // Отменяем предыдущий поиск
        searchJob?.cancel()
        
        // Запускаем новый поиск с задержкой (debounce 300 мс)
        searchJob = viewModelScope.launch {
            delay(300)
            loadData()
        }
    }
    
    /**
     * Устанавливает выбранную дату для просмотра дневных записей.
     */
    fun selectDate(date: String) {
        _state.update { it.copy(selectedDate = date) }
        // TODO: Загрузить дневную запись за указанную дату
    }
    
    /**
     * Удаляет факт из памяти.
     */
    fun deleteFact(fact: PermanentMemory) {
        viewModelScope.launch {
            try {
                memoryRepository.deletePermanentFact(fact.id)
                loadFacts()
                closeDeleteDialog()
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Ошибка удаления: ${e.message}") }
            }
        }
    }
    
    /**
     * Обновляет факт в памяти.
     */
    fun updateFact(
        id: String,
        key: String? = null,
        value: String? = null,
        confidence: Float? = null,
        scope: String? = null,
        tags: String? = null
    ) {
        viewModelScope.launch {
            try {
                memoryRepository.updatePermanentFact(
                    id = id,
                    key = key,
                    value = value,
                    confidence = confidence,
                    scope = scope,
                    tags = tags
                )
                loadFacts()
                closeEditDialog()
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Ошибка обновления: ${e.message}") }
            }
        }
    }
    
    /**
     * Подтверждает факт (устанавливает confidence = 1.0).
     */
    fun confirmFact(fact: PermanentMemory) {
        updateFact(id = fact.id, confidence = 1.0f)
    }
    
    /**
     * Показывает диалог удаления.
     */
    fun showDeleteDialog(fact: PermanentMemory) {
        _state.update { it.copy(showDeleteDialog = true, factToDelete = fact) }
    }
    
    /**
     * Закрывает диалог удаления.
     */
    fun closeDeleteDialog() {
        _state.update { it.copy(showDeleteDialog = false, factToDelete = null) }
    }
    
    /**
     * Показывает диалог редактирования.
     */
    fun showEditDialog(fact: PermanentMemory) {
        _state.update { 
            it.copy(
                showEditDialog = true,
                factToEdit = fact,
                editKey = fact.key,
                editValue = fact.value,
                editConfidence = fact.confidence
            )
        }
    }
    
    /**
     * Закрывает диалог редактирования.
     */
    fun closeEditDialog() {
        _state.update { it.copy(showEditDialog = false, factToEdit = null) }
    }
    
    /**
     * Обновляет поле редактирования ключа.
     */
    fun updateEditKey(key: String) {
        _state.update { it.copy(editKey = key) }
    }
    
    /**
     * Обновляет поле редактирования значения.
     */
    fun updateEditValue(value: String) {
        _state.update { it.copy(editValue = value) }
    }
    
    /**
     * Обновляет поле редактирования уверенности.
     */
    fun updateEditConfidence(confidence: Float) {
        _state.update { it.copy(editConfidence = confidence) }
    }
    
    /**
     * Сохраняет изменения в факте через диалог редактирования.
     */
    fun saveEditedFact() {
        val fact = state.value.factToEdit ?: return
        updateFact(
            id = fact.id,
            key = state.value.editKey.takeIf { it != fact.key },
            value = state.value.editValue.takeIf { it != fact.value },
            confidence = state.value.editConfidence.takeIf { it != fact.confidence }
        )
    }
    
    /**
     * Удаляет дневную запись.
     */
    fun deleteDailyEntry(date: String) {
        viewModelScope.launch {
            try {
                memoryRepository.deleteDailyMemory(date)
                loadDailyEntries()
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Ошибка удаления: ${e.message}") }
            }
        }
    }
    
    /**
     * Очищает сообщение об ошибке.
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
    
    // ============= МАССОВЫЕ ОПЕРАЦИИ =============
    
    /**
     * Включает/выключает режим выбора.
     */
    fun toggleSelectionMode() {
        _state.update { 
            if (it.isSelectionMode) {
                it.copy(isSelectionMode = false, selectedFactIds = emptySet())
            } else {
                it.copy(isSelectionMode = true)
            }
        }
    }
    
    /**
     * Входит в режим выбора и выбирает первый факт.
     */
    fun enterSelectionMode(factId: String? = null) {
        _state.update { 
            it.copy(
                isSelectionMode = true,
                selectedFactIds = if (factId != null) setOf(factId) else emptySet()
            )
        }
    }
    
    /**
     * Выходит из режима выбора.
     */
    fun exitSelectionMode() {
        _state.update { 
            it.copy(isSelectionMode = false, selectedFactIds = emptySet())
        }
    }
    
    /**
     * Выбирает факт.
     */
    fun selectFact(factId: String) {
        _state.update { 
            it.copy(selectedFactIds = it.selectedFactIds + factId)
        }
    }
    
    /**
     * Отменяет выбор факта.
     */
    fun deselectFact(factId: String) {
        _state.update { 
            it.copy(selectedFactIds = it.selectedFactIds - factId)
        }
    }
    
    /**
     * Переключает выбор факта.
     */
    fun toggleFactSelection(factId: String) {
        _state.update { 
            it.copy(
                selectedFactIds = if (factId in it.selectedFactIds) {
                    it.selectedFactIds - factId
                } else {
                    it.selectedFactIds + factId
                }
            )
        }
    }
    
    /**
     * Выбирает все факты на текущей странице.
     */
    fun selectAllFacts() {
        val allIds = state.value.facts.map { it.id }.toSet()
        _state.update { it.copy(selectedFactIds = allIds) }
    }
    
    /**
     * Очищает выбор.
     */
    fun clearSelection() {
        _state.update { it.copy(selectedFactIds = emptySet()) }
    }
    
    /**
     * Показывает диалог массового удаления.
     */
    fun showBulkDeleteDialog() {
        _state.update { it.copy(showBulkDeleteDialog = true) }
    }
    
    /**
     * Закрывает диалог массового удаления.
     */
    fun closeBulkDeleteDialog() {
        _state.update { it.copy(showBulkDeleteDialog = false) }
    }
    
    /**
     * Удаляет выбранные факты.
     */
    fun deleteSelectedFacts() {
        viewModelScope.launch {
            try {
                val idsToDelete = state.value.selectedFactIds
                idsToDelete.forEach { id ->
                    memoryRepository.deletePermanentFact(id)
                }
                exitSelectionMode()
                closeBulkDeleteDialog()
                loadFacts()
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Ошибка массового удаления: ${e.message}") }
            }
        }
    }
    
    // ============= ЭКСПОРТ/ИМПОРТ =============
    
    /**
     * Экспортирует память в Markdown.
     */
    fun exportMemory() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val markdown = memoryRepository.exportMemoryToMarkdown()
                _state.update { 
                    it.copy(
                        exportMarkdown = markdown,
                        showExportDialog = true,
                        isLoading = false
                    ) 
                }
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        errorMessage = "Ошибка экспорта: ${e.message}",
                        isLoading = false
                    ) 
                }
            }
        }
    }
    
    /**
     * Закрывает диалог экспорта.
     */
    fun closeExportDialog() {
        _state.update { it.copy(showExportDialog = false, exportMarkdown = null) }
    }
    
    /**
     * Показывает диалог импорта.
     */
    fun showImportDialog() {
        _state.update { it.copy(showImportDialog = true, importText = "", importResult = null) }
    }
    
    /**
     * Закрывает диалог импорта.
     */
    fun closeImportDialog() {
        _state.update { it.copy(showImportDialog = false, importText = "", importResult = null) }
    }
    
    /**
     * Обновляет текст для импорта.
     */
    fun updateImportText(text: String) {
        _state.update { it.copy(importText = text) }
    }
    
    /**
     * Импортирует память из Markdown текста.
     */
    fun importMemory() {
        val markdown = state.value.importText
        if (markdown.isBlank()) {
            _state.update { it.copy(errorMessage = "Введите Markdown текст для импорта") }
            return
        }
        
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val result = memoryRepository.importMemoryFromMarkdown(markdown)
                _state.update { 
                    it.copy(
                        importResult = result,
                        isLoading = false
                    ) 
                }
                
                // Если импорт успешен, обновляем данные
                if (result.isSuccess) {
                    loadData()
                }
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        errorMessage = "Ошибка импорта: ${e.message}",
                        isLoading = false
                    ) 
                }
            }
        }
    }
    
    /**
     * Очищает результат импорта.
     */
    fun clearImportResult() {
        _state.update { it.copy(importResult = null) }
    }
}