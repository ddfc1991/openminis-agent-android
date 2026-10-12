#!/usr/bin/env python3
"""Tiny OpenAI-compatible endpoint for the emulator smoke test (10.0.2.2:8088)."""
import json, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): print("fake-openai", self.command, self.path, flush=True)
    def _json(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            return self._json({"object": "list", "data": [{"id": "smoke-model", "object": "model", "owned_by": "smoke"}]})
        self._json({"ok": True})
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        text = "你好，我是测试模型。"
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
