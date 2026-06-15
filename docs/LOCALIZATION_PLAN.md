# Локализация Pai_Android1

## Фаза 1 — Перевод ✅ (15 июня)

- [x] 1.1 `agent/tools/LocaleManager.kt` — управление языком (SharedPrefs + applyLocale)
- [x] 1.2 `PaiApplication.kt` — подхват locale при старте
- [x] 1.3 `MainActivity.kt` — attachBaseContext с locale
- [x] 1.4 `res/values/strings.xml` — ~360 строк, все экраны (RU)
- [x] 1.5 `res/values-en/strings.xml` — английский перевод
- [x] 1.6 `SettingsScreen.kt` — переключатель 🇷🇺/🇬🇧
- [x] 1.7 Замена хардкодных строк на `stringResource()` во всех экранах (~310 замен)

## Фаза 2 — Подсказки ✅ (15 июня)

- [x] 2.1 `res/values/hints.xml` — 17 подсказок (RU)
- [x] 2.2 `res/values-en/hints.xml` — 17 подсказок (EN)
- [x] 2.3 `ui/components/HintTextView.kt` — HintText + HintDialog
- [x] 2.4 Внедрение HintText в экраны (6 из 7 — ChatDetailScreen TODO)

## Сводка по заменам (15 июня)

| Подзадача | Файлы | Замен | TODO |
|-----------|-------|-------|------|
| 1 (Settings, About, Appearance, Proactive, ChatList) | 5 | ~95 | ~10 |
| 2 (ChatDetail, Camera, Memory, Voice, LogTerminal, Scheduler) | 6 | 115 | 26 |
| 3 (Provider, WebSearch, Email, SkillStore, Router) | 5 | ~99 | ~8 |
| **Итого** | **16** | **~309** | **~44** |

*TODO строки добавлены в strings.xml, но замена в коде осталась для будущих правок*

## Созданные файлы

### Инфраструктура
- `agent/tools/LocaleManager.kt`
- `presentation/settings/LanguageSettingsViewModel.kt`
- `ui/components/HintTextView.kt` (HintText + HintDialog)

### Ресурсы
- `res/values/strings.xml` (~390 строк)
- `res/values-en/strings.xml` (~390 строк)
- `res/values/hints.xml` (17 подсказок)
- `res/values-en/hints.xml` (17 подсказок)

### Обновлённые файлы
- `PaiApplication.kt` — locale на старте + companion.instance
- `MainActivity.kt` — attachBaseContext
- `SettingsScreen.kt` — LanguageToggleCard
- `app/src/main/res/values/strings.xml` — перезаписан
- `app/src/main/res/values-en/strings.xml` — создан

## Что осталось (не обязательно прямо сейчас)
- Замена хардкодных строк → stringResource() в 15+ экранах
- Внедрение HintText в каждый экран
