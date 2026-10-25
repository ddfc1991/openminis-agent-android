"""Durable state: one SQLite file (WAL). Every agent step is committed before the next one starts, so a crash or a
`systemctl restart` resumes from the last finished step. Tables:

  tasks      one row per task (status, step, model list, policy snapshot, pending ask)
  msgs       the agent transcript (OpenAI chat messages), append-only, ordered by seq
  events     what the phone sees; the global autoincrement id is the resume cursor
  toolruns   started/done marker per tool call id (a send/post/delete/pay that was cut mid-call is never re-run blind)
  approvals  the user's one-shot 同意/拒绝 per tool call id
  inbox      user messages that arrived while the task was running (drained before the next model call)
  counters   per-account daily action counts (LimitGuard cap)
  kv         policy (the user's limits, phone-owned), misc
"""
import json
import os
import sqlite3
import threading
import time
import uuid

SCHEMA = """
CREATE TABLE IF NOT EXISTS tasks(
  id TEXT PRIMARY KEY, name TEXT, goal TEXT, status TEXT, created REAL, updated REAL,
  step INTEGER DEFAULT 0, max_steps INTEGER DEFAULT 80, models TEXT, result TEXT DEFAULT '',
  pending TEXT DEFAULT '', attempts INTEGER DEFAULT 0, next_at REAL DEFAULT 0, client_ref TEXT DEFAULT '',
  browser TEXT DEFAULT '', lease TEXT DEFAULT '');
CREATE TABLE IF NOT EXISTS msgs(task_id TEXT, seq INTEGER, body TEXT, PRIMARY KEY(task_id, seq));
CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT, ts REAL, type TEXT, data TEXT);
CREATE INDEX IF NOT EXISTS ev_task ON events(task_id, id);
CREATE TABLE IF NOT EXISTS toolruns(task_id TEXT, call_id TEXT, act TEXT, state TEXT, result TEXT, PRIMARY KEY(task_id, call_id));
CREATE TABLE IF NOT EXISTS approvals(task_id TEXT, call_id TEXT, decision TEXT, note TEXT, used INTEGER DEFAULT 0, PRIMARY KEY(task_id, call_id));
CREATE TABLE IF NOT EXISTS inbox(id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT, text TEXT, used INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS counters(key TEXT PRIMARY KEY, n INTEGER);
CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT);
"""

ACTIVE = ("queued", "running")
OPEN = ("queued", "running", "waiting")
FINAL = ("done", "error", "cancelled")


class DB:
    def __init__(self, path):
        self.path = path
        d = os.path.dirname(path)
        if d:
            os.makedirs(d, exist_ok=True)
        self._local = threading.local()
        self.lock = threading.RLock()
        self.changed = threading.Condition(threading.Lock())
        c = self.conn()
        c.executescript(SCHEMA)
        c.commit()
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass

    def conn(self):
        c = getattr(self._local, "c", None)
        if c is None:
            c = sqlite3.connect(self.path, timeout=30, isolation_level=None)
            c.row_factory = sqlite3.Row
            c.execute("PRAGMA journal_mode=WAL")
            c.execute("PRAGMA synchronous=NORMAL")
            self._local.c = c
        return c

    def x(self, sql, args=()):
        with self.lock:
            return self.conn().execute(sql, args)

    def notify(self):
        with self.changed:
            self.changed.notify_all()

    # ── tasks ──
    def create_task(self, goal, name="", models=None, max_steps=80, client_ref=""):
        tid = "ct" + uuid.uuid4().hex[:14]
        now = time.time()
        with self.lock:
            self.x("BEGIN IMMEDIATE")
            self.x("INSERT INTO tasks(id,name,goal,status,created,updated,max_steps,models,client_ref) VALUES(?,?,?,?,?,?,?,?,?)",
                   (tid, name or goal[:16], goal, "queued", now, now, max_steps, json.dumps(models or []), client_ref))
            self.x("COMMIT")
        self.event(tid, "created", {"name": name or goal[:16], "goal": goal, "line": "已在云端排队"})
        return tid

    def task(self, tid):
        r = self.x("SELECT * FROM tasks WHERE id=?", (tid,)).fetchone()
        return dict(r) if r else None

    def by_ref(self, ref):
        if not ref:
            return None
        r = self.x("SELECT * FROM tasks WHERE client_ref=? ORDER BY created DESC LIMIT 1", (ref,)).fetchone()
        return dict(r) if r else None

    def tasks(self, limit=50):
        return [dict(r) for r in self.x("SELECT * FROM tasks ORDER BY updated DESC LIMIT ?", (limit,)).fetchall()]

    def update(self, tid, **kv):
        if not kv:
            return
        kv["updated"] = time.time()
        cols = ",".join(f"{k}=?" for k in kv)
        self.x(f"UPDATE tasks SET {cols} WHERE id=?", tuple(kv.values()) + (tid,))
        self.notify()

    def claim(self, worker):
        """oldest runnable task → running under this worker (atomic)"""
        now = time.time()
        with self.lock:
            self.x("BEGIN IMMEDIATE")
            r = self.x("SELECT id FROM tasks WHERE status='queued' AND next_at<=? ORDER BY created LIMIT 1", (now,)).fetchone()
            if not r:
                self.x("COMMIT")
                return None
            self.x("UPDATE tasks SET status='running', lease=?, updated=? WHERE id=?", (worker, now, r["id"]))
            self.x("COMMIT")
        return r["id"]

    def recover(self):
        """after a crash / restart: whatever was running is queued again (its transcript keeps every finished step)"""
        n = self.x("UPDATE tasks SET status='queued', lease='' WHERE status='running'").rowcount
        return n

    # ── transcript ──
    def msgs(self, tid):
        return [json.loads(r["body"]) for r in self.x("SELECT body FROM msgs WHERE task_id=? ORDER BY seq", (tid,)).fetchall()]

    def add_msg(self, tid, m):
        with self.lock:
            r = self.x("SELECT COALESCE(MAX(seq),-1)+1 AS n FROM msgs WHERE task_id=?", (tid,)).fetchone()
            self.x("INSERT INTO msgs(task_id,seq,body) VALUES(?,?,?)", (tid, r["n"], json.dumps(m, ensure_ascii=False)))
            return r["n"]

    # ── events ──
    def event(self, tid, typ, data):
        data = dict(data or {})
        with self.lock:
            cur = self.x("INSERT INTO events(task_id,ts,type,data) VALUES(?,?,?,?)", (tid, time.time(), typ, json.dumps(data, ensure_ascii=False)))
            eid = cur.lastrowid
        self.notify()
        return eid

    def events(self, tid, after=0, limit=200):
        q = "SELECT * FROM events WHERE id>? " + ("AND task_id=? " if tid else "") + "ORDER BY id LIMIT ?"
        args = (after, tid, limit) if tid else (after, limit)
        out = []
        for r in self.x(q, args).fetchall():
            out.append({"id": r["id"], "task": r["task_id"], "ts": r["ts"], "type": r["type"], "data": json.loads(r["data"])})
        return out

    def last_event_id(self):
        r = self.x("SELECT COALESCE(MAX(id),0) AS m FROM events").fetchone()
        return r["m"]

    def wait_change(self, timeout):
        with self.changed:
            self.changed.wait(timeout)

    # ── tool runs / approvals / inbox ──
    def toolrun(self, tid, call_id):
        r = self.x("SELECT * FROM toolruns WHERE task_id=? AND call_id=?", (tid, call_id)).fetchone()
        return dict(r) if r else None

    def toolrun_set(self, tid, call_id, act, state, result=""):
        self.x("INSERT OR REPLACE INTO toolruns(task_id,call_id,act,state,result) VALUES(?,?,?,?,?)", (tid, call_id, act, state, result))

    def approval(self, tid, call_id):
        r = self.x("SELECT * FROM approvals WHERE task_id=? AND call_id=?", (tid, call_id)).fetchone()
        return dict(r) if r else None

    def approve(self, tid, call_id, decision, note=""):
        self.x("INSERT OR REPLACE INTO approvals(task_id,call_id,decision,note,used) VALUES(?,?,?,?,0)", (tid, call_id, decision, note))

    def use_approval(self, tid, call_id):
        self.x("UPDATE approvals SET used=1 WHERE task_id=? AND call_id=?", (tid, call_id))

    def push_inbox(self, tid, text):
        self.x("INSERT INTO inbox(task_id,text) VALUES(?,?)", (tid, text))
        self.notify()

    def drain_inbox(self, tid):
        with self.lock:
            rows = self.x("SELECT id,text FROM inbox WHERE task_id=? AND used=0 ORDER BY id", (tid,)).fetchall()
            for r in rows:
                self.x("UPDATE inbox SET used=1 WHERE id=?", (r["id"],))
        return [r["text"] for r in rows]

    def count(self, key, inc=0):
        with self.lock:
            r = self.x("SELECT n FROM counters WHERE key=?", (key,)).fetchone()
            n = (r["n"] if r else 0) + inc
            if inc:
                self.x("INSERT OR REPLACE INTO counters(key,n) VALUES(?,?)", (key, n))
        return n

    def kv_get(self, k, default=None):
        r = self.x("SELECT v FROM kv WHERE k=?", (k,)).fetchone()
        return json.loads(r["v"]) if r else default

    def kv_set(self, k, v):
        self.x("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)", (k, json.dumps(v, ensure_ascii=False)))
