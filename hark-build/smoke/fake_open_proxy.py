#!/usr/bin/env python3
"""[38.9.11.1] smoke: an open HTTP proxy (no auth) — CONNECT tunnels + absolute-form GET — standing in for the user's Clash app
on the phone's 127.0.0.1:7890 (adb reverse tcp:7890 → this). Logs one line per request.  usage: fake_open_proxy.py <port>"""
import socket, sys, threading
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 3130
log = open("fake-open-proxy.log", "a", buffering=1)
def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d: break
            b.sendall(d)
    except Exception: pass
    finally:
        for s in (a, b):
            try: s.shutdown(socket.SHUT_RDWR)
            except Exception: pass
def handle(c):
    try:
        head = b""
        while b"\r\n\r\n" not in head:
            d = c.recv(4096)
            if not d: return
            head += d
        h, rest = head.split(b"\r\n\r\n", 1)
        lines = h.decode("latin-1").split("\r\n")
        method, target, ver = lines[0].split(" ", 2)
        log.write(f"{method} {target}\n")
        if method == "CONNECT":
            host, port = target.rsplit(":", 1)
            u = socket.create_connection((host, int(port)), timeout=10)
            c.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
            if rest: u.sendall(rest)
        else:
            from urllib.parse import urlsplit
            p = urlsplit(target); host = p.hostname; port = p.port or 80
            u = socket.create_connection((host, port), timeout=10)
            path = (p.path or "/") + (("?" + p.query) if p.query else "")
            hdrs = [l for l in lines[1:] if not l.lower().startswith(("proxy-", "connection:"))]
            u.sendall((f"{method} {path} {ver}\r\n" + "\r\n".join(hdrs) + "\r\nConnection: close\r\n\r\n").encode("latin-1") + rest)
        threading.Thread(target=pipe, args=(u, c), daemon=True).start()
        pipe(c, u)
    except Exception as e:
        log.write(f"ERR {e}\n")
        try: c.close()
        except Exception: pass
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("127.0.0.1", PORT)); s.listen(64)
while True:
    c, _ = s.accept(); threading.Thread(target=handle, args=(c,), daemon=True).start()
