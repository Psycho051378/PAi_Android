"""
miio_control.py — управление устройствами Xiaomi MiIO через UDP напрямую.

Чистая реализация MiIO протокола без полного пакета python-miio.
Использует pycryptodome (Crypto.Cipher.AES) для шифрования.
ВАЖНО: Заголовки пакетов MiIO ВСЕГДА используют Big-Endian (>).
"""

import json
import socket
import hashlib
import struct
import sys

try:
    from Crypto.Cipher import AES
except ImportError:
    print("[-] Ошибка: Библиотека pycryptodome не установлена.")
    sys.exit(1)


def control(ip, token, command, params='{}'):
    """
    Выполнить команду управления на устройстве MiIO.
    """
    try:
        p = json.loads(params) if params and params != '{}' else {}

        # Специфика роботов-пылесосов: вместо 'set_power' используем 'app_start'/'app_stop'
        if command in ('turn_on', 'power_on', 'start'):
            return _miio_send(ip, token, 'app_start', [])
        elif command in ('turn_off', 'power_off', 'stop'):
            return _miio_send(ip, token, 'app_stop', [])
        elif command == 'charge':
            return _miio_send(ip, token, 'app_charge', [])
        elif command == 'status':
            return _miio_send(ip, token, 'get_prop', ['state', 'battery', 'clean_time', 'fan_power', 'area', 'main_brush_life'])
        elif command == 'custom':
            return _miio_send(ip, token, p.get('method', 'get_prop'), p.get('args', []))
        else:
            return _json_error(f'Неизвестная команда: {command}')
    except Exception as e:
        return _json_error(str(e))


def _miio_discover(ip, token, timeout=3):
    """
    Отправляет классический незашифрованный Hello-пакет MiIO (handshake).
    Возвращает (device_id, stamp) или None.
    """
    PORT = 54321
    packet = b'\x21\x31\x00\x20' + b'\xff' * 28

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        sock.sendto(packet, (ip, PORT))
        resp_data, addr = sock.recvfrom(1024)
    except socket.timeout:
        return None
    finally:
        sock.close()

    if len(resp_data) < 32:
        return None

    # Читаем Device ID (байты 8-12) и Stamp (байты 12-16) в формате Big-Endian
    resp_device_id = int.from_bytes(resp_data[8:12], byteorder='big')
    stamp = int.from_bytes(resp_data[12:16], byteorder='big')

    return (resp_device_id, stamp)


def _miio_send(ip, token_hex, method, params):
    """
    Отправляет зашифрованную MiIO команду через UDP сокет локально (Big-Endian).
    """
    PORT = 54321
    TIMEOUT = 4

    try:
        token = bytes.fromhex(token_hex)
        key = hashlib.md5(token).digest()
        iv = hashlib.md5(key + token).digest()

        # Шаг 1: Discovery для получения актуального device_id и stamp
        discovered = _miio_discover(ip, token, timeout=2)
        if discovered is None:
            return _json_error(f'Discovery timeout: {ip}:{PORT} не отвечает')

        device_id, stamp = discovered
        stamp += 1  # Увеличиваем stamp для каждого нового запроса

        # Шаг 2: Подготовка JSON команды
        payload_json = json.dumps({
            'id': 1,
            'method': method,
            'params': params
        }, separators=(',', ':'))

        payload_bytes = payload_json.encode()

        # PKCS7 выравнивание (padding)
        pad_len = 16 - (len(payload_bytes) % 16)
        payload_padded = payload_bytes + bytes([pad_len]) * pad_len

        # Шифрование AES-CBC
        cipher = AES.new(key, AES.MODE_CBC, iv)
        encrypted = cipher.encrypt(payload_padded)

        total_len = 32 + len(encrypted)

        # Сборка заголовка строго в формате Big-Endian (>)
        # Magic: 0x2131 (2 байта), Length (2 байта), Unknown: 0x0000 (4 байта),
        # Device ID (4 байта), Stamp (4 байта)
        hdr = struct.pack('>HHII I', 0x2131, total_len, 0x0000, device_id, stamp)
        hdr += b'\x00' * 16  # временное место под MD5 контрольную сумму

        # Контрольная сумма = MD5(Header_с_токеном_вместо_нулей + Encrypted_Payload)
        # Для расчета MD5 протокол требует временно подставить токен в заголовок
        # на место чексумы
        full_for_checksum = hdr[:16] + token + encrypted
        checksum = hashlib.md5(full_for_checksum).digest()

        # Финальный пакет: первые 16 байт заголовка + 16 байт чексумы + зашифрованные данные
        packet = hdr[:16] + checksum + encrypted

        # Шаг 3: Передача пакета по UDP
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(TIMEOUT)
        sock.sendto(packet, (ip, PORT))

        try:
            resp_data, addr = sock.recvfrom(4096)
        except socket.timeout:
            return _json_error(f'Таймаут {TIMEOUT}с: устройство не ответило (токен не подошёл или заголовок неверен)')
        finally:
            sock.close()

        if len(resp_data) < 32:
            return _json_ok('Команда отправлена (пустой ответ)')

        # Расшифровка ответа устройства
        enc_resp = resp_data[32:]
        if not enc_resp:
            return _json_ok('Команда отправлена (нет зашифрованного тела ответа)')

        # Обрезаем кратно 16 байтам для AES
        enc_resp = enc_resp[:len(enc_resp) - (len(enc_resp) % 16)]

        cipher2 = AES.new(key, AES.MODE_CBC, iv)
        decrypted = cipher2.decrypt(enc_resp)

        # Удаление PKCS7 паддинга
        if decrypted:
            pad_val = decrypted[-1]
            if 0 < pad_val <= 16:
                decrypted = decrypted[:-pad_val]

        try:
            resp_json = json.loads(decrypted.decode('utf-8'))
            if 'result' in resp_json:
                return _json_ok(resp_json['result'])
            elif 'error' in resp_json:
                return _json_error(f'Ошибка устройства: {resp_json["error"]}')
            else:
                return _json_ok(resp_json)
        except:
            return _json_ok(f'Ответ получен: {decrypted.decode("utf-8", errors="ignore")}')

    except Exception as e:
        return _json_error(str(e))


def _json_ok(result):
    return json.dumps({'status': 'ok', 'result': result}, ensure_ascii=False)


def _json_error(error):
    return json.dumps({'status': 'error', 'error': error}, ensure_ascii=False)
