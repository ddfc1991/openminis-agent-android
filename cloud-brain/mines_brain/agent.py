"""The agent loop. Every step is committed to SQLite before the next one begins, so `kill -9` + restart resumes at the
last finished step instead of starting over, and a phone that is asleep, offline or killed changes nothing here.

One step = one model call + (if it asked for one) one tool call. The transcript (msgs) and the event log (events) are
the checkpoint: on resume the loop simply reads the transcript back and keeps going.

A tool call that may act for the user (send/post/delete/pay) is written to `toolruns` as "started" BEFORE it runs and
"done" after, so a crash in the middle never silently repeats it: the resumed loop reports it as uncertain and asks.
"""
import json
import threading
import time

from . import limitguard as lg
from . import model as mdl
from . import tools as T

SYSTEM = """你是 Mines 的云端执行体，运行在用户自己的服务器上（新加坡 AWS）。你替用户把事情真正做完。

规矩：
1. 你在后台跑，手机可能黑屏、断网、被杀掉，这都不影响你。不要因为「等用户看屏幕」停下来。
2. 能自己做的就自己做：查资料用 http_fetch，跑命令/下载/解析用 shell，要真的在网页上操作用 browser。
3. 只有这几种情况才找用户：缺只有他知道的信息（ask_user）；滑块/人机验证/扫码/短信验证/付款（handoff 或 phone）。
4. 【限制层】是用户给你定的规矩，你不能改也不能绕。被拦住就按提示停下等用户，不要换别的路子重试。
5. 不要假装做完。做不到就说清楚卡在哪。做完了调用 finish，给一句中文结果。
6. 每一步都简短说明你在做什么（中文），用户在手机上看得到。
"""


def unanswered(msgs):
    """tool calls of the last assistant turn that have no tool result yet"""
    last = None
    for i in range(len(msgs) - 1, -1, -1):
        if msgs[i].get("role") == "assistant":
            last = i
            break
    if last is None or not msgs[last].get("tool_calls"):
        return []
    done = {m.get("tool_call_id") for m in msgs[last + 1:] if m.get("role") == "tool"}
    return [c for c in msgs[last]["tool_calls"] if c["id"] not in done]


def compact(msgs, budget=60000):
    """system + goal + the newest turns that fit; never splits an assistant tool_call turn from its results"""
    size = lambda m: len(json.dumps(m, ensure_ascii=False))
    if sum(size(m) for m in msgs) <= budget or len(msgs) <= 4:
        return msgs
    head = msgs[:2]
    tail = []
    total = sum(size(m) for m in head) + 200
    i = len(msgs) - 1
    while i >= 2:
        # take whole groups: an assistant turn plus the tool results after it
        j = i
        while j >= 2 and msgs[j].get("role") == "tool":
            j -= 1
        group = msgs[j:i + 1]
        g = sum(size(m) for m in group)
        if total + g > budget and tail:
            break
        tail = group + tail
        total += g
        i = j - 1
    note = {"role": "user", "content": f"（较早的 {len(msgs) - 2 - len(tail)} 条步骤记录已省略，下面是最近的步骤）"}
    if tail and tail[0].get("role") == "tool":
        tail = tail[1:]
    return head + [note] + tail


class Agent:
    def __init__(self, db, providers_fn, policy_fn, on_event=None, max_steps=80):
        self.db = db
        self.providers_fn = providers_fn
        self.policy_fn = policy_fn
        self.on_event = on_event or (lambda *a: None)
        self.max_steps = max_steps
        self.cancel = set()
        self._lock = threading.Lock()
        self.chat = mdl.chat_with_fallback
        self.retry_delays = (30, 90, 300, 900)

    # ── helpers ──
    def ev(self, tid, typ, **data):
        eid = self.db.event(tid, typ, data)
        try:
            self.on_event(tid, typ, data)
        except Exception:
            pass
        return eid

    def _providers(self, task):
        want = json.loads(task.get("models") or "[]")
        ps = self.providers_fn()
        if want:
            named = [p for p in ps if p.get("name") in want or p.get("model") in want]
            rest = [p for p in ps if p not in named]
            return named + rest
        return ps

    def _bootstrap(self, task):
        msgs = self.db.msgs(task["id"])
        if msgs:
            return msgs
        sys = SYSTEM
        pol = self.policy_fn() or {}
        allowed = [k for k, v in (pol.get("accounts") or {}).items() if v.get("allowed")]
        sys += "\n当前用户允许你直接使用的账号：" + ("、".join(allowed) if allowed else "（没有，任何账号操作都要先问用户）")
        sys += f"\n今天是 {time.strftime('%Y-%m-%d', time.gmtime(time.time() + 8 * 3600))}（北京时间）。"
        self.db.add_msg(task["id"], {"role": "system", "content": sys})
        self.db.add_msg(task["id"], {"role": "user", "content": task["goal"]})
        return self.db.msgs(task["id"])

    # ── the loop ──
    def run(self, tid):
        task = self.db.task(tid)
        if not task:
            return
        self.db.update(tid, status="running", pending="")
        self.ev(tid, "status", st="running", line="云端开始执行")
        self._bootstrap(task)
        step = task["step"] or 0
        while True:
            task = self.db.task(tid)
            if not task or task["status"] in ("cancelled",) or tid in self.cancel:
                self.cancel.discard(tid)
                self.db.update(tid, status="cancelled", pending="")
                self.ev(tid, "status", st="cancelled", line="已取消")
                return
            msgs = self.db.msgs(tid)
            open_calls = unanswered(msgs)
            if open_calls:
                # resume point: tool calls the model asked for that have no result yet (crash / approval / restart)
                for call in open_calls:
                    name = call["function"]["name"]
                    try:
                        args = json.loads(call["function"].get("arguments") or "{}")
                    except ValueError:
                        args = {}
                    if not isinstance(args, dict):
                        args = {}
                    res, stop = self._tool(tid, task, step, call["id"], name, args)
                    if res is not None:
                        self.db.add_msg(tid, {"role": "tool", "tool_call_id": call["id"], "content": res})
                    if stop:
                        return
                continue
            # user messages that arrived while running (only between complete turns)
            for extra in self.db.drain_inbox(tid):
                self.db.add_msg(tid, {"role": "user", "content": extra})
                self.ev(tid, "user_msg", text=extra[:400])
            msgs = self.db.msgs(tid)
            if step >= (task["max_steps"] or self.max_steps):
                self._finish(tid, False, f"步数到上限（{step}），先停下来等你看看")
                return
            step += 1
            self.db.update(tid, step=step)
            try:
                out, prov = self.chat(self._providers(task), compact(msgs), T.SCHEMAS,
                    log=lambda p, i, e: self.ev(tid, "model_retry", model=p.get("model", ""), attempt=i + 1, error=str(e)[:200]))
            except mdl.ModelError as e:
                self._retry_later(tid, task, f"模型都失败了：{e}")
                return
            self.db.update(tid, attempts=0)
            self.db.add_msg(tid, out)
            if out.get("content"):
                self.ev(tid, "say", step=step, text=out["content"][:800], model=prov.get("model", ""))
            if not out.get("tool_calls"):
                # a plain answer with no tool call ends the task
                self._finish(tid, True, out.get("content") or "做完了")
                return

    # ── one tool call ──
    def _tool(self, tid, task, step, call_id, name, args):
        """returns (tool result text, stop) — stop=True means the loop ends here (waiting / finished)"""
        if name == "finish":
            self.db.add_msg(tid, {"role": "tool", "tool_call_id": call_id, "content": "ok"})
            self._finish(tid, bool(args.get("ok", True)), str(args.get("result") or "做完了"))
            return None, True
        account = str(args.get("account") or "").strip()
        act = lg.act_of(name, args)
        pol = self.policy_fn() or {}
        ap = self.db.approval(tid, call_id)
        approved = bool(ap and ap["decision"] == "allow")
        if ap and ap["decision"] == "deny":
            self.db.use_approval(tid, call_id)
            self.ev(tid, "blocked", step=step, tool=name, act=act, account=account, why="denied", line="你拒绝了这一步")
            return "用户拒绝了这一步。换个不需要这一步的做法，或者调用 finish 说明做不了。", False
        used = self.db.count(f"{account}@{lg.day()}") if account else 0
        ok, why = lg.check(pol, account, act, used, approved)
        if not ok:
            ask = ("要我" + lg.ZH.get(act, act) + "：" + (args.get("text") or args.get("url") or args.get("command") or args.get("question") or "")[:120]) if why == "needs_confirm" \
                else lg.WHY.get(why, why)
            self.db.update(tid, status="waiting", pending=json.dumps({"kind": "approve", "call_id": call_id, "tool": name, "act": act, "account": account, "args": {k: str(v)[:300] for k, v in args.items()}, "why": why}, ensure_ascii=False))
            self.ev(tid, "ask", step=step, kind="approve", call_id=call_id, tool=name, act=act, account=account,
                    line=ask, why=why, detail=lg.WHY.get(why, why))
            return None, True
        if approved:
            self.db.use_approval(tid, call_id)

        if name in T.PHONE_TOOLS:
            kind = {"ask_user": "ask", "handoff": "handoff", "phone": "phone"}[name]
            line = args.get("question") or args.get("reason") or args.get("need") or "需要你处理"
            self.db.update(tid, status="waiting", pending=json.dumps({"kind": kind, "call_id": call_id, "line": line, "url": args.get("url", "")}, ensure_ascii=False))
            self.ev(tid, "ask", step=step, kind=kind, call_id=call_id, line=line, url=args.get("url", ""))
            return None, True

        runner = T.RUNNERS.get(name)
        if not runner:
            return f"错误：没有 {name} 这个工具", False

        prev = self.db.toolrun(tid, call_id)
        if prev and prev["state"] == "started" and act in lg.CONFIRM_ACTS:
            # a crash during a send/post/delete/pay: never repeat it blind
            self.db.toolrun_set(tid, call_id, act, "uncertain")
            self.ev(tid, "note", step=step, line=f"上次在「{lg.ZH.get(act, act)}」这一步被中断，没有重复执行")
            return "上一次执行这一步时服务被中断，不确定是否已经生效。请先用只读方式确认结果，不要直接重做。", False
        if prev and prev["state"] == "done":
            return prev["result"] or "（这一步之前已经执行过）", False

        self.db.toolrun_set(tid, call_id, act, "started")
        self.ev(tid, "tool", step=step, tool=name, act=act, account=account,
                line=self._tool_line(name, args))
        ctx = {"get": lambda: json.loads(self.db.task(tid).get("browser") or "{}"),
               "set": lambda s: self.db.update(tid, browser=json.dumps(s))}
        t0 = time.time()
        try:
            res = runner(args, ctx)
        except Exception as e:
            res = f"错误：{type(e).__name__}: {e}"
        if account and act not in (lg.READ, lg.DRAFT):
            self.db.count(f"{account}@{lg.day()}", 1)
        self.db.toolrun_set(tid, call_id, act, "done", res[:2000])
        self.ev(tid, "tool_done", step=step, tool=name, ms=int((time.time() - t0) * 1000), out=res[:400])
        return res, False

    @staticmethod
    def _tool_line(name, args):
        if name == "http_fetch":
            return "打开 " + str(args.get("url", ""))[:80]
        if name == "shell":
            return "跑命令：" + str(args.get("command", ""))[:70]
        if name == "browser":
            a = args.get("action")
            return {"open": "浏览器打开 " + str(args.get("url", ""))[:60], "text": "读取页面", "links": "看页面链接",
                    "click": "点击「" + str(args.get("text") or args.get("selector") or "")[:30] + "」",
                    "type": "输入内容", "press": "按键", "wait": "等待", "back": "返回"}.get(a, "浏览器 " + str(a))
        return name

    # ── endings ──
    def _finish(self, tid, ok, result):
        T.browser_close(json.loads((self.db.task(tid) or {}).get("browser") or "{}"))
        self.db.update(tid, status="done" if ok else "error", result=result[:2000], pending="", browser="")
        self.ev(tid, "status", st="done" if ok else "error", line=result[:500])

    def _retry_later(self, tid, task, why):
        """the whole model list failed: the task goes back to the queue with back-off; after 4 rounds it is an error"""
        n = (task.get("attempts") or 0) + 1
        if n > len(self.retry_delays):
            self._error(tid, why)
            return
        d = self.retry_delays[n - 1]
        self.db.update(tid, status="queued", attempts=n, next_at=time.time() + d, step=max(0, (task.get("step") or 1) - 1))
        self.ev(tid, "retry", line=f"模型暂时不可用，{d} 秒后自动重试（第 {n} 次）", error=why[:200])

    def _error(self, tid, why):
        self.db.update(tid, status="error", result=why[:500], pending="")
        self.ev(tid, "status", st="error", line=why[:500])

    # ── answers from the phone ──
    def answer(self, tid, call_id, text):
        """user answered an ask_user / handoff / phone: the tool result is their answer, the task runs again"""
        t = self.db.task(tid)
        if not t:
            return False
        pend = json.loads(t.get("pending") or "{}")
        cid = call_id or pend.get("call_id")
        if cid:
            self.db.add_msg(tid, {"role": "tool", "tool_call_id": cid, "content": "用户回复：" + text})
        else:
            self.db.add_msg(tid, {"role": "user", "content": text})
        self.db.update(tid, status="queued", pending="", next_at=0)
        self.ev(tid, "user_msg", text=text[:400])
        return True

    def decide(self, tid, call_id, allow, note=""):
        t = self.db.task(tid)
        if not t:
            return False
        pend = json.loads(t.get("pending") or "{}")
        cid = call_id or pend.get("call_id")
        self.db.approve(tid, cid, "allow" if allow else "deny", note)
        self.db.update(tid, status="queued", pending="", next_at=0)
        self.ev(tid, "decision", call_id=cid, allow=allow, line="你同意了" if allow else "你拒绝了")
        return True
