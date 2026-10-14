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

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): print("fake-openai", self.command, self.path, flush=True)
    def _json(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
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
            for ch in [text[:3], text[3:]]:
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
