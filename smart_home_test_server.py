"""
Универсальный тестовый сервер для проверки контроллеров Shelly/Tasmota/WLED.

Запускает три HTTP-сервера на разных портах:
- Порт 28080 → Shelly (Gen2 RPC + Gen1 HTTP)
- Порт 28081 → Tasmota (HTTP /cm)
- Порт 28082 → WLED (HTTP /win + /json)

Не требует прав администратора — все порты > 1024.

Эмулятор Android доступается к localhost ПК через 10.0.2.2.
"""

import json
from http.server import HTTPServer, BaseHTTPRequestHandler
from threading import Thread

# ═══════════════════ Shelly (порт 28080) ═══════════════════

SHELLY_INFO = {
    "name": "Test Shelly Bulb",
    "app": "Shelly",
    "mac": "AABBCCDDEEFF",
    "model": "SHRGBW2"
}

class ShellyHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"  [Shelly:28080] {args[0]} {args[1]} {args[2]}")

    def do_GET(self):
        if '/rpc/Shelly.GetDeviceInfo' in self.path:
            self._json(200, SHELLY_INFO)
        elif '/settings' in self.path:
            self._json(200, {"device": "shelly", "type": "SHRGBW2", "name": "Test"})
        elif '/relay/0' in self.path:
            self._json(200, {"ison": True})
        elif '/light/0' in self.path:
            self._json(200, {"ison": True, "brightness": 50})
        elif '/color/0' in self.path:
            self._json(200, {"ison": True})
        else:
            self._text(200, "Shelly OK")

    def do_POST(self):
        length = int(self.headers['Content-Length'])
        body = self.rfile.read(length).decode()
        print(f"  [Shelly:28080] POST body: {body}")
        self._json(200, {"result": "ok"})

    def _json(self, code, data):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def _text(self, code, text):
        self.send_response(code)
        self.end_headers()
        self.wfile.write(text.encode())


# ═══════════════════ Tasmota (порт 28081) ═══════════════════

TASMOTA_STATUS = {
    "Status": {"Power": 1, "Dimmer": 50},
    "StatusFWR": {"Version": "Tasmota 14.0.0 (test)"}
}

class TasmotaHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"  [Tasmota:28081] {args[0]} {args[1]} {args[2]}")

    def do_GET(self):
        if 'Status' in self.path:
            self._json(200, TASMOTA_STATUS)
        elif 'Power ON' in self.path:
            self._json(200, {"POWER": "ON"})
        elif 'Power OFF' in self.path:
            self._json(200, {"POWER": "OFF"})
        elif 'Dimmer' in self.path:
            self._json(200, {"Dimmer": 50})
        elif 'Color' in self.path:
            self._json(200, {"Color": "FF0000"})
        elif 'CTemp' in self.path:
            self._json(200, {"CTemp": 350})
        else:
            # Любая другая команда Tasmota отвечает {"POWER":"ON"}
            self._json(200, {"POWER": "ON"})

    def _json(self, code, data):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())


# ═══════════════════ WLED (порт 28082) ═══════════════════

WLED_INFO = {
    "name": "WLED Test",
    "brand": "WLED",
    "version": "0.14.0"
}

WLED_STATE = {
    "on": True,
    "bri": 128,
    "seg": [{"id": 0, "r": 255, "g": 0, "b": 0}]
}

class WledHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"  [WLED:28082] {args[0]} {args[1]} {args[2]}")

    def do_GET(self):
        if '/json/info' in self.path:
            self._json(200, WLED_INFO)
        elif '/json/state' in self.path:
            self._json(200, WLED_STATE)
        elif '/win' in self.path:
            print(f"  [WLED:28082] Команда: {self.path}")
            self._json(200, {"success": True})
        else:
            self._text(200, "OK")

    def _json(self, code, data):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def _text(self, code, text):
        self.send_response(code)
        self.end_headers()
        self.wfile.write(text.encode())


# ═══════════════════ Main ═══════════════════

def main():
    print("=" * 60)
    print("[START] Запуск тестовых серверов умного дома")
    print("=" * 60)
    print()
    print("  Shelly  -> http://localhost:28080   (emu: 10.0.2.2:28080)")
    print("  Tasmota -> http://localhost:28081   (emu: 10.0.2.2:28081)")
    print("  WLED    -> http://localhost:28082   (emu: 10.0.2.2:28082)")
    print("")
    print("  Test: say 'vkluchi svet' in agent -> finds Shelly")
    print("=" * 60)
    print("")

    servers = [
        ("Shelly", 28080, ShellyHandler),
        ("Tasmota", 28081, TasmotaHandler),
        ("WLED", 28082, WledHandler),
    ]

    threads = []
    for name, port, handler in servers:
        server = HTTPServer(('0.0.0.0', port), handler)
        t = Thread(target=server.serve_forever, daemon=True, name=name)
        threads.append(t)
        t.start()
        print(f"  [OK] {name} on port {port}")

    print()
    print("  Press Ctrl+C to stop")
    print()

    try:
        for t in threads:
            t.join()
    except KeyboardInterrupt:
        print("\n  [STOP] Shutting down...")


if __name__ == '__main__':
    main()
