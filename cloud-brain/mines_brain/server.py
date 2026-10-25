"""mines-brain HTTP API (127.0.0.1 only; the phone reaches it through its SSH tunnel's local port forward).

Auth: `Authorization: Bearer <token>`; the token file is created at install time (0600) and the app reads it once
over its own SSH session, then keeps it in the app vault.

  GET  /v1/health                         {"ok":true,"v":...}   (no auth; says nothing else)
  GET  /v1/info                           version, workers, queue, models (no keys), policy
  POST /v1/providers   {"providers":[{name,base,key,model}...]}   replace the model list (from the phone)
  GET  /v1/policy | PUT /v1/policy        the user's limits (phone only; the agent has no route here)
  POST /v1/tasks       {goal,name?,client_ref?,max_steps?,models?}  idempotent on client_ref
  GET  /v1/tasks       recent tasks
  GET  /v1/tasks/<id>  one task (+ pending ask)
  GET  /v1/events?after=<cursor>&task=<id>&wait=<s>   long poll; events are never lost: the cursor is the resume point
  POST /v1/tasks/<id>/reply    {text, call_id?}        answer an ask / handoff / phone request, or add an instruction
  POST /v1/tasks/<id>/decide   {allow:bool, call_id?, remember?}   限制层 同意 / 拒绝 (remember=allow this account)
  POST /v1/tasks/<id>/cancel
"""
import hmac
import json
import os
import re
import signal
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import model as mdl
from .agent import Agent
from .db import DB

VERSION = "1.0.0"

YES = re.compile(r"^\s*(同意|允许|可以|好的?|行|ok|okay|yes|y|批准|继续|确认)\s*[。.!！]?\s*$", re.I)
NO = re.compile(r"^\s*(拒绝|不行|不要|不同意|别|no|n|取消)\s*[。.!！]?\s*$", re.I)


class Brain:
    def __init__(self, db_path, token, workers=2, poll=1.0):
        self.db = DB(db_path)
        self.token = token
        self.agent = Agent(self.db, self.providers, self.policy)
        self.nworkers = workers
        self.poll = poll
        self.stop = threading.Event()
        self.threads = []
        self.started = time.time()
        n = self.db.recover()
        if n:
            for t in self.db.tasks(200):
                if t["status"] == "queued" and t["step"]:
                    self.db.event(t["id"], "note", {"line": f"云端服务重启，从第 {t['step']} 步接着做"})

    def providers(self):
        ps = self.db.kv_get("providers", [])
        env = os.environ.get("MB_PROVIDERS_FILE")
        if not ps and env and os.path.exists(env):
            try:
                ps = json.load(open(env))
            except Exception:
                ps = []
        return ps

    def policy(self):
        return self.db.kv_get("policy", {"killed": False, "accounts": {}, "confirm_off_account": True})

    # ── workers ──
    def worker(self, name):
        while not self.stop.is_set():
            tid = None
            try:
                tid = self.db.claim(name)
            except Exception as e:
                print("claim error", e, file=sys.stderr, flush=True)
            if not tid:
                self.db.wait_change(self.poll)
                continue
            try:
                self.agent.run(tid)
            except Exception as e:
                import traceback
                traceback.print_exc()
                # an unexpected crash of one task never takes the worker down; it goes back to the queue once
                t = self.db.task(tid) or {}
                if (t.get("attempts") or 0) < 3:
                    self.db.update(tid, status="queued", attempts=(t.get("attempts") or 0) + 1, next_at=time.time() + 20)
                    self.db.event(tid, "retry", {"line": f"内部出错，20 秒后重试：{type(e).__name__}"})
                else:
                    self.db.update(tid, status="error", result=f"内部出错：{e}")
                    self.db.event(tid, "status", {"st": "error", "line": f"内部出错：{e}"})

    def start_workers(self):
        for i in range(self.nworkers):
            t = threading.Thread(target=self.worker, args=(f"w{i}",), daemon=True)
            t.start()
            self.threads.append(t)

    # ── views ──
    def task_view(self, t):
        if not t:
            return None
        pend = json.loads(t.get("pending") or "{}")
        pend.pop("args", None) if pend.get("kind") != "approve" else None
        return {"id": t["id"], "name": t["name"], "goal": t["goal"], "status": t["status"], "step": t["step"],
                "max_steps": t["max_steps"], "result": t["result"], "pending": pend, "created": t["created"],
                "updated": t["updated"], "client_ref": t["client_ref"]}


def make_handler(brain):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "mines-brain"

        def log_message(self, fmt, *a):
            if os.environ.get("MB_ACCESS_LOG") == "1":
                sys.stderr.write("%s %s\n" % (self.command, self.path.split("?")[0]))

        def _send(self, code, obj):
            b = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(b)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(b)

        def _auth(self):
            h = self.headers.get("Authorization", "")
            ok = h.startswith("Bearer ") and hmac.compare_digest(h[7:].strip(), brain.token)
            if not ok:
                self._send(401, {"error": "unauthorized"})
            return ok

        def _body(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > 2_000_000:
                return None
            try:
                return json.loads(self.rfile.read(n) or b"{}") if n else {}
            except ValueError:
                return None

        def do_GET(self):
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            if u.path == "/v1/health":
                return self._send(200, {"ok": True, "v": VERSION})
            if not self._auth():
                return
            if u.path == "/v1/info":
                ts = brain.db.tasks(200)
                return self._send(200, {"v": VERSION, "workers": brain.nworkers, "up": int(time.time() - brain.started),
                                        "queued": sum(t["status"] == "queued" for t in ts), "running": sum(t["status"] == "running" for t in ts),
                                        "waiting": sum(t["status"] == "waiting" for t in ts), "cursor": brain.db.last_event_id(),
                                        "models": [mdl.public(p) for p in brain.providers()], "policy": brain.policy()})
            if u.path == "/v1/policy":
                return self._send(200, brain.policy())
            if u.path == "/v1/tasks":
                return self._send(200, {"tasks": [brain.task_view(t) for t in brain.db.tasks(int(q.get("limit", 50)))]})
            m = re.fullmatch(r"/v1/tasks/([A-Za-z0-9_\-]+)", u.path)
            if m:
                t = brain.task_view(brain.db.task(m.group(1)))
                return self._send(200 if t else 404, t or {"error": "no such task"})
            if u.path == "/v1/events":
                after = int(q.get("after", 0) or 0)
                task = q.get("task") or None
                wait = max(0.0, min(float(q.get("wait", 0) or 0), 55.0))
                t0 = time.time()
                evs = brain.db.events(task, after)
                while not evs and time.time() - t0 < wait:
                    brain.db.wait_change(min(2.0, wait - (time.time() - t0)) or 0.1)
                    evs = brain.db.events(task, after)
                cur = evs[-1]["id"] if evs else after
                return self._send(200, {"events": evs, "cursor": cur, "more": len(evs) >= 200})
            return self._send(404, {"error": "not found"})

        def do_PUT(self):
            if not self._auth():
                return
            u = urlparse(self.path)
            b = self._body()
            if b is None:
                return self._send(400, {"error": "bad json"})
            if u.path == "/v1/policy":
                pol = {"killed": bool(b.get("killed", False)), "accounts": {}, "confirm_off_account": bool(b.get("confirm_off_account", True))}
                for k, v in (b.get("accounts") or {}).items():
                    pol["accounts"][str(k)] = {"allowed": bool(v.get("allowed")), "confirm": bool(v.get("confirm", True)), "cap": int(v.get("cap", 30))}
                brain.db.kv_set("policy", pol)
                return self._send(200, pol)
            return self._send(404, {"error": "not found"})

        def do_POST(self):
            if not self._auth():
                return
            u = urlparse(self.path)
            b = self._body()
            if b is None:
                return self._send(400, {"error": "bad json"})
            if u.path == "/v1/providers":
                ps = []
                for p in b.get("providers") or []:
                    if p.get("base") and p.get("model"):
                        ps.append({"name": str(p.get("name") or p["model"])[:60], "base": str(p["base"]), "key": str(p.get("key") or ""),
                                   "model": str(p["model"]), "ua": str(p.get("ua") or "")})
                brain.db.kv_set("providers", ps)
                return self._send(200, {"ok": True, "count": len(ps), "models": [mdl.public(p) for p in ps]})
            if u.path == "/v1/tasks":
                goal = str(b.get("goal") or "").strip()
                if not goal:
                    return self._send(400, {"error": "goal required"})
                ref = str(b.get("client_ref") or "")[:80]
                old = brain.db.by_ref(ref)
                if old:   # a retried submit after a dropped connection: same task, never a duplicate
                    return self._send(200, brain.task_view(old))
                tid = brain.db.create_task(goal, str(b.get("name") or "")[:40], b.get("models") or [], int(b.get("max_steps") or 80), ref)
                return self._send(201, brain.task_view(brain.db.task(tid)))
            m = re.fullmatch(r"/v1/tasks/([A-Za-z0-9_\-]+)/(reply|decide|cancel)", u.path)
            if not m:
                return self._send(404, {"error": "not found"})
            tid, verb = m.groups()
            t = brain.db.task(tid)
            if not t:
                return self._send(404, {"error": "no such task"})
            pend = json.loads(t.get("pending") or "{}")
            if verb == "cancel":
                brain.agent.cancel.add(tid) if t["status"] == "running" else None
                if t["status"] != "running":
                    brain.db.update(tid, status="cancelled", pending="")
                    brain.db.event(tid, "status", {"st": "cancelled", "line": "已取消"})
                return self._send(200, {"ok": True})
            if verb == "decide":
                if t["status"] != "waiting" or pend.get("kind") != "approve":
                    return self._send(409, {"error": "nothing to decide"})
                allow = bool(b.get("allow"))
                if allow and b.get("remember") and pend.get("account"):
                    pol = brain.policy()
                    pol.setdefault("accounts", {})[pend["account"]] = {"allowed": True, "confirm": True, "cap": 30}
                    brain.db.kv_set("policy", pol)
                brain.agent.decide(tid, b.get("call_id") or pend.get("call_id"), allow, str(b.get("note") or ""))
                return self._send(200, brain.task_view(brain.db.task(tid)))
            # reply
            text = str(b.get("text") or "").strip()
            if not text:
                return self._send(400, {"error": "text required"})
            if t["status"] == "waiting" and pend.get("kind") == "approve":
                if YES.match(text):
                    brain.agent.decide(tid, pend.get("call_id"), True)
                elif NO.match(text):
                    brain.agent.decide(tid, pend.get("call_id"), False)
                else:
                    brain.agent.decide(tid, pend.get("call_id"), False, "（用户回复：" + text + "）")
                    brain.db.push_inbox(tid, text)
            elif t["status"] == "waiting" and pend.get("kind") in ("ask", "handoff", "phone"):
                brain.agent.answer(tid, pend.get("call_id"), text)
            elif t["status"] in ("done", "error", "cancelled"):
                brain.agent.answer(tid, None, text)    # a follow-up reopens the task with its whole history
            else:
                brain.db.push_inbox(tid, text)
                brain.db.event(tid, "user_msg", {"text": text[:400], "line": "已收到，下一步会看到"})
            return self._send(200, brain.task_view(brain.db.task(tid)))

    return H


def main():
    db_path = os.environ.get("MB_DB", "/var/lib/mines-brain/brain.db")
    token_file = os.environ.get("MB_TOKEN_FILE", "/var/lib/mines-brain/token")
    token = open(token_file).read().strip()
    if len(token) < 24:
        raise SystemExit("token too short")
    host = os.environ.get("MB_HOST", "127.0.0.1")
    if host not in ("127.0.0.1", "::1", "localhost"):
        raise SystemExit("mines-brain listens on loopback only")
    port = int(os.environ.get("MB_PORT", "8790"))
    brain = Brain(db_path, token, workers=int(os.environ.get("MB_WORKERS", "2")))
    brain.start_workers()
    srv = ThreadingHTTPServer((host, port), make_handler(brain))
    srv.daemon_threads = True

    def bye(*_):
        brain.stop.set()
        threading.Thread(target=srv.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, bye)
    print(f"mines-brain {VERSION} on {host}:{port}, db {db_path}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
