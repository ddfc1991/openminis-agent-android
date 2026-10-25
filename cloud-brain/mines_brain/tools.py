"""Server-side tools: http_fetch, shell (devbox jail over SSH), browser (local headless Chromium over CDP),
plus the phone-bound ones (ask_user / handoff / phone) and finish, which the agent loop handles itself."""
import base64
import html
import ipaddress
import json
import os
import re
import socket
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_OUT = 6000

ACCOUNT_DOC = "操作的是用户自己的某个已登录账号时必须填，如 github:ddfc1991；注册新账号、搜索、看公开网页时留空"

SCHEMAS = [
    {"type": "function", "function": {"name": "http_fetch", "description": "请求一个公网网址，返回状态码和正文（HTML 会转成纯文本）。GET 直接执行；POST/PUT/DELETE 视为对外发送，需要用户在手机上同意。",
        "parameters": {"type": "object", "properties": {"url": {"type": "string"}, "method": {"type": "string", "enum": ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"]},
            "headers": {"type": "object"}, "body": {"type": "string"}, "account": {"type": "string", "description": ACCOUNT_DOC}}, "required": ["url"]}}},
    {"type": "function", "function": {"name": "shell", "description": "在云端隔离的 Linux 容器（Ubuntu 24.04，root，可装软件，可联网）里执行 bash 命令。返回退出码和输出。适合下载、解析、跑脚本。",
        "parameters": {"type": "object", "properties": {"command": {"type": "string"}, "timeout": {"type": "integer", "description": "秒，默认 60，最多 600"},
            "account": {"type": "string", "description": ACCOUNT_DOC}}, "required": ["command"]}}},
    {"type": "function", "function": {"name": "browser", "description": "云端无头浏览器（每个任务独立的无痕会话）。action: open(url) 打开网页；text 读当前页文字；links 列出链接；click(selector 或 text) 点击；type(selector,text) 输入；press(key) 按键；wait(ms)；back。",
        "parameters": {"type": "object", "properties": {"action": {"type": "string", "enum": ["open", "text", "links", "click", "type", "press", "wait", "back"]},
            "url": {"type": "string"}, "selector": {"type": "string"}, "text": {"type": "string"}, "key": {"type": "string"}, "ms": {"type": "integer"},
            "account": {"type": "string", "description": ACCOUNT_DOC}}, "required": ["action"]}}},
    {"type": "function", "function": {"name": "ask_user", "description": "需要用户回答一个问题才能继续时调用（缺信息、要选择）。任务会暂停在「等你处理」，用户在手机上回复后继续。",
        "parameters": {"type": "object", "properties": {"question": {"type": "string"}}, "required": ["question"]}}},
    {"type": "function", "function": {"name": "handoff", "description": "遇到滑块、验证码、人机验证、短信验证、扫码、付款等只能真人完成的步骤时调用：把网址和要做的事交给用户在手机上完成，用户点「好了」后继续。",
        "parameters": {"type": "object", "properties": {"url": {"type": "string"}, "reason": {"type": "string"}}, "required": ["reason"]}}},
    {"type": "function", "function": {"name": "phone", "description": "需要用户手机上的东西时调用（短信验证码、手机里的文件、手机上已登录的 App）。用户手机会收到请求，处理后把结果发回来。",
        "parameters": {"type": "object", "properties": {"need": {"type": "string", "description": "要手机做什么，一句话"}}, "required": ["need"]}}},
    {"type": "function", "function": {"name": "finish", "description": "任务完成（或确定做不了）时调用，给出给用户看的中文结果。",
        "parameters": {"type": "object", "properties": {"result": {"type": "string"}, "ok": {"type": "boolean"}}, "required": ["result"]}}},
]

PHONE_TOOLS = ("ask_user", "handoff", "phone")


def clip(s, n=MAX_OUT):
    s = s if isinstance(s, str) else str(s)
    return s if len(s) <= n else s[: n - 200] + f"\n…（已截断，共 {len(s)} 字）…\n" + s[-150:]


# ── http_fetch ──

def _private(host):
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return False
    for i in infos:
        ip = ipaddress.ip_address(i[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast or ip.is_unspecified:
            return True
    return False


def html_text(s):
    s = re.sub(r"(?is)<(script|style|noscript|svg)[^>]*>.*?</\1>", " ", s)
    s = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</li>|</h\d>|</tr>", "\n", s)
    s = re.sub(r"(?s)<[^>]+>", " ", s)
    s = html.unescape(s)
    s = re.sub(r"[ \t\r\f\v]+", " ", s)
    s = re.sub(r"\n\s*\n+", "\n", s)
    return s.strip()


def http_fetch(args, ctx=None):
    url = str(args.get("url", "")).strip()
    u = urllib.parse.urlparse(url)
    if u.scheme not in ("http", "https") or not u.hostname:
        return "错误：只支持 http/https 网址"
    if _private(u.hostname):
        return "错误：不允许访问内网/本机地址"
    method = str(args.get("method", "GET")).upper()
    body = args.get("body")
    data = body.encode() if isinstance(body, str) and body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36")
    for k, v in (args.get("headers") or {}).items():
        req.add_header(str(k), str(v))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read(2_000_000)
            ct = r.headers.get("Content-Type", "")
            code = r.status
    except urllib.error.HTTPError as e:
        raw = e.read(200_000); ct = e.headers.get("Content-Type", ""); code = e.code
    except Exception as e:
        return f"错误：{type(e).__name__}: {e}"
    txt = raw.decode("utf-8", "replace")
    if "html" in ct.lower() or txt.lstrip()[:15].lower().startswith(("<!doctype", "<html")):
        txt = html_text(txt)
    return clip(f"HTTP {code}\n{txt}")


# ── shell in the devbox jail ──

DEVBOX = {"host": "127.0.0.1", "port": int(os.environ.get("MB_DEVBOX_PORT", "22022")),
          "key": os.environ.get("MB_DEVBOX_KEY", "/var/lib/mines-brain/devbox_ed")}


def _die_with_parent():
    """the command dies with the brain (kill -9 / OOM): a resumed step is re-run once instead of running twice in parallel"""
    try:
        import ctypes
        ctypes.CDLL("libc.so.6", use_errno=True).prctl(1, 9)   # PR_SET_PDEATHSIG, SIGKILL
    except Exception:
        pass


def shell(args, ctx=None):
    cmd = str(args.get("command", ""))
    if not cmd.strip():
        return "错误：命令为空"
    to = max(5, min(int(args.get("timeout") or 60), 600))
    if os.environ.get("MB_SHELL_LOCAL") == "1":      # tests only
        argv = ["bash", "-lc", cmd]
    else:
        # -tt: the command gets a pty, so it is hung up (SIGHUP) when the brain or its ssh dies instead of running on
        argv = ["ssh", "-tt", "-p", str(DEVBOX["port"]), "-i", DEVBOX["key"], "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null", "-o", "ConnectTimeout=10", "-o", "LogLevel=ERROR", f"root@{DEVBOX['host']}", cmd]
    try:
        p = subprocess.run(argv, capture_output=True, timeout=to, stdin=subprocess.DEVNULL, preexec_fn=_die_with_parent)
        out = (p.stdout + (b"\n[stderr]\n" + p.stderr if p.stderr.strip() else b"")).decode("utf-8", "replace").replace("\r\n", "\n")
        return clip(f"exit {p.returncode}\n{out}")
    except subprocess.TimeoutExpired:
        return f"错误：命令超过 {to} 秒，已停止"
    except Exception as e:
        return f"错误：{e}"


# ── browser over CDP (minimal stdlib WebSocket client) ──

class WS:
    def __init__(self, url, timeout=30):
        u = urllib.parse.urlparse(url)
        self.s = socket.create_connection((u.hostname, u.port or 80), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        path = u.path + ("?" + u.query if u.query else "")
        self.s.sendall((f"GET {path} HTTP/1.1\r\nHost: {u.hostname}:{u.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            ch = self.s.recv(4096)
            if not ch:
                raise ConnectionError("ws handshake closed")
            resp += ch
        head, _, self.buf = resp.partition(b"\r\n\r\n")
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise ConnectionError("ws handshake: " + head.split(b"\r\n")[0].decode())
        self.n = 0

    def _recv(self, n):
        while len(self.buf) < n:
            ch = self.s.recv(65536)
            if not ch:
                raise ConnectionError("ws closed")
            self.buf += ch
        d, self.buf = self.buf[:n], self.buf[n:]
        return d

    def send(self, obj):
        data = json.dumps(obj).encode()
        hdr = bytearray([0x81])
        ln = len(data)
        if ln < 126:
            hdr.append(0x80 | ln)
        elif ln < 65536:
            hdr.append(0x80 | 126); hdr += struct.pack(">H", ln)
        else:
            hdr.append(0x80 | 127); hdr += struct.pack(">Q", ln)
        mask = os.urandom(4)
        hdr += mask
        self.s.sendall(bytes(hdr) + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def recv(self):
        msg = b""
        while True:
            b1, b2 = self._recv(2)
            ln = b2 & 0x7F
            if ln == 126:
                ln = struct.unpack(">H", self._recv(2))[0]
            elif ln == 127:
                ln = struct.unpack(">Q", self._recv(8))[0]
            payload = self._recv(ln)
            op = b1 & 0x0F
            if op == 8:
                raise ConnectionError("ws closed by peer")
            if op in (9, 10):
                continue
            msg += payload
            if b1 & 0x80:
                return json.loads(msg.decode("utf-8", "replace"))

    def call(self, method, params=None, session=None, timeout=30):
        self.n += 1
        mid = self.n
        m = {"id": mid, "method": method, "params": params or {}}
        if session:
            m["sessionId"] = session
        self.send(m)
        t0 = time.time()
        self.s.settimeout(timeout)
        while time.time() - t0 < timeout:
            r = self.recv()
            if r.get("id") == mid:
                if "error" in r:
                    raise RuntimeError(r["error"].get("message", str(r["error"])))
                return r.get("result", {})
        raise TimeoutError(method)

    def close(self):
        try:
            self.s.close()
        except OSError:
            pass


CDP = os.environ.get("MB_CDP", "http://127.0.0.1:9222")


def _browser_ws():
    with urllib.request.urlopen(CDP + "/json/version", timeout=5) as r:
        return json.loads(r.read())["webSocketDebuggerUrl"]


def browser(args, ctx):
    """ctx: {"get": () -> state dict, "set": (state) -> None}; state keeps the task's browser context + target so a
    restart re-attaches to the same page"""
    action = str(args.get("action", "")).lower()
    try:
        ws = WS(_browser_ws())
    except Exception as e:
        return f"错误：云端浏览器不可用（{e}）"
    try:
        st = ctx["get"]() or {}
        tid = st.get("target")
        alive = False
        if tid:
            try:
                targets = ws.call("Target.getTargets").get("targetInfos", [])
                alive = any(t["targetId"] == tid for t in targets)
            except Exception:
                alive = False
        if not alive:
            bc = ws.call("Target.createBrowserContext", {"disposeOnDetach": False}).get("browserContextId")
            tid = ws.call("Target.createTarget", {"url": "about:blank", "browserContextId": bc})["targetId"]
            st = {"context": bc, "target": tid}
            ctx["set"](st)
        sess = ws.call("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]

        def ev(expr, timeout=20):
            r = ws.call("Runtime.evaluate", {"expression": expr, "returnByValue": True, "awaitPromise": True}, sess, timeout)
            if r.get("exceptionDetails"):
                raise RuntimeError(r["exceptionDetails"].get("text", "js error"))
            return (r.get("result") or {}).get("value")

        def settle(ms=8000):
            t0 = time.time()
            while time.time() - t0 < ms / 1000:
                try:
                    if ev("document.readyState", 5) == "complete":
                        break
                except Exception:
                    pass
                time.sleep(0.4)

        def page_text():
            t = ev("(document.title||'')+'\\n'+location.href+'\\n\\n'+(document.body?document.body.innerText:'')") or ""
            return clip(t, 5000)

        if action == "open":
            url = str(args.get("url", ""))
            u = urllib.parse.urlparse(url)
            if u.scheme not in ("http", "https") or not u.hostname or _private(u.hostname):
                return "错误：只能打开公网 http/https 网址"
            ws.call("Page.navigate", {"url": url}, sess, 30)
            settle(15000)
            return page_text()
        if action == "text":
            return page_text()
        if action == "links":
            v = ev("Array.from(document.querySelectorAll('a[href]')).slice(0,80).map(a=>(a.innerText||'').trim().slice(0,60)+' -> '+a.href).join('\\n')")
            return clip(v or "（没有链接）")
        if action == "back":
            ev("history.back()"); settle(); return page_text()
        if action == "wait":
            time.sleep(max(0, min(int(args.get("ms") or 1000), 15000)) / 1000); return page_text()
        if action == "press":
            key = str(args.get("key") or "Enter")
            for t in ("keyDown", "keyUp"):
                ws.call("Input.dispatchKeyEvent", {"type": t, "key": key, "code": key, "windowsVirtualKeyCode": 13 if key == "Enter" else 0}, sess)
            settle(); return "已按 " + key + "\n" + page_text()[:1500]
        if action in ("click", "type"):
            sel = args.get("selector") or ""
            txt = args.get("text") or ""
            find = ("(function(sel,txt){let el=null;if(sel){try{el=document.querySelector(sel)}catch(e){}}"
                    "if(!el&&txt&&%s){const all=[...document.querySelectorAll('a,button,input[type=submit],input[type=button],[role=button],label,span,div')];"
                    "el=all.find(e=>(e.innerText||e.value||'').trim()===txt)||all.find(e=>(e.innerText||e.value||'').trim().includes(txt));}"
                    "if(!el)return null;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,(el.innerText||el.value||el.tagName).slice(0,40)]})")
            if action == "click":
                r = ev(find % "true" + f"({json.dumps(sel)},{json.dumps(txt)})")
                if not r:
                    return "错误：页面上找不到要点的元素"
                x, y, label = r
                for t in ("mousePressed", "mouseReleased"):
                    ws.call("Input.dispatchMouseEvent", {"type": t, "x": x, "y": y, "button": "left", "clickCount": 1}, sess)
                time.sleep(1.2); settle()
                return f"已点击「{label}」\n" + page_text()[:2000]
            r = ev(find % "false" + f"({json.dumps(sel)},'')")
            if not r:
                return "错误：页面上找不到输入框（type 需要 selector）"
            ev(f"(function(){{const e=document.querySelector({json.dumps(sel)});e.focus();e.value='';}})()")
            ws.call("Input.insertText", {"text": txt}, sess)
            ev(f"(function(){{const e=document.querySelector({json.dumps(sel)});e.dispatchEvent(new Event('input',{{bubbles:true}}));e.dispatchEvent(new Event('change',{{bubbles:true}}));}})()")
            return f"已在 {sel} 输入 {len(txt)} 个字"
        return "错误：不支持的 action " + action
    except Exception as e:
        return f"错误：浏览器 {type(e).__name__}: {e}"
    finally:
        ws.close()


def browser_close(state):
    if not state or not state.get("context"):
        return
    try:
        ws = WS(_browser_ws())
        try:
            ws.call("Target.disposeBrowserContext", {"browserContextId": state["context"]})
        finally:
            ws.close()
    except Exception:
        pass


RUNNERS = {"http_fetch": http_fetch, "shell": shell, "browser": browser}
