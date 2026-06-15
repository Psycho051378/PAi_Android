# План реализации MapsTool

## Стек
- **Публичные API (все без ключей):** Nominatim (геокодинг), Overpass API (поиск POI), OSRM (маршруты)
- **Нативный Kotlin-инструмент** в Pai_Android1 (регистрация в ToolRegistry)
- **Intent** для открытия Яндекс.Карт / Google Maps

## API без ключей

### 1. Nominatim (OpenStreetMap) — геокодирование
- Адрес → координаты: `GET https://nominatim.openstreetmap.org/search?q={query}&format=json&limit=5&accept-language=ru`
- Координаты → адрес: `GET https://nominatim.openstreetmap.org/reverse?lat={lat}&lon={lon}&format=json&accept-language=ru`
- Лимит: 1 запрос/сек (обязательно User-Agent)

### 2. Overpass API — поиск объектов (POI)
- Запрос на Overpass QL языке: `POST https://overpass-api.de/api/interpreter`
- Пример: найти АЗС в радиусе 1000м от точки:
  ```
  [out:json][timeout:25];
  (
    node["amenity"="fuel"](around:1000,59.8587,29.9456);
    way["amenity"="fuel"](around:1000,59.8587,29.9456);
  );
  out body center;
  ```
- Категории: fuel (АЗС), restaurant, cafe, supermarket, pharmacy, hospital, parking, school

### 3. OSRM — построение маршрута
- `GET https://router.project-osrm.org/route/v1/driving/{lon1},{lat1};{lon2},{lat2}?overview=false&steps=true`
- Возвращает: расстояние, время, геометрию маршрута

### 4. Intent — открытие карты
- Google Maps: `geo:{lat},{lon}?q={lat},{lon}({label})` или `https://www.google.com/maps/dir/?api=1&origin={lat1},{lon1}&destination={lat2},{lon2}`
- Яндекс.Карты: `https://yandex.ru/maps/?rtext={lat1},{lon1}~{lat2},{lon2}`

## Архитектура

### 1. MapsTool.kt — нативный инструмент
```
MapsTool : AgentTool
├── execute(params)
│   ├── action=search   → searchPOI(query, lat, lon, radius) → Overpass API
│   ├── action=geocode  → geocode(address) → Nominatim
│   ├── action=reverse  → reverseGeocode(lat, lon) → Nominatim
│   ├── action=route    → buildRoute(fromLat, fromLon, toLat, toLon) → OSRM
│   └── action=open     → openInMaps(lat, lon, label) → Intent
│
├── searchPOI()  → Overpass API interpreter
├── geocode()    → Nominatim search
├── reverseGeocode() → Nominatim reverse
├── buildRoute() → OSRM router
└── openInMaps() → Intent(ACTION_VIEW) to Yandex/Google Maps
```

### 2. Регистрация
- **ToolRegistry**: `toolRegistry.register(mapsTool)` — в AppModule.kt
- **AgentPlanner.KNOWN_SKILLS**: добавить `"maps" → "search nearby places, geocode addresses, build routes, open navigation"`
- **AgentPlanner.buildPlanPrompt**: добавить правило для maps

## Пример сценария
```
Запрос: "построй маршрут до ближайшей заправки"

1. location (action=current) → 59.8587, 29.9456
2. maps (action=search, query="АЗС", lat=59.8587, lon=29.9456, radius=1000)
   → Overpass: [{"name":"Лукойл","lat":59.8601,"lon":29.9502,...}]
3. maps (action=open, lat=59.8601, lon=29.9502, label="Лукойл")
   → Intent → Яндекс.Карты с маршрутом
```

## Файлы для создания/изменения
1. **NEW** `app/src/main/java/com/pai/android/agent/tools/MapsTool.kt`
2. **EDIT** `app/src/main/java/com/pai/android/di/AppModule.kt` — регистрация в ToolRegistry
3. **EDIT** `app/src/main/java/com/pai/android/agent/AgentPlanner.kt` — KNOWN_SKILLS + buildPlanPrompt
