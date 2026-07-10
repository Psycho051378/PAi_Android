"""
miio_get_tokens.py — получение device token'ов из Mi Cloud.

Важно: патчим pytz.timezone и глушим tzlocal на уровне модуля,
ДО импорта micloud.
"""

import json
import datetime as _dt
import pytz
import sys


# =============================================================================
# PATCH: Полная изоляция от таймзон Android.
# tzlocal (зависимость micloud) лезет в системные файлы Android и падает.
# =============================================================================
class _GmtTz(_dt.tzinfo):
    _utcoffset = _dt.timedelta(0)
    _tzname = 'GMT'
    zone = 'GMT'
    def utcoffset(self, dt): return _dt.timedelta(0)
    def dst(self, dt): return _dt.timedelta(0)
    def tzname(self, dt): return 'GMT'

_gmt = _GmtTz()

# 1. Патчим pytz
pytz.timezone = lambda name: _gmt
if hasattr(pytz, '_tzinfo_cache'):
    pytz._tzinfo_cache['GMT'] = _gmt

# 2. Глушим tzlocal — micloud подтягивает её под капотом,
#    она лезет в /system/usr/share/zoneinfo Android и крашится.
class _FakeTzLocal:
    """Заглушка для tzlocal, всегда возвращает GMT."""
    def get_localzone(self):
        return _gmt
    def get_localzone_name(self):
        return 'GMT'

sys.modules['tzlocal'] = _FakeTzLocal()


# =============================================================================
# Основная функция
# =============================================================================
def get_tokens(username, password):
    """
    Авторизуется в Mi Cloud и получает список устройств с токенами.

    Returns:
        JSON-строка: {"status": "ok"/"error", "devices": [...], "error": "..."}
    """
    try:
        from micloud import MiCloud

        mc = MiCloud(username, password)
        if not mc.login():
            return json.dumps({
                "status": "error",
                "error": "Не удалось авторизоваться в Mi Cloud. Проверь логин/пароль.",
                "devices": []
            })

        raw_devices = mc.get_devices()
        devices = []

        for d in raw_devices:
            devices.append({
                "mac": d.get("mac", "").upper(),
                "did": d.get("did", ""),
                "name": d.get("name", ""),
                "model": d.get("model", ""),
                "localip": d.get("localip", ""),
                "token": d.get("token", ""),
                "ssid": d.get("ssid", "")
            })

        return json.dumps({
            "status": "ok",
            "devices": devices,
            "count": len(devices)
        })

    except ImportError as e:
        return json.dumps({
            "status": "error",
            "error": f"Библиотека micloud не установлена: {e}",
            "devices": []
        })
    except Exception as e:
        return json.dumps({
            "status": "error",
            "error": str(e),
            "devices": []
        })


# Для совместимости
if __name__ == '__main__':
    import sys
    if len(sys.argv) >= 3:
        print(get_tokens(sys.argv[1], sys.argv[2]))
    else:
        print(json.dumps({"status": "error", "error": "Usage: get_tokens(username, password)"}))
