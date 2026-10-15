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
        if self.path.rstrip("/").endswith("/models"):
            return self._json({"object": "list", "data": [{"id": "smoke-model", "object": "model", "owned_by": "smoke"}]})
        self._json({"ok": True})
    def do_POST(self):
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
        if "整理成用户以后能查的知识" in last_user_text(msgs):
            STATS["kb_posts"] = STATS.get("kb_posts", 0) + 1
            text = json.dumps(KB_JSON, ensure_ascii=False)
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
