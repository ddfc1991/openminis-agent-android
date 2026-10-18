#!/usr/bin/env python3
"""[Mines-38.9.6 i13] smoke: an HTTP proxy that REQUIRES Basic auth (smoke / proxy396), like the user's HK proxy.
CONNECT tunnels and absolute-form requests; 407 without the right Proxy-Authorization. Logs one line per request to
fake-proxy.log: AUTH_OK|AUTH_BAD <method> <target>.  usage: fake_proxy.py <port>"""
import base64, socket, sys, threading
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 3128
WANT = "Basic " + base64.b64encode(b"smoke:proxy396").decode()
log = open("fake-proxy.log", "a", buffering=1)

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
        method, target = lines[0].split(" ")[0], lines[0].split(" ")[1]
        auth = next((l.split(":", 1)[1].strip() for l in lines[1:] if l.lower().startswith("proxy-authorization:")), "")
        ok = auth == WANT
        log.write(("AUTH_OK" if ok else "AUTH_BAD") + f" {method} {target}\n")
        if not ok:
            c.sendall(b'HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm="smoke"\r\nContent-Length: 0\r\n\r\n'); return
        if method == "CONNECT":
            host, port = target.rsplit(":", 1)
            u = socket.create_connection((host, int(port)), 15)
            c.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            if rest: u.sendall(rest)
        else:
            from urllib.parse import urlsplit
            p = urlsplit(target); host = p.hostname; port = p.port or 80
            u = socket.create_connection((host, port), 15)
            path = (p.path or "/") + (("?" + p.query) if p.query else "")
            out = [f"{method} {path} HTTP/1.1"] + [l for l in lines[1:] if not l.lower().startswith("proxy-")]
            u.sendall(("\r\n".join(out) + "\r\n\r\n").encode("latin-1") + rest)
        threading.Thread(target=pipe, args=(u, c), daemon=True).start()
        pipe(c, u)
    except Exception as e:
        log.write(f"ERR {e}\n")
    finally:
        try: c.close()
        except Exception: pass

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("0.0.0.0", PORT)); s.listen(64)
while True:
    c, _ = s.accept(); threading.Thread(target=handle, args=(c,), daemon=True).start()
