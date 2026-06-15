package com.pai.android.agent

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.Intent as AndroidIntent
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.model.Message

/**
 * Навык поиска и запуска приложений в Android.
 * Использует AI для извлечения названия приложения из запроса,
 * PackageManager для поиска по системе, AndroidIntent для запуска.
 */
class AppLaunchSkill(
    private val context: Context,
    private val aiRepository: AiRepository
) : Skill {

    override val name: String = "app_launch"
    override val description: String = "Search and launch Android apps: calculator, camera, settings, browser, and other installed applications"

    override fun canHandle(intent: Intent, query: String, params: Map<String, Any>): Boolean {
        return intent == Intent.APP_LAUNCH || params["command"] == "app_launch" || params["command"] == "open_app"
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        return try {
            val query = params["query"] as? String ?: params["app_name"] as? String
                ?: return SkillResult.Error(message = "Не указано приложение для поиска",
                    details = "Укажите название приложения в параметре 'query' или 'app_name'")

            println("📱 AppLaunchSkill: поиск приложения '$query'")
            val appName = extractAppNameWithAI(query)
            val appInfo = findApp(appName)

            if (appInfo != null) {
                val launched = launchApp(appInfo)
                if (launched) {
                    SkillResult.Success(
                        message = "✅ **Запущено:** ${appInfo.displayName}",
                        data = mapOf("app_name" to appInfo.displayName, "package_name" to appInfo.packageName, "launched" to true),
                        responseType = ResponseType.TEXT
                    )
                } else {
                    SkillResult.Error(message = "Не удалось запустить приложение '${appInfo.displayName}'",
                        details = "Приложение найдено, но не удалось открыть")
                }
            } else {
                SkillResult.Error(message = "Приложение '$appName' не найдено в системе",
                    details = "Попробуйте уточнить название или установить приложение")
            }
        } catch (e: Exception) {
            println("❌ AppLaunchSkill ошибка: ${e.message}")
            SkillResult.Error(message = "Ошибка при поиске/запуске приложения", details = e.message)
        }
    }

    private suspend fun extractAppNameWithAI(query: String): String {
        return try {
            println("🧠 AppLaunchSkill: AI-анализ запроса '$query'")
            val response = aiRepository.sendMessage(
                messages = listOf(
                    Message.createUserMessage(chatId = "app_launch_skill",
                        content = "Извлеки название приложения из запроса пользователя. Верни ТОЛЬКО название, без пояснений.\n" +
                            "Примеры:\n" +
                            "\"запусти калькулятор\" → Калькулятор\n\"сфоткай меня\" → Камера\n\"открой настройки\" → Настройки\n" +
                            "\"запусти хром\" → Chrome\n\"проверь почту\" → Почта\n\"напиши заметку\" → Заметки\n" +
                            "Запрос: \"$query\"\nНазвание приложения:")
                ),
                modelOverride = "deepseek-chat"
            )
            if (response.isSuccess) {
                val extracted = response.getOrThrow().text.trim()
                if (extracted.isNotBlank() && extracted.length < 100) {
                    println("📱 AppLaunchSkill: AI извлёк название: '$extracted'")
                    return extracted
                }
            }
            query
        } catch (e: Exception) {
            println("⚠️ AppLaunchSkill: AI не сработал: ${e.message}")
            query
        }
    }

    data class AppInfo(val displayName: String, val packageName: String, val launchIntent: AndroidIntent)

    /** Транслитерация русского текста в латиницу */
    private fun transliterate(text: String): String {
        val map = mapOf(
            'а' to 'a', 'б' to 'b', 'в' to 'v', 'г' to 'g', 'д' to 'd',
            'е' to 'e', 'ё' to 'e', 'ж' to 'z', 'з' to 'z', 'и' to 'i',
            'й' to 'i', 'к' to 'k', 'л' to 'l', 'м' to 'm', 'н' to 'n',
            'о' to 'o', 'п' to 'p', 'р' to 'r', 'с' to 's', 'т' to 't',
            'у' to 'u', 'ф' to 'f', 'х' to 'h', 'ц' to 'c', 'ч' to 'c',
            'ш' to 's', 'щ' to 's', 'ъ' to ' ', 'ы' to 'y', 'ь' to ' ',
            'э' to 'e', 'ю' to 'u', 'я' to 'a'
        )
        return text.lowercase().map { map[it] ?: it }.joinToString("")
    }

    /** Fuzzy-match с транслитерацией */
    private fun fuzzyMatch(query: String, appLabel: String): Boolean {
        val q = query.lowercase().trim()
        val l = appLabel.lowercase().trim()
        val tq = transliterate(q)
        val tl = transliterate(l)
        return l.contains(q) || q.contains(l) ||
            tl.contains(tq) || tq.contains(tl) ||
            l.contains(tq) || tq.contains(l)
    }

    private fun findApp(appName: String): AppInfo? {
        val pm = context.packageManager
        val lowerName = appName.lowercase().trim()
        val candidates = mutableListOf<Pair<Int, AppInfo>>()

        // Фаза 1: Known system packages (bypass PackageManager limitations)
        // Пробуем несколько вариантов пакетов (AOSP, Samsung, Google)
        val known = mapOf(
            "настройки" to listOf("com.android.settings"),
            "settings" to listOf("com.android.settings"),
            "калькулятор" to listOf("com.android.calculator2", "com.sec.android.app.popupcalculator", "com.google.android.calculator"),
            "calculator" to listOf("com.android.calculator2", "com.sec.android.app.popupcalculator", "com.google.android.calculator"),
            "камера" to listOf("com.android.camera", "com.sec.android.camera", "com.google.android.apps.camera"),
            "camera" to listOf("com.android.camera", "com.sec.android.camera", "com.google.android.apps.camera"),
            "часы" to listOf("com.android.deskclock", "com.sec.android.app.clockpackage", "com.google.android.deskclock"),
            "clock" to listOf("com.android.deskclock", "com.sec.android.app.clockpackage", "com.google.android.deskclock"),
            "контакты" to listOf("com.android.contacts", "com.samsung.android.app.contacts"),
            "contacts" to listOf("com.android.contacts", "com.samsung.android.app.contacts"),
            "телефон" to listOf("com.android.dialer", "com.samsung.android.dialer"),
            "phone" to listOf("com.android.dialer", "com.samsung.android.dialer"),
            "хром" to listOf("com.android.chrome", "com.chrome.beta", "org.chromium.chrome"),
            "chrome" to listOf("com.android.chrome", "com.chrome.beta", "org.chromium.chrome"),
            "браузер" to listOf("com.android.chrome", "com.android.browser", "com.sec.android.app.sbrowser", "com.chrome.beta"),
            "browser" to listOf("com.android.chrome", "com.android.browser", "com.sec.android.app.sbrowser"),
            "календарь" to listOf("com.android.calendar", "com.samsung.android.calendar", "com.google.android.calendar"),
            "calendar" to listOf("com.android.calendar", "com.samsung.android.calendar", "com.google.android.calendar"),
            "галерея" to listOf("com.android.gallery3d", "com.sec.android.gallery3d", "com.google.android.apps.photos"),
            "gallery" to listOf("com.android.gallery3d", "com.sec.android.gallery3d", "com.google.android.apps.photos"),
            "файлы" to listOf("com.android.documentsui", "com.sec.android.app.myfiles"),
            "files" to listOf("com.android.documentsui", "com.sec.android.app.myfiles"),
            "youtube" to listOf("com.google.android.youtube", "com.vanced.android.youtube"),
            "плей маркет" to listOf("com.android.vending"),
            "play store" to listOf("com.android.vending"),
            "сообщения" to listOf("com.android.mms", "com.samsung.android.messaging", "com.google.android.apps.messaging"),
            "messages" to listOf("com.android.mms", "com.samsung.android.messaging", "com.google.android.apps.messaging"),
            "заметки" to listOf("com.android.notes", "com.samsung.android.app.notes", "com.google.android.keep"),
            "notes" to listOf("com.android.notes", "com.samsung.android.app.notes", "com.google.android.keep"),
            "музыка" to listOf("com.android.music", "com.sec.android.app.music", "com.google.android.music"),
            "music" to listOf("com.android.music", "com.sec.android.app.music", "com.google.android.music"),
            "карты" to listOf("com.google.android.apps.maps"),
            "maps" to listOf("com.google.android.apps.maps")
        )
        
        // Lookup known packages (пробуем все варианты, пока не найдём работающий)
        val knownPkgs = known[lowerName] ?: known.entries.find { lowerName.contains(it.key) }?.value
        if (knownPkgs != null) {
            for (pkg in knownPkgs) {
                try {
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        println("📱 AppLaunchSkill: найден по known: '$label' ($pkg)")
                        return AppInfo(label, pkg, intent)
                    }
                } catch (e: Exception) {
                    println("📱 AppLaunchSkill: known '$pkg' недоступен: ${e.message}")
                }
            }
        }

        // Фаза 2: Поиск по ВСЕМ установленным пакетам (getInstalledApplications)
        try {
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            println("📱 AppLaunchSkill: всего установлено: ${allApps.size} пакетов")

            for (app in allApps) {
                val displayName = pm.getApplicationLabel(app).toString()
                val lowerDisplay = displayName.lowercase()
                val pkgName = app.packageName

                // Точное совпадение
                if (lowerDisplay == lowerName) {
                    val intent = pm.getLaunchIntentForPackage(pkgName)
                    if (intent != null) {
                        println("📱 AppLaunchSkill: точное совпадение: '$displayName' ($pkgName)")
                        return AppInfo(displayName, pkgName, intent)
                    }
                }

                // Частичное совпадение
                var score = 0
                if (fuzzyMatch(lowerName, displayName)) score = 60
                else if (pkgName.lowercase().contains(lowerName)) score = 40
                else if (lowerName.contains(pkgName.lowercase().substringAfterLast("."))) score = 30

                if (score > 0) {
                    val intent = pm.getLaunchIntentForPackage(pkgName)
                    if (intent != null) {
                        candidates.add(score to AppInfo(displayName, pkgName, intent))
                    }
                }
            }
        } catch (e: Exception) {
            println("⚠️ AppLaunchSkill: getInstalledApplications ошибка: ${e.message}")
        }

        // Фаза 3: Fallback — queryIntentActivities (только launchable)
        if (candidates.isEmpty()) {
            try {
                val launchIntent = AndroidIntent(AndroidIntent.ACTION_MAIN).apply { addCategory(AndroidIntent.CATEGORY_LAUNCHER) }
                val launchables = pm.queryIntentActivities(launchIntent, 0)
                for (ri in launchables) {
                    val displayName = ri.loadLabel(pm).toString()
                    val pkgName = ri.activityInfo.packageName
                    var score = 0
                    if (displayName.lowercase().contains(lowerName)) score = 20
                    else if (pkgName.lowercase().contains(lowerName)) score = 10
                    if (score > 0) {
                        val intent = pm.getLaunchIntentForPackage(pkgName)
                        if (intent != null) candidates.add(score to AppInfo(displayName, pkgName, intent))
                    }
                }
            } catch (e: Exception) {
                println("⚠️ AppLaunchSkill: queryIntentActivities ошибка: ${e.message}")
            }
        }

        return candidates.maxByOrNull { it.first }?.second
    }

    private fun createAppInfo(ri: ResolveInfo, pm: PackageManager, displayName: String): AppInfo {
        val pkg = ri.activityInfo.packageName
        val intent = pm.getLaunchIntentForPackage(pkg) ?: AndroidIntent(AndroidIntent.ACTION_MAIN).apply {
            addCategory(AndroidIntent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }
        return AppInfo(displayName, pkg, intent)
    }

    private fun launchApp(appInfo: AppInfo): Boolean {
        return try {
            appInfo.launchIntent.addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(appInfo.launchIntent)
            println("📱 AppLaunchSkill: запущен ${appInfo.displayName} (${appInfo.packageName})")
            true
        } catch (e: Exception) {
            println("❌ AppLaunchSkill: ошибка запуска: ${e.message}")
            false
        }
    }
}
