"""
kasa_control.py — управление устройствами TP-Link Kasa/Tapo через python-kasa.

Использует библиотеку python-kasa для локального управления.
Требует установки: pip install python-kasa

Поддерживает:
- Kasa (старые): KL130, HS100, HS110 и др. — шифрованный TCP порт 9999
- Tapo (новые): L530, L630, P100 и др. — KLAP (AES-CBC-SHA256) через HTTP
"""

import json
import sys
import asyncio

try:
    from kasa import Device, Discover
except ImportError:
    print(json.dumps({'status': 'error', 'error': 'python-kasa не установлена. Установи: pip install python-kasa'}))
    sys.exit(1)


async def _control_async(ip, username, password, action, params):
    """Асинхронная версия управления устройством."""
    try:
        device = Device(ip)
        if username and password:
            device.set_credentials(username, password)

        await device.update()

        if action in ('turn_on', 'start'):
            await device.turn_on()
            return {'status': 'ok', 'result': 'Включено'}
        elif action in ('turn_off', 'stop'):
            await device.turn_off()
            return {'status': 'ok', 'result': 'Выключено'}
        elif action == 'set_brightness':
            level = int(params.get('level', 50))
            await device.set_brightness(level)
            return {'status': 'ok', 'result': f'Яркость: {level}%'}
        elif action == 'set_color_temp':
            temp = int(params.get('temp', 3500))
            await device.set_color_temp(temp)
            return {'status': 'ok', 'result': f'Температура: {temp}K'}
        elif action == 'set_rgb':
            r = int(params.get('r', 255))
            g = int(params.get('g', 255))
            b = int(params.get('b', 255))
            # Конвертируем RGB → HSV
            h, s, v = _rgb_to_hsv(r, g, b)
            await device.set_hsv(int(h), int(s), int(v))
            msg = f'Цвет: RGB({r},{g},{b})'
            return {'status': 'ok', 'result': msg}
        elif action == 'status':
            return {
                'status': 'ok',
                'result': {
                    'on': device.is_on,
                    'brightness': getattr(device, 'brightness', None),
                    'color_temp': getattr(device, 'color_temp', None),
                    'model': getattr(device, 'model', None),
                    'mac': getattr(device, 'mac', None)
                }
            }
        elif action == 'ping':
            await device.update()
            return {'status': 'ok', 'result': 'ping_ok'}
        elif action == 'set_hsv':
            h = int(params.get('h', 0))
            s = int(params.get('s', 100))
            v = int(params.get('v', 100))
            await device.set_hsv(h, s, v)
            return {'status': 'ok', 'result': f'HSV: {h},{s},{v}'}
        else:
            return {'status': 'error', 'error': f'Неизвестная команда: {action}'}

    except Exception as e:
        return {'status': 'error', 'error': str(e)}


async def _discover_async(timeout=3):
    """Асинхронный discovery устройств TP-Link в сети."""
    try:
        devices = await Discover.discover(timeout=timeout)
        result = []
        for ip, dev in devices.items():
            await dev.update()
            result.append({
                'ip': ip,
                'mac': getattr(dev, 'mac', ''),
                'model': getattr(dev, 'model', ''),
                'name': getattr(dev, 'alias', ''),
                'is_on': getattr(dev, 'is_on', False)
            })
        return {'status': 'ok', 'result': result}
    except Exception as e:
        return {'status': 'error', 'error': str(e)}


def control(ip, username, password, action, params='{}'):
    """
    Выполнить команду управления на устройстве TP-Link Kasa/Tapo.

    Args:
        ip (str): IP адрес устройства
        username (str): Логин TP-Link (для Tapo) или пустая строка для Kasa
        password (str): Пароль TP-Link (для Tapo) или пустая строка для Kasa
        action (str): Действие (turn_on, turn_off, set_brightness, set_rgb, status, ping, ...)
        params (str): JSON-строка с параметрами {"level": 50, "r": 255, ...}

    Returns:
        str: JSON-строка с результатом
    """
    try:
        p = json.loads(params) if params else {}
        result = asyncio.run(_control_async(ip, username, password, action, p))
        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        return json.dumps({'status': 'error', 'error': str(e)}, ensure_ascii=False)


def discover(username='', password='', timeout=3):
    """
    Найти все TP-Link Kasa/Tapo устройства в сети.

    Args:
        username (str): Логин TP-Link (опционально, для Tapo)
        password (str): Пароль TP-Link (опционально, для Tapo)
        timeout (int): Таймаут discovery в секундах

    Returns:
        str: JSON-строка со списком устройств
    """
    try:
        result = asyncio.run(_discover_async(timeout))
        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        return json.dumps({'status': 'error', 'error': str(e)}, ensure_ascii=False)


def _rgb_to_hsv(r, g, b):
    """Конвертировать RGB → HSV (0-360, 0-100, 0-100)."""
    r, g, b = r / 255.0, g / 255.0, b / 255.0
    mx = max(r, g, b)
    mn = min(r, g, b)
    df = mx - mn

    if mx == mn:
        h = 0
    elif mx == r:
        h = (60 * ((g - b) / df) + 360) % 360
    elif mx == g:
        h = (60 * ((b - r) / df) + 120) % 360
    else:
        h = (60 * ((r - g) / df) + 240) % 360

    s = 0 if mx == 0 else (df / mx) * 100
    v = mx * 100

    return (round(h), round(s), round(v))
