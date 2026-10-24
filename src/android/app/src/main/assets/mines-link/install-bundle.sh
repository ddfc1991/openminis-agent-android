#!/bin/sh
# Install the Mines link worker (systemd). Run as root on the server:
#   sh install-bundle.sh [--port 8443] [--ssh-mux]        (self-contained; Mines → 服务器 → 一键安装 does this over SSH)
#   or, with mines-link.py next to it:  sh install.sh [--port 8443] [--ssh-mux]
# Re-running keeps the existing token and certificate (the phone stays paired).
# Prints a one-line 连接码 for Mines (also saved in /etc/mines-link/phone.txt,
# which Mines can read by itself over SSH).
set -e
PORT=8443; MUX=""
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --ssh-mux) MUX="--ssh-mux"; shift ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
done
[ "$(id -u)" = 0 ] || { echo "请用 root 运行"; exit 1; }
command -v python3 >/dev/null || { (apt-get update -qq && apt-get install -y -qq python3) || yum install -y -q python3 || dnf install -y -q python3; }
command -v openssl >/dev/null || { (apt-get install -y -qq openssl) || yum install -y -q openssl || dnf install -y -q openssl; }
# [Mines-38.9.6 i17] agents search with rg (a job printed 'rg: not found'): install ripgrep, else a grep stand-in
command -v rg >/dev/null || { (apt-get install -y -qq ripgrep) || yum install -y -q ripgrep || dnf install -y -q ripgrep || apk add --no-cache ripgrep; } >/dev/null 2>&1 || true
command -v rg >/dev/null || { printf '%s\n' '#!/bin/sh' '# Mines: ripgrep is not installable here; plain grep stand-in (rg PATTERN [PATH...])' \
  '[ $# -le 1 ] && set -- "$@" .' 'exec grep -rnE --color=never --exclude-dir=.git --exclude-dir=node_modules "$@"' > /usr/local/bin/rg; chmod +x /usr/local/bin/rg; }
SRC="${MINES_LINK_SRC:-https://cdn.jsdelivr.net/gh/ddfc1991/openminis-agent-android@hark-minis-build/hark-build/vps-worker/mines-link.py}"
mkdir -p /etc/mines-link /opt/mines-link
if true; then cat > /opt/mines-link/mines-link.py <<'__MINES_EOF__'
#!/usr/bin/env python3
# Mines link worker — the phone's outbound, HTTPS-looking channel to this server.
#
# The phone (Mines app) opens ONE wss:// connection to this worker. Over it it
# runs short commands and long jobs. Jobs run detached from the connection
# (their own session/process group, output appended to a file), so a dropped
# phone never kills them; on reconnect the phone resumes each job's output
# from the exact byte offset it had. The brain stays on the phone: this worker
# only executes, it never calls a model.
#
# Python 3.8+ stdlib only. TLS with a self-signed cert the phone pins by
# SHA-256; a random token authenticates the phone.
#
# Optional --ssh-mux: the same port also carries SSH (connections that start
# with "SSH-" or send nothing for 1.5 s are spliced to 127.0.0.1:22), so one
# port 443 serves both the link and plain ssh.
#
# Protocol (JSON text frames):
#  phone → {"t":"hello","token":…}                     ← {"t":"hello","ok":true,"host":…,"v":1}
#          {"t":"ping","n":…}                          ← {"t":"pong","n":…}
#          {"t":"exec","rid":…,"cmd":…,"timeout":s}    ← {"t":"res","rid":…,"code":n,"out":…}
#          {"t":"run","id":…,"cmd":…}                  ← {"t":"started","id":…,"pid":n}   (idempotent per id)
#          {"t":"sub","id":…,"since":off}              ← {"t":"ev","id":…,"off":o,"b":base64} … {"t":"exit","id":…,"code":n,"off":o}
#          {"t":"stop","id":…}                         ← {"t":"stopped","id":…}
#          {"t":"jobs"}                                ← {"t":"jobs","list":[{"id","state","code","size","started"}]}
#          {"t":"stat","rid":…,"path":…}               ← {"t":"res","rid":…,"size":n,"sha256":…}
#          {"t":"put","rid":…,"path":…,"off":o,"b":…}  ← {"t":"res","rid":…,"size":n}   (resumable, chunked upload)
#          {"t":"get","rid":…,"path":…,"off":o,"len":n}← {"t":"res","rid":…,"b":…,"size":total}
# SPDX-License-Identifier: GPL-3.0-or-later  (part of Mines, a continuation of OpenMinis)
import argparse, asyncio, base64, hashlib, hmac, json, os, signal, socket, ssl, struct, sys, time

VERSION = 1
INTERNAL_TLS_PORT = 18443
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
HOME = os.path.expanduser("~")
JOBS = os.path.join(HOME, ".mines-link", "jobs")


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ---------------------------------------------------------------- websocket
class WS:
    def __init__(self, r, w):
        self.r, self.w = r, w
        self.lock = asyncio.Lock()
        self.closed = False

    async def recv(self):
        buf = b""
        opcode0 = None
        while True:
            h = await self.r.readexactly(2)
            fin, op = h[0] & 0x80, h[0] & 0x0F
            masked, ln = h[1] & 0x80, h[1] & 0x7F
            if ln == 126:
                ln = struct.unpack(">H", await self.r.readexactly(2))[0]
            elif ln == 127:
                ln = struct.unpack(">Q", await self.r.readexactly(8))[0]
            if ln > 64 * 1024 * 1024:
                raise ConnectionError("frame too large")
            mask = await self.r.readexactly(4) if masked else b"\0\0\0\0"
            data = bytearray(await self.r.readexactly(ln))
            for i in range(ln):
                data[i] ^= mask[i % 4]
            if op == 8:
                raise ConnectionError("closed by peer")
            if op == 9:
                await self.send_raw(10, bytes(data)); continue
            if op == 10:
                continue
            if op != 0:
                opcode0 = op
            buf += bytes(data)
            if fin:
                return buf.decode("utf-8", "replace") if opcode0 == 1 else buf

    async def send_raw(self, op, payload):
        n = len(payload)
        if n < 126:
            hdr = bytes([0x80 | op, n])
        elif n < 65536:
            hdr = bytes([0x80 | op, 126]) + struct.pack(">H", n)
        else:
            hdr = bytes([0x80 | op, 127]) + struct.pack(">Q", n)
        async with self.lock:
            self.w.write(hdr + payload)
            await self.w.drain()

    async def send(self, obj):
        if self.closed:
            return
        try:
            await self.send_raw(1, json.dumps(obj, ensure_ascii=False).encode())
        except Exception:
            self.closed = True


# ---------------------------------------------------------------- jobs
def jdir(jid):
    safe = "".join(c for c in jid if c.isalnum() or c in "._-")[:80]
    if not safe:
        raise ValueError("bad id")
    return os.path.join(JOBS, safe)


def start_job(jid, cmd):
    d = jdir(jid)
    if os.path.exists(os.path.join(d, "pid")):
        return int(open(os.path.join(d, "pid")).read().strip() or 0)
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "cmd"), "w").write(cmd)
    open(os.path.join(d, "out"), "wb").close()
    open(os.path.join(d, "started"), "w").write(str(int(time.time())))
    # The wrapper writes the exit code; the job is its own session so it
    # survives this worker restarting and the phone disconnecting.
    wrapper = 'cd ~; sh "$0/cmd" >> "$0/out" 2>&1 < /dev/null; echo $? > "$0/rc.tmp"; mv "$0/rc.tmp" "$0/rc"'
    p = __import__("subprocess").Popen(["sh", "-c", wrapper, d], stdin=open(os.devnull), stdout=open(os.devnull, "w"),
                                        stderr=__import__("subprocess").STDOUT, start_new_session=True)
    open(os.path.join(d, "pid"), "w").write(str(p.pid))
    return p.pid


def job_state(jid):
    d = jdir(jid)
    rc = os.path.join(d, "rc")
    if os.path.exists(rc):
        try:
            return "done", int(open(rc).read().strip() or 1)
        except ValueError:
            return "done", 1
    try:
        pid = int(open(os.path.join(d, "pid")).read().strip())
        os.kill(pid, 0)
        return "running", None
    except Exception:
        return ("done", 1) if os.path.exists(os.path.join(d, "pid")) else ("missing", None)


async def follow(ws, jid, since):
    d = jdir(jid)
    out = os.path.join(d, "out")
    if not os.path.exists(out):
        await ws.send({"t": "exit", "id": jid, "code": 127, "off": 0, "err": "no such job"}); return
    off = max(0, int(since or 0))
    while not ws.closed:
        size = os.path.getsize(out)
        if size > off:
            with open(out, "rb") as f:
                f.seek(off)
                while off < size and not ws.closed:
                    chunk = f.read(min(64 * 1024, size - off))
                    if not chunk:
                        break
                    await ws.send({"t": "ev", "id": jid, "off": off, "b": base64.b64encode(chunk).decode()})
                    off += len(chunk)
        st, code = job_state(jid)
        if st != "running" and os.path.getsize(out) <= off:
            await ws.send({"t": "exit", "id": jid, "code": code if code is not None else 1, "off": off}); return
        await asyncio.sleep(0.25)


def list_jobs():
    out = []
    if not os.path.isdir(JOBS):
        return out
    for jid in sorted(os.listdir(JOBS))[-60:]:
        st, code = job_state(jid)
        d = jdir(jid)
        try:
            started = int(open(os.path.join(d, "started")).read())
        except Exception:
            started = 0
        out.append({"id": jid, "state": st, "code": code, "size": os.path.getsize(os.path.join(d, "out")) if os.path.exists(os.path.join(d, "out")) else 0, "started": started})
    return out


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def clean_path(p):
    p = os.path.expanduser(p or "")
    return p if os.path.isabs(p) else os.path.join(HOME, p)


# ---------------------------------------------------------------- session
async def session(ws, token, peer):
    hello = json.loads(await asyncio.wait_for(ws.recv(), 20))
    if hello.get("t") != "hello" or not hmac.compare_digest(str(hello.get("token", "")), token):
        await ws.send({"t": "hello", "ok": False, "err": "bad token"}); return
    await ws.send({"t": "hello", "ok": True, "host": socket.gethostname(), "v": VERSION})
    log("phone connected", peer)
    tasks = {}
    while True:
        m = json.loads(await asyncio.wait_for(ws.recv(), 90))  # phone pings every 15 s
        t = m.get("t")
        try:
            if t == "ping":
                await ws.send({"t": "pong", "n": m.get("n")})
            elif t == "exec":
                async def ex(m=m):
                    try:
                        p = await asyncio.create_subprocess_shell(m["cmd"], stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
                                                                  stdin=asyncio.subprocess.DEVNULL, cwd=HOME, start_new_session=True)
                        try:
                            data, _ = await asyncio.wait_for(p.communicate(), float(m.get("timeout") or 60))
                            code = p.returncode
                        except asyncio.TimeoutError:
                            try: os.killpg(p.pid, signal.SIGKILL)
                            except Exception: pass
                            data, code = b"(timeout)", 124
                        await ws.send({"t": "res", "rid": m.get("rid"), "code": code, "out": data[-1_000_000:].decode("utf-8", "replace")})
                    except Exception as e:
                        await ws.send({"t": "res", "rid": m.get("rid"), "code": 1, "out": "worker error: %s" % e})
                asyncio.ensure_future(ex())
            elif t == "run":
                pid = start_job(m["id"], m["cmd"])
                await ws.send({"t": "started", "id": m["id"], "pid": pid, "rid": m.get("rid")})
            elif t == "sub":
                old = tasks.pop(m["id"], None)
                if old: old.cancel()
                tasks[m["id"]] = asyncio.ensure_future(follow(ws, m["id"], m.get("since", 0)))
            elif t == "stop":
                try:
                    pid = int(open(os.path.join(jdir(m["id"]), "pid")).read())
                    os.killpg(os.getpgid(pid), signal.SIGTERM)
                except Exception:
                    pass
                await ws.send({"t": "stopped", "id": m["id"], "rid": m.get("rid")})
            elif t == "jobs":
                await ws.send({"t": "jobs", "list": list_jobs(), "rid": m.get("rid")})
            elif t == "stat":
                p = clean_path(m["path"])
                if os.path.exists(p):
                    await ws.send({"t": "res", "rid": m.get("rid"), "size": os.path.getsize(p), "sha256": sha256_file(p)})
                else:
                    await ws.send({"t": "res", "rid": m.get("rid"), "size": -1})
            elif t == "put":
                p = clean_path(m["path"]); os.makedirs(os.path.dirname(p) or HOME, exist_ok=True)
                off = int(m.get("off", 0)); data = base64.b64decode(m.get("b", ""))
                mode = "r+b" if os.path.exists(p) else "wb"
                with open(p, mode) as f:
                    f.truncate(off); f.seek(off); f.write(data)
                await ws.send({"t": "res", "rid": m.get("rid"), "size": off + len(data)})
            elif t == "get":
                p = clean_path(m["path"]); off = int(m.get("off", 0)); ln = min(int(m.get("len", 1 << 20)), 4 << 20)
                with open(p, "rb") as f:
                    f.seek(off); data = f.read(ln)
                await ws.send({"t": "res", "rid": m.get("rid"), "b": base64.b64encode(data).decode(), "size": os.path.getsize(p)})
            else:
                await ws.send({"t": "error", "err": "unknown " + str(t)})
        except Exception as e:
            await ws.send({"t": "error", "rid": m.get("rid"), "id": m.get("id"), "err": str(e)})


# ---------------------------------------------------------------- http / tls / mux
async def handle_tls(r, w, token, path):
    peer = w.get_extra_info("peername")
    try:
        head = await asyncio.wait_for(r.readuntil(b"\r\n\r\n"), 15)
        lines = head.decode("latin-1").split("\r\n")
        req = lines[0].split(" ")
        hdr = {l.split(":", 1)[0].strip().lower(): l.split(":", 1)[1].strip() for l in lines[1:] if ":" in l}
        if len(req) < 2 or req[1].split("?")[0] != path or hdr.get("upgrade", "").lower() != "websocket":
            body = b"<html><body>ok</body></html>"
            w.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s" % (len(body), body))
            await w.drain(); w.close(); return
        acc = base64.b64encode(hashlib.sha1((hdr["sec-websocket-key"] + GUID).encode()).digest()).decode()
        w.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % acc).encode())
        await w.drain()
        ws = WS(r, w)
        await session(ws, token, peer)
    except (asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError, ssl.SSLError, OSError):
        pass
    except Exception as e:
        log("session error", peer, e)
    finally:
        try: w.close()
        except Exception: pass
        log("phone gone", peer)


async def splice(r1, w1, r2, w2):
    try:
        while True:
            b = await r1.read(65536)
            if not b:
                break
            w2.write(b); await w2.drain()
    except Exception:
        pass
    finally:
        try: w2.close()
        except Exception: pass


async def handle_mux(r, w, token, path, ctx, ssh_port):
    """Plain TCP accept on the shared port: TLS → link; SSH or silence → sshd."""
    try:
        first = await asyncio.wait_for(r.read(1), 1.5)
    except asyncio.TimeoutError:
        first = b""
    # TLS ClientHello → the internal TLS link listener; anything else → sshd.
    target = INTERNAL_TLS_PORT if first == b"\x16" else ssh_port
    try:
        r2, w2 = await asyncio.open_connection("127.0.0.1", target)
    except Exception:
        w.close(); return
    if first:
        w2.write(first); await w2.drain()
    await asyncio.gather(splice(r, w, r2, w2), splice(r2, w2, r, w))
    return
    try:
        r2, w2 = await asyncio.open_connection("127.0.0.1", ssh_port)
    except Exception:
        w.close(); return
    if first:
        w2.write(first); await w2.drain()
    await asyncio.gather(splice(r, w, r2, w2), splice(r2, w2, r, w))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8443)
    ap.add_argument("--path", default="/mines")
    ap.add_argument("--conf", default="/etc/mines-link")
    ap.add_argument("--ssh-mux", action="store_true", help="also splice SSH on the same port to 127.0.0.1:22")
    ap.add_argument("--ssh-port", type=int, default=22)
    a = ap.parse_args()
    token = open(os.path.join(a.conf, "token")).read().strip()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(os.path.join(a.conf, "cert.pem"), os.path.join(a.conf, "key.pem"))
    os.makedirs(JOBS, exist_ok=True)

    async def run():
        if a.ssh_mux:
            inner = await asyncio.start_server(lambda r, w: handle_tls(r, w, token, a.path), host="127.0.0.1", port=INTERNAL_TLS_PORT, ssl=ctx)
            srv = await asyncio.start_server(lambda r, w: handle_mux(r, w, token, a.path, ctx, a.ssh_port), host=None, port=a.port)
        else:
            srv = await asyncio.start_server(lambda r, w: handle_tls(r, w, token, a.path), host=None, port=a.port, ssl=ctx)
        log("mines-link v%d listening on %d%s" % (VERSION, a.port, " (+ssh mux)" if a.ssh_mux else ""))
        async with srv:
            await srv.serve_forever()
    asyncio.run(run())


if __name__ == "__main__":
    main()
__MINES_EOF__
else curl -fsSL --retry 3 "$SRC" -o /opt/mines-link/mines-link.py || curl -fsSL --retry 3 \
  "https://raw.githubusercontent.com/ddfc1991/openminis-agent-android/hark-minis-build/hark-build/vps-worker/mines-link.py" -o /opt/mines-link/mines-link.py; fi
python3 -c "import ast,sys;ast.parse(open('/opt/mines-link/mines-link.py').read())"
cd /etc/mines-link
[ -s token ] || (head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > token)
chmod 600 token
if [ ! -s cert.pem ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=$(hostname)" -keyout key.pem -out cert.pem >/dev/null 2>&1
  chmod 600 key.pem
fi
PIN=$(openssl x509 -in cert.pem -outform der | openssl dgst -sha256 | awk '{print $NF}')
if [ -n "$MUX" ] && [ "$PORT" = 443 ] && ss -ltnp 2>/dev/null | grep -q ':443 .*sshd'; then
  echo "注意：443 端口现在被 sshd 占用。--ssh-mux 模式会自己把 SSH 转给 22 端口，请先从 sshd 配置里去掉 Port 443 再重跑。"; exit 1
fi
cat > /etc/systemd/system/mines-link.service <<EOF
[Unit]
Description=Mines link worker (phone's HTTPS channel)
After=network-online.target
[Service]
ExecStart=/usr/bin/env python3 /opt/mines-link/mines-link.py --port $PORT $MUX
Restart=always
RestartSec=2
User=root
MemoryMax=200M
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable mines-link >/dev/null 2>&1
systemctl restart mines-link
command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q active && ufw allow "$PORT"/tcp >/dev/null || true
command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1 && { firewall-cmd --permanent --add-port="$PORT"/tcp >/dev/null; firewall-cmd --reload >/dev/null; } || true
IP=$(curl -s --max-time 5 https://api.ipify.org || hostname -I | awk '{print $1}')
LINE="mines-link://$IP:$PORT/mines#token=$(cat token)&pin=$PIN"
echo "$LINE" > /etc/mines-link/phone.txt; chmod 600 /etc/mines-link/phone.txt
sleep 1
systemctl is-active mines-link >/dev/null && echo "✓ mines-link 已运行（端口 $PORT）" || { echo "✗ 没启动成功："; journalctl -u mines-link -n 20 --no-pager; exit 1; }
echo "云服务器安全组需要放行 TCP $PORT。"
echo "Mines 连接码（在 Mines → 工作位置 → 服务器 → 快速线路 里会自动读取，也可以粘贴）："
echo "$LINE"
