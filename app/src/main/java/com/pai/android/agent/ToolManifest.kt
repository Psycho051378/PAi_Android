package com.pai.android.agent

/**
 * Формальное описание всех доступных инструментов для AI промпта.
 * Предотвращает галлюцинации — AI читает exact команды перед использованием.
 */
class ToolManifest(
    private val toolRegistry: ToolRegistry,
    private val skillRegistry: SkillRegistry
) {
    /**
     * Возвращает структурированный манифест инструментов для вставки в промпт.
     * AI должен использовать ТОЛЬКО команды из этого списка.
     */
    fun generateManifest(): String {
        return """
===== ДОСТУПНЫЕ ИНСТРУМЕНТЫ =====
Используй ТОЛЬКО команды из этого списка. НЕ придумывай свои названия команд.

${buildFileSystemCommands()}

${buildMemoryCommands()}

${buildDocumentAnalysisCommands()}

${buildAppLaunchCommands()}
===============================
        """.trimIndent()
    }

    private fun buildFileSystemCommands(): String = """
📁 ФАЙЛОВАЯ СИСТЕМА (file_system):
  Команды (ТОЛЬКО эти):
  - create_folder  → создать папку. Параметры: path (путь к папке)
  - write_file     → создать/перезаписать файл. Параметры: path (путь), content (содержимое)
  - append_file    → добавить в конец файла. Параметры: path, content
  - read_file      → прочитать файл. Параметры: path (путь к файлу)
  - list_files     → список файлов в папке. Параметры: path, recursive (true/false)
  - delete         → удалить файл. Параметры: path (точный путь), recursive (true/false)
  - move           → переместить файл. Параметры: source (откуда), target (куда)
  - rename         → переименовать файл. Параметры: path (путь), name (новое имя)
  - copy           → скопировать файл. Параметры: source (откуда), target (куда)
  - get_file_info  → информация о файле. Параметры: path
  - edit_file      → редактировать файл. Параметры: path, instruction/ content
  
  ВАЖНО:
  - write_file АВТОМАТИЧЕСКИ создаёт родительские папки
  - delete с recursive=true удаляет все файлы с совпадающим именем
  - delete НЕ поддерживает wildcard'ы (*) — передавай точное имя
  - move = read + write + delete исходника (не вызывай их отдельно)
""".trimIndent()

    private fun buildMemoryCommands(): String = """
🧠 ПАМЯТЬ (memory):
  Команды (ТОЛЬКО эти):
  - save_fact      → сохранить факт. Параметры: key (ключ), value (значение), category (категория)
  - search_facts   → поиск фактов. Параметры: query (запрос)
  - get_fact       → получить факт. Параметры: key (ключ)
  
  Пример:
  save_fact(key="любимый_цвет", value="синий", category="personal")
""".trimIndent()

    private fun buildDocumentAnalysisCommands(): String = """
📄 АНАЛИЗ ДОКУМЕНТОВ (document_analysis):
  Команды (ТОЛЬКО эти):
  - analyze_file   → анализ одного файла. Параметры: path
  - analyze_folder → анализ папки. Параметры: path
  
  ВАЖНО: для простого чтения содержимого используй file_system/read_file, не document_analysis
""".trimIndent()

    private fun buildAppLaunchCommands(): String = """
📱 ЗАПУСК ПРИЛОЖЕНИЙ (app_launch):
  Используется для запуска приложений на устройстве.
  Не требует дополнительных команд — просто передай запрос.
""".trimIndent()
}
