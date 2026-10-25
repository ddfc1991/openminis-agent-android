#!/usr/bin/env python3
"""[38.9.15 register_auto] fake sign-up site + fake Mail.tm for the 一键注册 smoke (emulator reaches it at 10.0.2.2:8096).

Sign-up site:
  GET  /signup          e-mail + password + confirm + user name + terms box + 「Sign up」
  POST /signup          stores the account, mails a 6-digit code to the address (fake Mail.tm), shows the code page
  POST /verify          right code -> 「Welcome! Your account has been created」
  GET  /captcha-signup  the same form behind a Cloudflare Turnstile box (stop-and-ask)
  GET  /state           JSON: accounts, verified, mails (for the driver)
Fake Mail.tm (same API shape as api.mail.tm) under /mailtm:
  GET /domains, POST /accounts, POST /token, GET /messages, GET /messages/<id>
"""
import json, random, sys, time, html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8096
BOXES = {}        # address -> password
TOKENS = {}       # token -> address
MAILS = {}        # address -> [msg]
ACCOUNTS = {}     # email -> {"pw":..., "user":..., "code":..., "verified":bool}
LOG = []
COUNT = {"renew": 0, "probe": 0, "verify_link": 0}
LINKS = {}      # token -> address

FORM = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>SmokeCloud sign up</title>
<style>body{{font-family:sans-serif;margin:24px}}input{{display:block;width:90%;margin:8px 0;padding:10px;font-size:16px}}button{{padding:12px 20px;font-size:16px}}</style></head>
<body><h2>Create your SmokeCloud account</h2>{extra}
<form method=post action="{action}">
<label>Email <input type=email name=email required placeholder="you@example.com"></label>
<label>Username <input type=text name=username placeholder="username"></label>
<label>Password <input type=password name=password required></label>
<label>Confirm password <input type=password name=password2 required></label>
<label><input type=checkbox name=terms id=terms required style="display:inline;width:auto"> I agree to the Terms</label>
<button type=button onclick="location.href='/login'">Log in</button>
<button type=submit>Sign up</button>
</form></body></html>"""

TURNSTILE = """<div class="cf-turnstile" style="width:300px;height:65px;border:1px solid #ccc">Verify you are human
<input type=hidden name="cf-turnstile-response" value=""></div>"""

CODE_PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>Check your email</title></head>
<body><h2>Check your email</h2><p>We sent a code to {email}.</p>
<form method=post action="/verify"><input type=hidden name=email value="{email}">
<label>Verification code <input type=text name=code autocomplete="one-time-code" inputmode=numeric maxlength=6></label>
<button type=submit>Verify</button></form></body></html>"""

DONE = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>Welcome</title></head>
<body><h2>Welcome!</h2><p>Your account has been created and your email verified.</p></body></html>"""


def now_iso():
    import datetime
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="text/html; charset=utf-8"):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def js(self, code, obj):
        self.send(code, json.dumps(obj), "application/ld+json")

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode() if n else ""

    def auth(self):
        a = self.headers.get("Authorization", "")
        return TOKENS.get(a[7:]) if a.startswith("Bearer ") else None

    def do_GET(self):
        u = urlparse(self.path)
        p = u.path
        LOG.append("GET " + self.path)
        if p == "/signup":
            return self.send(200, FORM.format(extra="", action="/signup"))
        if p == "/":   # [保活] the service's dashboard: a probe hits it, 续期 presses its free Renew button
            COUNT["probe"] += 1
            return self.send(200, "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>SmokeCloud</title></head><body>"
                "<h2>Your free server</h2><p>Expires in 2 days.</p><form method=post action='/renew'><button type=submit>Upgrade to Pro ($5)</button></form>"
                "<form method=post action='/renew'><button type=submit>Renew for free</button></form></body></html>")
        if p == "/verify-link":
            tok = parse_qs(u.query).get("token", [""])[0]
            if tok in LINKS:
                COUNT["verify_link"] += 1
                ACCOUNTS.setdefault(LINKS[tok], {})["verified"] = True
                return self.send(200, DONE)
            return self.send(400, "<html><body>link expired</body></html>")
        if p == "/mailtm/me":
            a = self.auth()
            return self.js(200, {"address": a}) if a else self.js(401, {"message": "no token"})
        if p == "/captcha-signup":
            return self.send(200, FORM.format(extra=TURNSTILE, action="/signup"))
        if p == "/login":
            return self.send(200, "<html><body>login</body></html>")
        if p == "/state":
            return self.send(200, json.dumps({"count": COUNT, "accounts": ACCOUNTS, "boxes": list(BOXES), "mails": {k: len(v) for k, v in MAILS.items()}, "log": LOG[-60:]}), "application/json")
        if p == "/mailtm/domains":
            return self.js(200, {"hydra:member": [{"id": "d1", "domain": "smoke-mail.test", "isActive": True, "isPrivate": False}]})
        if p == "/mailtm/messages":
            a = self.auth()
            if not a: return self.js(401, {"message": "no token"})
            ms = MAILS.get(a, [])
            return self.js(200, {"hydra:member": [{k: m[k] for k in ("id", "from", "subject", "intro", "createdAt")} for m in ms]})
        if p.startswith("/mailtm/messages/"):
            a = self.auth()
            if not a: return self.js(401, {"message": "no token"})
            mid = p.rsplit("/", 1)[1]
            for m in MAILS.get(a, []):
                if m["id"] == mid: return self.js(200, m)
            return self.js(404, {})
        self.send(404, "not found")

    def do_POST(self):
        u = urlparse(self.path)
        p = u.path
        raw = self.body()
        LOG.append("POST " + self.path)
        if p == "/mailtm/accounts":
            o = json.loads(raw or "{}")
            if o.get("address") in BOXES: return self.js(422, {"detail": "address already used"})
            BOXES[o["address"]] = o["password"]
            return self.js(201, {"id": "a" + str(len(BOXES)), "address": o["address"]})
        if p == "/mailtm/token":
            o = json.loads(raw or "{}")
            if BOXES.get(o.get("address")) != o.get("password"): return self.js(401, {"message": "Invalid credentials."})
            t = "t%06d" % random.randint(0, 999999)
            TOKENS[t] = o["address"]
            return self.js(200, {"token": t, "id": "x"})
        if p == "/seed":   # [整理] a temp box with a confirmation link waiting in it (driver-side, runner only)
            o = json.loads(raw or "{}")
            BOXES[o["address"]] = o["password"]
            tok = "s%06d" % random.randint(0, 999999); LINKS[tok] = o["address"]
            MAILS.setdefault(o["address"], []).insert(0, {"id": "m%d" % random.randint(1, 10**9), "from": {"address": "no-reply@smokecloud.test", "name": "SmokeCloud"},
                "subject": "Confirm your SmokeCloud email", "intro": "Confirm your email", "createdAt": now_iso(),
                "text": "Welcome to SmokeCloud. Open http://10.0.2.2:%d/verify-link?token=%s to confirm your email." % (PORT, tok),
                "html": ["<a href=\"http://10.0.2.2:%d/verify-link?token=%s\">Confirm</a>" % (PORT, tok)]})
            return self.js(200, {"ok": True, "token": tok})
        if p == "/renew":
            COUNT["renew"] += 1
            return self.send(200, "<html><body><h2>Renewed</h2><p>Your server now expires in 7 days.</p></body></html>")
        f = {k: v[0] for k, v in parse_qs(raw).items()}
        if p == "/signup":
            em = f.get("email", "")
            if not em or not f.get("password") or f.get("password") != f.get("password2") or "terms" not in f:
                return self.send(400, FORM.format(extra="<p class=error role=alert>Please fill every field</p>", action="/signup"))
            code = "%06d" % random.randint(100000, 999999)
            ACCOUNTS[em] = {"pw_len": len(f["password"]), "user": f.get("username", ""), "code": code, "verified": False}
            MAILS.setdefault(em, []).insert(0, {"id": "m%d" % random.randint(1, 10**9), "from": {"address": "no-reply@smokecloud.test", "name": "SmokeCloud"},
                "subject": "Your SmokeCloud verification code", "intro": "Your verification code is %s" % code, "createdAt": now_iso(),
                "text": "Your SmokeCloud verification code is %s. It expires in 10 minutes." % code, "html": ["<p>Your verification code is <b>%s</b></p>" % code]})
            return self.send(200, CODE_PAGE.format(email=html.escape(em)))
        if p == "/verify":
            em = f.get("email", ""); a = ACCOUNTS.get(em)
            if a and f.get("code") == a["code"]:
                a["verified"] = True
                return self.send(200, DONE)
            return self.send(400, CODE_PAGE.format(email=html.escape(em)).replace("<form", "<p role=alert>Wrong code</p><form"))
        self.send(404, "not found")


if __name__ == "__main__":
    print("fake signup on", PORT, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
