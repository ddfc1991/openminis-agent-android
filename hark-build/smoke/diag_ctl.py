#!/usr/bin/env python3
"""Mines 远程诊断 control client (runs ON the relay host; talks to the relay's root-only unix socket).
usage: ctl.py list | tail <sid> [n] | send <sid> <cmd> [json-args]   (DIAG_CTL env = socket path)"""
import json, os, socket, sys

CTL = os.environ.get("DIAG_CTL", "/run/mines-diag/ctl.sock")


def call(q):
    s = socket.socket(socket.AF_UNIX); s.settimeout(15); s.connect(CTL)
    s.sendall((json.dumps(q, ensure_ascii=False) + "\n").encode()); buf = b""
    while not buf.endswith(b"\n"):
        b = s.recv(65536)
        if not b: break
        buf += b
    s.close(); return json.loads(buf or b"{}")


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a or a[0] == "list": print(json.dumps(call({"op": "list"}), ensure_ascii=False, indent=1))
    elif a[0] == "tail": print("\n".join(call({"op": "tail", "sid": a[1], "n": int(a[2]) if len(a) > 2 else 50}).get("lines", [])))
    elif a[0] == "send":
        q = {"op": "send", "sid": a[1], "cmd": a[2], "args": json.loads(a[3]) if len(a) > 3 else {}}
        if os.environ.get("DIAG_TEST_BADSIG"): q["test_badsig"] = True
        if os.environ.get("DIAG_TEST_UNLISTED"): q["test_unlisted"] = True
        print(json.dumps(call(q), ensure_ascii=False))
    else: print(__doc__)
