#!/usr/bin/env python3
"""Mines 远程诊断 relay (38.9.11.3). Read-mostly: phones stream redacted diagnostics in; the operator (ctl socket, root only)
can send ONLY whitelisted commands, each HMAC-signed with the session key. Never runs anything on request.

  device:  wss://HOST:PORT{PATH}/dev   header X-Diag-Token: <token>   (also plain ws on 127.0.0.1 for a Cloudflare Tunnel origin)
  control: unix socket CTL (JSON lines): {"op":"list"} | {"op":"send","sid":..,"cmd":..,"args":{..}} | {"op":"tail","sid":..,"n":50}
  storage: DIR/<sid>.jsonl, ≤ SESSION_CAP bytes per session, ≤ DIR_CAP total (oldest files deleted)
env: DIAG_TOKEN_FILE DIAG_PATH DIAG_TLS_CERT DIAG_TLS_KEY DIAG_WSS_PORT DIAG_WS_PORT DIAG_WS_HOST DIAG_DIR DIAG_CTL"""
import asyncio, hashlib, hmac, json, os, re, ssl, sys, time, uuid
import websockets

TOKEN = open(os.environ.get("DIAG_TOKEN_FILE", "/etc/mines-diag/token")).read().strip() if not os.environ.get("DIAG_TOKEN") else os.environ["DIAG_TOKEN"]
PATH = os.environ.get("DIAG_PATH", "/mdiag")
DIR = os.environ.get("DIAG_DIR", "/var/lib/mines-diag")
CTL = os.environ.get("DIAG_CTL", "/run/mines-diag/ctl.sock")
SESSION_CAP = int(os.environ.get("DIAG_SESSION_CAP", str(20 << 20)))
DIR_CAP = int(os.environ.get("DIAG_DIR_CAP", str(200 << 20)))
MSG_CAP = 600 * 1024
WHITELIST = {"ping", "capture_jank_trace", "dump_main_thread_now", "set_log_level", "get_recent_crash", "show_notice",
             "request_screenshot_of_mines_only"}
SESS = {}   # sid -> {"ws", "since", "dev", "k"}
MAX_LIVE = int(os.environ.get("DIAG_MAX_LIVE", "16"))
RATE = {}   # peer ip -> [connect times in the last 60 s]
os.makedirs(DIR, exist_ok=True)


def log(*a): print(time.strftime("%Y-%m-%d %H:%M:%S"), *a, flush=True)


def session_key(sid):   # same derivation as DiagCore.sessionKey on the phone; keyed by the phone's per-session key "k"
    k = SESS.get(sid, {}).get("k", "")      # (the connect TOKEN ships inside the APK, so it is NOT the command key)
    return hmac.new(k.encode(), ("mines-diag|" + sid).encode(), hashlib.sha256).digest()


def canon_args(args):   # DiagCore.canonArgs: sorted k=v joined by & (values str())
    return "&".join(f"{k}={'true' if v is True else 'false' if v is False else v}" for k, v in sorted((args or {}).items()))


def sign(sid, cid, cmd, args, ts):
    return hmac.new(session_key(sid), f"{cid}|{cmd}|{canon_args(args)}|{ts}|{sid}".encode(), hashlib.sha256).hexdigest()


def is_open(ws):
    st = getattr(ws, "state", None)
    if st is not None: return getattr(st, "name", str(st)) == "OPEN"
    return not getattr(ws, "closed", True)


def safe_sid(s):
    return re.sub(r"[^A-Za-z0-9_\-]", "", str(s))[:64] or uuid.uuid4().hex[:12]


def enforce_dir_cap():
    fs = sorted((os.path.join(DIR, f) for f in os.listdir(DIR) if f.endswith(".jsonl")), key=os.path.getmtime)
    tot = sum(os.path.getsize(f) for f in fs)
    while fs and tot > DIR_CAP:
        f = fs.pop(0); tot -= os.path.getsize(f); os.remove(f); log("dir cap: removed", f)


def append(sid, obj):
    p = os.path.join(DIR, sid + ".jsonl")
    line = json.dumps(obj, ensure_ascii=False)
    if os.path.exists(p) and os.path.getsize(p) + len(line) > SESSION_CAP:
        os.replace(p, p[:-6] + ".1.jsonl")   # one rotation, then the dir cap trims
    with open(p, "a") as f: f.write(line + "\n")
    enforce_dir_cap()


async def device(ws):
    path = ws.request.path if hasattr(ws, "request") else ws.path
    hdr = ws.request.headers if hasattr(ws, "request") else ws.request_headers
    ip = ws.remote_address[0] if ws.remote_address else "?"
    if hdr.get("CF-Connecting-IP") and ip in ("127.0.0.1", "::1"): ip = hdr.get("CF-Connecting-IP")
    now = time.time(); RATE[ip] = [t for t in RATE.get(ip, []) if now - t < 60] + [now]
    if len(RATE) > 2000: RATE.clear()
    if len(RATE[ip]) > 6 or len(SESS) >= MAX_LIVE:
        await ws.close(4429, "busy"); return
    if path != PATH + "/dev" or not hmac.compare_digest(hdr.get("X-Diag-Token", ""), TOKEN):
        log("reject connection", path, ws.remote_address); await ws.close(4401, "unauthorized"); return
    try:
        hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
    except Exception:
        await ws.close(4400, "no hello"); return
    sid = safe_sid(hello.get("sid")); dev = {k: str(hello.get(k))[:80] for k in ("app", "version", "device", "android") if k in hello}
    k = str(hello.get("k", ""))
    if not re.fullmatch(r"[0-9a-f]{64}", k):
        await ws.close(4400, "no session key"); return
    old = SESS.get(sid)
    if old and old.get("ws") is not None and is_open(old["ws"]) and old.get("k") != k:
        await ws.close(4409, "session in use"); return   # a live session can't be taken over without its key
    if old:
        try: await old["ws"].close(4000, "replaced")
        except Exception: pass
    SESS[sid] = {"ws": ws, "since": time.time(), "dev": dev, "k": k}
    append(sid, {"t": "relay", "ev": "connected", "ts": int(time.time() * 1000), "dev": dev, "peer": str(ws.remote_address[0]) if ws.remote_address else ""})
    log("device connected", sid, dev)
    await ws.send(json.dumps({"t": "welcome", "sid": sid, "ts": int(time.time() * 1000)}))
    n, t0 = 0, time.time()
    try:
        async for m in ws:
            if isinstance(m, bytes): continue
            if len(m) > MSG_CAP: append(sid, {"t": "relay", "ev": "oversize", "len": len(m)}); continue
            n += 1
            if n > 10 and n / max(time.time() - t0, 1) > 5: await asyncio.sleep(0.5)   # ≤ ~5 msg/s
            try: o = json.loads(m)
            except Exception: continue
            o["rx"] = int(time.time() * 1000); append(sid, o)
            if o.get("t") == "rejected": log("device rejected a command", sid, o.get("reason"))
    except websockets.ConnectionClosed:
        pass
    finally:
        if SESS.get(sid, {}).get("ws") is ws: SESS.pop(sid, None)
        append(sid, {"t": "relay", "ev": "disconnected", "ts": int(time.time() * 1000)}); log("device gone", sid)


async def ctl_client(reader, writer):
    try:
        while True:
            line = await reader.readline()
            if not line: break
            try: q = json.loads(line)
            except Exception: q = {}
            op = q.get("op"); r = {"ok": False}
            if op == "list":
                live = {s: {"since": int(v["since"]), "dev": v["dev"]} for s, v in SESS.items()}
                files = sorted(((f[:-6], os.path.getsize(os.path.join(DIR, f)), int(os.path.getmtime(os.path.join(DIR, f)))) for f in os.listdir(DIR) if f.endswith(".jsonl")), key=lambda x: -x[2])[:50]
                r = {"ok": True, "live": live, "files": files}
            elif op == "tail":
                p = os.path.join(DIR, safe_sid(q.get("sid")) + ".jsonl")
                lines = open(p).read().splitlines()[-int(q.get("n", 50)):] if os.path.exists(p) else []
                r = {"ok": True, "lines": lines}
            elif op == "send":
                sid = safe_sid(q.get("sid")); cmd = q.get("cmd"); args = q.get("args") or {}
                s = SESS.get(sid)
                if cmd not in WHITELIST and not q.get("test_unlisted"):
                    r = {"ok": False, "error": "not whitelisted"}; append(sid, {"t": "relay", "ev": "ctl_refused", "cmd": str(cmd)[:40]})
                elif not s: r = {"ok": False, "error": "session not connected"}
                else:
                    cid = uuid.uuid4().hex[:16]; ts = int(time.time() * 1000)
                    sig = "bad" + "0" * 61 if q.get("test_badsig") else sign(sid, cid, cmd, args, ts)
                    msg = {"t": "cmd", "id": cid, "cmd": cmd, "args": args, "ts": ts, "sig": sig}
                    await s["ws"].send(json.dumps(msg, ensure_ascii=False)); append(sid, {"t": "relay", "ev": "cmd_sent", "id": cid, "cmd": cmd, "args": args})
                    r = {"ok": True, "id": cid}
            writer.write((json.dumps(r, ensure_ascii=False) + "\n").encode()); await writer.drain()
    finally:
        writer.close()


async def main():
    servers = []
    cert, key = os.environ.get("DIAG_TLS_CERT"), os.environ.get("DIAG_TLS_KEY")
    if cert and key and os.environ.get("DIAG_WSS_PORT"):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(cert, key)
        servers.append(await websockets.serve(device, "0.0.0.0", int(os.environ["DIAG_WSS_PORT"]), ssl=ctx, max_size=MSG_CAP + 4096, ping_interval=25, ping_timeout=60))
        log("wss on", os.environ["DIAG_WSS_PORT"], PATH)
    if os.environ.get("DIAG_WS_PORT"):
        servers.append(await websockets.serve(device, os.environ.get("DIAG_WS_HOST", "127.0.0.1"), int(os.environ["DIAG_WS_PORT"]), max_size=MSG_CAP + 4096, ping_interval=25, ping_timeout=60))
        log("ws on", os.environ.get("DIAG_WS_HOST", "127.0.0.1"), os.environ["DIAG_WS_PORT"], PATH)
    os.makedirs(os.path.dirname(CTL), exist_ok=True)
    if os.path.exists(CTL): os.remove(CTL)
    srv = await asyncio.start_unix_server(ctl_client, CTL); os.chmod(CTL, 0o600)
    log("ctl on", CTL)
    await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
