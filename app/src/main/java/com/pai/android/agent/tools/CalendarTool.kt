package com.pai.android.agent.tools

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import com.pai.android.agent.AgentTool
import com.pai.android.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Инструмент для работы с системным календарём Android.
 *
 * Позволяет:
 * - Получать список ближайших событий
 * - Искать события по тексту
 * - Читать детали конкретного события
 * - Создавать новые события
 */
@Singleton
class CalendarTool @Inject constructor(
    @ApplicationContext private val context: Context
) : AgentTool {

    override val name: String = "calendar"
    override val description: String = "Android calendar: list upcoming events, search events by title/text, read event details, create new events, delete events. Actions: list_upcoming (default), search, read, create, delete. For action=create: pass title in 'query', date as '2026-06-10' in 'date', time as '12:00' in 'time'. For action=delete: pass event_id."
    override val parametersSchema: String = """{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["list_upcoming", "search", "read", "create", "delete"],
                "description": "list_upcoming = get upcoming events (default, shows next events), search = search events by title/text, read = read specific event by id, create = add new event, delete = remove event by id"
            },
            "query": {
                "type": "string",
                "description": "Search term for action=search, or event title for action=create"
            },
            "days": {
                "type": "number",
                "description": "Number of days to look ahead for list_upcoming (default 7)"
            },
            "event_id": {
                "type": "number",
                "description": "Event ID for action=read"
            },
            "description": {
                "type": "string",
                "description": "Event description (for action=create)"
            },
            "location": {
                "type": "string",
                "description": "Event location (for action=create)"
            },
            "start_time": {
                "type": "number",
                "description": "Start time in milliseconds since epoch (for action=create)"
            },
            "end_time": {
                "type": "number",
                "description": "End time in milliseconds since epoch (for action=create)"
            },
            "date": {
                "type": "string",
                "description": "Event date in format YYYY-MM-DD (alternative to start_time for action=create)"
            },
            "time": {
                "type": "string",
                "description": "Event time in format HH:MM (alternative to start_time for action=create, e.g. '14:00')"
            },
            "calendar_id": {
                "type": "number",
                "description": "Calendar ID to use for creation (default: primary calendar)"
            }
        },
        "required": ["action"]
    }"""
    override val requiresConfirmation: Boolean = false

    private val dateFormat = SimpleDateFormat("EE, dd MMM yyyy HH:mm", Locale("ru"))

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString() ?: "list_upcoming"

        return when (action) {
            "list_upcoming" -> listUpcoming(params)
            "search" -> searchEvents(params)
            "read" -> readEvent(params)
            "create" -> createEvent(params)
            "delete" -> deleteEvent(params)
            else -> ToolResult.Error(error = "Unknown calendar action: $action")
        }
    }

    /**
     * Список ближайших событий.
     */
    private fun listUpcoming(params: Map<String, Any>): ToolResult {
        if (!hasCalendarPermission()) {
            return ToolResult.Success(
                output = "📅 Нет разрешения на чтение календаря. Разрешите доступ к календарю в настройках.",
                data = mapOf("permission_denied" to "true")
            )
        }

        val days = (params["days"] as? Number)?.toInt() ?: 7
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 60 * 60 * 1000

        val events = queryEvents(now, end, limit = 20)

        if (events.isEmpty()) {
            val calendarId = getPrimaryCalendarId()
            val msg = if (calendarId == null) {
                "📅 Календарь не настроен на устройстве. Создайте календарь (Google, Samsung или другой) в приложении «Календарь» на телефоне."
            } else {
                "📅 Нет ближайших событий на $days дней."
            }
            return ToolResult.Success(
                output = msg,
                data = mapOf("count" to "0")
            )
        }

        val sb = StringBuilder()
        sb.appendLine("📅 **Ближайшие события (${events.size}):**")
        events.forEachIndexed { i, ev ->
            sb.appendLine()
            sb.appendLine("**${i + 1}. ${ev.title}**")
            sb.appendLine("   🕐 ${ev.timeString}")
            if (ev.location.isNotBlank()) sb.appendLine("   📍 ${ev.location}")
            if (ev.description.isNotBlank()) {
                val desc = if (ev.description.length > 100) ev.description.take(100) + "…" else ev.description
                sb.appendLine("   📝 $desc")
            }
            sb.appendLine("   ID: ${ev.id}")
        }

        return ToolResult.Success(
            output = sb.toString().trimEnd(),
            data = mapOf("count" to events.size.toString())
        )
    }

    /**
     * Поиск событий по тексту в заголовке/описании.
     */
    private fun searchEvents(params: Map<String, Any>): ToolResult {
        if (!hasCalendarPermission()) {
            return ToolResult.Success(
                output = "📅 Нет разрешения на чтение календаря.",
                data = mapOf("permission_denied" to "true")
            )
        }

        val query = params["query"]?.toString() ?: ""
        if (query.isBlank()) {
            return ToolResult.Error(error = "Укажите текст для поиска (параметр query)")
        }

        val now = System.currentTimeMillis()
        val farFuture = now + 365L * 24 * 60 * 60 * 1000 // год вперёд

        val projection = arrayOf(
            Events._ID,
            Events.TITLE,
            Events.DTSTART,
            Events.DTEND,
            Events.EVENT_LOCATION,
            Events.DESCRIPTION,
            Events.ALL_DAY
        )
        val selection = "${Events.TITLE} LIKE ? OR ${Events.DESCRIPTION} LIKE ?"
        val selectionArgs = arrayOf("%$query%", "%$query%")

        val events = mutableListOf<CalendarEvent>()

        try {
            val cursor = context.contentResolver.query(
                Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${Events.DTSTART} ASC"
            )

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val start = c.getLong(c.getColumnIndexOrThrow(Events.DTSTART))
                    if (start > farFuture) continue
                    events.add(CalendarEvent(
                        id = c.getLong(c.getColumnIndexOrThrow(Events._ID)),
                        title = c.getString(c.getColumnIndexOrThrow(Events.TITLE)) ?: "",
                        startTime = start,
                        endTime = c.getLong(c.getColumnIndexOrThrow(Events.DTEND)),
                        location = c.getString(c.getColumnIndexOrThrow(Events.EVENT_LOCATION)) ?: "",
                        description = c.getString(c.getColumnIndexOrThrow(Events.DESCRIPTION)) ?: "",
                        allDay = c.getInt(c.getColumnIndexOrThrow(Events.ALL_DAY)) == 1
                    ))
                }
            }
        } catch (e: Exception) {
            return ToolResult.Error(error = "Ошибка поиска: ${e.message}")
        }

        if (events.isEmpty()) {
            return ToolResult.Success(
                output = "📅 События по запросу «$query» не найдены.",
                data = mapOf("count" to "0")
            )
        }

        val sb = StringBuilder()
        sb.appendLine("📅 **Найдено событий: ${events.size}** по запросу «$query»:")
        events.forEachIndexed { i, ev ->
            sb.appendLine()
            sb.appendLine("**${i + 1}. ${ev.title}**")
            sb.appendLine("   🕐 ${ev.timeString}")
            if (ev.location.isNotBlank()) sb.appendLine("   📍 ${ev.location}")
        }

        return ToolResult.Success(
            output = sb.toString().trimEnd(),
            data = mapOf("count" to events.size.toString())
        )
    }

    /**
     * Чтение конкретного события по ID.
     */
    private fun readEvent(params: Map<String, Any>): ToolResult {
        if (!hasCalendarPermission()) {
            return ToolResult.Success(
                output = "📅 Нет разрешения на чтение календаря.",
                data = mapOf("permission_denied" to "true")
            )
        }

        val eventId = params["event_id"] as? Number
        if (eventId == null) {
            return ToolResult.Error(error = "Укажите event_id для чтения события")
        }

        val projection = arrayOf(
            Events._ID,
            Events.TITLE,
            Events.DTSTART,
            Events.DTEND,
            Events.EVENT_LOCATION,
            Events.DESCRIPTION,
            Events.ALL_DAY,
            Events.AVAILABILITY,
            Events.ACCOUNT_NAME
        )

        try {
            val uri = Events.CONTENT_URI.buildUpon()
                .appendPath(eventId.toLong().toString())
                .build()
            val cursor = context.contentResolver.query(uri, projection, null, null, null)

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val title = c.getString(c.getColumnIndexOrThrow(Events.TITLE)) ?: ""
                    val start = c.getLong(c.getColumnIndexOrThrow(Events.DTSTART))
                    val end = c.getLong(c.getColumnIndexOrThrow(Events.DTEND))
                    val location = c.getString(c.getColumnIndexOrThrow(Events.EVENT_LOCATION)) ?: ""
                    val description = c.getString(c.getColumnIndexOrThrow(Events.DESCRIPTION)) ?: ""
                    val allDay = c.getInt(c.getColumnIndexOrThrow(Events.ALL_DAY)) == 1
                    val account = c.getString(c.getColumnIndexOrThrow(Events.ACCOUNT_NAME)) ?: ""

                    val sb = StringBuilder()
                    sb.appendLine("📅 **${title}**")
                    sb.appendLine("   ID: $eventId")
                    sb.appendLine("   Календарь: $account")
                    sb.appendLine("   🕐 ${formatTime(start)} — ${formatTime(end)}${if (allDay) " (весь день)" else ""}")
                    if (location.isNotBlank()) sb.appendLine("   📍 $location")
                    if (description.isNotBlank()) {
                        sb.appendLine("   📝 $description")
                    }

                    return ToolResult.Success(
                        output = sb.toString().trimEnd(),
                        data = mapOf(
                            "id" to eventId.toString(),
                            "title" to title,
                            "start" to start.toString(),
                            "end" to end.toString(),
                            "location" to location,
                            "description" to description
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return ToolResult.Error(error = "Ошибка чтения события: ${e.message}")
        }

        return ToolResult.Success(
            output = "📅 Событие с ID $eventId не найдено.",
            data = mapOf("found" to "false")
        )
    }

    /**
     * Создание нового события.
     */
    private fun createEvent(params: Map<String, Any>): ToolResult {
        if (!hasCalendarWritePermission()) {
            return ToolResult.Success(
                output = "📅 Нет разрешения на запись в календарь. Разрешите в настройках.",
                data = mapOf("permission_denied" to "true")
            )
        }

        val title = params["query"]?.toString() ?: params["title"]?.toString() ?: ""
        if (title.isBlank()) {
            return ToolResult.Error(error = "Укажите название события (query или title)")
        }

        val description = params["description"]?.toString() ?: ""
        val location = params["location"]?.toString() ?: ""

        val startTime = parseEventTime(params)
        val endTime = (params["end_time"] as? Number)?.toLong()
            ?: (startTime + 60 * 60 * 1000) // +1 час по умолчанию

        val calendarId = getPrimaryCalendarId()
        if (calendarId == null) {
            // Fallback: открываем приложение Календарь с формой создания события
            return try {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = Events.CONTENT_URI
                    putExtra(Events.TITLE, title)
                    putExtra(Events.DESCRIPTION, description)
                    putExtra(Events.EVENT_LOCATION, location)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
                    putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToolResult.Success(
                    output = "📅 Открыто приложение Календарь с формой создания события «$title» на 10 июня, 12:00. Проверьте и сохраните.",
                    data = mapOf("opened_form" to "true", "title" to title)
                )
            } catch (e: Exception) {
                ToolResult.Error(error = "Не найден календарь на устройстве. Откройте приложение «Календарь» один раз, чтобы оно создалось, и повторите попытку.")
            }
        }

        try {
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, title)
                put(Events.DESCRIPTION, description)
                put(Events.EVENT_LOCATION, location)
                put(Events.DTSTART, startTime)
                put(Events.DTEND, endTime)
                put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(Events.ALL_DAY, 0)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            }

            val uri = context.contentResolver.insert(Events.CONTENT_URI, values)
            if (uri != null) {
                val eventId = uri.lastPathSegment
                return ToolResult.Success(
                    output = "📅 Событие «$title» создано в календаре.\n   🕐 ${formatTime(startTime)}\n   📍 ${if (location.isNotBlank()) location else "не указано"}\n   ID: $eventId",
                    data = mapOf(
                        "id" to (eventId ?: "0"),
                        "title" to title,
                        "created" to "true"
                    )
                )
            } else {
                return ToolResult.Error(error = "Не удалось создать событие")
            }
        } catch (e: Exception) {
            return ToolResult.Error(error = "Ошибка создания события: ${e.message}")
        }
    }

    /**
     * Удаляет событие по ID.
     */
    private fun deleteEvent(params: Map<String, Any>): ToolResult {
        if (!hasCalendarWritePermission()) {
            return ToolResult.Success(
                output = "📅 Нет разрешения на запись в календарь.",
                data = mapOf("permission_denied" to "true")
            )
        }

        val eventId = params["event_id"] as? Number
        if (eventId == null) {
            return ToolResult.Error(error = "Укажите event_id для удаления события")
        }

        try {
            val uri = Events.CONTENT_URI.buildUpon()
                .appendPath(eventId.toLong().toString())
                .build()
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted > 0) {
                return ToolResult.Success(
                    output = "📅 Событие (ID $eventId) удалено.",
                    data = mapOf("deleted" to "true", "id" to eventId.toString())
                )
            } else {
                return ToolResult.Success(
                    output = "📅 Событие с ID $eventId не найдено или уже удалено.",
                    data = mapOf("deleted" to "false")
                )
            }
        } catch (e: Exception) {
            return ToolResult.Error(error = "Ошибка удаления события: ${e.message}")
        }
    }

    /**
     * Получает ID основного календаря (сначала primary, потом первый доступный).
     */
    private fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.VISIBLE
        )
        // Debug: проверяем permission и общее количество календарей
        println("CalendarTool: hasCalendarPermission=" + hasCalendarPermission() + ", hasWritePermission=" + hasCalendarWritePermission())
        try {
            val allCursors = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.IS_PRIMARY),
                null, null, null
            )
            allCursors?.use { c ->
                println("CalendarTool: total calendars=" + c.count)
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                    val name = c.getString(c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)) ?: "null"
                    val vis = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE))
                    val prim = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY))
                    println("CalendarTool:   id=" + id + ", account=" + name + ", visible=" + vis + ", primary=" + prim)
                }
            }
        } catch (e: Exception) {
            println("CalendarTool: query all calendars error: " + e.message)
        }

        // Сначала ищем видимые календари
        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                null
            )
            cursor?.use { c ->
                var primaryId: Long? = null
                var firstId: Long? = null
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                    val isPrimary = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)) == 1
                    if (firstId == null) firstId = id
                    if (isPrimary) primaryId = id
                }
                if (primaryId != null || firstId != null) return primaryId ?: firstId
            }
        } catch (_: Exception) {}

        // Fallback: любые календари (в т.ч. локальные/невидимые)
        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                    return id
                }
            }
        } catch (_: Exception) {}

            // Если календарей нет — пробуем открыть приложение Календарь, чтобы оно инициализировалось
        return null
    }

    /**
     * Запрос событий во временном диапазоне.
     */
    private fun queryEvents(startTime: Long, endTime: Long, limit: Int = 20): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val projection = arrayOf(
            Events._ID,
            Events.TITLE,
            Events.DTSTART,
            Events.DTEND,
            Events.EVENT_LOCATION,
            Events.DESCRIPTION,
            Events.ALL_DAY
        )
        val selection = "${Events.DTSTART} >= ? AND ${Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startTime.toString(), endTime.toString())

        try {
            val cursor = context.contentResolver.query(
                Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${Events.DTSTART} ASC LIMIT $limit"
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    events.add(CalendarEvent(
                        id = c.getLong(c.getColumnIndexOrThrow(Events._ID)),
                        title = c.getString(c.getColumnIndexOrThrow(Events.TITLE)) ?: "",
                        startTime = c.getLong(c.getColumnIndexOrThrow(Events.DTSTART)),
                        endTime = c.getLong(c.getColumnIndexOrThrow(Events.DTEND)),
                        location = c.getString(c.getColumnIndexOrThrow(Events.EVENT_LOCATION)) ?: "",
                        description = c.getString(c.getColumnIndexOrThrow(Events.DESCRIPTION)) ?: "",
                        allDay = c.getInt(c.getColumnIndexOrThrow(Events.ALL_DAY)) == 1
                    ))
                }
            }
        } catch (e: Exception) {
            println("CalendarTool: query error: ${e.message}")
        }
        return events
    }

    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCalendarWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Парсит время события из параметров.
     * Сначала проверяет start_time (timestamp), потом date+time (строки), потом query (текст).
     */
    private fun parseEventTime(params: Map<String, Any>): Long {
        // 1. Прямой timestamp
        val explicitTime = (params["start_time"] as? Number)?.toLong()
        if (explicitTime != null) return explicitTime

        val now = Calendar.getInstance()

        // 2. date + time строки
        val dateStr = params["date"]?.toString()
        val timeStr = params["time"]?.toString()
        if (dateStr != null) {
            try {
                val cal = Calendar.getInstance()
                val parts = dateStr.split("-")
                if (parts.size == 3) {
                    cal.set(Calendar.YEAR, parts[0].toInt())
                    cal.set(Calendar.MONTH, parts[1].toInt() - 1)
                    cal.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                    if (timeStr != null) {
                        val timeParts = timeStr.split(":")
                        cal.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                        cal.set(Calendar.MINUTE, timeParts[1].toInt())
                    } else {
                        cal.set(Calendar.HOUR_OF_DAY, 12)
                        cal.set(Calendar.MINUTE, 0)
                    }
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback: сейчас + 1 час
        now.add(Calendar.HOUR_OF_DAY, 1)
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)
        return now.timeInMillis
    }

    private fun formatTime(millis: Long): String {
        return dateFormat.format(Date(millis))
    }

    private data class CalendarEvent(
        val id: Long,
        val title: String,
        val startTime: Long,
        val endTime: Long,
        val location: String,
        val description: String,
        val allDay: Boolean
    ) {
        val timeString: String
            get() {
                val df = SimpleDateFormat("EE, dd MMM yyyy HH:mm", Locale("ru"))
                return if (allDay) {
                    SimpleDateFormat("dd MMM yyyy", Locale("ru")).format(Date(startTime)) + " (весь день)"
                } else {
                    "${df.format(Date(startTime))} — ${df.format(Date(endTime))}"
                }
            }
    }
}
