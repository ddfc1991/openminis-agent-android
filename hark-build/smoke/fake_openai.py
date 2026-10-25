#!/usr/bin/env python3
"""Tiny OpenAI-compatible endpoint for the emulator smoke test (10.0.2.2:8088)."""
import json, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATS = {"garbage_posts": 0, "loop_posts": 0, "saw_warning": False, "empty_tool_content": 0, "tool_msgs": 0, "tool": None}

def last_user_text(msgs):
    for m in reversed(msgs):
        if m.get("role") == "user":
            c = m.get("content")
            if isinstance(c, list):
                c = " ".join(p.get("text", "") for p in c if isinstance(p, dict))
            if isinstance(c, str) and c.strip() and "Image returned" not in c:
                return c
    return ""

def pick_tool(tools):
    names = [t.get("function", {}).get("name") for t in tools or []]
    # [38.9.13] memory_v2 replaces memory_get with `memory` (ls/find/read): ls always succeeds, so only the loop guard stops it
    if "memory_get" not in names and "memory" in names:
        return "memory", {"tool_title": "查记忆", "action": "ls"}
    for want in ("memory_get", "file_read", "skills_list", "list_skills"):
        if want in names:
            t = tools[names.index(want)]; break
    else:
        t = (tools or [{}])[0]
    f = t.get("function", {})
    req = (f.get("parameters") or {}).get("required") or []
    return f.get("name", "memory_get"), {k: "smoke-loop" for k in req}


# [build-38.6] fake 扣子 (list bots → auto-publish to the API channel → v3 chat), fake Manus tasks, fake AI web chat
COZE = {"published": {"bot1"}, "bots": [("bot1", "冒烟助手"), ("bot2", "写作搭子")], "chats": {}}
WEBCHAT = """<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>假豆包</title></head>
<body style="font-family:sans-serif;margin:0"><div id="log" style="padding:12px"></div>
<div style="position:fixed;bottom:0;left:0;right:0;padding:8px;background:#eee">
<textarea data-testid="chat_input_input" id="q" style="width:75%;height:60px"></textarea>
<button data-testid="chat_input_send_button" aria-label="发送" id="send">发送</button></div>
<script>
document.getElementById('send').onclick=function(){var q=document.getElementById('q').value;if(!q)return;
var log=document.getElementById('log');var u=document.createElement('div');u.setAttribute('data-testid','send_message');u.innerText=q;log.appendChild(u);
document.getElementById('q').value='';
var a=document.createElement('div');a.setAttribute('data-testid','receive_message');var c=document.createElement('div');c.setAttribute('data-testid','message_text_content');a.appendChild(c);log.appendChild(a);
var full='网页成员收到：我是假豆包，看到了群里的问题。';var i=0;var t=setInterval(function(){i+=3;c.innerText=full.slice(0,i);if(i>=full.length)clearInterval(t);},300);};
</script></body></html>"""
WEBLOGIN = """<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"></head><body style="font-family:sans-serif">
<h2>登录 Kimi</h2><p>手机号登录后才能聊天</p><button>登录</button></body></html>"""

# [build-38.7] fake web AI with real accounts: session cookie + localStorage per account (multi-account isolation smoke)
WEBACCT_WALL = """<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>假豆包 登录</title></head>
<body style="font-family:sans-serif"><h2>请先登录再使用</h2><p>手机号码登录</p><button>登录</button></body></html>"""
WEBACCT_LOGIN = """<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>登录成功</title></head>
<body style="font-family:sans-serif"><h2>已登录 %s</h2><script>localStorage.setItem('fuser','ls%s');</script></body></html>"""
WEBACCT_CHAT = """<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>假豆包</title></head>
<body style="font-family:sans-serif;margin:0"><div id="log" style="padding:12px"></div>
<div style="position:fixed;bottom:0;left:0;right:0;padding:8px;background:#eee">
<textarea data-testid="chat_input_input" id="q" style="width:75%;height:60px"></textarea>
<button data-testid="chat_input_send_button" aria-label="发送" id="send">发送</button></div>
<script>
document.getElementById('send').onclick=function(){var q=document.getElementById('q').value;if(!q)return;
var log=document.getElementById('log');var u=document.createElement('div');u.setAttribute('data-testid','send_message');u.innerText=q;log.appendChild(u);
document.getElementById('q').value='';
var a=document.createElement('div');a.setAttribute('data-testid','receive_message');var c=document.createElement('div');c.setAttribute('data-testid','message_text_content');a.appendChild(c);log.appendChild(a);
fetch('/webacct/reply?ls='+encodeURIComponent(localStorage.getItem('fuser')||'none')+'&q='+encodeURIComponent(q.slice(0,40)),{credentials:'same-origin'}).then(function(r){return r.text()}).then(function(t){c.innerText=t;});};
</script></body></html>"""

def cookie_of(h, name):
    for part in (h.headers.get("Cookie") or "").split(";"):
        k, _, v = part.strip().partition("=")
        if k == name: return v
    return None

KB_JSON = {"topic": "测试主题", "summary": "模型整理：这是假模型写的摘要。", "facts": ["模型要点一"], "decisions": ["模型结论：照办"], "todos": ["模型待办：明天检查"], "links": [], "files": []}
MULTIPART = """## 第一部分：结论
这是第一段，说明结论。

## 第二部分：步骤
1. 打开设置
2. 点「账号连接」
3. 登录一次

## 第三部分：代码
```python
print("hello mines")
```

最后一段：如果还有问题，随时问我。"""

GK = "http://10.0.2.2:8088/gecko-smoke"
I3913_SCRIPTS = {
    "MEMFIND": [("memory_write", {"tool_title": "记一条机器记忆", "content": "## 梦云冒烟\nSMOKEMEM-7731 梦云是冒烟测试用的机器", "dir": "机器"}),
                ("memory", {"tool_title": "查机器记忆", "action": "find", "query": "SMOKEMEM-7731"})],
    "GECKOREAD": [("browser_use", {"tool_title": "用 Mines 浏览器打开", "action": "navigate", "engine": "gecko", "url": GK}),
                  ("browser_use", {"tool_title": "读页面文字", "action": "get_text", "engine": "gecko"}),
                  ("browser_use", {"tool_title": "把页面给用户看", "action": "wait_for_human", "engine": "gecko"})],
    "OAUTHTEST": [("browser_use", {"tool_title": "打开 Google 登录", "action": "navigate", "url": "https://accounts.google.com/v3/signin/identifier?continue=https%3A%2F%2Fzeabur.com"})],
}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): print("fake-openai", self.command, self.path, flush=True)
    def _html(self, html):
        b = html.encode()
        self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def _json(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        if self.path.startswith("/webacct/login"):
            from urllib.parse import urlparse, parse_qs
            u = (parse_qs(urlparse(self.path).query).get("u") or ["A"])[0]
            b = (WEBACCT_LOGIN % (u, u)).encode()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Set-Cookie", "fsess=%s; Path=/; Max-Age=86400" % u)
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
            STATS.setdefault("webacct_logins", []).append(u); return
        if self.path.startswith("/webacct/reply"):
            from urllib.parse import urlparse, parse_qs
            qs = parse_qs(urlparse(self.path).query)
            who = cookie_of(self, "fsess")
            ls = (qs.get("ls") or ["none"])[0]
            STATS.setdefault("webacct_replies", []).append({"cookie": who, "ls": ls})
            b = ("我是账号%s（本地存储 %s）：收到" % (who or "未登录", ls)).encode()
            self.send_response(200); self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b); return
        if self.path.startswith("/webacct"):
            return self._html(WEBACCT_CHAT if cookie_of(self, "fsess") else WEBACCT_WALL)
        if self.path.startswith("/webchat-login"):
            return self._html(WEBLOGIN)
        if self.path.startswith("/webchat"):
            return self._html(WEBCHAT)
        if self.path.startswith("/v1/workspaces"):
            STATS["coze_list"] = STATS.get("coze_list", 0) + 1
            return self._json({"code": 0, "msg": "", "data": {"workspaces": [{"id": "ws1", "name": "个人空间"}]}})
        if self.path.startswith("/v1/bots"):
            api = "published_online" in self.path and "connector_id=1024" in self.path
            items = [{"id": b, "name": n, "icon_url": "", "is_published": b in COZE["published"]} for b, n in COZE["bots"] if (b in COZE["published"] or not api)]
            return self._json({"code": 0, "msg": "", "data": {"items": items, "total": len(items)}})
        if self.path.startswith("/v3/chat/retrieve"):
            return self._json({"code": 0, "data": {"status": "completed"}})
        if self.path.startswith("/v3/chat/message/list"):
            cid = self.path.split("chat_id=")[-1].split("&")[0]
            bot = COZE["chats"].get(cid, "bot1")
            name = dict(COZE["bots"]).get(bot, "?")
            return self._json({"code": 0, "data": [{"role": "assistant", "type": "answer", "content": f"扣子智能体「{name}」收到：这是我的看法。"}]})
        if "/manus/v2/task.detail" in self.path:
            return self._json({"task": {"status": "stopped"}})
        if "/manus/v2/task.listMessages" in self.path:
            return self._json({"messages": [{"type": "assistant_message", "assistant_message": {"content": "Manus 做完了：任务已完成，结论如下。"}}]})
        # [38.9.11.3 f] 每日简报 sources for the errzh hook: refused / connection cut / a normal feed
        if self.path.startswith("/rss403"):
            b = b"forbidden"; self.send_response(403); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b); return
        if self.path.startswith("/rsseof"):
            self.close_connection = True
            try: self.connection.shutdown(2)
            except Exception: pass
            return
        if self.path.startswith("/rssok"):
            now = time.strftime("%a, %d %b %Y %H:%M:%S GMT", time.gmtime())
            items = "".join(f"<item><title>测试新闻 {i}：手机 AI 助手更新</title><link>https://example.com/n{i}</link><pubDate>{now}</pubDate><description>第 {i} 条测试摘要。</description></item>" for i in range(1, 4))
            b = f'<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>ok</title>{items}</channel></rss>'.encode()
            self.send_response(200); self.send_header("Content-Type", "application/rss+xml; charset=utf-8"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b); return
        if self.path.startswith("/gecko-smoke"):   # [38.9.13] Mines' own browser (GeckoView) reads this page
            return self._html('<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>Gecko 冒烟</title></head>'
                              '<body style="font-family:sans-serif;padding:16px"><h2>Mines 浏览器冒烟页</h2><p id="m">GECKO-SMOKE-OK 4417</p></body></html>')
        if self.path.startswith("/stats/reset"):
            # [38.9.11.3] each vps_smoke.py run (perf "old" pass, then the full "new" pass) starts from zero counters
            STATS.clear(); STATS.update({"garbage_posts": 0, "loop_posts": 0, "saw_warning": False, "empty_tool_content": 0, "tool_msgs": 0, "tool": None})
            return self._json(STATS)
        if self.path.startswith("/stats"):
            return self._json(STATS)
        # [build-33] simulated Gmail Atom feed with a fresh code mail + a page with a one-time-code field
        if self.path.startswith("/feed/atom"):
            now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            xml = ('<?xml version="1.0" encoding="UTF-8"?><feed version="0.3" xmlns="http://purl.org/atom/ns#">'
                   '<title>Gmail - Inbox for smoke@gmail.com</title><fullcount>1</fullcount>'
                   '<entry><title>Your Supabase verification code</title>'
                   '<summary>Enter this code to sign in: 482913. It expires in 10 minutes.</summary>'
                   f'<issued>{now}</issued><modified>{now}</modified><id>tag:gmail.google.com,2004:smoke1</id>'
                   '<author><name>Supabase</name><email>noreply@mail.app.supabase.io</email></author></entry></feed>').encode()
            self.send_response(200); self.send_header("Content-Type", "application/atom+xml")
            self.send_header("Content-Length", str(len(xml))); self.end_headers(); self.wfile.write(xml); return
        if self.path.startswith("/smslogin"):
            # [build-38.7] phone + SMS code login (the code arrives by `adb emu sms send`)
            html = ('<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>手机号登录</title></head>'
                    '<body style="font-family:sans-serif;font-size:20px;padding:16px"><h2>手机号登录</h2>'
                    '<p>手机号</p><input id="phone" type="tel" placeholder="请输入手机号" style="width:90%;height:48px;font-size:20px">'
                    '<p>短信验证码</p><input id="code" name="smscode" placeholder="请输入短信验证码" inputmode="numeric" autocomplete="one-time-code" style="width:60%;height:48px;font-size:22px">'
                    '<button style="height:48px">获取验证码</button><br><br><button style="width:90%;height:52px">登录</button>'
                    '<p>请登录后使用</p></body></html>').encode()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html))); self.end_headers(); self.wfile.write(html); return
        if self.path.startswith("/pwlogin"):
            # [build-38.7] account + password login: filled from the imported (CSV) saved login
            html = ('<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>账号登录</title></head>'
                    '<body style="font-family:sans-serif;font-size:20px;padding:16px"><h2>账号登录</h2>'
                    '<input id="u" name="username" autocomplete="username" placeholder="账号" style="width:90%;height:48px;font-size:20px"><br><br>'
                    '<input id="p" name="password" type="password" autocomplete="current-password" placeholder="密码" style="width:90%;height:48px;font-size:20px"><br><br>'
                    '<button onclick="document.getElementById(\'r\').innerText=\'登录中：\'+document.getElementById(\'u\').value">登录</button>'
                    '<p id="r">请登录后使用</p></body></html>').encode()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html))); self.end_headers(); self.wfile.write(html); return
        if self.path.startswith("/otp"):
            html = (b'<html><body><h3>Enter the code we e-mailed you</h3>'
                    b'<input id="code" name="otp" autocomplete="one-time-code" inputmode="numeric" style="width:200px;height:40px">'
                    b'<button>Verify</button></body></html>')
            self.send_response(200); self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(html))); self.end_headers(); self.wfile.write(html); return
        # [build-35.3] fake provider key page for 「去官网获取」: an old key is already visible (must NOT be taken),
        # 「Create new API key」 opens a name field + Create, then the new key is shown with a Copy button
        if self.path.startswith("/fakekeys"):
            html = ('<html><head><meta name="viewport" content="width=device-width"></head><body style="font-size:18px">'
                    '<h2>API keys</h2><p>Existing: sk-' + 'a' * 32 + ' (old)</p>'
                    '<button id="new" onclick="document.getElementById(\'dlg\').style.display=\'block\'">Create new API key</button>'
                    '<div id="dlg" style="display:none;margin-top:20px"><input id="name" placeholder="Name" style="width:240px;height:40px">'
                    '<button onclick="document.getElementById(\'out\').innerText=\'sk-' + 'd00dfeed' * 4 + '\';'
                    'document.getElementById(\'dlg\').style.display=\'none\'">Create</button></div>'
                    '<pre id="out" style="margin-top:20px"></pre></body></html>').encode()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html))); self.end_headers(); self.wfile.write(html); return
        # [38.9.1] 一键关联 against an e-mail login page: 手机号登录 (default tab) / 邮箱登录 tab; e-mail + 发送验证码 → code from IMAP
        if self.path.startswith("/linkfake/home"):
            return self._html('<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>控制台</title></head>'
                              '<body style="font-family:sans-serif;font-size:20px;padding:16px"><h2>欢迎回来</h2><p id="who">已登录</p></body></html>')
        if self.path.startswith("/linkfake/send"):
            from urllib.parse import urlparse, parse_qs
            q = parse_qs(urlparse(self.path).query); STATS["link_send"] = (q.get("email") or q.get("phone") or [""])[0]; STATS["link_kind"] = "email" if q.get("email") else "phone"
            return self._json({"ok": True})
        if self.path.startswith("/linkfake/verify"):
            from urllib.parse import urlparse, parse_qs
            q = parse_qs(urlparse(self.path).query); STATS["link_code"] = (q.get("code") or [""])[0]
            ok = STATS["link_code"] == "482913"; STATS["link_ok"] = ok
            if ok:
                self.send_response(302); self.send_header("Set-Cookie", "sess=ok; Path=/"); self.send_header("Location", "/linkfake/home"); self.end_headers(); return
            return self._json({"ok": False})
        if self.path.startswith("/linkfake"):
            return self._html("""<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>登录</title>
<style>body{font-family:sans-serif;font-size:19px;padding:16px} .tabs span{display:inline-block;padding:10px 16px;margin-right:8px;border-bottom:3px solid transparent}
.tabs span.on{border-color:#e55;font-weight:bold} input{width:90%;height:46px;font-size:19px;margin:8px 0} button{height:46px;font-size:18px;margin:6px 0}</style></head>
<body><h2>登录 SmokeCloud</h2>
<div class="tabs" role="tablist"><span role="tab" class="on" id="tp" onclick="show('p')">手机号登录</span><span role="tab" id="te" onclick="show('e')">邮箱登录</span></div>
<div id="fp"><input id="phone" type="tel" placeholder="请输入手机号"><br><button onclick="send('phone')">获取验证码</button>
<p style="color:#888;font-size:15px">本站仅支持 +1 手机号</p></div>
<div id="fe" style="display:none"><input id="email" type="email" name="email" placeholder="请输入邮箱"><br><button onclick="send('email')">发送验证码</button></div>
<div id="fc" style="display:none"><input id="code" name="code" placeholder="请输入验证码" autocomplete="one-time-code" inputmode="numeric"><br>
<button onclick="location.href='/linkfake/verify?code='+document.getElementById('code').value">登录</button></div>
<p id="err" role="alert" class="error"></p>
<script>function show(t){document.getElementById('fp').style.display=t=='p'?'block':'none';document.getElementById('fe').style.display=t=='e'?'block':'none';
document.getElementById('tp').className=t=='p'?'on':'';document.getElementById('te').className=t=='e'?'on':'';}
function send(k){var v=document.getElementById(k).value;if(k=='phone'){document.getElementById('err').innerText='该手机号不支持，请用邮箱登录';return;}
fetch('/linkfake/send?'+k+'='+encodeURIComponent(v));document.getElementById('fc').style.display='block';document.getElementById('err').innerText='';}</script></body></html>""")
        # [38.9.1] key page with a modal dialog (name + expiration) for the keyboard-up shot
        if self.path.startswith("/keydlg"):
            return self._html("""<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>API Keys</title></head>
<body style="font-family:sans-serif;font-size:18px;margin:0"><div style="padding:16px"><h2>API Keys</h2><p>Manage your keys.</p>
<button onclick="document.getElementById('dlg').style.display='flex'">Create API Key</button></div>
<div id="dlg" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,.45);align-items:center;justify-content:center">
<div style="background:#fff;border-radius:12px;padding:18px;width:86%"><h3>Create API Key</h3><p>Display name</p>
<input id="name" placeholder="Name" style="width:95%;height:44px;font-size:18px"><p>Expiration</p>
<select id="exp" style="height:44px;font-size:17px"><option>30 days</option><option>No expiration</option></select><br><br>
<button onclick="document.getElementById('out').innerText='gsk_'+'smokeKEY1234567890'.repeat(3);document.getElementById('dlg').style.display='none'">Submit</button></div></div>
<pre id="out" style="padding:16px"></pre></body></html>""")
        # [38.9.1] Groq-like console with its nav drawer open: dark-theme CSS made the drawer see-through on a light page
        if self.path.startswith("/drawer"):
            return self._html("""<html><head><meta name="viewport" content="width=device-width"><meta charset="utf-8"><title>Console</title>
<style>body{font-family:sans-serif;margin:0;background:#fff;color:#111} .drawer{position:fixed;top:0;left:0;bottom:0;width:72%;background:#fff;box-shadow:2px 0 12px #0003;padding:18px;font-size:20px;line-height:2.2}
@media (prefers-color-scheme: dark){ .drawer{background:transparent;color:#eee} }</style></head>
<body><div style="padding:18px 18px 18px 40%"><h2>API Keys</h2><p>Your API keys are listed below. Do not share your API key with others.</p><p>gsk_…old (created 3 days ago)</p></div>
<nav class="drawer" data-state="open"><div>Playground</div><div>API Keys</div><div>Dashboard</div><div>Docs</div><div>Settings</div><div>Sign Out</div></nav></body></html>""")
        if self.path.startswith("/badkey/"):   # [38.9.11.3] 测试全部密钥: sk-bad-* answers 401
            if "sk-bad" in (self.headers.get("Authorization") or ""):
                return self._json({"error": {"message": "Invalid API key", "type": "invalid_request_error", "code": "invalid_api_key"}}, 401)
            return self._json({"object": "list", "data": [{"id": "smoke-model", "object": "model", "owned_by": "smoke"}]})
        if self.path.rstrip("/").endswith("/models"):
            return self._json({"object": "list", "data": [{"id": "smoke-model", "object": "model", "owned_by": "smoke"}]})
        self._json({"ok": True})
    def do_POST(self):
        # [Mines-38.9.6 i13] fake self-hosted Memoh: POST /memoh396/api/auth/login {username,password}; good396 → JWT, else 401
        if self.path.startswith("/memoh396/"):
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n) or b"{}")
            STATS.setdefault("memoh_logins", []).append(body.get("password") == "good396")
            if self.path.endswith("/auth/login"):
                if body.get("password") == "good396":
                    return self._json({"access_token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzbW9rZSJ9.c21va2U", "token_type": "bearer"})
                return self._json({"detail": "invalid credentials"}, 401)
            return self._json({"detail": "not found"}, 404)
        if self.path.startswith("/v1/bot/publish") or self.path.startswith("/v3/chat") or "/manus/v2/task.create" in self.path:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n) or b"{}")
            if self.path.startswith("/v1/bot/publish"):
                COZE["published"].add(body.get("bot_id")); STATS.setdefault("coze_published", []).append(body.get("bot_id"))
                return self._json({"code": 0, "msg": "", "data": {"bot_id": body.get("bot_id"), "version": "1"}})
            if self.path.startswith("/v3/chat"):
                cid = "c%d" % (len(COZE["chats"]) + 1); COZE["chats"][cid] = body.get("bot_id")
                STATS["coze_chats"] = STATS.get("coze_chats", 0) + 1
                return self._json({"code": 0, "data": {"id": cid, "conversation_id": "v" + cid, "status": "in_progress"}})
            STATS["manus_tasks"] = STATS.get("manus_tasks", 0) + 1
            return self._json({"task_id": "t%d" % STATS["manus_tasks"]})
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        text = "你好，我是测试模型。"
        msgs = body.get("messages") or []
        # [build-38.5] group chat self-test: a member whose model is gone (Ark-style 404) and one that is rate-limited twice
        mdl = str(body.get("model") or "")
        if mdl == "smoke-bad-model":
            STATS["bad_model_posts"] = STATS.get("bad_model_posts", 0) + 1
            return self._json({"error": {"code": "InvalidEndpointOrModel.NotFound", "message": "The model or endpoint smoke-bad-model does not exist or you do not have access to it. Request id: 0217"}}, 404)
        if mdl == "smoke-429":
            STATS["rl_posts"] = STATS.get("rl_posts", 0) + 1
            if STATS["rl_posts"] % 3 != 0:
                return self._json({"error": {"message": "Rate limit reached, please retry"}}, 429)
        if "SMOKE429" in last_user_text(msgs):   # [38.9.1 d] chat 429: the turn must end with a reason, not hang on 正在思考
            STATS["chat429"] = STATS.get("chat429", 0) + 1
            return self._json({"error": {"message": "Rate limit reached for deepseek-flash, please retry"}}, 429)
        if "整理成用户以后能查的知识" in last_user_text(msgs):
            STATS["kb_posts"] = STATS.get("kb_posts", 0) + 1
            text = json.dumps(KB_JSON, ensure_ascii=False)
        if "COPYTEST" in last_user_text(msgs):   # [38.9.9] copy UX: markdown + a code block
            text = "## 复制测试\n这是**加粗**的一段话，还有 `行内代码`。\n\n```bash\nminis-vps run uptime\n```\n\n最后一行。"
        ut0 = last_user_text(msgs)
        if "MINES_ROUTER" in ut0:   # [38.9.14 chat_home] router: registrations / deployments are tasks, the rest quick
            q = ut0.split("用户：", 1)[-1]
            STATS.setdefault("router", []).append(q[:40])
            text = json.dumps({"mode": "task", "name": "断网测试"} if "NETDROP" in q else
                              {"mode": "task", "name": "Zeabur 注册"} if ("注册" in q or "部署" in q) else {"mode": "quick"}, ensure_ascii=False)
        elif "今天几号" in ut0:
            STATS["quick_posts"] = STATS.get("quick_posts", 0) + 1
            text = "今天是 10 月 25 日，星期日。"
        elif ut0.strip() == "帮我注册 zeabur" and body.get("stream"):   # the background task's own turn: slow enough to see 进行中
            STATS["task_posts"] = STATS.get("task_posts", 0) + 1
            time.sleep(12)
            text = "Zeabur 注册已完成：账号 smoke@example.com 已登记到注册台账。"
        # [38.9.14.1 stall_watch] the first streamed turn for STALLONCE hangs after the headers (no event at all); the retry is
        # slow to start (8 s) so the 「模型没响应，正在重试…」 line can be seen, then answers
        if "STALLONCE" in ut0 and "MINES_ROUTER" not in ut0 and body.get("stream"):
            STATS["stall_posts"] = STATS.get("stall_posts", 0) + 1
            if STATS["stall_posts"] == 1:
                STATS["stall_hang_at"] = time.time()
                self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers(); self.wfile.flush()
                for _ in range(120):
                    time.sleep(1)
                return
            STATS["stall_retry_at"] = time.time()
            time.sleep(8)
            text = "卡住后已恢复，这是重试后的回答。"
        # [38.9.14.1 net_drop] the first streamed turn for NETDROPONCE sends two chunks, then the TCP connection is reset (RST,
        # like the phone's ECONNABORTED); the resumed turn is slow to start (10 s) so 「网络断了，等网络恢复后继续」 can be seen
        if "NETDROPONCE" in ut0 and "MINES_ROUTER" not in ut0 and body.get("stream"):
            STATS["netdrop_posts"] = STATS.get("netdrop_posts", 0) + 1
            if STATS["netdrop_posts"] == 1:
                import socket as _so, struct as _st
                self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
                for piece in ("好的，", "我开始部署"):
                    d = {"id": "x", "object": "chat.completion.chunk", "created": int(time.time()), "model": "smoke-model",
                         "choices": [{"index": 0, "delta": {"role": "assistant", "content": piece}, "finish_reason": None}]}
                    self.wfile.write(b"data: " + json.dumps(d, ensure_ascii=False).encode() + b"\n\n"); self.wfile.flush(); time.sleep(0.5)
                time.sleep(1)
                try:
                    self.connection.setsockopt(_so.SOL_SOCKET, _so.SO_LINGER, _st.pack("ii", 1, 0))
                    self.connection.close()
                except Exception: pass
                self.close_connection = True
                return
            time.sleep(10)
            text = "断网后已继续，这是恢复后的回答：部署完成。"
        if "MULTIPART" in last_user_text(msgs):
            STATS["multipart"] = STATS.get("multipart", 0) + 1
            text = MULTIPART
        if "结构化总结" in last_user_text(msgs):
            text = "## 结论\n测试结论。\n## 共识\n都能回复。\n## 分歧\n无。\n## 各成员要点\n成员A：在线\n## 下一步\n继续。"
        if "GARBAGETEST" in last_user_text(msgs) and body.get("stream"):
            # [build-34 smoke] a model whose output degenerates into garbage (user's Gemma report)
            STATS["garbage_posts"] += 1
            import random
            rnd = random.Random(STATS["garbage_posts"])
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
            alpha = "LLLL_\ub098L_yLL//////"
            try:
                d = {"id": "x", "object": "chat.completion.chunk", "created": int(time.time()), "model": "smoke-model",
                     "choices": [{"index": 0, "delta": {"role": "assistant", "content": "让我想想这个链接。"}, "finish_reason": None}]}
                self.wfile.write(b"data: " + json.dumps(d).encode() + b"\n\n"); self.wfile.flush()
                for _ in range(400):
                    ch = "".join(rnd.choice(alpha) for _ in range(50))
                    d = {"id": "x", "object": "chat.completion.chunk", "created": int(time.time()), "model": "smoke-model",
                         "choices": [{"index": 0, "delta": {"content": ch}, "finish_reason": None}]}
                    self.wfile.write(b"data: " + json.dumps(d).encode() + b"\n\n"); self.wfile.flush()
                    time.sleep(0.02)
                self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
            except Exception:
                pass  # the app hung up on us: that is the expected outcome
            return
        if "TOOLZH" in last_user_text(msgs) and body.get("stream"):
            # [38.9.11 int] tool pills must read in Chinese: one turn with three tool calls, then a short final answer
            after_tool = False
            for m in reversed(msgs):
                if m.get("role") == "user": break
                if m.get("role") == "tool": after_tool = True; break
            names = [t.get("function", {}).get("name") for t in body.get("tools") or []]
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
            def emit(delta, fin=None):
                d = {"id": "x", "object": "chat.completion.chunk", "created": int(time.time()), "model": "smoke-model",
                     "choices": [{"index": 0, "delta": delta, "finish_reason": fin}]}
                self.wfile.write(b"data: " + json.dumps(d, ensure_ascii=False).encode() + b"\n\n"); self.wfile.flush()
            if after_tool:
                emit({"role": "assistant", "content": "三个工具都跑完了：进度已更新，文件已写好，记忆也读过了。"})
                emit({}, "stop")
            else:
                calls = []
                for nm, args in (("task_progress", {"title": "整理文件", "steps": ["写文件", "读记忆"], "current": 1}),
                                 ("file_write", {"path": "/var/minis/shared/smoke/toolzh.txt", "content": "工具名测试"}),
                                 ("memory_get", {"key": "smoke"})):
                    if nm in names:
                        t = (body.get("tools") or [])[names.index(nm)].get("function", {})
                        req = (t.get("parameters") or {}).get("required") or []
                        a = dict(args); [a.setdefault(k, "smoke") for k in req]
                        calls.append((nm, a))
                if not calls: calls = [pick_tool(body.get("tools"))]
                STATS["toolzh"] = [c[0] for c in calls]
                emit({"role": "assistant", "content": "好的，我先记一下进度，再写个文件："})
                emit({"tool_calls": [{"index": i, "id": f"call_zh_{i}", "type": "function", "function": {"name": nm, "arguments": json.dumps(a, ensure_ascii=False)}}
                                     for i, (nm, a) in enumerate(calls)]})
                emit({}, "tool_calls")
            self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
            return
        if "LOOPTEST" in last_user_text(msgs) and body.get("stream"):
            # [build-30 smoke] A model that repeats the same text + tool call forever.
            STATS["loop_posts"] += 1
            for m in msgs:
                if m.get("role") == "tool":
                    STATS["tool_msgs"] += 1
                    c = m.get("content")
                    if not (c if isinstance(c, str) else json.dumps(c)).strip():
                        STATS["empty_tool_content"] += 1
                    if "You are repeating yourself" in (c if isinstance(c, str) else json.dumps(c)):
                        STATS["saw_warning"] = True
            name, args = pick_tool(body.get("tools"))
            STATS["tool"] = name
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
            n = STATS["loop_posts"]
            chunks = [{"role": "assistant", "content": "继续逆向 APK，看看实现："},
                      {"tool_calls": [{"index": 0, "id": f"call_loop_{n}", "type": "function",
                                       "function": {"name": name, "arguments": json.dumps(args)}}]}]
            for dl in chunks:
                d = {"id": "x", "object": "chat.completion.chunk", "created": int(time.time()), "model": "smoke-model",
                     "choices": [{"index": 0, "delta": dl, "finish_reason": None}]}
                self.wfile.write(b"data: " + json.dumps(d).encode() + b"\n\n"); self.wfile.flush()
            d = {"id": "x", "object": "chat.completion.chunk", "created": int(time.time()), "model": "smoke-model",
                 "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}],
                 "usage": {"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10}}
            self.wfile.write(b"data: " + json.dumps(d).encode() + b"\n\ndata: [DONE]\n\n"); self.wfile.flush()
            return
        # [38.9.13] scripted tool turns: MEMFIND (memory_write → memory find), GECKOREAD (browser_use engine=gecko), OAUTHTEST
        # (browser_use navigate to Google sign-in → handoff). Step = tool results since the user message; results go to STATS["i3913"].
        trig = next((k for k in I3913_SCRIPTS if k in last_user_text(msgs)), None)
        if trig and body.get("stream"):
            res = []
            for m in reversed(msgs):
                if m.get("role") == "user": break
                if m.get("role") == "tool":
                    c = m.get("content"); res.insert(0, c if isinstance(c, str) else json.dumps(c, ensure_ascii=False))
            names = [t.get("function", {}).get("name") for t in body.get("tools") or []]
            rec = STATS.setdefault("i3913", {}).setdefault(trig, {}); rec["tools"] = names; rec["results"] = [r[:1500] for r in res]
            script = I3913_SCRIPTS[trig]
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
            def emit(delta, fin=None):
                d = {"id": "x", "object": "chat.completion.chunk", "created": int(time.time()), "model": "smoke-model",
                     "choices": [{"index": 0, "delta": delta, "finish_reason": fin}]}
                self.wfile.write(b"data: " + json.dumps(d, ensure_ascii=False).encode() + b"\n\n"); self.wfile.flush()
            if len(res) < len(script) and script[len(res)][0] in names:
                nm, a = script[len(res)]
                emit({"role": "assistant", "content": ""})
                emit({"tool_calls": [{"index": 0, "id": f"call_{trig}_{len(res)}", "type": "function", "function": {"name": nm, "arguments": json.dumps(a, ensure_ascii=False)}}]})
                emit({}, "tool_calls")
            else:
                if len(res) < len(script): rec["missing_tool"] = script[len(res)][0]
                emit({"role": "assistant", "content": f"{trig} 完成（{len(res)} 步）。"}); emit({}, "stop")
            self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
            return
        # [build-35] 消息 cards: Mines asks for Chinese 介绍 / 点评 as a JSON array (non-stream)
        ut = last_user_text(msgs)
        if "中文卡片" in ut and not body.get("stream"):
            STATS["digest_posts"] = STATS.get("digest_posts", 0) + 1
            try:
                items = json.loads(ut[ut.rindex("\n[") + 1:])
            except Exception:
                items = []
            arr = [{"i": it.get("i"), "h": "测试标题：" + str(it.get("src", ""))[:6], "s": "一句话摘要",
                    "intro": "这是 Mines 写的中文介绍：它是一个测试条目，用来检查翻译和介绍。",
                    "review": "点评：对你来说可以看看，因为这是冒烟测试。", "v": "可以看看",
                    "help": "Mines 会帮你总结要点并告诉你下一步。",
                    "d": [{"t": "总结要点", "s": "用大白话说清楚"}]} for it in items]
            text = json.dumps(arr, ensure_ascii=False)
        if body.get("stream"):
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
            for ch in [text[i:i + 24] for i in range(0, len(text), 24)] or [""]:
                d = {"id": "x", "object": "chat.completion.chunk", "created": int(time.time()), "model": "smoke-model",
                     "choices": [{"index": 0, "delta": {"role": "assistant", "content": ch}, "finish_reason": None}]}
                self.wfile.write(b"data: " + json.dumps(d).encode() + b"\n\n"); self.wfile.flush()
            d = {"id": "x", "object": "chat.completion.chunk", "created": int(time.time()), "model": "smoke-model",
                 "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                 "usage": {"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10}}
            self.wfile.write(b"data: " + json.dumps(d).encode() + b"\n\ndata: [DONE]\n\n"); self.wfile.flush()
            return
        self._json({"id": "x", "object": "chat.completion", "created": int(time.time()), "model": "smoke-model",
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10}})

ThreadingHTTPServer(("0.0.0.0", 8088), H).serve_forever()
