package com.pai.android.agent.skills

import android.content.Context
import com.pai.android.agent.Intent
import com.pai.android.agent.Skill
import com.pai.android.agent.SkillResult
import com.pai.android.data.model.GeoTask
import com.pai.android.data.repository.GeoTaskRepository
import com.pai.android.data.service.LocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Навык управления гео-задачами (напоминания по геолокации).
 *
 * Команды:
 * - add:label=купить молоко:lat=59.93:lon=30.31:address=Пятёрочка:radius=300
 * - list — показать все активные
 * - remove:id=xxx
 * - test — создать тестовую задачу вокруг текущей позиции
 */
@Singleton
class GeoSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geoTaskRepository: GeoTaskRepository,
    private val locationService: LocationService
) : Skill {

    override val name: String = "geo"
    override val description: String = "Location-based reminders. Creates/edits/deletes tasks that notify when you're near a place. Commands: add (create with label+address+radius), edit (update by id with new label/address/radius), remove (deactivate by id), list (show all), test (quick test at current location). Radius defaults to 100m. Auto-checks proximity."

    /**
     * Распознаёт запросы, связанные с гео-задачами.
     */
    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        val lower = query.lowercase()
        return lower.contains("гео-задач") || lower.contains("гео задач") ||
               lower.contains("geo task") || lower.contains("напомни") ||
               (lower.contains("когда буду") && (lower.contains("рядом") || lower.contains("у"))) ||
               lower.contains("geo:") ||
               (params["command"]?.toString()?.startsWith("geo") == true)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val command = params["command"]?.toString() ?: params["action"]?.toString() ?: ""
        return when (command) {
            "add" -> addTask(params)
            "test" -> createTestTask()
            "list" -> listTasks()
            "edit", "update" -> editTask(params)
            "remove", "delete" -> removeTask(params)
            // Fallback: если команда не распознана, пробуем угадать по параметрам
            else -> {
                if (params.containsKey("label") || params.containsKey("address")) addTask(params)
                else if (params.containsKey("id")) removeTask(params)
                else listTasks()
            }
        }
    }

    private suspend fun addTask(params: Map<String, Any>): SkillResult {
        val label = params["label"]?.toString() ?: return SkillResult.Error("Укажите label — что нужно напомнить")
        val address = params["address"]?.toString()
        val radius = params["radius"]?.toString()?.toIntOrNull()
            ?: (params["radius"] as? Number)?.toInt()
            ?: 100

        // Координаты из параметров (если LLM передала)
        var lat = params["lat"]?.toString()?.toDoubleOrNull()
            ?: params["latitude"]?.toString()?.toDoubleOrNull()
            ?: (params["latitude"] as? Number)?.toDouble()
        var lon = params["lon"]?.toString()?.toDoubleOrNull()
            ?: params["longitude"]?.toString()?.toDoubleOrNull()
            ?: (params["longitude"] as? Number)?.toDouble()

        // Если координат нет, но есть адрес — пытаемся геокодировать
        if ((lat == null || lon == null) && !address.isNullOrBlank()) {
            try {
                val geocoder = android.location.Geocoder(context)
                val results = geocoder.getFromLocationName(address, 1)
                if (!results.isNullOrEmpty()) {
                    lat = results[0].latitude
                    lon = results[0].longitude
                }
            } catch (_: Exception) {
                // Геокодер недоступен — используем текущую локацию
            }
        }

        // Если всё ещё нет координат — используем текущее местоположение
        if (lat == null || lon == null) {
            val loc = locationService.getCachedLocation() ?: locationService.getCurrentLocation()
            if (loc != null && loc.latitude != 0.0) {
                lat = loc.latitude
                lon = loc.longitude
            } else {
                return SkillResult.Error("Не могу определить координаты для «$address». Укажи адрес точнее или включи GPS")
            }
        }

        val task = GeoTask(
            label = label,
            latitude = lat,
            longitude = lon,
            address = address,
            radiusMeters = radius
        )
        geoTaskRepository.save(task)
        val addrStr = address ?: "$lat, $lon"
        return SkillResult.Success(
            "✅ Гео-задача создана!\n«$label»\n📍 $addrStr\n📏 Радиус $radius м\n💡 Когда будешь рядом — придет уведомление"
        )
    }

    private suspend fun listTasks(): SkillResult {
        val tasks = geoTaskRepository.getActive()
        if (tasks.isEmpty()) return SkillResult.Success("📭 Нет активных гео-задач")

        val sb = StringBuilder("📍 **Активные гео-задачи (${tasks.size}):**\n")
        val loc = locationService.getCachedLocation()
        tasks.forEachIndexed { i, t ->
            val dist = if (loc != null && loc.latitude != 0.0) {
                val l1 = android.location.Location("").also { it.latitude = loc.latitude; it.longitude = loc.longitude }
                val l2 = android.location.Location("").also { it.latitude = t.latitude; it.longitude = t.longitude }
                val d = l1.distanceTo(l2).toInt()
                if (d < 1000) "${d}м" else "${d / 1000}км"
            } else "?"
            sb.appendLine("${i + 1}. «${t.label}»")
            if (t.address != null) sb.appendLine("   📍 ${t.address}")
            sb.appendLine("   📏 ~$dist · id: ${t.id.take(8)}…")
        }
        return SkillResult.Success(sb.toString())
    }

    private suspend fun removeTask(params: Map<String, Any>): SkillResult {
        val id = params["id"]?.toString() ?: return SkillResult.Error("Укажите id задачи")
        geoTaskRepository.deactivate(id)
        return SkillResult.Success("✅ Гео-задача деактивирована")
    }

    private suspend fun editTask(params: Map<String, Any>): SkillResult {
        val id = params["id"]?.toString() ?: return SkillResult.Error("Укажите id задачи для редактирования")
        // Получаем текущую задачу из БД через прямой запрос
        val activeTasks = geoTaskRepository.getActive()
        val existing = activeTasks.find { it.id == id }
            ?: return SkillResult.Error("Задача с id '$id' не найдена")

        val label = params["label"]?.toString() ?: existing.label
        val address = params["address"]?.toString() ?: existing.address
        val radius = params["radius"]?.toString()?.toIntOrNull()
            ?: (params["radius"] as? Number)?.toInt()
            ?: existing.radiusMeters
        val lat = params["lat"]?.toString()?.toDoubleOrNull()
            ?: params["latitude"]?.toString()?.toDoubleOrNull()
            ?: existing.latitude
        val lon = params["lon"]?.toString()?.toDoubleOrNull()
            ?: params["longitude"]?.toString()?.toDoubleOrNull()
            ?: existing.longitude

        val updated = existing.copy(
            label = label,
            latitude = lat,
            longitude = lon,
            address = address,
            radiusMeters = radius,
            lastTriggeredAt = null  // сбрасываем триггер, чтобы сработала заново
        )
        geoTaskRepository.save(updated)
        val addrStr = address ?: "$lat, $lon"
        return SkillResult.Success(
            "✅ Гео-задача обновлена!\n«$label»\n📍 $addrStr\n📏 Радиус $radius м"
        )
    }

    /**
     * Создаёт тестовую задачу на 50 метров вокруг текущего местоположения.
     */
    private suspend fun createTestTask(): SkillResult {
        val loc = locationService.getCachedLocation()
            ?: locationService.getCurrentLocation()
        if (loc == null || loc.latitude == 0.0) {
            return SkillResult.Error("❌ Нет данных GPS. Выйди на улицу с открытым небом")
        }

        val task = GeoTask(
            label = "🔔 ТЕСТ: напоминание в этом месте",
            latitude = loc.latitude,
            longitude = loc.longitude,
            address = loc.address,
            radiusMeters = 50
        )
        geoTaskRepository.save(task)
        return SkillResult.Success(
            "✅ Тестовая гео-задача создана!\n" +
            "«🔔 ТЕСТ: напоминание в этом месте»\n" +
            "📍 ${loc.address}\n" +
            "📏 Радиус 50 м\n" +
            "💡 Останься на месте или подойди поближе — через минуту придёт уведомление"
        )
    }
}
