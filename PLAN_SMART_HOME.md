# Smart Home Control — Implementation Plan

## Overview
Extend Pai_Android1 with persistent device storage, automatic device type detection, and multi-protocol control capabilities. The system must be **fully dynamic** — no hardcoded network names, IPs, or device-specific logic in the core. All manufacturer-specific knowledge is in pluggable **detectors** and **controllers**.

## Architecture Layers

### Layer 1: Data Models (Room)
```
smart_home_network
 ├─ id (UUID, PK)
 ├─ ssid: String
 ├─ gatewayIp: String
 ├─ subnet: String
 └─ lastScan: Long (timestamp)

smart_home_devices
 ├─ id (UUID, PK)
 ├─ networkId (FK → smart_home_network.id)
 ├─ mac: String (unique device identifier)
 ├─ ip: String (current IP — may change)
 ├─ hostname: String (original scanner hostname)
 ├─ displayName: String (user-friendly name, auto-generated or custom)
 ├─ deviceType: String (LIGHT | VACUUM | HUMIDIFIER | SPEAKER | TV | ESP_DEVICE | ROUTER | COMPUTER | PHONE | WEARABLE | OTHER)
 ├─ protocol: String (wiz | yeelight | tasmota | esphome | roborock | mqtt | yandex | unknown)
 ├─ port: Int (control port, 0 = unknown)
 ├─ capabilities: String (JSON array: ["on_off","brightness","color_temp","rgb","power"])
 ├─ present: Boolean (true = currently in network)
 ├─ firstSeen: Long
 ├─ lastSeen: Long
 ├─ vendor: String (MAC OUI vendor name)
 └─ metadata: String (JSON extras: firmware, model, UPnP details, etc.)
```

### Layer 2: Device Type Detection (`DeviceTypeDetector`)
Stateless utility that determines device type from:
- **Hostname patterns** (extensible map)
- **Open ports** (extensible map)
- **UDP fingerprints** from `UdpProber`
- **HTTP fingerprints** from `HttpProber`

Example pattern entries (NOT hardcoded to any specific network):
```kotlin
hostnamePatterns = mapOf(
    "yeelink" to DeviceTypeInfo.LIGHT("yeelight", 55443),
    "wiz_" to DeviceTypeInfo.LIGHT("wiz", 0),
    "roborock-vacuum" to DeviceTypeInfo.VACUUM("roborock"),
    "zhimi-humidifier" to DeviceTypeInfo.HUMIDIFIER("xiaomi_miio"),
    "ESP_" to DeviceTypeInfo.ESP_DEVICE("tasmota"),
    "YandexStation" to DeviceTypeInfo.SPEAKER("yandex"),
    ...
)
```

### Layer 3: Smart Home Repository (`SmartHomeRepository`)
Single source of truth for device data:
- `updateFromScan(networkId, rawDevices: List<DeviceRaw>)` — patch scan results into DB
- `getAllDevices(networkId)` — list devices
- `getDeviceTypes()` — all distinct types present
- `renameDevice(deviceId, newName)` — user override
- `deleteDevice(deviceId)` — remove from DB
- `getDevicesByType(networkId, type)` — for control commands
- `resolveIntent(action, targetType)` → list of matching devices

### Layer 4: Device Controllers
Interface + pluggable implementations:
```kotlin
interface DeviceController {
    val protocol: String
    suspend fun turnOn(ip: String, port: Int): Boolean
    suspend fun turnOff(ip: String, port: Int): Boolean
    suspend fun setBrightness(ip: String, port: Int, level: Int): Boolean
    suspend fun setColorTemp(ip: String, port: Int, temp: Int): Boolean
    suspend fun setRGB(ip: String, port: Int, r: Int, g: Int, b: Int): Boolean
    suspend fun customCommand(ip: String, port: Int, command: String, params: Map<String, Any>): Result
}
```

**Initial controllers:**
- `WizController` — UDP 38899, JSON commands
- `YeelightController` — TCP 55443, JSON-RPC
- `TasmotaController` — HTTP GET cmnd
- `RoborockController` — HTTP API

**ControllerRouter** — maps `device.protocol` → controller instance

### Layer 5: Intent Parser
Maps natural language to actions:
```kotlin
data class ControlIntent(
    val action: String,        // "turn_on", "turn_off", "set_brightness", "set_color_temp", "start", "stop"
    val targetType: String?,   // "light", "vacuum", null = all manageable
    val targetName: String?,   // specific device name or null
    val params: Map<String, Any>  // extra params (brightness level, temp, etc.)
)
```
- "turn off the lights" → TURN_OFF, LIGHT
- "start cleaning" → START, VACUUM
- "set lamp 1 to 50%" → SET_BRIGHTNESS, null, targetName="Lamp 1", params={level:50}
- "turn off WiZ 2" → TURN_OFF, null, targetName="WiZ Lamp 2"

### Layer 6: Device Name Generator
- Uses `deviceType` → prefix map: `LIGHT → "Lamp"`, `VACUUM → "Vacuum"`, etc.
- Appends counter per type: `"Lamp 1"`, `"Lamp 2"`
- No hardcoded names, fully data-driven
- User can override via `renameDevice()`

---

## Implementation Phases

### Phase 1 — Database (now)
Files:
- `data/model/SmartHomeNetwork.kt`
- `data/model/SmartHomeDevice.kt`
- `data/local/SmartHomeDao.kt`
- Update `AppDatabase.kt` (v22→v23 migration)
- Update `AppModule.kt` (DI)

### Phase 2 — Type Detection + Name Generator (now)
Files:
- `agent/skills/home/detector/DeviceTypeDetector.kt`
- `agent/skills/home/detector/DeviceNameGenerator.kt`

### Phase 3 — SmartHomeRepository (next session)
Files:
- `data/repository/SmartHomeRepository.kt`
- Update `HomeSkill.kt` (integrate repository, save scan results)

### Phase 4 — Device Controllers
Files under `agent/skills/home/device/`:
- `DeviceController.kt` (interface)
- `WizController.kt`
- `YeelightController.kt`
- `TasmotaController.kt`
- `DeviceControllerRouter.kt`

### Phase 5 — Control Intent + Integration
- `agent/skills/home/device/ControlIntentParser.kt`
- `agent/skills/home/device/SmartHomeDispatcher.kt`
- Update `HomeSkill.canHandle()` / `doControl()`

### Phase 6 — User Commands for Device Management
- "show devices", "rename device", "delete device", "what's missing"

---

## Design Principles
1. **No hardcoded data** — all names, IPs, SSIDs, device types are data-driven
2. **Extensible** — add new manufacturers by adding entries to maps and/or new controller classes
3. **Portable** — works on any home network with supported device types
4. **English code** — all code, comments, and prompts in English
5. **CLARITY** — clean interfaces, documented extension points, obvious for any developer
