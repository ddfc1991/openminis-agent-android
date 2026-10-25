"""End-to-end test against the deployed mines-brain on the AWS hub, reached the way the phone reaches it: an SSH
session as ubuntu with a local port forward to 127.0.0.1:8790 (no public port). Needs paramiko and the hub key.

  python3 e2e_aws.py [--offline 120] [--skip-kill]

Steps: read the token over SSH → start a loopback fake model on the hub (tests only, removed at the end) → sync it as
the provider → (1) devbox shell + headless browser task, (2) kill -9 the service mid-task → systemd restarts it →
the task resumes and finishes, finished steps not repeated, (3) the client closes its tunnel for --offline seconds while
a task runs, reconnects and catches up from its cursor with no gap. Prints one JSON report line at the end.
"""
import argparse
import json
import os
import socket
import sys
import threading
import time
import urllib.request

import paramiko

HOST = os.environ.get("MB_HUB", "18.143.139.29")
KEY = os.environ.get("MB_HUB_KEY", "/workspace/work/keys/siyun_ed")
FAKE_PORT = 18555


def ssh():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username="ubuntu", pkey=paramiko.Ed25519Key.from_private_key_file(KEY), timeout=20, banner_timeout=20)
    c.get_transport().set_keepalive(15)
    return c


def run(c, cmd, t=60):
    _, o, e = c.exec_command(cmd, timeout=t)
    return o.read().decode() + e.read().decode()


class Forward:
    """ssh -L <local>:127.0.0.1:8790 over a paramiko transport (what the app does with JSch setPortForwardingL)"""

    def __init__(self, client, rport=8790):
        self.t = client.get_transport()
        self.srv = socket.socket()
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(16)
        self.port = self.srv.getsockname()[1]
        self.rport = rport
        self.alive = True
        threading.Thread(target=self.loop, daemon=True).start()

    def loop(self):
        while self.alive:
            try:
                s, _ = self.srv.accept()
            except OSError:
                return
            threading.Thread(target=self.pipe, args=(s,), daemon=True).start()

    def pipe(self, s):
        try:
            ch = self.t.open_channel("direct-tcpip", ("127.0.0.1", self.rport), s.getpeername())
        except Exception:
            s.close(); return

        def a2b(x, y):
            try:
                while True:
                    d = x.recv(65536)
                    if not d:
                        break
                    y.sendall(d)
            except Exception:
                pass
            finally:
                try: y.close()
                except Exception: pass
        threading.Thread(target=a2b, args=(s, ch), daemon=True).start()
        a2b(ch, s)

    def close(self):
        self.alive = False
        self.srv.close()


class API:
    def __init__(self, port, token):
        self.base, self.token = f"http://127.0.0.1:{port}", token

    def req(self, m, p, b=None, timeout=70):
        r = urllib.request.Request(self.base + p, data=json.dumps(b).encode() if b is not None else None, method=m)
        r.add_header("Authorization", "Bearer " + self.token); r.add_header("Content-Type", "application/json")
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(r, timeout=timeout) as x:
            return json.loads(x.read())

    def wait(self, tid, want, secs=120):
        t0 = time.time()
        while time.time() - t0 < secs:
            t = self.req("GET", f"/v1/tasks/{tid}")
            if t["status"] in want:
                return t
            time.sleep(1)
        return t


def script(*s):
    return "SCRIPT:" + json.dumps(list(s), ensure_ascii=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", type=float, default=120)
    ap.add_argument("--skip-kill", action="store_true")
    a = ap.parse_args()
    rep = {}
    c = ssh()
    token = run(c, "cat ~/.mines-brain/token").strip()
    assert token.startswith("mb_"), "no token"
    here = os.path.dirname(os.path.abspath(__file__))
    sf = c.open_sftp(); sf.put(os.path.join(here, "fake_model.py"), "/tmp/mb_fake_model.py"); sf.close()
    run(c, f"sudo systemctl stop mb-e2e-fake 2>/dev/null; sudo systemctl reset-failed mb-e2e-fake 2>/dev/null; sudo systemd-run --unit mb-e2e-fake --uid ubuntu -p MemoryMax=64M python3 /tmp/mb_fake_model.py {FAKE_PORT}; sleep 1; curl -s -m 3 127.0.0.1:{FAKE_PORT}/stats")
    fw = Forward(c)
    api = API(fw.port, token)
    info0 = api.req("GET", "/v1/info")
    prev_providers = info0.get("models")
    rep["info"] = {k: info0[k] for k in ("v", "workers", "up")}
    api.req("POST", "/v1/providers", {"providers": [{"name": "e2e-fake", "base": f"http://127.0.0.1:{FAKE_PORT}/v1", "model": "fake-1"}]})

    # (1) real tools on the hub: devbox shell + headless browser
    t = api.req("POST", "/v1/tasks", {"name": "e2e 工具", "goal": script(
        {"say": "看看容器", "tool": "shell", "args": {"command": "hostname; id -un; uname -m"}},
        {"say": "打开网页", "tool": "browser", "args": {"action": "open", "url": "https://example.com"}},
        {"tool": "finish", "args": {"result": "工具都通"}})})
    t = api.wait(t["id"], ("done", "error"), 90)
    ev = api.req("GET", f"/v1/events?task={t['id']}")["events"]
    outs = [e["data"].get("out", "") for e in ev if e["type"] == "tool_done"]
    rep["tools"] = {"status": t["status"], "shell": outs[0][:80] if outs else "", "browser": outs[1][:80] if len(outs) > 1 else ""}
    assert t["status"] == "done", t
    assert "root" in outs[0], outs
    assert "Example Domain" in outs[1], outs

    # (2) kill -9 mid-task → systemd restarts → resume
    if not a.skip_kill:
        marks = "/tmp/mb_e2e_marks.txt"
        run(c, f"sudo docker exec devbox rm -f {marks}")
        steps = [{"say": f"第{i + 1}步", "tool": "shell", "args": {"command": f"echo start-{i} >> {marks}; sleep 4; echo end-{i} >> {marks}"}} for i in range(5)]
        t = api.req("POST", "/v1/tasks", {"name": "e2e 重启", "goal": script(*steps, {"tool": "finish", "args": {"result": "重启后接着做完了"}})})
        tid = t["id"]
        for _ in range(60):
            if "start-2" in run(c, f"sudo docker exec devbox cat {marks} 2>/dev/null"):
                break
            time.sleep(1)
        pid_before = run(c, "systemctl show mines-brain -p MainPID --value").strip()
        run(c, "sudo systemctl kill -s KILL mines-brain")
        killed_at = time.time()
        before = run(c, f"sudo docker exec devbox cat {marks}").split()
        # tunnel connections drop with the service; the client just retries
        t = None
        for _ in range(60):
            try:
                t = api.req("GET", f"/v1/tasks/{tid}", timeout=10); break
            except Exception:
                time.sleep(1)
        back_after = time.time() - killed_at
        t = api.wait(tid, ("done", "error"), 120)
        after = run(c, f"sudo docker exec devbox cat {marks}").split()
        pid_after = run(c, "systemctl show mines-brain -p MainPID --value").strip()
        ev = api.req("GET", f"/v1/events?task={tid}")["events"]
        rep["kill"] = {"status": t["status"], "result": t["result"], "pid": [pid_before, pid_after], "back_s": round(back_after, 1),
                       "marks_before_kill": before, "marks_after": after,
                       "resume_note": [e["data"].get("line") for e in ev if "重启" in (e["data"].get("line") or "")]}
        assert t["status"] == "done", t
        assert pid_before != pid_after
        assert after.count("start-0") == 1 and after.count("end-0") == 1 and after.count("end-1") == 1, after
        assert after.count("end-4") == 1, after

    # (3) phone offline: close the tunnel AND the SSH session for --offline seconds while a task runs
    n = 6
    per = max(2, int(a.offline / (n + 1)))
    steps = [{"say": f"离线第{i + 1}步", "tool": "shell", "args": {"command": f"sleep {per}; echo off-{i}"}} for i in range(n)]
    t = api.req("POST", "/v1/tasks", {"name": "e2e 离线", "goal": script(*steps, {"tool": "finish", "args": {"result": "手机离线时云端做完了"}}),
                                      "client_ref": f"e2e-off-{int(time.time())}"})
    tid = t["id"]
    time.sleep(2)
    seen = api.req("GET", f"/v1/events?task={tid}&after=0")["events"]
    cur = seen[-1]["id"]
    fw.close(); c.close()                                      # phone gone
    off_at = time.time()
    time.sleep(a.offline)
    c = ssh(); fw = Forward(c); api = API(fw.port, token)      # phone back, new tunnel
    gap = time.time() - off_at
    st_on_return = api.req("GET", f"/v1/tasks/{tid}")
    while True:
        r = api.req("GET", f"/v1/events?task={tid}&after={cur}&wait=5")
        seen += r["events"]; cur = r["cursor"]
        if seen and seen[-1]["type"] == "status" and seen[-1]["data"].get("st") in ("done", "error"):
            break
    full = api.req("GET", f"/v1/events?task={tid}&after=0")["events"]
    rep["offline"] = {"gap_s": round(gap), "status_on_return": st_on_return["status"], "step_on_return": st_on_return["step"],
                      "events_seen": len(seen), "events_total": len(full), "no_gap": [e["id"] for e in seen] == [e["id"] for e in full],
                      "final": seen[-1]["data"].get("line")}
    assert rep["offline"]["no_gap"]
    assert st_on_return["step"] >= 3, "task did not keep running while the phone was away"

    # cleanup: test provider out, the phone's own list back (empty before first sync), fake model stopped
    api.req("POST", "/v1/providers", {"providers": []})
    run(c, "sudo systemctl stop mb-e2e-fake; rm -f /tmp/mb_fake_model.py /tmp/mb_fake.log; sudo docker exec devbox rm -f /tmp/mb_e2e_marks.txt")
    rep["mem"] = run(c, "systemctl show mines-brain -p MemoryCurrent --value").strip()
    rep["prev_models"] = prev_providers
    print("E2E " + json.dumps(rep, ensure_ascii=False))
    fw.close(); c.close()


if __name__ == "__main__":
    main()
