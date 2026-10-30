# Изменения за 2026-10-30 — навык «Умный дом» (Pai_Android1)

Файл для быстрого ввода в курс дела (в т.ч. для OpenClaw).
Проект: E:\BackUps\.openclaw\workspace\projects\Pai_Android1
Сборка: app-debug.apk от 30.10.2026 16:06 (187.8 МБ), Kotlin компилируется, 11 юнит-тестов зелёные.

--------------------------------------------------------------------------------
0. КОРОТКО (TL;DR)
--------------------------------------------------------------------------------

1. MAC больше не обязателен для управления: устройства опознаются сами
   (WiZ, Yeelight, Shelly, Tasmota, плюс WLED/MiIO для состояния).
   Раньше всё, у чего не было MAC из роутера/ARP, отбрасывалось при сохранении скана.

2. Найдены и исправлены две реальные причины «пустой сети»:
   - активный VPN (Cloudflare WARP) уводил broadcast/multicast в туннель;
   - часть Yeelight не отвечает на multicast M-SEARCH (IGMP snooping после
     переподключения) — добавлен unicast-sweep.

3. Управление стало адресным: быстрая идентификация перед командой,
   unicast вместо «полива», проверка состояния до и после команды.
   Плюс исправлен баг сохранения галочки «Использовать роутер».

--------------------------------------------------------------------------------
1. ЗАЧЕМ (контекст проблемы)
--------------------------------------------------------------------------------

До изменений было два режима:
  - адресный: нужен MAC в БД (smart_home_devices.mac, NOT NULL + UNIQUE);
  - blind: команда «всем подряд» (broadcast + перебор 254 IP).

MAC добывался только через ARP / mDNS / вход на роутер (реально поддерживался
лишь TP-Link). Отсюда: либо логин на роутере, либо «широкий полив».

Ключевая мысль: половина протоколов сама сообщает MAC, модель и состояние.
Код эти ответы уже получал (WiZ broadcast, Shelly /shelly и т.п.), но выбрасывал,
потому что при сохранении стояло: if (mac == "?") continue.

--------------------------------------------------------------------------------
2. НОВЫЕ ФАЙЛЫ
--------------------------------------------------------------------------------

Слой идентичности (app/src/main/java/com/pai/android/agent/skills/home/identity/):
  Identity.kt                 — IdentityHit, DeviceState (сравнение состояния с
                                командой), MacUtil (нормализация MAC, отбраковка
                                multicast/zero), интерфейс IdentityProbe.
  IdentityRegistry.kt         — оркестратор: broadcast-этап + адресный этап,
                                бюджет времени (9 с), слияние результатов,
                                Semaphore(16).
  WizIdentityProbe.kt         — UDP 38899 getPilot broadcast/unicast (MAC, состояние),
                                getSystemConfig (model/roomId). Шлёт на broadcast
                                подсети (x.y.z.255) и на 255.255.255.255.
  YeelightIdentityProbe.kt    — SSDP 239.255.255.250:1982 (2 раунда) + unicast-sweep
                                по подсети + TCP 55443 get_prop.
  ShellyIdentityProbe.kt      — GET /rpc/Shelly.GetDeviceInfo (Gen2) и /shelly (Gen1).
  TasmotaIdentityProbe.kt     — GET /cm?cmnd=Status%200 (MAC, Module, Version,
                                POWER/Dimmer/CT/Color).
  ProbeHttp.kt                — общий HTTP-клиент пробников: 400/900/1200 мс.

Сеть (…/home/net/):
  LanNetwork.kt               — привязка сокетов к Wi-Fi сети через
                                Network.bindSocket (API 24+), wifiIpv4(),
                                broadcastAddress(). Нужна, когда активен VPN.

Устройства (…/home/device/):
  StatefulController.kt       — интерфейс «умею читать состояние» (readState).
                                Реализован в Wiz/Yeelight/Shelly/Tasmota/
                                GenericHttp(WLED)/Miio контроллерах.

Тесты:
  app/src/test/java/com/pai/android/agent/skills/home/identity/IdentityTest.kt
                              — 11 тестов: нормализация MAC, multicast-бит,
                                сравнение состояния (turn_on/off, brightness,
                                rgb, color_temp), identityKey.

Инструменты и документация:
  tools/lan_probe.py          — эталонный пробник с ПК: автоопределение интерфейса,
                                WiZ, Yeelight (multicast+unicast), UPnP, mDNS,
                                MiIO, порт-скан «немых» устройств (Tuya 6668 и др.).
  docs/smart-home-network-test-plan.md
                              — методика проверки на реальной сети (16 кейсов,
                                критерии, диагностика, VPN, mDNS/unicast).
  plans/smart-home-discovery-v2.md
                              — план фаз (gitignored, см. .gitignore: plans/).
  baseline.json               — эталон текущей сети (локальный артефакт, не коммитится).

--------------------------------------------------------------------------------
3. ИЗМЕНЁННЫЕ ФАЙЛЫ
--------------------------------------------------------------------------------

agent/skills/HomeSkill.kt  (главные изменения)
  - конструктор: + IdentityRegistry, + LanNetwork;
  - doScan переписан: параллельно ping / mDNS+SSDP / broadcast-идентичность;
    двухпроходный TCP-скан (вся /24 по 80/443/55443, затем полный FAST_PORTS
    по найденным); identity-unicast; NetBIOS только для «неизвестных» и с
    бюджетом; роутер — опциональное дополнение;
  - запись в БД по identity (MAC или протокол:id), без требования MAC;
    устройства совсем без идентичности по-прежнему не сохраняются;
  - quickDiscoverManageable(): быстрая идентификация перед командой, когда
    БД пуста/устарела (broadcast + unicast-sweep + HTTP-fallback);
  - getMyIp()/getGatewayIp(): сначала Wi-Fi сеть, потом activeNetwork
    (при VPN activeNetwork — это туннель), потом WifiManager;
  - pingSweep: пул 48 потоков + бюджет 6 с, ProcessBuilder с вычитыванием
    stdout (раньше 254 потока и 254 процесса ping одновременно);
  - deviceLabel: метки Tuya (6668), IP-камера (554), принтер (9100);
  - guessVendor: добавлены OUI Yeelight B4:60:ED, B0:E2:35, 78:11:DC;
  - удалён неиспользуемый tcpSweep.

home/TcpScanner.kt
  - FAST_PORTS (13 портов, включая 6668 Tuya) и COMMON_PORTS (глубокий скан);
  - invokeAll с бюджетом вместо awaitTermination(10s): раньше возвращался
    частичный результат и устройства «пропадали»;
  - таймаут connect 300 мс, 96 потоков; aliveHosts(); isPortOpen(network).

home/UdpProber.kt
  - Yeelight SSDP: порт исправлен с 4321 на 1982;
  - MiIO: вместо JSON — бинарный handshake 21 31 00 20 + 28 x 0xFF, JSON
    вырезается из ответа после 32-байтного заголовка;
  - флаги includeWiz/includeYeelight (не дублировать identity-слой);
  - discoverNetbios(ip, timeoutMs, network); привязка сокетов к Wi-Fi.

home/MulticastDiscovery.kt
  - параметр network и привязка сокетов;
  - mDNS: MulticastSocket создаётся НЕсвязанным (MulticastSocket(null)),
    затем bindSocket и bind(5353) — иначе при VPN multicast уходит в туннель;
  - SSDP: привязка сокета.

home/device/BlindDispatcher.kt
  - guided-режим: сначала IdentityRegistry.broadcastScan, затем адресная
    отправка только подходящим по capability устройствам (turn_on/off →
    on_off, яркость → brightness, rgb → rgb, ct → color_temp);
  - «полив» остался последним рубежом, если никого не опознали;
  - pingSweep заменён на TCP-лайвность (isReachable на Android не шлёт ICMP,
    падает на TCP:7 и всегда даёт пусто).

home/device/SmartHomeDispatcher.kt
  - команды выполняются параллельно (async/awaitAll), порядок ответов сохранён;
  - идемпотентность: если устройство уже в целевом состоянии — не отправляем
    («уже выключено — команда не отправлена»);
  - верификация: через 400 мс перечитываем состояние, ответ
    «(подтверждено)» / «отправлено, но состояние не изменилось»;
  - get_status идёт через StatefulController.readState (раньше слался
    невалидный customCommand("status") для Yeelight/WiZ/WLED).

home/device/{Wiz,Yeelight,Shelly,Tasmota,GenericHttp,Miio}Controller.kt
  - реализуют StatefulController.readState():
      Wiz — getPilot; Yeelight — get_prop; Shelly — Switch/Light GetStatus,
      Gen1 /status, /relay/0; Tasmota — Status 8 / Status 0 (StatusSTS);
      WLED — /json/state; Miio — python status (state, battery).

data/local/SmartHomeDao.kt
  - getDeviceByMac(... COLLATE NOCASE) — LLM может вернуть MAC в другом регистре.

data/repository/SmartHomeRepository.kt
  - saveDiscovered(devices): точечный upsert без пометки остальных absent
    (updateFromScan — это снимок сети, для быстрой идентификации не подходит).

di/AppModule.kt
  - provideHomeSkill: + IdentityRegistry, + LanNetwork.

ui/screens/RouterSettingsViewModel.kt + RouterSettingsScreen.kt
  - ИСПРАВЛЕН БАГ: галочка «Использовать роутер для сканирования сети» не
    сохранялась. toggleEnabled() менял только UI, а кнопка «Сохранить» была
    enabled = fieldsEnabled && !loading, где fieldsEnabled = state.enabled,
    т.е. при снятой галочке кнопка блокировалась и выключить роутер было нельзя.
  - Теперь toggle сохраняет конфиг сразу (синхронно, prefs.apply()), кнопка
    «Сохранить» не зависит от галочки (enabled = !state.loading).

app/build.gradle.kts
  - пины Python-зависимостей: beautifulsoup4==4.12.3, soupsieve==2.5
    (Chaquopy ставит Python 3.11.0, свежий soupsieve требует >= 3.11.5 —
    из-за этого сборка падала на :app:generateDebugPythonRequirements).

--------------------------------------------------------------------------------
4. ЧТО ИЗМЕНИЛОСЬ В ПОВЕДЕНИИ (видно пользователю)
--------------------------------------------------------------------------------

Отчёт скана:
  - новая строка «Опознано протоколом: N»;
  - на устройство: «🔎 WIZ <model> · порт 38899 · wiz-broadcast»,
    «Состояние: вкл, ярк.100%», источник (wiz-broadcast / yeelight-ssdp /
    yeelight-unicast / shelly-gen2 / tasmota);
  - «🔌 Tuya (имя только через облако/ключ)» для порта 6668;
  - «📹 IP-камера» (554), «🖨 Принтер» (9100).

Команды:
  - «выключи свет» из пустой БД: сначала быстрая идентификация (~1–2 с),
    затем unicast только лампам;
  - повторная команда не отправляется («уже выключено»);
  - результат подтверждается чтением состояния.

База:
  - колонка mac теперь хранит «идентичность»: MAC (AA:BB:CC:DD:EE:FF) либо
    синтетический ключ (wiz:..., yeelight:0x..., shelly:...);
  - реальный MAC (если известен) — в metadata.realMac;
  - устройства без идентичности (ПК, телефоны) в БД не пишутся.

Настройки:
  - prefs-файл home_skill, ключ router_config (JSON: enabled, ip, port,
    username, password, community, protocol).

--------------------------------------------------------------------------------
5. ИСПРАВЛЕННЫЕ БАГИ (список)
--------------------------------------------------------------------------------

1. Нет MulticastLock → mDNS/SSDP почти ничего не находили.
   Теперь withMulticastLock() вокруг mDNS и broadcast-идентичности.
2. Yeelight M-SEARCH уходил на 4321 вместо 1982.
3. TcpScanner возвращал частичный результат (awaitTermination 10 с).
4. BlindDispatcher.pingSweep на InetAddress.isReachable — всегда пусто на Android.
5. get_status слал невалидный запрос (Yeelight/WiZ/WLED).
6. Порт управления брался «первым открытым» (мог быть 22/554).
7. MiIO discovery слал JSON вместо бинарного handshake.
8. Broadcast/multicast уходили в VPN-туннель (Cloudflare WARP) — 0 устройств.
9. Часть Yeelight не отвечает на multicast — добавлен unicast-sweep.
10. Галочка «Использовать роутер» не сохранялась (кнопка «Сохранить» была
    заблокирована при снятой галочке).
11. NetBIOS-опрос шёл последовательно по 1.5 с на IP (минуты на скан).
12. WifiManager.dhcpInfo/connectionInfo давали 0 → терялся шлюз; теперь
    LinkProperties Wi-Fi сети.

--------------------------------------------------------------------------------
6. СБОРКА И ПРОВЕРКИ (как воспроизвести)
--------------------------------------------------------------------------------

Компиляция:
  gradlew.bat :app:compileDebugKotlin

Юнит-тесты (на этой машине нужен флаг из-за junction C:→E:):
  gradlew.bat :app:testDebugUnitTest --tests "*IdentityTest*" -Pksp.incremental=false
  Ожидаемо: 11 tests, 0 failures.
  Результат: app/build/test-results/testDebugUnitTest/TEST-...IdentityTest.xml

APK:
  gradlew.bat :app:assembleDebug
  Результат: app/build/outputs/apk/debug/app-debug.apk (~187.8 МБ)

Если снова падает Chaquopy/pip («requires a different Python»):
  запайнить новую виноватую зависимость в app/build.gradle.kts;
  временный обход только для Kotlin: -x :app:generateDebugPythonRequirements

--------------------------------------------------------------------------------
7. ПРОВЕРКА НА РЕАЛЬНОЙ СЕТИ (текущий стенд)
--------------------------------------------------------------------------------

Методика: docs/smart-home-network-test-plan.md
Эталон:   python tools/lan_probe.py --json baseline.json

Наблюдаемое 30.10.2026 (сеть 192.168.0.0/24):
  192.168.0.20   WIZ       A8:BB:50:99:0F:79
  192.168.0.84   WIZ       A8:BB:50:DF:B1:71
  192.168.0.82   YEELIGHT  colorb (LAN Control вкл)
  192.168.0.132  YEELIGHT  colorb
  192.168.0.170  YEELIGHT  colorb (не отвечает на multicast, только unicast)
  192.168.0.30 / .46 / .152  Tuya (tcp:6668, без имени — зашифровано)
  192.168.0.1    роутер TP-Link, MAC 14:EB:B6:58:83:E4
  192.168.0.142  Samsung TV (UPnP)
  192.168.0.37   медиа-рендер (UPnP)

Ключевые факты, добытые на стенде:
  - WARP включён: broadcast без bind → 0 ответов, с bind → 2 лампы;
  - Yeelight: multicast 2 из 3, unicast 3 из 3, TCP 3 из 3.

--------------------------------------------------------------------------------
8. ОГРАНИЧЕНИЯ / ЧТО НЕ СДЕЛАНО
--------------------------------------------------------------------------------

  - Tuya: видно только порт 6668; имя и управление — через Tuya Cloud
    (client id/secret + localKey) или локальный ключ. Фаза 3 плана.
  - MiIO: опознание есть, но для управления нужен токен из Mi Cloud.
  - Zigbee-датчики (Aqara/Xiaomi): не IP-устройства, только через API шлюза
    или облако.
  - Яндекс Станции / ИК-пульты: метаданные только через Яндекс API (OAuth).
  - Комнаты/группы/алиасы — не сделаны (сейчас только displayName).
  - Подтверждение широких команд (>3 устройств) — не сделано.
  - Только IPv4 и /24; IPv6 и /16 не покрыты.
  - ICMP-ping не привязан к Wi-Fi (при полном туннеле может молчать) —
    на результат не влияет, TCP-скан находит хосты сам.
  - Android 16 (local network permission) не учтён.

--------------------------------------------------------------------------------
9. ЗАМЕТКИ ДЛЯ OPENCLAW
--------------------------------------------------------------------------------

  - Инвариант: smart_home_devices.mac — это КЛЮЧ ИДЕНТИЧНОСТИ, не обязательно
    настоящий MAC. Не «чинить» его приведением к MAC-формату.
  - Любой новый сокет в discovery/управлении привязывать через LanNetwork.bind,
    иначе при VPN снова «пустая сеть».
  - Broadcast отправлять на broadcastAddress() (x.y.z.255), не только на
    255.255.255.255.
  - Discovery, который «не нашёл устройство», проверять unicast'ом: часть
    устройств не отвечает на multicast.
  - SmartHomeDispatcher.executeOne — единая точка команд (идемпотентность +
    верификация). Если добавляешь протокол, реализуй StatefulController.readState,
    иначе верификация для него просто отключится.
  - *.txt в .gitignore (см. строку «Debug / temp files»), plans/ и memory/
    тоже игнорируются — поэтому этот файл .md.
  - Локальный артефакт baseline.json не коммитится; пересоздаётся командой выше.
