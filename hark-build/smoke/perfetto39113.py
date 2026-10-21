"""[38.9.11.3 f] what blocks the main thread during a page switch. usage: perfetto39113.py <trace.pftrace> <package>
Windows come from trace_marker prints 'mines-T:<name>:B' / ':E' written by vps_smoke.py. Per window: main-thread slices
(longest, by name), main-thread state split (running / runnable / sleeping / blocked + blocked_function), RenderThread long
slices, GC / lock-contention slices, frame timeline jank, and the app frames most often on the main thread's stack while a
frame was late (callstack sampling). Prints one JSON object."""
import sys, json, re, collections
from perfetto.trace_processor import TraceProcessor

path, pkg = sys.argv[1], sys.argv[2]
tp = TraceProcessor(trace=path)
def q(sql):
    try: return [r.__dict__ for r in tp.query(sql)]
    except Exception as e: return [{"error": str(e)[:300]}]

out = {"trace": path.split("/")[-1]}
proc = q(f"select upid, pid, name from process where name like '{pkg}%' order by (name = '{pkg}') desc, upid desc")
proc = [p for p in proc if "error" not in p]
if not proc: print(json.dumps({"error": "app process not in trace", **out})); sys.exit(0)
upid, pid = proc[0]["upid"], proc[0]["pid"]
main = q(f"select utid from thread where upid = {upid} and tid = {pid}")
rt = q(f"select utid from thread where upid = {upid} and name = 'RenderThread'")
mu = main[0]["utid"] if main and "utid" in main[0] else None
ru = rt[0]["utid"] if rt and "utid" in rt[0] else None

# markers
marks = []
for sql in ("select ts, extract_arg(arg_set_id, 'buf') as buf from ftrace_event where name = 'print'",
            "select ts, extract_arg(arg_set_id, 'buf') as buf from raw where name = 'print'"):
    r = q(sql)
    if r and "error" not in r[0]: marks = [(x["ts"], x["buf"] or "") for x in r if "mines-T:" in (x["buf"] or "")]; break
if not marks:   # an atrace-formatted marker may have become a slice
    marks = [(x["ts"], x["name"]) for x in q("select ts, name from slice where name like 'mines-T:%'") if "error" not in x]
win = {}
for ts, b in marks:
    m = re.search(r"mines-T:([\w]+):([BE])", b)
    if m: win.setdefault(m.group(1), {})[m.group(2)] = ts
out["windows"] = {k: round((v["E"] - v["B"]) / 1e9, 2) for k, v in win.items() if "B" in v and "E" in v}

def thread_slices(utid, t0, t1, mindur_ms=8, n=10):
    return q(f"""select s.name, s.depth, round(s.dur/1e6, 1) as ms from slice s join thread_track tt on s.track_id = tt.id
                 where tt.utid = {utid} and s.ts < {t1} and s.ts + s.dur > {t0} and s.dur >= {int(mindur_ms * 1e6)}
                 order by s.dur desc limit {n}""")

agg_main = collections.Counter(); agg_rt = collections.Counter(); agg_state = collections.Counter(); agg_block = collections.Counter()
per = {}
for name, v in sorted(win.items()):
    if "B" not in v or "E" not in v: continue
    t0, t1 = v["B"], v["E"]; o = {}
    if mu is not None:
        o["main_long"] = thread_slices(mu, t0, t1)
        for s in o["main_long"]:
            if "name" in s and s["depth"] <= 3: agg_main[s["name"]] += s["ms"]
        st = q(f"""select state, blocked_function as bf, round(sum(min(ts + dur, {t1}) - max(ts, {t0}))/1e6, 1) as ms from thread_state
                   where utid = {mu} and ts < {t1} and ts + dur > {t0} group by state, bf order by ms desc limit 8""")
        o["main_state"] = st
        for s in st:
            if "state" in s: agg_state[s["state"]] += s["ms"] or 0
            if s.get("bf"): agg_block[s["bf"]] += s["ms"] or 0
    if ru is not None:
        o["render_long"] = thread_slices(ru, t0, t1, 8, 6)
        for s in o["render_long"]:
            if "name" in s and s["depth"] <= 2: agg_rt[s["name"]] += s["ms"]
    o["gc_lock"] = q(f"""select s.name, round(s.dur/1e6,1) as ms, t.name as thread from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
                         where t.upid = {upid} and s.ts < {t1} and s.ts + s.dur > {t0} and s.dur > 2e6
                         and (s.name like '%GC%' or s.name like 'Lock contention%' or s.name like 'monitor contention%') order by s.dur desc limit 5""")
    fr = q(f"""select count(*) as n, sum(case when jank_type != 'None' then 1 else 0 end) as jank, round(max(dur)/1e6,1) as max_ms,
               group_concat(distinct jank_type) as types from actual_frame_timeline_slice where upid = {upid} and ts < {t1} and ts + dur > {t0}""")
    o["frametimeline"] = fr[0] if fr else None
    per[name] = o
out["per_window"] = per
out["main_top_slices_ms"] = agg_main.most_common(12)
out["render_top_slices_ms"] = agg_rt.most_common(8)
out["main_state_ms"] = agg_state.most_common()
out["main_blocked_fn_ms"] = agg_block.most_common(6)

# callstack samples on the main thread inside late frames of the windows
if mu is not None and win:
    late = q(f"""select ts, dur from actual_frame_timeline_slice where upid = {upid} and dur > 17e6""")
    late = [(x["ts"], x["ts"] + x["dur"]) for x in late if "ts" in x]
    wins = [(v["B"], v["E"]) for v in win.values() if "B" in v and "E" in v]
    samp = q(f"select ts, callsite_id from perf_sample where utid = {mu} and callsite_id is not null")
    samp = [s for s in samp if "ts" in s and any(a <= s["ts"] <= b for a, b in wins)]
    in_late = [s for s in samp if any(a <= s["ts"] <= b for a, b in late)] if late else samp
    frames = {}
    def chain(cs):
        if cs in frames: return frames[cs]
        r = q(f"""with recursive c(id, parent_id, frame_id, depth) as (select id, parent_id, frame_id, depth from stack_profile_callsite where id = {cs}
                  union all select p.id, p.parent_id, p.frame_id, p.depth from stack_profile_callsite p join c on p.id = c.parent_id)
                  select coalesce(f.deobfuscated_name, f.name) as fn from c join stack_profile_frame f on f.id = c.frame_id order by c.depth desc""")
        frames[cs] = [x.get("fn") or "?" for x in r if "error" not in x]; return frames[cs]
    leaf = collections.Counter(); app = collections.Counter(); incl = collections.Counter()
    for s in in_late[:6000]:
        ch = chain(s["callsite_id"])           # leaf first
        if not ch: continue
        leaf[ch[0]] += 1
        a = next((f for f in ch if f and ("openminis" in f or "compose" in f.lower())), None)
        if a: app[a] += 1
        for f in set(ch):
            if f and "openminis" in f: incl[f] += 1
    out["samples"] = {"main_in_windows": len(samp), "main_in_late_frames": len(in_late), "late_frames": len(late)}
    out["late_leaf_top"] = leaf.most_common(12)
    out["late_first_app_frame_top"] = app.most_common(12)
    out["late_app_inclusive_top"] = incl.most_common(12)
print(json.dumps(out, ensure_ascii=False, default=str))
