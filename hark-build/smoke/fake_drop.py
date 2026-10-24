#!/usr/bin/env python3
"""[Mines-38.9.10 i29] smoke: a 'proxy' that accepts the TCP connection, reads the request, then drops it (no reply).
A port-only health check calls this 能用; a real request through it must fail. Logs to fake-drop.log.
usage: fake_drop.py <port>"""
import socket, sys, threading, time
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 3129
log = open("fake-drop.log", "a", buffering=1)

def handle(c, a):
    try:
        c.settimeout(5)
        d = c.recv(4096)
        log.write(f"{time.strftime('%H:%M:%S')} DROP {a[0]} {d[:80]!r}\n")
    except Exception as e:
        log.write(f"DROP_ERR {e}\n")
    finally:
        try: c.close()
        except Exception: pass

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("0.0.0.0", PORT)); s.listen(50)
print("fake_drop on", PORT, flush=True)
while True:
    c, a = s.accept()
    threading.Thread(target=handle, args=(c, a), daemon=True).start()
