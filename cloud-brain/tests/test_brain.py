"""mines-brain unit + integration tests (stdlib unittest; no network beyond 127.0.0.1).
  python3 -m unittest discover -s tests -v          (from cloud-brain/)
OFFLINE_SECS (default 8) sets the phone-offline gap of test_offline_client_catches_up; the AWS run uses 120."""
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)
os.environ["MB_SHELL_LOCAL"] = "1"
os.environ["NO_PROXY"] = os.environ["no_proxy"] = "127.0.0.1,localhost"

import fake_model  # noqa: E402
from mines_brain import agent as A  # noqa: E402
from mines_brain import limitguard as lg  # noqa: E402
from mines_brain import model as mdl  # noqa: E402
from mines_brain import tools as T  # noqa: E402
from mines_brain.server import Brain, make_handler  # noqa: E402
from http.server import ThreadingHTTPServer  # noqa: E402

TOKEN = "test-token-0123456789abcdef0123"
FAKE = fake_model.serve()
FAKE_BASE = f"http://127.0.0.1:{FAKE.server_address[1]}"


def script(*steps):
    return "SCRIPT:" + json.dumps(list(steps), ensure_ascii=False)


class Client:
    def __init__(self, port, token=TOKEN):
        self.base = f"http://127.0.0.1:{port}"
        self.token = token

    def req(self, method, path, body=None, timeout=70):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(self.base + path, data=data, method=method)
        r.add_header("Authorization", "Bearer " + self.token)
        r.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(r, timeout=timeout) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def wait_status(self, tid, want, secs=30):
        t0 = time.time()
        while time.time() - t0 < secs:
            _, t = self.req("GET", f"/v1/tasks/{tid}")
            if t.get("status") in want:
                return t
            time.sleep(0.2)
        return t


class BrainCase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.brain = Brain(os.path.join(self.dir, "b.db"), TOKEN, workers=2, poll=0.2)
        self.brain.agent.retry_delays = (0.5, 0.5)
        self.brain.start_workers()
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.brain))
        self.srv.daemon_threads = True
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.c = Client(self.srv.server_address[1])
        self.c.req("POST", "/v1/providers", {"providers": [{"name": "fake", "base": FAKE_BASE + "/v1", "key": "k", "model": "fake-1"}]})
        # fast model retries in tests
        orig = mdl.chat_with_fallback
        self.brain.agent.chat = lambda ps, m, t, log=None: orig(ps, m, t, backoff=(0.05,), sleep=time.sleep, log=log, timeout=10)

    def tearDown(self):
        self.brain.stop.set()
        self.srv.shutdown()
        shutil.rmtree(self.dir, ignore_errors=True)

    def submit(self, goal, **kw):
        code, t = self.c.req("POST", "/v1/tasks", dict(goal=goal, **kw))
        self.assertIn(code, (200, 201), t)
        return t["id"]

    # ── API basics ──
    def test_auth_required(self):
        code, _ = Client(self.srv.server_address[1], token="wrong-token-xxxxxxxxxxxxxxx").req("GET", "/v1/tasks")
        self.assertEqual(code, 401)
        r = urllib.request.urlopen(self.c.base + "/v1/health", timeout=5)
        self.assertEqual(json.loads(r.read())["ok"], True)

    def test_info_never_shows_keys(self):
        _, info = self.c.req("GET", "/v1/info")
        self.assertNotIn("key", json.dumps(info["models"]))
        self.assertEqual(info["models"][0]["model"], "fake-1")

    def test_submit_idempotent_on_client_ref(self):
        a = self.submit(script({"say": "好了"}), client_ref="phone-123")
        b = self.submit(script({"say": "好了"}), client_ref="phone-123")
        self.assertEqual(a, b)

    # ── agent loop ──
    def test_tool_then_finish(self):
        tid = self.submit(script({"say": "先看看", "tool": "shell", "args": {"command": "echo hello-brain"}},
                                 {"tool": "finish", "args": {"result": "做完了：hello"}}))
        t = self.c.wait_status(tid, ("done", "error"))
        self.assertEqual(t["status"], "done", t)
        self.assertEqual(t["result"], "做完了：hello")
        _, ev = self.c.req("GET", f"/v1/events?task={tid}")
        types = [e["type"] for e in ev["events"]]
        for want in ("created", "status", "say", "tool", "tool_done"):
            self.assertIn(want, types)
        self.assertEqual(ev["events"][-1]["data"]["st"], "done")
        out = [e for e in ev["events"] if e["type"] == "tool_done"][0]["data"]["out"]
        self.assertIn("hello-brain", out)
        ids = [e["id"] for e in ev["events"]]
        self.assertEqual(ids, sorted(ids))

    def test_plain_answer_ends_task(self):
        tid = self.submit(script({"say": "答案是 42"}))
        t = self.c.wait_status(tid, ("done",))
        self.assertEqual(t["result"], "答案是 42")

    def test_model_fallback_and_retry(self):
        fake_model.STATE["flaky"] = 0
        self.c.req("POST", "/v1/providers", {"providers": [
            {"name": "dead", "base": FAKE_BASE + "/fail500/v1", "model": "dead-1"},
            {"name": "flaky", "base": FAKE_BASE + "/flaky/v1", "model": "flaky-1"}]})
        tid = self.submit(script({"say": "通了"}))
        t = self.c.wait_status(tid, ("done", "error"))
        self.assertEqual(t["status"], "done", t)
        _, ev = self.c.req("GET", f"/v1/events?task={tid}")
        retries = [e["data"]["model"] for e in ev["events"] if e["type"] == "model_retry"]
        self.assertIn("dead-1", retries)
        self.assertIn("flaky-1", retries)       # 2 retries with back-off on the flaky one, then it answered
        self.assertEqual([e for e in ev["events"] if e["type"] == "say"][0]["data"]["model"], "flaky-1")

    def test_unreachable_gateway_skipped_fast(self):
        mdl._DEAD.clear()
        self.c.req("POST", "/v1/providers", {"providers": [
            {"name": "cn-only", "base": "http://127.0.0.1:1/v1", "model": "dead-cn"},
            {"name": "fake", "base": FAKE_BASE + "/v1", "model": "fake-1"}]})
        t0 = time.time()
        tid = self.submit(script({"say": "绕过了"}))
        t = self.c.wait_status(tid, ("done", "error"))
        self.assertEqual(t["status"], "done", t)
        self.assertLess(time.time() - t0, 10)
        self.assertIn("http://127.0.0.1:1/v1", mdl._DEAD)
        mdl._DEAD.clear()

    def test_unreachable_gateway_skipped_fast(self):
        mdl._DEAD.clear()
        self.c.req("POST", "/v1/providers", {"providers": [
            {"name": "cn-only", "base": "http://127.0.0.1:1/v1", "model": "dead-cn"},
            {"name": "fake", "base": FAKE_BASE + "/v1", "model": "fake-1"}]})
        t0 = time.time()
        tid = self.submit(script({"say": "绕过了"}))
        t = self.c.wait_status(tid, ("done", "error"))
        self.assertEqual(t["status"], "done", t)
        self.assertLess(time.time() - t0, 10)
        self.assertIn("http://127.0.0.1:1/v1", mdl._DEAD)
        mdl._DEAD.clear()

    def test_all_models_down_requeues_then_errors(self):
        self.c.req("POST", "/v1/providers", {"providers": [{"name": "dead", "base": FAKE_BASE + "/fail500/v1", "model": "dead-1"}]})
        tid = self.submit(script({"say": "x"}))
        t = self.c.wait_status(tid, ("error",), secs=40)
        self.assertEqual(t["status"], "error", t)
        _, ev = self.c.req("GET", f"/v1/events?task={tid}")
        self.assertGreaterEqual(sum(e["type"] == "retry" for e in ev["events"]), 2)

    # ── 限制层 ──
    def test_send_needs_phone_approval_then_runs(self):
        tid = self.submit(script({"tool": "http_fetch", "args": {"url": "https://example.com/api", "method": "POST", "body": "{}"}},
                                 {"tool": "finish", "args": {"result": "发出去了"}}))
        t = self.c.wait_status(tid, ("waiting",))
        self.assertEqual(t["status"], "waiting", t)
        self.assertEqual(t["pending"]["kind"], "approve")
        self.assertEqual(t["pending"]["why"], "needs_confirm")
        _, ev = self.c.req("GET", f"/v1/events?task={tid}")
        ask = [e for e in ev["events"] if e["type"] == "ask"][-1]["data"]
        self.assertEqual(ask["act"], "send")
        code, _ = self.c.req("POST", f"/v1/tasks/{tid}/decide", {"allow": True})
        self.assertEqual(code, 200)
        t = self.c.wait_status(tid, ("done", "error"))
        self.assertEqual(t["status"], "done", t)
        _, ev = self.c.req("GET", f"/v1/events?task={tid}")
        self.assertTrue(any(e["type"] == "tool" and e["data"]["tool"] == "http_fetch" for e in ev["events"]))

    def test_deny_tells_model(self):
        tid = self.submit(script({"tool": "browser", "args": {"action": "click", "text": "立即付款"}},
                                 {"tool": "finish", "args": {"result": "没付", "ok": False}}))
        t = self.c.wait_status(tid, ("waiting",))
        self.assertEqual(t["pending"]["act"], "pay")
        self.c.req("POST", f"/v1/tasks/{tid}/reply", {"text": "拒绝"})
        t = self.c.wait_status(tid, ("done", "error"))
        self.assertEqual(t["status"], "error")
        msgs = self.brain.db.msgs(tid)
        self.assertTrue(any(m.get("role") == "tool" and "拒绝" in m.get("content", "") for m in msgs))

    def test_account_not_allowed_by_default_and_remember(self):
        tid = self.submit(script({"tool": "browser", "args": {"action": "type", "selector": "#q", "text": "hi", "account": "github:ddfc1991"}},
                                 {"tool": "finish", "args": {"result": "ok"}}))
        t = self.c.wait_status(tid, ("waiting",))
        self.assertEqual(t["pending"]["why"], "not_allowed")
        self.c.req("POST", f"/v1/tasks/{tid}/decide", {"allow": True, "remember": True})
        self.c.wait_status(tid, ("done", "error"))
        _, pol = self.c.req("GET", "/v1/policy")
        self.assertTrue(pol["accounts"]["github:ddfc1991"]["allowed"])

    def test_kill_switch(self):
        self.c.req("PUT", "/v1/policy", {"killed": True})
        tid = self.submit(script({"tool": "shell", "args": {"command": "echo x"}}, {"tool": "finish", "args": {"result": "ok"}}))
        t = self.c.wait_status(tid, ("waiting",))
        self.assertEqual(t["pending"]["why"], "killed")

    def test_agent_cannot_reach_own_api(self):
        port = self.srv.server_address[1]
        for u in (f"http://127.0.0.1:{port}/v1/policy", "http://localhost:8790/v1/policy", "http://169.254.169.254/latest/meta-data/"):
            self.assertIn("不允许", T.http_fetch({"url": u}))
        self.assertIn("不允许", T.http_fetch({"url": "http://[::1]:8790/v1/policy"}))

    def test_limitguard_rules(self):
        P = {"killed": False, "accounts": {}}
        self.assertEqual(lg.check(P, "", lg.READ), (True, "ok"))
        self.assertEqual(lg.check(P, "", lg.TOUCH), (True, "ok"))
        self.assertEqual(lg.check(P, "", lg.PAY), (False, "needs_confirm"))
        self.assertEqual(lg.check(P, "", lg.SEND), (False, "needs_confirm"))
        self.assertEqual(lg.check(P, "x:y", lg.TOUCH), (False, "not_allowed"))
        self.assertEqual(lg.check(P, "x:y", lg.READ), (True, "ok"))
        P2 = {"accounts": {"x:y": {"allowed": True, "confirm": True, "cap": 2}}}
        self.assertEqual(lg.check(P2, "x:y", lg.TOUCH), (True, "ok"))
        self.assertEqual(lg.check(P2, "x:y", lg.POST), (False, "needs_confirm"))
        self.assertEqual(lg.check(P2, "x:y", lg.POST, approved=True), (True, "ok"))
        self.assertEqual(lg.check(P2, "x:y", lg.TOUCH, used_today=2), (False, "cap"))
        self.assertEqual(lg.act_of("browser", {"action": "click", "text": "发送验证码"}), lg.TOUCH)
        self.assertEqual(lg.act_of("browser", {"action": "click", "text": "发送"}), lg.SEND)
        self.assertEqual(lg.act_of("browser", {"action": "click", "text": "Delete repository"}), lg.DELETE)
        self.assertEqual(lg.act_of("http_fetch", {"url": "https://a", "method": "GET"}), lg.READ)
        self.assertEqual(lg.act_of("http_fetch", {"url": "https://a/checkout", "method": "POST"}), lg.PAY)
        # the agent's tools have no way to write the policy
        self.assertFalse(any(s["function"]["name"] in ("policy", "set_policy", "limits") for s in T.SCHEMAS))

    # ── phone-bound tools ──
    def test_ask_user_and_handoff(self):
        tid = self.submit(script({"tool": "ask_user", "args": {"question": "用哪个邮箱？"}},
                                 {"tool": "handoff", "args": {"url": "https://example.com/captcha", "reason": "滑块验证"}},
                                 {"tool": "finish", "args": {"result": "注册好了"}}))
        t = self.c.wait_status(tid, ("waiting",))
        self.assertEqual(t["pending"]["kind"], "ask")
        self.c.req("POST", f"/v1/tasks/{tid}/reply", {"text": "a@b.com"})
        t = self.c.wait_status(tid, ("waiting",), secs=10)
        for _ in range(20):
            if t["pending"].get("kind") == "handoff":
                break
            time.sleep(0.2)
            _, t = self.c.req("GET", f"/v1/tasks/{tid}")
        self.assertEqual(t["pending"]["kind"], "handoff")
        self.assertEqual(t["pending"]["url"], "https://example.com/captcha")
        self.c.req("POST", f"/v1/tasks/{tid}/reply", {"text": "好了"})
        t = self.c.wait_status(tid, ("done",))
        self.assertEqual(t["result"], "注册好了")
        msgs = self.brain.db.msgs(tid)
        self.assertTrue(any(m.get("role") == "tool" and "a@b.com" in m.get("content", "") for m in msgs))

    def test_followup_reopens_done_task(self):
        tid = self.submit(script({"say": "第一次"}, {"say": "第二次"}))
        self.c.wait_status(tid, ("done",))
        self.c.req("POST", f"/v1/tasks/{tid}/reply", {"text": "再来"})
        time.sleep(0.5)
        t = self.c.wait_status(tid, ("done",))
        self.assertEqual(t["result"], "第二次")

    def test_cancel(self):
        tid = self.submit(script({"tool": "shell", "args": {"command": "sleep 2"}}, {"tool": "shell", "args": {"command": "sleep 2"}},
                                 {"tool": "finish", "args": {"result": "x"}}))
        time.sleep(0.8)
        self.c.req("POST", f"/v1/tasks/{tid}/cancel", {})
        t = self.c.wait_status(tid, ("cancelled",), secs=10)
        self.assertEqual(t["status"], "cancelled")

    def test_long_poll_wakes_on_event(self):
        _, ev = self.c.req("GET", "/v1/events?after=0")
        cur = ev["cursor"]
        out = {}

        def poll():
            out["r"] = self.c.req("GET", f"/v1/events?after={cur}&wait=20")
        th = threading.Thread(target=poll)
        th.start()
        time.sleep(0.5)
        t0 = time.time()
        self.submit(script({"say": "x"}))
        th.join(25)
        self.assertLess(time.time() - t0, 5)
        self.assertTrue(out["r"][1]["events"])

    def test_offline_client_catches_up(self):
        """the phone reads to a cursor, goes offline for OFFLINE_SECS while the task keeps running, comes back and gets
        every event after its cursor (no gap, no duplicate), ending in done"""
        gap = float(os.environ.get("OFFLINE_SECS", "8"))
        n = 4
        steps = [{"say": f"第{i}步", "tool": "shell", "args": {"command": f"sleep {gap / (n + 1):.2f}; echo s{i}"}} for i in range(n)]
        tid = self.submit(script(*steps, {"tool": "finish", "args": {"result": "离线期间做完了"}}))
        seen = []
        _, ev = self.c.req("GET", f"/v1/events?task={tid}&after=0")
        seen += ev["events"]
        cur = ev["cursor"]
        time.sleep(gap + 2)                       # phone offline
        _, t = self.c.req("GET", f"/v1/tasks/{tid}")
        self.assertEqual(t["status"], "done", "task did not keep running while the client was away")
        while True:
            _, ev = self.c.req("GET", f"/v1/events?task={tid}&after={cur}")
            seen += ev["events"]
            cur = ev["cursor"]
            if not ev["more"]:
                break
        _, full = self.c.req("GET", f"/v1/events?task={tid}&after=0")
        self.assertEqual([e["id"] for e in seen], [e["id"] for e in full["events"]])
        self.assertEqual(seen[-1]["data"]["st"], "done")


class CompactCase(unittest.TestCase):
    def test_compact_keeps_pairs(self):
        msgs = [{"role": "system", "content": "s"}, {"role": "user", "content": "g"}]
        for i in range(60):
            msgs.append({"role": "assistant", "content": "x" * 500, "tool_calls": [{"id": f"c{i}", "type": "function", "function": {"name": "shell", "arguments": "{}"}}]})
            msgs.append({"role": "tool", "tool_call_id": f"c{i}", "content": "y" * 2000})
        out = A.compact(msgs, budget=20000)
        self.assertLess(len(out), len(msgs))
        self.assertEqual(out[0]["role"], "system")
        for i, m in enumerate(out):
            if m["role"] == "tool":
                self.assertIn(out[i - 1]["role"], ("assistant", "tool"))
        self.assertEqual(A.unanswered(out), [])


class RestartCase(unittest.TestCase):
    """kill -9 the service in the middle of a task; the restarted service resumes from the last finished step"""

    def test_kill_and_resume(self):
        d = tempfile.mkdtemp()
        tok = os.path.join(d, "token")
        open(tok, "w").write(TOKEN)
        marks = os.path.join(d, "marks.txt")
        port = 18790 + os.getpid() % 500
        env = dict(os.environ, MB_DB=os.path.join(d, "b.db"), MB_TOKEN_FILE=tok, MB_PORT=str(port), MB_SHELL_LOCAL="1", PYTHONPATH=ROOT)

        def start():
            p = subprocess.Popen([sys.executable, "-m", "mines_brain.server"], env=env, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for _ in range(50):
                try:
                    urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/health", timeout=1)
                    return p
                except Exception:
                    time.sleep(0.1)
            raise RuntimeError("brain did not start")

        p = start()
        c = Client(port)
        c.req("POST", "/v1/providers", {"providers": [{"name": "fake", "base": FAKE_BASE + "/v1", "model": "fake-1"}]})
        steps = [{"tool": "shell", "args": {"command": f"echo start-{i} >> {marks}; sleep 1.5; echo end-{i} >> {marks}"}} for i in range(4)]
        _, t = c.req("POST", "/v1/tasks", {"goal": script(*steps, {"tool": "finish", "args": {"result": "重启后做完了"}})})
        tid = t["id"]
        # wait until step 2's command started, then kill -9
        for _ in range(100):
            if os.path.exists(marks) and "start-1" in open(marks).read():
                break
            time.sleep(0.1)
        p.send_signal(signal.SIGKILL)
        p.wait()
        before = open(marks).read()
        self.assertIn("end-0", before)
        self.assertNotIn("end-1", before)
        p = start()
        try:
            t = c.wait_status(tid, ("done", "error"), secs=40)
            self.assertEqual(t["status"], "done", t)
            self.assertEqual(t["result"], "重启后做完了")
            lines = open(marks).read().split()
            self.assertEqual(lines.count("start-0"), 1, lines)      # the finished step was not run again
            self.assertEqual(lines.count("end-0"), 1, lines)
            self.assertEqual(lines.count("end-1"), 1, lines)        # the cut step ran to completion once
            self.assertEqual(lines.count("end-3"), 1, lines)
            _, ev = c.req("GET", f"/v1/events?task={tid}")
            self.assertTrue(any("重启" in (e["data"].get("line") or "") for e in ev["events"]))
        finally:
            p.send_signal(signal.SIGTERM)
            p.wait(10)
            shutil.rmtree(d, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
