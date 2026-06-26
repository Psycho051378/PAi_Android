# Согласование AgentPlanner и Smart Router — план доработки

## Проблема

AgentPlanner и Smart Router работают на разных уровнях и не учитывают решения друг друга:

```
AgentPlanner (нашёл web_search) ──→ AiRepository.sendMessage ──→ SmartRouter → HYBRID (игнорирует план)
```

**Результат:** навыки (web_search, file_ops, email, и т.д.) не выполняются, т.к. Hybrid перехватывает управление до их запуска. AI отвечает из устаревших обучающих данных.

## Решение

### Шаг 1 — DecisionEngine.processQuery: разделить планирование и маршрутизацию

Сейчас processQuery запускает AgentPlanner И AiRepository.sendMessage почти одновременно. Нужно:

1. AgentPlanner строит план
2. **Если план содержит навыки** (web_search, file_ops, email, calendar и т.д.) → **выполняем их** в processQuery до отправки в AI
3. Результаты выполнения складываем в `memoryContext` (обогащённый контекст)
4. **Только потом** вызываем `AiRepository.sendMessage` с обогащённым контекстом

### Шаг 2 — Флаг `planExecuted`

Добавить флаг, что план уже выполнен:
- `AgentResponse.planExecuted: Boolean = false`
- Если AgentPlanner выполнил план → `planExecuted = true`

### Шаг 3 — SmartRouter: учитывать `planExecuted`

В `SmartRouter.route()`:
- Если `planExecuted == true` → **не включать Hybrid**
- Вернуть `RouteDecision.Network` (или `RouteDecision.Local`, если модель локальная)
- Почему: план уже выполнен, результаты поиска/операций уже в контексте — нет смысла разбивать на подшаги

### Шаг 4 — Новая последовательность

```
processQuery(query):
  1. AgentPlanner строит план на основе query
  2. Если план не пуст → выполняем шаги плана:
     - web_search → поиск → результаты → memoryContext
     - file_ops → файловая операция → результат → memoryContext
     - email → отправка/чтение → результат → memoryContext
     - ...
     - planExecuted = true
  3. Отправляем в AiRepository.sendMessage:
     - memoryContext = старый контекст + результаты выполнения
     - planExecuted = true (через settings или отдельный параметр)
  4. SmartRouter.route():
     - Если planExecuted → NETWORK (не Hybrid)
     - DeepSeek/GPT получает query + memoryContext (с результатами поиска)
     - Отвечает на основе актуальных данных
```

### Какие файлы менять

- `DecisionEngine.kt` — processQuery(): новая последовательность выполнения плана
- `AgentPlanner.kt` — возможно, добавить возврат `planExecuted`
- `SmartRouter.kt` — `route()`: проверка `planExecuted`
- `AiRepository.kt` — передавать `planExecuted` в SmartRouter

### Что НЕ меняется

- `handleHybrid()` — остаётся как есть, просто не вызывается когда planExecuted=true
- `trySendToLocal()`, `fallbackAndGet()`, `isSatisfied()` — без изменений
- Smart Router настройки (enableHybrid, hybridThreshold) — без изменений

---

*План составлен 25 июня 2026. Предыдущая работа: накопление контекста и раннее завершение в handleHybrid уже реализованы.*
