"""Deterministic OpenAI-compatible model for tests. The task goal is `SCRIPT:<json list>`; each element is the reply
for the next assistant turn: {"tool": name, "args": {...}} | {"say": "..."} (plain answer = end) | {"tools": [..]}.
Paths: /v1 normal, /fail500/v1 always 500, /flaky/v1 fails the first 2 calls, /slow/v1 sleeps 3 s.
Run standalone: python3 fake_model.py <port>"""
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"calls": 0, "flaky": 0, "by_path": {}}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/stats":
            return self._send(200, STATE)
        self._send(200, {"ok": True})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        STATE["calls"] += 1
        STATE["by_path"][self.path] = STATE["by_path"].get(self.path, 0) + 1
        if self.path.startswith("/fail500"):
            return self._send(500, {"error": "boom"})
        if self.path.startswith("/flaky"):
            STATE["flaky"] += 1
            if STATE["flaky"] <= 2:
                return self._send(503, {"error": "busy"})
        if self.path.startswith("/slow"):
            time.sleep(3)
        msgs = body.get("messages", [])
        goal = next((m["content"] for m in msgs if m["role"] == "user"), "")
        script = json.loads(goal.split("SCRIPT:", 1)[1]) if "SCRIPT:" in goal else [{"say": "OK"}]
        turn = sum(1 for m in msgs if m["role"] == "assistant")
        step = script[turn] if turn < len(script) else {"say": "脚本结束"}
        msg = {"role": "assistant", "content": step.get("say", f"第 {turn + 1} 步")}
        tl = step.get("tools") or ([step] if "tool" in step else [])
        if tl:
            msg["tool_calls"] = [{"id": f"call_{turn}_{i}", "type": "function",
                                  "function": {"name": t["tool"], "arguments": json.dumps(t.get("args", {}), ensure_ascii=False)}}
                                 for i, t in enumerate(tl)]
        self._send(200, {"choices": [{"message": msg, "finish_reason": "tool_calls" if tl else "stop"}]})


def serve(port=0):
    s = ThreadingHTTPServer(("127.0.0.1", port), H)
    s.daemon_threads = True
    threading.Thread(target=s.serve_forever, daemon=True).start()
    return s


if __name__ == "__main__":
    s = ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H)
    s.serve_forever()
