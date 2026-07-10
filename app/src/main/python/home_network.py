"""
home_network.py — сканирование TP-Link роутера через tplinkrouterc6u.

Адаптировано из net_scan.py (рабочий скрипт).
Функция scan() — точка входа для Chaquopy.
"""

import json
import requests
import urllib3


def scan(router_ip, password, username=None, protocol="http"):
    """
    Сканирует домашнюю сеть через роутер TP-Link.
    
    Args:
        router_ip: IP роутера (например "192.168.0.1")
        password: пароль администратора
        username: не используется (для AX73)
        protocol: "http" или "https"
    
    Returns:
        dict с ключами: status, devices, error
    """
    urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
    original_request = requests.Session.request
    devices_list = []

    try:
        from tplinkrouterc6u import TplinkRouterProvider

        # Monkey-patch SSL для tplinkrouterc6u
        def patched_request(self, method, url, *args, **kwargs):
            kwargs['verify'] = False
            return original_request(self, method, url, *args, **kwargs)
        requests.Session.request = patched_request

        # Авторизация
        host = router_ip if router_ip.startswith("http") else f"{protocol}://{router_ip}"
        router = TplinkRouterProvider.get_client(host, password)
        router.authorize()

        # DHCP leases
        try:
            leases = router.get_ipv4_dhcp_leases()
            for lease in leases:
                devices_list.append({
                    "ip": lease.ipaddr,
                    "mac": lease.macaddr.upper() if lease.macaddr else "?",
                    "name": lease.hostname or "?"
                })
        except Exception:
            # Fallback: статус устройства
            try:
                status = router.get_status()
                for device in getattr(status, 'devices', []):
                    ip = device.ipaddr if hasattr(device, 'ipaddr') else "?"
                    mac = device.macaddr.upper() if hasattr(device, 'macaddr') and device.macaddr else "?"
                    name = device.hostname if hasattr(device, 'hostname') and device.hostname else "?"
                    if ip and ip != "?":
                        devices_list.append({"ip": ip, "mac": mac, "name": name})
            except Exception as e2:
                print(f"home_network: status.devices also failed: {e2}")

        # WiFi-клиенты (дополняем)
        try:
            clients = router.get_wireless_clients()
            existing_macs = {d['mac'] for d in devices_list}
            for client in clients:
                ip = client.ipaddr if hasattr(client, 'ipaddr') else "?"
                mac = client.macaddr.upper() if hasattr(client, 'macaddr') and client.macaddr else "?"
                name = client.hostname if hasattr(client, 'hostname') and client.hostname else "?"
                if mac not in existing_macs and ip != "?" and mac != "?":
                    devices_list.append({"ip": ip, "mac": mac, "name": name or "?"})
        except Exception:
            pass

        router.logout()

        # Сортировка по IP
        devices_list.sort(key=lambda x: list(map(int, x['ip'].split('.'))))

        return {
            "status": "ok",
            "devices": devices_list,
            "error": None
        }

    except ImportError as e:
        return {
            "status": "error",
            "devices": [],
            "error": f"tplinkrouterc6u not installed: {e}"
        }
    except Exception as e:
        return {
            "status": "error",
            "devices": [],
            "error": f"Router scan failed: {str(e)}"
        }
    finally:
        # Восстанавливаем оригинальный request
        requests.Session.request = original_request
