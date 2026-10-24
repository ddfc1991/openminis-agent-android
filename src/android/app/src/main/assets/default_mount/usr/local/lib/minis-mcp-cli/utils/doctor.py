"""doctor.py — `minis-mcp-cli doctor`: why does an MCP server not answer?

[Mines-mcp-29] Starts each enabled server in its own process (not through the
daemon, so one stuck server can't hide the others), all in parallel, sends
`initialize`, and reports per server: ok + milliseconds, or the reason
(command missing, npm download failing, crashed + its stderr, timed out).
Bounded: returns within --timeout + 5 s whatever happens.
"""

import json
import os
import shutil
import subprocess
import sys
import threading
import time

from utils import config, launch
from transport.errors import expand_env


def _check_stdio(name, cfg, timeout, out):
    t0 = time.time()
    command = cfg.get("command", "")
    args = cfg.get("args") or []
    row = {"server": name, "type": "stdio", "installed": launch.installed(command, args)}
    if not shutil.which(os.path.basename(command)) and not os.path.exists(command):
        row.update(ok=False, reason="命令不存在：%s（第一次调用时会自动装 nodejs/npm；也可运行 apk add nodejs npm）" % command)
        out[name] = row
        return
    cmd, argv = launch.resolve(command, args)
    row["launch"] = (cmd + " " + " ".join(argv))[:200]
    env = dict(os.environ)
    for k, v in (cfg.get("env") or {}).items():
        env[k] = expand_env(v)
    errf = launch.open_stderr(name)
    try:
        p = subprocess.Popen([cmd] + argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=errf, env=env, text=True, bufsize=1, start_new_session=True)
    except OSError as exc:
        row.update(ok=False, reason="启动失败：%s" % exc)
        out[name] = row
        return
    box = {}

    def reader():
        try:
            for raw in p.stdout:
                try:
                    msg = json.loads(raw.strip() or "null")
                except ValueError:
                    continue
                if isinstance(msg, dict) and msg.get("id") == 1:
                    box["msg"] = msg
                    return
        except (OSError, ValueError):
            pass

    try:
        p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "minis-mcp-doctor", "version": "1"}}}) + "\n")
        p.stdin.flush()
    except OSError:
        pass
    th = threading.Thread(target=reader, daemon=True)
    th.start()
    th.join(timeout)
    ms = int((time.time() - t0) * 1000)
    if "msg" in box:
        info = (box["msg"].get("result") or {}).get("serverInfo") or {}
        row.update(ok=True, ms=ms, reason="正常（%s %s）" % (info.get("name", ""), info.get("version", "")))
    elif p.poll() is not None:
        row.update(ok=False, ms=ms, reason="进程退出了（rc=%s）：%s" % (p.returncode, launch.explain(name, cfg)))
    else:
        row.update(ok=False, ms=ms, reason="%s 秒内没有应答：%s" % (timeout, launch.explain(name, cfg)))
    try:
        p.kill()
    except OSError:
        pass
    out[name] = row


def _check_http(name, cfg, timeout, out):
    t0 = time.time()
    row = {"server": name, "type": "http", "url": cfg.get("url")}
    try:
        from transport.http import HTTPTransport
        HTTPTransport(cfg, name).ping()
        row.update(ok=True, ms=int((time.time() - t0) * 1000), reason="正常")
    except Exception as exc:  # noqa: BLE001
        row.update(ok=False, ms=int((time.time() - t0) * 1000), reason=str(getattr(exc, "message", exc))[:300])
    out[name] = row


def run(args, pretty, emit):
    timeout = 60
    if "--timeout" in args:
        i = args.index("--timeout")
        try:
            timeout = max(3, min(600, int(args[i + 1])))
        except (IndexError, ValueError):
            pass
        del args[i:i + 2]
    servers = config.get_servers()
    names = [a for a in args if not a.startswith("-")] or [n for n, c in servers.items() if c.get("enabled", True)]
    out = {}
    threads = []
    for n in names:
        cfg = servers.get(n)
        if cfg is None:
            out[n] = {"server": n, "ok": False, "reason": "没有这个服务器"}
            continue
        fn = _check_stdio if config.is_stdio(cfg) else _check_http
        t = threading.Thread(target=fn, args=(n, cfg, timeout, out), daemon=True)
        t.start()
        threads.append((n, t))
    deadline = time.time() + timeout + 5
    for n, t in threads:
        t.join(max(0.1, deadline - time.time()))
        if n not in out:
            out[n] = {"server": n, "ok": False, "reason": "检查本身超时"}
    rows = [out[n] for n in names if n in out]
    if not pretty:
        for r in rows:
            sys.stderr.write("%s %-10s %s%s\n" % ("✓" if r.get("ok") else "✗", r["server"], r.get("reason", ""),
                                                  (" (%d ms)" % r["ms"]) if "ms" in r else ""))
    emit({"servers": rows, "ok": all(r.get("ok") for r in rows)}, pretty)
