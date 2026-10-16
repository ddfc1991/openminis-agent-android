#!/usr/bin/env python3
"""[Mines-38.8] tiny plain-TCP IMAP server for the 邮箱 smoke: user box1@smoke.test / code goodcode. Read-only commands."""
import socketserver, sys, base64
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 1143
def msg(i, frm, subj, body, seen):
    sb = "=?UTF-8?B?" + base64.b64encode(subj.encode()).decode() + "?="
    raw = (f"From: {frm}\r\nSubject: {sb}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
           + base64.b64encode(body.encode()).decode() + "\r\n").encode()
    return (i, raw, seen)
MSGS = [msg(1, "Alice <alice@example.com>", "周报：38.8 测试进度", "邮箱聚合测试第一封，已读。", True),
        msg(2, "网易邮箱 <service@163.com>", "IMAP 服务已开启", "这是 fake IMAP 服务器的第二封，未读。", False),
        msg(3, "GitHub <noreply@github.com>", "Run succeeded: mines-388", "第三封，未读，最新。", False)]
class H(socketserver.StreamRequestHandler):
    def w(self, s): self.wfile.write((s + "\r\n").encode()); self.wfile.flush()
    def handle(self):
        self.w("* OK fake IMAP ready"); authed = False
        while True:
            line = self.rfile.readline()
            if not line: return
            parts = line.decode(errors="replace").strip().split(" ", 2)
            if len(parts) < 2: continue
            tag, cmd = parts[0], parts[1].upper(); rest = parts[2] if len(parts) > 2 else ""
            print("C:", tag, cmd, rest[:60] if cmd != "LOGIN" else "(login)", flush=True)
            if cmd == "ID": self.w('* ID NIL'); self.w(f"{tag} OK ID completed")
            elif cmd == "LOGIN":
                ok = '"goodcode"' in rest and "@smoke.test" in rest
                authed = ok; self.w(f"{tag} OK LOGIN completed" if ok else f"{tag} NO LOGIN Login error password error")
            elif cmd in ("EXAMINE", "SELECT"):
                if not authed: self.w(f"{tag} NO not authenticated"); continue
                self.w(f"* {len(MSGS)} EXISTS"); self.w("* 0 RECENT"); self.w(f"{tag} OK [READ-ONLY] done")
            elif cmd == "STATUS": self.w(f"* STATUS INBOX (UNSEEN {sum(1 for m in MSGS if not m[2])})"); self.w(f"{tag} OK STATUS done")
            elif cmd == "FETCH":
                for i, raw, seen in MSGS:
                    fl = "\\Seen" if seen else ""
                    self.wfile.write(f'* {i} FETCH (UID {100+i} FLAGS ({fl}) INTERNALDATE "16-Oct-2026 0{i}:00:00 +0800" BODY[]<0> {{{len(raw)}}}\r\n'.encode() + raw + b")\r\n")
                self.w(f"{tag} OK FETCH done")
            elif cmd == "LOGOUT": self.w("* BYE"); self.w(f"{tag} OK bye"); return
            else: self.w(f"{tag} BAD unknown")
class S(socketserver.ThreadingTCPServer): allow_reuse_address = True; daemon_threads = True
if __name__ == "__main__":
    S(("0.0.0.0", PORT), H).serve_forever()
