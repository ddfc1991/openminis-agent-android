#!/usr/bin/env python3
"""VPS-screen crash smoke test for the Mines release APK on an emulator.

Drives the real UI with adb + uiautomator (no test APK, release build as
shipped): launch -> home Today card -> 工作位置 -> add a reachable SSH server
(the runner's own sshd at 10.0.2.2, password login + key install) -> VPS mode
-> server detail -> add an unreachable server -> 立即检测 -> Settings ->
工作位置. After every step it checks the app process is still alive and at the
end prints every FATAL EXCEPTION from logcat. Exit 1 when the app crashed.
"""
import json, os, re, struct, subprocess, sys, time, urllib.request, xml.etree.ElementTree as ET

PKG = os.environ.get("PKG", "com.openminis.mines.plus")
OUT = os.environ.get("OUT", "smoke-out")
SSH_HOST = os.environ.get("SSH_HOST", "10.0.2.2")
SSH_PASS = os.environ.get("SSH_PASS", "MinesSmoke-2026")
os.makedirs(OUT, exist_ok=True)
try:
    QUICK = "quick" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    QUICK = False
try:
    VOICE352 = "voice" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    VOICE352 = False
try:
    B353 = "b353" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    B353 = False
try:
    B36 = "b36" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    B36 = False
try:
    B381 = "b381" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    B381 = False
try:
    B385 = "b385" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    B385 = False
try:
    B386 = "b386" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    B386 = False
try:
    B387 = "b387" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
    FAST = "fast" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    B387 = False; FAST = False
try:
    B388 = "b388" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    B388 = False
if B388: B387 = True; FAST = True
try:
    UI388 = "ui388" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    UI388 = False
try:
    UI389 = "ui389" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    UI389 = False
try:
    HUB = "hub" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    HUB = False
try:
    UI3891 = "ui3891" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    UI3891 = False
try:
    FIX3891 = "fix3891" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    FIX3891 = False
try:
    MSG3892 = "msg3892" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    MSG3892 = False
try:
    ACCT3893 = "acct3893" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    ACCT3893 = False
try:
    H3894 = "h3894" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    H3894 = False
try:
    I395 = "i395" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
    I395B = "i395b" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
except Exception:
    I395 = False; I395B = False
try:
    I396 = "i396" in open("hark-build/smoke/target").read().splitlines()[1:2][0]
    I396M = "i396m" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.6 i13] Memoh / proxy auth / sheets
    I396X = "i396x" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.6 i15] weather / 快速测试 / 重试 / overlays / Clash
except Exception:
    I396 = False; I396M = False; I396X = False
try:
    I397 = "i397" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.7] takeover / 无痕 / 工程模式 / home fixes
except Exception:
    I397 = False
try:
    if "fast i397" in open("hark-build/smoke/target").read(): I397 = True
except Exception:
    pass
try:
    I398 = "i398" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.8] picker / stacked quick test / timeout / workflows
except Exception:
    I398 = False
try:
    I398B = "i398b" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.8 b] model detail / market / error card
except Exception:
    I398B = False
try:
    I399 = "i399" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.9] swipe / sheets / sky / 免登录 page / OAuth quick test / copy
except Exception:
    I399 = False
try:
    I3910 = "i3910" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.10 i29] sky v7 / swipe title / Clash restart / real proxy check / soak
except Exception:
    I3910 = False
try:
    I3911 = "i3911" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.11 int] integrated build screens
except Exception:
    I3911 = False
try:
    I39111 = "i39111" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.11.1 hotfix] fog sky perf + 天空动画 + local proxy
except Exception:
    I39111 = False
try:
    I39112 = "i39112" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.11.2] 无痕 isolation + 晴 flash capture
except Exception:
    I39112 = False
try:
    I3912 = "i3912" in open("hark-build/smoke/target").read().splitlines()[1:2][0].split()   # [38.9.12] new pages + 账号自动调用
except Exception:
    I3912 = False
try:
    I39121 = "i39121" in open("hark-build/smoke/target").read().splitlines()[1:2][0].split()   # [38.9.12.1] OOM hotfix repro + safe start
except Exception:
    I39121 = False
try:
    I39113 = "i39113" in open("hark-build/smoke/target").read().splitlines()[1:2][0]   # [38.9.11.3] provider latency + continuous fog drift + SSH cert
except Exception:
    I39113 = False
try:
    I3913 = "i3913" in open("hark-build/smoke/target").read().splitlines()[1:2][0].split()   # [38.9.13] task projects / VPS page / handoff v2 / memory v2 / Mines browser
except Exception:
    I3913 = False
try:
    I3914 = "i3914" in open("hark-build/smoke/target").read().splitlines()[1:2][0].split()   # [38.9.14] chat_home: 聊天 + auto tasks
except Exception:
    I3914 = False
if I3914: I397 = True
if I39113: I39112 = True
if I39112: I39111 = True
if I3911: I397 = True
if I3910: I397 = True
if I399: I397 = True
if I398: I397 = True
if I397: FAST = True   # [i21b] b397 only runs inside the FAST gate (smoke 39136327758 ran the legacy path without it)
FONT_SCALE = "1.18"
step_no = 0
crashed = []


def adb(*a, check=False, timeout=60):
    r = subprocess.run(["adb", *a], capture_output=True, text=True, errors="replace", timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(r.stderr)
    return r.stdout


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def alive():
    return sh(f"pidof {PKG}").strip() != ""


def dump():
    for _ in range(3):
        sh("rm -f /sdcard/u.xml; uiautomator dump /sdcard/u.xml >/dev/null 2>&1")
        x = sh("cat /sdcard/u.xml")
        if x.strip().startswith("<?xml"):
            try:
                r = ET.fromstring(x[x.index("<hierarchy"):])
            except Exception:
                continue
            # [build-34] an emulator system app ANR (googlesdksetup isn't responding) covered every screen in smoke run 20
            if "isn't responding" in x and "Mines isn't responding" not in x:
                for n in r.iter("node"):
                    # tap "Wait", not "Close app": killing googlesdksetup can leave the API 35 emulator without Wi-Fi
                    if n.get("text") in ("Wait", "等待"):
                        b = [int(v) for v in re.findall(r"\d+", n.get("bounds", ""))]
                        if len(b) == 4:
                            sh(f"input tap {(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}"); time.sleep(1.5)
                        break
                continue
            return r
        time.sleep(1)
    return None


def nodes(root):
    out = []
    if root is None:
        return out
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            out.append((n.get("text", "") or "", n.get("content-desc", "") or "", (x1 + x2) // 2, (y1 + y2) // 2, n))
    return out


def texts(root):
    return [t or d for t, d, *_ in nodes(root) if (t or d)]


def snap(name):
    global step_no
    step_no += 1
    tag = f"{step_no:02d}-{name}"
    for _ in range(3):   # screencap sometimes returns an empty file (smoke run 36: b381-connections.png 0 bytes)
        subprocess.run(f"adb exec-out screencap -p > {OUT}/{tag}.png", shell=True)
        if os.path.exists(f"{OUT}/{tag}.png") and os.path.getsize(f"{OUT}/{tag}.png") > 1000: break
        time.sleep(1.5)
    root = dump()
    t = texts(root)
    with open(f"{OUT}/{tag}.txt", "w") as f:
        f.write("\n".join(t))
    print(f"[{tag}] alive={alive()} texts={t[:40]}", flush=True)
    return root


def find(root, label, exact=False):
    for t, d, x, y, n in nodes(root):
        for s in (t, d):
            if s and (s == label if exact else label in s):
                return x, y
    return None


def tap_text(label, exact=False, wait=2.5, root=None):
    root = root if root is not None else dump()
    p = find(root, label, exact)
    if not p:
        print(f"  (not found: {label})", flush=True)
        return False
    sh(f"input tap {p[0]} {p[1]}")
    time.sleep(wait)
    return True


def tap_work(wait=2.5):
    """Home 工作位置 pill: build-29+ exposes it as content-description "工作位置：<value>"
    (compact pill, no visible label); older builds show the text 工作位置."""
    root = dump()
    if find(root, "工作位置："):
        return tap_text("工作位置：", wait=wait, root=root)
    return tap_text("工作位置", wait=wait, root=root)


SELFTEST_URI = f"content://com.openminis.mines.plus.selftest"
selftest_fail = []


def selftest(method, arg, timeout=90):
    # an empty --arg '' is dropped by adb shell → 'content' printed its usage (smoke run 36); omit it instead
    a = f" --arg '{arg}'" if arg != "" else ""
    out = sh(f"content call --uri {SELFTEST_URI} --method {method}{a}", timeout=timeout)
    print(f"  selftest {method} {arg!r} -> {out.strip()[:600]}", flush=True)
    return out


def selftests():
    """build-29+ adb-only SelfTestProvider: app launch from background, storage speed,
    cold start at fontScale 1.3. Skipped when the provider is absent (older builds)."""
    probe = selftest("allow", "a11y_cli")
    if "result=ok" not in probe and "result=ok" not in probe.replace(" ", ""):
        print("  (selftest provider not available, skipping)", flush=True); return
    # 1) launch another app while Mines is in the background
    sh("input keyevent KEYCODE_HOME"); time.sleep(3)
    sh("am force-stop com.android.settings"); time.sleep(1)
    out = selftest("offload", "android-a11y-cli open com.android.settings")
    time.sleep(4)
    top = sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -3")
    print("  top after open:", top.strip(), flush=True)
    if "com.android.settings" not in top:
        selftest_fail.append("open app from background"); snap("selftest-open-failed")
    else:
        snap("selftest-open-ok")
    check("selftest: open app from background")
    # 2) storage info under 6 s
    out = selftest("offload", "android-device storage")
    m = re.search(r"ms=(\d+)", out); ex = re.search(r"exit=(\d+)", out)
    ms = int(m.group(1)) if m else -1
    print(f"  storage: exit={ex.group(1) if ex else '?'} ms={ms}", flush=True)
    if not (ex and ex.group(1) == "0" and 0 <= ms < 6000):
        selftest_fail.append(f"storage exit={ex.group(1) if ex else '?'} ms={ms}")
    check("selftest: storage")
    # 3) cold start at fontScale 1.3 (user runs a large system font)
    sh("settings put system font_scale 1.3"); time.sleep(2)
    sh(f"am force-stop {PKG}"); time.sleep(2)
    relaunch()
    snap("cold-start-font13")
    if not check("cold start at fontScale 1.3"):
        selftest_fail.append("cold start at fontScale 1.3")
    root = dump()
    if not (find(root, "工作位置：") or find(root, "工作位置")):
        print("  (工作位置 pill not visible at fontScale 1.3)", flush=True)
    sh("settings put system font_scale 1.0"); time.sleep(1)


def type_text(s):
    sh("input text " + s.replace(" ", "%s").replace("'", "\\'").replace("&", "\\&"))


def check(step):
    time.sleep(1.5)
    if not alive():
        print(f"!!! APP DIED after step: {step}", flush=True)
        crashed.append(step)
        return False
    return True


def relaunch():
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
    time.sleep(10)
    dismiss_dialogs()
    if alive(): go_home()


def dismiss_dialogs():
    for _ in range(4):
        root = dump()
        hit = False
        for lab in ("Wait", "等待", "以后再说", "以后", "跳过", "稍后", "知道了", "不用了", "取消", "Allow", "允许", "ALLOW", "While using the app"):
            p = find(root, lab, exact=True)
            if p:
                sh(f"input tap {p[0]} {p[1]}")
                time.sleep(1.5)
                hit = True
                break
        if not hit:
            return


def fill_field(root, label, value, clear=False):
    p = find(root, label)
    if not p:
        print(f"  (field not found: {label})", flush=True)
        return
    sh(f"input tap {p[0]} {p[1]}")
    time.sleep(0.6)
    if clear:
        sh("input keyevent KEYCODE_MOVE_END")
        for _ in range(12):
            sh("input keyevent KEYCODE_DEL")
    type_text(value)
    time.sleep(0.5)


def add_server(name, host, password, port=None):
    if not tap_text("添加服务器"):
        return
    root = snap(f"add-dialog-{name}")
    fill_field(root, "名字", name)
    # [38.9.3 g2 smoke fix] the dialog shifts once the IME opens: re-dump before every field (smoke g typed the IP into 用户)
    time.sleep(0.8)
    root = dump(); fill_field(root, "地址 / IP", host) if find(root, "地址 / IP") else fill_field(root, "地址", host)
    if port:
        root = dump(); fill_field(root, "端口", str(port), clear=True)
    if password:
        time.sleep(0.5)
        root = dump(); fill_field(root, "密码", password)
    sh("input keyevent KEYCODE_BACK")  # close IME
    time.sleep(1)
    root = dump()
    if not tap_text("保存", exact=True, root=root):
        sh("input keyevent KEYCODE_BACK")
    time.sleep(3)


def edits(root):
    return [(x, y, n) for t, d, x, y, n in nodes(root) if n.get("class", "").endswith("EditText")]


def fill_edit(idx, value, clear=True):
    root = dump(); e = edits(root)
    if idx >= len(e):
        print(f"  (no EditText #{idx}; have {len(e)})", flush=True); return
    x, y, _ = e[idx]
    sh(f"input tap {x} {y}"); time.sleep(0.7)
    if clear:
        sh("input keyevent KEYCODE_MOVE_END")
        sh("input keyevent " + " ".join(["KEYCODE_DEL"] * 40))
    type_text(value); time.sleep(0.6)


SCREEN = [1080, 1920]


def scroll_down():
    w, h = SCREEN
    sh(f"input swipe {w//2} {int(h*0.8)} {w//2} {int(h*0.3)} 300"); time.sleep(1.2)


def tap_any(labels, wait=2.5, exact=False):
    root = dump()
    for lab in labels:
        if find(root, lab, exact):
            return tap_text(lab, exact=exact, wait=wait, root=root)
    return False


def setup_provider():
    """Onboarding: OpenAI-compatible provider at the runner's fake endpoint, one model, one chat."""
    tap_text("Add a Provider", wait=4)
    root = snap("provider-chooser")
    if find(root, "Choose Provider") or find(root, "OpenAI"):
        tap_text("OpenAI", exact=True, wait=3) or tap_text("OpenAI", wait=3)
        root = snap("provider-picked")
        if find(root, "API Key", exact=True):
            tap_text("API Key", exact=True, wait=3); root = snap("auth-picked")
    # fields are found by their placeholder text (indices shift when the IME opens)
    def ime_shown():
        return "mInputShown=true" in sh("dumpsys input_method | grep mInputShown")

    def type_into(placeholder, value):
        for _ in range(3):
            root = dump()
            target = None
            for n in root.iter("node") if root is not None else []:
                if n.get("class", "").endswith("EditText"):
                    if any((c.get("text") == placeholder) for c in n.iter("node")) or n.get("text") == placeholder:
                        b = list(map(int, re.findall(r"\d+", n.get("bounds", "")))); target = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2); break
            if target:
                sh(f"input tap {target[0]} {target[1]}"); time.sleep(1.5)
                # [38.8 v4] smoke 38860755136: one long `input text` lost characters on a slow emulator; type in small chunks
                for k in range(0, len(value), 6): type_text(value[k:k + 6]); time.sleep(0.35)
                time.sleep(0.6)
                if ime_shown(): sh("input keyevent KEYCODE_BACK"); time.sleep(1)
                return True
            scroll_down()
        print(f"  (field with placeholder not found: {placeholder})", flush=True); return False
    type_into("sk-...", "sk-smoke-000000000000000000000000")
    type_into("https://api.openai.com", "http://10.0.2.2:8088")
    root = snap("provider-filled")
    for _ in range(4):
        if tap_any(["Add Provider", "Save Provider", "Save API Key"], wait=5, exact=True):
            break
        scroll_down()
    root = snap("provider-saved")
    for _ in range(3):
        if find(root, "Select Models"):
            break
        sh("input keyevent KEYCODE_BACK"); time.sleep(2); root = dump()
    tap_text("Select Models", wait=10)
    root = snap("models")
    for _ in range(3):
        if find(root, "smoke-model"): break
        if tap_any(["Retry"], wait=8): root = dump()
    if not tap_text("smoke-model", wait=2):
        if tap_any(["Add Model Manually"], wait=3):
            fill_edit(0, "smoke-model", clear=False)
            sh("input keyevent KEYCODE_BACK"); time.sleep(1)
            for _ in range(3):
                if tap_any(["Add Custom Model", "Add Model", "Save", "Add"], wait=4, exact=True): break
                scroll_down()
            snap("custom-model-added")
            tap_text("smoke-model", wait=2)
    root = snap("model-picked")
    tap_any(["Done (1/3)", "Done ("], wait=5)
    root = snap("after-models")
    for _ in range(3):
        if find(root, "Start a Conversation") or find(root, "工作位置"):
            break
        sh("input keyevent KEYCODE_BACK"); time.sleep(2); root = dump()
    if find(root, "Start a Conversation"):
        tap_text("Start a Conversation", wait=6)
        root = snap("chat")
        e = edits(root)
        if e:
            sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text("hello")
            sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
            tap_any(["Send", "发送"], wait=10) or sh("input keyevent KEYCODE_ENTER")
            time.sleep(8)
        snap("chat-sent")
        check("chat with fake model")
        loop_test()
        try:
            garbage_test()
        except Exception as e:
            print("garbage test error:", e, flush=True); selftest_fail.append(f"b34 garbage error {e}")
        sh("input keyevent KEYCODE_BACK"); time.sleep(3)
    root = snap("home-with-chat")
    check("home list (TodayCard) shown")


def loop_test():
    """[build-30] Agent loop guard: the fake model repeats one text + tool call; Mines must warn, then pause."""
    root = dump()
    e = edits(root)
    if not e:
        print("  loop test: no input field", flush=True); selftest_fail.append("loop test: no input"); return
    sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text("LOOPTEST")
    sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
    tap_any(["Send", "发送"], wait=3) or sh("input keyevent KEYCODE_ENTER")
    paused = False
    for _ in range(30):
        time.sleep(3)
        root = dump()
        if find(root, "检测到重复循环"):
            paused = True; break
    snap("loop-guard")
    try:
        st = json.loads(subprocess.run("curl -s http://127.0.0.1:8088/stats", shell=True, capture_output=True, text=True, timeout=10).stdout or "{}")
    except Exception as ex:
        st = {"error": str(ex)}
    print("  loop test: paused=%s stats=%s" % (paused, st), flush=True)
    if not paused: selftest_fail.append("loop guard did not pause")
    if not st.get("saw_warning"): selftest_fail.append("loop guard warning not sent to model")
    if (st.get("loop_posts") or 99) > 6: selftest_fail.append("loop ran %s model calls" % st.get("loop_posts"))
    if st.get("empty_tool_content"): selftest_fail.append("empty tool content sent")
    if find(root, "停止"):
        tap_text("停止", exact=True, wait=2)
    check("loop guard pause")


def toggle_near(label):
    root = dump()
    p = find(root, label)
    if not p:
        print(f"  (toggle label not found: {label})", flush=True); return False
    best = None
    for t, d, x, y, n in nodes(root):
        if n.get("checkable") == "true":
            dist = abs(y - p[1])
            if best is None or dist < best[0]: best = (dist, x, y, n.get("checked"))
    if not best or best[0] > 200:
        sh(f"input tap {p[0]} {p[1]}"); time.sleep(2); return True
    print(f"  toggle {label}: was checked={best[3]}", flush=True)
    if best[3] != "true":
        sh(f"input tap {best[1]} {best[2]}"); time.sleep(2)
    return True


def go_home():
    for _ in range(3):
        root = dump()
        if find(root, "isn't responding"): dismiss_dialogs(); root = dump()
        if find(root, "工作位置：") or find(root, "工作位置", exact=True) or find(root, "Welcome to Mines"): return root
        if find(root, "Google search") or find(root, "At a glance"):
            break  # on the launcher: bring Mines back instead of pressing back
        sh("input keyevent KEYCODE_BACK"); time.sleep(2.5)
        if not alive(): return None
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(8)
    return dump()


def user_like_state():
    """Wallpaper on home + glass + desk pet on, like the user's phone, then cold start."""
    go_home()
    for _ in range(2):
        if tap_any(["Settings", "设置"], wait=3, exact=True): break
    snap("settings")
    for _ in range(6):
        if tap_any(["壁纸与形象"], wait=4): break
        scroll_down()
    snap("personalize")
    # Photo wallpaper (the user's phone has one): pick /sdcard/Pictures/smoke-wall.jpg
    picked = False
    for _ in range(4):
        if find(dump(), "相册", exact=True): break
        scroll_down()
    p = find(dump(), "相册", exact=True)
    if p:
        sh(f"input tap {p[0]} {max(0, p[1] - 90)}"); time.sleep(5)
        for _ in range(3):
            root = snap("picker")
            hit = None
            for t, d, x, y, n in nodes(root):
                if "smoke-wall" in t or "smoke-wall" in d or d.startswith("Photo taken") or d.startswith("Image") or "照片" in d:
                    hit = (x, y); break
            if hit:
                sh(f"input tap {hit[0]} {hit[1]}"); time.sleep(5); picked = True; break
            tap_any(["Browse", "Allow", "Images", "Pictures"], wait=3)
        snap("after-pick")
    if not picked:
        tap_any(["极光"], wait=2, exact=True)
    print("photo wallpaper picked:", picked, flush=True)
    for _ in range(4):
        if find(dump(), "首页也用这张壁纸"):
            toggle_near("首页也用这张壁纸"); break
        scroll_down()
    for _ in range(6):
        if find(dump(), "显示桌宠"):
            toggle_near("显示桌宠"); break
        scroll_down()
    snap("personalize-done")
    check("personalize toggles")
    for _ in range(3):
        sh("input keyevent KEYCODE_BACK"); time.sleep(2)
    snap("home-with-wallpaper")
    check("home with wallpaper + pet")
    sh(f"am force-stop {PKG}"); time.sleep(2)
    relaunch(); snap("cold-launch-user-state"); check("cold launch with wallpaper + pet + chats")
    time.sleep(10); snap("cold-launch-user-state-10s"); check("10 s after cold launch (user state)")
    dump_fatal()


def dump_fatal():
    out = adb("logcat", "-d", "-v", "threadtime", "-b", "crash", timeout=60)
    print("---- crash buffer ----\n" + out[-15000:], flush=True)


page_missing = []


def open_page(step, tap_labels, expect, wait=6, exact=False, linger=0):
    """[build-31] open a page from home, check the app survives and the page title shows."""
    root = go_home()
    if root is None:
        relaunch(); root = dump()
    ok = False
    for lab in tap_labels:
        lab, ex = lab if isinstance(lab, tuple) else (lab, exact)
        if find(root, lab, ex):
            ok = tap_text(lab, exact=ex, wait=wait, root=root); break
    if not ok:
        # Today card may be collapsed
        tap_text("展开", wait=2); root = dump()
        for lab in tap_labels:
            lab, ex = lab if isinstance(lab, tuple) else (lab, exact)
            if find(root, lab, ex):
                ok = tap_text(lab, exact=ex, wait=wait, root=root); break
    if linger: time.sleep(linger)
    r = snap(step.replace(" ", "-"))
    alive_ok = check(step)
    seen = any(find(r, e) for e in expect) if r is not None else False
    print(f"PAGE {step}: tapped={ok} title_seen={seen} alive={alive_ok}", flush=True)
    if not (ok and seen): page_missing.append(step)
    if alive_ok:
        sh("input keyevent KEYCODE_BACK"); time.sleep(2.5)
    else:
        relaunch()


def build32_apps():
    """[build-32] 「应用」 catalog: open, search, a detail page, tap 网站 ↗ (Custom Tab / browser), come back alive."""
    root = go_home()
    if root is None:
        relaunch(); root = dump()
    if not (tap_text("账号：", wait=6, root=root) or tap_text("账号", exact=True, wait=6)):
        page_missing.append("应用 (not opened)"); return
    r = snap("apps-catalog"); check("open 应用 catalog")
    if r is None or not (find(r, "搜索应用") or find(r, "应用", True)):
        page_missing.append("应用 title")
    if tap_text("搜索应用", wait=2):
        type_text("groq"); time.sleep(2)
        snap("apps-search"); check("search 应用")
        sh("input keyevent KEYCODE_BACK"); time.sleep(1)  # hide keyboard
    if tap_text("Groq", exact=True, wait=4):
        snap("apps-detail"); check("open app detail")
        for _ in range(4):
            r = dump()
            if r is not None and find(r, "网站", True): break
            scroll_down(); time.sleep(1)
        if tap_text("网站", exact=True, wait=6):
            snap("apps-site-link"); check("tap 网站 ↗")
            sh("input keyevent KEYCODE_BACK"); time.sleep(2)
            relaunch_needed = not alive()
            if relaunch_needed: relaunch()
            check("back from 网站 ↗")
        else:
            page_missing.append("网站 ↗ row")
    else:
        page_missing.append("Groq row")
    for _ in range(3):
        sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)


def build32_visual():
    """[build-32] screens the user asked to be looked at, plus the pet frame check."""
    # settings grouped page
    root = go_home()
    for lab in ["设置", "Settings"]:
        if tap_text(lab, exact=True, wait=4): break
    else:
        sh("input keyevent KEYCODE_MENU"); time.sleep(2)
    snap("v-settings"); check("settings page")
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1)
    # login pages: aliyun + supabase through 应用 → detail → + 添加
    for name in ["阿里云", "Supabase"]:
        sh(f"am force-stop {PKG}"); time.sleep(1); relaunch(); go_home()
        if not (tap_text("账号：", wait=5) or tap_text("账号", exact=True, wait=5) or tap_text("应用", exact=True, wait=5)):
            page_missing.append(f"login {name}: 应用 not opened"); continue
        if tap_text("搜索应用", wait=2):
            type_text("aliyun" if name == "阿里云" else "supabase"); time.sleep(2); sh("input keyevent KEYCODE_BACK"); time.sleep(1)
        if tap_text(name, wait=4):
            snap(f"v-detail-{name}")
            if tap_text("+ 添加", wait=12) or tap_text("添加", wait=12):
                time.sleep(10)
                snap(f"v-login-{name}"); check(f"login page {name}")
        for _ in range(4): sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
        if not alive(): relaunch()
    # 消息 feed + detail sheet
    open_page("消息 feed visual", [("消息", True)], ["我关注的", "全部"], linger=10)
    # detail sheet: open the feed again and tap the first card we can find
    go_home()
    if tap_text("消息", exact=True, wait=8):
        time.sleep(6)
        snap("v-feed")
        opened = False
        for lab in ["智谱", "阿里云百炼", "硅基流动", "Groq", "OpenRouter", "Gemini", "免费"]:
            if tap_text(lab, wait=4):
                opened = True; break
        if opened:
            time.sleep(2); snap("v-feed-detail"); check("feed detail sheet")
            sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
        else:
            page_missing.append("feed card (none tappable)")
        for _ in range(2): sh("input keyevent KEYCODE_BACK"); time.sleep(1)
    # server quick link state: open smoke server, wait > 10 s, capture
    go_home()
    if tap_text("服务器：", wait=6) or tap_work(wait=6):
        if tap_text("smoke", exact=True, wait=8):
            for _ in range(3): scroll_down(); time.sleep(1)
            time.sleep(14); snap("v-server-quicklink"); check("server quick link state")
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1)
    # desk pet: start it, then hold each frame (adb self-test) and capture; plus 3 natural frames
    selftest("pet", "show", timeout=20)
    sh("input keyevent KEYCODE_HOME"); time.sleep(5)
    for i in range(3):
        snap(f"v-pet-{i}"); time.sleep(0.7)
    for fr in ["blink", "mouth_a", "mouth_o", "think"]:
        selftest("pet", fr, timeout=20); time.sleep(0.8)
        snap(f"v-pet-{fr}")
    time.sleep(10)
    print("PET LOG:\n" + sh("logcat -d -s MinesPet:I", timeout=30)[-2000:], flush=True)


def build33_sources():
    """[build-33] logins become data sources automatically: code extraction, auto-detect of a
    signed-in GitHub + X session (no taps), a simulated code e-mail read through the mailbox path
    and filled into a page, and the 消息 「接入更多来源」 card no longer asking for signed-in sources."""
    probe = selftest("extract", "【阿里云】您的验证码为 482913，5分钟内有效。")
    if "unknown method" in probe:
        print("  (build-33 self-tests not in this APK, skipping)", flush=True); return
    if "482913" not in probe:
        selftest_fail.append("b33 extract code")
    go_home()
    out = selftest("sources", "fake", timeout=60)
    time.sleep(0.6); snap("b33-auto-toast")
    # "Result: Bundle[{result={...}}]": decode just the JSON object (a greedy regex grabbed the Bundle's "}" too)
    i = out.find("result={")
    try:
        j = json.JSONDecoder().raw_decode(out[i + 7:])[0] if i >= 0 else {}
    except Exception:
        j = {}
    print("  b33 sources:", j, flush=True)
    vault = j.get("vault", []); miss = j.get("missing", []); act = j.get("active", [])
    if "github" not in vault or "x" not in vault:
        selftest_fail.append(f"b33 auto-detect (vault={vault})")
    if "GitHub" in miss or "X" in miss:
        selftest_fail.append(f"b33 card still asks for signed-in sources ({miss})")
    if "github" not in act or "x" not in act:
        selftest_fail.append(f"b33 active sources ({act})")
    out = selftest("mailcode", "http://10.0.2.2:8088/feed/atom|http://10.0.2.2:8088/otp", timeout=120)
    if '"code":"482913"' not in out:
        selftest_fail.append("b33 code e-mail not picked up")
    if "ok:482913" not in out:
        selftest_fail.append("b33 code not filled into the page")
    # 消息: the sources card must not list GitHub / X any more
    sh(f"am force-stop {PKG}"); time.sleep(1); relaunch(); time.sleep(4)
    snap("b33-relaunch")
    go_home()
    if tap_text("消息", exact=True, wait=8):
        time.sleep(6)
        r = snap("b33-feed")
        t = " ".join(texts(r))
        if "＋ GitHub" in t:
            selftest_fail.append("b33 feed card shows signed-in sources")
        if "＋ X" in t:
            # the fake auth_token is sent to the real x.com by the X reader; X rejects it and expires the
            # cookie, so after a relaunch X is correctly "not signed in" (seen on API 34 in smoke run 18)
            print("  note: X shown as missing after relaunch (real x.com expired the fake auth_token)", flush=True)
        for _ in range(2): sh("input keyevent KEYCODE_BACK"); time.sleep(1)
    else:
        page_missing.append("b33 消息")
    go_home()
    if tap_text("账号：", wait=6) or tap_text("账号", exact=True, wait=6):
        time.sleep(2); snap("b33-apps")
        if tap_text("搜索应用", wait=2):
            type_text("gmail"); time.sleep(2); sh("input keyevent KEYCODE_BACK"); time.sleep(1)
            if tap_text("Gmail", exact=True, wait=4):
                time.sleep(2); snap("b33-gmail-detail")
        for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
    if not alive(): relaunch()
    # [build-33] polished pages: 知识库 / 模型路由与分流 / 测试与上报 (from 设置)
    for lab, name in [("知识库", "b33-knowledge"), ("模型路由与分流", "b33-routing"), ("一键冒烟测试", "b33-report")]:
        go_home()
        for st in ["设置", "Settings"]:
            if tap_text(st, exact=True, wait=4): break
        opened = False
        for _ in range(6):
            if tap_text(lab, wait=4):
                opened = True; break
            scroll_down(); time.sleep(1)
        if opened:
            time.sleep(4); r = snap(name); check(name)
            t = " ".join(texts(r))
            if "BM25" in t or "failed to connect" in t:
                selftest_fail.append(f"{name} shows raw tech text")
            scroll_down(); time.sleep(1); snap(name + "-2")
        else:
            page_missing.append(name)
        for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
        if not alive(): relaunch()
    # voice screen: no 线路/模型 line under the status text (details only in ⚙)
    go_home()
    if tap_text("实时语音", wait=8):
        time.sleep(8)
        r = snap("b33-voice")
        if any("线路：" in t or "模型：" in t for t in texts(r)):
            selftest_fail.append("b33 voice screen still shows route/model")
        for _ in range(2): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
    else:
        page_missing.append("b33 voice")
    if not alive(): relaunch()


def garbage_test():
    """[build-34] Degenerating model: Mines must abort the stream, retry once with lower thinking, then stop / mark unstable."""
    root = dump()
    e = edits(root)
    if not e:
        selftest_fail.append("garbage test: no input"); return
    sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text("GARBAGETEST")
    sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
    tap_any(["Send", "发送"], wait=3) or sh("input keyevent KEYCODE_ENTER")
    seen = False
    for _ in range(20):
        time.sleep(3)
        root = dump()
        if find(root, "乱码") or find(root, "不稳定"):
            seen = True; break
    snap("v34-garbage")
    try:
        st = json.loads(subprocess.run("curl -s http://127.0.0.1:8088/stats", shell=True, capture_output=True, text=True, timeout=10).stdout or "{}")
    except Exception as ex:
        st = {"error": str(ex)}
    print("  garbage test: notice_seen=%s posts=%s" % (seen, st.get("garbage_posts")), flush=True)
    if not seen: selftest_fail.append("garbage: no 乱码/不稳定 notice")
    if not (st.get("garbage_posts") in (2, 3)): selftest_fail.append("garbage: posts=%s (want 2: first try + one retry)" % st.get("garbage_posts"))
    check("garbage abort")


def selftest_json(method, arg, timeout=60):
    out = selftest(method, arg, timeout=timeout)
    i = out.find("result={")
    try:
        return json.JSONDecoder().raw_decode(out[i + 7:])[0] if i >= 0 else {}
    except Exception:
        return {}


def build34():
    """[build-34] design system pages, per-character theme switch, voice showcase + TTS picker,
    degeneration detector + failure-pattern guard self-tests."""
    j = selftest_json("degen", "x")
    if not j.get("ok"): selftest_fail.append(f"b34 degen selftest {j}")
    j = selftest_json("failguard", "x")
    if not j.get("ok"): selftest_fail.append(f"b34 failguard selftest {j}")
    j = selftest_json("tts", "gemini")
    # [build-35] v2 token (edge-tts 7.3.1) for t=1760400000 is checked in build35()
    # per-character theme: home + voice page per character
    for cid, want in [("claude", "B5532F"), ("whale", "2E73C2"), ("gemini", "835EC2")]:
        j = selftest_json("theme", cid)
        if not str(j.get("primary", "")).upper().endswith(want) or j.get("style") != cid:
            selftest_fail.append(f"b34 theme {cid} {j}")
        sh(f"am force-stop {PKG}"); time.sleep(1); relaunch(); go_home(); time.sleep(3)
        r = snap(f"v34-home-{cid}"); check(f"home with {cid}")
        if r is not None and find(r, "娘"): selftest_fail.append(f"b34 '娘' still shown ({cid})")
        if tap_text("实时语音", wait=6):
            time.sleep(4); snap(f"v34-voice-{cid}"); check(f"voice page {cid}")
            vr = dump()
            if vr is not None and (find(vr, "线路") or find(vr, "模型：")): selftest_fail.append("b34 voice page shows route/model text")
            for _ in range(2): sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
            if not alive(): relaunch()
    selftest_json("theme", "claude")
    sh(f"am force-stop {PKG}"); time.sleep(1); relaunch(); go_home(); time.sleep(2)
    # voice settings: TTS picker + free voice + 试听 button
    if tap_text("实时语音", wait=6):
        time.sleep(3)
        if tap_text("语音设置", wait=4):
            time.sleep(2); snap("v34-voice-settings"); check("voice settings sheet")
            if tap_text("免费声音", exact=True, wait=3):
                r = snap("v34-tts-edge")
                for _ in range(3):
                    if r is not None and find(r, "试听"): break
                    scroll_down(); time.sleep(1); r = dump()
                snap("v34-tts-edge-scrolled")
                if r is None or not find(r, "试听"): page_missing.append("b34 试听 button")
                if r is None or not find(r, "语速"): page_missing.append("b34 speed slider")
            else:
                page_missing.append("b34 TTS engine picker")
            for _ in range(3):
                r = dump()
                if r is not None and find(r, "声纹"): break
                scroll_down(); time.sleep(1)
            snap("v34-voiceprint-row")
            if tap_text("录入声纹", wait=3) or tap_text("声纹", wait=3):
                time.sleep(2); snap("v34-voiceprint"); check("voiceprint enrol page")
                sh("input keyevent KEYCODE_BACK"); time.sleep(1)
            else:
                page_missing.append("b34 voiceprint row")
            selftest("tts", "gemini")
        for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
        if not alive(): relaunch()
    # design pages
    open_page("b34 消息", [("消息", True)], ["全部", "要处理"], linger=4)
    open_page("b34 应用", [("应用与账号", False), ("账号：", False)], ["搜索", "应用"], linger=3)
    # [build-34] in-app browser zoom in both UA modes (pinch/double-tap on, zoom-locking viewport rewritten)
    for prof in ("mobile", "desktop"):
        j = selftest_json("zoom", prof, timeout=40)
        print(f"  zoom {prof}: {j}", flush=True)
        if not j.get("ok"): selftest_fail.append(f"b34 zoom {prof} {j}")
    # [build-34] agent browser renders real pages (BrowserTabPool navigate / get_text / screenshot), both UA modes.
    # example.com must pass; vulbox.com must pass whenever it is reachable from the runner (title or text came back).
    def _reach(u):
        try:
            import urllib.request
            urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 14) Chrome/124.0 Mobile"}), timeout=20).read(200)
            return True
        except Exception as e:
            print(f"  runner cannot reach {u}: {e}", flush=True); return False
    vulbox_ok = _reach("https://www.vulbox.com/")
    for url in ("https://example.com/", "https://www.vulbox.com/"):
        if "vulbox" in url and not vulbox_ok:
            print("  (vulbox.com not reachable from the runner; skipped)", flush=True); continue
        for prof in ("mobile", "desktop"):
            j = selftest_json("webrender", f"{url}|{prof}", timeout=130)
            print(f"  webrender {url} {prof}: {json.dumps(j, ensure_ascii=False)[:900]}", flush=True)
            errpage = any(k in json.dumps(j, ensure_ascii=False) for k in ("Webpage not available", "net::ERR_", "ERR_NAME_NOT_RESOLVED", "ERR_INTERNET_DISCONNECTED"))
            if j.get("ok") and not errpage: continue
            selftest_fail.append(f"b34 webrender{' (error page: emulator had no network?)' if errpage else ''} {url} {prof} {json.dumps(j, ensure_ascii=False)[:400]}")
    # settings + its design pages
    go_home()
    if tap_text("设置", exact=True, wait=5):
        time.sleep(2); r = snap("v34-settings"); check("settings page")
        if r is None or not find(r, "常用"): page_missing.append("b34 设置")
        for lab, shot in [("知识库", "v34-knowledge"), ("模型路由", "v34-routing")]:
            for _ in range(4):
                r = dump()
                if r is not None and find(r, lab): break
                scroll_down(); time.sleep(1)
            if tap_text(lab, wait=5):
                time.sleep(3); snap(shot); check(shot)
                sh("input keyevent KEYCODE_BACK"); time.sleep(2)
            else:
                page_missing.append("b34 " + lab)
        for _ in range(2): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
        if not alive(): relaunch()
    else:
        page_missing.append("b34 设置 (gear)")
    # desk pet body motion (articulated mesh): wave / look (turn) / happy, two shots each
    selftest("pet", "show", timeout=20)
    sh("input keyevent KEYCODE_HOME"); time.sleep(4)
    for mo in ["WAVE", "LOOK", "HAPPY"]:
        selftest("pet", "mode:" + mo, timeout=20); time.sleep(0.6)
        snap(f"v34-pet-{mo.lower()}-a"); time.sleep(0.9); snap(f"v34-pet-{mo.lower()}-b")
    relaunch()


def build35():
    """[build-35] Edge TTS v2 token + live synthesis + fallback, voiceprint pipeline + enrolment UI (injected mic),
    offline model mirrors, MCP merge (DSH 装了又掉), login WebViews."""
    j = selftest_json("tts", "edge")
    print(f"  b35 tts: {j}", flush=True)
    if not j.get("drm_ok") or not str(j.get("gec", "")).startswith("F3E7AA1D0E82AD85-"): selftest_fail.append(f"b35 edge v2 token {j}")
    j = selftest_json("tts", "live", timeout=120)
    print(f"  b35 tts live: {j}", flush=True)
    if (j.get("edge_samples") or -1) <= 0: selftest_fail.append(f"b35 edge live synthesis {j}")
    if not j.get("chain_used") or (j.get("chain_samples") or 0) <= 0: selftest_fail.append(f"b35 tts chain {j}")
    selftest("tts", "gemini")
    j = selftest_json("mcpmerge", "x")
    print(f"  b35 mcpmerge: {j}", flush=True)
    if not j.get("kept"): selftest_fail.append(f"b35 dsh server entry lost on app save {j}")
    j = selftest_json("voiceprint", "pipeline", timeout=300)
    print(f"  b35 voiceprint pipeline: {j}", flush=True)
    if (j.get("clip_samples") or 0) <= 0: selftest_fail.append(f"b35 voiceprint clips {j}")
    if "静音" not in str(j.get("silence_error", "")): selftest_fail.append(f"b35 voiceprint silence not reported {j}")
    if not j.get("model"): selftest_fail.append(f"b35 voiceprint model download {j}")
    elif not j.get("enrolled") or (j.get("score_self") or 0) < 0.5: selftest_fail.append(f"b35 voiceprint enrol {j}")
    selftest("voiceprint", "clear")
    # web page translation (English page → Chinese, in place)
    j = selftest_json("translate", "x", timeout=120)
    print(f"  b35 translate: {json.dumps(j, ensure_ascii=False)[:300]}", flush=True)
    if not j.get("foreign") or (j.get("applied") or 0) <= 0 or not re.search(r"[\u4e00-\u9fff]", str(j.get("text", ""))):
        selftest_fail.append(f"b35 page translation {json.dumps(j, ensure_ascii=False)[:200]}")
    # offline model (wake word / ASR) through the mirrored downloader
    j = selftest_json("models", "fresh", timeout=400)
    print(f"  b35 models: {j}", flush=True)
    if not j.get("vosk_ok"): selftest_fail.append(f"b35 vosk model download {j}")
    # voiceprint enrolment page with the injected mic: three phrases → saved; then silence → plain reason
    for kind, want in (("speech", "录好了"), ("silence", "静音")):
        selftest("voiceprint", "inject:" + kind)
        go_home()
        ok = False
        if tap_text("实时语音", wait=6):
            time.sleep(3)
            if tap_text("语音设置", wait=4):
                for _ in range(4):
                    r = dump()
                    if r is not None and find(r, "录入声纹"): break
                    scroll_down(); time.sleep(1)
                if tap_text("录入声纹", wait=3) or tap_text("重新录入声纹", wait=3):
                    time.sleep(2); snap(f"v35-vp-{kind}-open")
                    for i in range(3 if kind == "speech" else 1):
                        tap_text("开始", wait=2) or tap_text("读第", wait=2) or tap_text("再试一次", wait=2)
                        time.sleep(1.2); snap(f"v35-vp-{kind}-listening-{i}")
                        time.sleep(5 if kind == "speech" else 8)
                    for _ in range(40):
                        r = dump()
                        if r is not None and find(r, want): ok = True; break
                        time.sleep(3)
                    snap(f"v35-vp-{kind}-result")
        if not ok: selftest_fail.append(f"b35 voiceprint enrol page ({kind}) never showed 「{want}」")
        for _ in range(4): sh("input keyevent KEYCODE_BACK"); time.sleep(1)
        if not alive(): relaunch()
    selftest("voiceprint", "inject:off"); selftest("voiceprint", "clear")
    # login / key-fetch WebView setup renders real pages
    for u in ("https://bailian.console.aliyun.com/", "https://signin.aliyun.com/", "https://www.vulbox.com/", "https://github.com/login"):
        j = selftest_json("loginweb", u, timeout=70)
        print(f"  b35 loginweb {u}: {json.dumps(j, ensure_ascii=False)[:300]}", flush=True)
        if (j.get("chars") or -1) < 20: page_missing.append(f"b35 loginweb blank {u}")
    relaunch(); go_home(); time.sleep(2)


def build31_pages():
    open_page("应用 page", ["账号："], ["搜索应用", "应用"])
    open_page("消息 feed page", [("消息", True), ("消息 ", False)], ["我关注的", "全部"], linger=8)
    open_page("server page", ["服务器："], ["服务器", "smoke"])
    open_page("voice page", ["实时语音"], ["语音", "通话", "Gemini"], linger=6)


def feed_351():
    """[build-35.1] 消息 page: tabs 账号异常/我关注的/X/GitHub/值得关注 with counts, 「忽略」 on cards, tap hides + 撤销."""
    go_home()
    if not tap_text("打开消息页", wait=8):
        selftest_fail.append("b351 feed: bar missing"); return
    time.sleep(6)
    r = snap("feed-tabs")
    t = texts(r) if r is not None else []
    for lab in ("账号异常", "我关注的", "X", "GitHub", "值得关注"):
        if not any(re.match(rf"^{re.escape(lab)} \d+", x) for x in t): selftest_fail.append(f"b351 tab missing: {lab} N")
    if any(x.startswith(("要处理 ", "飙升 ", "可学 ")) for x in t): selftest_fail.append("b351 old tabs still shown")
    # pick a tab that has cards: 值得关注 (bundled free offers), else 全部
    for lab in ("值得关注 ", "全部 "):
        p = next(((tx, x, y) for tx, d, x, y, n in nodes(r) if (tx or d).startswith(lab) and not (tx or d).endswith(" 0")), None)
        if p:
            sh(f"input tap {p[1]} {p[2]}"); time.sleep(3)
            if find(dump(), "忽略这条"): break
    r = dump()
    time.sleep(1)
    r = snap("feed-35_1")
    subprocess.run(f"cp {OUT}/{step_no:02d}-feed-35_1.png {OUT}/feed-35_1.png", shell=True)
    if not find(r, "忽略这条"):
        selftest_fail.append("b351 no 忽略 button on cards"); sh("input keyevent KEYCODE_BACK"); time.sleep(2); return
    before = set(x for x in texts(r) if len(x) >= 8)
    tap_text("忽略这条", wait=1.2, root=r)
    r2 = snap("feed-after-ignore")
    after = set(x for x in texts(r2) if len(x) >= 8) if r2 is not None else set()
    gone = [x for x in before - after if "忽略" not in x]
    snackbar = r2 is not None and bool(find(r2, "已忽略"))
    if not snackbar: selftest_fail.append("b351 no 已忽略 · 撤销 bar")
    if not gone: selftest_fail.append("b351 ignored card did not disappear")
    undone = False
    if snackbar and tap_text("撤销", wait=2):
        r3 = snap("feed-after-undo")
        undone = r3 is not None and any(g in set(texts(r3)) for g in gone)
    check("消息 page ignore + undo")
    print(f"B351 feed tabs={[x for x in t if re.match(r'^(账号异常|我关注的|X|GitHub|值得关注|其他|全部) ', x)]} ignore_gone={gone[:2]} snackbar={snackbar} undo_back={undone}", flush=True)
    sh("input keyevent KEYCODE_BACK"); time.sleep(2)


def keyagent_351():
    """[build-35.1] 一键接入 硅基流动: the key-fetch WebView must not be a bare white page (progress / reason / 重试 / 浏览器打开)."""
    sh(f"am start -n {PKG}/com.openminis.app.MainActivity --es mines_route plus_connections --es mines_open_kind siliconflow", timeout=60)
    time.sleep(6)
    snap("siliconflow-detail")
    if not (tap_text("一键接入", wait=3) or tap_text("再生成一个新密钥", wait=3)):
        scroll_down(); time.sleep(1)
        if not (tap_text("一键接入", wait=3) or tap_text("再生成一个新密钥", wait=3)):
            page_missing.append("b351 一键接入 button not found"); return
    time.sleep(18)
    r = snap("keyagent-siliconflow")
    subprocess.run(f"cp {OUT}/{step_no:02d}-keyagent-siliconflow.png {OUT}/keyagent-35_1.png", shell=True)
    time.sleep(14)
    r2 = snap("keyagent-siliconflow-later")
    check("一键接入 硅基流动 WebView")
    t = texts(r2) if r2 is not None else []
    if not any("重试" in x for x in t) or not any("浏览器打开" in x for x in t): selftest_fail.append("b351 keyagent: no 重试 / 浏览器打开")
    print(f"B351 keyagent texts={t[:30]}", flush=True)
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
    if not alive(): relaunch()


def cards_351():
    """[build-35.1] 支付卡: fill JS on a local form, OCR parse, per-account profile; page opens (no lock screen → no prompt)."""
    j = selftest_json("cardfill", "x", timeout=60)
    print(f"  b351 cardfill: {j}", flush=True)
    vals = str(j.get("values", "")).split("|")
    if not (j.get("detect") == "1" and vals[:1] == ["4242424242424242"] and len(vals) >= 6 and vals[5] == "0"):
        selftest_fail.append(f"b351 cardfill {j}")
    if j.get("ocr_number") != "4242424242424242" or j.get("ocr_exp") != "08/29" or j.get("ocr_name") != "JOHN APPLESEED":
        selftest_fail.append(f"b351 card OCR parse {j}")
    if not j.get("locked"): selftest_fail.append("b351 agent lock after fill missing")
    sh(f"am start -n {PKG}/com.openminis.app.MainActivity --es mines_route plus_cards", timeout=60)
    time.sleep(6)
    r = snap("pay-cards")
    subprocess.run(f"cp {OUT}/{step_no:02d}-pay-cards.png {OUT}/cards-35_1.png", shell=True)
    check("支付卡 page")
    if r is None or not (find(r, "添加卡") or find(r, "验证")): page_missing.append("b351 支付卡 page")
    sh("input keyevent KEYCODE_BACK"); time.sleep(2)


def quick_home_351():
    """[build-35.1] quick mode (target line 2 = quick): home = 最近对话 + one slim 消息 bar → own 消息 page, back returns."""
    root = go_home()
    if root is None: relaunch(); root = go_home()
    time.sleep(3)
    r = snap("home-35_1")
    subprocess.run(f"cp {OUT}/{step_no:02d}-home-35_1.png {OUT}/home-35_1.png", shell=True)
    t = "\n".join(texts(r)) if r is not None else ""
    for want, label in (("最近对话", "最近对话 header"), ("打开消息页", "slim 消息 bar")):
        if want not in t: page_missing.append(f"b351 home missing {label}")
    for bad in ("要处理的消息", "对话（当前）"):
        if bad in t: selftest_fail.append(f"b351 home still shows {bad}")
    if not tap_text("打开消息页", wait=6, root=r):
        selftest_fail.append("b351 slim 消息 bar not tappable"); return
    time.sleep(3)
    r2 = snap("digest-from-bar")
    check("open 消息 page from slim bar")
    t2 = "\n".join(texts(r2)) if r2 is not None else ""
    if "打开消息页" in t2 or "最近对话" in t2: selftest_fail.append("b351 消息 page did not open as its own screen")
    sh("input keyevent KEYCODE_BACK"); time.sleep(3)
    r3 = snap("home-after-back")
    check("back from 消息 page")
    if r3 is None or not find(r3, "最近对话"): selftest_fail.append("b351 back did not return to home")
    feed_351()
    try:
        cards_351()
    except Exception as e:
        print("cards error:", e, flush=True); selftest_fail.append(f"cards error {e}")
    try:
        keyagent_351()
    except Exception as e:
        print("keyagent error:", e, flush=True)
    print(f"B351 home_ok={'最近对话' in t and '打开消息页' in t} page_opened={bool(t2)} back_home={r3 is not None and bool(find(r3, '最近对话'))}", flush=True)


def voice_352():
    """[build-35.2] voice: page opens; 测试语音 with a fake key → KEY error (not config); 「设定好我的声音录入」 opens 声纹录入."""
    j = selftest_json("voicediag", "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUM", timeout=120)
    print(f"B352 voicediag kind={j.get('kind')} lines={j.get('lines')}", flush=True)
    if j.get("kind") != "key": selftest_fail.append(f"b352 voicediag kind={j.get('kind')} (want key)")
    go_home()
    opened = tap_text("实时语音", wait=8)
    if not opened:
        sh(f"am start -n {PKG}/com.openminis.app.MainActivity --es mines_route plus_voice_live"); time.sleep(5)
    time.sleep(4)
    r = snap("v352-voice")
    check("voice page opens")
    t = " ".join(texts(r)) if r is not None else ""
    if not any(w in t for w in ("开始语音", "Gemini", "通话", "普通语音", "账号连接")): page_missing.append("b352 voice page")
    ji = selftest_json("voiceintent", "设定好我的声音录入", timeout=30)
    print(f"B352 voiceintent {ji}", flush=True)
    if ji.get("page") != "voiceprint_enroll": selftest_fail.append(f"b352 voiceintent page={ji.get('page')}")
    if not ji.get("promise"): selftest_fail.append("b352 promise detector")
    ok = False
    for _ in range(8):
        time.sleep(1.5)
        r2 = dump()
        if r2 is not None and find(r2, "录入你的声音"): ok = True; break
    snap("v352-enrol")
    check("voiceprint intent opens enrolment")
    if not ok: selftest_fail.append("b352 声纹录入 page did not open from the voice intent")
    print(f"B352 voice_page={bool(t)} enrol_opened={ok}", flush=True)
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)


def node_by_desc(root, prefix):
    for t, d, x, y, n in nodes(root):
        if (d or "").startswith(prefix) or (t or "").startswith(prefix):
            return x, y
    return None


def tap_scrolled(prefix, tries=6, wait=4):
    """tap a node whose text / content-description starts with prefix, scrolling the page up until it is on screen"""
    for _ in range(tries):
        r = dump()
        p = node_by_desc(r, prefix)
        if p and 150 < p[1] < SCREEN[1] - 150:
            sh(f"input tap {p[0]} {p[1]}"); time.sleep(wait); return True
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.75)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 400"); time.sleep(1.5)
    print(f"  (not reachable: {prefix})", flush=True)
    return False


def open_tab(label):
    for _ in range(4):
        r = dump()
        p = next(((x, y) for tx, d, x, y, n in nodes(r) if (tx or d).startswith(label)), None)
        if p and p[0] < SCREEN[0] - 40:
            sh(f"input tap {p[0]} {p[1]}"); time.sleep(3); return True
        a = next(((x, y) for tx, d, x, y, n in nodes(r) if (tx or d).startswith("账号异常 ")), None)
        if not a: return False
        sh(f"input swipe {int(SCREEN[0]*0.85)} {a[1]} {int(SCREEN[0]*0.15)} {a[1]} 300"); time.sleep(1.5)
    return False


def tap_pill_under(headline, pill):
    """tap the action pill (text startswith pill) of the card whose headline contains [headline]"""
    for _ in range(5):
        r = dump()
        h = next(((x, y) for tx, d, x, y, n in nodes(r) if headline in (tx or d)), None)
        if h:
            cand = sorted(((y, x) for tx, d, x, y, n in nodes(r) if (tx or d).startswith(pill) and y > h[1]), key=lambda v: v[0])
            if cand and cand[0][0] < SCREEN[1] - 120:
                sh(f"input tap {cand[0][1]} {cand[0][0]}"); return True
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.7)} {SCREEN[0]//2} {int(SCREEN[1]*0.4)} 400"); time.sleep(1.5)
    print(f"  (pill not reachable: {pill} under {headline})", flush=True)
    return False


def b353():
    """[build-35.3] 消息 buttons really act; 「去官网获取」 fills + saves a key from a (fake) page; account page masked + collapsed."""
    res = {}
    # ---- 1. 值得关注 「帮我领」 → a Mines chat with the task, sent ----
    j = selftest_json("feedinject", "both", timeout=30)
    print(f"B353 inject {j}", flush=True)
    go_home()
    if tap_text("打开消息页", wait=8):
        for attempt in range(2):
            r = dump()
            open_tab("值得关注 ")
            snap("b353-deal-tab")
            if tap_pill_under("SmokeAI", "✦ 帮我领"):
                snap("b353-deal-tapped")  # 「处理中…」 / toast
                ok = False
                for _ in range(10):
                    time.sleep(2)
                    r2 = dump(); t2 = " ".join(texts(r2)) if r2 is not None else ""
                    if "帮我领取" in t2 or ("SmokeAI" in t2 and "打开消息页" not in t2 and "值得关注 " not in t2): ok = True; break
                st = selftest_json("feedstate", "", timeout=20)
                snap("b353-deal-chat")
                res["deal_chat"] = ok and bool(st.get("session"))
                print(f"B353 deal chat_shown={ok} state={st}", flush=True)
                break
            print("  retry deal tab", flush=True)
        if not res.get("deal_chat"): selftest_fail.append("b353 帮我领 did not open a chat with the sent task")
    else:
        selftest_fail.append("b353 消息 bar missing")
    # ---- 2. 账号异常 「一键修复」 → repair flow (KeyAgentDialog) ----
    go_home()
    if tap_text("打开消息页", wait=8):
        open_tab("账号异常 ")
        snap("b353-account-tab")
        ok = False
        if tap_pill_under("SmokeKey", "✦ 一键修复"):
            for _ in range(8):
                time.sleep(2)
                r2 = dump(); t2 = " ".join(texts(r2)) if r2 is not None else ""
                if "正在接入 硅基流动" in t2 or "正在修复 硅基流动" in t2 or "不会点任何付费按钮" in t2: ok = True; break
            snap("b353-fix-flow")
        res["fix_flow"] = ok
        print(f"B353 fix_flow={ok}", flush=True)
        if not ok: selftest_fail.append("b353 一键修复 did not start the repair flow")
        tap_text("关闭", exact=True, wait=2)
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
    # ---- 3. 「去官网获取」 on DeepSeek with a fake key page → key read, saved, tested ----
    key = "sk-" + "d00dfeed" * 4
    go_home()
    selftest_json("keyfake", f"deepseek|http://10.0.2.2:8088/fakekeys|{key}", timeout=20)
    selftest("openkind", "deepseek", timeout=20); time.sleep(5)
    snap("b353-deepseek")
    got = False
    if tap_scrolled("去官网获取", wait=3):
        for _ in range(20):
            time.sleep(2)
            r2 = dump()
            if r2 is not None and find(r2, "已接入 DeepSeek"): break
        snap("b353-key-dialog")
        ks = selftest_json("keystate", "deepseek", timeout=20)
        got = bool(ks.get("has")) and ks.get("tail") == key[-8:]
        print(f"B353 keystate={ks}", flush=True)
        tap_text("完成", exact=True, wait=2)
    res["key_auto"] = got
    if not got: selftest_fail.append("b353 去官网获取 did not fill + save the key from the page")
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
    # ---- 4. account page: masked, collapsed per service; expanded rows one line (font 1.3) ----
    selftest_json("fakeaccounts", "", timeout=20)
    sh("settings put system font_scale 1.3"); time.sleep(3)
    go_home()
    selftest("openkind", "", timeout=20); time.sleep(5)
    if not find(dump(), "搜索应用"):
        tap_text("应用与账号", wait=6)
    full = "deningyang740@qq.com"
    # bring the 已连接 section on screen without tapping
    for _ in range(6):
        r = dump(); p = node_by_desc(r, "商汤 SenseNova")
        if p and 150 < p[1] < SCREEN[1] - 150: break
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.75)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 400"); time.sleep(1.5)
    r = snap("b353-accounts-collapsed")
    subprocess.run(f"cp {OUT}/{step_no:02d}-b353-accounts-collapsed.png {OUT}/accounts-collapsed.png", shell=True)
    t = "\n".join(texts(r)) if r is not None else ""
    collapsed_ok = ("个账号" in t) and ("API Key · " not in t)
    if full in t: selftest_fail.append("b353 full e-mail shown (collapsed)")
    tap_scrolled("商汤 SenseNova", wait=2)
    tap_scrolled("QQ 邮箱", wait=2)
    r = snap("b353-accounts-expanded")
    subprocess.run(f"cp {OUT}/{step_no:02d}-b353-accounts-expanded.png {OUT}/accounts-expanded.png", shell=True)
    t = "\n".join(texts(r)) if r is not None else ""
    if full in t: selftest_fail.append("b353 full e-mail shown (expanded)")
    masked = "de***40@qq.com" in t
    print(f"B353 collapsed_ok={collapsed_ok} masked={masked} full_absent={full not in t} res={res}", flush=True)
    if not collapsed_ok: page_missing.append("b353 collapsed summary not seen")
    if not masked: page_missing.append("b353 masked e-mail not seen in expanded rows")
    sh("settings put system font_scale 1.0"); time.sleep(2)


def b36_home():
    for _ in range(4):
        r = dump()
        if r is not None and find(r, "最近对话"): return r
        if r is not None and (find(r, "Google search") or find(r, "At a glance")): break
        sh("input keyevent KEYCODE_BACK"); time.sleep(2)
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(8)
    return dump()


def shot36(name):
    r = snap("b36-" + name)
    subprocess.run(f"cp {OUT}/{step_no:02d}-b36-{name}.png {OUT}/b36-{name}.png", shell=True)
    return r


def b36():
    """[build-36] warm UI: home = conversations + bottom bar (消息 badge); 消息 = 4 tabs + 「N 件要处理」 strip + detail
    sheet with a working main pill; settings = 6 groups; 账号连接 masked at font 1.3; voice page + voice settings."""
    res = {}
    j = selftest_json("feedinject", "both", timeout=30)
    print(f"B36 inject {j}", flush=True)
    selftest_json("fakeaccounts", "", timeout=20)
    r = b36_home(); time.sleep(3)
    r = shot36("home")
    t = "\n".join(texts(r)) if r is not None else ""
    res["home"] = "最近对话" in t and "消息" in t
    for bad in ("打开消息页", "要处理的消息", "工作位置："):
        if bad in t: selftest_fail.append(f"b36 home still shows {bad}")
    if not res["home"]: page_missing.append("b36 home (最近对话 + 消息 tab)")
    # ---- 消息 from the bottom bar ----
    if not tap_text("消息", exact=True, wait=5): tap_text("消息，", wait=5)
    time.sleep(4)
    r = shot36("messages")
    tx = texts(r) if r is not None else []
    res["tabs"] = all(any(x.startswith(lab) for x in tx) for lab in ("账号异常", "X", "GitHub", "值得关注"))
    res["old_tabs_gone"] = not any(x.startswith(("我关注的", "全部 ", "✎")) for x in tx)
    res["summary"] = any("件要处理" in x for x in tx)
    if not res["tabs"]: selftest_fail.append("b36 消息 tabs missing")
    if not res["old_tabs_gone"]: selftest_fail.append("b36 old tabs (我关注的/全部/✎) still shown")
    if any(x.endswith(": null") or "：null" in x for x in tx): selftest_fail.append("b36 null shown on 消息 page")
    # ---- 「去看」 → 账号异常, open the SmokeKey card's detail sheet ----
    if not tap_text("去看", wait=3): open_tab("账号异常")
    time.sleep(2)
    shot36("account-tab")
    opened = False
    for _ in range(4):
        r = dump()
        h = next(((x, y) for tx_, d, x, y, n in nodes(r) if "SmokeKey" in (tx_ or d) and not (tx_ or d).startswith(("一键修复", "去处理"))), None)
        if h and h[1] < SCREEN[1] - 90:
            sh(f"input tap {h[0]} {h[1]}"); time.sleep(3)
            if find(dump(), "包含的内容") or find(dump(), "关闭"): opened = True; break
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.7)} {SCREEN[0]//2} {int(SCREEN[1]*0.4)} 400"); time.sleep(1.5)
    r = shot36("detail")
    res["detail"] = opened
    if not opened: selftest_fail.append("b36 detail sheet did not open")
    else:
        pill = None
        for lab in ("一键修复：", "去处理：", "开始吧：", "帮我领："):
            pill = find(r, lab)
            if pill: break
        res["pill"] = bool(pill)
        if pill:
            sh(f"input tap {pill[0]} {pill[1]}")
            ok = False
            for _ in range(8):
                time.sleep(2)
                t2 = " ".join(texts(dump()) or [])
                if any(w in t2 for w in ("正在修复", "正在接入", "不会点任何付费按钮", "处理中", "账号连接", "重新检测")): ok = True; break
            shot36("detail-pill-tapped")
            res["pill_acts"] = ok
            if not ok: selftest_fail.append("b36 detail main pill did nothing")
            tap_text("关闭", exact=True, wait=2)
        else: selftest_fail.append("b36 detail main pill not found")
    for _ in range(4): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
    # ---- settings: 6 groups ----
    b36_home()
    if not (tap_text("设置", exact=True, wait=4) or tap_text("Settings", exact=True, wait=4)):
        sh(f"am start -n {PKG}/com.openminis.app.MainActivity --es mines_route settings"); time.sleep(4)
    r = shot36("settings")
    t = "\n".join(texts(r)) if r is not None else ""
    sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.75)} {SCREEN[0]//2} {int(SCREEN[1]*0.3)} 400"); time.sleep(1.5)
    r = shot36("settings-lower")
    t += "\n" + ("\n".join(texts(r)) if r is not None else "")
    res["settings6"] = all(w in t for w in ("语音", "账号与 AI 服务", "网络", "数据", "外观", "测试与上报"))
    if not res["settings6"]: selftest_fail.append("b36 settings 6 groups not all shown")
    if tap_text("账号与 AI 服务", wait=3): shot36("settings-ai"); sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
    # ---- 账号连接 at font 1.3: masked, one line per service ----
    sh("settings put system font_scale 1.3"); time.sleep(3)
    b36_home()
    if not tap_text("应用", exact=True, wait=5): selftest("openkind", "", timeout=20); time.sleep(5)
    time.sleep(3)
    r = shot36("connections")
    t = "\n".join(texts(r)) if r is not None else ""
    res["conn_title"] = "账号连接" in t
    if "deningyang740@qq.com" in t: selftest_fail.append("b36 full e-mail shown on 账号连接")
    for _ in range(2):
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.75)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 400"); time.sleep(1.5)
    shot36("connections-lower")
    sh("settings put system font_scale 1.0"); time.sleep(2)
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)
    # ---- voice page + its settings sheet ----
    b36_home()
    if not tap_text("实时语音", wait=6):
        sh(f"am start -n {PKG}/com.openminis.app.MainActivity --es mines_route plus_voice_live"); time.sleep(5)
    time.sleep(3)
    r = shot36("voice")
    t = " ".join(texts(r)) if r is not None else ""
    res["voice"] = bool(t)
    if find(r, "线路") or find(r, "模型："): selftest_fail.append("b36 voice page shows route/model")
    if tap_text("语音设置", wait=3) or tap_text("设置", wait=3):
        r = shot36("voice-settings")
        t = " ".join(texts(r)) if r is not None else ""
        res["voice_now"] = "现在用" in t and "声音来源" in t
        if not res["voice_now"]: page_missing.append("b36 voice settings 现在用 / 声音来源")
    # ---- 60v2: voiceprint save from the bundled model within 5 s ----
    jv = selftest_json("voiceprint", "assetsave", timeout=90)
    print(f"B36 voiceprint assetsave {jv}", flush=True)
    res["vp_save_ms"] = jv.get("save_ms")
    if not jv.get("enrolled"): selftest_fail.append(f"b36 voiceprint save {jv}")
    elif (jv.get("save_ms") or 99999) > 5000: page_missing.append(f"b36 voiceprint save slower than 5 s ({jv.get('save_ms')} ms)")
    print(f"B36 {res}", flush=True)
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.2)


def shot381(name):
    r = snap("b381-" + name)
    subprocess.run(f"cp {OUT}/{step_no:02d}-b381-{name}.png {OUT}/b381-{name}.png", shell=True)
    return r


def back(n=1):
    for _ in range(n): sh("input keyevent KEYCODE_BACK"); time.sleep(1.3)


def b381_home():
    for _ in range(12):   # [38.7 v11] after the 24-page regression walk 5 backs left a sign-in page on top
        r = dump()
        if r is not None and find(r, "最近对话"): return r
        sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(8)
    return dump()


def png_size(path):
    try:
        with open(path, "rb") as f: h = f.read(24)
        return struct.unpack(">II", h[16:24]) if h[:8] == b"\x89PNG\r\n\x1a\n" else None
    except Exception:
        return None


def screen_ink(path, box):
    """[build-38.4] share (1/10000) of pixels inside box (x1,y1,x2,y2) of a screencap that differ from the box's most
    common colour (5 bits/channel), on a 1/4 grid. 0 = one flat colour (nothing drawn). -1 if it cannot be read."""
    try:
        from PIL import Image
        im = Image.open(path).convert("RGB")
        x1, y1, x2, y2 = [int(v) for v in box]
        x1, y1 = max(0, x1), max(0, y1); x2, y2 = min(im.width, x2), min(im.height, y2)
        if x2 - x1 < 8 or y2 - y1 < 8: return -1
        reg = im.crop((x1, y1, x2, y2)).resize(((x2 - x1) // 4, (y2 - y1) // 4))
        counts = {}
        for p in reg.getdata():
            k = (p[0] & 0xF8, p[1] & 0xF8, p[2] & 0xF8); counts[k] = counts.get(k, 0) + 1
        n = sum(counts.values()); top = max(counts.values())
        return (n - top) * 10000 // n
    except Exception as e:
        print("screen_ink error", e, flush=True); return -1


def node_bounds(root, pred):
    for n in (root.iter("node") if root is not None else []):
        if pred(n):
            b = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
            if len(b) == 4: return b
    return None


def wait_page_loaded(tag, limit=45):
    """[build-38.4] screenshot only after the page finished: WebView progress 100, document complete, then 3 s settle."""
    t0 = time.time(); j = {}
    while time.time() - t0 < limit:
        j = selftest_json("webfit", "probe", timeout=20) or {}
        tx = j.get("text") or {}
        if j.get("progress") == 100 and tx.get("rs") == "complete": break
        time.sleep(3)
    time.sleep(3)
    print(f"B384 {tag} loaded after {time.time() - t0:.0f}s progress={j.get('progress')} rs={(j.get('text') or {}).get('rs')}", flush=True)
    return j


def screen_383():
    """[build-38.3] the emulator must BE the user's phone (1280x2800 px, 480 dpi), set at AVD creation (avd-fit.sh +
    -skin), not by `wm size` (clamped to a multiple of the old 320x640 panel -> 1080x1920)."""
    sh("wm size reset"); sh("wm density reset"); time.sleep(2)
    ws, wd = sh("wm size").strip(), sh("wm density").strip()
    print("B383 wm size ->", ws.replace("\n", " / "), "| wm density ->", wd.replace("\n", " / "), flush=True)
    print("B383 props", sh("getprop ro.sf.lcd_density; getprop qemu.sf.lcd_density; getprop ro.boot.qemu.skin; getprop androidboot.qemu.skin").split(), flush=True)
    print("B383 display", sh("dumpsys display | grep -m3 -E 'mBaseDisplayInfo|DisplayDeviceInfo'")[:600], flush=True)
    m = re.search(r"Physical size: (\d+)x(\d+)", ws)
    if m: SCREEN[0], SCREEN[1] = int(m.group(1)), int(m.group(2))
    dpi = re.search(r"Physical density: (\d+)", wd)
    snap("b383-screen-check")
    shot = f"{OUT}/{step_no:02d}-b383-screen-check.png"
    ps = png_size(shot)
    print(f"B383 screen {SCREEN} dpi={dpi.group(1) if dpi else '?'} screencap={ps}", flush=True)
    if SCREEN != [1280, 2800] or "Override" in ws: selftest_fail.append(f"b383 emulator size {ws!r} (want Physical 1280x2800, no override)")
    if not dpi or dpi.group(1) != "480" or "Override" in wd: selftest_fail.append(f"b383 emulator density {wd!r} (want 480)")
    if ps != (1280, 2800): selftest_fail.append(f"b383 screencap is {ps} (want 1280x2800)")


BROWSER_SHOTS = []


def browser_open(url, tag):
    """Open the in-app browser on url: self-test hook on 38.3+, else home ☰ 更多 → 打开浏览器 → type the URL."""
    b381_home()
    j = selftest_json("webfit", "open:" + url, timeout=20)
    if j.get("ok"):
        time.sleep(3)
    else:
        if not tap_text("更多", exact=True, wait=2): tap_text("更多", wait=2)
        if not (tap_text("打开浏览器", wait=4) or tap_text("Open Browser", wait=4)):
            selftest_fail.append(f"b383 browser: 打开浏览器 not found ({tag})"); return False
        r = dump()
        ed_ = next(((x, y) for t, d, x, y, n in nodes(r) if n.get("class", "").endswith("EditText")), None)
        if not ed_:
            selftest_fail.append(f"b383 browser: URL field not found ({tag})"); return False
        sh(f"input tap {ed_[0]} {ed_[1]}"); time.sleep(1)
        # select all + delete (smoke 42: MOVE_END + DEL left "https://example" in the field)
        sh("input keycombination 113 29"); time.sleep(0.3); sh("input keyevent KEYCODE_DEL")
        sh("input keyevent KEYCODE_MOVE_END")
        for _ in range(40): sh("input keyevent KEYCODE_DEL")
        sh(f"input text '{url}'"); time.sleep(0.5); sh("input keyevent 66"); time.sleep(1)
        sh("input keyevent KEYCODE_ESCAPE")   # close the keyboard so the page is fully visible
    time.sleep(14)   # page load (coze.cn's SPA takes ~10 s)
    for _ in range(8):   # smoke 43: coze.cn still showed its 「Loading...」 splash after 14 s on the emulator
        r = dump(); t = texts(r) if r is not None else []
        if not any(x.strip() in ("Loading...", "加载中", "加载中...") for x in t): break
        time.sleep(5)
    return True


def browser_probe(tag, want_dp=None):
    j = selftest_json("webfit", "probe", timeout=20)
    print(f"B383 probe {tag} {json.dumps(j, ensure_ascii=False)[:900]}", flush=True)
    if not j:
        return j  # APK without the probe (38.2)
    if not j.get("ok"):
        selftest_fail.append(f"b383 probe {tag}: {j.get('error')}"); return j
    pg = j.get("page") or {}
    if not j.get("mobileUa"): selftest_fail.append(f"b383 {tag}: UA without 'Mobile' ({pg.get('ua', '')[:80]})")
    if "wide=true overview=true" not in j.get("settings", ""): selftest_fail.append(f"b383 {tag}: settings {j.get('settings')}")
    if "builtIn=true display=false" not in j.get("settings", ""): selftest_fail.append(f"b383 {tag}: zoom settings {j.get('settings')}")
    if want_dp == "screen": want_dp = j.get("screenWidthDp") or round(SCREEN[0] / 3.0)   # [38.7 v11] dpi 405 → 427 dp, not SCREEN/3
    if want_dp and abs((j.get("cssViewW") or 0) - want_dp) > 12: selftest_fail.append(f"b383 {tag}: WebView is {j.get('cssViewW')} dp wide (want ~{want_dp}, fillMaxWidth)")
    return j


def b384_paint(tag, root, shot, j):
    """[build-38.4] the page must really be drawn (smoke 44: coze.cn only its background colour), and the sheet title
    must be one line with the close X on screen."""
    j = j or {}
    wv = node_bounds(root, lambda n: n.get("class", "").endswith("WebView"))
    ink = screen_ink(shot, wv) if wv else -1
    tx = j.get("text") or {}
    print(f"B384 paint {tag} webview={wv} screencapInk={ink} appScreenInk={j.get('screenInk')} softwareInk={j.get('softwareInk')} "
          f"layer={j.get('layer')} heal={j.get('heal')!r} text={json.dumps(tx, ensure_ascii=False)[:400]}", flush=True)
    if ink >= 0 and ink < 10 and (tx.get("shown") or 0) >= 3:
        selftest_fail.append(f"b384 {tag}: page not drawn (screen ink {ink}/10000, page shows {tx.get('shown')} text nodes; layer {j.get('layer')}; heal {j.get('heal')!r})")
        # A/B evidence for the next fix: the same page with each layer type
        for lt in ("hardware", "software", "none"):
            selftest_json("webfit", "layer:" + lt, timeout=10); time.sleep(4)
            rr = snap(f"b384-{tag}-layer-{lt}")
            print(f"B384 A/B {tag} layer={lt} ink={screen_ink(f'{OUT}/{step_no:02d}-b384-{tag}-layer-{lt}.png', wv)}", flush=True)
    title = (j.get("page") or {}).get("title") or ""
    close = node_bounds(root, lambda n: (n.get("content-desc") or "") in ("关闭", "Close"))   # the header X (not M3's 「关闭工作表」 scrim action)
    tb = node_bounds(root, lambda n: title and (n.get("text") or "") == title)
    print(f"B384 title {tag} bounds={tb} close={close}", flush=True)
    if not close or close[2] > SCREEN[0] or close[0] < 0:
        page_missing.append(f"b384 {tag}: browser close X not on screen ({close})")
    if tb and (tb[3] - tb[1]) > 110:
        page_missing.append(f"b384 {tag}: browser title is more than one line ({tb[3] - tb[1]} px high)")


def browser_case(url, tag, check_fit=True):
    if not browser_open(url, tag): return
    wait_page_loaded(tag)
    r = snap("b383-browser-" + tag)
    shot = f"{OUT}/b383-browser-{tag}.png"
    subprocess.run(f"cp {OUT}/{step_no:02d}-b383-browser-{tag}.png {shot}", shell=True)
    BROWSER_SHOTS.append(shot)
    j = browser_probe(tag, want_dp="screen")
    pg = (j or {}).get("page") or {}
    b384_paint(tag, r, shot, j)
    if j and check_fit and not j.get("fits"):
        selftest_fail.append(f"b383 {tag}: page wider than the screen (scrollWidth {pg.get('scrollWidth')} > innerWidth {pg.get('innerWidth')})")
    if tag == "example":
        # landscape, then the old 1080x1920 size, same page
        sh("settings put system accelerometer_rotation 0; settings put system user_rotation 1"); time.sleep(5)
        snap("b383-browser-example-land")
        subprocess.run(f"cp {OUT}/{step_no:02d}-b383-browser-example-land.png {OUT}/b383-browser-example-land.png", shell=True)
        jl = browser_probe("example-land") if j else {}
        if jl and jl.get("ok"): print(f"B383 landscape WebView {jl.get('cssViewW')} dp of screen {jl.get('screenWidthDp')} dp", flush=True)
        if jl and not jl.get("fits"): selftest_fail.append("b383 example landscape: page wider than the screen")
        sh("settings put system user_rotation 0"); time.sleep(4)
        sh("wm size 1080x1920"); time.sleep(5)
        print("B383 wm size (1080x1920 check) ->", sh("wm size").strip().replace("\n", " / "), flush=True)
        snap("b383-browser-example-1080x1920")
        subprocess.run(f"cp {OUT}/{step_no:02d}-b383-browser-example-1080x1920.png {OUT}/b383-browser-example-1080x1920.png", shell=True)
        print("B383 1080x1920 screencap", png_size(f"{OUT}/b383-browser-example-1080x1920.png"), flush=True)
        jm = browser_probe("example-1080x1920", want_dp=360) if j else {}
        if jm and not jm.get("fits"): selftest_fail.append("b383 example at 1080x1920: page wider than the screen")
        sh("wm size reset"); time.sleep(5)
    back(2)


def b383_browser():
    """[build-38.3] in-app browser pages fit the phone width (example.com, www.coze.cn) at 1280x2800 / 480 dpi."""
    selftest_json("webfit", "secureoff", timeout=10)
    try:
        browser_case("https://example.com/", "example")
    except Exception as e:
        print("b383 example error:", e, flush=True); selftest_fail.append(f"b383 example error {e}")
    try:
        browser_case("https://www.coze.cn/", "coze")
    except Exception as e:
        print("b383 coze error:", e, flush=True); selftest_fail.append(f"b383 coze error {e}")
    if not alive(): relaunch()


def b381():
    """[build-38.1] board-accurate screens at 1080x2400 / 480 dpi, Chinese app locale, a loud wallpaper set:
    home, 消息, detail, chat, settings, 语音设置, call + 声音 sheet, 账号连接, 定时任务 and old pages."""
    res = {}
    sh(f"cmd locale set-app-locales {PKG} --locales zh-CN"); time.sleep(1)
    screen_383()
    print("B381 biometric", selftest_json("biometric", "", timeout=20), flush=True)
    jb = selftest_json("biometric", "", timeout=20)
    if not jb.get("manifest"): selftest_fail.append(f"b381 USE_BIOMETRIC missing {jb}")
    if str(jb.get("can", "")).startswith("error"): selftest_fail.append(f"b381 canAuthenticate threw {jb}")
    print("B381 inject", selftest_json("feedinject", "both", timeout=30), flush=True)
    selftest_json("fakeaccounts", "", timeout=20)
    print("B381 uiprep", selftest_json("uiprep", "杨arling|gen", timeout=20), flush=True)
    sh(f"am force-stop {PKG}"); time.sleep(2)
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(10)
    r = b381_home(); time.sleep(2)
    r = shot381("home")
    t = "\n".join(texts(r)) if r is not None else ""
    res["home"] = "最近对话" in t
    for bad in ("件要处理", "Today", "min ago", "语音，", "服务器 2"):
        if bad in t: page_missing.append(f"b381 home still shows {bad}")
    if "开个新对话" not in t: selftest_fail.append("b381 home input bar missing")
    # ☰ developer menu
    if tap_text("更多", exact=True, wait=2): shot381("menu"); back()
    # ---- 消息 ----
    if not tap_text("消息", exact=True, wait=5): tap_text("消息，", wait=5)
    time.sleep(4)
    r = shot381("messages")
    tx = texts(r) if r is not None else []
    if any("接入更多来源" in x for x in tx): selftest_fail.append("b381 sources card still on 消息")
    ys = [y for tx_, d, x, y, n in nodes(r) if (tx_ or d).startswith(("账号异常", "X，", "GitHub", "值得关注"))]
    res["tabs_one_row"] = len(set(y // 20 for y in ys)) <= 1 if ys else False
    if ys and not res["tabs_one_row"]: selftest_fail.append(f"b381 tabs wrap {ys}")
    # detail sheet
    for _ in range(3):
        r = dump()
        h = next(((x, y) for tx_, d, x, y, n in nodes(r) if "SmokeKey" in (tx_ or d) and not (tx_ or d).startswith(("一键修复", "去处理"))), None)
        if h: sh(f"input tap {h[0]} {h[1]}"); time.sleep(3); break
        if not tap_text("去看", wait=2): open_tab("账号异常")
    shot381("detail"); back()
    back(2)
    # ---- chat: first conversation ----
    b381_home()
    r = dump()
    row = next(((x, y) for tx_, d, x, y, n in nodes(r) if y > SCREEN[1] * 0.35 and y < SCREEN[1] * 0.75 and (tx_ or "") and len(tx_ or "") > 3 and "开个新对话" not in tx_ and "最近对话" not in tx_), None)
    if row:
        sh(f"input tap {row[0]} {row[1]}"); time.sleep(5); rc = shot381("chat")
        if rc is not None and any(x.startswith("Provider error") for x in texts(rc)): page_missing.append("b381 chat banner still starts with 'Provider error'")
        back()
    # ---- settings + 语音设置 ----
    b381_home()
    if tap_text("设置", exact=True, wait=4) or tap_text("设置", wait=4) or tap_text("Settings", exact=True, wait=4):
        shot381("settings")
        if tap_text("语音", exact=True, wait=4):
            r = shot381("voice-settings")
            t = " ".join(texts(r)) if r is not None else ""
            if "每个 AI 的声音" not in t: selftest_fail.append("b381 voice settings: 每个 AI 的声音 missing")
            back()
        if tap_text("账号与 AI 服务", wait=3):
            shot381("settings-ai")
            if tap_text("支付卡", wait=4): shot381("paycards"); back()
            for lab in ("模型组", "Model Groups"):
                if tap_text(lab, wait=4): shot381("modelgroups"); back(); break
            else:
                sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.3)} 400"); time.sleep(1.5)
                if tap_text("模型组", wait=4): shot381("modelgroups"); back()
            back()
        if tap_text("外观", exact=True, wait=3):
            if tap_text("外观", wait=4): shot381("appearance"); back()
            back()
        if tap_text("测试与上报", wait=3):
            if tap_text("一键自检", wait=4): shot381("report"); back()
            back()
        if tap_text("数据", exact=True, wait=3):
            if tap_text("知识库", wait=4): shot381("knowledge"); back()
            back()
        back()
    # ---- 定时任务 (bottom bar 任务) ----
    b381_home()
    if tap_text("任务", exact=True, wait=5): time.sleep(2); shot381("scheduled"); back()
    # ---- 账号连接 ----
    b381_home()
    if tap_text("应用", exact=True, wait=5): time.sleep(3); shot381("connections"); back()
    # ---- voice call + ⋯ 声音 sheet ----
    b381_home()
    if tap_text("实时语音", exact=True, wait=6):
        time.sleep(5)
        r = shot381("voice")
        t = " ".join(texts(r)) if r is not None else ""
        if "正在试" in t or "直连" in t: selftest_fail.append("b381 voice page shows route names")
        if tap_text("语音设置", wait=3) or tap_text("更多", wait=3):
            r = shot381("voice-sheet")
            t = " ".join(texts(r)) if r is not None else ""
            if "的声音" not in t: page_missing.append("b381 voice sheet 「X 的声音」 line missing")
            back()
        back(2)
    # ---- voiceprint save < 5 s ----
    jv = selftest_json("voiceprint", "assetsave", timeout=90)
    print(f"B381 voiceprint assetsave {jv}", flush=True)
    res["vp_save_ms"] = jv.get("save_ms")
    if not jv.get("enrolled"): selftest_fail.append(f"b381 voiceprint save {jv}")
    elif (jv.get("save_ms") or 99999) > 5000: page_missing.append(f"b381 voiceprint save slower than 5 s ({jv.get('save_ms')} ms)")
    # ---- home at font 1.3 ----
    sh("settings put system font_scale 1.3"); time.sleep(3)
    b381_home(); shot381("home-fs130")
    if tap_text("消息", exact=True, wait=5) or tap_text("消息，", wait=5):
        time.sleep(3); shot381("messages-fs130"); back()
    sh("settings put system font_scale 1.0"); time.sleep(2)
    try:
        b383_browser()
    except Exception as e:
        print("b383 browser error:", e, flush=True); selftest_fail.append(f"b383 browser error {e}")
    try:
        res["coze"] = coze_382()
    except Exception as e:
        print("b382 coze error:", e, flush=True); selftest_fail.append(f"b382 coze error {e}")
    print(f"B381 {res}", flush=True)


# ======================= [build-38.5] redesign smoke at the user's real rendering =======================

def shot385(name):
    r = snap("b385-" + name)
    subprocess.run(f"cp {OUT}/{step_no:02d}-b385-{name}.png {OUT}/b385-{name}.png", shell=True)
    return r


def result_json(method, arg, timeout=120):
    out = selftest(method, arg, timeout=timeout)
    i = out.find("result=")
    if i < 0: return None
    raw = out[i + 7:].strip()
    try:
        return json.JSONDecoder().raw_decode(raw)[0]
    except Exception:
        return raw.split("}]")[0].strip()


def route(r, wait=4):
    selftest("route", r, timeout=20); time.sleep(wait)


def screen_385():
    sh("wm size reset"); sh("wm density reset"); time.sleep(2)
    ws, wd = sh("wm size").strip(), sh("wm density").strip()
    m = re.search(r"Physical size: (\d+)x(\d+)", ws)
    if m: SCREEN[0], SCREEN[1] = int(m.group(1)), int(m.group(2))
    dpi = re.search(r"Physical density: (\d+)", wd)
    sh(f"settings put system font_scale {FONT_SCALE}"); time.sleep(2)
    fs = sh("settings get system font_scale").strip()
    print(f"B385 screen {SCREEN} dpi={dpi.group(1) if dpi else '?'} font_scale={fs}", flush=True)
    if SCREEN != [1080, 2362]: selftest_fail.append(f"b385 emulator size {ws!r} (want 1080x2362)")
    if not dpi or dpi.group(1) != "405": selftest_fail.append(f"b385 emulator density {wd!r} (want 405)")


def group_385(res):
    """G + I: 3-member group with one broken model and one rate-limited member; @only; structured summary;
    a 账号连接-saved key makes the partner usable with no 缺 key."""
    B = "http://10.0.2.2:8088"
    result_json("group", "reset")
    a = result_json("group", f"custom 成员A|{B}/a/v1|k|smoke-model")
    b = result_json("group", f"custom 坏模型|{B}/b/v1|k|smoke-bad-model")
    c = result_json("group", f"custom 限流|{B}/c/v1|k|smoke-429")
    print("B385 customs", a, b, c, flush=True)
    gid = result_json("group", f"make 冒烟群|{a},{b},{c}")
    res["gid"] = gid
    r1 = result_json("group", f"send {gid}|大家好，各说一句", timeout=150) or []
    print("B385 round1", json.dumps(r1, ensure_ascii=False)[:1500], flush=True)
    if not isinstance(r1, list): selftest_fail.append(f"b385 group send not a list: {r1}"); return gid
    st = {x.get("name"): x for x in r1 if x.get("who") != "user"}
    if any(x.get("status") == "stopped" for x in r1): selftest_fail.append("b385 group: 已停止 in a normal round")
    bad = next((x for x in r1 if x.get("name") == "坏模型"), {})
    if bad.get("status") != "ok" or "smoke-model" not in (bad.get("detail", "") + bad.get("model", "")):
        selftest_fail.append(f"b385 group: broken model not auto-switched {bad}")
    rl = next((x for x in r1 if x.get("name") == "限流"), {})
    if rl.get("status") != "ok": selftest_fail.append(f"b385 group: 429 member not retried {rl}")
    if not any(x.get("who") == "host" and x.get("kind") == "wrap" and x.get("status") == "ok" for x in r1):
        selftest_fail.append("b385 group: host did not wrap up the round")
    r2 = result_json("group", f"send {gid}|@成员A 只问你一个", timeout=90) or []
    who2 = [x.get("name") for x in r2 if isinstance(x, dict) and x.get("who") != "user"]
    print("B385 round2", who2, flush=True)
    if who2 != ["成员A"]: selftest_fail.append(f"b385 group @mention answered by {who2}")
    r3 = result_json("group", f"summary {gid}", timeout=90) or []
    print("B385 summary", json.dumps(r3, ensure_ascii=False)[:600], flush=True)
    if not any(isinstance(x, dict) and x.get("kind") == "summary" and x.get("status") == "ok" and "结论" in x.get("text", "") for x in r3):
        selftest_fail.append(f"b385 group summary not structured {r3}")
    # I: a key saved by 账号连接 → partner usable at once, no 「缺 key」
    v = result_json("group", f"vaultkey deepseek|sk-smoke-0123456789abcdef0123456789|{B}/v1") or {}
    print("B385 vaultkey", v, flush=True)
    res["vaultkey"] = v
    if not isinstance(v, dict) or v.get("state") != "READY" or v.get("dupes") != 1:
        selftest_fail.append(f"b385 connected key not READY {v}")
    g2 = result_json("group", "make 账号连接群|deepseek")
    r4 = result_json("group", f"send {g2}|你好", timeout=90) or []
    ds = next((x for x in r4 if isinstance(x, dict) and x.get("who") == "deepseek"), {})
    print("B385 deepseek reply", ds, flush=True)
    if ds.get("status") != "ok" or "密钥" in ds.get("text", "") or "连接" in ds.get("text", ""):
        selftest_fail.append(f"b385 connected deepseek chat failed {ds}")
    return gid


def b385():
    res = {}
    sh(f"cmd locale set-app-locales {PKG} --locales zh-CN"); time.sleep(1)
    screen_385()
    print("B385 inject", selftest_json("feedinject", "both", timeout=30), flush=True)
    selftest_json("fakeaccounts", "", timeout=20)
    print("B385 uiprep", selftest_json("uiprep", "杨arling|gen", timeout=20), flush=True)
    sh(f"am force-stop {PKG}"); time.sleep(2)
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(10)
    b381_home(); time.sleep(2)
    r = shot385("home")
    t = "\n".join(texts(r)) if r is not None else ""
    for want in ("最近对话", "电量", "网络", "存储", "运行", "开个新对话"):
        if want not in t: page_missing.append(f"b385 home missing {want}")
    # ---- 消息 + 为你推荐 + long press + sources ----
    if not tap_text("消息", exact=True, wait=5): tap_text("消息，", wait=5)
    time.sleep(4)
    r = shot385("messages")
    tx = texts(r) if r is not None else []
    if not any(x.startswith("为你推荐") for x in tx): page_missing.append("b385 为你推荐 tab missing")
    if any(x.startswith("账号异常，0") for x in tx) or "账号异常 0" in tx: page_missing.append("b385 zero count still shown")
    if any(x == "帮我领" for x in tx): print("B385 note: 帮我领 visible (claimable offer?)", flush=True)
    # detail sheet: first card headline
    r = dump()
    card = next(((x, y) for tx_, d, x, y, n in nodes(r) if y > SCREEN[1] * 0.3 and y < SCREEN[1] * 0.8 and len(tx_ or "") >= 8), None)
    if card:
        sh(f"input tap {card[0]} {card[1]}"); time.sleep(3); shot385("detail"); back()
        sh(f"input swipe {card[0]} {card[1]} {card[0]} {card[1]} 900"); time.sleep(2.5)
        r = shot385("longpress-glass")
        t = " ".join(texts(r)) if r is not None else ""
        if "分享为图片" not in t: page_missing.append("b385 long-press: 分享为图片 not visible (cut under the bar?)")
        back()
    # sources (⚙ → 消息来源)
    if tap_text("关注设置和消息来源", wait=2) and tap_text("消息来源", exact=True, wait=3):
        r = shot385("sources")
        t = " ".join(texts(r)) if r is not None else ""
        if "都接好了" in t: selftest_fail.append("b385 sources still says 都接好了")
        if "添加账号" not in t: page_missing.append("b385 sources: 添加账号 list missing")
        back()
    if tap_text("关注设置和消息来源", wait=2) and tap_text("关注设置", exact=True, wait=3):
        shot385("follows"); back()
    back(2)
    # ---- chat ----
    b381_home()
    r = dump()
    row = next(((x, y) for tx_, d, x, y, n in nodes(r) if y > SCREEN[1] * 0.45 and y < SCREEN[1] * 0.72 and len(tx_ or "") > 3
                and not any(k in (tx_ or "") for k in ("开个新对话", "最近对话", "电量", "网络", "存储", "运行"))), None)
    if row:
        sh(f"input tap {row[0]} {row[1]}"); time.sleep(5); shot385("chat"); back()
    # ---- group (G) + partners (I, J) ----
    try:
        gid = group_385(res)
    except Exception as e:
        print("group_385 error:", e, flush=True); selftest_fail.append(f"b385 group error {e}"); gid = None
    if gid:
        route(f"plus_group_chat/{gid}", 5); r = shot385("group-chat")
        t = " ".join(texts(r)) if r is not None else ""
        if "does not exist" in t: selftest_fail.append("b385 group chat shows raw English error")
        back()
    route("plus_group", 4); shot385("group-list"); back()
    route("plus_group_partners", 8)
    r = shot385("partners")
    t = " ".join(texts(r)) if r is not None else ""
    if "余额 余额" in t: selftest_fail.append("b385 partners: 余额 余额")
    if "还差智能体 Bot ID" in t: selftest_fail.append("b385 partners: old 还差智能体 Bot ID")
    sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.25)} 500"); time.sleep(1.5)
    r = shot385("partners-2")
    # J: a 一键连接 button enters the connect flow — [build-38.7] bare-model rows live under the folded 「高级」 section,
    # so the fold is opened first (smoke 38.6 failed here because it searched a closed fold)
    for _ in range(5):
        r = dump()
        if r is not None and find(r, "高级：直接用模型"):
            tap_text("高级：直接用模型", wait=3); res["advanced_opened"] = True; break
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 500"); time.sleep(1.5)
    if res.get("advanced_opened"): shot385("partners-advanced")
    hit = None
    for _ in range(6):
        r = dump()
        hit = find(r, "一键连接", exact=True)
        if hit: break
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 500"); time.sleep(1.5)
    if hit:
        sh(f"input tap {hit[0]} {hit[1]}"); time.sleep(6)
        r = shot385("partner-connect")
        t = " ".join(texts(r)) if r is not None else ""
        res["connect_flow"] = any(k in t for k in ("一键接入", "正在接入", "去官网获取", "手动填写密钥", "登录", "账号连接"))
        if not res["connect_flow"]: selftest_fail.append(f"b385 一键连接 did not open the connect flow: {t[:200]}")
        back(2)
    else:
        selftest_fail.append("b385 partners: no 一键连接 button found")
    back()
    # ---- scheduled, knowledge (+ 脑图), settings, voice settings, plugins ----
    b381_home()
    if tap_text("任务", exact=True, wait=5): time.sleep(2); shot385("scheduled"); back()
    route("plus_knowledge", 5); shot385("knowledge")
    if tap_text("脑图", exact=True, wait=4): shot385("knowledge-map"); tap_text("列表", exact=True, wait=2)
    back()
    b381_home()
    if tap_text("设置", exact=True, wait=4) or tap_text("设置", wait=4):
        shot385("settings")
        if tap_text("语音", exact=True, wait=4):
            shot385("voice-settings")
            sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.25)} 500"); time.sleep(1.5)
            r = shot385("voice-settings-2")
            t = " ".join(texts(r)) if r is not None else ""
            if "0.38" in t or "0.3" in t.split(): page_missing.append("b385 voice: numeric sensitivity still shown")
            back()
        back()
    route("plus_plugins", 4); shot385("plugins"); back()
    if B386:
        try:
            b386_extra(res)
        except Exception as e:
            print("b386 error:", e, flush=True); selftest_fail.append(f"b386 error {e}")
    if B387:
        result_json("group", "wallpaper off")   # the photo wallpaper set earlier covers the fog backdrop
        anim(True)
        for fn in (b387_greeting, b387_orbit, b387_daynight, b387_fog, b387_still, b387_animoff, b387_xlogin, b387_pwfill, b387_sms, b387_linkall, b387_ignore, b387_perms, b387_noblack, b387_vps, b387_typing, b387_regress, b387_knowledge, b387_multiacct, b387_persist, b387_leftovers, b387_realweb, b387_platforms, b387_coze):
            try:
                fn(res)
            except Exception as e:
                import traceback; traceback.print_exc()
                print(f"{fn.__name__} error:", e, flush=True); selftest_fail.append(f"{fn.__name__} error {e}")
        print("B387 RESULT " + json.dumps(res.get("b387", {}), ensure_ascii=False), flush=True)
        if B388: print("B388 RESULT " + json.dumps(res.get("b388", {}), ensure_ascii=False), flush=True)
    print(f"B385 {res}", flush=True)


# ======================= [build-38.6] agents / web apps in the group, reply cards, avatar level =======================

def home_avatar_386(res):
    """1: the face sits in the top bar, its upper half level with ☰ and ⚙, never overlapping them."""
    b381_home(); time.sleep(2)
    r = dump()
    av = node_bounds(r, lambda n: (n.get("content-desc") or "").startswith("换角色"))
    menu = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
    gear = node_bounds(r, lambda n: (n.get("content-desc") or "") in ("设置", "Settings"))
    res["avatar"] = {"avatar": av, "menu": menu, "gear": gear}
    print("B386 avatar", res["avatar"], flush=True)
    if not (av and menu and gear):
        page_missing.append(f"b386 home: avatar/☰/⚙ bounds missing {res['avatar']}"); return
    icon_cy = (menu[1] + menu[3]) / 2
    # [build-38.7] design387: ☰ / ⚙ sit on the face's middle line (38.6: upper half)
    if abs(icon_cy - (av[1] + av[3]) / 2) > 24:
        selftest_fail.append(f"b386 home: ☰/⚙ not on the face's middle line (avatar {av}, icon centre y {icon_cy})")
    if av[0] < menu[2] or av[2] > gear[0]:
        selftest_fail.append(f"b386 home: avatar overlaps ☰/⚙ horizontally {res['avatar']}")


def fake_stats():
    try:
        import urllib.request
        return json.loads(urllib.request.urlopen("http://127.0.0.1:8088/stats", timeout=5).read())
    except Exception as e:
        return {"err": str(e)}


def group_386(res):
    """Group of agents + apps: fake 扣子 (list → auto-publish → chat), fake Manus task, fake web chat page (豆包 selectors),
    one bad member (Kimi web page behind a login wall); @only; summary; 1:1 chat with a 扣子 bot and with Mines' own agent."""
    B = "http://10.0.2.2:8088"
    v = result_json("group", f"vaultkey coze|pat_smoke_0123456789abcdef0123|{B}/v3") or {}
    cs = result_json("group", "cozesync", timeout=60) or {}
    print("B386 cozesync", json.dumps(cs, ensure_ascii=False)[:800], flush=True)
    res["coze"] = cs
    if not isinstance(cs, dict) or len(cs.get("bots", [])) != 2: selftest_fail.append(f"b386 coze: bots not listed {cs}")
    elif "bot2" not in cs.get("published", []) and "bot2" not in fake_stats().get("coze_published", []):
        selftest_fail.append(f"b386 coze: unpublished bot not auto-published {cs} {fake_stats()}")
    elif sorted(m.get("state") for m in cs.get("members", []) if m.get("id", "").startswith("coze:")) != ["READY", "READY"]:
        selftest_fail.append(f"b386 coze: bots not READY members {cs}")
    result_json("group", f"vaultkey manus|sk-manus-smoke-0123456789abcdef|{B}/manus/v2")
    result_json("group", f"base web:doubao|{B}/webchat"); result_json("group", "weblogin web:doubao|1")
    result_json("group", f"base web:kimi|{B}/webchat-login"); result_json("group", "weblogin web:kimi|1")
    gid = result_json("group", "make 智能体群|coze:bot1,manus,web:doubao,web:kimi")
    res["gid386"] = gid
    t0 = time.time()
    r1 = result_json("group", f"send {gid}|大家好，各说一句你的看法", timeout=200) or []
    res["round_s"] = round(time.time() - t0, 1)
    print("B386 round1", res["round_s"], "s", json.dumps(r1, ensure_ascii=False)[:2000], flush=True)
    if not isinstance(r1, list): selftest_fail.append(f"b386 group send not a list: {r1}"); return gid
    by = {x.get("who"): x for x in r1 if isinstance(x, dict)}
    for who, want in (("coze:bot1", "冒烟助手"), ("manus", "Manus 做完了"), ("web:doubao", "网页成员收到")):
        x = by.get(who, {})
        if x.get("status") != "ok" or want not in (x.get("text", "") + x.get("name", "")):
            selftest_fail.append(f"b386 group: {who} did not answer {x}")
    k = by.get("web:kimi", {})
    if k.get("status") not in ("error", "skip") or ("登录" not in (k.get("text", "") + k.get("detail", ""))):
        selftest_fail.append(f"b386 group: login-wall member not reported plainly {k}")
    if any(x.get("status") == "stopped" for x in r1 if isinstance(x, dict)): selftest_fail.append("b386 group: 已停止 in a normal round")
    if not any(x.get("who") == "host" and x.get("kind") == "wrap" and x.get("status") == "ok" for x in r1 if isinstance(x, dict)):
        selftest_fail.append("b386 group: host did not wrap up the round")
    r2 = result_json("group", f"send {gid}|@冒烟助手 只问你一个", timeout=120) or []
    who2 = [x.get("who") for x in r2 if isinstance(x, dict) and x.get("who") != "user"]
    print("B386 round2", who2, flush=True)
    if who2 != ["coze:bot1"]: selftest_fail.append(f"b386 group @mention answered by {who2}")
    r3 = result_json("group", f"summary {gid}", timeout=120) or []
    if not any(isinstance(x, dict) and x.get("kind") == "summary" and x.get("status") == "ok" for x in r3):
        selftest_fail.append(f"b386 group summary failed {r3}")
    # 1:1 with a bot that Mines published itself, and with Mines' own agent
    d = result_json("group", "direct coze:bot2")
    r4 = result_json("group", f"send {d}|你好", timeout=90) or []
    x = next((y for y in r4 if isinstance(y, dict) and y.get("who") == "coze:bot2"), {})
    print("B386 direct coze", x, flush=True)
    if x.get("status") != "ok" or "写作搭子" not in x.get("text", ""): selftest_fail.append(f"b386 direct 扣子 chat failed {x}")
    res["direct_coze"] = d
    dm = result_json("group", "direct mines")
    r5 = result_json("group", f"send {dm}|你好，简单介绍一下你自己", timeout=200) or []
    x = next((y for y in r5 if isinstance(y, dict) and y.get("who") == "mines"), {})
    print("B386 direct mines", x, flush=True)
    res["mines_agent"] = x.get("status")
    if x.get("status") != "ok": selftest_fail.append(f"b386 Mines agent member failed {x}")
    return gid


def b386_extra(res):
    home_avatar_386(res)
    shot385("home-avatar")
    try:
        gid = group_386(res)
    except Exception as e:
        print("group_386 error:", e, flush=True); selftest_fail.append(f"b386 group error {e}"); gid = None
    if gid:
        route(f"plus_group_chat/{gid}", 5)
        r = shot385("agents-group")
        t = " ".join(texts(r)) if r is not None else ""
        if "讨论总结" not in t: page_missing.append("b386 agents group: summary bubble not on screen")
        back()
    route("plus_group_partners", 8)
    r = shot385("partners386")
    t = " ".join(texts(r)) if r is not None else ""
    for want in ("智能体", "冒烟助手", "聊天"):
        if want not in t: page_missing.append(f"b386 partners: {want} missing")
    for bad in ("Bot ID", "pat_", "10.0.2.2"):
        if bad in t: selftest_fail.append(f"b386 partners shows technical field {bad}")
    sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.25)} 500"); time.sleep(1.5)
    r = shot385("partners386-2")
    t2 = " ".join(texts(r)) if r is not None else ""
    if "App 账号" not in t + t2: page_missing.append("b386 partners: App 账号 section missing")
    if "高级" not in t + t2:
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.25)} 500"); time.sleep(1.5)
        r = shot385("partners386-3"); t3 = " ".join(texts(r)) if r is not None else ""
        if "高级" not in t3: page_missing.append("b386 partners: 高级：直接用模型 fold missing")
    back()
    route("plus_group_new", 6); shot385("new-group386"); back()
    # 1:1 扣子 chat screen
    if res.get("direct_coze"):
        route(f"plus_group_chat/{res['direct_coze']}", 5); shot385("direct-coze"); back()
    # E: reply card in a normal chat (the smoke chat has a reply from the fake model)
    b381_home()
    r = dump()
    row = next(((x, y) for tx_, d, x, y, n in nodes(r) if y > SCREEN[1] * 0.35 and y < SCREEN[1] * 0.75 and len(tx_ or "") > 3
                and not any(k in (tx_ or "") for k in ("开个新对话", "最近对话", "电量", "网络", "存储", "运行", "Claude", "在线"))), None)
    if row:
        sh(f"input tap {row[0]} {row[1]}"); time.sleep(5); shot385("chat-card"); back()
    # F: real reads of 微博 / B站 / 知乎 from the emulator (no session) + one public endpoint each
    sc = result_json("sourcecheck", "", timeout=90)
    print("B386 sourcecheck", json.dumps(sc, ensure_ascii=False)[:1500], flush=True)
    res["sourcecheck"] = sc
    if not isinstance(sc, list) or len(sc) != 3: selftest_fail.append(f"b386 sourcecheck not run {sc}")
    else:
        for x in sc:
            if not x.get("own") or not x.get("public"): selftest_fail.append(f"b386 sourcecheck blank result {x}")


# ======================= [build-38.7] 知识库 / 多账号隔离 / 38.6 遗留 / 真实网页 AI =======================

def shot387(name):
    r = snap("b387-" + name)
    subprocess.run(f"cp {OUT}/{step_no:02d}-b387-{name}.png {OUT}/b387-{name}.png", shell=True)
    return r


def R7(res):
    return res.setdefault("b387", {})


def all_desc(root, prefix):
    out = []
    if root is None: return out
    for n in root.iter("node"):
        d = n.get("content-desc") or ""
        if d.startswith(prefix):
            b = re.findall(r"\d+", n.get("bounds") or "")
            if len(b) == 4:
                x1, y1, x2, y2 = map(int, b); out.append(((x1 + x2) // 2, (y1 + y2) // 2, d))
    return out


def b387_knowledge(res):
    """知识库: three conversations (chat / 群聊 / 单聊 with 豆包) → organised with local rules (no key) → 脑图 nodes →
    point detail with its source → 「回到对话」 opens that chat; a new message + leaving the window updates it;
    with a (fake) model key the summary comes from the model."""
    r7 = R7(res)
    B = "http://10.0.2.2:8088"
    result_json("group", "kbep ")                    # no model endpoint: local rules
    result_json("group", f"base web:doubao|{B}/webchat")
    seed = result_json("group", "kbseed ") or {}
    print("B387 kbseed", seed, flush=True)
    result_json("group", "kbrun", timeout=120)
    st = result_json("group", "kbstate", timeout=30) or []
    print("B387 kbstate", json.dumps(st, ensure_ascii=False)[:2500], flush=True)
    r7["kb_rules"] = st
    kinds = {e.get("kind") for e in st if isinstance(e, dict)}
    for k in ("chat", "group", "direct"):
        if k not in kinds: selftest_fail.append(f"b387 kb: no {k} conversation organised {kinds}")
    for e in st if isinstance(st, list) else []:
        if e.get("id") == "kbseed-chat" or e.get("source") == "交易复盘群":
            if not e.get("summary") or not e.get("decisions"): selftest_fail.append(f"b387 kb: rules digest empty {e}")
    if not any(isinstance(e, dict) and e.get("kind") == "direct" and "豆包" in e.get("source", "") for e in st):
        selftest_fail.append("b387 kb: 单聊 source label missing")
    # 脑图 = knowledge home
    route("plus_knowledge", 6)
    r = shot387("kb-map")
    t = " ".join(texts(r)) if r is not None else ""
    if "立即整理" not in t: page_missing.append("b387 kb: 立即整理 button missing")
    topics = [d for d in all_desc(r, "脑图节点：") if " · " in d[2]]
    r7["kb_topics"] = [d[2] for d in topics]
    if not topics: selftest_fail.append("b387 kb: no topic nodes on the map")
    tp = next((d for d in topics if "交易" in d[2]), topics[0] if topics else None)
    if tp:
        sh(f"input tap {tp[0]} {tp[1]}"); time.sleep(2.5)
        r = shot387("kb-branch")
        leaves = [d for d in all_desc(r, "脑图节点：") if " · " not in d[2] and d[2] != "脑图节点：我"]
        r7["kb_leaves"] = [d[2] for d in leaves]
        on = [d for d in leaves if 0 < d[0] < SCREEN[0] and 300 < d[1] < SCREEN[1] - 150]
        if not leaves: selftest_fail.append("b387 kb: branch shows no 要点 leaves")
        elif on:
            lf = next((d for d in on if "✓" in d[2]), on[0])
            sh(f"input tap {lf[0]} {lf[1]}"); time.sleep(2.5)
            r = shot387("kb-point")
            t = " ".join(texts(r)) if r is not None else ""
            r7["kb_point"] = t[:400]
            if "来自" not in t: selftest_fail.append(f"b387 kb: point detail has no source line {t[:200]}")
            if tap_text("回到对话", exact=True, wait=5):
                r = shot387("kb-back-to-chat")
                t = " ".join(texts(r)) if r is not None else ""
                r7["kb_back"] = t[:300]
                if not any(k in t for k in ("仓位", "BTC", "交易复盘群", "止损")):
                    selftest_fail.append(f"b387 kb: 回到对话 did not open the source chat {t[:200]}")
                back()
            else: selftest_fail.append("b387 kb: 回到对话 button missing")
        else: page_missing.append(f"b387 kb: leaves off screen {leaves[:3]}")
    back()
    # leaving a window updates it (no idle wait)
    gid = seed.get("group") if isinstance(seed, dict) else None
    if gid:
        before = next((e.get("chatAt") for e in st if isinstance(e, dict) and e.get("id") == "g:" + gid), 0) or 0
        result_json("group", f"kbadd {gid}|再补充：止盈设 6%|host|好的，结论：止盈 6%。")
        route(f"plus_group_chat/{gid}", 4); back(); time.sleep(9)
        st2 = result_json("group", "kbstate", timeout=30) or []
        after = next((e for e in st2 if isinstance(e, dict) and e.get("id") == "g:" + gid), {})
        r7["kb_leave_update"] = {"before": before, "after": after.get("chatAt"), "decisions": after.get("decisions")}
        if not after or (after.get("chatAt") or 0) <= before: selftest_fail.append(f"b387 kb: leaving the group did not re-organise it {r7['kb_leave_update']}")
    # with a model key (fake model) → model summary, via the 立即整理 button
    result_json("group", f"kbep {B}/v1|sk-smoke|smoke-model")
    result_json("group", "kbseed v2")
    route("plus_knowledge", 5)
    if tap_text("立即整理", exact=True, wait=8):
        time.sleep(4); shot387("kb-organised")
    else: page_missing.append("b387 kb: could not tap 立即整理")
    st3 = result_json("group", "kbstate", timeout=30) or []
    r7["kb_model"] = [e.get("model") for e in st3 if isinstance(e, dict)]
    if not any(isinstance(e, dict) and e.get("model") for e in st3): selftest_fail.append("b387 kb: model endpoint not used for organising")
    print("B387 fake stats kb_posts", fake_stats().get("kb_posts"), flush=True)
    back()
    route("plus_knowledge", 4)
    if tap_text("列表", exact=True, wait=3): shot387("kb-list"); tap_text("脑图", exact=True, wait=2)
    back()
    result_json("group", "kbep ")


def b387_multiacct_round(res, mode_tag):
    r7 = R7(res)
    B = "http://10.0.2.2:8088"
    result_json("group", "webacctreset web:doubao")
    result_json("group", f"base web:doubao|{B}/webacct/")
    a2 = result_json("group", "webacctadd web:doubao")
    la = result_json("group", f"webacctlogin web:doubao|{B}/webacct/login?u=A", timeout=60)
    lb = result_json("group", f"webacctlogin {a2}|{B}/webacct/login?u=B", timeout=60)
    ws = result_json("group", "webstates") or []
    print(f"B387 multi[{mode_tag}] logins", la, lb, json.dumps(ws, ensure_ascii=False)[:800], flush=True)
    names = {w.get("id"): w for w in ws if isinstance(w, dict)}
    a, b = names.get("web:doubao", {}), names.get(a2, {})
    if a.get("state") != "READY" or b.get("state") != "READY": selftest_fail.append(f"b387 multi[{mode_tag}]: both accounts not online {a} {b}")
    if "账号A" not in a.get("name", "") or "账号B" not in b.get("name", ""): selftest_fail.append(f"b387 multi[{mode_tag}]: names not 豆包·账号A/B {a.get('name')} {b.get('name')}")
    gid = result_json("group", f"make 双账号群{mode_tag}|web:doubao,{a2}|par")
    r1 = result_json("group", f"send {gid}|你是谁", timeout=200) or []
    by = {x.get("who"): x for x in r1 if isinstance(x, dict)}
    ra, rb = by.get("web:doubao", {}), by.get(a2, {})
    print(f"B387 multi[{mode_tag}] round1", json.dumps([ra, rb], ensure_ascii=False)[:800], flush=True)
    ok1 = "账号A" in ra.get("text", "") and "lsA" in ra.get("text", "") and "账号B" in rb.get("text", "") and "lsB" in rb.get("text", "")
    if not ok1: selftest_fail.append(f"b387 multi[{mode_tag}]: replies crossed / missing A={ra} B={rb}")
    result_json("group", "websignout web:doubao")
    r2 = result_json("group", f"send {gid}|再说一次你是谁", timeout=200) or []
    by2 = {x.get("who"): x for x in r2 if isinstance(x, dict)}
    ra2, rb2 = by2.get("web:doubao", {}), by2.get(a2, {})
    print(f"B387 multi[{mode_tag}] after signout A", json.dumps([ra2, rb2], ensure_ascii=False)[:800], flush=True)
    if not ("账号B" in rb2.get("text", "") and "lsB" in rb2.get("text", "")): selftest_fail.append(f"b387 multi[{mode_tag}]: B broke after A signed out {rb2}")
    if ra2.get("status") == "ok": selftest_fail.append(f"b387 multi[{mode_tag}]: A still answers after sign-out {ra2}")
    r7[f"multi_{mode_tag}"] = {"names": [a.get("name"), b.get("name")], "round1": [ra.get("text"), rb.get("text")],
                               "after_signout": [ra2.get("status"), ra2.get("detail"), rb2.get("text")], "ok": ok1}
    return gid, a2


def b387_multiacct(res):
    r7 = R7(res)
    info = result_json("group", "jarinfo") or {}
    print("B387 jarinfo", info, flush=True)
    r7["jar"] = info
    mode = info.get("mode", "x") if isinstance(info, dict) else "x"
    gid, a2 = b387_multiacct_round(res, mode)
    route("plus_group_partners", 8)
    for _ in range(4):
        r = dump()
        if r is not None and find(r, "豆包·账号"): break
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 500"); time.sleep(1.5)
    r = shot387("partners-two-accounts")
    t = " ".join(texts(r)) if r is not None else ""
    if "豆包·账号A" not in t and "豆包·账号B" not in t: page_missing.append("b387 partners: 豆包·账号A/B rows not on screen")
    back()
    if gid:
        route(f"plus_group_chat/{gid}", 5); shot387("two-accounts-group"); back()
    if mode == "profile":
        result_json("group", "jarmode swap")
        b387_multiacct_round(res, "swap")
        result_json("group", "jarmode auto")
    result_json("group", "webacctreset web:doubao")


def b387_leftovers(res):
    """38.6 leftovers: summary bubble full width, ☰ round like ⚙, Manus line, 新建群聊 怎么接 → AI 伙伴, no orphan 它,
    单聊 without 发给大家 / member strip, multi-part reply card, glass backdrop without grid."""
    r7 = R7(res)
    b381_home(); time.sleep(2)
    r = dump()
    menu = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
    gear = node_bounds(r, lambda n: (n.get("content-desc") or "") in ("设置", "Settings"))
    r7["menu_gear"] = [menu, gear]
    shot387("home")
    if menu and gear:
        mw, gw = menu[2] - menu[0], gear[2] - gear[0]
        if abs(mw - gw) > 8 or abs((menu[1] + menu[3]) - (gear[1] + gear[3])) > 16:
            selftest_fail.append(f"b387 home: ☰ {menu} and ⚙ {gear} differ in size / level")
    else: page_missing.append(f"b387 home: ☰/⚙ bounds {menu} {gear}")
    g = res.get("gid386")
    if g:
        route(f"plus_group_chat/{g}", 5)
        for _ in range(4):
            r = dump()
            if r is not None and find(r, "讨论总结"): break
            sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.7)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 400"); time.sleep(1)
        r = shot387("summary-bubble")
        r7["summary_title"] = node_bounds(r, lambda n: (n.get("text") or "") == "讨论总结")
        if r7["summary_title"] is None: page_missing.append("b387 summary bubble not on screen")
        back()
    d = res.get("direct_coze") or result_json("group", "direct mines")
    if d:
        route(f"plus_group_chat/{d}", 5)
        r = shot387("direct-chat")
        t = " ".join(texts(r)) if r is not None else ""
        r7["direct_texts"] = t[:300]
        if "发给大家" in t: selftest_fail.append("b387 单聊 still says 发给大家")
        if "位成员" in t or "@Mines" in t: selftest_fail.append(f"b387 单聊 still shows the member strip / count {t[:160]}")
        back()
    route("plus_group_partners", 8)
    r = shot387("partners")
    t = " ".join(texts(r)) if r is not None else ""
    r7["partners_texts"] = t[:600]
    if "standard" in t: selftest_fail.append("b387 partners: Manus still shows its tier/model 'standard'")
    if "Manus" in t and "自己做完再回你" not in t: page_missing.append("b387 partners: Manus plain description not seen")
    back()
    route("plus_group_new", 6)
    r = shot387("new-group")
    tx = texts(r) if r is not None else []
    if any(x.strip() == "它" for x in tx): selftest_fail.append("b387 新建群聊: orphan 它 line")
    sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.75)} {SCREEN[0]//2} {int(SCREEN[1]*0.45)} 400"); time.sleep(1.2)
    r = dump()
    hit = find(r, "怎么接", exact=True)
    if hit:
        sh(f"input tap {hit[0]} {hit[1]}"); time.sleep(5)
        r = shot387("howto-opens-partners")
        t = " ".join(texts(r)) if r is not None else ""
        r7["howto"] = t[:300]
        if "AI 伙伴" not in t: selftest_fail.append(f"b387 怎么接 did not open AI 伙伴 {t[:160]}")
        back(2)
    else:
        r7["howto"] = "no 怎么接 row"
        back()
    # multi-part reply card in a normal chat
    b381_home()
    r = dump()
    row = next(((x, y) for tx_, d_, x, y, n in nodes(r) if y > SCREEN[1] * 0.35 and y < SCREEN[1] * 0.75 and len(tx_ or "") > 3
                and not any(k in (tx_ or "") for k in ("开个新对话", "最近对话", "电量", "网络", "存储", "运行", "Claude", "在线", "家里网络升级"))), None)
    if row:
        sh(f"input tap {row[0]} {row[1]}"); time.sleep(5)
        r = dump(); e = edits(r)
        if e:
            sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text("MULTIPART")
            sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
            tap_any(["发送", "Send"], wait=12) or sh("input keyevent KEYCODE_ENTER")
            time.sleep(10)
            r = shot387("multipart-reply")
            t = " ".join(texts(r)) if r is not None else ""
            r7["multipart"] = t[-500:]
            if not any(k in t for k in ("第一部分", "第二部分", "第三部分", "最后一段")):
                selftest_fail.append(f"b387 multi-part reply not rendered {t[-200:]}")
            sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.75)} {SCREEN[0]//2} {int(SCREEN[1]*0.4)} 400"); time.sleep(1.5)
            shot387("multipart-reply-2")
        else: page_missing.append("b387 multipart: no chat input")
        back()
    else: page_missing.append("b387 multipart: no chat row on home")
    # glass sheet backdrop: no grid
    b381_home()
    if not tap_text("消息", exact=True, wait=5): tap_text("消息，", wait=5)
    time.sleep(3)
    r = dump()
    card = next(((x, y) for tx_, d_, x, y, n in nodes(r) if y > SCREEN[1] * 0.3 and y < SCREEN[1] * 0.8 and len(tx_ or "") >= 8), None)
    if card:
        sh(f"input swipe {card[0]} {card[1]} {card[0]} {card[1]} 900"); time.sleep(3)
        shot387("glass-sheet")
        gs = glass_grid_score(f"{OUT}/b387-glass-sheet.png")
        r7["glass_grid"] = gs
        print("B387 glass grid score", gs, flush=True)
        if gs is not None and gs > 2.0: selftest_fail.append(f"b387 glass backdrop still shows a grid (score {gs})")
        back()
    else: page_missing.append("b387 glass: no 消息 card to long-press")
    back()


def glass_grid_score(path):
    """Mean |pixel - 3x3 mean| over the top band (blurred backdrop): smooth blur < 1, the 38.6 grid clearly higher."""
    try:
        from PIL import Image
        im = Image.open(path).convert("L")
        w, h = im.size
        reg = im.crop((40, int(h * 0.08), w - 40, int(h * 0.30)))
        px = reg.load(); W, H = reg.size
        tot = 0; n = 0
        for y in range(1, H - 1, 3):
            for x in range(1, W - 1, 3):
                m = sum(px[x + dx, y + dy] for dx in (-1, 0, 1) for dy in (-1, 0, 1)) / 9.0
                tot += abs(px[x, y] - m); n += 1
        return round(tot / max(n, 1), 3)
    except Exception as e:
        print("glass score error", e, flush=True)
        return None


WEBCHECK = {}   # [38.7 v11] real-site check results, reused by the platform table (a second check timed out on adb)
REAL_SITES = ["web:doubao", "web:yuanbao", "web:kimi", "web:deepseek", "web:tongyi", "web:chatglm", "web:chatgpt", "web:gemini", "web:grok"]


def b387_realweb(res):
    """Real sites (emulator network, never signed in): each login page opens; not-signed-in is recognised and shown as
    「先登录」; sites that let visitors chat answer one real question."""
    r7 = R7(res)
    for sid in REAL_SITES:
        result_json("group", f"base {sid}|")
        result_json("group", f"weblogin {sid}|0")
    out = {}
    for sid in REAL_SITES:
        c = result_json("group", f"webcheck {sid}|30", timeout=90) or {}
        print("B387 webcheck", json.dumps(c, ensure_ascii=False)[:500], flush=True)
        out[sid] = {"check": c}
        WEBCHECK[sid] = c
        if not isinstance(c, dict): selftest_fail.append(f"b387 real {sid}: check failed {c}"); continue
        if c.get("state") == "SIGNED_IN": selftest_fail.append(f"b387 real {sid}: never-signed-in page classified as SIGNED_IN {c}")
        if c.get("partner") != "NEED_LOGIN" or "登录" not in c.get("reason", ""): selftest_fail.append(f"b387 real {sid}: partner not 「先登录」 {c}")
    for sid in REAL_SITES:
        c = out[sid].get("check")
        st = c.get("state") if isinstance(c, dict) else None
        if st == "GUEST":
            a = result_json("group", f"webask {sid}|你好，请用一句话介绍你自己|guest", timeout=160) or {}
            print("B387 webask", json.dumps(a, ensure_ascii=False)[:600], flush=True)
            out[sid]["ask"] = a
            if not (isinstance(a, dict) and a.get("ok") and len(a.get("text", "")) >= 2):
                selftest_fail.append(f"b387 real {sid}: guest chat got no reply {a}")
        elif st in ("LOGIN", "VERIFY"):
            a = result_json("group", f"webask {sid}|你好", timeout=130) or {}
            print("B387 webask-wall", json.dumps(a, ensure_ascii=False)[:400], flush=True)
            out[sid]["ask"] = a
            if isinstance(a, dict) and a.get("ok"): selftest_fail.append(f"b387 real {sid}: answered although the page wanted a login {a}")
    r7["realweb"] = out
    for sid in REAL_SITES:
        name = sid.split(":")[1]
        route("plus_group_weblogin/" + sid.replace(":", "%3A"), 16)
        r = shot387("login-" + name)
        t = " ".join(texts(r)) if r is not None else ""
        ink = screen_ink(f"{OUT}/b387-login-{name}.png", (0, int(SCREEN[1] * 0.2), SCREEN[0], int(SCREEN[1] * 0.9)))
        out[sid]["login_screen"] = {"title_ok": "登录" in t, "ink": ink, "texts": t[:200]}
        if "登录" not in t: page_missing.append(f"b387 real {sid}: login screen title missing")
        back()
    route("plus_group_partners", 8)
    for _ in range(3):
        r = dump()
        if r is not None and find(r, "App 账号"): break
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 500"); time.sleep(1.5)
    shot387("partners-real-need-login")
    back()


GREET_CASES = [
    ("late-night", {"hour": 23, "weather": "clear", "weekday": 1}, ("夜深", "很晚", "深夜"), ("睡", "休息", "到这儿")),
    ("dawn", {"hour": 3, "weather": "clear", "weekday": 1}, ("凌晨", "夜很深", "还没睡"), ("睡", "休息", "累")),
    ("morning", {"hour": 7, "weather": "clear", "weekday": 1}, ("早上好", "上午好", "新的一天"), ("早饭", "慢慢来", "喝杯水")),
    ("rain", {"hour": 15, "weather": "rain", "weekday": 1}, ("下午", "午后"), ("雨", "伞")),
    ("battery-low", {"hour": 10, "weather": "clear", "battery": 9, "weekday": 1}, ("早上好", "上午好", "新的一天"), ("电",)),
    ("temp-drop", {"hour": 18, "weather": "drop", "weekday": 1}, ("傍晚", "黄昏"), ("降温", "冷", "加件", "着凉", "多穿")),
    ("weekend", {"hour": 10, "weather": "clear", "weekend": True}, ("上午", "早上", "新的一天", "周末"), ("周末",)),
    ("away", {"hour": 20, "weather": "clear", "away": 6, "weekday": 1}, ("晚上", "好久不见", "忙完"), ("好久不见", "没见", "欢迎回来")),
]


def b387_greeting(res):
    """Home greeting changes with time / weather / battery / weekend / absence (injected), no sentence twice a day."""
    r7 = R7(res)
    real = selftest("group", "greetweather", timeout=40)
    r7["greet_real_weather"] = real.strip()[-200:]
    out = {}
    for tag, inj, want_t, want_c in GREET_CASES:
        g = result_json("group", "greet " + json.dumps(inj, ensure_ascii=False).replace(" ", ""), timeout=20) or {}
        b381_home(); time.sleep(2.5)
        r = shot387("greet-" + tag)
        t = " ".join(texts(r)) if r is not None else ""
        ok_api = isinstance(g, dict) and any(w in g.get("title", "") for w in want_t) and any(w in g.get("care", "") for w in want_c)
        ok_ui = isinstance(g, dict) and g.get("title", "#") in t and g.get("care", "#") in t
        out[tag] = {"greeting": g, "ui": ok_ui}
        print("B387 greet", tag, json.dumps(g, ensure_ascii=False), "ui", ok_ui, flush=True)
        if not ok_api: selftest_fail.append(f"b387 greeting {tag}: wrong text {g}")
        if not ok_ui: selftest_fail.append(f"b387 greeting {tag}: home does not show it ({t[:160]})")
        # weather line above the face (same source); the rain case must say so
        wx = next((d[2] for d in all_desc(r, "天气：")), None)
        out[tag]["weather_chip"] = wx
        # [38.7 v11] closed orbit sprite has no semantics by design (AnimeWeather); the chip is read when opened (orbit check)
        if inj.get("weather") == "rain" and not (wx and "雨" in wx): page_missing.append(f"b387 weather chip (closed sprite, no a11y by design) on rain {wx}")
        if wx:
            # [38.7 orbit] the sprite circles the face: never over ☰ / ⚙
            ch = node_bounds(r, lambda n: (n.get("content-desc") or "").startswith("天气："))
            menu = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
            gear = node_bounds(r, lambda n: (n.get("content-desc") or "") in ("设置", "Settings"))
            out[tag]["sprite_bounds"] = [ch, menu, gear]
            if ch and menu and gear and (ch[0] < menu[2] or ch[2] > gear[0]): selftest_fail.append(f"b387 weather sprite over ☰/⚙ {ch} {menu} {gear}")
    # no weather → the line is gone (no empty pill)
    result_json("group", "greet " + json.dumps({"hour": 15, "weather": "none", "weekday": 1}).replace(" ", ""), timeout=20)
    b381_home(); time.sleep(2.5)
    r = shot387("greet-no-weather")
    out["no_weather_chip"] = next((d[2] for d in all_desc(r, "天气：")), None)
    if out["no_weather_chip"]: selftest_fail.append(f"b387 weather chip shown without weather {out['no_weather_chip']}")
    # same category, same day: three different sentences
    seen = []
    for i in range(3):
        g = result_json("group", "greet " + json.dumps({"hour": 22, "weather": "clear", "weekday": 1, "n": i}).replace(" ", ""), timeout=20) or {}
        seen.append(g.get("care") if isinstance(g, dict) else None)
    out["no_repeat"] = seen
    if len(set(seen)) != 3: selftest_fail.append(f"b387 greeting repeats a sentence on the same day {seen}")
    result_json("group", "greet off")
    b381_home(); time.sleep(2); shot387("greet-real-now")
    r7["greeting"] = out


COZE_LOGIN = ["账号登录-火山引擎", "火山引擎", "账号登录", "手机号登录", "欢迎使用扣子", "欢迎来到扣子", "登录扣子", "登录 / 注册", "登录/注册", "验证码", "获取验证码", "手机号", "抖音登录", "Welcome to Coze", "Sign in", "登录"]


def focus_pkg():
    o = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2")
    return o


def coze_382():
    """[build-38.2] 一键接入 扣子: the key-fetch WebView must show coze.cn's sign-in (手机号登录 / 欢迎使用扣子 …), not a white
    page and not a jump to Chrome. Token capture itself needs a real sign-in (phone + SMS) and is NOT tested here."""
    res = {"opened": False, "login_text": None, "chrome": False, "white": False}
    sh(f"am force-stop com.android.chrome")
    # smoke 43: after the browser checks the deep link left the app on a chat (it does not navigate on the emulator);
    # go home first and open 应用 → 扣子 through the self-test hook
    b381_home()
    selftest("openkind", "coze", timeout=20); time.sleep(5)
    if not any("扣子" in x for x in (texts(dump()) or [])):
        sh(f"am start -n {PKG}/com.openminis.app.MainActivity --es mines_route plus_connections --es mines_open_kind coze", timeout=60)
    time.sleep(7)
    r = snap("coze-detail")
    t = texts(r) if r is not None else []
    print(f"B382 coze detail texts={t[:25]}", flush=True)
    if not any("扣子" in x or "Coze" in x for x in t):
        # the deep link sometimes lands on the 应用 list: search for 扣子 there
        if tap_text("扣子", wait=4): r = snap("coze-detail-2"); t = texts(r) if r is not None else []
    if not (tap_text("一键接入", exact=True, wait=3) or tap_text("再生成一个新密钥", wait=3)):
        scroll_down(); time.sleep(1)
        if not (tap_text("一键接入", exact=True, wait=3) or tap_text("再生成一个新密钥", wait=3)):
            selftest_fail.append("b382 coze: 一键接入 button not found")
            print(f"B382 coze {res}", flush=True); return res
    res["opened"] = True
    seen = []
    for i in range(10):          # up to ~60 s for coze.cn → signin.volcengine.com to render
        time.sleep(6)
        r = dump()
        t = texts(r) if r is not None else []
        seen = t
        # smoke 40: the dialog's own note 「扣子要先用手机号登录（火山引擎账号）…Mines…」 matched 「手机号登录」 — only page text counts
        t = [x for x in t if "Mines" not in x and not x.startswith("正在接入") and "付费按钮" not in x]
        fp = focus_pkg()
        if "com.android.chrome" in fp: res["chrome"] = True; break
        if any("已改用 Chrome" in x for x in t): res["chrome"] = True; break
        hit = next((h for h in COZE_LOGIN[:-2] if any(h in x for x in t)), None)
        if hit: res["login_text"] = hit; break
        weak = next((h for h in COZE_LOGIN[-2:] if any(x.strip() == h for x in t)), None)   # a bare 「登录」/「Sign in」 button
        if weak and i >= 4: res["login_text"] = weak; break
        if any("显示不出来" in x or "白屏" in x for x in t): res["white"] = True
    wait_page_loaded("keyagent-coze", limit=30)
    rk = snap("coze-keyagent")
    subprocess.run(f"cp {OUT}/{step_no:02d}-coze-keyagent.png {OUT}/b383-keyagent-coze.png", shell=True)
    # [build-38.4] the action row sits below the page in its own opaque space, above the navigation bar
    wvb = node_bounds(rk, lambda n: n.get("class", "").endswith("WebView"))
    btn = node_bounds(rk, lambda n: (n.get("text") or "") in ("重试",))
    print(f"B384 keyagent webview={wvb} retry={btn}", flush=True)
    if wvb and btn and wvb[3] > btn[1] + 2: page_missing.append(f"b384 keyagent: buttons overlap the page (webview bottom {wvb[3]} > button top {btn[1]})")
    if btn and btn[3] > SCREEN[1]: page_missing.append(f"b384 keyagent: buttons below the screen ({btn})")
    if not btn: page_missing.append("b384 keyagent: 重试 button not found")
    jk = browser_probe("keyagent-coze", want_dp="screen")
    if jk and jk.get("ok") and not jk.get("fits"): print("B383 keyagent-coze: page wider than the screen", flush=True)
    print(f"B382 coze texts={seen[:40]} focus={focus_pkg().strip()[:200]}", flush=True)
    if res["chrome"]: selftest_fail.append("b382 coze: jumped to Chrome instead of showing the sign-in page")
    elif not res["login_text"]: selftest_fail.append("b382 coze: sign-in page text not found (white page?)")
    print(f"B382 coze {res}", flush=True)
    for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
    sh("am force-stop com.android.chrome")
    if not alive(): relaunch()
    return res


# ======================= [build-38.7 v2] weather orbit / fog backdrop / SMS autofill / 一键关联 / 手机权限 =======================

def _bounds_desc(r, prefix):
    return node_bounds(r, lambda n: (n.get("content-desc") or "").startswith(prefix))


def _crop_diff(a, b, box, lvl=12):
    try:
        from PIL import Image, ImageChops
        ia = Image.open(a).convert("RGB").crop(box); ib = Image.open(b).convert("RGB").crop(box)
        d = ImageChops.difference(ia, ib).convert("L")
        hist = d.histogram(); n = sum(hist)
        changed = sum(hist[lvl:]) / max(1, n)
        return round(changed, 4)
    except Exception as e:
        return f"err {e}"


def _grid_score(path, box):
    """stripes / grid check: mean absolute 2nd difference along rows and columns of a smooth background crop (higher = patterned)."""
    try:
        from PIL import Image
        im = Image.open(path).convert("L").crop(box)
        w, h = im.size; px = im.load(); tot = 0; n = 0
        for y in range(2, h - 2, 7):
            for x in range(2, w - 2, 7):
                tot += abs(px[x - 1, y] - 2 * px[x, y] + px[x + 1, y]) + abs(px[x, y - 1] - 2 * px[x, y] + px[x, y + 1]); n += 2
        return round(tot / max(1, n), 3)
    except Exception as e:
        return f"err {e}"


def anim(on):
    """the smoke emulator runs with animations off; the orbit / fog / day-night checks need them on (app relaunched)"""
    v = "1" if on else "0"
    sh(f"settings put global window_animation_scale {v}; settings put global transition_animation_scale {v}; settings put global animator_duration_scale {v}")
    adb("shell", "am", "force-stop", PKG); time.sleep(1)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(8)


def b387_orbit(res):
    """sun / rain / night: the sprite circles the face (two frames differ in position), a tap on the face brings the big
    picture to the top of the head with the °-pill, then it goes back to the orbit."""
    r7 = R7(res); out = {}
    for tag, inj in (("sun", {"hour": 15, "weather": "clear", "weekday": 1}), ("rain", {"hour": 15, "weather": "rain", "weekday": 1}),
                     ("night", {"hour": 23, "weather": "clear", "weekday": 1})):
        result_json("group", "greet " + json.dumps(inj).replace(" ", ""), timeout=20)
        b381_home(); time.sleep(3)
        r1 = shot387(f"orbit-{tag}-1"); b1 = _bounds_desc(r1, "天气：")
        time.sleep(1.6)
        r2 = shot387(f"orbit-{tag}-2"); b2 = _bounds_desc(r2, "天气：")
        av = _bounds_desc(r2, "换角色")
        o = {"sprite1": b1, "sprite2": b2, "avatar": av}
        if (not b1 or not b2) and av:
            # uiautomator often misses the moving sprite node: compare the pixels around the face instead
            bx = (max(0, av[0] - 200), max(0, av[1] - 200), min(SCREEN[0] or 1080, av[2] + 200), av[3] + 200)
            pd = _crop_diff(f"{OUT}/b387-orbit-{tag}-1.png", f"{OUT}/b387-orbit-{tag}-2.png", bx)
            o["pixel_move"] = pd
            if not isinstance(pd, float) or pd < 0.002: selftest_fail.append(f"b387 orbit {tag}: sprite not found and no movement around the face {pd}")
        elif not b1 or not b2: selftest_fail.append(f"b387 orbit {tag}: sprite not found {b1} {b2}")
        elif b1 == b2: selftest_fail.append(f"b387 orbit {tag}: sprite did not move {b1}")
        if av:
            adb("shell", "input", "tap", str((av[0] + av[2]) // 2), str((av[1] + av[3]) // 2)); time.sleep(1.3)
            r3 = shot387(f"orbit-{tag}-open")
            t3 = " ".join(texts(r3)) if r3 is not None else ""
            big = [d for d in all_desc(r3, "天气：")]
            o["open_desc"] = big[:3]
            above = [d for d in big if d[1] < av[1] + (av[3] - av[1]) * 0.15]
            o["open_above"] = bool(above)
            # [38.7 v11] uiautomator misses the animated big picture / pill (smoke 38735737556: visible in the shots, absent in
            # the dump): compare pixels above the head and under the face between the orbit frame and the open frame
            W_ = SCREEN[0] or 1080
            head = (max(0, av[0] - 120), max(0, av[1] - 260), min(W_, av[2] + 120), av[1] + 20)
            pill = (max(0, av[0] - 160), av[3], min(W_, av[2] + 160), av[3] + 140)
            ph = _crop_diff(f"{OUT}/b387-orbit-{tag}-1.png", f"{OUT}/b387-orbit-{tag}-open.png", head)
            pp = _crop_diff(f"{OUT}/b387-orbit-{tag}-1.png", f"{OUT}/b387-orbit-{tag}-open.png", pill)
            o["open_px_head"] = ph; o["open_px_pill"] = pp
            if not above and not (isinstance(ph, float) and ph >= 0.01): selftest_fail.append(f"b387 orbit {tag}: big picture not above the head {big} {av} px={ph}")
            if "°" not in t3 and not (isinstance(pp, float) and pp >= 0.01): selftest_fail.append(f"b387 orbit {tag}: temperature pill not shown px={pp}")
            time.sleep(5.5)
            r4 = shot387(f"orbit-{tag}-back")
            o["back"] = _bounds_desc(r4, "天气：")
        else: page_missing.append(f"b387 orbit {tag}: avatar bounds missing")
        out[tag] = o
        print("B387 orbit", tag, json.dumps(o), flush=True)
    for a, b in (("sun", "home"),):
        subprocess.run(f"cp {OUT}/b387-orbit-sun-1.png {OUT}/home.png", shell=True)
    result_json("group", "greet off")
    r7["orbit"] = out


def b387_fog(res):
    """the backdrop fog moves: same page, two frames 3 s apart differ in an empty background area; no stripes / grid."""
    r7 = R7(res)
    route("plus_permissions_guide", 4)
    a = f"{OUT}/b387-fog-1.png"; b = f"{OUT}/b387-fog-2.png"
    f1 = result_json("group", "fogstate"); shot387("fog-1"); time.sleep(3); shot387("fog-2"); f2 = result_json("group", "fogstate")
    W, H = (SCREEN[0], SCREEN[1]) if SCREEN and SCREEN[0] else (1080, 2362)
    box = (0, int(H * 0.86), W, H - 10)   # under the card list (empty backdrop)
    d = _crop_diff(a, b, box, lvl=2); g = _grid_score(a, (0, int(H * 0.86), W, H - 10))
    r7["fog"] = {"changed": d, "grid": g, "box": box, "state1": f1, "state2": f2}
    print("B387 fog", r7["fog"], flush=True)
    if not isinstance(d, float) or d < 0.01: selftest_fail.append(f"b387 fog: backdrop did not move {d}")
    if isinstance(g, float) and g > 6: selftest_fail.append(f"b387 fog: patterned backdrop (grid score {g})")
    back()


def b387_sms(res):
    """phone + SMS login page (fake): `adb emu sms send` → the code is filled in by itself, 「已自动填好验证码」 shown."""
    r7 = R7(res)
    B = "http://10.0.2.2:8088"
    result_json("group", f"setbase web:kimi|{B}/smslogin")
    result_json("group", "weblogin web:kimi|0")
    route("plus_group_weblogin/web%3Akimi", 9)
    shot387("sms-page")
    sh('emu sms send 10690000 "【Kimi】您的验证码是482913，5分钟内有效，请勿泄露。"') if False else subprocess.run(
        ['adb', 'emu', 'sms', 'send', '10690000', '【Kimi】您的验证码是482913，5分钟内有效，请勿泄露。'], capture_output=True, timeout=30)
    ok = False; t = ""
    for _ in range(8):
        time.sleep(2.5)
        r = dump(); t = " ".join(texts(r)) if r is not None else ""
        if "482913" in t or "已自动填好验证码" in t: ok = True; break
    shot387("sms-filled")
    r7["sms"] = {"ok": ok, "text": t[:300]}
    print("B387 sms", r7["sms"], flush=True)
    if not ok: selftest_fail.append(f"b387 sms: code not auto-filled {t[:200]}")
    back(); result_json("group", "setbase web:kimi|")


def b387_linkall(res):
    """一键关联: start page, start → first login page opens by itself, back → next step, stop → 关联结果 groups."""
    r7 = R7(res); o = {}
    result_json("group", "linknote web:yuanbao|扫码")
    result_json("group", "linkmark web:deepseek|0")
    result_json("group", "linkmark web:gemini|1")
    route("plus_linkall", 4)
    r = shot387("linkall-start"); t = " ".join(texts(r)) if r is not None else ""
    if "一键关联" not in t or "开始" not in t: page_missing.append(f"b387 linkall start page {t[:160]}")
    if tap_text("开始", exact=True, wait=7):
        r = shot387("linkall-login"); t = " ".join(texts(r)) if r is not None else ""
        o["login"] = t[:200]
        o["login_lum"] = _lum(f"{OUT}/b387-linkall-login.png"); o["login_flat"] = _flat(f"{OUT}/b387-linkall-login.png")
        time.sleep(3); shot387("linkall-login-2"); o["login2_lum"] = _lum(f"{OUT}/b387-linkall-login-2.png"); o["login2_flat"] = _flat(f"{OUT}/b387-linkall-login-2.png")
        # [v11d] a white sign-in SITE is fine (元宝 is white); a frame with nothing on it (no header either) is the bug
        if o["login_flat"] is not None: selftest_fail.append(f"b387 linkall: blank frame when the sign-in page opened {o['login_flat']}")
        if o["login2_flat"] is not None: selftest_fail.append(f"b387 linkall: still blank 3 s later {o['login2_flat']}")
        if "登录" not in t: selftest_fail.append(f"b387 linkall: login page did not open {t[:160]}")
        back(); time.sleep(1.2)
        r = shot387("linkall-next"); t = " ".join(texts(r)) if r is not None else ""
        o["next"] = t[:200]
        # coming back counts the platform and (3 s later) opens the next one by itself
        # [38.8] the queue order changed (豆包 first): "moved on" = 下一个 shown, or a DIFFERENT platform's sign-in page
        first = (re.search(r"登录 (\S+)", o.get("login", "")) or [None, None])[1]
        def _moved(tx):
            m2 = re.search(r"登录 (\S+)", tx)
            return "下一个" in tx or "第 2 个" in tx or (m2 is not None and first is not None and m2.group(1) != first)
        moved = _moved(t)
        for _ in range(8):
            if moved: break
            time.sleep(1); r2 = dump(); t2 = " ".join(texts(r2)) if r2 is not None else ""
            moved = _moved(t2)
        o["moved_on"] = moved
        if not moved: selftest_fail.append(f"b387 linkall: did not move on after coming back {t[:160]}")
        tap_text("停一下", exact=True, wait=1.5)
        tap_text("结束，看结果", exact=True, wait=3)
    else: page_missing.append("b387 linkall: 开始 not tappable")
    r = dump(); t = " ".join(texts(r)) if r is not None else ""
    if "关联结果" not in t:
        route("plus_linkall_summary", 4); r = dump(); t = " ".join(texts(r)) if r is not None else ""
    shot387("linkall-summary")
    o["summary"] = t[:400]
    for want in ("关联结果", "已关联", "需要你操作", "过期了"):
        if want not in t: selftest_fail.append(f"b387 linkall summary: missing {want}")
    o["states"] = result_json("group", "linkstates")
    r7["linkall"] = o
    print("B387 linkall", json.dumps(o, ensure_ascii=False)[:800], flush=True)
    back(); back()


def b387_perms(res):
    r7 = R7(res)
    route("plus_permissions_guide", 4)
    r = shot387("perms"); t = " ".join(texts(r)) if r is not None else ""
    o = {"text": t[:400]}
    for want in ("手机权限", "短信验证码", "麦克风"):
        if want not in t: page_missing.append(f"b387 perms: {want} missing")
    if tap_text("读取通知", wait=2):
        r = shot387("perms-step"); t2 = " ".join(texts(r)) if r is not None else ""
        o["step"] = t2[:200]
        if "去设置" not in t2 and "打开" not in t2: selftest_fail.append(f"b387 perms: no one-step guide {t2[:160]}")
        tap_text("先不开", exact=True, wait=1.5)
    r7["perms"] = o
    back()


def b387_daynight(res):
    """day → night while the app is open: fake clock crosses the sunset; frames before / mid-transition / after."""
    r7 = R7(res); o = {}
    result_json("group", "thememode 3")
    result_json("group", "greet " + json.dumps({"weather": "clear", "weekday": 1}).replace(" ", ""), timeout=20)
    result_json("group", "daynight 15:00:00|18:30"); b381_home(); time.sleep(4)
    o["sim"] = selftest("group", "daynight 18:29:58|18:30", timeout=20).strip()[-120:]
    t0 = time.time()
    shot387("daynight-0-day")
    while time.time() - t0 < 3.2: time.sleep(0.1)
    rm = shot387("daynight-1-mid")
    time.sleep(4.5)
    re_ = shot387("daynight-2-night")
    W, H = (SCREEN[0], SCREEN[1]) if SCREEN and SCREEN[0] else (1080, 2362)
    box = (0, int(H * 0.9), W, H - 10)
    try:
        from PIL import Image
        def mean(pth):
            im = Image.open(pth).convert("L").crop(box); h = im.histogram(); n = sum(h)
            return round(sum(i * v for i, v in enumerate(h)) / max(1, n), 1)
        o["lum"] = [mean(f"{OUT}/b387-daynight-{k}.png") for k in ("0-day", "1-mid", "2-night")]
    except Exception as e:
        o["lum"] = f"err {e}"
    o["sprite_night"] = next((d[2] for d in all_desc(re_, "天气：")), None)
    print("B387 daynight", json.dumps(o, ensure_ascii=False), flush=True)
    L = o["lum"]
    if isinstance(L, list):
        if not (L[0] - L[2] > 40): selftest_fail.append(f"b387 daynight: did not turn dark {L}")
        if not (L[2] + 5 < L[1] < L[0] - 5): page_missing.append(f"b387 daynight: mid frame not between day and night {L}")
    r7["daynight"] = o
    result_json("group", "daynight off"); result_json("group", "greet off"); result_json("group", "thememode 1")



def b387_still(res):
    """animations off: sprite parked at the face's upper right, a tap shows the pill (no flight)"""
    r7 = R7(res)
    anim(False)
    result_json("group", "greet " + json.dumps({"hour": 15, "weather": "clear", "weekday": 1}).replace(" ", ""), timeout=20)
    b381_home(); time.sleep(3)
    r = shot387("still-1"); time.sleep(1.5); r2 = shot387("still-2")
    b1 = _bounds_desc(r, "天气："); b2 = _bounds_desc(r2, "天气：")
    r7["still"] = {"b1": b1, "b2": b2}
    if b1 != b2: selftest_fail.append(f"b387 still: sprite moves with animations off {b1} {b2}")
    result_json("group", "greet off")
    anim(True)


def b387_animoff(res):
    """functional checks run with animations off: uiautomator cannot dump a screen that never goes idle (orbit / fog)"""
    anim(False)


def b387_xlogin(res):
    """X in 账号连接: the in-app sign-in page opens x.com/i/flow/login, never crashes the app; no-connection hint."""
    r7 = R7(res); o = {}
    sh("logcat -c")
    result_json("group", "openlogin x"); time.sleep(4)
    shot387("x-login-choice")
    tap_text("在 Mines 内登录", wait=14)
    r = shot387("x-login"); t = " ".join(texts(r)) if r is not None else ""
    o["alive"] = alive(); o["text"] = t[:300]
    lc = sh("logcat -d -b crash | tail -40", timeout=30)
    o["crash"] = lc[-600:] if "FATAL" in lc or "AndroidRuntime" in lc else ""
    if not o["alive"]: selftest_fail.append("b387 x: app died on the X login page")
    if o["crash"]: selftest_fail.append(f"b387 x: crash {o['crash'][:300]}")
    if not any(k in t + " ".join(texts(dump()) or []) for k in ("外部浏览器", "外部打开")): page_missing.append("b387 x: 外部浏览器 button / note missing")
    o["login_seen"] = any(w in t for w in ("登录 X", "Sign in to X", "登录到 X", "Phone, email", "手机号码、邮件地址", "电子邮件", "连不上 X"))
    if not o["login_seen"]: page_missing.append(f"b387 x: no X sign-in form or connection hint {t[:200]}")
    r7["xlogin"] = o
    print("B387 xlogin", json.dumps(o, ensure_ascii=False)[:900], flush=True)
    back(); back()


def b387_pwfill(res):
    """saved login (Edge CSV import) is typed into an in-app sign-in page + submitted in 一键关联 mode."""
    r7 = R7(res)
    B = "http://10.0.2.2:8088"
    print(selftest("group", "savedlogins name,url,username,password\\n10.0.2.2," + B + "/pwlogin,smoke_user,smoke_pass"), flush=True)
    result_json("group", f"setbase web:kimi|{B}/pwlogin")
    result_json("group", "weblogin web:kimi|0")
    route("plus_group_weblogin/web%3Akimi", 10)
    r = shot387("pw-filled"); t = " ".join(texts(r)) if r is not None else ""
    ok = "已填好保存的账号密码" in t or "smoke_user" in t
    r7["pwfill"] = {"ok": ok, "text": t[:300]}
    print("B387 pwfill", r7["pwfill"], flush=True)
    if not ok: selftest_fail.append(f"b387 pwfill: saved login not filled {t[:200]}")
    back(); result_json("group", "setbase web:kimi|")


def _lum(path):
    try:
        from PIL import Image
        W, H = (SCREEN[0], SCREEN[1]) if SCREEN and SCREEN[0] else (1080, 2362)
        im = Image.open(path).convert("L").crop((0, int(H * .1), W, int(H * .9))); h = im.histogram()
        return round(sum(i * v for i, v in enumerate(h)) / max(1, sum(h)), 1)
    except Exception as e:
        return f"err {e}"


def _flat(path):
    """whole screen (below the status bar) one colour = nothing drawn; returns the stddev when flat, else None"""
    try:
        from PIL import Image, ImageStat
        im = Image.open(path).convert("L"); W, H = im.size
        sd = ImageStat.Stat(im.crop((0, int(H * .04), W, int(H * .95)))).stddev[0]
        return round(sd, 2) if sd < 2.0 else None
    except Exception as e:
        return None


def b387_noblack(res):
    """FLAG_SECURE gone: screencap of 账号连接 / key page / chat / settings is not black."""
    r7 = R7(res); out = {}
    for tag, rt in (("connections", "plus_connections"), ("settings", "settings"), ("cards", "plus_cards"), ("linkall", "plus_linkall")):
        route(rt, 4)
        snap_ = shot387("noblack-" + tag)
        if tag == "linkall":   # [38.7 v11] 一键关联 may open the next sign-in page right now: look again 4 s later
            out["linkall_first"] = _lum(f"{OUT}/b387-noblack-linkall.png"); out["linkall_first_flat"] = _flat(f"{OUT}/b387-noblack-linkall.png")
            if out["linkall_first_flat"] is not None: selftest_fail.append(f"b387 noblack linkall: blank frame {out['linkall_first_flat']}")
            time.sleep(4); snap_ = shot387("noblack-" + tag)
            out["linkall_second_flat"] = _flat(f"{OUT}/b387-noblack-linkall.png")
            if out["linkall_second_flat"] is not None: selftest_fail.append(f"b387 noblack linkall: blank 4 s later {out['linkall_second_flat']}")
        W, H = (SCREEN[0], SCREEN[1]) if SCREEN and SCREEN[0] else (1080, 2362)
        try:
            from PIL import Image
            im = Image.open(f"{OUT}/b387-noblack-{tag}.png").convert("L").crop((0, int(H * .1), W, int(H * .8))); h = im.histogram()
            mean = sum(i * v for i, v in enumerate(h)) / max(1, sum(h))
        except Exception as e:
            mean = -1
        out[tag] = round(mean, 1)
        if 0 <= mean < 8: selftest_fail.append(f"b387 noblack: {tag} screenshot is black")
        back()
    r7["noblack"] = out
    print("B387 noblack", out, flush=True)
    route("plus_routing", 4); r = shot387("routing"); t = " ".join(texts(r)) if r is not None else ""
    r7["routing_text"] = t[:200]
    if "同模型自动成组" in t: selftest_fail.append("b387 routing: 同模型自动成组 still shown")
    back()



def b387_vps(res):
    """快速线路 code read hangs (simulated): retries, auto-configure, then red 「快速线路未就绪」 + notification with 重试."""
    r7 = R7(res); o = {}
    raw = selftest("group", "vpscodesim", timeout=30)
    m_ = re.search(r"result=([0-9a-f-]{8,})", raw or "")
    sid = m_.group(1) if m_ else ""
    o["server"] = sid
    route("plus_server/" + sid.strip(), 3)
    shot387("vps-reading")
    t = ""
    for _ in range(12):
        time.sleep(3)
        r = dump(); t = " ".join(texts(r)) if r is not None else ""
        if "快速线路未就绪：" in t and "正在读取" not in t: break
    shot387("vps-notready")
    o["text"] = t[:300]
    if "快速线路未就绪" not in t: selftest_fail.append(f"b387 vps: no 快速线路未就绪 after timeouts {t[:200]}")
    if "已连接" in t and "未就绪" in t: selftest_fail.append("b387 vps: shows 已连接 while not ready")
    sh("cmd statusbar expand-notifications"); time.sleep(2.5)
    r = shot387("vps-notification"); tn = " ".join(texts(r)) if r is not None else ""
    o["notification"] = tn[:300]
    if "快速线路未就绪" not in tn: selftest_fail.append(f"b387 vps: notification missing {tn[:200]}")
    if "重试" not in tn: page_missing.append("b387 vps: 重试 action not seen in the shade")
    sh("cmd statusbar collapse"); time.sleep(1)
    result_json("group", "vpscodesim off")
    r7["vps"] = o
    print("B387 vps", json.dumps(o, ensure_ascii=False)[:800], flush=True)
    back()


def b387_ignore(res):
    """忽略 in 关联结果 → 已忽略 section; restore from 设置 › 已忽略的平台 (both places)."""
    r7 = R7(res); o = {}
    route("plus_linkall_summary", 4)
    ok = tap_text("忽略", exact=True, wait=2)
    r = shot387("linkall-ignored"); t = " ".join(texts(r)) if r is not None else ""
    o["tapped"] = ok; o["text"] = t[:300]
    st1 = result_json("group", "linkstates") or {}
    ign = [k for k, v in st1.items() if v == "已忽略"] if isinstance(st1, dict) else []
    o["ignored_after_tap"] = ign
    if ok and not ign: selftest_fail.append(f"b387 ignore: 忽略 tapped but nothing is 已忽略 {t[:160]}")
    # the 已忽略 section is the last one: scroll to it and restore there
    for _ in range(40):   # [38.7 v11] 85 platforms: 14 swipes stopped at ClickUp, before the 已忽略 section
        r = dump()
        if r is not None and find(r, "恢复", exact=True): break
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.8)} {SCREEN[0]//2} {int(SCREEN[1]*0.25)} 300"); time.sleep(0.8)
    r = shot387("linkall-ignored-section"); t2 = " ".join(texts(r)) if r is not None else ""
    if ign and "已忽略" not in t2: selftest_fail.append(f"b387 ignore: no 已忽略 section in 关联结果 {t2[-200:]}")
    if tap_text("恢复", exact=True, wait=2):
        st2 = result_json("group", "linkstates") or {}
        o["restored_in_summary"] = not any(v == "已忽略" for v in st2.values()) if isinstance(st2, dict) else None
        if not o["restored_in_summary"]: selftest_fail.append("b387 ignore: 恢复 in 关联结果 did not restore")
    back()
    # ignore one again, then restore it from 设置
    result_json("group", "linkignore conn:weibo|1")
    route("settings", 4)
    for _ in range(6):
        r = dump()
        if r is not None and find(r, "已忽略的平台"): break
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.75)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 400"); time.sleep(1)
    r = shot387("settings-ignored-row"); t = " ".join(texts(r)) if r is not None else ""
    o["settings_row"] = "已忽略的平台" in t
    if "已忽略的平台" not in t: selftest_fail.append("b387 ignore: 设置 has no 已忽略的平台 row")
    if tap_text("已忽略的平台", exact=True, wait=3):
        r = shot387("settings-ignored-page"); t = " ".join(texts(r)) if r is not None else ""
        o["page"] = t[:300]
        if "微博" not in t: selftest_fail.append(f"b387 ignore: 已忽略的平台 page does not list 微博 {t[:160]}")
        if tap_text("全部恢复", exact=True, wait=2):
            r = shot387("settings-ignored-restored"); t = " ".join(texts(r)) if r is not None else ""
            o["restored_in_settings"] = "没有忽略的平台" in t
            if "没有忽略的平台" not in t: selftest_fail.append(f"b387 ignore: restore in 设置 did not clear the list {t[:160]}")
        back()
    else: page_missing.append("b387 ignore: 已忽略的平台 not tappable")
    st = result_json("group", "linkstates") or {}
    o["weibo_state"] = st.get("conn:weibo") if isinstance(st, dict) else None
    r7["ignore"] = o
    print("B387 ignore", json.dumps(o, ensure_ascii=False)[:600], flush=True)
    back()



def _type_into_first_edit(digits):
    r = dump()
    eds = []
    if r is not None:
        for n in r.iter("node"):
            if n.get("class") == "android.widget.EditText":
                b = re.findall(r"\d+", n.get("bounds") or "")
                if len(b) == 4: eds.append(tuple(map(int, b)))
    if not eds: return None, ""
    x1, y1, x2, y2 = eds[0]
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)); time.sleep(1.5)
    # a prefilled phone number (本机号码 autofill, maxlength 11) would swallow the typed digits: clear it first
    sh("input keycombination 113 29"); time.sleep(0.3); sh("input keyevent KEYCODE_DEL")   # [38.7 v11] select all first (a saved name stayed)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * 24)); time.sleep(0.8)
    adb("shell", "input", "text", digits); time.sleep(2)
    r = dump(); val = ""
    if r is not None:
        for n in r.iter("node"):
            if n.get("class") == "android.widget.EditText":
                b = tuple(map(int, re.findall(r"\d+", n.get("bounds") or "0 0 0 0")))
                if b == eds[0]: val = n.get("text") or ""
    return eds[0], val


def b387_typing(res):
    """DeepSeek-style phone field: typed digits must come out in order (user saw 123 → 321)."""
    r7 = R7(res); out = {}
    B = "http://10.0.2.2:8088"
    cases = [("fake-sms", "web:kimi", f"{B}/smslogin"), ("fake-pw", "web:kimi", f"{B}/pwlogin"), ("deepseek", "web:deepseek", ""), ("kimi", "web:kimi", ""), ("doubao", "web:doubao", "")]
    for tag, sid, base in cases:
        result_json("group", f"setbase {sid}|{base}")
        result_json("group", f"weblogin {sid}|0")
        route("plus_group_weblogin/" + sid.replace(":", "%3A"), 14)
        if tag == "deepseek":   # its phone tab may need a tap first
            tap_any(["手机号登录", "验证码登录", "Phone"], wait=2)
        b, val = _type_into_first_edit("13800138000")
        shot387("typing-" + tag)
        digits = re.sub(r"\D", "", val or "")
        out[tag] = {"field": b, "value": val}
        if b is None: page_missing.append(f"b387 typing {tag}: no input field found")
        elif "13800138000" not in digits and digits:
            selftest_fail.append(f"b387 typing {tag}: typed 13800138000, field shows {val!r}")
        back(); time.sleep(1)
    result_json("group", "setbase web:kimi|")
    r7["typing"] = out
    print("B387 typing", json.dumps(out, ensure_ascii=False), flush=True)


REGRESS_ROUTES = ["settings", "plus_connections", "plus_personalize", "plus_voice_settings", "plus_sandbox", "plus_plugins", "plus_market",
                  "plus_routing", "plus_tidy", "plus_knowledge", "plus_verify", "plus_update", "plus_report", "plus_digest", "plus_cards",
                  "plus_work_location", "plus_group", "plus_group_partners", "plus_group_new", "plus_linkall", "plus_linkall_summary",
                  "plus_permissions_guide", "plus_linkall_ignored", "plus_migrate"]


def b387_regress(res):
    """every page opens (screenshot), app alive, not black; chat input takes letters + digits."""
    r7 = R7(res); rows = []
    for rt in REGRESS_ROUTES:
        sh("logcat -c")
        route(rt, 4)
        r = shot387("reg-" + rt)
        t = " ".join(texts(r)) if r is not None else ""
        lc = sh("logcat -d -b crash | tail -20", timeout=20)
        ok = alive() and bool(t.strip()) and "FATAL" not in lc
        rows.append({"page": rt, "ok": ok, "shot": f"b387-reg-{rt}.png", "text": t[:80]})
        if not ok: selftest_fail.append(f"b387 regress: {rt} failed (alive={alive()}, crash={'FATAL' in lc})")
        if not alive(): relaunch()
        back(); time.sleep(0.8)
    # chat input
    b381_home(); time.sleep(2)
    tap_any(["开个新对话…", "开个新对话"], wait=3)
    b, val = _type_into_first_edit("Hello123")
    shot387("reg-chat-typing")
    rows.append({"page": "chat input", "ok": "Hello123" in (val or ""), "shot": "b387-reg-chat-typing.png", "text": val})
    if "Hello123" not in (val or ""): page_missing.append(f"b387 regress: chat input shows {val!r}")
    back()
    r7["regress"] = rows
    print("B387 REGRESS " + json.dumps(rows, ensure_ascii=False), flush=True)



def b387_persist(res):
    """in-app sign-in survives an app restart: the two fake 豆包 accounts stay signed in and still answer as themselves."""
    r7 = R7(res); o = {}
    o["connfake"] = {k: result_json("group", f"connfake {k}") for k in ("x", "weibo")}
    # two real in-app sign-ins on the runner's fake 豆包 site (cookies + local storage), as in the multi-account test
    B = "http://10.0.2.2:8088"
    result_json("group", "webacctreset web:doubao")
    result_json("group", f"base web:doubao|{B}/webacct/")
    a2 = result_json("group", "webacctadd web:doubao")
    o["login_a"] = result_json("group", f"webacctlogin web:doubao|{B}/webacct/login?u=A", timeout=60)
    o["login_b"] = result_json("group", f"webacctlogin {a2}|{B}/webacct/login?u=B", timeout=60)
    st0 = result_json("group", "linkstates") or {}
    o["conn_before"] = {k: st0.get(k) for k in ("conn:x", "conn:weibo")} if isinstance(st0, dict) else st0
    before = result_json("group", "webstates") or []
    o["before"] = [(w.get("id"), w.get("state")) for w in before if isinstance(w, dict) and "doubao" in w.get("id", "")]
    adb("shell", "am", "force-stop", PKG); time.sleep(2)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(10)
    after = result_json("group", "webstates") or []
    o["after"] = [(w.get("id"), w.get("state")) for w in after if isinstance(w, dict) and "doubao" in w.get("id", "")]
    ask = result_json("group", "webask web:doubao|你是哪个账号", timeout=150) or {}
    o["ask_after_restart"] = ask
    o["links"] = result_json("group", "linkstates")
    o["conn_after"] = {k: o["links"].get(k) for k in ("conn:x", "conn:weibo")} if isinstance(o["links"], dict) else None
    for k in ("x", "weibo"): result_json("group", f"connfake {k}|off")
    r7["persist"] = o
    print("B387 persist", json.dumps(o, ensure_ascii=False)[:900], flush=True)
    ready_after = [x for x in o["after"] if x[1] == "READY"]
    cp = o.get("conn_after") or {}
    for k in ("conn:x", "conn:weibo"):
        if cp.get(k) != "已关联": selftest_fail.append(f"b387 persist: {k} not 已关联 after restart ({cp.get(k)})")
    if not ready_after: selftest_fail.append(f"b387 persist: sign-in lost after restart {o['after']}")
    if not (isinstance(ask, dict) and ask.get("ok")): selftest_fail.append(f"b387 persist: account does not answer after restart {ask}")


def b387_platforms(res):
    """一键关联, every platform for real (never signed in on this emulator): sign-in page opens inside Mines, no crash,
    no silent white page; the page state + the hint Mines shows are recorded for the report table."""
    r7 = R7(res); rows = []
    for sid in ([x for x in REAL_SITES if x == "web:yuanbao"] if FAST else REAL_SITES):
        result_json("group", f"base {sid}|"); result_json("group", f"weblogin {sid}|")
        sh("logcat -c")
        route("plus_group_weblogin/" + sid.replace(":", "%3A"), 16)
        r = shot387("plat-" + sid.split(":")[1]); t = " ".join(texts(r)) if r is not None else ""
        lc = sh("logcat -d -b crash | tail -20", timeout=20)
        W, H = (SCREEN[0], SCREEN[1]) if SCREEN and SCREEN[0] else (1080, 2362)
        try:
            from PIL import Image
            im = Image.open(f"{OUT}/b387-plat-{sid.split(':')[1]}.png").convert("L").crop((0, int(H * .25), W, int(H * .9)))
            ext = im.getextrema(); flat = (ext[1] - ext[0]) < 12
        except Exception:
            flat = None
        c = WEBCHECK.get(sid) if isinstance(WEBCHECK.get(sid), dict) and WEBCHECK[sid].get("state") else None
        if c is None:
            try:
                c = result_json("group", f"webcheck {sid}|25", timeout=80) or {}
            except Exception as e:
                c = {"state": "TIMEOUT", "reason": f"页面 80 秒没判断完：{e}"[:120]}
        row = {"platform": sid, "alive": alive(), "crash": "FATAL" in lc, "white": flat, "state": c.get("state") if isinstance(c, dict) else None,
               "partner": c.get("partner") if isinstance(c, dict) else None, "reason": (c.get("reason") if isinstance(c, dict) else "") or "",
               "shot": f"b387-plat-{sid.split(':')[1]}.png", "page": t[:120]}
        rows.append(row)
        if not row["alive"] or row["crash"]: selftest_fail.append(f"b387 platform {sid}: app crashed on its sign-in page")
        if flat: selftest_fail.append(f"b387 platform {sid}: white page without a hint")
        if not row["alive"]: relaunch()
        back(); time.sleep(1)
    # [38.7 v8] every 账号连接 platform (AI 服务商, 开发, 办公, 金融, 社交 incl. X / 微博 …) the way 一键关联 opens it
    info = result_json("group", "linkinfo", timeout=60) or []
    r7["linkinfo"] = info
    for p in info:
        sid = p.get("id", "")
        if not sid.startswith("conn:"): continue
        if FAST and sid[5:] not in ("douyin", "x", "feishu", "qq", "weibo"): continue
        kid = sid[5:]; tag = re.sub(r"[^a-z0-9_]", "_", kid.lower())
        sh("logcat -c")
        b381_home()
        try:
            result_json("group", f"linkopen {sid}")
        except Exception as e:
            print("linkopen error", sid, e, flush=True)
        time.sleep(3)
        if p.get("how", "") == "在 Mines 内登录":
            tap_text("在 Mines 内登录", wait=10)
        r = shot387("plat-" + tag); t = " ".join(texts(r)) if r is not None else ""
        lc = sh("logcat -d -b crash | tail -20", timeout=20)
        W, H = (SCREEN[0], SCREEN[1]) if SCREEN and SCREEN[0] else (1080, 2362)
        try:
            from PIL import Image
            im = Image.open(f"{OUT}/b387-plat-{tag}.png").convert("L").crop((0, int(H * .25), W, int(H * .9)))
            ext = im.getextrema(); flat = (ext[1] - ext[0]) < 12
        except Exception:
            flat = None
        if flat:   # [v11d] a heavy page (抖音 video) can be mid-paint: look again before calling it white
            time.sleep(6); r = shot387("plat-" + tag); t = " ".join(texts(r)) if r is not None else ""
            try:
                im = Image.open(f"{OUT}/b387-plat-{tag}.png").convert("L").crop((0, int(H * .25), W, int(H * .9)))
                ext = im.getextrema(); flat = (ext[1] - ext[0]) < 12
            except Exception:
                pass
            if flat and r is not None and any(k in " ".join(texts(r)) for k in ("加载中", "还在加载", "没显示出来")): flat = False   # a hint is shown
        name = p.get("name", kid)
        opened = alive() and not flat and bool(t.strip()) and ("账号连接" not in t or name.split(" ")[0] in t)
        hint = next((x for x in texts(r) or [] if any(k in x for k in ("连不上", "打不开", "超时", "失败", "代理", "人机", "验证", "不允许", "无法"))), "") if r is not None else ""
        row = {"platform": sid, "name": name, "category": p.get("category"), "how": p.get("how"), "auto": p.get("auto"),
               "external": p.get("external"), "alive": alive(), "crash": "FATAL" in lc, "white": flat, "opened": opened,
               "state": p.get("state"), "reason": hint[:80] or (p.get("step") or ""), "shot": f"b387-plat-{tag}.png", "page": t[:120]}
        rows.append(row)
        if not row["alive"] or row["crash"]: selftest_fail.append(f"b387 platform {sid}: app crashed opening it")
        if flat: selftest_fail.append(f"b387 platform {sid}: white page without a hint")
        if not row["alive"]: relaunch()
        back(); time.sleep(0.8)
    r7["platforms"] = rows
    print("B387 PLATFORMS " + json.dumps(rows, ensure_ascii=False), flush=True)



def b387_coze(res):
    """[38.7 v8] the 扣子 Coze row of the platform table, step by step. Real coze.cn sign-in page in Mines' WebView is
    opened for real; the phone + SMS sign-in and the KeyAgent pat_ capture need a real account → 未实测; listing /
    publishing bots and the group reply run against the runner's fake Coze API (same code path, fake server)."""
    r7 = R7(res); row = {"platform": "扣子 Coze"}
    result_json("group", "connfake coze|off")
    try:
        c = coze_382()
    except Exception as e:
        c = {"err": str(e)}
    row["webview_login_page"] = ("通过（真站点）：内置 WebView 显示了扣子登录页「%s」" % c.get("login_text")) if c.get("login_text") and not c.get("chrome") \
        else f"失败：{c}"
    row["phone_sms_login"] = "未实测：模拟器上没有真实手机号 / 短信，卡在扣子（火山引擎）登录页的手机号 + 验证码这一步"
    row["keyagent_pat"] = "未实测：KeyAgent 取 pat_ 密钥要先真登录扣子，登录这一步没法在 CI 完成，所以没走到「创建个人访问令牌」"
    B = "http://10.0.2.2:8088"
    result_json("group", f"vaultkey coze|pat_smoke_0123456789abcdef0123|{B}/v3")
    cs = result_json("group", "cozesync", timeout=60) or {}
    bots = cs.get("bots", []) if isinstance(cs, dict) else []
    pub = (cs.get("published", []) if isinstance(cs, dict) else []) + fake_stats().get("coze_published", [])
    row["list_bots"] = f"通过（假扣子接口）：列出 {len(bots)} 个 bot" if len(bots) == 2 else f"失败：{cs}"
    row["publish_bot"] = "通过（假扣子接口）：未发布的 bot2 被自动发布" if "bot2" in pub else f"失败：bot2 没被发布 {pub}"
    gid = result_json("group", "make 扣子冒烟群|coze:bot1")
    r = result_json("group", f"send {gid}|你好，回一句", timeout=120) or []
    x = next((y for y in r if isinstance(y, dict) and y.get("who") == "coze:bot1"), {}) if isinstance(r, list) else {}
    row["group_reply"] = ("通过（假扣子接口）：群里 bot1 回了「%s」" % x.get("text", "")[:40]) if x.get("status") == "ok" and x.get("text") \
        else f"失败：{x or r}"
    row["real_group_reply"] = "未实测：真实扣子 bot 回话需要真实 pat_ 密钥（卡在上面的手机号登录）"
    for k in ("webview_login_page", "list_bots", "publish_bot", "group_reply"):
        if not row[k].startswith("通过"): selftest_fail.append(f"b387 coze {k}: {row[k][:160]}")
    r7["coze"] = row
    print("B387 COZE " + json.dumps(row, ensure_ascii=False), flush=True)


def main():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    if m: SCREEN[0], SCREEN[1] = int(m.group(1)), int(m.group(2))
    print("screen", SCREEN, flush=True)
    # [38.9.11.3] the fake model server lives across both vps_smoke.py passes (perf old, then new): zero its counters
    try: print("fake stats reset:", subprocess.run("curl -s http://127.0.0.1:8088/stats/reset", shell=True, capture_output=True, text=True, timeout=10).stdout, flush=True)
    except Exception as ex: print("fake stats reset failed:", ex, flush=True)
    sh("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
    adb("logcat", "-c")
    lc = subprocess.Popen(f"adb logcat -v threadtime > {OUT}/logcat.txt", shell=True)
    if I396 or I397: wait_gms_stable()   # [i17]
    print(sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity", timeout=90), flush=True)
    time.sleep(12)
    snap("launch")
    if not check("cold launch (fresh install)"):
        dump_fatal()
        time.sleep(3); print(sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity", timeout=90)); time.sleep(12)
        snap("launch-2"); check("second launch"); dump_fatal()
    dismiss_dialogs()
    root = snap("home")
    check("launch")
    if find(root, "Welcome to Mines"):
        setup_provider()
        sh(f"am force-stop {PKG}"); time.sleep(2)
        relaunch(); snap("relaunch-with-home"); check("cold launch with chats (home list)")
        dump_fatal()
        user_like_state()
    if I39113 and "skyonly" not in open("hark-build/smoke/target").read().splitlines()[1] and "diagsoak" not in open("hark-build/smoke/target").read().splitlines()[1]:   # [38.9.11.3] speed before / after (adb only; also runs on 38.9.11.2 with MINES_PERF_ONLY=1)
        try: perf_39113(os.environ.get("MINES_PERF_TAG", "new"))
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"perf error {e}")
        if PERF_ONLY:
            if os.environ.get("MINES_PERF_TAG") == "old":   # [38.9.11.3 h] the same sky strips on 38.9.11.2 (default settings, shader path)
                Rold = {}; sc0 = sh("settings get global animator_duration_scale").strip(); Rold["animator_scale_was"] = sc0
                sh("settings put global animator_duration_scale 1")
                for mode, sfx in ((1, "light"), (2, "dark")):
                    try:
                        theme388(mode); healthy_home(); result_json("group", "i396 skygpu on", timeout=40)
                        sky_frames_39113("old", "default", sfx, "gpu", Rold, keep=True)
                    except Exception as e: print("sky old error", e, flush=True)
                sh(f"settings put global animator_duration_scale {sc0 if sc0 not in ('', 'null') else 1}")
                print("I39113 SKYOLD " + json.dumps(Rold, ensure_ascii=False), flush=True)
            return finish(lc)
    if I396 or I397:   # [i17] onboarding may have been killed by the GMS death: make sure the app is up with a model
        if not alive(): recover("onboarding")
        seed_model()

    if FAST:   # [38.7 v11d] user-approved targeted gate: linking, white pages, persistence, chosen platform rows
        res = {}
        try:
            result_json("group", "wallpaper off"); anim(True)
        except Exception as e:
            print("fast prep error", e, flush=True)
        if UI388: fns = (b388_ui, b389_ui, b3891_fb, b3891_ui) if UI3891 else (b388_ui, b389_ui) if UI389 else (b388_ui,)
        elif FIX3891: fns = ((b3892_msg,) if MSG3892 else ()) + (b3891_fix,)   # [38.9.1 e] states smoke d missed; [38.9.2 f] 消息 pull first
        if ACCT3893 and not UI388: fns = (b3893_acct, b3893_hostkey) + tuple(fns)   # [38.9.3 g] merged 账号 page + host key first
        elif UI3891: fns = (b3891_fb, b3891_ui)   # [38.9.1 d] feedback round first, then the 38.9.1 pages
        elif HUB: fns = ()
        elif B388: fns = (b387_linkall, b387_noblack, b388_weblogin, b388_mailbox, b388_presets)
        else: fns = (b387_linkall, b387_noblack, b387_persist, b387_platforms)
        if HUB: fns = tuple(fns) + (b3891_hub,)   # [38.9.1 c] hub runs after the UI passes
        if I395: fns = (b395_i2, b395_i) if I395B else (b395_i,)   # [38.9.5 i] build-i gate only: 一键关联 / 一键重新登录, gateway, new screens light + dark
        if I39113: fns = ((b3913,) if I3913 else ()) + ((b39121,) if I39121 else ()) + ((b3912,) if I3912 else ()) + (b39113,)
        elif I39112: fns = (b39112,)
        if I3914: fns = (b3914,)
        elif I39111: fns = (b39111,)
        elif I3911: fns = (b3911,)
        elif I3910: fns = (b3910,)
        elif I399: fns = (b399,)
        elif I398B: fns = (b398b,)
        elif I398: fns = (b398,)
        elif I397: fns = (b397,)   # [38.9.7] its own short gate (1080x2412 / 480 dpi, custom avatar)
        elif I396: fns = ((b396x,) if I396X else ()) + ((b396m,) if I396M else ()) + (b396,) + (tuple(fns) if I395 else ())   # [38.9.6 i12] glass / auto progress / archive shots first, then the full i395 pass + 自检
        elif H3894: fns = (b3894_h,) + tuple(fns)   # [38.9.4 h] SkillHub market, Memoh, a11y wording, 账号 entries, glass / opaque reading, g2 defects first
        for fn in fns:
            try:
                fn(res)
            except Exception as e:
                import traceback; traceback.print_exc()
                print(f"{fn.__name__} error:", e, flush=True); selftest_fail.append(f"{fn.__name__} error {e}")
        print("B387 RESULT " + json.dumps(res.get("b387", {}), ensure_ascii=False), flush=True)
        if B388: print("B388 RESULT " + json.dumps(res.get("b388", {}), ensure_ascii=False), flush=True)
        return finish(lc)
    if B385:
        try:
            b385()
        except Exception as e:
            print("b385 error:", e, flush=True); selftest_fail.append(f"b385 error {e}")
        return finish(lc)
    if B381:
        try:
            b381()
        except Exception as e:
            print("b381 error:", e, flush=True); selftest_fail.append(f"b381 error {e}")
        return finish(lc)
    if B36:
        try:
            b36()
        except Exception as e:
            print("b36 error:", e, flush=True); selftest_fail.append(f"b36 error {e}")
        return finish(lc)
    if B353:
        try:
            b353()
        except Exception as e:
            print("b353 error:", e, flush=True); selftest_fail.append(f"b353 error {e}")
        return finish(lc)
    if VOICE352:
        try:
            voice_352()
        except Exception as e:
            print("voice352 error:", e, flush=True); selftest_fail.append(f"voice352 error {e}")
        return finish(lc)
    if QUICK:
        try:
            quick_home_351()
        except Exception as e:
            print("quick error:", e, flush=True); selftest_fail.append(f"quick error {e}")
        return finish(lc)
    # Home Today card → 工作位置
    if not tap_work(wait=6):
        # Today card may be collapsed: tap the greeting card to expand
        tap_text("好", wait=2)
        tap_work(wait=6)
    snap("work-location")
    if not check("open 工作位置 from home"):
        relaunch(); tap_work(wait=6)

    # Reachable server (runner sshd), password → key install
    add_server("smoke", SSH_HOST, SSH_PASS)
    time.sleep(20)
    snap("after-add-reachable")
    if not check("add reachable server + authorize"):
        relaunch(); tap_work(wait=6)

    # VPS mode
    tap_text("重活交给服务器", wait=15)
    snap("vps-mode")
    if not check("switch to 服务器 mode"):
        relaunch(); tap_work(wait=6)

    # Server detail (stats over JSch)
    tap_text("smoke", exact=True, wait=20)
    snap("server-detail")
    if not check("open server detail"):
        relaunch(); tap_work(wait=6)
    else:
        time.sleep(10); snap("server-detail-later"); check("server detail after 30 s")
        sh("input keyevent KEYCODE_BACK"); time.sleep(3)

    # Unreachable server (blackhole) + a refused port
    add_server("deadbox", "10.255.255.1", "x")
    time.sleep(25)
    snap("after-add-unreachable")
    check("add unreachable server")
    tap_text("立即检测", wait=25)
    snap("recheck")
    check("立即检测 with unreachable server")

    tap_text("deadbox", exact=True, wait=20)
    snap("dead-detail")
    check("open unreachable server detail")
    sh("input keyevent KEYCODE_BACK"); time.sleep(2)

    # Back home, server chip
    sh("input keyevent KEYCODE_BACK"); time.sleep(3)
    snap("home-again")
    # build-29+: compact pills, the label lives in the content-description "服务器：1/2"
    tap_text("服务器：", wait=8) or tap_text("服务器", exact=True, wait=8)
    snap("server-chip")
    check("open 服务器 chip")
    sh("input keyevent KEYCODE_BACK"); time.sleep(2)

    try:
        build31_pages()
    except Exception as e:
        print("build31_pages error:", e, flush=True); page_missing.append(f"error {e}")
    try:
        build32_apps()
    except Exception as e:
        print("build32_apps error:", e, flush=True); page_missing.append(f"apps error {e}")
    try:
        build32_visual()
    except Exception as e:
        print("build32_visual error:", e, flush=True); page_missing.append(f"visual error {e}")
    try:
        build33_sources()
    except Exception as e:
        print("build33_sources error:", e, flush=True); selftest_fail.append(f"b33 error {e}")
    try:
        build34()
    except Exception as e:
        print("build34 error:", e, flush=True); selftest_fail.append(f"b34 error {e}")
    try:
        build35()
    except Exception as e:
        print("build35 error:", e, flush=True); selftest_fail.append(f"b35 error {e}")

    # Cold start with VPS mode + servers saved (the user's state)
    sh(f"am force-stop {PKG}"); time.sleep(2)
    relaunch()
    snap("cold-start-vps")
    check("cold start in VPS mode")
    tap_work(wait=10)
    snap("work-location-cold")
    check("open 工作位置 after cold start")
    time.sleep(30)
    snap("work-location-idle")
    check("idle 30 s on 工作位置")
    # 测试连接 (build-28+); harmless when absent
    if tap_text("测试连接", wait=30):
        snap("test-connection"); check("测试连接")
    # 上次崩溃 page exists (build-28+)
    time.sleep(2)
    try:
        selftests()
    except Exception as e:
        print("selftests error:", e, flush=True); selftest_fail.append(f"selftests error {e}")
    finish(lc)


# ======================= [38.8] targeted screens =======================
def R8(res):
    return res.setdefault("b388", {})


def shot388(name):
    r = snap("b388-" + name)
    subprocess.run(f"cp {OUT}/{step_no:02d}-b388-{name}.png {OUT}/b388-{name}.png", shell=True)
    return r


def _txt(r):
    return " ".join(texts(r)) if r is not None else ""


def _orange_share(path, top, bottom):
    """share of strongly terracotta/orange pixels in a horizontal band (38.7 bottom bar ~ solid orange)"""
    try:
        from PIL import Image
        im = Image.open(path).convert("RGB"); W, H = im.size
        band = im.crop((0, int(H * top), W, int(H * bottom))).resize((108, 30)); px = list(band.getdata())
        o = sum(1 for (r, g, b) in px if r > 170 and 60 < g < 150 and b < 110 and r - b > 90)
        return round(o / len(px), 3)
    except Exception as e:
        return f"err {e}"


def theme388(mode):
    result_json("group", f"thememode {mode}"); adb("shell", "am", "force-stop", PKG); time.sleep(1)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(8)


def b388_weblogin(res):
    """WebLoginScreen in frosted glass: compact pill (no full-width orange bar), one glass hint strip, no brown bands."""
    r8 = R8(res); rows = []
    info = result_json("group", "linkinfo", timeout=60) or []
    for kid0 in ("x", "weibo", "qq", "weibo@night", "qq@night"):
        kid = kid0.split("@")[0]
        if kid0 == "x": theme388(1)
        if kid0 == "weibo@night": theme388(2)
        p = next((x for x in info if x.get("id") == "conn:" + kid), None)
        sh("logcat -c"); b381_home()
        try: result_json("group", f"linkopen conn:{kid}")
        except Exception as e: print("linkopen error", kid, e, flush=True)
        time.sleep(3)
        if p and p.get("how", "") == "在 Mines 内登录": tap_text("在 Mines 内登录", wait=10)
        else: time.sleep(6)
        tag = kid0.replace("@", "-")
        r = shot388("weblogin-" + tag); t = _txt(r)
        pth = f"{OUT}/b388-weblogin-{tag}.png"
        row = {"kid": kid0, "alive": alive(), "text": t[:200], "has_done": "我已登录好了" in t or "登录好了" in t,
               "has_strip": "外部打开" in t, "bottom_orange": _orange_share(pth, 0.90, 0.965), "top_orange": _orange_share(pth, 0.08, 0.16),
               "flat": _flat(pth)}
        rows.append(row)
        if not row["alive"]: selftest_fail.append(f"b388 weblogin {kid}: app died"); relaunch()
        if isinstance(row["bottom_orange"], float) and row["bottom_orange"] > 0.45:
            selftest_fail.append(f"b388 weblogin {kid}: bottom bar still mostly solid orange ({row['bottom_orange']})")
        if not row["has_done"]: page_missing.append(f"b388 weblogin {kid}: 我已登录好了 not found")
        back(); time.sleep(1)
    theme388(1)
    # the 群聊 sign-in page (元宝) with its glass note
    result_json("group", "base web:yuanbao|"); result_json("group", "weblogin web:yuanbao|")
    route("plus_group_weblogin/web%3Ayuanbao", 14)
    r = shot388("weblogin-yuanbao"); rows.append({"kid": "web:yuanbao", "alive": alive(), "text": _txt(r)[:200]})
    back(); time.sleep(1)
    r8["weblogin"] = rows
    print("B388 weblogin", json.dumps(rows, ensure_ascii=False)[:1500], flush=True)


def b388_mailbox(res):
    """消息 → 邮箱: injected multi-account inbox (screens), then the REAL add/read path against the runner's fake IMAP."""
    r8 = R8(res); o = {}
    theme388(1)
    o["fake"] = result_json("mailbox", "fake")
    result_json("mailbox", "open"); time.sleep(5)
    r = shot388("mail-all"); t = _txt(r); o["all_text"] = t[:400]
    for want in ("邮箱", "全部", "GitHub", "今天"):
        if want not in t: selftest_fail.append(f"b388 mailbox: '{want}' missing on the inbox")
    if "授权码不对" not in t: page_missing.append("b388 mailbox: broken-account strip not shown")
    if tap_text("demo.one@163", wait=2.5):
        shot388("mail-one")
        tap_text("demo.one@163", wait=1.5)
    if tap_text("Run succeeded", wait=3):
        r = shot388("mail-detail"); o["detail"] = _txt(r)[:300]
        if "复制正文" not in o["detail"]: page_missing.append("b388 mailbox: detail sheet buttons missing")
        back()
    # long-press the first account chip → manage sheet
    xy = find(dump(), "alex.demo")
    if xy:
        sh(f"input swipe {xy[0]} {xy[1]} {xy[0]} {xy[1]} 900"); time.sleep(2); shot388("mail-manage"); back()
    # dark
    theme388(2)
    result_json("mailbox", "fake"); result_json("mailbox", "open"); time.sleep(5)
    shot388("mail-dark")
    if tap_text("Run succeeded", wait=3): shot388("mail-detail-dark"); back()
    theme388(1)
    # empty state + add sheet
    result_json("mailbox", "off"); result_json("mailbox", "open"); time.sleep(4)
    r = shot388("mail-empty"); o["empty"] = _txt(r)[:300]
    if "把你的邮箱都放到这里" not in o["empty"]: page_missing.append("b388 mailbox: empty state missing")
    if tap_text("添加 163 / QQ 邮箱", wait=3) or tap_text("添加邮箱", wait=3):
        r = shot388("mail-add"); o["add"] = _txt(r)[:300]
        if "验证并添加" not in o["add"]: selftest_fail.append("b388 mailbox: add sheet missing 验证并添加")
        back()
    back()
    # REAL path (same code as the phone, plain TCP to the runner's fake IMAP instead of TLS :993)
    result_json("mailbox", "plain 10.0.2.2:1143")
    bad = result_json("mailbox", "add box1@smoke.test|badcode", timeout=60) or {}
    o["bad"] = bad
    if not (isinstance(bad, dict) and bad.get("error")): selftest_fail.append(f"b388 mailbox: wrong 授权码 was accepted {bad}")
    if isinstance(bad, dict) and "box1@smoke.test" in (bad.get("accounts") or []): selftest_fail.append("b388 mailbox: wrong 授权码 saved the account")
    good = result_json("mailbox", "add box1@smoke.test|goodcode", timeout=60) or {}
    o["good"] = good
    if not (isinstance(good, dict) and good.get("error") == ""): selftest_fail.append(f"b388 mailbox: right 授权码 refused {good}")
    st = result_json("mailbox", "refresh", timeout=90) or {}
    o["read"] = st
    if not (isinstance(st, dict) and st.get("mails", 0) >= 3): selftest_fail.append(f"b388 mailbox: real IMAP read got {st}")
    result_json("mailbox", "open"); time.sleep(5)
    r = shot388("mail-real"); o["real_text"] = _txt(r)[:300]
    if "IMAP 服务已开启" not in o["real_text"] and "Run succeeded" not in o["real_text"]: page_missing.append("b388 mailbox: real mail not on screen")
    back()
    r8["mailbox"] = o
    print("B388 mailbox", json.dumps(o, ensure_ascii=False)[:2500], flush=True)


def b388_presets(res):
    """free login routes: preset list, Muse 中继 / Memoh sign-in dialogs, AI 伙伴 「更多免费接入」, Mines × Muse 群."""
    r8 = R8(res); o = {}
    o["list"] = result_json("group", "preset list", timeout=40)
    for pid in ("nanomuse", "memoh"):
        result_json("group", f"preset openlogin {pid}"); time.sleep(6)
        r = shot388("preset-" + pid); o[pid] = _txt(r)[:300]
        want = "登录 Muse 中继" if pid == "nanomuse" else "登录 Memoh"
        if want not in o[pid]: page_missing.append(f"b388 preset {pid}: '{want}' dialog not seen")
        back(); time.sleep(1); back(); time.sleep(1)
    route("plus_group_partners", 6)
    r = shot388("partners-top"); o["partners_top"] = _txt(r)[:400]
    t = ""
    for _ in range(14):
        scroll_down(); r = dump(); t = _txt(r)
        if "建一个 Mines × Muse 群" in t: break
    r = shot388("partners-extras"); o["extras"] = _txt(r)[:400]
    if "建一个 Mines × Muse 群" not in o["extras"]: selftest_fail.append("b388 presets: 「建一个 Mines × Muse 群」 row missing")
    if "扣子" not in o["partners_top"] + o["extras"] + t: page_missing.append("b388 presets: 扣子 row not seen")
    if tap_text("建一个 Mines × Muse 群", wait=8):
        r = shot388("muse-group"); o["group"] = _txt(r)[:300]
        if not alive(): selftest_fail.append("b388 presets: app died opening the Mines × Muse group")
        back()
    o["group_json"] = result_json("group", "preset group", timeout=40)
    back()
    r8["presets"] = o
    print("B388 presets", json.dumps(o, ensure_ascii=False)[:2500], flush=True)



def _strip_diff(a, b):
    """mean abs difference of the left 28 px margin strip (fog only, no cards) between two screenshots"""
    try:
        from PIL import Image, ImageChops
        A = Image.open(a).convert("L"); B_ = Image.open(b).convert("L"); W, H = A.size
        box = (0, int(H * 0.12), 28, int(H * 0.80))
        d = ImageChops.difference(A.crop(box), B_.crop(box)); h = d.histogram(); n = sum(h)
        return round(sum(i * v for i, v in enumerate(h)) / max(1, n), 2)
    except Exception as e:
        return f"err {e}"


def _bounds_of(r, pred):
    for tx, d, x, y, n in nodes(r):
        if pred(tx or "", d or ""): return (x, y)
    return None


def b388_ui(res):
    """[38.8 v4] build-36 sheet restyle: home light/dark (+ fog moving), search in place with the keyboard,
    消息 with a resolved card and no spinner, 知识库 mind map card, KeyAgent compact strip + pills."""
    r8 = R8(res); o = {}
    result_json("group", "thememode 1"); adb("shell", "am", "force-stop", PKG); time.sleep(1)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(8)
    print("UI388 inject", selftest_json("feedinject", "resolved", timeout=30), flush=True)
    r = b381_home(); time.sleep(3)
    r = shot388("home-light")
    t = _txt(r); o["home_text"] = t[:600]
    for want in ("最近对话", "都在正常跑"):
        if want not in t: page_missing.append(f"ui388 home: '{want}' not seen")
    for gone in ("电量 ", "存储 ", "运行 "):
        if any(x.startswith(gone.strip()) and len(x) <= 3 for x in texts(r) or []): selftest_fail.append(f"ui388 home: status tile '{gone}' still shown")
    if not any(("好，" in x) or x.startswith(("早上好", "上午好", "中午好", "下午好", "晚上好", "夜深了")) for x in texts(r) or []):
        page_missing.append("ui388 home: greeting not seen")
    face0 = _bounds_of(r, lambda tx, d: d.startswith("换角色"))
    time.sleep(5)
    shot388("home-light-b")
    o["fog_diff_home"] = _strip_diff(f"{OUT}/b388-home-light.png", f"{OUT}/b388-home-light-b.png")
    # search in place with the keyboard up
    if tap_text("搜索对话", wait=2.5):
        time.sleep(2); type_text("Mines"); time.sleep(2.5)
        r = shot388("home-search-ime")
        ime = "mInputShown=true" in sh("dumpsys input_method | grep -m1 mInputShown")
        face1 = _bounds_of(r, lambda tx, d: d.startswith("换角色"))
        o["ime"] = ime; o["face_before"] = face0; o["face_ime"] = face1
        if face0 and face1 and abs(face0[1] - face1[1]) > 6: selftest_fail.append(f"ui388 search: header moved with the keyboard {face0}→{face1}")
        if "取消" not in _txt(r): page_missing.append("ui388 search: in-place field (取消) not seen")
        tap_text("取消", exact=True, wait=2)
        sh("input keyevent KEYCODE_BACK"); time.sleep(1)
    else:
        page_missing.append("ui388 search button not found")
    # dark
    result_json("group", "thememode 2"); adb("shell", "am", "force-stop", PKG); time.sleep(1)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(8)
    print("UI388 inject2", selftest_json("feedinject", "resolved", timeout=30), flush=True)
    b381_home(); time.sleep(3); shot388("home-dark"); time.sleep(5); shot388("home-dark-b")
    o["fog_diff_dark"] = _strip_diff(f"{OUT}/b388-home-dark.png", f"{OUT}/b388-home-dark-b.png")
    # 消息 (light)
    result_json("group", "thememode 1"); adb("shell", "am", "force-stop", PKG); time.sleep(1)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(8)
    print("UI388 inject3", selftest_json("feedinject", "resolved", timeout=30), flush=True)
    b381_home(); time.sleep(2)
    if not tap_text("消息", exact=True, wait=1.2): tap_text("消息，", wait=1.2)
    shot388("msg-open")
    time.sleep(4)
    r = shot388("msg")
    t = _txt(r); o["msg_text"] = t[:800]
    if "权限问题已解决" in t and any("已失效" in x for x in (texts(r) or [])[:0]): pass
    o["msg_progress_nodes"] = sh("uiautomator dump /sdcard/pg.xml >/dev/null 2>&1; cat /sdcard/pg.xml | grep -o 'ProgressBar' | head -3")
    if open_tab("账号异常"):
        time.sleep(2); r = shot388("msg-account"); o["msg_account"] = _txt(r)[:600]
        # the resolved card lives on the 账号异常 tab (为你推荐 does not list it)
        if "已解决" not in o["msg_account"]: selftest_fail.append("ui388 消息: 已解决 card not shown")
        if "收起" not in o["msg_account"]: page_missing.append("ui388 消息: 收起 not seen")
    else:
        page_missing.append("ui388 消息: 账号异常 tab not found")
    back(1)
    # 知识库
    B = "http://10.0.2.2:8088"
    result_json("group", "kbep "); result_json("group", f"base web:doubao|{B}/webchat")
    result_json("group", "kbseed "); result_json("group", "kbrun", timeout=120)
    route("plus_knowledge", 7)
    r = shot388("kb-map")
    topics = [d for d in all_desc(r, "脑图节点：") if " · " in d[2]]
    o["kb_topics"] = [d[2] for d in topics]
    if not topics: selftest_fail.append("ui388 kb: no topic nodes")
    time.sleep(4); shot388("kb-map-b")
    o["fog_diff_kb"] = _strip_diff(f"{OUT}/b388-kb-map.png", f"{OUT}/b388-kb-map-b.png")
    if topics:
        tp = next((d for d in topics if "交易" in d[2]), topics[0])
        sh(f"input tap {tp[0]} {tp[1]}"); time.sleep(2.5); shot388("kb-branch")
    result_json("group", "thememode 2"); back(2)
    route("plus_knowledge", 7); shot388("kb-map-dark")
    result_json("group", "thememode 1"); back(2)
    # KeyAgent page (DeepSeek)
    sh(f"am start -n {PKG}/com.openminis.app.MainActivity --es mines_route plus_connections --es mines_open_kind deepseek", timeout=60)
    time.sleep(6)
    if tap_text("一键接入", wait=3) or tap_text("再生成一个新密钥", wait=3) or (scroll_down() or tap_text("一键接入", wait=3)):
        time.sleep(14)
        r = shot388("keyagent")
        t = texts(r) or []
        o["keyagent"] = t[:20]
        if not any("重试" in x for x in t) or not any("浏览器打开" in x for x in t): selftest_fail.append("ui388 keyagent: no 重试 / 浏览器打开")
        for _ in range(3): sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
    else:
        page_missing.append("ui388 keyagent: 一键接入 not found")
    if not alive(): relaunch()
    # [38.8 v5d] home search with NO hits stays in place (face + 取消 + no-results line), typed text not reordered
    try:
        b381_home(); time.sleep(2)
        if tap_text("搜索对话", wait=2.5):
            time.sleep(2); type_text("zqxjk"); time.sleep(2.5)
            r = shot388("home-search-nohit")
            t = texts(r) or []
            o["search_nohit_text"] = t[:12]
            o["search_nohit_face"] = _bounds_of(r, lambda tx, d: d.startswith("换角色"))
            if "取消" not in t: selftest_fail.append("ui388 search no-hit: 取消 / in-place field gone")
            if not any("zqxjk" in x for x in t): selftest_fail.append(f"ui388 search no-hit: typed text wrong {t[:6]}")
            tap_text("取消", exact=True, wait=2); sh("input keyevent KEYCODE_BACK"); time.sleep(1)
    except Exception as e:
        print("search nohit error", e, flush=True)
    # [38.8 v5d] home weather ambience: forced sunny / rainy (light theme), two frames each to see it move
    try:
        for tag, inj in (("sun", {"hour": 11, "weather": "clear", "weekday": 1}), ("rain", {"hour": 11, "weather": "rain", "weekday": 1})):
            result_json("group", "greet " + json.dumps(inj).replace(" ", ""), timeout=20)
            b381_home(); time.sleep(3); shot388(f"home-{tag}"); time.sleep(1.2); shot388(f"home-{tag}-b")
            o[f"amb_diff_{tag}"] = _strip_diff(f"{OUT}/b388-home-{tag}.png", f"{OUT}/b388-home-{tag}-b.png")
        result_json("group", "greet off", timeout=20)
    except Exception as e:
        print("ambience shots error", e, flush=True)
    # [38.8 v5d] tiered screen reading: tools register, the tree read returns elements, replay path saved + replayed
    try:
        selftest("allow", "a11y_cli")
        comp = f"{PKG}/com.openminis.app.accessibility.MinisAccessibilityService"
        sh(f"settings put secure enabled_accessibility_services {comp}"); sh("settings put secure accessibility_enabled 1"); time.sleep(4)
        b381_home(); time.sleep(2)
        el = selftest("offload", "android-a11y-cli ui elements", timeout=60)
        m_ = re.search(r'"tier":"([^"]+)"', el); c_ = re.search(r'"count":(\d+)', el)
        o["tiers_elements"] = {"tier": m_.group(1) if m_ else None, "count": int(c_.group(1)) if c_ else 0}
        if not m_ or not c_ or int(c_.group(1)) <= 0: selftest_fail.append(f"tiers: ui elements returned no elements ({el.strip()[:200]})")
        ls = selftest("offload", "android-a11y-cli list", timeout=60)
        o["tiers_list_head"] = ls[ls.find("屏幕"):][:160]
        if "读屏" not in ls: selftest_fail.append("tiers: list does not name its reading tier")
        d1 = selftest("offload", 'android-a11y-cli do --task smoke-tier "wait 100"', timeout=60)
        d2 = selftest("offload", 'android-a11y-cli do --task smoke-tier "wait 100"', timeout=60)
        o["tiers_saved"] = "已记下" in d1; o["tiers_replayed"] = "1·回放" in d2
        if not o["tiers_saved"] or not o["tiers_replayed"]: selftest_fail.append(f"tiers: replay path not saved/replayed ({d1.strip()[:120]} | {d2.strip()[:120]})")
        pa = selftest("offload", "android-a11y-cli paths", timeout=30); o["tiers_paths"] = pa.strip()[:200]
        sh("settings put secure enabled_accessibility_services ''")
    except Exception as e:
        selftest_fail.append(f"tiers smoke error {e}")
    for k in ("fog_diff_home", "fog_diff_dark"):
        v = o.get(k)
        if isinstance(v, (int, float)) and v < 0.4: page_missing.append(f"ui388 fog did not move ({k}={v})")
    r8["ui"] = o
    print("UI388 RESULT " + json.dumps(o, ensure_ascii=False)[:4000], flush=True)


def b389_ui(res):
    """[38.9] new pages: 账号关联 keychain list light+dark + detail, 应用代理 light+dark (+ 分工 scrolled), 外观调节, 经验,
    and the 知识库 星空 map with animations ON (two frames + a short screenrecord)."""
    r8 = R8(res); o = {}
    csv = ("name,url,username,password\\n"
           "GitHub,https://github.com/login,smoke@example.com,pw-smoke-1\\n"
           "Cloudflare,https://dash.cloudflare.com/login,smoke@example.com,pw-smoke-2\\n"
           "阿里云,https://account.aliyun.com/login,13800138000,pw-smoke-3\\n"
           "OKX,https://www.okx.com/account/login,smoke@example.com,pw-smoke-4\\n"
           "ModelScope,https://modelscope.cn/login,13800138000,pw-smoke-5\\n"
           "Google,https://accounts.google.com/,smoke@example.com,pw-smoke-6")
    o["seed"] = result_json("group", "savedlogins " + csv, timeout=30)
    def relaunch_theme(mode):
        result_json("group", f"thememode {mode}"); adb("shell", "am", "force-stop", PKG); time.sleep(1)
        adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(8)
    def page(rt, name, want, wait=6):
        try:
            route(rt, wait); r = shot388(name); t = _txt(r); o[name] = t[:500]
            if not alive(): crashed.append(f"b389 {name}"); relaunch(); return None
            for w in want:
                if w not in t: page_missing.append(f"ui389 {name}: '{w}' not seen")
            return r
        except Exception as e:
            page_missing.append(f"ui389 {name} error {e}"); return None
    relaunch_theme(1)
    time.sleep(6)   # favicons fetch in the background on first open
    r = page("plus_keychain", "kc-list", ("账号关联",), wait=9)
    time.sleep(4); r = shot388("kc-list"); o["kc-list"] = _txt(r)[:600]
    rows = [d for d in all_desc(r, "") if "，" in d[2] and any(k in d[2] for k in ("GitHub", "Cloudflare", "阿里云", "OKX", "ModelScope", "Google", "github", "aliyun"))]
    o["kc_rows"] = [d[2] for d in rows][:12]
    if not rows: page_missing.append("ui389 kc-list: no seeded account rows")
    else:
        sh(f"input tap {rows[0][0]} {rows[0][1]}"); time.sleep(4)
        r = shot388("kc-detail"); o["kc-detail"] = _txt(r)[:500]
        if not alive(): crashed.append("b389 kc-detail"); relaunch()
        back(1)
    # 「已记下 X 账号」 glass toast (mock 05)
    o["autoacct"] = result_json("group", "autoacct Hugging Face|huggingface.co|smoke@example.com", timeout=20)
    time.sleep(1.2); r = shot388("kc-toast"); o["kc-toast"] = _txt(r)[:300]
    if "已记下" not in o["kc-toast"]: page_missing.append("ui389 kc-toast: 已记下 not seen")
    page("plus_app_agents", "agents", ("应用代理",))
    scroll_down(); time.sleep(1.5); shot388("agents-split")
    page("plus_look", "look", ())
    result_json("group", "expadd howto|在服务器上跑长任务|用 minis-vps bg 起任务，再 minis-vps attach 看输出|别直接 ssh 跑，断线会中断", timeout=20)
    result_json("group", "expadd lesson|找服务器登录|在 设置 › 网络 › 电脑与服务器，不要去搜文件|", timeout=20)
    page("plus_experience", "exp", ("经验",))
    relaunch_theme(2)
    page("plus_keychain", "kc-list-dark", ("账号关联",), wait=9)
    page("plus_app_agents", "agents-dark", ("应用代理",))
    relaunch_theme(1)
    # 知识库 星空 with animations ON (the smoke otherwise runs with animator scale 0 → GalaxyMotion.still)
    try:
        anim(True)
        route("plus_knowledge", 8)
        shot388("kb-anim-1"); time.sleep(1.4); shot388("kb-anim-2")
        o["kb_anim_diff"] = _strip_diff(f"{OUT}/b388-kb-anim-1.png", f"{OUT}/b388-kb-anim-2.png")
        sh("screenrecord --time-limit 7 --size 540x1200 --bit-rate 2500000 /sdcard/kb.mp4", timeout=30)
        subprocess.run(f"adb pull /sdcard/kb.mp4 {OUT}/b389-kb-anim.mp4", shell=True, timeout=60)
        o["kb_mp4"] = os.path.getsize(f"{OUT}/b389-kb-anim.mp4") if os.path.exists(f"{OUT}/b389-kb-anim.mp4") else 0
        if isinstance(o["kb_anim_diff"], (int, float)) and o["kb_anim_diff"] < 0.3: page_missing.append(f"ui389 kb: frames identical with animations on ({o['kb_anim_diff']})")
        back(2)
    except Exception as e:
        page_missing.append(f"ui389 kb anim error {e}")
    finally:
        anim(False)
    r8["ui389"] = o
    print("UI389 RESULT " + json.dumps(o, ensure_ascii=False)[:4000], flush=True)

def _nonwhite(path):
    """share of pixels that are not near-white in the middle band (blank console pages are ~0)"""
    try:
        from PIL import Image
        im = Image.open(path).convert("RGB"); W, H = im.size
        px = list(im.crop((0, int(H * .18), W, int(H * .85))).resize((90, 160)).getdata())
        return round(sum(1 for (r, g, b) in px if min(r, g, b) < 225) / len(px), 3)
    except Exception as e:
        return f"err {e}"


def b3891_hub(res):
    """[38.9.1] 技能市场 = Hermes Skills Hub: real index sync (ETag), hub list + category + search shots, one GitHub install + one ClawHub install."""
    r8 = R8(res); o = {}
    theme388(1)
    o["sync"] = result_json("group", "skillshub sync", timeout=900)
    cnt = o["sync"].get("count", 0) if isinstance(o["sync"], dict) else 0
    if cnt < 50000: selftest_fail.append(f"hub: index has only {cnt} skills {o['sync']}")
    o["sync2"] = result_json("group", "skillshub sync", timeout=120)   # second call: no download (fresh / 304)
    o["pdf"] = result_json("group", "skillshub page pdf", timeout=60)
    o["cat_sec"] = result_json("group", "skillshub cat 数据安全", timeout=60)
    o["zh"] = result_json("group", "skillshub page 设计", timeout=60)
    result_json("group", "skillshub open", timeout=20); time.sleep(8)
    r = shot388("hub3891-list"); o["list"] = _txt(r)[:400]
    if "Skills Hub" not in o["list"]: page_missing.append("hub: list page missing 'Skills Hub'")
    if tap_text("数据安全", wait=4): shot388("hub3891-cat")
    tap_text("数据安全", wait=2)
    o["inst_gh"] = result_json("group", "skillshub install algorithmic-art", timeout=180)
    o["inst_claw"] = result_json("group", "skillshub install clawhub algorithmic-art", timeout=180)   # only the ClawHub row matches both words
    for k in ("inst_gh", "inst_claw"):
        v = o[k]
        if not (isinstance(v, dict) and v.get("hasSkillMd")): selftest_fail.append(f"hub: {k} did not install SKILL.md {v}")
    back(); result_json("group", "skillshub open", timeout=20); time.sleep(6)
    xy = find(dump(), "搜索技能")
    if xy:
        sh(f"input tap {xy[0]} {xy[1]}"); time.sleep(1); sh("input text algorithmic"); time.sleep(4); sh("input keyevent 111"); time.sleep(1)
        r = shot388("hub3891-installed"); o["installed"] = _txt(r)[:300]
        if "已安装" not in o["installed"]: page_missing.append("hub: 已安装 not shown for the installed skill")
    back(2)
    r8["hub"] = o
    print("HUB RESULT " + json.dumps(o, ensure_ascii=False)[:3000], flush=True)


def rawshot(name):
    """screencap only (no uiautomator dump: heavy WebView pages time it out)"""
    global step_no
    step_no += 1
    p = f"{OUT}/{step_no:02d}-b388-{name}.png"
    for _ in range(3):
        subprocess.run(f"adb exec-out screencap -p > {p}", shell=True, timeout=60)
        if os.path.exists(p) and os.path.getsize(p) > 1000: break
        time.sleep(1.5)
    subprocess.run(f"cp {p} {OUT}/b388-{name}.png", shell=True)
    print(f"[rawshot {name}] alive={alive()}", flush=True)


def b3891_fix(res):
    """[38.9.1 e] what smoke d missed: keychain with saved logins + identities, Google detail, 已记下 toast logos,
    model picker from the header, Hark 测试一下 (signed-in → real round trip → plain result), Grok delete → card gone,
    硅基流动 / OpenAI consoles, KeyAgent dialog with the soft keyboard up, 163 add with a cleared address field."""
    r8 = R8(res); o = {}; F = "http://10.0.2.2:8088"
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"fix3891 {name} error {e}")
        if not alive(): crashed.append(f"fix3891 {name}"); relaunch()
    theme388(1)
    def kc():
        csv = ("name,url,username,password\\n"
               "GitHub,https://github.com/login,smoke@example.com,pw-smoke-1\\n"
               "Cloudflare,https://dash.cloudflare.com/login,smoke@example.com,pw-smoke-2\\n"
               "阿里云,https://account.aliyun.com/login,13800138000,pw-smoke-3\\n"
               "OKX,https://www.okx.com/account/login,smoke@example.com,pw-smoke-4\\n"
               "ModelScope,https://modelscope.cn/login,13800138000,pw-smoke-5\\n"
               "Google,https://accounts.google.com/,smoke@example.com,pw-smoke-6")
        o["seed"] = result_json("group", "savedlogins " + csv, timeout=30)
        o["kcseed"] = result_json("group", "kcseed", timeout=30)
        route("plus_keychain", 10); time.sleep(4); r = shot388("fix-kc"); o["kc"] = _txt(r)[:500]
        rows = [d for d in all_desc(r, "") if "，" in d[2] and "Google" in d[2]]
        if rows:
            sh(f"input tap {rows[0][0]} {rows[0][1]}"); time.sleep(4); r = shot388("fix-kc-google"); o["kc_google"] = _txt(r)[:300]; back(1)
        else: page_missing.append("fix3891 kc: Google row not found")
        o["autoacct"] = result_json("group", "autoacct Hugging Face|huggingface.co|smoke@example.com", timeout=20)
        time.sleep(1.5); shot388("fix-kc-toast"); time.sleep(5); shot388("fix-kc-after-toast")
        back(1)
    step("kc", kc)
    def picker():
        b381_home(); time.sleep(1)
        if not tap_text("你好，我是测试模型。", wait=5): tap_text("GARBAGETEST", wait=5)
        if not tap_text("Default Models", wait=3): page_missing.append("fix3891 picker: Default Models row not found")
        r = shot388("fix-picker"); t = _txt(r); o["picker"] = t[:400]
        if "自动" not in t: page_missing.append(f"fix3891 picker: 自动（推荐） not seen {t[:160]}")
        scroll_down(); time.sleep(1); shot388("fix-picker-b"); back(2)
    step("picker", picker)
    def hark():
        result_json("group", "agents clear", timeout=20); result_json("group", "agents add web:hark", timeout=20)
        o["weblogin"] = result_json("group", "weblogin web:hark|1", timeout=30)
        route("plus_app_agents", 6); r = shot388("fix-hark-row"); o["hark_row"] = _txt(r)[:300]
        if tap_text("测试一下", wait=1.2):
            shot388("fix-hark-testing"); time.sleep(35); r = shot388("fix-hark-test"); o["hark_test"] = _txt(r)[:300]
        else: page_missing.append("fix3891 hark: 测试一下 not found after weblogin")
        back(1)
    step("hark", hark)
    def grok():
        result_json("group", "vaultkey xai|xai-smokeKEY000000000000000000000000000000000000000000000000000000000000", timeout=30)
        selftest_json("openkind", "xai", timeout=20); time.sleep(6); shot388("fix-grok-before")
        if tap_text("删除这个API Key", wait=2.5):
            tap_text("删除", exact=True, wait=3); time.sleep(2); r = shot388("fix-grok-after"); o["grok_after"] = _txt(r)[:300]
            if "没通过" in o["grok_after"]: selftest_fail.append("fix3891 grok: stale 「保存的…没通过」 card after delete")
        else: page_missing.append("fix3891 grok: 删除这个API Key not found")
        back(2)
    step("grok", grok)
    def ime():
        sh("settings put secure show_ime_with_hard_keyboard 1")
        result_json("group", f"keyagent groq|{F}/keydlg", timeout=20); time.sleep(10)
        if tap_text("Create API Key", wait=2.5):
            e = edits(dump())
            if e: sh(f"input tap {e[0][0]} {e[0][1]}"); time.sleep(2.5)
            shot388("fix-ka-ime")
        back(3); time.sleep(2)
    step("ime", ime)
    def consoles():
        nw = {}
        for k in ("siliconflow", "openai"):
            result_json("group", f"keyagent {k}|", timeout=20); time.sleep(22)
            rawshot(f"fix-con-{k}"); nw[k] = _nonwhite(f"{OUT}/b388-fix-con-{k}.png"); back(3); time.sleep(2)
        o["console_nonwhite"] = nw
        for k, v in nw.items():
            if isinstance(v, float) and v < 0.02: page_missing.append(f"fix3891 console {k}: looks blank ({v})")
    step("consoles", consoles)
    def mail():
        result_json("mailbox", "open"); time.sleep(5)
        if tap_text("添加 163 / QQ 邮箱", wait=3) or tap_text("添加邮箱", wait=3):
            e = edits(dump())
            if len(e) >= 2:
                sh(f"input tap {e[0][0]} {e[0][1]}"); time.sleep(.6); sh("input keyevent KEYCODE_MOVE_END")
                sh("input keyevent " + " ".join(["67"] * 45)); type_text("smoke.mines.test@163.com"); sh("input keyevent 111"); time.sleep(1)
                e2 = edits(dump()); t2 = e2[1] if len(e2) >= 2 else e[1]
                sh(f"input tap {t2[0]} {t2[1]}"); time.sleep(.8); type_text("ABCDEFGHIJKLMNOP"); sh("input keyevent 111")
                tap_text("验证并添加", wait=20)
                r = shot388("fix-mail-163"); o["mail163"] = _txt(r)[:300]
            back(2)
    step("mail", mail)
    r8["fix3891"] = o
    print("FIX3891 RESULT " + json.dumps(o, ensure_ascii=False)[:4000], flush=True)


def b3892_msg(res):
    """[38.9.2 f] 消息 pull-to-refresh (user: 「下拉不更新」): fake source brings one new card per read + a 163 box with a wrong
    授权码 on the runner's fake IMAP. Open 消息 (resume refresh = read 1) -> pull -> 'Smoke 新消息 #2' on top, line 新到 1 条 · 163 授权码失效,
    spinner ends; then leave the app > 30 s and come back -> read 3 without a pull."""
    r8 = R8(res); o = {}
    w, h = SCREEN
    def state():
        st = result_json("msgfeed", "state", timeout=30)
        return st if isinstance(st, dict) else {}
    def settle(limit=40):
        st = {}
        for _ in range(limit):
            st = state()
            if st and not st.get("busy"): return st
            time.sleep(1.5)
        return st
    theme388(1)
    o["seed"] = result_json("msgfeed", "seed", timeout=30)
    o["badbox"] = result_json("msgfeed", "badbox 10.0.2.2:1143", timeout=30)
    o["open"] = result_json("msgfeed", "open", timeout=30); time.sleep(4)
    st = settle(); o["before_state"] = st
    time.sleep(1.5)
    r = shot388("msg-before"); tb = _txt(r); o["before"] = tb[:500]
    # first load: nothing counts as new, so #1 sits in score order (may be below the fold) -> check the feed state
    if "st:msg1" not in (st.get("smoke") or []): selftest_fail.append(f"msg3892: open did not read the source (#1 missing) {st}")
    if "Smoke 新消息 #2" in tb: selftest_fail.append("msg3892: #2 already there before the pull")
    # pull down from under the tabs
    sh(f"input swipe {w//2} {int(h*0.30)} {w//2} {int(h*0.78)} 700")
    time.sleep(0.6); rawshot("msg-pulling")
    # the result line shows for 6 s: poll the (fast) feed state, then screenshot + dump at once (uiautomator dumps take seconds)
    t0 = time.time(); st = {}
    for _ in range(60):
        st = state()
        if "st:msg2" in (st.get("smoke") or []) and not st.get("busy"): break
        time.sleep(0.5)
    o["pull_secs"] = round(time.time() - t0, 1)
    r = shot388("msg-after"); ta = _txt(r)
    o["after"] = ta[:600]; o["after_state"] = st
    if "Smoke 新消息 #2" not in ta: selftest_fail.append(f"msg3892: pull did not bring the new card #2 {st}")
    if "新到" not in ta:
        # the line lives 10 s; the uiautomator dump finishes ~12 s after the screenshot (smoke g) -> timing, not an app failure
        page_missing.append("msg3892: 「新到 N 条」 not in the (slow) dump; check the msg-after screenshot")
    if "163 授权码失效" not in ta: selftest_fail.append("msg3892: failed 163 box not named in the result line")
    if (st.get("top") or [""])[0] != "st:msg2": selftest_fail.append(f"msg3892: new card not on top {st.get('top')}")
    if st.get("busy"): selftest_fail.append("msg3892: still busy after the pull")
    # spinner gone: the dump must not show the refresh indicator any more (it has no text; check the busy flag + screen line)
    # leave the app > 30 s and come back: ON_RESUME re-reads (read 3) without a pull
    sh("input keyevent KEYCODE_HOME"); time.sleep(33)
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(4)
    st = settle(); time.sleep(1.5)
    r = shot388("msg-resume"); tr = _txt(r); o["resume"] = tr[:500]; o["resume_state"] = st
    if "st:msg3" not in (st.get("smoke") or []): selftest_fail.append(f"msg3892: coming back did not refresh (no #3) {st}")
    # dark theme look of the line
    theme388(2)   # force-stops the app: seed again
    result_json("msgfeed", "seed", timeout=30); result_json("msgfeed", "badbox 10.0.2.2:1143", timeout=30)
    result_json("msgfeed", "open", timeout=30); time.sleep(3); settle()
    sh(f"input swipe {w//2} {int(h*0.30)} {w//2} {int(h*0.78)} 700")
    for _ in range(30):
        time.sleep(1.5)
        if "新到" in _txt(dump()): break
    shot388("msg-after-dark")
    theme388(1)
    o["off"] = result_json("msgfeed", "off", timeout=30)
    r8["msg3892"] = o
    print("MSG3892 RESULT " + json.dumps(o, ensure_ascii=False)[:4000], flush=True)



def b3894_h(res):
    """[38.9.4 h] SkillHub (skillhub.cn) market: list >= 10, search, install one verified small plugin (QingYunA/answer-me-with-html)
    + one skill (weather) → 已安装; Memoh hosted state after a fake app.memoh.net cookie sign-in (not 「还没登录」); 自检 无障碍
    wording (never 已开启 while the service is not running); ☰ 「账号」 entry and the 设置 账号 card; glass shots light + dark
    (设置, 账号, 选择会话, 知识库, dialog, md viewer opaque paper, 工作位置); g2 defects: 登录 summary vs 豆包 row, offers collapsed
    below connected services, masked phone on the detail page."""
    r8 = R8(res); o = {}
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"h3894 {name} error {e}")
        if not alive(): crashed.append(f"h3894 {name}"); relaunch()
    theme388(1)
    # 1 SkillHub market
    def mkt():
        o["list"] = result_json("group", "h394 list", timeout=90)
        L = o["list"] if isinstance(o["list"], dict) else {}
        if (L.get("plugins") or 0) < 10: selftest_fail.append(f"mkt3894: SkillHub plugins {L.get('plugins')} < 10 ({L.get('err')})")
        if (L.get("skills") or 0) < 10: selftest_fail.append(f"mkt3894: SkillHub skills {L.get('skills')} < 10 ({L.get('err')})")
        o["search"] = result_json("group", "h394 search weather", timeout=60)
        S = o["search"] if isinstance(o["search"], dict) else {}
        if not S.get("skills"): selftest_fail.append(f"mkt3894: search 'weather' found no skills {S}")
        result_json("group", "h394 open 0", timeout=20); time.sleep(8)
        r = shot388("mkt-plugins"); t = _txt(r); o["plugins_page"] = t[:500]
        if "SkillHub 插件" not in t: selftest_fail.append("mkt3894: 插件 tab not showing SkillHub list")
        if tap_text("搜索 SkillHub 插件", wait=1.5):
            type_text("answer-me-with-html"); time.sleep(1); sh("input keyevent KEYCODE_BACK"); time.sleep(5)
        r = shot388("mkt-plugin-search"); t = _txt(r); o["plugin_search"] = t[:400]
        if "answer-me-with-html" not in t: selftest_fail.append("mkt3894: plugin search did not find answer-me-with-html")
        elif "已安装" not in t:
            rows = [d for d in all_desc(r, "answer-me-with-html")]
            if rows: sh(f"input tap {rows[0][0]} {rows[0][1]}"); time.sleep(3)
            else: tap_text("answer-me-with-html", wait=3)
            shot388("mkt-plugin-intro")
            sh("pm grant com.openminis.mines.plus android.permission.POST_NOTIFICATIONS")
            abi = sh("getprop ro.product.cpu.abi").strip(); o["abi"] = abi
            if tap_text("一键安装", exact=True, wait=3):
                ok = False; t_end = time.time() + 300; seen = []; job_seen = False; job_gone_at = None
                while time.time() < t_end:   # [h4] cap 5 min; [h4b] the job pill/toast are popups (not in the dump) → watch the job notification
                    time.sleep(4)
                    nt = sh("dumpsys notification --noredact | grep -a -E 'android.title=.*(安装|任务在后台)' | head -3")
                    if "answer-me-with-html" in nt or "任务在后台" in nt: job_seen = True
                    elif job_seen and job_gone_at is None: job_gone_at = time.time()
                    t = _txt(dump())
                    seen += [x for x in ("正在安装", "安装中", "沙箱", "没成功", "失败", "出错", "完成", "已安装") if x in t and x not in seen]
                    if "失败" in t or "出错" in t or "没成功" in t: break
                    if "完成" in t or "已安装" in t: ok = True; break
                    if job_gone_at and time.time() - job_gone_at > 8: break
                o["plugin_seen"] = seen; o["plugin_job_seen"] = job_seen; o["plugin_job_ended"] = job_gone_at is not None
                r = shot388("mkt-plugin-installing"); o["plugin_task"] = _txt(r)[-400:]
                for lab in ("关闭", "完成", "好"):
                    if tap_text(lab, exact=True, wait=2): break
                time.sleep(3); r = shot388("mkt-plugin-installed"); t = _txt(r); o["plugin_after"] = t[:400]
                if "已安装" not in t:
                    back(1); result_json("group", "h394 open 0", timeout=20); time.sleep(6)
                    if tap_text("搜索 SkillHub 插件", wait=1.5): type_text("answer-me-with-html"); sh("input keyevent KEYCODE_BACK"); time.sleep(5)
                    r = shot388("mkt-plugin-installed2"); t = _txt(r)
                if "已安装" not in t:
                    # [h4b] the app's sandbox is an arm64 rootfs (PRoot); it never boots on the x86_64 emulator, so `minis-dsh add`
                    # ends with 「沙箱还没启动好」 after 60 s. There the step only checks that the install job started and ended.
                    if abi.startswith("x86") and o.get("plugin_job_seen") and o.get("plugin_job_ended"):
                        page_missing.append(f"mkt3894 note: plugin install job started and ended, not 已安装 — sandbox cannot boot on {abi} (expected on the emulator)")
                    elif abi.startswith("x86") and not o.get("plugin_job_seen"):
                        selftest_fail.append(f"mkt3894: 一键安装 started no install job (no job notification on {abi})")
                    else: selftest_fail.append(f"mkt3894: plugin not 已安装 after install ({o.get('plugin_task','')[-160:]})")
            else: selftest_fail.append("mkt3894: 一键安装 button not found in the plugin intro")
        back(1); time.sleep(1)
        o["skill"] = result_json("group", "h394 skill weather", timeout=120)
        K = o["skill"] if isinstance(o["skill"], dict) else {}
        if not K.get("installed"): selftest_fail.append(f"mkt3894: skill weather not installed {o['skill']}")
        result_json("group", "h394 open 1", timeout=20); time.sleep(8)
        if tap_text("搜索 SkillHub 技能", wait=1.5):
            type_text("weather"); time.sleep(1); sh("input keyevent KEYCODE_BACK"); time.sleep(5)
        r = shot388("mkt-skill-installed"); t = _txt(r); o["skill_page"] = t[:400]
        if "已安装" not in t: selftest_fail.append("mkt3894: 技能 tab does not show 已安装 for weather")
        back(1)
    step("mkt", mkt)
    # 2 Memoh hosted sign-in state
    def memoh():
        result_json("group", "agents clear", timeout=20); result_json("group", "agents add memoh", timeout=20)
        o["memoh"] = result_json("group", "h394 memoh fake", timeout=90)
        M = o["memoh"] if isinstance(o["memoh"], dict) else {}
        if not M.get("hosted"): selftest_fail.append(f"memoh: hosted base not recognised {M}")
        if any("还没登录" in m for m in (M.get("members") or [])): selftest_fail.append(f"memoh: still 还没登录 after a hosted sign-in {M.get('members')}")
        route("plus_app_agents", 6); r = shot388("memoh-agents"); t = _txt(r); o["memoh_page"] = t[:400]
        if "需要登录" in t and "Memoh" in t: page_missing.append("memoh: 应用代理 still shows 需要登录 (fake cookie rejected by the real site is possible)")
        back(1)
    step("memoh", memoh)
    # 3 自检 无障碍 wording
    def a11y():
        o["a11y"] = result_json("group", "h394 a11y", timeout=30)
        A = o["a11y"] if isinstance(o["a11y"], dict) else {}
        if A and not A.get("running") and "已开启" in str(A.get("detail")): selftest_fail.append(f"a11y: says 已开启 while not running {A}")
        route("plus_report", 8); tap_text("一键自检", wait=15); r = shot388("selfcheck"); t = _txt(r); o["selfcheck"] = t[:600]   # [h4] 自检 lives in 测试与上报, not 浏览器验证
        if "无障碍" in t and A and not A.get("running") and re.search(r"无障碍[^，。]{0,6}已开启", t): selftest_fail.append("a11y: 自检 page says 无障碍 已开启 while the service is not running")
        back(1)
    step("a11y", a11y)
    # 4 ☰ 账号 entry + 设置 账号 card
    def entries():
        b381_home(); time.sleep(1)
        if tap_text("更多", exact=True, wait=2):
            r = shot388("menu-light"); t = _txt(r)
            if "账号" not in t: selftest_fail.append("h3894: ☰ menu has no 账号 entry")
            if tap_text("账号", exact=True, wait=4):
                r = shot388("menu-accounts"); t = _txt(r)
                if "服务" not in t or "登录" not in t: selftest_fail.append("h3894: ☰ 账号 did not open the 账号 page")
                back(1)
        else: page_missing.append("h3894: ☰ 更多 not found")
        route("settings", 5); r = shot388("settings-light"); t = _txt(r); o["settings"] = t[:500]
        if "账号" not in t: selftest_fail.append("h3894: 设置 has no 账号 card")
        if t.count("账号") > 3: page_missing.append(f"h3894: 设置 shows 账号 {t.count('账号')} times (duplicate row?)")
        if tap_text("账号", wait=4):
            r = shot388("settings-accounts"); t = _txt(r)
            if "服务" not in t: selftest_fail.append("h3894: 设置 账号 card did not open the 账号 page")
            back(1)
        back(1)
    step("entries", entries)
    # 5 g2 defects on the 账号 page
    def g2():
        o["seed"] = result_json("group", "acct393 seed", timeout=60)
        result_json("group", "linkmark doubao|0", timeout=20)
        route("plus_connections", 8); time.sleep(2)
        r = shot388("acct-svc-offers"); t = _txt(r); o["svc"] = t[:600]
        if "帮我领" in t: selftest_fail.append("g2: free offers expanded at the top of 服务 (want collapsed below connected services)")
        if not tap_text("登录 标签", wait=4): tap_text("登录", exact=True, wait=4)
        time.sleep(3); scroll_down(); r = shot388("acct-login-summary"); t = _txt(r); o["login"] = t[:700]
        rows = [d for d in all_desc(r, "豆包")]
        if rows and ("未检查" in rows[0][2] or "正在确认" in rows[0][2]) and "过期了" in t:
            # [h4b] 过期了 can be another platform (the seeded OpenRouter key is refused by the real openrouter.ai → 401):
            # open the group and fail only when 豆包 itself is listed there
            if tap_text("过期了 ", wait=3):
                r2 = shot388("acct-login-expired-open"); t2 = _txt(r2); o["expired_open"] = t2[-500:]
                seg = t2.split("过期了", 1)[-1]
                seg = seg.split("还没关联", 1)[0].split("未检查 ", 1)[0]
                o["expired_rows"] = seg[:200]
                if "豆包" in seg: selftest_fail.append("g2: 一键关联的结果 lists 豆包 under 过期了 while its row is 未检查")
                else: page_missing.append(f"g2 note: 过期了 group = {seg.strip()[:80]!r} (not 豆包)")
                tap_text("过期了 ", wait=2)
            else:
                selftest_fail.append("g2: 一键关联的结果 says 过期了 while the 豆包 row is 未检查 (group not openable)")
        if not rows:
            sh("input swipe 540 600 540 1800 300"); time.sleep(1.5); r = dump(); rows = [d for d in all_desc(r, "豆包")]
        if rows:
            sh(f"input tap {rows[0][0]} {rows[0][1]}"); time.sleep(4)
            r = shot388("acct-detail-masked"); t = _txt(r); o["detail"] = t[:500]
            if "13800138000" in t: selftest_fail.append("g2: detail page shows the full phone number")
            back(1)
        else: page_missing.append("g2: 豆包 row not found")
        back(1)
    step("g2", g2)
    # 6 glass / opaque shots, light + dark
    def glass(sfx):
        route("settings", 5); shot388("glass-settings-" + sfx); back(1)
        route("plus_connections", 6); shot388("glass-accounts-" + sfx); back(1)
        b381_home(); time.sleep(1)
        if tap_text("更多", exact=True, wait=2):
            shot388("glass-menu-" + sfx)
            if tap_text("选择对话", wait=3) or tap_text("选择会话", wait=3):
                r = shot388("glass-select-" + sfx)
                if sfx == "light" and "✕" not in _txt(r) and "取消" not in _txt(r): page_missing.append("h3894: 选择会话 ✕ / 取消 not found")
                back(1)
            else: back(1)
        route("plus_knowledge", 8); shot388("glass-kb-" + sfx); back(1)
        route("plus_work_location", 8); r = shot388("glass-work-" + sfx); o["work_" + sfx] = _txt(r)[:500]
        if tap_text("添加服务器", wait=3): shot388("glass-dialog-" + sfx); back(1); time.sleep(1)
        back(1)
        b381_home(); time.sleep(1)
        if not tap_text("你好，我是测试模型。", wait=5): tap_text("GARBAGETEST", wait=5)
        result_json("group", "mdview", timeout=20); time.sleep(4); r = shot388("glass-md-" + sfx); o["md_" + sfx] = _txt(r)[:200]
        back(1)
    step("glass-light", lambda: glass("light"))
    theme388(2)
    step("glass-dark", lambda: glass("dark"))
    theme388(1)
    # 7 glass 自动 over a photo wallpaper (the loud generated one = worst case) + 外观调节 showing 自动 (现在 N%)
    def photo(sfx):
        route("plus_look", 6); r = shot388("look-auto-" + sfx); t = _txt(r); o["look_" + sfx] = t[:400]
        if "自动" not in t: selftest_fail.append(f"glass auto: 外观调节 does not show 自动 ({sfx})")
        back(1)
        route("settings", 5); shot388("photo-settings-" + sfx); back(1)
        route("plus_connections", 6); shot388("photo-accounts-" + sfx); back(1)
        b381_home(); time.sleep(2); shot388("photo-home-" + sfx)
    def photo_all():
        o["wall"] = selftest_json("uiprep", "杨arling|gen", timeout=20); time.sleep(2)
        theme388(1); photo("light")
        theme388(2); photo("dark")
        theme388(1)
        result_json("group", "wallpaper off")
    step("glass-photo", photo_all)
    r8["h3894"] = o
    print("H3894 RESULT " + json.dumps(o, ensure_ascii=False)[:5000], flush=True)

def b3893_acct(res):
    """[38.9.3 g] user 2026-10-17 「动不动就账号失效登录，而且ui都不一样，没对齐」: ONE 「账号」 page with 服务 / 登录 tabs, the same row +
    pill everywhere. Seeded like the user's phone: OpenRouter = API key only (patrol OK) that 38.9.2 listed under 账号关联 as
    登录失效, a stale 豆包 过期了 never confirmed, a mail-sender-named row 「Matt from WarpStati.」. Expect: OpenRouter 正常 in 服务 and
    NOT in 登录; no 登录失效 / 要重新登录 anywhere; 豆包 = 未检查 (grey) with 一键重新登录 in its detail; the sender name gone. Light + dark."""
    r8 = R8(res); o = {}
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"acct3893 {name} error {e}")
        if not alive(): crashed.append(f"acct3893 {name}"); relaunch()
    theme388(1)
    o["seed"] = result_json("group", "acct393 seed", timeout=60)
    st = o["seed"] if isinstance(o["seed"], dict) else {}
    if st.get("openrouterInLogin"): selftest_fail.append(f"acct3893: OpenRouter (API key only) still listed as a web login {st.get('login')}")
    if st.get("badNames"): selftest_fail.append(f"acct3893: mail sender name still a site name {st.get('badNames')}")
    if st.get("expired"): selftest_fail.append(f"acct3893: {st.get('expired')} rows still 要重新登录 without a confirmed sign-out {st.get('login')}")
    if st.get("orPill") != "OK": selftest_fail.append(f"acct3893: OpenRouter service pill {st.get('orPill')} (want OK)")
    def pages(sfx):
        route("plus_connections", 8); time.sleep(3)
        r = shot388("acct-svc-" + sfx); t = _txt(r); o["svc_" + sfx] = t[:600]
        if "服务" not in t or "登录" not in t: selftest_fail.append(f"acct3893 {sfx}: tabs 服务 / 登录 not shown")
        if "OpenRouter" not in t:
            scroll_down(); r = shot388("acct-svc2-" + sfx); t = _txt(r)
        if "OpenRouter" not in t: page_missing.append(f"acct3893 {sfx}: OpenRouter row not on 服务")
        for bad in ("登录失效", "要重新登录"):
            if bad in t: selftest_fail.append(f"acct3893 {sfx}: 「{bad}」 on 服务")
        # OpenRouter row: its description carries the pill
        rows = [d for d in all_desc(r, "OpenRouter")]
        o["or_row_" + sfx] = [x[2] for x in rows][:2]
        if rows and "正常" not in rows[0][2]:
            # the seeded key is fake: a real key test against openrouter.ai answers 401 -> 异常 is the truth, not a false 失效
            if "401" in rows[0][2] and "登录失效" not in rows[0][2]: page_missing.append(f"acct3893 {sfx}: OpenRouter fake key rejected by the real API (expected): {rows[0][2]}")
            else: selftest_fail.append(f"acct3893 {sfx}: OpenRouter row not 正常: {rows[0][2]}")
        # 登录 tab
        if not tap_text("登录 标签", wait=4): tap_text("登录", exact=True, wait=4)
        time.sleep(3); r = shot388("acct-login-" + sfx); t = _txt(r); o["login_" + sfx] = t[:700]
        for bad in ("登录失效", "Matt from", "OpenRouter"):
            if bad in t: selftest_fail.append(f"acct3893 {sfx}: 「{bad}」 on 登录")
        if "个登录" not in t: page_missing.append(f"acct3893 {sfx}: 登录 tab summary not shown")
        # 豆包 detail (stale 过期了 → 未检查 / 正在确认 + 一键重新登录)
        rows = [d for d in all_desc(r, "豆包")]
        if not rows:
            scroll_down(); r = dump(); rows = [d for d in all_desc(r, "豆包")]
        if rows:
            o["doubao_row_" + sfx] = rows[0][2]
            if "未检查" not in rows[0][2] and "正在确认" not in rows[0][2]: selftest_fail.append(f"acct3893 {sfx}: 豆包 row not 未检查: {rows[0][2]}")
            sh(f"input tap {rows[0][0]} {rows[0][1]}"); time.sleep(4)
            r = shot388("acct-detail-" + sfx); t = _txt(r); o["detail_" + sfx] = t[:500]
            if "一键重新登录" not in t: selftest_fail.append(f"acct3893 {sfx}: detail has no 一键重新登录")
            if "登录失效" in t: selftest_fail.append(f"acct3893 {sfx}: detail says 登录失效")
            back(1); time.sleep(2)
        else: page_missing.append(f"acct3893 {sfx}: 豆包 row not found on 登录")
        back(1); time.sleep(2)
    step("light", lambda: pages("light"))
    theme388(2)
    step("dark", lambda: pages("dark"))
    theme388(1)
    o["state"] = result_json("group", "acct393 state", timeout=60)
    r8["acct3893"] = o
    print("ACCT3893 RESULT " + json.dumps(o, ensure_ascii=False)[:4000], flush=True)


def b3893_hostkey(res):
    """[38.9.3 g] server reinstalled → host key changed: add the runner's sshd (password → key), regenerate its host keys,
    cold start, 立即检测 → 「服务器重装过，指纹变了」 + 「信任新指纹」 → tap → connected again (key still there) — never
    'End of IO stream' / 'Auth fail'."""
    r8 = R8(res); o = {}
    try:
        theme388(1)
        route("plus_work_location", 6)
        add_server("hk", SSH_HOST, SSH_PASS)
        time.sleep(20); r = shot388("hk-added"); o["added"] = _txt(r)[:400]
        sub = subprocess.run("sudo sh -c 'rm -f /etc/ssh/ssh_host_*; ssh-keygen -A >/dev/null; (systemctl restart ssh || service ssh restart)'; sleep 2; ssh-keyscan -T 5 127.0.0.1 2>/dev/null | head -1",
                             shell=True, capture_output=True, text=True, timeout=60)
        o["rekey"] = (sub.stdout or sub.stderr)[-200:]
        adb("shell", "am", "force-stop", PKG); time.sleep(1)
        adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(8)
        route("plus_work_location", 6)
        tap_text("立即检测", wait=4)
        t = ""
        for _ in range(20):
            time.sleep(3); t = _txt(dump())
            if "指纹变了" in t: break
        r = shot388("hk-changed"); t = _txt(r); o["changed"] = t[:500]
        if "指纹变了" not in t: selftest_fail.append("hostkey: changed host key not reported (no 「指纹变了」)")
        for bad in ("End of", "Auth fail"):
            if bad in t: selftest_fail.append(f"hostkey: raw error 「{bad}」 shown")
        if tap_text("信任新指纹", wait=3):
            for _ in range(15):
                time.sleep(3); t = _txt(dump())
                if "指纹变了" not in t: break
            r = shot388("hk-trusted"); t = _txt(r); o["trusted"] = t[:500]
            if "指纹变了" in t: selftest_fail.append("hostkey: still 指纹变了 after 信任新指纹")
            if "服务器密码" in t: o["asked_password"] = True   # acceptable when the key had to be re-installed
        else: selftest_fail.append("hostkey: no 「信任新指纹」 button")
        # test dialog: one reason per line (no 「X（X）」), 去授权 only when not authorised
        if tap_text("测试连接", wait=3):
            for _ in range(12):
                time.sleep(2.5); t = _txt(dump())
                if "好" in t or "去授权" in t: break
            r = shot388("hk-test"); t = _txt(r); o["test"] = t[:500]
            if re.search(r"(这个端口不是 SSH)（\1）", t): selftest_fail.append("hostkey: duplicated reason in 测试连接")
            tap_text("好", exact=True, wait=2)
        back(1)
    except Exception as e:
        import traceback; traceback.print_exc(); page_missing.append(f"hostkey error {e}")
        if not alive(): crashed.append("hostkey"); relaunch()
    r8["hostkey"] = o
    print("HOSTKEY RESULT " + json.dumps(o, ensure_ascii=False)[:3000], flush=True)


def b3891_fb(res):
    """[38.9.1 d] user feedback round: chat header 2nd line (account · model · 思考), 429 ends with a reason (no endless
    正在思考), per-reply model tag, model picker not empty + 自动（推荐）, 账号连接 cards, 一键关联 start copy, 关联结果 flat,
    Hark 测试一下 one-sentence + 去登录, markdown viewer before (old transparent) / after (opaque) light + dark."""
    r8 = R8(res); o = {}
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"fb3891 {name} error {e}")
        if not alive(): crashed.append(f"fb3891 {name}"); relaunch()
    theme388(1)
    def open_chat():
        b381_home(); time.sleep(1)
        if not tap_text("你好，我是测试模型。", wait=5): tap_text("GARBAGETEST", wait=5)
    # 1 chat header + per-reply tag, then a 429 turn
    def chat():
        open_chat()
        r = shot388("fb-chat-header"); t = _txt(r); o["header"] = t[:400]
        if "smoke-model" not in t and "Smoke Model" not in t: page_missing.append(f"fb3891 chat header: model id not seen {t[:160]}")
        def send(msg):
            e = edits(dump())
            if not e: page_missing.append("fb3891 chat: no input field"); return
            sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text(msg)
            sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
            tap_any(["Send", "发送"], wait=2) or sh("input keyevent KEYCODE_ENTER")
        send("hello again")
        time.sleep(8); sh("input keyevent 111"); r = shot388("fb-chat-tag"); o["tag"] = _txt(r)[-300:]
        send("SMOKE429 please")
        time.sleep(10); sh("input keyevent 111"); shot388("fb-chat-429-10s")
        time.sleep(30); r = shot388("fb-chat-429-40s"); t = _txt(r); o["429"] = t[-400:]
        if "正在思考" in t: selftest_fail.append("fb3891 429: still 正在思考 40 s after a 429")
    step("chat", chat)
    # 2 model picker from the header (not empty, 自动（推荐） on top)
    def picker():
        r = dump()
        if not (tap_text("smoke-model", wait=3, root=r) or tap_text("Smoke Model", wait=3, root=r) or tap_text("Default Models", wait=3, root=r)):
            page_missing.append("fb3891 picker: header model row not found")
        r = shot388("fb-picker"); t = _txt(r); o["picker"] = t[:400]
        if "自动" not in t: page_missing.append(f"fb3891 picker: 自动（推荐） not seen {t[:160]}")
        if "smoke-model" not in t and "Smoke Model" not in t: selftest_fail.append(f"fb3891 picker: no model rows {t[:160]}")
        back(1)
    step("picker", picker)
    # 3 markdown viewer over the chat: old transparent (before) vs opaque (after), light + dark
    def md():
        open_chat()
        o["md_legacy"] = result_json("group", "mdview legacy", timeout=20); time.sleep(4); shot388("fb-md-before"); back(1); time.sleep(1)
        o["md_new"] = result_json("group", "mdview", timeout=20); time.sleep(4); r = shot388("fb-md-after"); o["md_after"] = _txt(r)[:300]
        if "smoke-doc.md" not in o["md_after"]: page_missing.append("fb3891 md: viewer title not seen")
        back(1)
        theme388(2); open_chat()
        result_json("group", "mdview", timeout=20); time.sleep(4); shot388("fb-md-after-dark"); back(1)
        theme388(1)
    step("md", md)
    # 4 账号连接 cards (free credit, 需要处理) top + scrolled
    def conn():
        result_json("group", "feedinject resolved", timeout=20)
        route("plus_connections", 6); shot388("fb-conn"); scroll_down(); time.sleep(1.5); r = shot388("fb-conn-b"); o["conn"] = _txt(r)[:400]
        back(1)
    step("conn", conn)
    # 5 一键关联 start page copy, 6 关联结果 flattened
    def link():
        route("plus_linkall", 4); r = shot388("fb-linkall"); o["linkall"] = _txt(r)[:400]
        back(1)
        route("plus_linkall_summary", 5); r = shot388("fb-linkresult"); o["linkresult"] = _txt(r)[:400]
        scroll_down(); time.sleep(1.5); shot388("fb-linkresult-b"); back(1)
    step("link", link)
    # 7 Hark 测试一下: one plain sentence + 去登录 pill
    def hark():
        result_json("group", "agents clear", timeout=20); o["hark_add"] = result_json("group", "agents add web:hark", timeout=20)
        route("plus_app_agents", 6); shot388("fb-hark-row")
        if tap_text("测试一下", wait=1.5):
            time.sleep(1); shot388("fb-hark-testing"); time.sleep(8); r = shot388("fb-hark-test"); o["hark_test"] = _txt(r)[:300]
            if "去登录" not in o["hark_test"]: page_missing.append("fb3891 hark test: 去登录 not seen")
        else: page_missing.append("fb3891 hark: 测试一下 not found")
        back(1)
    step("hark", hark)
    r8["fb3891"] = o
    print("FB3891 RESULT " + json.dumps(o, ensure_ascii=False)[:4000], flush=True)


def b3891_ui(res):
    """[38.9.1] 账号关联 dynamic identities + Google provider detail, 应用代理 empty + manual add + 去登录 WebView + 测试一下 card,
    WorkBuddy error text, Grok key delete, KeyAgent IME dialog + Groq drawer, 4 real consoles non-white, 邮箱 verify Gmail + 163
    (real servers, fake codes → plain reason), in-app browser 手机版/电脑版 chip, 一键关联 e-mail login page."""
    r8 = R8(res); o = {}
    F = "http://10.0.2.2:8088"
    def page(rt, name, want=(), wait=6):
        try:
            route(rt, wait); r = shot388(name); t = _txt(r); o[name] = t[:500]
            if not alive(): crashed.append(f"b3891 {name}"); relaunch(); return None
            for w in want:
                if w not in t: page_missing.append(f"ui3891 {name}: '{w}' not seen")
            return r
        except Exception as e:
            page_missing.append(f"ui3891 {name} error {e}"); return None
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"ui3891 {name} error {e}")
        if not alive(): crashed.append(f"b3891 {name}"); relaunch()
    theme388(1)
    # 1 账号关联: identities the user has, + Google provider detail
    def kc():
        o["kcseed"] = result_json("group", "kcseed", timeout=30)
        r = page("plus_keychain", "kc3891-ident", ("账号关联",), wait=9)
        t = _txt(r)
        for w in ("138", "smoke@example.com"):
            if w not in t: page_missing.append(f"ui3891 kc-ident: '{w}' not seen")
        if tap_text("Google", wait=4): shot388("kc3891-google"); back()
        scroll_down(); time.sleep(1.5); shot388("kc3891-ident-b")
    step("kc", kc)
    # 2 应用代理: empty state, manual add, 去登录 (real web page), 测试一下 visible result
    def ag():
        o["ag_clear"] = result_json("group", "agents clear", timeout=20)
        r = page("plus_app_agents", "ag3891-empty", ("应用代理",))
        if tap_text("添加", wait=3) or tap_text("+", exact=True, wait=3):
            shot388("ag3891-catalog")
            if tap_text("Memoh", wait=3): pass
            back()
        o["ag_add"] = result_json("group", "agents add memoh", timeout=20)
        r = page("plus_app_agents", "ag3891-one", ("Memoh",))
        if tap_text("去登录", wait=8):
            r = shot388("ag3891-login"); o["ag-login"] = _txt(r)[:300]; back(); time.sleep(2)
        else: page_missing.append("ui3891 agents: 去登录 not found")
        if tap_text("测试一下", wait=1.5):
            time.sleep(2.5); shot388("ag3891-testing"); time.sleep(8); r = shot388("ag3891-test"); o["ag-test"] = _txt(r)[:300]
    step("agents", ag)
    # 3 WorkBuddy: the error is specific (not 「网络问题」 for a loopback gateway)
    def wb():
        o["workbuddy"] = str(result_json("group", "direct workbuddy|回复 OK", timeout=120))[:400]
        page("plus_group_partners", "wb3891-partners", ())
    step("workbuddy", wb)
    # 4 Grok key: delete works and stays deleted
    def grok():
        o["grok_seed"] = result_json("group", "vaultkey xai|xai-smokeKEY000000000000000000000000000000000000000000000000000000000000", timeout=30)
        selftest_json("openkind", "xai", timeout=20); time.sleep(5)
        shot388("grok3891-before")
        if tap_text("删除这个API Key", wait=2.5) or tap_text("删除这个密钥", wait=2.5):   # label = 删除这个<keyLabel>
            shot388("grok3891-confirm")
            tap_text("删除", exact=True, wait=3) or tap_text("确定", wait=3)
            r = shot388("grok3891-after"); o["grok-after"] = _txt(r)[:300]
        else: page_missing.append("ui3891 grok: 删除这个API Key / 密钥 not found")
        o["grok_state"] = selftest_json("keystate", "xai", timeout=20)
        if isinstance(o["grok_state"], dict) and o["grok_state"].get("has"): selftest_fail.append(f"ui3891 grok: key still there after delete {o['grok_state']}")
        back(2)
    step("grok", grok)
    # 5 KeyAgent WebView: dialog with the keyboard up, Groq-like drawer opaque
    def ka():
        sh("settings put secure show_ime_with_hard_keyboard 1")
        o["ka1"] = result_json("group", f"keyagent groq|{F}/keydlg", timeout=20); time.sleep(10)
        shot388("ka3891-page")
        if tap_text("Create API Key", wait=2.5):
            if tap_text("Name", wait=2.5): pass
            time.sleep(2); shot388("ka3891-ime")
        back(3); time.sleep(2)
        o["ka2"] = result_json("group", f"keyagent groq|{F}/drawer", timeout=20); time.sleep(10)
        shot388("ka3891-drawer"); back(3)
        theme388(2)
        o["ka3"] = result_json("group", f"keyagent groq|{F}/drawer", timeout=20); time.sleep(10)
        shot388("ka3891-drawer-dark"); back(3)
        theme388(1)
    step("keyagent", ka)
    # 6 four real consoles in the KeyAgent WebView: not a white page (sign-in page is fine)
    def consoles():
        nw = {}
        for k in ("volcengine", "qwen", "siliconflow", "openai"):
            result_json("group", f"keyagent {k}|", timeout=20); time.sleep(20)
            shot388(f"con3891-{k}"); nw[k] = _nonwhite(f"{OUT}/b388-con3891-{k}.png"); back(3); time.sleep(2)
        o["console_nonwhite"] = nw
        for k, v in nw.items():
            if isinstance(v, float) and v < 0.02: page_missing.append(f"ui3891 console {k}: looks blank ({v})")
    step("consoles", consoles)
    # 7 邮箱 verify against the REAL servers with fake codes: a plain Chinese reason, nothing saved
    def mail():
        result_json("mailbox", "plain ", timeout=10)
        g = result_json("mailbox", "add smoke.mines.test@gmail.com|abcd efgh ijkl mnop", timeout=60) or {}
        n = result_json("mailbox", "add smoke.mines.test@163.com|ABCDEFGHIJKLMNOP", timeout=60) or {}
        q = result_json("mailbox", "add smoke@outlook.com|abcdabcdabcd", timeout=30) or {}
        o["verify_gmail"] = g.get("error") if isinstance(g, dict) else g
        o["verify_163"] = n.get("error") if isinstance(n, dict) else n
        o["verify_outlook"] = q.get("error") if isinstance(q, dict) else q
        for nm, e in (("gmail", o["verify_gmail"]), ("163", o["verify_163"]), ("outlook", o["verify_outlook"])):
            if not e: selftest_fail.append(f"ui3891 mailbox {nm}: fake code accepted")
        result_json("mailbox", "open"); time.sleep(5); shot388("mail3891-box")
        if tap_text("添加邮箱", wait=3) or tap_text("添加 163 / QQ 邮箱", wait=3):
            r = dump(); edits = []
            try:
                import xml.etree.ElementTree as ET
                for nd in r.iter("node"):
                    if nd.get("class", "").endswith("EditText"):
                        b = re.findall(r"\d+", nd.get("bounds", "")); edits.append(((int(b[0]) + int(b[2])) // 2, (int(b[1]) + int(b[3])) // 2))
            except Exception as e: print("edit scan", e)
            if len(edits) >= 2:
                sh(f"input tap {edits[0][0]} {edits[0][1]}"); time.sleep(.6); sh("input text smoke.mines.test@163.com")
                sh("input keyevent 111"); time.sleep(1)   # keyboard down so the 2nd field is where the dump says
                e2 = []
                for nd in dump().iter("node"):
                    if nd.get("class", "").endswith("EditText"):
                        b = re.findall(r"\d+", nd.get("bounds", "")); e2.append(((int(b[0]) + int(b[2])) // 2, (int(b[1]) + int(b[3])) // 2))
                tgt = e2[1] if len(e2) >= 2 else edits[1]
                sh(f"input tap {tgt[0]} {tgt[1]}"); time.sleep(.8); sh("input text ABCDEFGHIJKLMNOP"); sh("input keyevent 111")
                tap_text("验证并添加", wait=18)
                r = shot388("mail3891-163-result"); o["mail-163-ui"] = _txt(r)[:300]
            back(2)
    step("mailbox", mail)
    # 8 in-app browser: 手机版 / 电脑版 chip per site + 用系统浏览器打开; 一键关联 e-mail login fake
    def br():
        b381_home(); time.sleep(1)
        selftest("webfit", f"open:{F}/linkfake", timeout=30); time.sleep(8)
        r = shot388("br3891-mobile"); o["br"] = _txt(r)[:300]
        if "手机版" not in o["br"] and "电脑版" not in o["br"]: page_missing.append("ui3891 browser: mode chip not seen")
        if tap_text("手机版", wait=6): shot388("br3891-desktop")
        tap_text("电脑版", wait=4)
        back(2)
        result_json("group", f"linkrun {F}/linkfake", timeout=20); time.sleep(8)
        if tap_text("邮箱登录", wait=2): shot388("link3891-email")
        back(2)
    step("browser", br)
    # 9 fog demo: calm / windy / rain wind forced, one screenrecord across them, plus 3 glass levels (light)
    def fog():
        anim(True)
        try:
            b381_home(); time.sleep(3)
            result_json("group", "fogwind 0.5|270|1", timeout=10); time.sleep(1)
            subprocess.Popen(f"adb shell screenrecord --time-limit 15 --size 540x1200 --bit-rate 3000000 /sdcard/fog.mp4", shell=True)
            time.sleep(1); shot388("fog3891-calm"); time.sleep(3.5)
            result_json("group", "fogwind 12|270|3", timeout=10); time.sleep(1); shot388("fog3891-windy"); time.sleep(3.5)
            result_json("group", "fogwind 6|200|63", timeout=10); time.sleep(1); shot388("fog3891-rain"); time.sleep(5)
            subprocess.run(f"adb pull /sdcard/fog.mp4 {OUT}/b3891-fog-demo.mp4", shell=True, timeout=60)
            o["fog_mp4"] = os.path.getsize(f"{OUT}/b3891-fog-demo.mp4") if os.path.exists(f"{OUT}/b3891-fog-demo.mp4") else 0
            o["fog_diff_windy_rain"] = _strip_diff(f"{OUT}/b388-fog3891-windy.png", f"{OUT}/b388-fog3891-rain.png")
            result_json("group", "fogwind off", timeout=10)
            for g in ("0.15", "0.30", "0.55"):
                o[f"look_{g}"] = result_json("group", f"lookset {g}|", timeout=10); time.sleep(2); shot388(f"glass3891-{g}")
            result_json("group", "lookset reset", timeout=10)
        finally:
            anim(False)
    step("fog", fog)
    r8["ui3891"] = o
    print("UI3891 RESULT " + json.dumps(o, ensure_ascii=False)[:5000], flush=True)



def set_zh():
    """[i11] the user's phone is Chinese: run the build-i screens with the app's language = 简体中文 (per-app locale, API 33+),
    so the sheets show what the user sees (English emulator → 「Model Groups」 etc. came from the English fallback)."""
    print("I11 locale", sh(f"cmd locale set-app-locales {PKG} --locales zh-CN 2>&1 || true")[-120:], flush=True)
    adb("shell", "am", "force-stop", PKG); time.sleep(1)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(8)
    dismiss_dialogs()


def b395_i(res):
    """[38.9.5 i] seeded like the user's phone (Google / GitHub signed in, 阿里云 过期了, 163 未检查, 硅基 忽略, 5 chats with goal
    tables, a cloud browser via workspace_register). Checks: accounts(list) for the agent never leaks a secret and offers ways
    in; one hidden re-login attempt per site answers with a plain reason (no endless spinner); the 免登录 AI gateway answers on
    127.0.0.1 at launch; screenshots light + dark of every changed screen."""
    r8 = R8(res); o = {}
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i395 {name} error {e}")
        if not alive(): crashed.append(f"i395 {name}"); relaunch()
    set_zh()
    o["seed"] = result_json("group", "i395 seed", timeout=90)
    sd = o["seed"] if isinstance(o["seed"], dict) else {}
    if not sd.get("ignored"): selftest_fail.append("i395: 硅基流动 not 忽略 by default/after seed")
    if "已加到" not in str(sd.get("ws", "")): selftest_fail.append(f"i395: workspace_register failed: {sd.get('ws')}")
    acc = selftest("group", "i395 accounts", timeout=60); o["accounts"] = acc[-1500:]
    if re.search(r"sk-[A-Za-z0-9]{10,}|password|密码：", acc): selftest_fail.append("i395: accounts(list) shows a secret")
    for site in ("github.com", "aliyun.com", "mail.163.com", "accounts.google.com"):
        o["login_" + site] = result_json("group", f"i395 login {site}", timeout=150)
        v = o["login_" + site]
        if not isinstance(v, dict) or not v.get("line"): selftest_fail.append(f"i395: re-login {site} gave no answer: {v}")
        elif not v.get("ok") and "：" not in v.get("line", ""): selftest_fail.append(f"i395: re-login {site} failed without a reason: {v}")
    o["gw"] = result_json("group", "i395 gw", timeout=60)
    gw = o["gw"] if isinstance(o["gw"], dict) else {}
    if not str(gw.get("models", "")).startswith("200"): selftest_fail.append(f"i395: gateway not answering on 127.0.0.1: {gw.get('models')}")
    o["bailian"] = selftest("group", "i395 bailian sk-ws-abcdEFGH12345678", timeout=20)[-200:]
    if "maas.aliyuncs.com" not in o["bailian"]: selftest_fail.append("i395: sk-ws key not sent to the workspace endpoint")
    LOOK_NOW = [False]
    def pages(sfx):
        r = b381_home(); time.sleep(3); r = shot388(f"i395-home-{sfx}"); t = _txt(r); o["home_" + sfx] = t[:500]
        rows = all_desc(r, "进度：")
        o["rings_" + sfx] = len(rows)
        if not rows: page_missing.append(f"i395 {sfx}: no 「⋯」 progress pill on home")
        else:
            sh(f"input tap {rows[0][0]} {rows[0][1]}"); time.sleep(3); r = shot388(f"i395-progress-{sfx}"); o["sheet_" + sfx] = _txt(r)[:400]
            back(1); time.sleep(2)
            # chat stop divider: open the 已停止 / 出错 chat through its card's 进入对话
            r = dump(); st = [x for x in all_desc(r, "进度：已停止")] or [x for x in all_desc(r, "进度：出错")]
            if st:
                sh(f"input tap {st[0][0]} {st[0][1]}"); time.sleep(3)
                if tap_text("进入对话", wait=4):
                    time.sleep(3); r = shot388(f"i395-chatstop-{sfx}"); t = _txt(r); o["chatstop_" + sfx] = t[-300:]
                    if "已停止" not in t and "出错" not in t: page_missing.append(f"i395 {sfx}: chat stop divider not seen")
                    back(1); time.sleep(2)
                else: back(1); time.sleep(1)
            else: page_missing.append(f"i395 {sfx}: no 已停止/出错 row for the chat divider")
        route("plus_keychain", 6); r = shot388(f"i395-accounts-{sfx}"); o["kc_" + sfx] = _txt(r)[:500]
        rows = all_desc(r, "添加：关联账号或添加身份")
        if rows:
            sh(f"input tap {rows[0][0]} {rows[0][1]}"); time.sleep(2); r = shot388(f"i395-acctmenu-{sfx}"); t = _txt(r); o["menu_" + sfx] = t[:200]
            if "关联账号" not in t or "添加身份" not in t: selftest_fail.append(f"i395 {sfx}: account menu items missing: {t[:120]}")
            if tap_text("添加身份", wait=3):
                r = shot388(f"i395-addident-{sfx}"); t = _txt(r)
                for g in ("常用", "社交", "其他"):
                    if g not in t: page_missing.append(f"i395 {sfx}: 添加身份 group {g} not seen")
                back(1); time.sleep(1)
            else: back(1)
        else: page_missing.append(f"i395 {sfx}: account + menu not found")
        back(1); time.sleep(1)
        route("plus_connections", 6); r = shot388(f"i395-services-{sfx}"); o["svc_" + sfx] = _txt(r)[:500]
        if not tap_text("登录 标签", wait=3): tap_text("登录", exact=True, wait=3)
        time.sleep(2); r = shot388(f"i395-logins-{sfx}"); o["logins_" + sfx] = _txt(r)[:600]
        back(1); time.sleep(1)
        route("plus_code_sources", 5); r = shot388(f"i395-codesrc-{sfx}"); t = _txt(r); o["codesrc_" + sfx] = t[:500]
        for want in ("验证码来源", "AI 邮箱", "AgentMail", "Mail.tm"):
            if want not in t:
                scroll_down(); t += " " + _txt(dump())
            if want not in t: selftest_fail.append(f"i395 {sfx}: 验证码与 AI 邮箱 page lacks {want}")
        scroll_down(); shot388(f"i395-codesrc2-{sfx}")
        back(1); time.sleep(1)
        route("model_groups", 6); r = shot388(f"i395-modelgroups-{sfx}"); t = _txt(r); o["mg_" + sfx] = t[:500]
        if "免登录 AI" not in t: selftest_fail.append(f"i395 {sfx}: 模型组 route status card missing")
        for en in ("Model Groups", "Groups"):   # [i11] Chinese UI
            if en in t: selftest_fail.append(f"i395 {sfx}: 模型组 page still shows English 「{en}」")
        if "没登录 重新登录" in t: selftest_fail.append(f"i395 {sfx}: never-signed route row says 重新登录 (want 登录)")
        back(1); time.sleep(1)
        route("plus_work_location", 6); scroll_down(); r = shot388(f"i395-work-{sfx}"); t = _txt(r); o["work_" + sfx] = t[:600]
        if "免费云浏览器" not in t:
            scroll_down(); r = shot388(f"i395-work2-{sfx}"); t = _txt(r)
        if "免费云浏览器" not in t: selftest_fail.append(f"i395 {sfx}: registered cloud browser not on 工作位置")
        elif "到期" not in t: selftest_fail.append(f"i395 {sfx}: cloud browser card has no 到期 line")
        back(1); time.sleep(1)
        route("plus_app_agents", 5); shot388(f"i395-agents-{sfx}"); back(1); time.sleep(1)
        # glass 自动 (thin again in i5): 外观调节 over the fog, top + glass section
        if sfx == "dark" and not LOOK_NOW[0]: return   # [i5b] dark 外观调节 runs last (emulator died there in 39044584628)
        route("plus_look", 6); r = shot388(f"i395-look-{sfx}"); o["look_" + sfx] = _txt(r)[:300]
        scroll_down(); time.sleep(1); r = shot388(f"i395-look2-{sfx}"); t = _txt(r)
        m_ = re.search(r"现在 (\d+)%", t + " " + o["look_" + sfx]); o["glass_" + sfx] = m_.group(1) if m_ else "?"
        if m_ and int(m_.group(1)) > (45 if sfx == "light" else 50): selftest_fail.append(f"i395 {sfx}: glass auto {m_.group(1)}% over the cap")
        back(1); time.sleep(1)
    theme388(1)
    step("light", lambda: pages("light"))
    theme388(2)
    step("dark", lambda: pages("dark"))
    theme388(1)
    # 38.9.4 smoke fixes: refused key = 「key 无效」 on 服务, not 登录 › 过期了 (seeded OpenRouter key gets 401 from the real site)
    def keybad():
        result_json("group", "acct393 seed", timeout=60); time.sleep(20)
        route("plus_connections", 8); time.sleep(3); r = shot388("i395-keybad-svc"); t = _txt(r); o["keybad_svc"] = t[:500]
        if not tap_text("登录 标签", wait=3): tap_text("登录", exact=True, wait=3)
        time.sleep(3); r = shot388("i395-keybad-login"); t = _txt(r); o["keybad_login"] = t[:600]
        if "OpenRouter" in t and "过期" in t: selftest_fail.append("i395: refused OpenRouter key still under 登录 › 过期了")
        back(1)
    # 外观调节 over a photo wallpaper: frame time (emulator died here twice on 38.9.4)
    def lookperf():
        o["wall"] = selftest_json("uiprep", "杨arling|gen", timeout=20); time.sleep(2)
        sh(f"dumpsys gfxinfo {PKG} reset"); route("plus_look", 6)
        for _ in range(3): scroll_down(); time.sleep(1)
        shot388("i395-look-photo")
        t = _txt(dump()); m_ = re.search(r"现在 (\d+)%", t); o["glass_photo"] = m_.group(1) if m_ else "?"
        g = sh(f"dumpsys gfxinfo {PKG}", timeout=30)
        o["gfx"] = " | ".join(l.strip() for l in g.splitlines() if re.search(r"Total frames|Janky frames|50th percentile|90th percentile|99th percentile", l))[:400]
        print("I395 GFX", o["gfx"], flush=True)
        back(1); time.sleep(1)
        r = b381_home(); time.sleep(3); shot388("i395-home-photo-light")
        theme388(2); time.sleep(3); route("plus_look", 6); scroll_down(); time.sleep(1); shot388("i395-look-photo-dark")
        back(1); r = b381_home(); time.sleep(3); shot388("i395-home-photo-dark"); theme388(1)
        result_json("group", "wallpaper off")
    # SkillHub same-slug rows: only the installed publisher's row says 已安装
    o["skill"] = result_json("group", "h394 skill weather", timeout=120)
    sk = o["skill"] if isinstance(o["skill"], dict) else {}
    stt = sk.get("states") or []
    if stt and sum(1 for x in stt if x.endswith(":MINE")) != 1: selftest_fail.append(f"i395: SkillHub same-slug states {stt} (want exactly one MINE)")
    if len(stt) > 1 and "没有覆盖" not in str(sk.get("other", "")): selftest_fail.append(f"i395: installing another publisher's same-slug skill did not refuse: {sk.get('other')}")
    def skillshot():
        result_json("group", "h394 open 1", timeout=20); time.sleep(8)
        if tap_text("搜索 SkillHub 技能", wait=1.5):
            type_text("weather"); time.sleep(1); sh("input keyevent KEYCODE_BACK"); time.sleep(5)
        r = shot388("i395-skill-sameslug"); t = _txt(r); o["skill_page"] = t[:400]
        # [i11] count ROWS whose pill says 已安装 (the installed row's own description also contains 「已安装，长按卸载」)
        inst_rows = all_desc(r, "状态：已安装"); o["skill_rows_installed"] = len(inst_rows); o["skill_rows_other"] = len(all_desc(r, "状态：同名已装"))
        if len(inst_rows) > 1: selftest_fail.append(f"i395: {len(inst_rows)} same-slug rows say 已安装 on the market page")
        back(1)
    # [i6] user 22:02–22:04: ☰ 「AI 服务」 + glass menu, Notion truthful web sign-in, 脑图 sci-fi
    def menu_ai(sfx):
        r = b381_home(); time.sleep(2)
        menu = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
        # [i11] tap the CENTRE (node_bounds = x1,y1,x2,y2; the old tap hit the circle's corner and never opened the menu)
        if menu: sh(f"input tap {(menu[0] + menu[2]) // 2} {(menu[1] + menu[3]) // 2}")
        else: tap_text("更多", wait=2)
        time.sleep(2); r = shot388(f"i395-menu-{sfx}"); t = _txt(r); o["menu_home_" + sfx] = t[:300]
        if "AI 服务" not in t: selftest_fail.append(f"i395 {sfx}: ☰ menu has no 「AI 服务」: {t[:120]}")
        if tap_text("AI 服务", exact=True, wait=3):
            time.sleep(4); r = shot388(f"i395-aiservices-{sfx}"); t = _txt(r); o["aisvc_" + sfx] = t[:300]
            if "登录" in t and "服务" in t and "AI 服务" not in t: selftest_fail.append(f"i395 {sfx}: AI 服务 opened the 账号 page with tabs")
            back(1); time.sleep(1)
        else: back(1)
    step("menu-light", lambda: menu_ai("light"))
    def notion():
        o["notion_seed"] = selftest("group", "connfake notion", timeout=30)[-120:]
        selftest("openkind", "notion", timeout=20); time.sleep(10)
        r = shot388("i395-notion"); t = _txt(r); o["notion"] = t[:500]
        if "已连接 · 断开" in t: selftest_fail.append("i395: Notion button still 「已连接 · 断开」")
        if "未登录" not in t and "已登录" not in t and "检查" not in t: selftest_fail.append(f"i395: Notion hero has no truthful login state: {t[:160]}")
        if "已登录" in t and "未登录" not in t: selftest_fail.append("i395: Notion says 已登录 with a fake cookie (getSpaces must refuse it)")
        # [i11] never signed in → the button says 登录 (not 重新登录); no lone ⓘ above it
        if "未登录" in t and "重新登录" in t: selftest_fail.append("i395: Notion 未登录 but the button says 重新登录")
        if "说明：说明" in t: selftest_fail.append("i395: Notion still has the lone ⓘ above the button")
        back(1); time.sleep(1)
        selftest("group", "connfake notion|off", timeout=30)
    step("notion", notion)
    def kbmap(sfx):
        B = "http://10.0.2.2:8088"
        result_json("group", "kbep "); result_json("group", f"base web:doubao|{B}/webchat")
        result_json("group", "kbseed "); result_json("group", "kbrun", timeout=120)
        sh(f"dumpsys gfxinfo {PKG} reset")
        route("plus_knowledge", 7); r = shot388(f"i395-kbmap-{sfx}")
        topics = [d for d in all_desc(r, "脑图节点：") if " · " in d[2]]
        o["kb_topics_" + sfx] = len(topics)
        if not topics: selftest_fail.append(f"i395 {sfx}: 脑图 has no topic nodes")
        else:
            sh(f"input tap {topics[0][0]} {topics[0][1]}"); time.sleep(2.5); shot388(f"i395-kbbranch-{sfx}")
            sh("input swipe 500 1300 200 1100 120"); time.sleep(2); shot388(f"i395-kbfling-{sfx}")
        g = sh(f"dumpsys gfxinfo {PKG}", timeout=30)
        o["kb_gfx_" + sfx] = " | ".join(l.strip() for l in g.splitlines() if re.search(r"Total frames|Janky frames|50th percentile|90th percentile", l))[:300]
        print("I395 KB GFX", sfx, o["kb_gfx_" + sfx], flush=True)
        back(2); time.sleep(1)
    step("kb-light", lambda: kbmap("light"))
    theme388(2)
    step("menu-dark", lambda: menu_ai("dark"))
    step("kb-dark", lambda: kbmap("dark"))
    theme388(1)
    step("keybad", keybad)
    step("skillshot", skillshot)
    def look_dark():
        LOOK_NOW[0] = True; theme388(2)
        route("plus_look", 6); r = shot388("i395-look-dark"); o["look_dark"] = _txt(r)[:300]
        scroll_down(); time.sleep(1); r = shot388("i395-look2-dark"); t = _txt(r)
        m_ = re.search(r"现在 (\d+)%", t + " " + o["look_dark"]); o["glass_dark"] = m_.group(1) if m_ else "?"
        if m_ and int(m_.group(1)) > 50: selftest_fail.append(f"i395 dark: glass auto {m_.group(1)}% over the cap")
        back(1); time.sleep(1); theme388(1)
    step("look-dark", look_dark)
    step("lookperf", lookperf)
    r8["i395"] = o
    print("I395 RESULT " + json.dumps(o, ensure_ascii=False)[:6000], flush=True)

def b395_i2(res):
    """[38.9.5 i 22:23–22:43] new shots first (the emulator tends to die late): home greeting + weather light/dark, varied
    greeting never repeats on consecutive opens, 引用回复 + copy chips in a chat, the secure login card light/dark."""
    r8 = R8(res); o = {}
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i395b {name} error {e}")
        if not alive(): crashed.append(f"i395b {name}"); relaunch()
    set_zh()
    result_json("group", "i395 seed", timeout=90)
    # varied greeting: 6 opens, never the same phrase twice in a row
    gs = [selftest("group", "greetvar", timeout=20).strip()[-40:] for _ in range(6)]
    o["greetings"] = gs
    if any(a == b for a, b in zip(gs, gs[1:])): selftest_fail.append(f"i395b: greeting repeated on consecutive opens: {gs}")
    if any("主人" in g for g in gs): selftest_fail.append(f"i395b: greeting still says 主人: {gs}")
    def home(sfx):
        result_json("group", 'greet {"hour":22,"weather":"cloudy","soon":"1 小时后有雨"}')
        r = b381_home(); time.sleep(4); r = shot388(f"i395b-home-{sfx}"); t = _txt(r); o["home_" + sfx] = t[:400]
        w = all_desc(r, "天气：")
        if not w: selftest_fail.append(f"i395b {sfx}: no weather on home")
        else:
            sh(f"input tap {w[0][0]} {w[0][1]}"); time.sleep(2); r = shot388(f"i395b-weather-{sfx}"); o["wx_" + sfx] = _txt(r)[:300]
            back(1); time.sleep(1)
        route("settings", 5); r = shot388(f"i395b-settings-{sfx}"); o["settings_" + sfx] = _txt(r)[:300]; back(1); time.sleep(1)
    def chat(sfx):
        r = b381_home(); time.sleep(2)
        rows = all_desc(r, "进度：")
        if rows:
            sh(f"input tap {rows[0][0]} {rows[0][1]}"); time.sleep(3); tap_text("进入对话", wait=4); time.sleep(3)
        else:
            tap_text("开个新对话", wait=4); time.sleep(3)
        r = dump(); e = edits(r)
        if not e: page_missing.append(f"i395b {sfx}: no chat input"); return
        # [i11] both themes type and send (dark used to reuse a message that was not there after the theme restart → no chips);
        # the fake sk-ws test key goes FIRST and the text is typed in short chunks (one long `input text` was cut after 「path」)
        sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6)
        for chunk in ("key sk-ws-AbCdEf0123456789xyzQW ", "srv 38.76.209.77:8090 code 482913 ", "mail smoke@example.com ", "api http://8.217.89.133:8787/v1"):
            type_text(chunk); time.sleep(0.4)
        sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
        r0 = dump()
        sb = node_bounds(r0, lambda n: (n.get("content-desc") or "") in ("Send", "发送"))
        if sb: sh(f"input tap {(sb[0] + sb[2]) // 2} {(sb[1] + sb[3]) // 2}")
        elif not tap_any(["Send", "发送"], wait=1): sh("input keyevent KEYCODE_ENTER")
        time.sleep(10)
        r = shot388(f"i395b-chips-{sfx}"); t = _txt(r); o["chips_" + sfx] = t[-500:]
        if "482913" in t and "验证码" not in t and "code" in t: pass
        if "sk-ws-AbCd" in t and "sk-ws-••••••" not in t: pass  # the bubble shows the raw text the user typed; the chip must be masked
        chips = all_desc(r, "IP 38.76.209.77:8090")
        if not chips: selftest_fail.append(f"i395b {sfx}: no IP chip under the message")
        else:
            sh(f"input tap {chips[0][0]} {chips[0][1]}"); time.sleep(1.5)
            clip = sh("cmd clipboard get-primary-clip 2>/dev/null || true")
            o["clip_" + sfx] = clip[-80:]
        if not all_desc(r, "密钥已隐藏"): selftest_fail.append(f"i395b {sfx}: the sk-ws key is not a masked chip")
        # 引用回复 from the user bubble's long-press menu
        b = node_bounds(r, lambda n: "482913" in (n.get("text") or ""))
        if b:
            sh(f"input swipe {b[0]} {b[1]} {b[0]} {b[1]} 900"); time.sleep(2)
            if tap_text("引用回复", wait=3):
                time.sleep(2); sh("input keyevent KEYCODE_BACK"); time.sleep(1)
                r = shot388(f"i395b-quote-{sfx}"); o["quote_" + sfx] = _txt(r)[-300:]
                if not all_desc(r, "引用："): selftest_fail.append(f"i395b {sfx}: quote block not above the input")
                q = all_desc(r, "取消引用")
                if q: sh(f"input tap {q[0][0]} {q[0][1]}"); time.sleep(1)
            else: page_missing.append(f"i395b {sfx}: 引用回复 not in the bubble menu"); back(1)
        else: page_missing.append(f"i395b {sfx}: user bubble not found")
        back(1); time.sleep(1)
    def card(sfx):
        selftest("group", "i395 card", timeout=20); time.sleep(3)
        r = shot388(f"i395b-card-{sfx}"); t = _txt(r); o["card_" + sfx] = t[:300]
        for want in ("Notion", "保存到保险箱", "登录"):
            if want not in t: selftest_fail.append(f"i395b {sfx}: login card lacks {want}")
        if "smoke@example.com" not in t: page_missing.append(f"i395b {sfx}: account not prefilled on the card")
        selftest("group", "i395 cardoff", timeout=20); time.sleep(1)
        selftest("group", "i395 card refused", timeout=20); time.sleep(3)
        r = shot388(f"i395b-cardrefused-{sfx}"); t = _txt(r)
        if "密码不对" not in t: selftest_fail.append(f"i395b {sfx}: refused card title missing")
        selftest("group", "i395 cardoff", timeout=20); time.sleep(1)
    theme388(1)
    step("home-light", lambda: home("light"))
    step("chat-light", lambda: chat("light"))
    step("card-light", lambda: card("light"))
    theme388(2)
    step("home-dark", lambda: home("dark"))
    step("chat-dark", lambda: chat("dark"))
    step("card-dark", lambda: card("dark"))
    theme388(1)
    result_json("group", "greet off")
    r8["i395b"] = o
    print("I395B RESULT " + json.dumps(o, ensure_ascii=False)[:4000], flush=True)


UNTESTABLE = []   # [38.9.6 i15] checks the x86_64 emulator cannot run (proot sandbox never starts there)


def _sandbox_down(sc):
    return isinstance(sc, list) and any("沙箱还没启动" in (r.get("detail") or "") for r in sc)


RECOVERIES = []   # [38.9.6 i17] app restarts after the emulator's GMS / FontsProvider death (smoke P)


def wait_gms_stable(limit=150):
    """[i17] smoke P: com.google.android.gms.persistent died mid-run and took Mines with it ('depends on provider
    ...FontsProvider in dying proc'). Before the app starts, wait until the GMS persistent pid has not changed for 25 s."""
    t0 = time.time(); last = None; since = time.time()
    while time.time() - t0 < limit:
        pid = sh("pidof com.google.android.gms.persistent").strip()
        if pid != last: last = pid; since = time.time()
        elif pid and time.time() - since >= 25: break
        time.sleep(5)
    print(f"GMS persistent pid {last!r} stable after {int(time.time() - t0)} s", flush=True)


def gms_died_since(mark):
    """[i17] ActivityManager lines about GMS dying / a provider in a dying process since logcat mark (epoch secs)."""
    out = sh(f"logcat -d -v epoch -t {int(mark)}.000 ActivityManager:I *:S", timeout=30)
    return [l for l in out.splitlines() if ("dying proc" in l or "com.google.android.gms.persistent" in l and "died" in l.lower())][:5]


def seed_model():
    """[i17] the fake OpenAI provider + smoke-model, whatever happened to onboarding."""
    r = result_json("group", "i396 seedmodel", timeout=40)
    print(f"  seedmodel -> {r}", flush=True)
    return r


def recover(tag):
    """[i17] app dead or home unreachable → force-stop, relaunch, skip onboarding if it is back, re-seed the model."""
    RECOVERIES.append(tag); print(f"!!! RECOVER after {tag} (GMS: {gms_died_since(time.time() - 900)})", flush=True)
    sh(f"am force-stop {PKG}"); time.sleep(2)
    if not sh("pidof com.google.android.gms.persistent").strip(): wait_gms_stable(90)
    sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity", timeout=90); time.sleep(10)
    dismiss_dialogs()
    seed_model()
    r = dump()
    if r is not None and find(r, "Welcome to Mines"):
        for lab in ("Skip", "跳过", "Skip for now", "以后再说"):
            if tap_text(lab, exact=True, wait=2): break
        sh(f"am force-stop {PKG}"); time.sleep(2); sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity", timeout=90); time.sleep(10)
    set_zh()
    return b381_home()


def healthy_home():
    """[i17] the app is alive and the home list answers a dump within reason; else recover once."""
    if not alive(): return recover("dead")
    t0 = time.time(); r = dump()
    if r is not None and find(r, "最近对话"): return r
    r = b381_home()
    if r is None or not find(r, "最近对话") or time.time() - t0 > 60: return recover("home unreachable")
    return r


def blur_ratio(home_png, menu_png, box=(0.70, 0.36, 0.97, 0.62)):
    """[i17] sharpness of the page right of the ☰ menu: menu shot vs home shot (gradient energy ratio, 1 = untouched)."""
    try:
        from PIL import Image
        def lum(p):
            im = Image.open(p).convert("L"); W, H = im.size
            im = im.crop((int(box[0] * W), int(box[1] * H), int(box[2] * W), int(box[3] * H)))
            return list(im.getdata()), im.size
        (a, (w, h)), (b, _) = lum(home_png), lum(menu_png)
        def e(px):
            return sum(abs(px[i + 1] - px[i]) + abs(px[i + w] - px[i]) for i in range(0, (h - 1) * w) if (i % w) < w - 1)
        ea = e(a); return round(e(b) / ea, 3) if ea > 0 else 0.0
    except Exception as ex:
        print("blur_ratio error", ex, flush=True); return -1.0


def b396x(res):
    """[38.9.6 i15] seed chats first (i396m sheets need a chat), weather scenes (7 × light/dark + static fallback), 快速测试 sheet
    over the busy model page (Chinese errors, glass), 重试 tapped twice (「已重新开始（第 N 次）」), every overlay in both themes
    with the GlassBackdrop blur flag, embedded Clash against the runner's auth-only proxy."""
    r8 = R8(res); o = {"overlay": []}
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i396x {name} error {e}")
        if not alive(): crashed.append(f"i396x {name}"); recover(f"i396x {name}")
    def ov(name, sfx):
        r = shot388(f"i396x-ov-{name}-{sfx}"); f = result_json("group", "i396 overlay", timeout=10)
        o["overlay"].append({"name": name, "theme": sfx, "flag": f, "text": _txt(r)[:160]})
        print(f"I396X OVERLAY | {sfx} | {name} | {json.dumps(f, ensure_ascii=False)[:200]}", flush=True)
        return r
    set_zh()
    o["seedmodel"] = seed_model()
    o["seed"] = result_json("group", "i396 seed", timeout=90)
    # 1 weather scenes
    def sky(sfx):
        for k in ("sunny", "cloudy", "rain", "snow", "fog", "night", "sunset"):
            st = result_json("group", f"i396 sky {k}", timeout=20); b381_home(); time.sleep(3)
            shot388(f"i396x-sky-{k}-{sfx}"); o[f"sky_{k}_{sfx}"] = st
            if not (isinstance(st, dict) and st.get("scene") == k): selftest_fail.append(f"i396x sky {sfx}: forced {k} but scene={st}")
        for k in ("rain", "night"):
            result_json("group", f"i396 sky {k} static", timeout=20); b381_home(); time.sleep(3); shot388(f"i396x-sky-{k}-static-{sfx}")
        result_json("group", "i396 sky cloudy static", timeout=20)   # [i15] static for the rest of the run (swiftshader load)
    # 2 快速测试 sheet over the busy model page
    def quick(sfx):
        healthy_home()
        q = result_json("group", "i396 quicktest", timeout=30)
        if q == "no model": seed_model(); q = result_json("group", "i396 quicktest", timeout=30)
        o["quick_" + sfx] = q
        time.sleep(8); r = ov("quicktest", sfx); t = _txt(r); o["quicktext_" + sfx] = t[:400]
        if "快速测试" not in t: page_missing.append(f"i396x 快速测试 {sfx}: sheet not open ({str(q)[:60]})")
        for en in ("No API key configured", "API key", "provider."):
            if en in t: selftest_fail.append(f"i396x 快速测试 {sfx}: English error still shown 「{en}」")
        back(1); time.sleep(1); back(1); time.sleep(1)
    # 3 重试 tapped twice on the 「帮我领取免费云浏览器」 card
    def retry(sfx):
        rg = result_json("group", "i396 retryreg", timeout=20); o["retryreg_" + sfx] = rg
        r = b381_home(); time.sleep(3)
        tb = node_bounds(r, lambda n: (n.get("text") or "").startswith("帮我领取免费云浏览器"))
        pills = [p for p in all_desc(r, "进度：") if tb and tb[1] - 20 <= p[1] <= tb[3] + 160]
        if not pills: page_missing.append(f"i396x 重试 {sfx}: no ⋯ pill on 帮我领取免费云浏览器"); return
        sh(f"input tap {pills[0][0]} {pills[0][1]}"); time.sleep(3)
        for n in (1, 2):
            if not tap_text("重试", exact=True, wait=2.5): selftest_fail.append(f"i396x 重试 {sfx}: 重试 #{n} not found"); return
            r = shot388(f"i396x-retry{n}-{sfx}"); t = _txt(r); o[f"retry{n}_{sfx}"] = t[:300]
            if "重新开始" not in t: selftest_fail.append(f"i396x 重试 {sfx}: tap #{n} shows no 「重新开始」 line")
            if n == 2 and "第 2 次" not in t and "第 3 次" not in t and "第 4 次" not in t: selftest_fail.append(f"i396x 重试 {sfx}: second tap has no 「第 N 次」")
        c = result_json("group", "i396 retrycount", timeout=10); o["retrycount_" + sfx] = c
        back(1); time.sleep(1)
    # 4 overlays
    def overlays(sfx):
        healthy_home(); r = b381_home(); time.sleep(3)
        mb = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
        if mb: sh(f"input tap {(mb[0] + mb[2]) // 2} {(mb[1] + mb[3]) // 2}"); time.sleep(2.5); ov("menu", sfx); back(1); time.sleep(1)
        r = b381_home(); time.sleep(2); pills = all_desc(r, "进度："); o["pills_" + sfx] = len(pills)
        if len(pills) < 3: selftest_fail.append(f"i396x {sfx}: only {len(pills)} ⋯ progress pills on home")
        if pills: sh(f"input tap {pills[0][0]} {pills[0][1]}"); time.sleep(3); ov("progress", sfx); back(1); time.sleep(1)
        r = b381_home(); time.sleep(2)
        tb = node_bounds(r, lambda n: (n.get("text") or "").startswith("今天新加坡天气"))
        if tb:
            cx, cy = (tb[0] + tb[2]) // 2, (tb[1] + tb[3]) // 2
            sh(f"input swipe {cx} {cy} {cx} {cy} 1600"); time.sleep(2); ov("rowmenu", sfx); back(1); time.sleep(1)
        result_json("group", 'greet {"hour":15,"weather":"cloudy"}'); r = b381_home(); time.sleep(3); w = all_desc(r, "天气：")
        if w: sh(f"input tap {w[0][0]} {w[0][1]}"); time.sleep(2.5); ov("dialog", sfx); back(1); time.sleep(1)
        route("plus_keychain", 6); r = dump(); a = all_desc(r, "添加：关联账号或添加身份")
        if a: sh(f"input tap {a[0][0]} {a[0][1]}"); time.sleep(2.5); ov("bsheet", sfx); back(1); time.sleep(1)
        back(1); time.sleep(1)
        r = b381_home(); time.sleep(2)
        if tap_text("今天新加坡天气", wait=4):
            time.sleep(1); shot388(f"i396x-chat-{sfx}")
            r = dump(); mb = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
            if mb: sh(f"input tap {(mb[0] + mb[2]) // 2} {(mb[1] + mb[3]) // 2}"); time.sleep(2); ov("chatmenu", sfx); back(1); time.sleep(1)
            r = dump(); ab = node_bounds(r, lambda n: (n.get("content-desc") or "") == "附件")
            if ab: sh(f"input tap {(ab[0] + ab[2]) // 2} {(ab[1] + ab[3]) // 2}"); time.sleep(2); ov("attach", sfx); back(1); time.sleep(1)
            else: page_missing.append(f"i396x {sfx}: + 附件 not found in chat")
            r = dump()
            if tap_text("默认模型", wait=3, root=r) or tap_text("smoke-model", wait=2, root=r): ov("picker", sfx); back(1); time.sleep(1)
            else: page_missing.append(f"i396x {sfx}: model picker header not found")
            back(1); time.sleep(1)
        else: page_missing.append(f"i396x {sfx}: no chat to open")
    for mode, sfx in ((1, "light"), (2, "dark")):
        theme388(mode)
        step("sky-" + sfx, lambda sfx=sfx: sky(sfx))
        step("quick-" + sfx, lambda sfx=sfx: quick(sfx))
        step("retry-" + sfx, lambda sfx=sfx: retry(sfx))
        step("overlays-" + sfx, lambda sfx=sfx: overlays(sfx))
    theme388(1)
    # 5 embedded Clash (mihomo arm64) through the runner's auth-only proxy
    def clash():
        p0 = result_json("group", "i396 proxyset", timeout=30); o["clash_proxy"] = p0
        try: n0 = len(open("fake-proxy.log").read().splitlines())
        except Exception: n0 = 0
        route("plus_routing", 5)
        found = False
        for _ in range(8):
            r = dump()
            if find(r, "开启 Clash（mihomo）", False): found = True; break
            sh("input swipe 540 1700 540 900 400"); time.sleep(0.8)
        if not found: page_missing.append("i396x clash: 「开启 Clash（mihomo）」 row not found"); return
        r = shot388("i396x-clash-off"); o["clash_off"] = _txt(r)[:300]
        tap_text("开启 Clash（mihomo）", wait=10)
        r = shot388("i396x-clash-on"); t = _txt(r); o["clash_on"] = t[:400]
        if "没有带内核" in t: selftest_fail.append("i396x clash: APK says it has no Clash core")
        sc = result_json("group", "i396 selfcheck", timeout=220); o["clash_selfcheck"] = sc
        if isinstance(sc, list):
            for row in sc:
                if "Clash" in (row.get("name") or ""):
                    print(f"I396X CLASH | {row.get('name')} | ok={row.get('ok')} | {row.get('detail')}", flush=True)
        try: log = open("fake-proxy.log").read().splitlines()[n0:]
        except Exception: log = []
        o["clash_proxy_log"] = log[-30:]
        print(f"I396X CLASHLOG {log[-12:]}", flush=True)
        rows = {r_.get("name"): r_ for r_ in sc} if isinstance(sc, list) else {}
        run = rows.get("Clash 内核运行"); ip = rows.get("Clash 出口 IP")
        if not run or not run.get("ok"):
            UNTESTABLE.append(f"Clash 内核运行 on x86_64 emulator (arm64 binary via translation): {run}")
        elif not ip or not ip.get("ok"): selftest_fail.append(f"i396x clash: core runs but no exit IP through it: {ip}")
        if run and run.get("ok") and not any(l.startswith("AUTH_OK") for l in log):
            selftest_fail.append("i396x clash: core runs but nothing reached the auth proxy with Proxy-Authorization")
        if any(l.startswith("AUTH_BAD") for l in log): print("I396X CLASH AUTH_BAD lines present", flush=True)
        route("plus_routing", 4)
        for _ in range(8):
            r = dump()
            if find(r, "开启 Clash（mihomo）", False): tap_text("开启 Clash（mihomo）", wait=3); break
            sh("input swipe 540 1700 540 900 400"); time.sleep(0.8)
        result_json("group", "i396 proxyoff", timeout=20)
    step("clash", clash)
    # 6 [i17] three-path server check: the runner's sshd (10.0.2.2:22) is up; a blackhole address must never be called
    #   「在线」, and a phone-side-only failure must never be 「服务器挂了」
    def reach():
        up = result_json("group", "i396 reach 10.0.2.2 22", timeout=60); o["reach_up"] = up
        dead = result_json("group", "i396 reach 10.255.255.1 22", timeout=90); o["reach_dead"] = dead
        print(f"I396X REACH | up {json.dumps(up, ensure_ascii=False)} | dead {json.dumps(dead, ensure_ascii=False)}", flush=True)
        if not (isinstance(up, dict) and up.get("kind") == "OK_DIRECT"): selftest_fail.append(f"i396x reach: runner sshd not 在线: {up}")
        if not (isinstance(dead, dict) and dead.get("kind") in ("DOWN", "UNKNOWN")): selftest_fail.append(f"i396x reach: blackhole verdict wrong: {dead}")
        if isinstance(dead, dict) and dead.get("kind") == "UNKNOWN" and dead.get("down"): selftest_fail.append("i396x reach: UNKNOWN flagged as down")
    step("reach", reach)
    r8["i396x"] = o
    print("I396X UNTESTABLE " + json.dumps(UNTESTABLE, ensure_ascii=False), flush=True)
    print("I396X RESULT " + json.dumps(o, ensure_ascii=False)[:12000], flush=True)


def b396m(res):
    """[38.9.6 i13] Memoh (account 「\\u0001\\u0001」 + 正常 bug: cleaned record, 要重新登录 without a session, __Host- cookie kept
    across restart, self-hosted refused password → secure login card once, right password → token), the login card light/dark,
    proxy with auth (自己填代理 user/password → local split proxy adds Proxy-Authorization, sandbox + Gmail through it),
    and the glass sheets: ☰, + 附件, 选择模型, chat + home input, slider at minimum over a busy photo, light + dark."""
    r8 = R8(res); o = {}
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i396m {name} error {e}")
        if not alive(): crashed.append(f"i396m {name}"); recover(f"i396m {name}")
    def flag(name, sfx):
        f = result_json("group", "i396 overlay", timeout=10); o.setdefault("overlay", []).append({"name": name, "theme": sfx, "flag": f})
        print(f"I396M OVERLAY | {sfx} | {name} | {json.dumps(f, ensure_ascii=False)[:220]}", flush=True)
        return f
    set_zh()
    # 1 Memoh: bad record from 38.9.5 → cleaned, not 正常
    def memoh_record():
        o["bad"] = result_json("group", "i396 memohbad", timeout=20)
        c = result_json("group", "i396 memohcheck", timeout=40); o["check1"] = c
        if isinstance(c, dict):
            if any("\\u0001" in x or "\u0001" in x for x in c.get("ids", [])): selftest_fail.append(f"i396m memoh: account still 「\\u0001」: {c}")
            if c.get("user"): selftest_fail.append(f"i396m memoh: memoh_user not cleaned: {c.get('user')!r}")
            if "正常" in c.get("status", []): selftest_fail.append(f"i396m memoh: 正常 with no password and no session: {c}")
        else: page_missing.append(f"i396m memohcheck: no result {str(c)[:80]}")
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); route("plus_keychain", 7)
            if tap_text("Memoh", wait=5):
                time.sleep(3); r = shot388(f"i396m-memoh-acct-{sfx}"); t = _txt(r); o["acct_" + sfx] = t[:400]
                if "\\u0001" in t: selftest_fail.append(f"i396m memoh {sfx}: 账号 still shows \\u0001")
                if "正常" in t: selftest_fail.append(f"i396m memoh {sfx}: 账号详情 shows 正常 without password / session")
                back(1)
            else: page_missing.append(f"i396m memoh {sfx}: Memoh row not on 账号 page")
        theme388(1)
    step("memoh-record", memoh_record)
    # 2 __Host- session cookie survives losing it + a restart (SessionKeeper restore without Domain=)
    def memoh_cookie():
        a = result_json("group", "i396 memohcookie set", timeout=20); o["ck_set"] = a
        b = result_json("group", "i396 memohcookie drop", timeout=20); o["ck_drop"] = b
        sh(f"am force-stop {PKG}"); time.sleep(1); relaunch(); time.sleep(6)
        c = result_json("group", "i396 memohcookie get", timeout=20); o["ck_after_restart"] = c
        if not (isinstance(a, dict) and a.get("cookie")): page_missing.append(f"i396m cookie: not set {a}")
        if not (isinstance(c, dict) and c.get("cookie")): selftest_fail.append(f"i396m cookie: __Host- Memoh session lost after restart {c}")
        d = result_json("group", "i396 memohcheck", timeout=40); o["check2"] = d
        if isinstance(d, dict) and "要重新登录" in d.get("status", []): selftest_fail.append(f"i396m memoh: 要重新登录 while the session cookie is there {d}")
    step("memoh-cookie", memoh_cookie)
    # 3 self-hosted: saved password refused → card asks once; right password → token
    def memoh_card():
        o["exp"] = result_json("group", "i396 memohexpire", timeout=20); time.sleep(5)
        t1 = result_json("group", "i396 memohtok", timeout=10); o["tok1"] = t1
        if not (isinstance(t1, dict) and t1.get("card")): selftest_fail.append(f"i396m memoh: no login card after the saved password was refused {t1}"); return
        r = dump(); o["card_text"] = _txt(r)[:200]
        if "密码不对" not in _txt(r): page_missing.append("i396m memoh card: 「密码不对，重新输入」 not shown")
        pb = node_bounds(r, lambda n: (n.get("content-desc") or "") == "密码")
        if pb:
            sh(f"input tap {(pb[0] + pb[2]) // 2} {(pb[1] + pb[3]) // 2}"); time.sleep(1); type_text("good396"); time.sleep(1)
            sh("input keyevent 111"); time.sleep(1)
            if not tap_text("登录", exact=True, wait=3): page_missing.append("i396m memoh card: 登录 not found")
            time.sleep(5); t2 = result_json("group", "i396 memohtok", timeout=10); o["tok2"] = t2
            if not (isinstance(t2, dict) and t2.get("tok") == "ok"): selftest_fail.append(f"i396m memoh: right password did not give a token {t2}")
        else: page_missing.append("i396m memoh card: 密码 field not found")
        try: o["fake_logins"] = json.load(urllib.request.urlopen("http://127.0.0.1:8088/stats", timeout=5)).get("memoh_logins")
        except Exception as e: o["fake_logins"] = f"err {e}"
    step("memoh-card", memoh_card)
    # 4 the login card (demo values; not FLAG_SECURE so it can be photographed) light + dark
    for mode, sfx in ((1, "light"), (2, "dark")):
        def card(sfx=sfx, mode=mode):
            theme388(mode); b381_home(); time.sleep(2)
            result_json("group", "i396 logincard refused", timeout=10); time.sleep(2.5)
            r = shot388(f"i396m-cred-{sfx}"); o["cred_" + sfx] = _txt(r)[:200]; back(1); time.sleep(1)
        step("cred-" + sfx, card)
    theme388(1)
    # 5 proxy with auth: 自己填代理 fields + the 自检 rows through the runner's auth-only proxy
    def proxy():
        p0 = result_json("group", "i396 proxyset", timeout=30); o["proxy"] = p0
        route("plus_routing", 5)
        for _ in range(3): sh("input swipe 540 1700 540 700 400"); time.sleep(0.8)
        r = shot388("i396m-routing"); t = _txt(r); o["routing"] = t[:400]
        for w in ("用户名", "密码"):
            if w not in t: page_missing.append(f"i396m routing: 自己填代理 lacks 「{w}」")
        if "proxy396" in t: selftest_fail.append("i396m routing: proxy password shown in clear text")
        sc = result_json("group", "i396 selfcheck", timeout=220); o["selfcheck_proxy"] = sc
        if isinstance(sc, list):
            for row in sc:
                if row.get("name") in ("沙箱代理出口", "Gmail 可达"): print(f"I396M PROXY | {row.get('name')} | ok={row.get('ok')} | {row.get('detail')}", flush=True)
        try: log = open("fake-proxy.log").read().splitlines()
        except Exception: log = []
        o["proxy_log"] = log[-40:]
        okl = [l for l in log if l.startswith("AUTH_OK")]; bad = [l for l in log if l.startswith("AUTH_BAD")]
        print(f"I396M PROXYLOG ok={len(okl)} bad={len(bad)} sample={okl[:6]}", flush=True)
        if _sandbox_down(sc) and not any("api.ipify.org" in l for l in okl): UNTESTABLE.append("沙箱代理出口: 沙箱还没启动 on the x86_64 emulator")
        elif not any("api.ipify.org" in l for l in okl): selftest_fail.append(f"i396m proxy: sandbox exit check never went through the auth proxy (ok={len(okl)}, bad={len(bad)})")
        if not any("google.com" in l for l in okl): selftest_fail.append("i396m proxy: Gmail check never went through the auth proxy")
        if bad and not okl: selftest_fail.append("i396m proxy: every request reached the proxy WITHOUT Proxy-Authorization")
        result_json("group", "i396 proxyoff", timeout=20)
    step("proxy", proxy)
    # 6 glass sheets light + dark (☰ / + 附件 / 选择模型 / inputs / slider minimum over a busy photo)
    def sheets(sfx):
        healthy_home(); seed_model()
        r = b381_home(); time.sleep(2); shot388(f"i396m-home-input-{sfx}")
        mb = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
        if mb: sh(f"input tap {(mb[0] + mb[2]) // 2} {(mb[1] + mb[3]) // 2}"); time.sleep(2); shot388(f"i396m-menu-{sfx}"); flag("menu", sfx); back(1); time.sleep(1)
        else: page_missing.append(f"i396m {sfx}: ☰ not found")
        if not find(dump(), "今天新加坡天气", False): result_json("group", "i396 seed", timeout=90); b381_home(); time.sleep(3)   # [i15] a chat to open
        if tap_text("今天新加坡天气", wait=4) or tap_text("你好，我是测试模型。", wait=3):
            time.sleep(2); shot388(f"i396m-chat-input-{sfx}")
            r = dump(); ab = node_bounds(r, lambda n: (n.get("content-desc") or "") == "附件")
            if ab: sh(f"input tap {(ab[0] + ab[2]) // 2} {(ab[1] + ab[3]) // 2}"); time.sleep(2); shot388(f"i396m-attach-{sfx}"); flag("attach", sfx); back(1); time.sleep(1)
            else: page_missing.append(f"i396m {sfx}: + 附件 not found")
            r = dump()
            if tap_text("默认模型", wait=3, root=r) or tap_text("smoke-model", wait=2, root=r) or tap_text("Default Models", wait=2, root=r):
                time.sleep(2); r = shot388(f"i396m-picker-{sfx}"); t = _txt(r); flag("picker", sfx)
                if "Default Models" in t: selftest_fail.append(f"i396m {sfx}: picker still shows 「Default Models」")
                back(1); time.sleep(1)
            else: page_missing.append(f"i396m {sfx}: model picker header not found")
            back(1); time.sleep(1)
        else: page_missing.append(f"i396m {sfx}: no chat to open")
        # worst case: busy photo wallpaper + glass slider at its minimum, ☰ over it
        selftest_json("uiprep", "杨arling|gen", timeout=20); result_json("group", "lookset 0.0|", timeout=10); time.sleep(2)
        r = b381_home(); time.sleep(2); shot388(f"i396m-worst-home-{sfx}")
        mb = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
        if mb:
            sh(f"input tap {(mb[0] + mb[2]) // 2} {(mb[1] + mb[3]) // 2}"); time.sleep(2.5); shot388(f"i396m-worst-menu-{sfx}")
            f = flag("worst-menu", sfx); back(1)
            import glob as _g
            hp = sorted(_g.glob(f"{OUT}/*i396m-worst-home-{sfx}.png")); mp = sorted(_g.glob(f"{OUT}/*i396m-worst-menu-{sfx}.png"))
            br = blur_ratio(hp[-1], mp[-1]) if hp and mp else -1.0
            o[f"worst_ratio_{sfx}"] = br
            print(f"I396M WORST | {sfx} | page-right-of-menu sharpness ratio {br} | flag {json.dumps(f, ensure_ascii=False)[:200]}", flush=True)
            # the page behind must be blurred (ratio well under 1) or, if the app could not prove the blur, the fill dense
            if br > 0.6 and not (isinstance(f, dict) and not f.get("ready") and "dense" in str(f.get("result"))):
                selftest_fail.append(f"i396m {sfx}: ☰ at glass 0 over photo — page behind still sharp (ratio {br}, flag {f})")
        else: page_missing.append(f"i396m {sfx}: ☰ not found for the worst case")
        result_json("group", "lookset reset", timeout=10); result_json("group", "wallpaper off", timeout=30)
    for mode, sfx in ((1, "light"), (2, "dark")):
        theme388(mode); step("sheets-" + sfx, lambda sfx=sfx: sheets(sfx))
    theme388(1)
    r8["i396m"] = o
    print("I396M RESULT " + json.dumps(o, ensure_ascii=False)[:8000], flush=True)


def b396(res):
    """[38.9.6 i12] user 2026-10-18: ☰ menu + progress card = one real glass surface (no inner white block, no solid card),
    暖瓷 tint, every chat has goal / steps / summary (new chat with automatic steps, plain Q&A, chat idle 1 day rebuilt from
    history), archive (one tap + 撤销, 3-day auto, 已归档 list, chat ⋯ menu), a dialog + another bottom sheet, light + dark;
    then the in-app 自检 (MinesSelfCheck) in both themes as a per-item table."""
    r8 = R8(res); o = {}
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i396 {name} error {e}")
        if not alive(): crashed.append(f"i396 {name}"); recover(f"i396 {name}")
    set_zh()
    if not I396X: o["seed"] = result_json("group", "i396 seed", timeout=90)   # [i15] b396x already seeded (a second seed duplicated every chat)
    result_json("group", "i396 sky cloudy static", timeout=20)   # [i15] static sky for the rest (swiftshader + live AGSL made long-press flaky)
    def row_pill(r, title):
        tb = node_bounds(r, lambda n: (n.get("text") or "").startswith(title))
        if not tb: return None, None
        ty = (tb[1] + tb[3]) // 2
        pills = [p for p in all_desc(r, "进度：") if p[1] >= tb[1] - 20 and p[1] <= tb[3] + 160]
        return (min(pills, key=lambda p: abs(p[1] - ty)) if pills else None), tb
    def sheet_for(title, tag, sfx, wants):
        r = b381_home(); time.sleep(3)
        p, tb = row_pill(r, title)
        if not p:
            page_missing.append(f"i396 {sfx}: no ⋯ pill on the row 「{title}」 (title {tb})"); return None
        sh(f"input tap {p[0]} {p[1]}"); time.sleep(3.5)
        r = shot388(f"i396-sheet-{tag}-{sfx}"); t = _txt(r); o[f"sheet_{tag}_{sfx}"] = t[:500]
        if "还没有步骤表" in t: selftest_fail.append(f"i396 {sfx}: 「还没有步骤表」 still on the {tag} card")
        for w in wants:
            if w not in t: selftest_fail.append(f"i396 {sfx}: {tag} card lacks 「{w}」: {t[:160]}")
        return t
    def pages(sfx):
        o["relive_" + sfx] = selftest("group", "i396 relive", timeout=30)[-80:]
        r = b381_home(); time.sleep(4); r = shot388(f"i396-home-{sfx}"); t = _txt(r); o["home_" + sfx] = t[:500]
        pills = all_desc(r, "进度："); o["pills_" + sfx] = len(pills)
        if len(pills) < 3: selftest_fail.append(f"i396 {sfx}: only {len(pills)} ⋯ progress pills on home (want one on every row)")
        if "上周的旧对话" in t: selftest_fail.append(f"i396 {sfx}: chat idle 5 days still on home (3-day auto archive)")
        # ☰ menu (centre tap)
        menu = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
        if menu: sh(f"input tap {(menu[0] + menu[2]) // 2} {(menu[1] + menu[3]) // 2}")
        time.sleep(2.5); r = shot388(f"i396-menu-{sfx}"); t = _txt(r); o["menu_" + sfx] = t[:300]
        if "AI 群聊" not in t: selftest_fail.append(f"i396 {sfx}: ☰ menu did not open: {t[:100]}")
        back(1); time.sleep(1)
        sheet_for("帮我领取免费云浏览器", "auto", sfx, ("问：帮我领取免费云浏览器", "打开注册页", "填写邮箱", "等邮件验证码"))
        back(1); time.sleep(1.5)
        sheet_for("今天新加坡天气", "qa", sfx, ("已回答", "答：多云"))
        back(1); time.sleep(1.5)
        t = sheet_for("整理服务器日志", "idle", sfx, ("问：帮我看看服务器日志", "答：已重启"))
        if t is not None and sfx == "light":
            if tap_text("归档", exact=True, wait=0.6):
                r0 = dump(); t0 = _txt(r0)   # [i15] the bar lives 4 s; a full shot388 (screencap + dump) can outlast it
                ub = node_bounds(r0, lambda n: (n.get("text") or "") == "撤销")
                r = shot388(f"i396-undo-{sfx}"); t2 = t0 + " " + _txt(r); o["undo_" + sfx] = t2[-300:]
                if "已归档这个对话" not in t2: selftest_fail.append(f"i396 {sfx}: no 「已归档 · 撤销」 toast after 归档")
                if t0.count("整理服务器日志") > 0 and "已归档这个对话" in t0: selftest_fail.append(f"i396 {sfx}: archived chat still on home")
                if ub: sh(f"input tap {(ub[0] + ub[2]) // 2} {(ub[1] + ub[3]) // 2}"); time.sleep(2)
                elif not tap_text("撤销", exact=True, wait=2): selftest_fail.append(f"i396 {sfx}: 撤销 not found")
                time.sleep(1.5); t3 = _txt(dump())
                if "整理服务器日志" not in t3: selftest_fail.append(f"i396 {sfx}: 撤销 did not bring the chat back")
            else: selftest_fail.append(f"i396 {sfx}: 归档 button not on the progress card"); back(1)
        elif t is not None: back(1)
        time.sleep(1.5)
        # 已归档 list via 全部 ›
        r = b381_home(); time.sleep(2)
        if tap_text("筛选对话", wait=2) or tap_text("全部", exact=True, wait=2):
            r = shot388(f"i396-filter-{sfx}"); t = _txt(r)
            if tap_text("已归档", wait=2.5):
                r = shot388(f"i396-archived-{sfx}"); t = _txt(r); o["archived_" + sfx] = t[:400]
                if "上周的旧对话" not in t: selftest_fail.append(f"i396 {sfx}: 已归档 list lacks the 5-day-old chat")
                p, _ = row_pill(r, "上周的旧对话")
                if p:
                    sh(f"input tap {p[0]} {p[1]}"); time.sleep(3.5); r = shot388(f"i396-sheet-old-{sfx}"); t = _txt(r); o["sheet_old_" + sfx] = t[:300]
                    if "取消归档" not in t: selftest_fail.append(f"i396 {sfx}: archived chat's card has no 取消归档")
                    back(1); time.sleep(1)
                tap_text("筛选对话", wait=2) and tap_text("全部", exact=True, wait=2)
            else: page_missing.append(f"i396 {sfx}: 已归档 not in the 全部 menu: {t[:120]}"); back(1)
        else: page_missing.append(f"i396 {sfx}: 全部 › filter not found")
        # long-press row → glass row menu (归档 item)
        r = b381_home(); time.sleep(2)
        tb = node_bounds(r, lambda n: (n.get("text") or "").startswith("今天新加坡天气"))
        if tb:
            cx, cy = (tb[0] + tb[2]) // 2, (tb[1] + tb[3]) // 2
            sh(f"input swipe {cx} {cy} {cx} {cy} 1600"); time.sleep(2)
            r = shot388(f"i396-rowmenu-{sfx}"); t = _txt(r); o["rowmenu_" + sfx] = t[:300]
            if "归档" not in t: selftest_fail.append(f"i396 {sfx}: long-press menu has no 归档")
            back(1); time.sleep(1)
        # dialog: home weather (MinesDialog through the glass AlertDialog)
        result_json("group", 'greet {"hour":15,"weather":"cloudy"}')
        r = b381_home(); time.sleep(3); w = all_desc(r, "天气：")
        if w:
            sh(f"input tap {w[0][0]} {w[0][1]}"); time.sleep(2.5); r = shot388(f"i396-dialog-{sfx}"); o["dialog_" + sfx] = _txt(r)[:200]
            back(1); time.sleep(1)
        else: page_missing.append(f"i396 {sfx}: no weather item for the dialog shot")
        # another bottom sheet: 账号 › ＋
        route("plus_keychain", 6); r = dump()
        a = all_desc(r, "添加：关联账号或添加身份")
        if a:
            sh(f"input tap {a[0][0]} {a[0][1]}"); time.sleep(2.5); r = shot388(f"i396-bsheet-{sfx}"); o["bsheet_" + sfx] = _txt(r)[:200]
            back(1); time.sleep(1)
        else: page_missing.append(f"i396 {sfx}: 账号 ＋ not found for the bottom-sheet shot")
        back(1); time.sleep(1)
        # chat ⋯ menu: 进度与步骤 + 归档 in every chat
        r = b381_home(); time.sleep(2)
        if tap_text("今天新加坡天气", wait=4):
            r = dump(); mb = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
            if mb: sh(f"input tap {(mb[0] + mb[2]) // 2} {(mb[1] + mb[3]) // 2}")
            time.sleep(2); r = shot388(f"i396-chatmenu-{sfx}"); t = _txt(r); o["chatmenu_" + sfx] = t[:300]
            for w_ in ("进度与步骤", "归档"):
                if w_ not in t: selftest_fail.append(f"i396 {sfx}: chat ⋯ menu lacks {w_}")
            if tap_text("进度与步骤", wait=3):
                r = shot388(f"i396-chatsheet-{sfx}"); t = _txt(r); o["chatsheet_" + sfx] = t[:300]
                if "已回答" not in t: selftest_fail.append(f"i396 {sfx}: chat 进度与步骤 card lacks the summary")
                back(1); time.sleep(1)
            else: back(1)
            back(1); time.sleep(1)
        else: page_missing.append(f"i396 {sfx}: Q&A chat row not found")
    theme388(1)
    step("light", lambda: pages("light"))
    theme388(2)
    step("dark", lambda: pages("dark"))
    theme388(1)
    # every listed chat has a table after the background pass (old / idle chats included)
    selftest("group", "i396 backfill", timeout=60)
    st = result_json("group", "i396 state", timeout=30); o["state"] = st
    if isinstance(st, list):
        no = [x.get("title") for x in st if not x.get("has") or not x.get("line")]
        if no: selftest_fail.append(f"i396: chats without a progress table/summary after backfill: {no[:6]}")
        old = [x for x in st if x.get("title", "").startswith("上周的旧对话")]
        if old and not old[0].get("archived"): selftest_fail.append("i396: 5-day-old chat not auto-archived")
    # in-app 自检, both themes
    for mode, sfx in ((1, "light"), (2, "dark")):
        theme388(mode)
        sc = result_json("group", "i396 selfcheck", timeout=200); o["selfcheck_" + sfx] = sc
        if isinstance(sc, list):
            for row in sc: print(f"I396 SELFCHECK {sfx} | {'PASS' if row.get('ok') else ('WARN' if row.get('warn') else 'FAIL')} | {row.get('name')} | {row.get('detail')}", flush=True)
        else: page_missing.append(f"i396 自检 {sfx}: no result ({str(sc)[:80]})")
    theme388(1)
    r8["i396"] = o
    print("I396 RESULT " + json.dumps(o, ensure_ascii=False)[:8000], flush=True)


def b397(res, sub=False):
    """[38.9.7] user's phone 1080x2412 / 480 dpi, custom avatar. Home weather skin + greeting 「N 件事等你」 tap (light + dark),
    应用代理 Manus 去登录 (no FATAL, never a bare white page) + Memoh 填 key, 无痕标签 open/close, browser takeover (home browser
    page → an empty chat pool's agent tab), 高级工程模式 switch, SkillHub market without desktop / panel rows, one DSH call, 自检."""
    o = res.setdefault("i397", {})
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i397 {name} error {e}")
        if not alive(): crashed.append(f"i397 {name}"); recover(f"i397 {name}")
    def shot(name):
        r = snap("i397-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i397-{name}.png {OUT}/i397-{name}.png", shell=True)
        return r
    def png(name): return f"{OUT}/i397-{name}.png"
    print("I397 screen", sh("wm size").strip(), sh("wm density").strip(), flush=True)
    set_zh()
    o["seedmodel"] = seed_model()
    o["avatar"] = selftest_json("uiprep", "杨arling|gen", timeout=20)
    try: result_json("group", "wallpaper off")
    except Exception: pass
    o["seed"] = result_json("group", "i396 seed", timeout=90)
    o["wait"] = result_json("group", "i397 wait", timeout=60)
    def home(sfx):
        result_json("group", "i396 sky auto static", timeout=20)
        r = b381_home(); time.sleep(4); r = shot(f"home-{sfx}")
        o[f"sky_{sfx}"] = result_json("group", "i396 skystate", timeout=10)
        w = all_desc(r, "天气：") or all_desc(r, "天气")
        o[f"weather_{sfx}"] = [d for _, _, d in w][:2]
        if not w: selftest_fail.append(f"i397 home {sfx}: no weather chip on home")
        g = node_bounds(r, lambda n: "点按看是哪" in (n.get("content-desc") or ""))   # [i21b] greeting semantics: 「…。点按看是哪 N 件」
        if not g: g = node_bounds(r, lambda n: "件事等你" in (n.get("text") or "") or "件事等你" in (n.get("content-desc") or ""))
        if not g: selftest_fail.append(f"i397 home {sfx}: greeting has no 「件事等你」 (seeded 2 waiting)"); return
        sh(f"input tap {(g[0] + g[2]) // 2} {(g[1] + g[3]) // 2}"); time.sleep(2.5)
        r = shot(f"waiting-{sfx}"); t = _txt(r); o[f"waiting_{sfx}"] = t[:300]
        if "注册免费云服务器" not in t or "绑定 GitHub 账号" not in t: selftest_fail.append(f"i397 greeting tap {sfx}: list lacks the 2 waiting chats")
        if tap_text("注册免费云服务器", wait=4):
            r = dump(); o[f"waitopen_{sfx}"] = _txt(r)[:160]; back(1)
        else: back(1)
    def agents(sfx):
        o["agents_add"] = [result_json("group", "agents add web:manus", timeout=10), result_json("group", "agents add memoh", timeout=10)]
        route("plus_app_agents", 6); r = shot(f"agents-{sfx}")
        mb = node_bounds(r, lambda n: (n.get("text") or "") == "Manus")
        pills = [(x, y) for t, d, x, y, n in nodes(r) if (t or d) == "去登录" and mb and -40 < y - (mb[1] + mb[3]) // 2 < 280]
        if not pills: page_missing.append(f"i397 agents {sfx}: Manus 去登录 not found"); 
        else:
            sh(f"input tap {pills[0][0]} {pills[0][1]}"); time.sleep(6); shot(f"manus-6s-{sfx}")
            time.sleep(10); r = shot(f"manus-16s-{sfx}")
            if not alive(): crashed.append(f"i397 manus {sfx}"); recover("manus"); return
            fl = _flat(png(f"manus-16s-{sfx}")); lum = _lum(png(f"manus-16s-{sfx}")); t = _txt(r)
            o[f"manus_{sfx}"] = {"flat": fl, "lum": lum, "card": "页面没打开" in t, "text": t[:200]}
            if fl is not None: selftest_fail.append(f"i397 Manus 去登录 {sfx}: flat screen (sd {fl}, lum {lum})")
            back(1); time.sleep(1)
            r = dump()
            if r is not None and not find(r, "Memoh"): route("plus_app_agents", 5); r = dump()
        mb = node_bounds(r, lambda n: (n.get("text") or "") == "Memoh")
        pills = [(x, y) for t, d, x, y, n in nodes(r) if (t or d) in ("填 key", "去登录") and mb and -40 < y - (mb[1] + mb[3]) // 2 < 280]
        if not pills: page_missing.append(f"i397 agents {sfx}: Memoh 填 key not found"); return
        sh(f"input tap {pills[0][0]} {pills[0][1]}"); time.sleep(3); r = shot(f"memoh-{sfx}"); t = _txt(r)
        o[f"memoh_{sfx}"] = t[:200]
        if not alive(): crashed.append(f"i397 memoh {sfx}"); recover("memoh"); return
        if "地址" not in t: selftest_fail.append(f"i397 Memoh 填 key {sfx}: address field not shown")
        back(1); time.sleep(1); back(1)
    def incog():
        r = b381_home(); time.sleep(2)
        mb = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
        if mb: sh(f"input tap {(mb[0] + mb[2]) // 2} {(mb[1] + mb[3]) // 2}"); time.sleep(2.5)
        if not tap_text("无痕标签", wait=1.5):   # [i22] no back(): it sent Mines to the background in smoke 39140538050
            shot("incog-menu"); selftest_fail.append("i397 无痕标签 not in ☰ menu"); b381_home(); return
        r = dump(); time.sleep(4); r = shot("incog-open"); t = _txt(r)
        st = result_json("group", "i397 incog", timeout=10); o["incog_state"] = st
        sup = isinstance(st, dict) and st.get("supported")
        o["incog_text"] = t[:200]
        if sup and "无痕" not in t: selftest_fail.append("i397 无痕: supported but sheet title lacks 无痕")
        if not sup: UNTESTABLE.append("无痕标签: emulator WebView has no MULTI_PROFILE → refused with the update message (by design)")
        if sup:
            browser_url = "https://example.com"
            r = dump(); ed_ = next(((x, y) for tt, d, x, y, n in nodes(r) if n.get("class", "").endswith("EditText")), None)
            if ed_:
                sh(f"input tap {ed_[0]} {ed_[1]}"); time.sleep(1); sh("input keycombination 113 29"); sh("input keyevent KEYCODE_DEL")
                sh(f"input text '{browser_url}'"); sh("input keyevent 66"); time.sleep(8); shot("incog-page")
            back(1); time.sleep(2); shot("incog-closed")
            o["incog_after"] = result_json("group", "i397 incog", timeout=10)
            h = result_json("group", "i397 handoff", timeout=10); o["incog_handoff"] = h
            if isinstance(h, dict) and "example.com" in (h.get("last") or "") and not o.get("takeover_done"):
                selftest_fail.append("i397 无痕 page was recorded for handoff")
    def takeover():
        browser_open("https://example.com/?mines397", "i397")
        shot("browser-user"); back(1); time.sleep(2)
        h = result_json("group", "i397 handoff", timeout=10); o["handoff"] = h
        if not (isinstance(h, dict) and "example.com" in (h.get("last") or "")): selftest_fail.append(f"i397 takeover: user page not recorded ({str(h)[:120]})")
        a = result_json("group", "i397 agentbrowse", timeout=70); o["agentbrowse"] = a
        if not (isinstance(a, dict) and a.get("adopted") == 1):   # [i21b] adoption is the check; url only logged
            print(f"I397 TAKEOVER url={(a or {}).get('url') if isinstance(a, dict) else a}", flush=True)
            selftest_fail.append(f"i397 takeover: agent tab did not open the user's page ({str(a)[:160]})")
        o["takeover_done"] = True
    def engineer():
        route("agent_tools", 5)
        for _ in range(6):
            r = dump()
            if find(r, "高级工程模式"): break
            scroll_down()
        r = shot("engineer-on"); e0 = result_json("group", "i397 engineer", timeout=10)
        if not find(r, "高级工程模式"): selftest_fail.append("i397 高级工程模式 switch not found"); return
        p = find(r, "高级工程模式"); sh(f"input tap {p[0]} {p[1]}"); time.sleep(2)
        sw = [(x, y) for t, d, x, y, n in nodes(dump()) if n.get("checkable") == "true" and abs(y - p[1]) < 160]
        e1 = result_json("group", "i397 engineer", timeout=10)
        if isinstance(e1, dict) and e1.get("on") == (e0 or {}).get("on") and sw: sh(f"input tap {sw[0][0]} {sw[0][1]}"); time.sleep(2); e1 = result_json("group", "i397 engineer", timeout=10)
        shot("engineer-off")
        o["engineer"] = [e0, e1]
        if not (isinstance(e0, dict) and e0.get("on") is True): selftest_fail.append(f"i397 高级工程模式 not on by default ({e0})")
        if not (isinstance(e1, dict) and e1.get("on") is False): selftest_fail.append(f"i397 高级工程模式 switch did not turn it off ({e1})")
        result_json("group", "i397 engineer on", timeout=10); back(1)
    def market():
        route("plus_market", 12); r = dump(); t = _txt(r)
        for _ in range(3):
            if "手机能用" in t or "共" in t: break
            time.sleep(6); r = dump(); t = _txt(r)
        r = shot("market"); t = _txt(r); o["market"] = t[:400]
        if "手机能用" not in t: page_missing.append("i397 market: header 「N 个手机能用」 not seen")
        bad = [w for w in ("电脑版", "宿主", "资料库") if w in t]
        if bad: selftest_fail.append(f"i397 market lists non-runnable rows: {bad}")
        scroll_down(); scroll_down(); r = shot("market-2"); t2 = _txt(r)
        bad2 = [w for w in ("电脑版", "宿主", "资料库") if w in t2]
        if bad2: selftest_fail.append(f"i397 market (scrolled) lists non-runnable rows: {bad2}")
        btn = next(((x, y, tt or d) for tt, d, x, y, n in nodes(dump()) if (tt or d) in ("试装", "安装")), None)
        if btn:
            sh(f"input tap {btn[0]} {btn[1]}"); time.sleep(4); shot("market-install-tap")
            if tap_text("安装", exact=True, wait=3) or tap_text("试装", exact=True, wait=3): pass
            time.sleep(60); r = shot("market-install-60s"); o["install"] = {"btn": btn[2], "text": _txt(r)[:300]}
        else: page_missing.append("i397 market: no 安装 / 试装 button")
        back(1)
    def dsh():
        out = selftest("offload", "minis-dsh calltest", timeout=150); o["dsh_calltest"] = out.strip()[-400:]
        if "echo:hi" not in out: UNTESTABLE.append("DSH 插件调用 (minis-dsh calltest) in the emulator sandbox: " + out.strip()[-120:])
    def selfcheck():
        rows = result_json("group", "i396 selfcheck", timeout=170); o["selfcheck"] = rows
        names = ("浏览器接管", "无痕标签", "高级工程模式", "DSH 插件调用", "首页天气", "天气皮肤", "字号与缩放", "网页登录诊断")
        if isinstance(rows, list):
            for rw in rows:
                if rw.get("name") in names: print(f"I397 SELFCHECK | {rw.get('name')} | ok={rw.get('ok')} | {rw.get('detail')}", flush=True)
            got = {rw.get("name") for rw in rows}
            miss = [n for n in names if n not in got]
            if miss: selftest_fail.append(f"i397 自检 rows missing: {miss}")
        else: selftest_fail.append(f"i397 自检 did not answer ({str(rows)[:80]})")
    if sub:   # [38.9.8] only the 38.9.7 gaps: Manus 去登录 / Memoh 填 key (both themes) + 无痕标签 entry
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home(); step("agents-" + sfx, lambda sfx=sfx: agents(sfx))
        theme388(1); healthy_home(); step("incog", incog)
        print("I397 RESULT " + json.dumps(o, ensure_ascii=False)[:4000], flush=True)
        return
    for mode, sfx in ((1, "light"), (2, "dark")):
        theme388(mode); healthy_home()
        step("home-" + sfx, lambda sfx=sfx: home(sfx))
        step("agents-" + sfx, lambda sfx=sfx: agents(sfx))
    theme388(1); healthy_home()
    step("takeover", takeover)
    def ssh():
        o["ssh_local_ed25519"] = result_json("group", "i39112 sshprobe 10.0.2.2 22", timeout=60)
        o["ssh_instabox"] = result_json("group", "i39112 sshprobe api.instabox.sh 2222", timeout=60)
        print("I39112 SSH", o["ssh_local_ed25519"], "|", o["ssh_instabox"], flush=True)
        if "Algorithm negotiation fail" in str(o["ssh_local_ed25519"]): selftest_fail.append(f"i39112 ssh-ed25519-only sshd: {o['ssh_local_ed25519']}")

    step("incog", incog)
    step("ssh", ssh)
    step("engineer", engineer)
    step("market", market)
    step("dsh", dsh)
    step("selfcheck", selfcheck)
    print("I397 RESULT " + json.dumps(o, ensure_ascii=False)[:6000], flush=True)


def b398(res):
    """[38.9.8] 选择模型 with a 10-model 「免登录 AI（Mines）」 card (two 「Mines-Test」 bots) light + dark; 快速测试 stacked over it
    (solid, page below dimmed) against a server that never answers → must stop with a real reason (≤ 60 s + margin);
    home 未完成 marker, 工作流记录 page, 自检 rows; then the 38.9.7 gaps (Manus / Memoh pills, 无痕 entry)."""
    o = res.setdefault("i398", {})
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i398 {name} error {e}")
        if not alive(): crashed.append(f"i398 {name}"); recover(f"i398 {name}")
    def shot(name):
        r = snap("i398-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i398-{name}.png {OUT}/i398-{name}.png", shell=True)
        return r
    print("I398 screen", sh("wm size").strip(), sh("wm density").strip(), flush=True)
    set_zh()
    o["seedmodel"] = seed_model()
    try: result_json("group", "wallpaper off")
    except Exception: pass
    o["seed"] = result_json("group", "i398 seedpicker", timeout=30)
    print("I398 SEED", o["seed"], flush=True)
    def picker(sfx):
        o[f"open_{sfx}"] = result_json("group", "i398 picker", timeout=20); time.sleep(5)
        r = shot(f"picker-{sfx}"); t = _txt(r)
        for _ in range(4):
            if "免登录" in t: break
            scroll_down(); time.sleep(1); r = shot(f"picker-{sfx}"); t = _txt(r)
        o[f"picker_{sfx}"] = t[:600]
        if "免登录" not in t: selftest_fail.append(f"i398 picker {sfx}: 免登录 AI（Mines） card not seen")
        names = [x for x in ((tt or d) for tt, d, _, _, _ in nodes(r)) if x and "Mines-Test" in x]
        o[f"minestest_{sfx}"] = names
        print(f"I398 MINES-TEST {sfx} {names}", flush=True)
        if len(set(names)) < 2 and len(names) >= 2: selftest_fail.append(f"i398 picker {sfx}: two Mines-Test rows look the same {names}")
        scroll_down(); time.sleep(1); r = shot(f"picker-end-{sfx}"); o[f"picker_end_{sfx}"] = _txt(r)[:300]
        back(1); time.sleep(1.5); back(1); time.sleep(1)
    def quick():
        o["quick_open"] = result_json("group", "i398 picker coze:7696936574432591908", timeout=20)
        time.sleep(7); r = shot("quick-3s"); t = _txt(r); o["quick_3s"] = t[:400]
        if "快速测试" not in t: selftest_fail.append(f"i398 quick test sheet did not open over 选择模型 ({t[:120]})")
        ov = result_json("group", "i396 overlay", timeout=10); o["quick_overlay"] = ov
        t0 = time.time(); done = False
        while time.time() - t0 < 100:
            time.sleep(10); r = dump(); t = _txt(r)
            if "测试中" not in t and "快速测试" in t: done = True; break
        r = shot("quick-end"); t = _txt(r); o["quick_end"] = t[:500]; o["quick_secs"] = int(time.time() - t0) + 7
        o["silent"] = result_json("group", "i398 silent", timeout=10)
        print(f"I398 QUICK done={done} secs={o['quick_secs']} silent={o['silent']} text={t[:300]}", flush=True)
        if not done: selftest_fail.append(f"i398 quick test still 测试中… after {o['quick_secs']} s")
        elif not any(w in t for w in ("超时", "没有回应", "没回", "秒", "失败")): selftest_fail.append(f"i398 quick test ended without a reason ({t[:160]})")
        back(1); time.sleep(1.5); back(1); time.sleep(1); back(1)
    def marker():
        r = b381_home(); time.sleep(3); r = shot("home"); 
        b = node_bounds(r, lambda n: "未完成的事" in (n.get("content-desc") or ""))
        o["marker"] = [d for _, _, d in all_desc(r, "未完成的事")][:1]
        if not b: page_missing.append("i398 home: 未完成 marker not seen (seeded 2 waiting chats)"); return
        sh(f"input tap {(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}"); time.sleep(2.5); r = shot("unfinished"); o["unfinished"] = _txt(r)[:300]; back(1)
    def workflows():
        route("plus_workflows", 5); r = shot("workflows"); t = _txt(r); o["workflows"] = t[:300]
        if "工作流" not in t: page_missing.append(f"i398 工作流记录 page not seen ({t[:100]})")
        back(1)
    def clash():   # 订阅 / 正在启动… state with a real line (emulator: no phone Clash app)
        route("plus_routing", 5); r = dump()
        for _ in range(6):
            if find(r, "Clash"): break
            scroll_down(); time.sleep(1); r = dump()
        r = shot("clash"); o["clash"] = _txt(r)[:400]
        if "正在启动" in _txt(r): time.sleep(20); r = shot("clash-20s"); o["clash_20s"] = _txt(r)[:300]
        back(1)
    def selfcheck():
        rows = result_json("group", "i396 selfcheck", timeout=240); o["selfcheck"] = rows
        names = ("Clash 订阅", "免登录 AI 实测", "自我地图", "技能匹配", "工作流记录", "浏览器接管", "无痕标签")
        if isinstance(rows, list):
            for rw in rows:
                if rw.get("name") in names: print(f"I398 SELFCHECK | {rw.get('name')} | ok={rw.get('ok')} | {rw.get('detail')}", flush=True)
            miss = [n for n in names if n not in {rw.get("name") for rw in rows}]
            if miss: selftest_fail.append(f"i398 自检 rows missing: {miss}")
        else: selftest_fail.append(f"i398 自检 did not answer ({str(rows)[:80]})")
    for mode, sfx in ((2, "dark"), (1, "light")):
        theme388(mode); healthy_home(); step("picker-" + sfx, lambda sfx=sfx: picker(sfx))
    theme388(2); healthy_home(); step("quick", quick)
    theme388(1); healthy_home()
    o["wait"] = result_json("group", "i397 wait", timeout=60)
    step("marker", marker)
    step("workflows", workflows)
    step("clash", clash)
    step("selfcheck", selfcheck)
    print("I398 RESULT " + json.dumps(o, ensure_ascii=False)[:6000], flush=True)
    try: b397(res, sub=True)
    except Exception as e:
        import traceback; traceback.print_exc(); page_missing.append(f"i398 b397 sub error {e}")


def b398b(res):
    """[38.9.8 b] user 01:17–01:22 phone shots: 模型详情 (back arrow not 「耳」, no bright bands, last footer line above the
    gesture bar, backdrop = current sky scene) dark + light; 市场 (Chinese descriptions, 「手机能用」 chip, 「只能电脑用」 group,
    dsh-pilot note + 一键安装 off); failed install card solid with the Chinese reason first; quick test timeout re-check."""
    o = res.setdefault("i398b", {})
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i398b {name} error {e}")
        if not alive(): crashed.append(f"i398b {name}"); recover(f"i398b {name}")
    def shot(name):
        r = snap("i398-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i398-{name}.png {OUT}/i398-{name}.png", shell=True)
        return r
    set_zh()
    o["seedmodel"] = seed_model()
    o["seed"] = result_json("group", "i398 seedpicker", timeout=30)
    o["classify"] = result_json("group", "i398 classify", timeout=10)
    print("I398B CLASSIFY", o["classify"], flush=True)
    if not (isinstance(o["classify"], dict) and o["classify"].get("desktopOnly") and not o["classify"].get("canTry")): selftest_fail.append(f"i398b dsh-pilot not desktop-only {o['classify']}")
    def detail(sfx):
        o[f"detail_{sfx}"] = result_json("group", "i398 modeldetail", timeout=15); time.sleep(5)
        r = shot(f"detail-{sfx}"); t = _txt(r); o[f"detail_top_{sfx}"] = t[:200]
        o[f"backdrop_{sfx}"] = result_json("group", "i398 backdrop", timeout=10)
        print(f"I398B DETAIL {sfx} backdrop={o[f'backdrop_{sfx}']} text={t[:160]}", flush=True)
        for _ in range(6): scroll_down(); time.sleep(0.6)
        time.sleep(1.5); r = shot(f"detail-end-{sfx}"); o[f"detail_end_{sfx}"] = _txt(r)[-200:]
        back(1); time.sleep(1.5)
    def market():
        route("plus_market", 12); r = dump(); t = _txt(r)
        for _ in range(4):
            if "手机能用" in t: break
            time.sleep(6); r = dump(); t = _txt(r)
        time.sleep(25)   # translations land (one batched call to the configured model)
        r = shot("market"); t = _txt(r); o["market"] = t[:500]
        o["zh"] = result_json("group", "i398 zh", timeout=10); print("I398B ZH", o["zh"], flush=True)
        if tap_text("手机能用", exact=True, wait=3):
            for _ in range(10):
                r = dump()
                if find(r, "只能电脑用"): break
                scroll_down(); time.sleep(1)
            r = shot("market-desktop"); t = _txt(r); o["market_desktop"] = t[:500]
            if "只能电脑用" not in t: page_missing.append("i398b market: 「只能电脑用」 group not seen")
            hit = False
            for _ in range(10):
                if tap_text("dsh-pilot", wait=3): hit = True; break
                scroll_down(); time.sleep(1)
            if hit:
                r = shot("pilot"); t = _txt(r); o["pilot"] = t[:400]
                btn = next((n for tt, d, x, y, n in nodes(r) if (tt or d) == "一键安装"), None)
                o["pilot_btn_enabled"] = (btn or {}).get("enabled")
                print(f"I398B PILOT enabled={o['pilot_btn_enabled']} text={t[:240]}", flush=True)
                if "手机上装了也用不了" not in t: selftest_fail.append("i398b dsh-pilot dialog lacks the 只能电脑用 note")
                back(1); time.sleep(1)
            else: page_missing.append("i398b market: dsh-pilot not listed (SkillHub list may have changed)")
        else: page_missing.append("i398b market: 手机能用 chip not found")
        back(1); time.sleep(1)
    def jobfail():
        r = b381_home(); time.sleep(2)
        o["jobfail"] = result_json("group", "i398 jobfail", timeout=10); time.sleep(12)
        r = shot("jobfail"); t = _txt(r); o["jobfail_text"] = t[:300]
        if tap_text("详情", exact=True, wait=2): time.sleep(1.5); shot("jobfail-details")
        print(f"I398B JOBFAIL {t[:240]}", flush=True)
        if "没成功" not in t: page_missing.append("i398b failure card not seen")
        tap_text("好", exact=True, wait=2)
    def quick():
        o["quick_open"] = result_json("group", "i398 picker coze:7696936574432591908", timeout=20)
        time.sleep(7); shot("quick-3s-b")
        t0 = time.time(); t = ""
        while time.time() - t0 < 100:
            time.sleep(10); t = _txt(dump())
            if "测试中" not in t and "快速测试" in t: break
        r = shot("quick-end-b"); t = _txt(r); o["quick_end"] = t[:300]; o["quick_secs"] = int(time.time() - t0) + 7
        o["silent"] = result_json("group", "i398 silent", timeout=10)
        print(f"I398B QUICK secs={o['quick_secs']} silent={o['silent']} text={t[:240]}", flush=True)
        if "测试中" in t: selftest_fail.append(f"i398b quick test still 测试中… after {o['quick_secs']} s")
        back(1); time.sleep(1.5); back(1); time.sleep(1); back(1)
    for mode, sfx in ((2, "dark"), (1, "light")):
        theme388(mode); healthy_home(); step("detail-" + sfx, lambda sfx=sfx: detail(sfx))
    theme388(2); healthy_home()
    def clash():
        route("plus_routing", 5)
        for _ in range(10):
            r = dump()
            if find(r, "内置 Clash 内核"): break
            scroll_down(); time.sleep(0.8)
        scroll_down(); time.sleep(1); r = shot("clash-b"); o["clash"] = _txt(r)[:400]; back(1)
    step("market", market)
    step("clash", clash)
    step("jobfail", jobfail)
    step("quick", quick)
    print("I398B RESULT " + json.dumps(o, ensure_ascii=False)[:6000], flush=True)


def b399(res):
    """[38.9.9] 服务商 / 模型组 rows (no bleed-through at rest, swipe reveals, delete dialog), 选择模型 + stacked 快速测试 (no
    white line, uniform dim), sky 晴 / 多云 / 阴 / no-data on home, chat page sky, 免登录 AI page from the home ☰ drawer,
    quick test on an OAuth provider (Copilot, never signed in → a real Chinese reason), copy UX (footer 复制, one long-press sheet)."""
    o = res.setdefault("i399", {})
    def step(name, f):
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i399 {name} error {e}")
        if not alive(): crashed.append(f"i399 {name}"); recover(f"i399 {name}")
    def shot(name):
        r = snap("i399-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i399-{name}.png {OUT}/i399-{name}.png", shell=True)
        return r
    print("I399 screen", sh("wm size").strip(), sh("wm density").strip(), flush=True)
    set_zh()
    o["seedmodel"] = seed_model()
    try: result_json("group", "wallpaper off")
    except Exception: pass
    o["seed"] = result_json("group", "i398 seedpicker", timeout=30)
    o["seedoauth"] = result_json("group", "i398 seedoauth", timeout=30)
    print("I399 SEED", o["seed"], o["seedoauth"], flush=True)
    result_json("group", "i396 sky sunny static", timeout=20)

    def swipe_page(route_name, tag, row_pred):
        route(route_name, 5); r = shot(f"{tag}-rest"); t = _txt(r)
        o[f"{tag}_rest"] = t[:500]
        if "删除" in t: selftest_fail.append(f"i399 {tag}: 删除 visible at rest (bleed-through)")
        b = node_bounds(r, row_pred)
        if not b:
            for _ in range(3):
                scroll_down(); time.sleep(1); r = dump(); b = node_bounds(r, row_pred)
                if b: break
        if not b: page_missing.append(f"i399 {tag}: no row to swipe"); back(1); return
        # [i26] start on the row's right side (not the end of a short text node) and pull a full reveal
        y = (b[1] + b[3]) // 2; x2 = (SCREEN[0] - 100) if SCREEN[0] else b[2] - 20
        sh(f"input swipe {x2} {y} {max(40, x2 - 560)} {y} 400"); time.sleep(1.5)
        o[f"{tag}_probe"] = result_json("group", "i398 swipeprobe", timeout=10); print(f"I399 SWIPEPROBE {tag} {o[f'{tag}_probe']}", flush=True)
        r = shot(f"{tag}-swiped"); t = _txt(r); o[f"{tag}_swiped"] = t[:300]
        if "删除" not in t: selftest_fail.append(f"i399 {tag}: swipe did not reveal 删除")
        else:
            tap_text("删除", exact=True, wait=2); time.sleep(1.5)
            r = shot(f"{tag}-delete-dialog"); t = _txt(r); o[f"{tag}_dialog"] = t[:300]
            o[f"{tag}_dialog_probe"] = result_json("group", "i398 swipeprobe", timeout=10)
            if "取消" not in t: selftest_fail.append(f"i399 {tag}: 删除 tap showed no confirmation dialog ({t[:100]} | probe {o[f'{tag}_dialog_probe']})")
            tap_text("取消", exact=True, wait=2); time.sleep(1)
            # [i27] the other action: swipe again, tap 编辑 → its detail page opens (the list's add button is gone)
            r = dump(); b2 = node_bounds(r, row_pred) if r is not None else None
            if b2:
                y2 = (b2[1] + b2[3]) // 2
                sh(f"input swipe {x2} {y2} {max(40, x2 - 560)} {y2} 400"); time.sleep(1.5)
                if tap_text("编辑", exact=True, wait=2):
                    time.sleep(2); r = shot(f"{tag}-edit-opened"); t = _txt(r); o[f"{tag}_edit"] = t[:300]
                    o[f"{tag}_edit_probe"] = result_json("group", "i398 swipeprobe", timeout=10)
                    if any(k in t for k in ("添加 AI 服务商", "新建分组")): selftest_fail.append(f"i399 {tag}: 编辑 tap did not open the detail page ({t[:100]})")
                    back(1); time.sleep(1.5)
                else: page_missing.append(f"i399 {tag}: 编辑 not visible on the second swipe")
        back(1); time.sleep(1)
    def providers():
        swipe_page("providers", "providers", lambda n: "免登录 AI" in ((n.get("text") or "") + (n.get("content-desc") or "")))
    def groups():
        swipe_page("model_groups", "groups", lambda n: "个模型" in ((n.get("text") or "") + (n.get("content-desc") or "")))
    def picker():
        o["open"] = result_json("group", "i398 picker", timeout=20); time.sleep(5)
        r = shot("picker"); o["picker"] = _txt(r)[:300]
        back(1); time.sleep(1.5); back(1); time.sleep(1)
        o["quick_open"] = result_json("group", "i398 picker coze:7696936574432591908", timeout=20); time.sleep(6)
        r = shot("quick-stacked"); t = _txt(r); o["quick_stacked"] = t[:300]
        if "快速测试" not in t: selftest_fail.append(f"i399 quick test sheet not over 选择模型 ({t[:100]})")
        if "· 1908" not in t: selftest_fail.append(f"i399 stacked quick test title lacks 「· 1908」 ({t[:120]})")
        o["overlay"] = result_json("group", "i396 overlay", timeout=10)
        back(1); time.sleep(1.5); back(1); time.sleep(1); back(1)
    def oauth_quick():
        o["oq_open"] = result_json("group", "i398 picker gpt-4.1", timeout=20); time.sleep(5)
        t0 = time.time(); t = ""
        while time.time() - t0 < 75:
            time.sleep(6); t = _txt(dump())
            if "测试中" not in t and "快速测试" in t: break
        r = shot("oauth-quick"); t = _txt(r); o["oauth_quick"] = t[:400]
        print(f"I399 OAUTH QUICK {t[:300]}", flush=True)
        if "还没配置 API Key" in t: selftest_fail.append("i399 OAuth quick test still says 还没配置 API Key")
        if not any(w in t for w in ("Copilot", "GitHub", "登录")): page_missing.append(f"i399 OAuth quick test: no Copilot/GitHub reason seen ({t[:120]})")
        back(1); time.sleep(1.5); back(1); time.sleep(1); back(1)
    def sky():
        # [i26] sky v6 = graded photo per weather: every scene light + dark (still frame), then live frames of 晴 seconds and
        # minutes apart (skyclock shifts the shot clock: +0 / +6 s / +150 s / +300 s = next shot)
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode)
            for k in ("sunny", "cloudy", "overcast", "rain", "fog", "sunset", "night", "snow"):
                st = result_json("group", f"i396 sky {k} static", timeout=20); b381_home(); time.sleep(3); shot(f"sky-{k}-{sfx}")
                o[f"sky_{k}_{sfx}"] = st
                if not (isinstance(st, dict) and st.get("scene") == k): selftest_fail.append(f"i399 sky {sfx}: forced {k} but {st}")
                # [i27] 阴 must use the overcast photo, 雾 the fog photo (were identical)
                exp = {"overcast": "sky_overcast", "fog": "sky_fog"}.get(k)
                if exp and isinstance(st, dict) and not str(st.get("shot", "")).startswith(exp): selftest_fail.append(f"i399 sky {sfx}: {k} shot {st.get('shot')}")
            o[f"nodata_{sfx}"] = result_json("group", "i396 sky auto static", timeout=20); b381_home(); time.sleep(3); shot(f"sky-nodata-{sfx}")
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode)
            result_json("group", "i396 sky sunny", timeout=20); b381_home(); time.sleep(4)
            for off, tag in ((0, "t0"), (None, "t6"), (150, "t150"), (300, "t300")):
                if off is not None: o[f"live_{sfx}_{tag}"] = result_json("group", f"i396 skyclock {off}", timeout=20); b381_home(); time.sleep(3)
                else: time.sleep(6); o[f"live_{sfx}_{tag}"] = result_json("group", "i396 skystate", timeout=10)
                shot(f"sky-live-{sfx}-{tag}")
            result_json("group", "i396 skyclock 0", timeout=20)
        print("I399 SKY " + json.dumps({k: v for k, v in o.items() if k.startswith(("sky_", "live_", "nodata_"))}, ensure_ascii=False)[:3000], flush=True)
        result_json("group", "i396 sky sunny static", timeout=20)
        theme388(1)
    def drawer_free():
        r = b381_home(); time.sleep(2)
        mb = node_bounds(r, lambda n: (n.get("content-desc") or "") == "更多")
        if mb: sh(f"input tap {(mb[0] + mb[2]) // 2} {(mb[1] + mb[3]) // 2}"); time.sleep(2.5)
        shot("drawer")
        if not tap_text("免登录 AI", wait=2):
            page_missing.append("i399 免登录 AI not in ☰ drawer"); b381_home(); return
        time.sleep(3); r = shot("free-ai"); t = _txt(r); o["free_ai"] = t[:400]
        if "一键检查" not in t: selftest_fail.append(f"i399 免登录 AI page lacks 一键检查 ({t[:100]})")
        if tap_text("一键检查", exact=True, wait=2): time.sleep(12); r = shot("free-ai-checked"); o["free_ai_checked"] = _txt(r)[:300]
        back(1); time.sleep(1)
    def chat_copy():
        result_json("group", "i396 sky sunny static", timeout=20)
        o["chat_open"] = result_json("group", "i398 picker", timeout=20); time.sleep(5)
        # the hook opens a chat with 选择模型 armed: pick the fake smoke-model there (the seeded coze entry never answers)
        picked = False
        for _ in range(5):
            if tap_text("smoke-model", wait=2): picked = True; break
            scroll_down(); time.sleep(1)
        if not picked: page_missing.append("i399 chat: smoke-model not found in 选择模型"); back(1); time.sleep(1.5)
        time.sleep(1.5)
        r = dump(); e = edits(r)
        if not e: page_missing.append("i399 chat: no input"); return
        sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text("COPYTEST"); sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
        tap_any(["Send", "发送"], wait=3) or sh("input keyevent KEYCODE_ENTER")
        for _ in range(15):
            time.sleep(2); r = dump()
            if find(r, "最后一行"): break
        r = shot("chat-sky"); t = _txt(r); o["chat"] = t[:400]
        # [i27] light 夜 chat veil (blue-grey, not flat grey)
        o["chat_night"] = result_json("group", "i396 sky night static", timeout=20); time.sleep(3); shot("chat-night-light")
        result_json("group", "i396 sky sunny static", timeout=20); time.sleep(2)
        if "复制" not in t: selftest_fail.append("i399 chat: footer/code 复制 not visible")
        if tap_text("复制", exact=True, wait=2): time.sleep(0.8); shot("chat-copied")
        b = node_bounds(r, lambda n: "这是" in (n.get("text") or "") or "最后一行" in (n.get("text") or ""))
        if b:
            x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
            sh(f"input swipe {x} {y} {x} {y} 900"); time.sleep(2)
            r = shot("chat-longpress"); t = _txt(r); o["longpress"] = t[:300]
            if "复制全文" not in t: selftest_fail.append(f"i399 long-press did not open the one sheet ({t[:100]})")
            if tap_text("选择文字", exact=True, wait=2): time.sleep(1.5); shot("chat-select-all")
            back(1); time.sleep(1)
        else: page_missing.append("i399 chat: reply text not found for long-press")
        back(1); time.sleep(1)
    theme388(1); healthy_home()
    step("providers", providers)
    step("groups", groups)
    theme388(2); healthy_home()
    step("picker", picker)
    step("oauth", oauth_quick)
    step("sky", sky)
    healthy_home()
    step("drawer", drawer_free)
    step("chat", chat_copy)
    print("I399 RESULT " + json.dumps(o, ensure_ascii=False)[:8000], flush=True)


def b3910(res):
    """[38.9.10 i29] sky v7 (procedural AGSL): 8 scenes x light/dark on home, 5 scenes x light/dark on the chat page;
    swipe rows on 服务商 / 模型组 keep the title; Clash service after am crash / kill (status line only: x86_64 can't run
    mihomo); proxy check against a runner port that accepts then drops (3129) = a real failure; >= 10 min soak with heap +
    meminfo; ~10 s screenrecords of live 晴 and 雨 on home."""
    o = res.setdefault("i3910", {})
    T_I3910 = time.time()
    def step(name, f):
        t0 = time.time()
        if t0 - T_I3910 > 82 * 60:   # [i30] smoke 1 hit the 120-min job timeout in the soak → finish() never ran
            page_missing.append(f"i3910 {name} skipped (time budget)"); print(f"I3910 SKIP {name} (time budget)", flush=True); return
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i3910 {name} error {e}")
        print(f"I3910 STEP {name} {int(time.time() - t0)} s", flush=True)
        if not alive(): crashed.append(f"i3910 {name}"); recover(f"i3910 {name}")
    def shot(name):
        r = snap("i3910-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i3910-{name}.png {OUT}/i3910-{name}.png", shell=True)
        return r
    print("I3910 screen", sh("wm size").strip(), sh("wm density").strip(), flush=True)
    set_zh()
    o["seedmodel"] = seed_model()
    try: result_json("group", "wallpaper off")
    except Exception: pass
    o["seed"] = result_json("group", "i398 seedpicker", timeout=30)
    o["heap0"] = result_json("group", "i3910 heap", timeout=10)
    o["meminfo0"] = sh(f"dumpsys meminfo {PKG}", timeout=30)[:3000]
    print("I3910 MEMINFO START\n" + o["meminfo0"][:1600], flush=True)
    SCENES = ("sunny", "cloudy", "overcast", "fog", "snow", "sunset", "night", "rain")
    CHAT = ("sunny", "rain", "overcast", "fog", "night")

    def sky_home():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            for k in SCENES:
                st = result_json("group", f"i396 sky {k} static", timeout=20); b381_home(); time.sleep(5)
                shot(f"sky-{k}-{sfx}"); st2 = result_json("group", "i396 skystate", timeout=10)
                o[f"sky_{k}_{sfx}"] = st2 or st
                if not (isinstance(st2, dict) and st2.get("scene") == k): selftest_fail.append(f"i3910 sky {sfx}: forced {k} but {st2}")
                elif st2.get("error"): page_missing.append(f"i3910 sky {sfx} {k}: shader error {st2.get('error')} (fallback still {st2.get('shot')})")
            record_theme(sfx)   # [i30] same theme, no extra force-stop
        print("I3910 SKY " + json.dumps({k: v for k, v in o.items() if k.startswith("sky_")}, ensure_ascii=False)[:4000], flush=True)

    def sky_chat():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            result_json("group", "i396 sky sunny static", timeout=20)
            o[f"chat_open_{sfx}"] = result_json("group", "i398 picker", timeout=20); time.sleep(5)
            picked = False
            for _ in range(4):
                if tap_text("smoke-model", wait=2): picked = True; break
                scroll_down(); time.sleep(1)
            if not picked: back(1); time.sleep(1.5)
            r = dump(); e = edits(r)
            if e:
                sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text("COPYTEST"); sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
                tap_any(["Send", "发送"], wait=3) or sh("input keyevent KEYCODE_ENTER")
                for _ in range(12):
                    time.sleep(2); r = dump()
                    if find(r, "最后一行"): break
            else: page_missing.append(f"i3910 chat {sfx}: no input")
            for k in CHAT:
                st = result_json("group", f"i396 sky {k} static", timeout=20); time.sleep(7)   # [i30] smoke 1 shots lagged one scene
                shot(f"chat-{k}-{sfx}"); o[f"chat_{k}_{sfx}"] = st
            back(1); time.sleep(1)

    def swipe_page(route_name, tag, row_pred):
        route(route_name, 5); r = shot(f"{tag}-rest")
        b = node_bounds(r, row_pred)
        if not b:
            for _ in range(3):
                scroll_down(); time.sleep(1); r = dump(); b = node_bounds(r, row_pred)
                if b: break
        if not b: page_missing.append(f"i3910 {tag}: no row to swipe"); back(1); return
        title_nodes = [n.get("text") for n in r.iter("node") if n.get("text") and _in(n, b)] if hasattr(r, "iter") else []
        y = (b[1] + b[3]) // 2; x2 = (SCREEN[0] - 100) if SCREEN[0] else b[2] - 20
        sh(f"input swipe {x2} {y} {max(40, x2 - 560)} {y} 400"); time.sleep(1.5)
        o[f"{tag}_probe"] = result_json("group", "i398 swipeprobe", timeout=10)
        if "open=true" not in str(o[f"{tag}_probe"]):   # [i30] smoke 1: 服务商 swipe lost under 3-s render stalls (off=-31)
            time.sleep(2); sh(f"input swipe {x2} {y} {max(40, x2 - 560)} {y} 600"); time.sleep(2)
            o[f"{tag}_probe2"] = result_json("group", "i398 swipeprobe", timeout=10); print(f"I3910 SWIPE RETRY {tag} {o[f'{tag}_probe2']}", flush=True)
        r = shot(f"{tag}-swiped"); t = _txt(r); o[f"{tag}_swiped"] = t[:400]
        if "删除" not in t: selftest_fail.append(f"i3910 {tag}: swipe did not reveal 删除")
        # the row's title must still be on screen (narrowed row, not pushed off / blank)
        title = next((x for x in title_nodes if len(x) >= 2 and x not in ("编辑", "删除")), None)
        o[f"{tag}_title"] = title
        if title and title not in t: selftest_fail.append(f"i3910 {tag}: title 「{title}」 gone after swipe")
        if not title: page_missing.append(f"i3910 {tag}: could not read the row title before the swipe")
        sh(f"input tap {SCREEN[0] // 4} {y}"); time.sleep(1); back(1); time.sleep(1)

    def swipes():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            swipe_page("providers", f"providers-{sfx}", lambda n: "免登录 AI" in ((n.get("text") or "") + (n.get("content-desc") or "")))
            swipe_page("model_groups", f"groups-{sfx}", lambda n: "个模型" in ((n.get("text") or "") + (n.get("content-desc") or "")))

    def svc():
        return sh(f"dumpsys activity services {PKG}/com.openminis.app.plus.ClashService", timeout=20)

    def clash():
        theme388(1); healthy_home()
        o["clash_on"] = result_json("group", "i3910 clashon", timeout=30); time.sleep(3)
        s0 = svc(); o["svc_on"] = ("ServiceRecord" in s0, "isForeground=true" in s0)
        print(f"I3910 CLASH ON {o['clash_on']} svc={o['svc_on']}", flush=True)
        if "ServiceRecord" not in s0: selftest_fail.append(f"i3910 clash: ClashService not running after clashon ({o['clash_on']})")
        pid0 = sh(f"pidof {PKG}").strip()
        for how in ("crash", "kill"):
            sh("input keyevent KEYCODE_HOME"); time.sleep(2)
            if how == "crash": sh(f"am crash {PKG}")
            else:
                p = sh(f"pidof {PKG}").strip()
                sh(f"am kill {PKG}"); time.sleep(1)
                if p and sh(f"pidof {PKG}").strip() == p: sh(f"kill -9 {p}") ; sh(f"su 0 kill -9 {p}")
            t0 = time.time(); back_pid = ""; s = ""
            while time.time() - t0 < 90:
                time.sleep(3); back_pid = sh(f"pidof {PKG}").strip(); s = svc()
                if back_pid and back_pid != pid0 and "ServiceRecord" in s: break
            o[f"after_{how}"] = {"secs": int(time.time() - t0), "pid_before": pid0, "pid_after": back_pid,
                                 "service": "ServiceRecord" in s, "fg": "isForeground=true" in s}
            st = result_json("group", "i3910 clash", timeout=20); o[f"after_{how}_state"] = st
            print(f"I3910 CLASH AFTER {how} {o[f'after_{how}']} state={st}", flush=True)
            if not (back_pid and back_pid != pid0 and "ServiceRecord" in s):
                selftest_fail.append(f"i3910 clash: service not restarted within 90 s after {how} ({o[f'after_{how}']})")
            elif isinstance(st, dict) and not st.get("serviceAlive"): selftest_fail.append(f"i3910 clash after {how}: serviceAlive false ({st})")
            pid0 = back_pid or pid0
        relaunch(); time.sleep(4)
        route("plus_routing", 5)
        for _ in range(10):
            r = dump()
            if find(r, "内置 Clash 内核"): break
            scroll_down(); time.sleep(0.8)
        scroll_down(); time.sleep(1); r = shot("clash-status"); o["clash_page"] = _txt(r)[:500]
        sh("cmd statusbar expand-notifications"); time.sleep(2); shot("clash-notification"); sh("cmd statusbar collapse"); time.sleep(1)
        back(1)
        o["clash_off"] = result_json("group", "i3910 clashoff", timeout=20); time.sleep(2)
        if "ServiceRecord" in svc(): selftest_fail.append("i3910 clash: service still running after clashoff")

    def proxy():
        theme388(1); healthy_home()
        j = result_json("group", "i3910 proxies 10.0.2.2:3129,10.0.2.2:3128:smoke:proxy396,10.0.2.2:3128:smoke:proxy396", timeout=120)
        o["proxies"] = j; print(f"I3910 PROXIES {j}", flush=True)
        if not isinstance(j, dict): selftest_fail.append(f"i3910 proxies: no json ({j})"); return
        chk = {c.get("proxy"): c for c in j.get("checks", [])}
        d = chk.get("10.0.2.2:3129"); g = chk.get("10.0.2.2:3128")
        if not d: selftest_fail.append(f"i3910 proxies: drop proxy not checked ({j})")
        elif d.get("ok"): selftest_fail.append(f"i3910 proxies: accept-then-drop proxy shown as working ({d})")
        if g and not g.get("ok"): UNTESTABLE.append(f"auth proxy 3128 real check failed on runner (no outbound?): {g}")
        if j.get("listed", 0) > 2: selftest_fail.append(f"i3910 proxies: duplicate host:port listed twice ({j.get('listed')})")
        route("plus_routing", 5)
        for _ in range(8):
            r = dump()
            if find(r, "10.0.2.2"): break
            scroll_down(); time.sleep(0.8)
        r = shot("proxy-list"); o["proxy_page"] = _txt(r)[:500]
        for lbl in ("测试", "检测", "测一下"):
            if tap_text(lbl, wait=2): time.sleep(15); r = shot("proxy-tested"); o["proxy_page_tested"] = _txt(r)[:500]; break
        back(1)
        result_json("group", "i396 proxyoff", timeout=20)

    def soak():
        theme388(1); healthy_home()
        result_json("group", "i396 sky rain", timeout=20); b381_home()
        o["soak"] = []; t0 = time.time(); i = 0
        while time.time() - t0 < 420:
            i += 1
            if i % 3 == 1:
                result_json("group", "i398 picker", timeout=20); time.sleep(4); back(1); time.sleep(1); back(1); time.sleep(1)
            elif i % 3 == 2:
                for _ in range(3): scroll_down(); time.sleep(0.5)
                b381_home()
            else:
                route("providers", 3); back(1); time.sleep(1)
            if i % 2 == 0:
                h = result_json("group", "i3910 heap", timeout=10); o["soak"].append([int(time.time() - t0), h])
                print(f"I3910 SOAK {int(time.time() - t0)} s heap={h}", flush=True)
            time.sleep(15)
        o["gc"] = result_json("group", "i3910 gc", timeout=20)
        o["heap1"] = result_json("group", "i3910 heap", timeout=10)
        o["meminfo1"] = sh(f"dumpsys meminfo {PKG}", timeout=30)[:3000]
        print("I3910 MEMINFO END\n" + o["meminfo1"][:1600], flush=True)
        print(f"I3910 HEAP start={o['heap0']} end={o['heap1']} gc={o['gc']}", flush=True)
        o["oom"] = result_json("group", "i3910 oomtest", timeout=20); time.sleep(3)
        print(f"I3910 OOMTEST {o['oom']} alive={alive()}", flush=True)
        if not alive(): selftest_fail.append("i3910 oomtest: app died on a background OutOfMemoryError")

    def record_theme(sfx):
        if True:
            for k in ("sunny", "rain"):
                result_json("group", f"i396 sky {k}", timeout=20); b381_home(); time.sleep(3)
                sh(f"rm -f /sdcard/rec.mp4; screenrecord --time-limit 10 --bit-rate 6000000 /sdcard/rec.mp4", timeout=40); time.sleep(1)
                adb("pull", "/sdcard/rec.mp4", f"{OUT}/i3910-rec-{k}-{sfx}.mp4", timeout=60)
                st = result_json("group", "i396 skystate", timeout=10); o[f"rec_{k}_{sfx}"] = st
                print(f"I3910 REC {k} {sfx} {os.path.getsize(f'{OUT}/i3910-rec-{k}-{sfx}.mp4') if os.path.exists(f'{OUT}/i3910-rec-{k}-{sfx}.mp4') else 0} B state={st}", flush=True)
        result_json("group", "i396 sky sunny static", timeout=20)

    # [i30] order: what smoke 1 never reached (finish) matters most → clash/proxy/swipes/soak before chat shots
    step("sky_home", sky_home)
    step("clash", clash)
    step("proxy", proxy)
    step("swipes", swipes)
    step("soak", soak)
    step("sky_chat", sky_chat)
    print("I3910 RESULT " + json.dumps({k: v for k, v in o.items() if not k.startswith("meminfo")}, ensure_ascii=False)[:9000], flush=True)


def b3911(res):
    """[38.9.11 int] screens of the integrated 38.9.11 build (A+B+C+D): home sky 晴/雨 light+dark, chat sky light+dark with Chinese
    tool pills (fake model TOOLZH → task_progress / file_write / memory_get), shared images as a stack in the composer and in the
    sent message, AI 服务商 (能用/不能用), 模型组 edit, 工作流记录 / 经验 list + detail, 工作位置 servers collapsed / expanded (hook +
    real taps), 每日简报, file manager (browser + own APK info), 谷歌账号 page, 应用专用密码 guide step 1."""
    o = res.setdefault("i3911", {})
    T0 = time.time()
    def step(name, f):
        t0 = time.time()
        if t0 - T0 > 85 * 60:
            page_missing.append(f"i3911 {name} skipped (time budget)"); print(f"I3911 SKIP {name}", flush=True); return
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i3911 {name} error {e}")
        print(f"I3911 STEP {name} {int(time.time() - t0)} s", flush=True)
        if not alive(): crashed.append(f"i3911 {name}"); recover(f"i3911 {name}")
    def shot(name):
        r = snap("i3911-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i3911-{name}.png {OUT}/i3911-{name}.png", shell=True)
        return r
    def wait_for(label, secs=30):
        t0 = time.time()
        while time.time() - t0 < secs:
            r = dump()
            if r is not None and find(r, label): return r
            time.sleep(2)
        return None
    print("I3911 screen", sh("wm size").strip(), sh("wm density").strip(), flush=True)
    set_zh()
    o["seedmodel"] = seed_model()
    try: result_json("group", "wallpaper off")
    except Exception: pass
    o["seedpicker"] = result_json("group", "i398 seedpicker", timeout=30)
    o["servers"] = result_json("group", "i3911 servers", timeout=20)
    o["xpseed"] = result_json("group", "i3911 xpseed", timeout=30)
    print(f"I3911 SEED {o['servers']} {o['xpseed']}", flush=True)

    def sky_home():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            for k in ("sunny", "rain"):
                result_json("group", f"i396 sky {k} static", timeout=20); b381_home(); time.sleep(5)
                shot(f"home-{k}-{sfx}"); st = result_json("group", "i396 skystate", timeout=10); o[f"home_{k}_{sfx}"] = st
                if not (isinstance(st, dict) and st.get("scene") == k): selftest_fail.append(f"i3911 home {sfx}: forced {k} but {st}")

    def open_chat():
        result_json("group", "i398 picker", timeout=20); time.sleep(5)
        for _ in range(4):
            if tap_text("smoke-model", wait=2): return True
            scroll_down(); time.sleep(1)
        back(1); time.sleep(1.5); return False

    def send(text):
        r = dump(); e = edits(r)
        if not e: return False
        sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text(text); sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
        tap_any(["Send", "发送"], wait=3) or sh("input keyevent KEYCODE_ENTER")
        return True

    def chat():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            result_json("group", "i396 sky sunny static", timeout=20)
            open_chat()
            if not send("TOOLZH please use the tools"): page_missing.append(f"i3911 chat {sfx}: no input"); back(1); continue
            r = wait_for("三个工具都跑完了", 60); time.sleep(3)
            r = shot(f"chat-tools-{sfx}"); t = _txt(r); o[f"chat_tools_{sfx}"] = t[:600]
            if r is None or "三个工具都跑完了" not in t: page_missing.append(f"i3911 chat {sfx}: final reply not seen")
            eng = [w for w in ("Task Progress", "File Write", "file_write", "Memory Get", "memory_get", "Execute Shell") if w in t]
            if eng: selftest_fail.append(f"i3911 chat {sfx}: English tool names on screen {eng}")
            zh = [w for w in ("任务进度", "写文件", "读记忆") if w in t]; o[f"chat_tools_zh_{sfx}"] = zh
            result_json("group", "i396 sky rain static", timeout=20); time.sleep(7)
            shot(f"chat-rain-{sfx}")
            back(1); time.sleep(1)

    def images():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            result_json("group", "i396 sky sunny static", timeout=20)
            o[f"share_{sfx}"] = result_json("group", "i3911 shareimgs", timeout=30); time.sleep(4)
            # [smoke2] the hook only persists the PendingShare; a real share also wakes MainActivity (shared_content=true →
            # ShareCoordinator.processPendingShare → buffer → ChatScreen drains it into the composer). singleTask → onNewIntent.
            print(sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity --ez shared_content true", timeout=40)[-200:], flush=True); time.sleep(7)
            r0 = dump(); o[f"composer_{sfx}"] = _txt(r0)[:300]
            r = shot(f"composer-images-{sfx}")
            if not send("look at these images"): page_missing.append(f"i3911 images {sfx}: no input"); back(1); continue
            wait_for("你好，我是测试模型", 40); time.sleep(3)
            r = shot(f"chat-images-{sfx}"); o[f"chat_images_{sfx}"] = _txt(r)[:300]
            back(1); time.sleep(1)

    def providers():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            route("providers", 6); r = shot(f"providers-{sfx}"); t = _txt(r); o[f"providers_{sfx}"] = t[:600]
            if mode == 1:
                if tap_any(["全部检测", "检测全部", "重新检测"], wait=3): time.sleep(20); r = shot("providers-checked-light"); o["providers_checked"] = _txt(r)[:600]
            if not any(w in t for w in ("能用", "不能用", "还没检测", "检测")): page_missing.append(f"i3911 providers {sfx}: no 能用/不能用 wording ({t[:120]})")
            back(1); time.sleep(1)

    def groups():
        theme388(1); healthy_home()
        route("model_groups", 5); r = shot("groups-list-light")
        if tap_text("个模型", wait=4) or tap_text("默认模型", wait=4):
            r = shot("group-edit-light"); t = _txt(r)
            scroll_down(); time.sleep(1); r2 = shot("group-edit-light-2"); t += " " + _txt(r2); o["group_edit"] = t[:800]
            eng = [w for w in ("Try models in order", "Fall back on rate limits", "Fallback 触发条件") if w in t]
            if eng: selftest_fail.append(f"i3911 模型组 edit still English: {eng}")
            back(1); time.sleep(1)
        else: page_missing.append("i3911 模型组: no group row to open")
        back(1); time.sleep(1)

    def xp():
        theme388(1); healthy_home()
        route("plus_workflows", 6); r = shot("workflows-list-light"); t = _txt(r); o["workflows"] = t[:800]
        if "<scheduled_task" in t or "st_8f3a" in t or "839201" in t: selftest_fail.append("i3911 工作流记录: raw envelope / code on screen")
        if tap_text("502", wait=4) or tap_text("new-api", wait=4) or tap_text("梦云", wait=4):
            r = shot("workflow-detail-light"); o["workflow_detail"] = _txt(r)[:600]; back(1); time.sleep(1)
        else: page_missing.append("i3911 工作流记录: seeded run not found")
        back(1); time.sleep(1)
        route("plus_experience", 6); r = shot("experience-list-light"); o["experience"] = _txt(r)[:800]
        if tap_text("修 VPS", wait=4) or tap_text("502", wait=4):
            r = shot("experience-detail-light"); o["experience_detail"] = _txt(r)[:600]; back(1); time.sleep(1)
        else: page_missing.append("i3911 经验: seeded entry not found")
        back(1); time.sleep(1)

    def work():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            result_json("group", "i3911 servers", timeout=20)
            route("plus_work_location", 6); r = shot(f"work-collapsed-{sfx}"); t = _txt(r); o[f"work_collapsed_{sfx}"] = t[:600]
            if "IP 归属地" in t or "限制：" in t: selftest_fail.append(f"i3911 工作位置 {sfx}: details visible while collapsed")
            if "alvps" not in t: page_missing.append(f"i3911 工作位置 {sfx}: seeded servers not shown")
            if mode == 1:
                # a real tap on the card (its name) must open it; a second tap must close it
                tap_text("alvps", wait=2); r = shot("work-tap-open-light"); t2 = _txt(r); o["work_tap_open"] = t2[:600]
                if "IP 归属地" not in t2 and "测试连接" not in t2: selftest_fail.append("i3911 工作位置: tap did not expand the card")
                tap_text("alvps", wait=2); r = dump(); t3 = _txt(r); o["work_tap_close"] = t3[:300]
                if "IP 归属地" in t3: selftest_fail.append("i3911 工作位置: second tap did not collapse the card")
            o[f"expand_{sfx}"] = result_json("group", "i3911 expand smoke-alvps", timeout=10); time.sleep(2)
            r = shot(f"work-expanded-{sfx}"); o[f"work_expanded_{sfx}"] = _txt(r)[:600]
            result_json("group", "i3911 expand none", timeout=10)
            back(1); time.sleep(1)

    def digest():
        theme388(1); healthy_home()
        route("plus_digest", 8); r = shot("digest-brief-light"); o["digest"] = _txt(r)[:500]
        if "每日简报" not in o["digest"]: page_missing.append("i3911 每日简报 tab not seen")
        back(1); time.sleep(1)

    def files():
        theme388(1); healthy_home()
        print(sh(f"am start -W -n {PKG}/com.openminis.app.files.FileManagerActivity", timeout=40)[-200:], flush=True); time.sleep(6)
        r = shot("files-browser-light"); o["files_browser"] = _txt(r)[:500]
        apk = (result_json("group", "i3911 apkpath", timeout=10) or "")
        apk = apk if isinstance(apk, str) else str(apk); o["apkpath"] = apk
        if apk.startswith("/"):
            print(sh(f"am start -W -n {PKG}/com.openminis.app.files.FileManagerActivity --es com.openminis.app.files.PATH {apk}", timeout=40)[-200:], flush=True); time.sleep(10)
            r = shot("files-apk-light"); t = _txt(r); o["files_apk"] = t[:600]
            if "com.openminis.mines.plus" not in t and "1.23.11" not in t: page_missing.append("i3911 file manager: APK info not confirmed")
            if tap_text("签名", wait=3): shot("files-apk-sign-light")
        back(3); time.sleep(1)

    def google():
        theme388(1); healthy_home()
        result_json("group", "i3911 gemini", timeout=10); time.sleep(5)
        r = shot("gemini-accounts-light"); o["gemini"] = _txt(r)[:500]; back(1); time.sleep(1)
        result_json("group", "i3911 apppw", timeout=10); time.sleep(5)
        r = shot("apppw-step1-light"); o["apppw"] = _txt(r)[:500]; back(1); time.sleep(1)

    # [smoke2] smoke 1 (run 39373174888) covered sky_home/work/providers/groups/xp/digest/google; rerun only what its driver broke
    step("chat", chat)
    step("images", images)
    step("files", files)
    print("I3911 RESULT " + json.dumps(o, ensure_ascii=False)[:12000], flush=True)


def b39111(res):
    """[38.9.11.1 hotfix] fog sky on home + chat (light / dark): emulator stills, then the shader forced on the software GPU in
    流畅 and 精美, frame stats (dumpsys gfxinfo) for each; 外观调节 › 天空动画 row; local proxy probe with the phone's
    127.0.0.1:7890 = an open proxy on the runner (adb reverse), 7897 = a listener that drops, then with nothing → direct."""
    o = res.setdefault("i39111", {})
    T0 = time.time()
    def step(name, f):
        t0 = time.time()
        if t0 - T0 > 80 * 60:
            page_missing.append(f"i39111 {name} skipped (time budget)"); print(f"I39111 SKIP {name}", flush=True); return
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i39111 {name} error {e}")
        print(f"I39111 STEP {name} {int(time.time() - t0)} s", flush=True)
        if not alive(): crashed.append(f"i39111 {name}"); recover(f"i39111 {name}")
    def shot(name):
        r = snap("i39111-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i39111-{name}.png {OUT}/i39111-{name}.png", shell=True)
        return r
    def gfx(tag, secs=10):
        sh(f"dumpsys gfxinfo {PKG} reset"); time.sleep(secs)
        g = sh(f"dumpsys gfxinfo {PKG}", timeout=30)
        o["gfx_" + tag] = " | ".join(l.strip() for l in g.splitlines() if re.search(r"Total frames|Janky frames|50th percentile|90th percentile|99th percentile|Number Slow UI|Number Frame deadline", l))[:500]
        print("I39111 GFX", tag, o["gfx_" + tag], flush=True)
    def state(tag):
        st = result_json("group", "i396 skystate", timeout=15); o["sky_" + tag] = st
        print("I39111 SKY", tag, json.dumps(st, ensure_ascii=False)[:600], flush=True)
        return st
    print("I39111 screen", sh("wm size").strip(), sh("wm density").strip(), flush=True)
    set_zh()
    o["seedmodel"] = seed_model()
    try: result_json("group", "wallpaper off")
    except Exception: pass
    o["seedpicker"] = result_json("group", "i398 seedpicker", timeout=30)

    def proxy():
        # nothing on 7890 / 7897 yet → expect no local proxy (split proxy is off on a fresh install)
        o["lp_none"] = result_json("group", "i396 localproxy", timeout=40); print("I39111 LP none", o["lp_none"], flush=True)
        print(adb("reverse", "tcp:7890", "tcp:3130"), adb("reverse", "tcp:7897", "tcp:3129"), adb("reverse", "--list"), flush=True)
        o["lp_7890"] = result_json("group", "i396 localproxy", timeout=40); print("I39111 LP 7890", o["lp_7890"], flush=True)
        lp = o["lp_7890"] if isinstance(o["lp_7890"], dict) else {}
        if lp.get("best") != 7890: selftest_fail.append(f"i39111 localproxy: expected 7890 (open proxy) to be chosen, got {lp}")
        if any(str(x).startswith("7897:OK") for x in lp.get("probes", [])): selftest_fail.append(f"i39111 localproxy: dropping listener 7897 passed {lp}")
        adb("reverse", "--remove", "tcp:7890"); time.sleep(1)
        o["lp_after"] = result_json("group", "i396 localproxy", timeout=40); print("I39111 LP after", o["lp_after"], flush=True)
        la = o["lp_after"] if isinstance(o["lp_after"], dict) else {}
        if la.get("best", -1) not in (-1, None): selftest_fail.append(f"i39111 localproxy: a proxy still chosen after 7890 went away {la}")
        adb("reverse", "--remove-all")

    def home_fog():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            result_json("group", "i396 skygpu off", timeout=30)
            result_json("group", "i396 sky fog static", timeout=20); b381_home(); time.sleep(5)
            shot(f"home-fog-still-{sfx}"); state(f"still_{sfx}")
            o[f"gpu_on_{sfx}"] = result_json("group", "i396 skygpu on", timeout=40)
            result_json("group", "i396 skymode smooth", timeout=20); result_json("group", "i396 sky fog", timeout=20); b381_home(); time.sleep(8)
            shot(f"home-fog-smooth-{sfx}"); gfx(f"home_smooth_{sfx}", 10); st = state(f"smooth_{sfx}")
            if isinstance(st, dict) and st.get("error"): page_missing.append(f"i39111 home {sfx}: shader error {st.get('error')}")
            result_json("group", "i396 skymode rich", timeout=20); time.sleep(6)
            shot(f"home-fog-rich-{sfx}"); gfx(f"home_rich_{sfx}", 10); state(f"rich_{sfx}")
            result_json("group", "i396 skymode off", timeout=20); time.sleep(4)
            gfx(f"home_off_{sfx}", 8); state(f"off_{sfx}")
            result_json("group", "i396 skymode smooth", timeout=20)
            result_json("group", "i396 skygpu off", timeout=30)

    def open_chat():
        result_json("group", "i398 picker", timeout=20); time.sleep(5)
        for _ in range(4):
            if tap_text("smoke-model", wait=2): return True
            scroll_down(); time.sleep(1)
        back(1); time.sleep(1.5); return False

    def chat_fog():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            result_json("group", "i396 skygpu on", timeout=40); result_json("group", "i396 skymode smooth", timeout=20)
            result_json("group", "i396 sky fog", timeout=20)
            open_chat()
            r = dump(); e = edits(r)
            if e:
                sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text("hello fog"); sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
                tap_any(["Send", "发送"], wait=3) or sh("input keyevent KEYCODE_ENTER")
                time.sleep(12)
            else: page_missing.append(f"i39111 chat {sfx}: no input")
            shot(f"chat-fog-smooth-{sfx}"); gfx(f"chat_smooth_{sfx}", 10); st = state(f"chat_smooth_{sfx}")
            if isinstance(st, dict) and st.get("skies", 0) > 2: selftest_fail.append(f"i39111 chat {sfx}: {st.get('skies')} skies registered")
            back(1); time.sleep(1.5)
            result_json("group", "i396 skygpu off", timeout=30)

    def look():
        theme388(1); healthy_home()
        route("plus_look", 6); r = shot("look-light"); t = _txt(r); o["look"] = t[:500]
        if "天空动画" not in t: page_missing.append("i39111 外观调节: 天空动画 row not seen")
        if tap_text("精美", wait=2): time.sleep(1); o["look_rich"] = state("look_rich"); shot("look-rich")
        if tap_text("流畅", wait=2): time.sleep(1); o["look_smooth"] = state("look_smooth")
        back(1); time.sleep(1)

    def aimail():
        theme388(1); healthy_home()
        route("plus_code_sources", 6)
        for _ in range(3):
            r = dump()
            if r is not None and find(r, "开通临时邮箱"): break
            scroll_down(); time.sleep(1)
        r = shot("aimail-before"); t = _txt(r); o["aimail_before"] = t[:600]
        if "暂不可用" not in t: page_missing.append("i39111 AI 邮箱: AGmail 暂不可用 not seen")
        if tap_text("开通临时邮箱", wait=2):
            for _ in range(15):
                time.sleep(2); r = dump(); t = _txt(r)
                if "开通中" not in t: break
            r = shot("aimail-mailtm"); t = _txt(r); o["aimail_after"] = t[:600]
            print("I39111 AIMAIL", t[:400], flush=True)
            if "已开通" not in t and "没开通" not in t and "Mail.tm" not in t: page_missing.append("i39111 Mail.tm: no result line")
        else: page_missing.append("i39111 Mail.tm: 开通临时邮箱 button not found")
        if tap_text("打开官网", wait=2):
            time.sleep(8); r = shot("aimail-site"); o["aimail_site"] = _txt(r)[:300]; back(1); time.sleep(2)
        else: page_missing.append("i39111 AI 邮箱: 打开官网 not found")
        back(1); time.sleep(1)

    def images():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            result_json("group", "i396 sky fog static", timeout=20)
            o[f"share_{sfx}"] = result_json("group", "i3911 shareimgs", timeout=30); time.sleep(4)
            print(sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity --ez shared_content true", timeout=40)[-200:], flush=True); time.sleep(7)
            shot(f"composer-images-{sfx}")
            r = dump(); e = edits(r)
            if not e: page_missing.append(f"i39111 images {sfx}: no input"); back(1); continue
            sh(f"input tap {e[-1][0]} {e[-1][1]}"); time.sleep(0.6); type_text("look at these images"); sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
            tap_any(["Send", "发送"], wait=3) or sh("input keyevent KEYCODE_ENTER")
            time.sleep(12); shot(f"chat-images-{sfx}")
            back(1); time.sleep(1.5)

    def logincard():
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            o[f"logincard_{sfx}"] = result_json("group", "i396 logincard", timeout=20); time.sleep(3)
            r = shot(f"logincard-{sfx}"); t = _txt(r); o[f"logincard_txt_{sfx}"] = t[:300]
            for w in ("保存到密码库", "提交", "验证码"):
                if w not in t: page_missing.append(f"i39111 login card {sfx}: '{w}' not on screen")
            back(1); time.sleep(1.5)

    step("proxy", proxy)
    step("logincard", logincard)
    step("images", images)
    step("aimail", aimail)
    step("home_fog", home_fog)
    step("chat_fog", chat_fog)
    step("look", look)
    result_json("group", "i396 sky auto static", timeout=20)
    print("I39111 RESULT " + json.dumps(o, ensure_ascii=False)[:12000], flush=True)


def b39112(res):
    """[38.9.11.2] 无痕: normal browser sets cookie + localStorage + IndexedDB + Cache + SW on a local page; a 无痕 session must
    see none; set inside 无痕, 清除并关闭, reopen → gone; normal browser still has its own; kill-safe (force-stop during a
    session → wiped on next launch). 晴 「闪光」: screenrecord the sunny sky (light / dark) with the 38.9.11.1 behaviour
    (skylegacy on) and the fix, governor level stepped 0→1→2→0 during each recording; luminance analysed offline."""
    import urllib.request
    o = res.setdefault("i39112", {})
    T0 = time.time()
    def step(name, f):
        t0 = time.time()
        if t0 - T0 > 85 * 60:
            page_missing.append(f"i39112 {name} skipped (time budget)"); print(f"I39112 SKIP {name}", flush=True); return
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i39112 {name} error {e}")
        print(f"I39112 STEP {name} {int(time.time() - t0)} s", flush=True)
        if not alive(): crashed.append(f"i39112 {name}"); recover(f"i39112 {name}")
    def shot(name):
        r = snap("i39112-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i39112-{name}.png {OUT}/i39112-{name}.png", shell=True)
        return r
    def reports():
        try: return json.loads(urllib.request.urlopen("http://127.0.0.1:8099/reports", timeout=5).read().decode())
        except Exception as e: print("reports error", e, flush=True); return []
    def last(tag, wait=20):
        for _ in range(wait):
            m = [r for r in reports() if r.get("tag") == tag]
            if m: return m[-1]
            time.sleep(1)
        return None
    U = "http://localhost:8099/t"
    def empty(r): return r is not None and not r.get("ck") and not r.get("ls") and not r.get("idb") and not r.get("cache") and r.get("sw") in ("0", 0)
    def istate(tag):
        st = result_json("group", "i39112 incogstate", timeout=20); o["state_" + tag] = st; print("I39112 STATE", tag, st, flush=True); return st if isinstance(st, dict) else {}
    def clear_close(tag):
        if not tap_text("清除并关闭", wait=4): page_missing.append(f"i39112 {tag}: 清除并关闭 not found"); back(1)
        time.sleep(4)
    print("I39112 screen", sh("wm size").strip(), sh("wm density").strip(), flush=True)
    set_zh()
    try: result_json("group", "wallpaper off")
    except Exception: pass

    def incog():
        print(adb("reverse", "tcp:8099", "tcp:8099"), flush=True)
        theme388(1); healthy_home()
        istate("start")
        # 1 normal browser writes everything
        result_json("group", f"i39112 normal {U}/normal?act=set&tag=normal", timeout=30); time.sleep(6)
        r = last("normal"); o["normal_set"] = r; shot("normal-set")
        if not r or r.get("ck") != "normal" or r.get("ls") != "normal": selftest_fail.append(f"i39112 normal browser did not store: {r}")
        back(1); time.sleep(2)
        # 2 无痕 must see none of it
        st = result_json("group", f"i39112 incog {U}/incog1?act=read&tag=incog1", timeout=30); time.sleep(6)
        r = last("incog1"); o["incog1"] = r; rr = shot("incog-read-empty"); t = _txt(rr)
        o["path"] = (st or {}).get("lastPath") if isinstance(st, dict) else None
        if "无痕" not in t: page_missing.append("i39112 无痕 indicator not on screen")
        if "清除并关闭" not in t: page_missing.append("i39112 清除并关闭 not on screen")
        if not empty(r): selftest_fail.append(f"i39112 无痕 saw normal data: {r}")
        istate("open1")
        # 3 write inside 无痕, read back (sanity), then 清除并关闭
        result_json("group", f"i39112 incog {U}/incogset?act=set&tag=incogset", timeout=30); time.sleep(6)
        r = last("incogset"); o["incogset"] = r; shot("incog-set")
        if not r or r.get("ck") != "incogset": page_missing.append(f"i39112 无痕 could not write (page may not work there): {r}")
        clear_close("close1"); s1 = istate("closed1"); shot("after-clear")
        if s1.get("process"): selftest_fail.append("i39112 :incog process still running after 清除并关闭")
        if s1.get("dirs"): selftest_fail.append(f"i39112 :incog data dirs left after close: {s1.get('dirs')}")
        if s1.get("open"): selftest_fail.append(f"i39112 profiles still open after close: {s1.get('open')}")
        # 4 reopen → gone
        result_json("group", f"i39112 incog {U}/incog2?act=read&tag=incog2", timeout=30); time.sleep(6)
        r = last("incog2"); o["incog2"] = r; shot("incog-reopen-empty")
        if not empty(r): selftest_fail.append(f"i39112 reopened 无痕 still has data: {r}")
        clear_close("close2")
        # 5 normal browser unaffected
        result_json("group", f"i39112 normal {U}/normal2?act=read&tag=normal2", timeout=30); time.sleep(6)
        r = last("normal2"); o["normal2"] = r; shot("normal-after")
        if not r or r.get("ck") != "normal" or r.get("ls") != "normal" or r.get("idb") != "normal": selftest_fail.append(f"i39112 normal browser lost its data: {r}")
        back(1); time.sleep(2)
        # 5b 每个对话的隔离: conversation A's agent browser vs conversation B vs the home browser
        ca = result_json("group", f"i39112 conv smokeConvA {U}/convA?act=set&tag=convA", timeout=30); time.sleep(8)
        o["conv_open_a"] = ca; r = last("convA"); o["convA"] = r
        result_json("group", f"i39112 conv smokeConvB {U}/convB?act=read&tag=convB", timeout=30); time.sleep(8)
        r = last("convB"); o["convB"] = r
        result_json("group", f"i39112 conv smokeConvA {U}/convA2?act=read&tag=convA2", timeout=30); time.sleep(8)
        r2 = last("convA2"); o["convA2"] = r2
        result_json("group", f"i39112 normal {U}/normal3?act=read&tag=normal3", timeout=30); time.sleep(6)
        r3 = last("normal3"); o["normal3"] = r3; back(1); time.sleep(1.5)
        o["conv_state"] = result_json("group", "i39112 convstate", timeout=20)
        conv_active = isinstance(ca, dict) and ca.get("active")
        o["conv_path"] = "profile" if conv_active else "none (no MULTI_PROFILE: conversations share the default profile)"
        print("I39112 CONV", o["conv_path"], json.dumps({k: o.get(k) for k in ("convA", "convB", "convA2", "normal3")}, ensure_ascii=False), flush=True)
        if conv_active:
            if not empty(o["convB"]): selftest_fail.append(f"i39112 conversation B saw A's data: {o['convB']}")
            if not r2 or r2.get("ck") != "convA": selftest_fail.append(f"i39112 conversation A lost its data: {r2}")
            if not r3 or r3.get("ck") != "normal": selftest_fail.append(f"i39112 home browser changed by a conversation: {r3}")
        else: UNTESTABLE.append("i39112 per-conversation profiles: emulator WebView has no MULTI_PROFILE")
        result_json("group", "i39112 convdel smokeConvA", timeout=20); result_json("group", "i39112 convdel smokeConvB", timeout=20)
        time.sleep(3); o["conv_state_after_delete"] = result_json("group", "i39112 convstate", timeout=20)
        # 6 dark theme top bar
        theme388(2); healthy_home()
        result_json("group", f"i39112 incog {U}/incogdark?act=read&tag=incogdark", timeout=30); time.sleep(6); shot("incog-dark")
        clear_close("closedark"); theme388(1); healthy_home()
        # 7 kill-safe: write in 无痕, force-stop the whole app, relaunch → leftovers wiped
        result_json("group", f"i39112 incog {U}/incogkill?act=set&tag=incogkill", timeout=30); time.sleep(6)
        istate("before_kill")
        sh(f"am force-stop {PKG}"); time.sleep(2)
        sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1"); time.sleep(14)
        sk = istate("after_relaunch")
        if sk.get("marker") or sk.get("dirs"): selftest_fail.append(f"i39112 kill-safe: leftovers after relaunch {sk}")
        healthy_home()
        result_json("group", f"i39112 incog {U}/incog3?act=read&tag=incog3", timeout=30); time.sleep(6)
        r = last("incog3"); o["incog3"] = r
        if not empty(r): selftest_fail.append(f"i39112 kill-safe: data survived a force-stop: {r}")
        clear_close("close3")
        # 8 forced process path where profiles exist (no-op on the emulator, which has none)
        o["reports"] = reports()[-20:]
        print("I39112 INCOG", json.dumps({k: o.get(k) for k in ("path", "normal_set", "incog1", "incogset", "incog2", "normal2", "incog3")}, ensure_ascii=False), flush=True)

    def atomic():
        o["atomic"] = result_json("group", "i39112 atomic", timeout=20); print("I39112 ATOMIC", o["atomic"], flush=True)
        a = o["atomic"] if isinstance(o["atomic"], dict) else {}
        if a.get("fresh") != "v3@main" or a.get("mainBad") != "v2@bak1" or a.get("allBad") is not True:
            selftest_fail.append(f"i39112 atomic store on device: {a}")

    def sky():
        o["freeze_signals"] = result_json("group", "i39112 skyfreeze state", timeout=15); print("I39112 FREEZE", o["freeze_signals"], flush=True)
        result_json("group", "i39112 skyfreeze ignore", timeout=15)   # measure the sky itself (the emulator's a11y would freeze it)
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            o[f"gpu_{sfx}"] = result_json("group", "i396 skygpu on", timeout=40)
            result_json("group", "i396 skymode smooth", timeout=20)
            for leg in ("on", "off"):
                tag = f"{'before' if leg == 'on' else 'after'}-{sfx}"
                result_json("group", f"i39112 skylegacy {leg}", timeout=15)
                result_json("group", "i39112 skylevel 0", timeout=15)
                result_json("group", "i396 sky sunny", timeout=20); b381_home(); time.sleep(8)
                sh("rm -f /sdcard/sky.mp4")
                p = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "9", "--bit-rate", "12000000", "/sdcard/sky.mp4"])
                t0 = time.time()
                for at, lv in ((3.0, 1), (5.0, 2), (6.5, 0)):
                    while time.time() - t0 < at: time.sleep(0.05)
                    result_json("group", f"i39112 skylevel {lv}", timeout=10)
                    print("I39112 LEVEL", tag, lv, round(time.time() - t0, 2), flush=True)
                try: p.wait(timeout=30)
                except Exception: p.kill()
                time.sleep(1)
                print(adb("pull", "/sdcard/sky.mp4", f"{OUT}/i39112-sky-{tag}.mp4"), flush=True)
                shot(f"sky-{tag}")
                o[f"sky_{tag}"] = result_json("group", "i396 skystate", timeout=15)
            # freeze check (fixed build): key input from 2 s to 4.5 s → the sky must hold still until 1 s after the last tap
            if sfx == "light":
                result_json("group", "i39112 skylegacy off", timeout=15); result_json("group", "i39112 skylevel 0", timeout=15)
                b381_home(); time.sleep(6)
                sh("rm -f /sdcard/sky.mp4")
                p = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "8", "--bit-rate", "12000000", "/sdcard/sky.mp4"])
                t0 = time.time()
                W, H = 1080, 2412
                while time.time() - t0 < 2.0: time.sleep(0.05)
                while time.time() - t0 < 4.5:
                    sh("input keyevent 59"); time.sleep(0.25)   # SHIFT_LEFT: an input event that changes nothing on screen
                print("I39112 TAPS end", round(time.time() - t0, 2), flush=True)
                try: p.wait(timeout=30)
                except Exception: p.kill()
                time.sleep(1); print(adb("pull", "/sdcard/sky.mp4", f"{OUT}/i39112-sky-freeze-light.mp4"), flush=True)
            result_json("group", "i39112 skylegacy off", timeout=15); result_json("group", "i39112 skylevel 0", timeout=15)
            result_json("group", "i396 skygpu off", timeout=30)

    def ssh():
        # B/C: local sshd offers ONLY ssh-ed25519 host keys (38.9.11.1 JSch on Android: 「Algorithm negotiation fail」)
        o["ssh_local_ed25519"] = result_json("group", "i39112 sshprobe 10.0.2.2 22", timeout=60)
        o["ssh_instabox"] = result_json("group", "i39112 sshprobe api.instabox.sh 2222", timeout=60)
        print("I39112 SSH", o["ssh_local_ed25519"], "|", o["ssh_instabox"], flush=True)
        r = str(o["ssh_local_ed25519"])
        if "hostkey=ssh-ed25519" not in r and "connected ssh-ed25519" not in r: selftest_fail.append(f"i39112 ssh-ed25519-only sshd: {r}")

    def proxy():
        # A: a local proxy that really gets out (open proxy on 7890) is used with 分流代理 OFF; 严格代理 blocks when none passes
        print(adb("reverse", "tcp:7890", "tcp:3130"), flush=True)
        o["px_7890"] = result_json("group", "i396 localproxy", timeout=40); print("I39112 PX 7890", o["px_7890"], flush=True)
        a = o["px_7890"] if isinstance(o["px_7890"], dict) else {}
        if a.get("source") != "local:7890" or "7890" not in str(a.get("http_proxy")): selftest_fail.append(f"i39112 sandbox did not take the passing local proxy: {a}")
        adb("reverse", "--remove", "tcp:7890"); time.sleep(1)
        o["px_none"] = result_json("group", "i396 localproxy", timeout=40); print("I39112 PX none", o["px_none"], flush=True)
        o["px_strict_on"] = result_json("group", "i39112 strict on", timeout=40); print("I39112 PX strict on", o["px_strict_on"], flush=True)
        b = o["px_strict_on"] if isinstance(o["px_strict_on"], dict) else {}
        if b.get("source") != "blocked" or b.get("http_proxy") != "http://127.0.0.1:1": selftest_fail.append(f"i39112 严格代理 did not block: {b}")
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home(); route("plus_sandbox", 5)
            for _ in range(6):
                if "严格代理" in _txt(snap("i39112-probe-sandbox")): break
                scroll_down(); time.sleep(1)
            shot(f"strict-on-{sfx}"); back(1); time.sleep(1)
        o["px_strict_off"] = result_json("group", "i39112 strict off", timeout=40); print("I39112 PX strict off", o["px_strict_off"], flush=True)
        c = o["px_strict_off"] if isinstance(o["px_strict_off"], dict) else {}
        if c.get("source") == "blocked": selftest_fail.append(f"i39112 严格代理 off still blocked: {c}")
        theme388(1); healthy_home(); route("plus_sandbox", 5)
        for _ in range(6):
            if "严格代理" in _txt(snap("i39112-probe-sandbox2")): break
            scroll_down(); time.sleep(1)
        shot("strict-off-light"); back(1); time.sleep(1)

    def merge():
        # D: 3 providers on one host → card → preview → 确定合并 (1 left, 3-key pool, union of models) → 撤销 (3 again)
        theme388(1); healthy_home()
        o["merge_seed"] = result_json("group", "i39112 mergeseed", timeout=30); print("I39112 MERGE seed", o["merge_seed"], flush=True)
        route("providers", 6); r = shot("merge-card"); t = _txt(r)
        if "合并重复供应商" not in t: page_missing.append(f"i39112 merge card not on providers page ({t[:160]})")
        if tap_text("看看怎么合并", wait=3): shot("merge-preview")
        else: page_missing.append("i39112 看看怎么合并 not found")
        if tap_text("确定合并", wait=4):
            o["merge_after"] = result_json("group", "i39112 mergestate", timeout=30); print("I39112 MERGE after", o["merge_after"], flush=True)
            shot("merge-done")
            m = o["merge_after"] if isinstance(o["merge_after"], dict) else {}
            if m.get("count") != 1 or m.get("pools") != [3] or m.get("models") != [2] or m.get("backup") is not True:
                selftest_fail.append(f"i39112 merge result wrong: {m}")
            if tap_text("撤销", exact=True, wait=4):
                o["merge_undo"] = result_json("group", "i39112 mergestate", timeout=30); print("I39112 MERGE undo", o["merge_undo"], flush=True)
                shot("merge-undone")
                u = o["merge_undo"] if isinstance(o["merge_undo"], dict) else {}
                if u.get("count") != 3 or u.get("backup") is not False: selftest_fail.append(f"i39112 撤销 did not restore: {u}")
            else: page_missing.append("i39112 撤销 not found")
        else: page_missing.append("i39112 确定合并 not found")
        back(1); time.sleep(1)
        theme388(2); healthy_home(); route("providers", 6); shot("merge-card-dark"); back(1); theme388(1); healthy_home()
        result_json("group", "i39112 mergeclean", timeout=30)

    step("incog", incog)
    step("atomic", atomic)
    step("sky", sky)
    step("ssh", ssh)
    step("proxy", proxy)
    step("merge", merge)
    result_json("group", "i39112 skyfreeze on", timeout=15)
    result_json("group", "i396 sky auto static", timeout=20)
    print("I39112 RESULT " + json.dumps(o, ensure_ascii=False)[:12000], flush=True)


def _in(n, b):
    try:
        nb = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
        return nb[1] >= b[1] - 5 and nb[3] <= b[3] + 5
    except Exception:
        return False


def finish(lc):
    lc.terminate()
    time.sleep(1)
    log = open(f"{OUT}/logcat.txt", errors="replace").read().splitlines()
    fatal = [i for i, l in enumerate(log)
             if ("FATAL EXCEPTION" in l or "Fatal signal" in l or " ANR in " in l)
             and PKG in "\n".join(log[i:i + 4])
             and "CrashedByAdbException" not in "\n".join(log[i:i + 4])
             and "MinesSmokeOomSimulation" not in "\n".join(log[i:i + 6])]   # [38.9.12.1] the i39121 OOM kill is intentional   # [i30] am crash in the i3910 Clash step is intentional
    print("intentional OOM-simulation FATALs (not counted):", sum(1 for l in log if "MinesSmokeOomSimulation" in l and "OutOfMemoryError" in l), flush=True)
    print("intentional am crash FATALs (not counted):", sum(1 for l in log if "CrashedByAdbException: shell-induced crash" in l and "AndroidRuntime" in l), flush=True)
    print("=" * 30, "FATAL EXCEPTIONS:", len(fatal), flush=True)
    for i in fatal:
        print("\n".join(log[max(0, i - 3): i + 60]))
        print("-" * 30)
    print("crashed steps:", crashed)
    print("selftest failures:", selftest_fail)
    print("pages not confirmed:", page_missing)
    print("untestable here:", UNTESTABLE)
    print("recoveries (app restarted after GMS / hang):", RECOVERIES)
    # Mines' own crash log (build-28+), readable through the app's files only for debuggable builds; logcat tag MinesCrash
    for l in log:
        if "MinesCrash" in l:
            print(l)
    sys.exit(1 if (crashed or fatal or selftest_fail) else 0)



SKY_SCENES_39113 = ("sunny", "cloudy", "overcast", "fog", "rain", "snow", "night", "sunset")


def sky_cells_39113(a, b, thr=1.0):
    """8×8 grid of mean |Δ| (grey levels): (cells above thr, mean over all cells)"""
    from PIL import Image, ImageChops, ImageStat
    d = ImageChops.difference(Image.open(a).convert("L"), Image.open(b).convert("L")); W, H = d.size; n = 0; tot = 0.0
    for i in range(8):
        for j in range(8):
            m = ImageStat.Stat(d.crop((i * W // 8, j * H // 8, (i + 1) * W // 8, (j + 1) * H // 8))).mean[0]; tot += m
            if m > thr: n += 1
    return n, round(tot / 64, 2)


def sky_frames_39113(tag, cname, sfx, path, R, keep):
    """[38.9.11.3 h] user 20:39 「最起码和你这样动才行」: per scene three screenshots at 0 / 1.5 / 3 s on home; the sky must
    visibly change within 3 s (≥ 12 of 64 grid cells with mean |Δ| > 1 grey level between frame 0 and frame 2)"""
    for sc in SKY_SCENES_39113:
        k = f"{tag}/{cname}/{sfx}/{path}/{sc}"
        result_json("group", f"i396 sky {sc}", timeout=20); b381_home(); time.sleep(3)
        fs = [f"{OUT}/sky-{tag}-{cname}-{sfx}-{path}-{sc}-{i}.png" for i in range(3)]
        t0 = time.time()
        for i, f in enumerate(fs):
            while time.time() - t0 < 1.5 * i: time.sleep(0.05)
            subprocess.run(f"adb exec-out screencap -p > {f}", shell=True)
        st = result_json("group", "i396 skystate", timeout=15)
        try: n, mean = sky_cells_39113(fs[0], fs[2]); n1, _ = sky_cells_39113(fs[0], fs[1])
        except Exception as e: n, mean, n1 = -1, str(e)[:60], -1
        R[k] = {"cells3s": n, "cells1_5s": n1, "mean3s": mean, "mode": (st or {}).get("mode", "") if isinstance(st, dict) else str(st)[:200]}
        print("I39113 SKYALL", k, json.dumps(R[k], ensure_ascii=False), flush=True)
        if tag == "new" and not (isinstance(n, int) and n >= 12): selftest_fail.append(f"i39113 sky not visibly moving in 3 s: {k} {R[k]}")
        if not keep: subprocess.run("rm -f " + " ".join(fs), shell=True)


PERF_ONLY = os.environ.get("MINES_PERF_ONLY") == "1"


PERFETTO_CFG_39113 = """
buffers: { size_kb: 98304 fill_policy: DISCARD }
buffers: { size_kb: 32768 fill_policy: DISCARD }
data_sources: { config { name: "linux.ftrace" target_buffer: 0 ftrace_config {
  ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_wakeup" ftrace_events: "sched/sched_waking"
  ftrace_events: "sched/sched_blocked_reason" ftrace_events: "ftrace/print"
  atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "am" atrace_categories: "wm" atrace_categories: "dalvik"
  atrace_categories: "input" atrace_categories: "binder_driver" atrace_categories: "res" atrace_categories: "sched"
  atrace_apps: "%PKG%" } } }
data_sources: { config { name: "linux.process_stats" target_buffer: 1 process_stats_config { scan_all_processes_on_start: true } } }
data_sources: { config { name: "android.surfaceflinger.frametimeline" target_buffer: 1 } }
data_sources: { config { name: "linux.perf" target_buffer: 1 perf_event_config { timebase { frequency: 500 }
  callstack_sampling { scope { target_cmdline: "%PKG%" } kernel_frames: false } } } }
duration_ms: %DUR%
"""


def perf_transitions_39113(tag, chat_node):
    """home → chat, chat → settings (in-app route), settings → back, chat → back home. Each: gfxinfo reset, the switch,
    3 s, then framestats → frames, janky (>16.7 ms) %, slowest frame ms. A trace_marker 'mines-T:<name>:B/E' (and
    /proc/uptime as a fallback) brackets each switch in the Perfetto trace, analysed by perfetto39113.py."""
    T = {}
    def framestats():
        g = sh(f"dumpsys gfxinfo {PKG} framestats", timeout=60)
        rows, hdr = [], None
        for line in g.splitlines():
            line = line.strip()
            if line.startswith("Flags,"): hdr = line.rstrip(",").split(","); continue
            if hdr and re.match(r"^\d+,", line):
                v = line.rstrip(",").split(",")
                if len(v) >= len(hdr):
                    d = dict(zip(hdr, v))
                    try:
                        if int(d["Flags"]) & 0x1: continue   # skip only WINDOW_VISIBILITY_CHANGED; first-draw / slow frames stay
                        iv, fc = int(d["IntendedVsync"]), int(d["FrameCompleted"])
                        if fc > iv > 0: rows.append((fc - iv) / 1e6)
                    except Exception: pass
            elif hdr and line.startswith("---PROFILEDATA---") and rows: hdr = None
        j = sum(1 for x in rows if x > 16.7)
        return {"frames": len(rows), "janky_pct": round(100.0 * j / len(rows), 1) if rows else None,
                "slowest_ms": round(max(rows), 1) if rows else None, "over_32ms": sum(1 for x in rows if x > 32),
                "p50_ms": round(sorted(rows)[len(rows) // 2], 1) if rows else None}
    def mark(txt):
        for f in ("/sys/kernel/tracing/trace_marker", "/sys/kernel/debug/tracing/trace_marker"):
            if "denied" not in sh(f"echo '{txt}' > {f} 2>&1; echo rc=$?") : break
        return sh("cat /proc/uptime").split()[0]
    dur = 180000
    cfg = PERFETTO_CFG_39113.replace("%PKG%", PKG).replace("%DUR%", str(dur))
    trace = f"/data/misc/perfetto-traces/mines-{tag}.pftrace"
    sh(f"rm -f {trace}")
    open("/tmp/pf39113.pbtxt", "w").write(cfg); adb("push", "/tmp/pf39113.pbtxt", "/data/local/tmp/pf39113.pbtxt")
    # config through stdin (Android 12+: traced cannot read a file in /data/local/tmp); --background prints the pid and detaches
    pfo = sh(f"cat /data/local/tmp/pf39113.pbtxt | perfetto --txt -c - -o {trace} --background 2>&1", timeout=60)
    print("perfetto start:", pfo.strip()[-500:], flush=True)
    m = re.findall(r"^\s*(\d+)\s*$", pfo, re.M); pfpid = m[-1] if m else None
    time.sleep(4)
    def one(name, action):
        sh(f"dumpsys gfxinfo {PKG} reset"); time.sleep(0.5)
        b = mark(f"mines-T:{name}:B"); ok = action(); time.sleep(3.0); e = mark(f"mines-T:{name}:E")
        r = framestats(); r["ok"] = bool(ok); r["uptime"] = [b, e]; T[name] = r
        print(f"PERFT {tag} {name} {json.dumps(r)}", flush=True)
    go_home(); time.sleep(3)
    def to_chat():
        c = chat_node()
        if c: sh(f"input tap {c[0]} {c[1]}")
        return bool(c)
    for rep in (1, 2):
        one(f"home_to_chat{rep}", to_chat)
        one(f"chat_to_settings{rep}", lambda: (selftest("route", "settings", timeout=20), True)[1])
        one(f"settings_back{rep}", lambda: (sh("input keyevent KEYCODE_BACK"), True)[1])
        one(f"chat_back_home{rep}", lambda: (sh("input keyevent KEYCODE_BACK"), True)[1])
        go_home(); time.sleep(2)
    time.sleep(1)
    print("perfetto stop:", sh(f"kill -TERM {pfpid} 2>&1" if pfpid else "pkill -TERM -x perfetto 2>&1"), flush=True)   # SIGTERM = flush + write
    for _ in range(40):
        if not sh("pidof perfetto").strip(): break
        time.sleep(1)
    print("perfetto trace:", sh(f"ls -la {trace} 2>&1"), flush=True)
    name = f"perf-{tag}.pftrace"
    print(adb("pull", trace, f"{OUT}/{name}"), flush=True)
    T["trace"] = name if os.path.exists(f"{OUT}/{name}") else None
    if T["trace"]:
        try:
            r = subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "perfetto39113.py"), f"{OUT}/{name}", PKG],
                               capture_output=True, text=True, timeout=600)
            print("PERFETTO39113 " + tag + " " + (r.stdout or "")[-6000:] + (r.stderr or "")[-1500:], flush=True)
        except Exception as ex: print("perfetto analysis failed", ex, flush=True)
    return T


def perf_39113(tag):
    """[38.9.11.3] speed before / after on the same emulator, adb only (works on 38.9.11.2 too): cold start (am start -W, x5),
    PSS after idle, janky frames while scrolling home + opening a chat + scrolling it (dumpsys gfxinfo), and home → chat
    open time from a screenrecord of the tap (sky set static for that part only; analysed offline: first changed frame →
    settled)."""
    P = {"tag": tag}
    def total_ms(out):
        m = re.search(r"TotalTime:\s*(\d+)", out or ""); return int(m.group(1)) if m else None
    ts = []
    for i in range(5):
        sh(f"am force-stop {PKG}"); time.sleep(2.5)
        ts.append(total_ms(sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity", timeout=60))); time.sleep(7)
    P["cold_ms"] = ts
    dismiss_dialogs(); go_home(); time.sleep(10)
    mi = sh(f"dumpsys meminfo {PKG}", timeout=60)
    m = re.search(r"TOTAL PSS:\s*(\d+)", mi) or re.search(r"\n\s*TOTAL\s+(\d+)", mi)
    P["pss_kb"] = int(m.group(1)) if m else None
    def chat_node():
        root = dump()
        for t, d, x, y, n in nodes(root):
            if ("hello" in t.lower() or "hello" in d.lower()) and y > 300: return (x, y)
        # [38.9.11.3] home's 最近对话 row shows the last message (GARBAGETEST / the reply), not "hello"
        for t, d, x, y, n in nodes(root):
            if any(k in (t + d) for k in ("GARBAGETEST", "你好，我是测试模型")) and y > 300: return (x, y)
        return None
    def gfx():
        g = sh(f"dumpsys gfxinfo {PKG}", timeout=60)
        out = {}
        for k, rx in (("frames", r"Total frames rendered:\s*(\d+)"), ("janky", r"Janky frames:\s*(\d+)"), ("janky_pct", r"Janky frames:\s*\d+\s*\(([\d.]+)%\)"),
                      ("p50", r"50th percentile:\s*(\d+)ms"), ("p90", r"90th percentile:\s*(\d+)ms"), ("p95", r"95th percentile:\s*(\d+)ms"), ("p99", r"99th percentile:\s*(\d+)ms")):
            mm = re.search(rx, g); out[k] = float(mm.group(1)) if mm else None
        return out
    # janky frames with the sky as the user has it
    sh(f"dumpsys gfxinfo {PKG} reset")
    for _ in range(3): sh("input swipe 540 1800 540 900 350"); time.sleep(0.8); sh("input swipe 540 900 540 1800 350"); time.sleep(0.8)
    c = chat_node()
    if c:
        sh(f"input tap {c[0]} {c[1]}"); time.sleep(3)
        for _ in range(3): sh("input swipe 540 1500 540 900 300"); time.sleep(0.7); sh("input swipe 540 900 540 1500 300"); time.sleep(0.7)
        sh("input keyevent KEYCODE_BACK"); time.sleep(2.5)
    else:
        UNTESTABLE.append(f"perf {tag}: no chat row on home to open")
    P["gfx"] = gfx()
    # [38.9.11.3 f] page switches (user: 「切页面卡在半路」): per transition gfxinfo framestats (janky %, slowest frame) inside one
    # Perfetto trace (sched + atrace gfx/view/am/wm/dalvik/input/binder + frametimeline + main-thread callstack sampling)
    try: P["transitions"] = perf_transitions_39113(tag, chat_node)
    except Exception as ex: P["transitions"] = {"error": str(ex)}; print("perf transitions error", ex, flush=True)
    # home → chat open: screenrecord around the tap, sky static so only the page change moves
    try: result_json("group", "i396 skymode off", timeout=20)
    except Exception: pass
    time.sleep(2)
    P["open_videos"] = []
    for i in range(3):
        go_home(); time.sleep(2.5)
        c = chat_node()
        if not c: UNTESTABLE.append(f"perf {tag}: open video {i}: no chat row on home"); break
        sh("rm -f /sdcard/open.mp4")
        pr = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "6", "--bit-rate", "8000000", "/sdcard/open.mp4"])
        time.sleep(2.0); sh(f"input tap {c[0]} {c[1]}")
        try: pr.wait(timeout=20)
        except Exception: pr.kill()
        time.sleep(1); name = f"perf-{tag}-open{i}.mp4"
        print(adb("pull", "/sdcard/open.mp4", f"{OUT}/{name}"), flush=True); P["open_videos"].append(name)
        sh("input keyevent KEYCODE_BACK"); time.sleep(2)
    try: result_json("group", "i396 skymode smooth", timeout=20)
    except Exception: pass
    go_home()
    print("PERF39113 " + json.dumps(P, ensure_ascii=False), flush=True)
    return P


def b3912(res):
    """[38.9.12] screenshots of the new / changed pages (light + dark) + the 账号自动调用 gate (GitHub-via-Google page with no
    台账 password must press the provider button, never pop the password card, never type a password) + NEXUS HUD frames."""
    o = res.setdefault("i3912", {})
    def shot(name):
        r = snap("i3912-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i3912-{name}.png {OUT}/i3912-{name}.png", shell=True)
        return r
    def step(name, f):
        t0 = time.time()
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i3912 {name} error {e}")
        print(f"I3912 STEP {name} {int(time.time() - t0)} s", flush=True)
        if not alive(): crashed.append(f"i3912 {name}"); recover(f"i3912 {name}")
    set_zh()
    try: result_json("group", "wallpaper off")
    except Exception: pass
    def autologin():
        r = result_json("group", "i3912 autologin", timeout=60) or {}
        o["autologin"] = r; print("I3912 AUTOLOGIN " + json.dumps(r, ensure_ascii=False), flush=True)
        if r.get("cardShown"): selftest_fail.append("i3912 autologin: password card popped for an account that signs in via Google")
        if str(r.get("clicked")) != "1": selftest_fail.append(f"i3912 autologin: 'Continue with Google' not pressed: {r}")
        if str(r.get("pwLen")) not in ("0", ""): selftest_fail.append(f"i3912 autologin: a password was typed: {r}")
        if "Continue with Google" not in str(r.get("note")): selftest_fail.append(f"i3912 autologin: agent note wrong: {r.get('note')}")
    def light_pages():
        theme388(1); healthy_home(); shot("home-light")
        route("plus_apps_hub", 5); shot("apps-light")
        route("plus_auto_login", 4); shot("autologin-light")
        route("plus_work_pool", 14); shot("workpool-light")
        o["pool"] = result_json("group", "i3912 pool", timeout=15)
        back(1); time.sleep(1)
    def kb():
        theme388(2); healthy_home()
        result_json("group", "i3912 kbseed", timeout=30)
        route("plus_knowledge", 5)
        k0 = result_json("group", "i3912 kbstate", timeout=15) or {}
        shot("kb"); time.sleep(3)
        k1 = result_json("group", "i3912 kbstate", timeout=15) or {}
        o["kb_frames"] = [k0.get("frames"), k1.get("frames")]
        try:
            if not (int(k1.get("frames", 0)) > int(k0.get("frames", 0)) + 5): selftest_fail.append(f"i3912 知识库 HUD not animating: {o['kb_frames']}")
        except Exception as e: selftest_fail.append(f"i3912 kb frames {e}")
        shot("kb-2")
        result_json("group", "i3912 kbselect 0", timeout=15); time.sleep(4)
        o["kb_sel"] = (result_json("group", "i3912 kbstate", timeout=15) or {}).get("selected")
        r = shot("kb-focus")
        if not o["kb_sel"]: selftest_fail.append("i3912 知识库: topic focus did not select")
        if r is not None and not (find(r, "在对话中引用") and find(r, "生成主题摘要")): page_missing.append("i3912 kb detail buttons not in dump")
        result_json("group", "i3912 kbselect -1", timeout=15); time.sleep(2)
        # battery: frames stop while the app is in the background
        sh("input keyevent KEYCODE_HOME"); time.sleep(2)
        b0 = (result_json("group", "i3912 kbstate", timeout=15) or {}).get("frames"); time.sleep(4)
        b1 = (result_json("group", "i3912 kbstate", timeout=15) or {}).get("frames")
        o["kb_bg_frames"] = [b0, b1]
        if b0 is not None and b1 is not None and int(b1) - int(b0) > 3: selftest_fail.append(f"i3912 知识库 HUD keeps drawing in the background: {o['kb_bg_frames']}")
        adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(5)
        result_json("group", "i3912 kbclean", timeout=30)
    def dark_pages():
        healthy_home(); shot("home-dark")
        sh(f"am start -n {PKG}/com.openminis.app.MainActivity --es mines_route settings"); time.sleep(3)
        route("settings", 4); shot("settings-dark")
        route("plus_apps_hub", 4); shot("apps-dark")
        route("plus_auto_login", 4); shot("autologin-dark")
        route("plus_quick_connect", 4); shot("connect-dark")
        route("plus_register_ledger", 5); shot("register-dark")
    def pages2():   # [38.9.12 b] 消息 验证码 tab + 摘要 · 注册台账 · 快速连接 · 来电 needs-attention gate
        theme388(1); healthy_home()
        c0 = result_json("group", "i3912 mailcodes", timeout=20) or {}
        o["codes"] = c0
        try:
            if int(c0.get("codes", 0)) < 2 or int(c0.get("fresh", 0)) < 1: selftest_fail.append(f"i3912 验证码: codes not extracted {c0}")
        except Exception as e: selftest_fail.append(f"i3912 验证码 readout {e} {c0}")
        route("plus_digest", 5)
        if open_tab("验证码"):
            time.sleep(1); r = shot("codes-light")
            if r is not None and not find(r, "482 917"): page_missing.append("i3912 验证码 tab: 482 917 not shown")
            if r is not None and not find(r, "复制"): page_missing.append("i3912 验证码 tab: 复制 missing")
        else: page_missing.append("i3912 消息: 验证码 tab not found")
        if open_tab("邮箱"):
            time.sleep(1); r = shot("mail-light")
            if r is not None and not (find(r, "摘要") or find(r, "邮件摘要")): page_missing.append("i3912 邮箱: 摘要 card missing")
        else: page_missing.append("i3912 消息: 邮箱 tab not found")
        result_json("group", "i3912 mailoff", timeout=15)
        route("plus_register_ledger", 6); r = shot("register-light")
        if r is not None and not find(r, "一键注册"): page_missing.append("i3912 注册台账: 一键注册 missing")
        o["reg"] = result_json("group", "i3912 reg", timeout=15)
        route("plus_quick_connect", 5); r = shot("connect-light")
        if r is not None and not (find(r, "钥匙服务") and find(r, "添加服务器")): page_missing.append("i3912 快速连接: 钥匙服务 / 添加服务器 missing")
        route("plus_apps_hub", 4); r = shot("apps2-light")
        if r is not None and not (find(r, "注册台账") and find(r, "快速连接")): page_missing.append("i3912 应用: new tiles missing")
        a = selftest("group", "i3912 attn", timeout=15)
        o["attn"] = str(a)[-160:]
        if "ring=true" in str(a): selftest_fail.append(f"i3912 来电: needs-attention would ring while Mines is in the foreground: {a}")
        back(1); time.sleep(1)
    def call():
        result_json("group", "i3912 call", timeout=15); time.sleep(4)
        r = shot("call")
        if r is None or not (find(r, "接听") and find(r, "挂断") and find(r, "转文字")): selftest_fail.append("i3912 来电 screen buttons missing")
        tap_text("挂断", exact=True, wait=3)
        if not alive(): crashed.append("i3912 call hangup")
    step("autologin", autologin)
    step("light", light_pages)
    step("kb", kb)
    step("dark", dark_pages)
    step("pages2", pages2)
    step("call", call)
    try: result_json("group", "i3912 autoclean", timeout=20)
    except Exception: pass
    theme388(1)
    print("I3912 RESULT " + json.dumps(o, ensure_ascii=False)[:6000], flush=True)


def b39121(res):
    """[38.9.12.1] OOM hotfix: reproduce the user's 2026-10-23 state — 5 agent sessions holding slots, one ~300-message chat
    (newest, so the old auto-reopen would land in it), repeated browser screenshots in 5 pools at once on a desktop viewport
    (≈ 9 Mpx, the size that asked for 36 MB) — then an OutOfMemoryError on the main thread through the real crash path, and a
    relaunch that must start into safe mode: home (not the long chat), card 「上次内存不足退出，已暂停 5 个任务」, 0 crash."""
    o = res.setdefault("i39121", {})
    def shot(name):
        r = snap("i39121-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i39121-{name}.png {OUT}/i39121-{name}.png", shell=True)
        return r
    def fail(m): selftest_fail.append("i39121 " + m); print("I39121 FAIL " + m, flush=True)
    set_zh(); healthy_home()
    # (A) stale ":acra" process holding WebView while the main process launches (user's crash loop 07:20, restartCount 122)
    try:
        a = result_json("group", "i39121 acrastart", timeout=60) or {}
        o["acra_start"] = a; print("I39121 ACRA " + json.dumps(a, ensure_ascii=False), flush=True)
        if not any(":acra" in str(p) for p in (a.get("procs") or [])): fail(f":acra probe process not running: {a}")
        if a.get("err"): fail(f":acra probe WebView failed: {a.get('err')}")
        sh("logcat -b crash -c")
        result_json("group", "i39121 killmain", timeout=20)
        for _ in range(15):
            time.sleep(1)
            if not alive(): break
        o["acra_alive_after_kill"] = sh(f"pidof {PKG}:acra").strip()
        if not o["acra_alive_after_kill"]: fail(":acra probe died with the main process (test not meaningful)")
        print(sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity", timeout=90)[-300:], flush=True); time.sleep(12)
        o["acra_main_alive"] = alive()
        if not o["acra_main_alive"]: fail("main process not running after launch beside a live :acra"); crashed.append("i39121 acra"); recover("i39121 acra")
        w = result_json("group", "i39121 webcheck", timeout=40) or {}
        o["acra_webcheck"] = w; print("I39121 ACRA WEBCHECK " + json.dumps(w, ensure_ascii=False), flush=True)
        if "webcheck-ok" not in str(w.get("title")): fail(f"WebView in main beside :acra failed: {w}")
        cb = adb("logcat", "-d", "-b", "crash", timeout=30)
        o["acra_lock_crash"] = "more than one process" in cb
        if o["acra_lock_crash"] or "FATAL EXCEPTION" in cb: fail("crash after launching beside :acra: " + cb[-800:])
        shot("acra-main")
        set_zh(); healthy_home()
    except Exception as e:
        import traceback; traceback.print_exc(); fail(f"acra step error {e}")
    # (B) 首页 reverted to 38.9.11.3.1 (no 今日概览) · 来电 转文字 / 接听 stay on the call screen
    try:
        r = healthy_home(); shot("home")
        for bad in ("今日概览", "账号巡查"):
            if r is not None and find(r, bad): fail(f"home still shows {bad}")
        sh(f"pm grant {PKG} android.permission.RECORD_AUDIO")
        result_json("group", "i3912 call", timeout=20); time.sleep(4); rr = shot("call-ring")
        if rr is not None and any("示例" in t for t in texts(rr)): fail("ring screen still says 示例")
        if tap_text("转文字", exact=True, wait=4):
            r = dump(); shot("call-text")
            o["call_text"] = result_json("group", "i39121 callstate", timeout=15)
            if not (r is not None and find(r, "打开对话") and find(r, "Vultr")): fail(f"转文字 did not show the text on the call screen: {o['call_text']}")
            tap_text("关闭", exact=True, wait=2)
        else: fail("转文字 button missing")
        result_json("group", "i3912 call", timeout=20); time.sleep(4)
        if tap_text("接听", exact=True, wait=10):
            r = dump(); shot("call-incall")
            o["call_in"] = result_json("group", "i39121 callstate", timeout=15)
            print("I39121 CALL " + json.dumps(o["call_in"], ensure_ascii=False), flush=True)
            if not (r is not None and find(r, "通话中") and find(r, "静音") and find(r, "挂断")): fail(f"接听 did not turn into the in-call screen: {o['call_in']}")
            if r is not None and find(r, "实时语音"): fail("接听 jumped to the 实时语音 page")
            if r is not None and not (find(r, "扬声器") and any(re.search(r"通话中 \d\d:\d\d", t) for t in texts(r))): fail("in-call timer / 扬声器 missing")
            if r is not None and any("示例" in t for t in texts(r)): fail("in-call screen says 示例")
            act = sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -2")
            o["call_top"] = act.strip()[-200:]
            if "MinesCallActivity" not in act: fail(f"接听 left the call screen: {act.strip()[-200:]}")
            time.sleep(6)
            r2 = dump(); shot("call-incall-6s")
            o["call_in_6s"] = result_json("group", "i39121 callstate", timeout=15)
            if r2 is None or not find(r2, "挂断"): fail("call screen gone 6 s after 接听")
            if tap_text("静音", exact=True, wait=1.5):
                o["call_muted"] = result_json("group", "i39121 callstate", timeout=15)
                if not (isinstance(o["call_muted"], dict) and o["call_muted"].get("muted") is True): fail(f"静音 did not toggle: {o['call_muted']}")
                tap_text("取消静音", exact=True, wait=1)
            else: fail("静音 button missing")
            if tap_text("扬声器", wait=1.5):
                o["call_speaker"] = result_json("group", "i39121 callstate", timeout=15)
            else: fail("扬声器 button missing")
            if not alive(): fail("app died in the call"); crashed.append("i39121 call"); recover("i39121 call")
            else: tap_text("挂断", exact=True, wait=3)
        else: fail("接听 button missing")
        if not alive(): fail("app died after hang-up"); crashed.append("i39121 hangup"); recover("i39121 hangup")
        healthy_home()
    except Exception as e:
        import traceback; traceback.print_exc(); fail(f"home/call step error {e}")
    # (C) 知识库 HUD (kb_v3b): sphere between search and 最近收录, ≤ 8 nodes, stats row, frames advancing
    try:
        healthy_home(); result_json("group", "i3912 kbseed", timeout=30)
        route("plus_knowledge", 5)
        k0 = result_json("group", "i3912 kbstate", timeout=15) or {}
        r = shot("kb"); time.sleep(3)
        k1 = result_json("group", "i3912 kbstate", timeout=15) or {}
        o["kb_frames"] = [k0.get("frames"), k1.get("frames")]
        try:
            if not (int(k1.get("frames", 0)) > int(k0.get("frames", 0)) + 5): fail(f"知识库 HUD not animating: {o['kb_frames']}")
        except Exception as e: fail(f"kb frames {e}")
        o["kb_texts"] = texts(r)[:60] if r is not None else []
        for lab in ("ENTRIES", "CLUSTERS", "LAST SYNC", "INDEXED", "RECENT INGEST"):
            if r is not None and not find(r, lab): fail(f"知识库: {lab} missing")
        shot("kb-2")
        if not alive(): fail("app died on 知识库"); crashed.append("i39121 kb"); recover("i39121 kb")
        result_json("group", "i3912 kbclean", timeout=30)
        back(1); time.sleep(1)
    except Exception as e:
        import traceback; traceback.print_exc(); fail(f"kb step error {e}")
    # (D) 验证码与AI邮箱 → 高级 · 可选 → 自建域名 / Cloudflare → 开通 opens the config dialog
    try:
        healthy_home(); route("plus_code_sources", 5); shot("codesrc")
        ok = tap_text("高级 · 可选", exact=True, wait=2)
        if not ok:
            scroll_down(); ok = tap_text("高级 · 可选", exact=True, wait=2)
        if not ok: fail("高级 · 可选 row missing")
        else:
            for _ in range(3):
                r = dump()
                if find(r, "自建域名 / Cloudflare"): break
                scroll_down()
            r = dump(); shot("mail-adv")
            if not find(r, "自建域名 / Cloudflare"): fail("自建域名 / Cloudflare row missing")
            # tap the 开通 pill that sits below the 自建域名 row (the last exact 开通 on screen)
            row = find(r, "自建域名 / Cloudflare")
            pills = [(x, y) for t, d, x, y, n in nodes(r) if (t == "开通" or d == "开通") and row and y > row[1]]
            if not pills: scroll_down(); r = dump(); row = find(r, "自建域名 / Cloudflare"); pills = [(x, y) for t, d, x, y, n in nodes(r) if (t == "开通" or d == "开通") and (not row or y > row[1])]
            if pills:
                sh(f"input tap {pills[0][0]} {pills[0][1]}"); time.sleep(2)
                r = dump(); shot("mail-adv-dialog")
                o["selfhost_dialog"] = bool(find(r, "开通自建域名收件"))
                if not o["selfhost_dialog"]: fail("自建域名 开通 did not open the config dialog")
                if not find(r, "从剪贴板粘贴"): fail("自建域名 dialog: 从剪贴板粘贴 missing")
                tap_text("取消", exact=True, wait=1.5)
            else: fail("自建域名 开通 pill missing")
        if not alive(): fail("app died on 验证码与AI邮箱"); crashed.append("i39121 codesrc"); recover("i39121 codesrc")
        back(1); time.sleep(1)
    except Exception as e:
        import traceback; traceback.print_exc(); fail(f"mail adv step error {e}")
    o["heap0"] = result_json("group", "i3910 heap", timeout=20)
    seed = result_json("group", "i39121 seed", timeout=400) or {}
    o["seed"] = seed; print("I39121 SEED " + json.dumps(seed, ensure_ascii=False)[:600], flush=True)
    ids = seed.get("ids") if isinstance(seed, dict) else None
    if not ids or len(ids) != 5: fail(f"seed failed: {seed}"); return
    try:
        if int(seed.get("messages", 0)) < 290: fail(f"long chat has {seed.get('messages')} messages")
    except Exception: pass
    sl = result_json("group", "i39121 slots", timeout=60) or {}
    o["slots"] = sl; print("I39121 SLOTS " + json.dumps(sl, ensure_ascii=False), flush=True)
    if not isinstance(sl, dict) or int(sl.get("running", 0)) != 5: fail(f"5 slots not held: {sl}")
    o["shots"] = []
    for rnd in range(3):
        t0 = time.time()
        x = result_json("group", "i39121 shots 6", timeout=900) or {}
        o["shots"].append(x); print(f"I39121 SHOTS round {rnd} {int(time.time() - t0)} s " + json.dumps(x, ensure_ascii=False)[:1500], flush=True)
        if not alive(): fail(f"app died during screenshot round {rnd}"); crashed.append("i39121 shots"); recover("i39121 shots"); return
        pools = x.get("pools") if isinstance(x, dict) else None
        if not pools: fail(f"round {rnd}: no result {x}"); continue
        for i, p in enumerate(pools):
            if int(p.get("err", 0)) > 0 or int(p.get("ok", 0)) < 6: fail(f"round {rnd} pool {i}: {p}")
            if int(p.get("maxW", 0)) > 1280: fail(f"round {rnd} pool {i}: screenshot {p.get('maxW')} px wide (> 1280)")
    # the long chat opens and renders (300 messages, nothing auto-resumes)
    route("chat/" + ids[0], 8); shot("long-chat")
    if not alive(): fail("app died opening the long chat"); crashed.append("i39121 long chat"); recover("i39121 long chat"); return
    o["heap_long"] = result_json("group", "i3910 heap", timeout=20)
    back(1); time.sleep(2); healthy_home()
    # still 5 slots (the 5 'running' tasks), then the OOM on the main thread
    result_json("group", "i39121 slots", timeout=60)
    route("chat/" + ids[0], 6)   # die while the long chat is open, like the phone
    o["gate"] = result_json("group", "i39121 gate", timeout=20)
    k = result_json("group", "i39121 oomkill", timeout=20) or {}
    o["oomkill"] = k
    dead = False
    for _ in range(20):
        time.sleep(1)
        if not alive(): dead = True; break
    o["killed"] = dead
    if not dead: fail("OOM simulation did not end the process")
    # relaunch: must come up in safe mode
    t0 = time.time()
    print(sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity", timeout=90)[-300:], flush=True)
    time.sleep(12)
    o["relaunch_s"] = int(time.time() - t0)
    o["alive_after"] = alive()
    if not o["alive_after"]: fail("app not running after relaunch"); crashed.append("i39121 relaunch"); recover("i39121 relaunch"); return
    r = dump(); txt = texts(r)
    o["home_after"] = bool(find(r, "最近对话"))
    o["card_after"] = next((t for t in txt if "上次内存不足退出" in t), "")
    o["long_chat_reopened"] = any("第 1" in t and "步" in t for t in txt)
    st = result_json("group", "i39121 safe", timeout=20) or {}
    o["safe"] = st; print("I39121 SAFE " + json.dumps(st, ensure_ascii=False)[:900], flush=True)
    shot("safe-start")
    if not o["home_after"]: fail("relaunch did not land on home")
    if o["long_chat_reopened"]: fail("relaunch reopened the long chat")
    if "已暂停 5 个任务" not in o["card_after"]: fail(f"safe-start card wrong: {o['card_after']!r} {st}")
    if not (isinstance(st, dict) and st.get("forceHome") is True): fail(f"forceHome not set: {st}")
    time.sleep(20)
    if not alive(): fail("app died 30 s after the safe start"); crashed.append("i39121 after relaunch"); recover("i39121 after relaunch"); return
    shot("safe-start-30s")
    # 继续 on the first task opens that chat (nothing resumed by itself); the card keeps the other 4
    if tap_text("继续", exact=True, wait=6):
        shot("continue"); o["continue_alive"] = alive()
        if not o["continue_alive"]: fail("app died after 继续"); crashed.append("i39121 continue"); recover("i39121 continue"); return
        back(1); time.sleep(2)
        r = healthy_home(); o["card_after_continue"] = next((t for t in texts(r) if "上次内存不足退出" in t), "")
        shot("after-continue")
        if "已暂停 4 个任务" not in o["card_after_continue"]: fail(f"card after 继续: {o['card_after_continue']!r}")
    else:
        fail("继续 button not found")
    # a second cold start is a normal one (forceHome only once), the card stays until 知道了
    sh(f"am force-stop {PKG}"); time.sleep(2); sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity", timeout=90); time.sleep(10)
    o["second_start"] = result_json("group", "i39121 safe", timeout=20)
    healthy_home()
    if tap_text("知道了", exact=True, wait=3): o["dismissed"] = True
    else: fail("知道了 not found on home after the second start")
    o["after_dismiss"] = result_json("group", "i39121 safe", timeout=20)
    shot("dismissed")
    result_json("group", "i39121 clean", timeout=120)
    o["heap_end"] = result_json("group", "i3910 heap", timeout=20)
    print("I39121 RESULT " + json.dumps(o, ensure_ascii=False)[:12000], flush=True)



# ======================= [38.9.13 integ] task projects / VPS page / handoff v2 / memory v2 / Mines browser =======================
def b3914(res):
    """[38.9.14 chat_home] cold launch lands on 聊天 (main conversation) with the 4-tab dock; 「帮我注册 zeabur」 → router says task →
    「已建任务」 chip, the task runs in its own conversation in the background (进行中 → 完成, notice in 聊天), tap chip opens the task;
    「今天几号」 → inline answer, no new task; dock 任务 (等你处理 / 进行中 / 已完成) / 消息 / 应用 (知识库 first) / 聊天; 实验功能 row."""
    o = res.setdefault("i3914", {})
    def shot(name):
        r = snap("i3914-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i3914-{name}.png {OUT}/i3914-{name}.png", shell=True)
        return r
    def fail(m): selftest_fail.append("i3914 " + m); print("I3914 FAIL " + m, flush=True)
    def state():
        s = result_json("group", "i3914 state", timeout=20)
        return s if isinstance(s, dict) else {}
    def fake():
        try: return json.loads(subprocess.run("curl -s http://127.0.0.1:8088/stats", shell=True, capture_output=True, text=True, timeout=10).stdout)
        except Exception: return {}
    def wait_state(pred, secs):
        t0 = time.time(); s = state()
        while time.time() - t0 < secs:
            if pred(s): return s
            time.sleep(2); s = state()
        return s
    def step(name, f):
        t0 = time.time()
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); fail(f"{name} error {e}")
        print(f"I3914 STEP {name} {int(time.time() - t0)} s", flush=True)
        if not alive(): crashed.append(f"i3914 {name}"); recover(f"i3914 {name}")
    def tap_dock(label):
        r = dump(); best = None
        for t, d, x, y, n in nodes(r) if r is not None else []:
            if (t == label or d == label or (d or "").startswith(label + "，")) and y > SCREEN[1] * 0.8: best = (x, y)
        if best: sh(f"input tap {best[0]} {best[1]}"); time.sleep(3); return True
        return False

    set_zh(); o["seedmodel"] = seed_model()
    try: result_json("group", "wallpaper off")
    except Exception: pass

    def launch():
        sh(f"am force-stop {PKG}"); time.sleep(2)
        sh(f"am start -W -n {PKG}/com.openminis.app.MainActivity", timeout=90); time.sleep(10); dismiss_dialogs()
        r = shot("launch"); t = _txt(r); o["launch_txt"] = t[:300]
        s = state(); o["state0"] = s
        if not s.get("on"): fail(f"flag chat_home not on by default: {s}")
        if not s.get("main"): fail("no main conversation after launch")
        miss = [w for w in ("聊天", "任务", "消息", "应用") if w not in t]
        if miss: fail(f"launch: not on 聊天 with the dock (missing {miss})")
        if "最近对话" in t: fail("launch: old conversation list shown instead of 聊天")
    step("launch", launch)

    def task():
        out = result_json("group", "i3914 say 帮我注册 zeabur", timeout=40); o["say_task"] = out
        s = wait_state(lambda s: len(s.get("tasks") or []) >= 1, 40); o["state_task"] = s
        ts = s.get("tasks") or []
        if not ts: fail(f"no task created for 帮我注册 zeabur: {s}"); return
        t = ts[-1]; o["task"] = t
        if "Zeabur" not in t.get("name", ""): fail(f"task name not the project: {t.get('name')}")
        if not str(s.get("last", "")).startswith("TASK"): fail(f"router decision not TASK: {s.get('last')}")
        time.sleep(2); r = shot("chip-running"); txt = _txt(r)
        if "已建任务" not in txt: fail("chip 已建任务 not shown in 聊天")
        o["chip_running_state"] = t.get("st")
        if t.get("st") not in ("queued", "running"): print(f"I3914 note: task already {t.get('st')} at first look", flush=True)
        rows = s.get("rows") or []
        if not any(x.get("tag", "").startswith("CREATED") for x in rows): fail(f"no CREATED row in 聊天: {rows[-3:]}")
        if any(x.get("role") == "assistant" and not x.get("tag") for x in rows[-2:]): fail("main chat got a model reply for the task message (tool spam?)")
        s2 = wait_state(lambda s: any(x.get("st") == "done" for x in (s.get("tasks") or [])), 120); o["state_done"] = s2
        d = [x for x in (s2.get("tasks") or []) if x.get("st") == "done"]
        if not d: fail(f"task did not finish in background: {(s2.get('tasks') or [])}"); return
        o["task_done"] = d[-1]
        time.sleep(3); r = shot("chip-done"); txt = _txt(r)
        if not any(x.get("tag", "").startswith("DONE") for x in (s2.get("rows") or [])): fail("no DONE notice row in 聊天")
        if "回复你" not in txt: fail("完成 notice card (「… · 回复你 HH:mm 的消息」) not visible in 聊天 after the task finished")
        if (fake().get("task_posts") or 0) < 1: fail("the task conversation never called the model")
    step("task", task)

    def open_chip():
        r = dump(); p = find(r, "已建任务")
        if not p: fail("chip not found to tap"); return
        sh(f"input tap {p[0]} {p[1]}"); time.sleep(4)
        r = shot("task-page"); t = _txt(r); o["task_page"] = t[:400]
        if "帮我注册 zeabur" not in t: fail("tapping the chip did not open the task conversation")
        back(1); time.sleep(2)
        r = dump()
        if r is None or "已建任务" not in _txt(r): fail("back from the task conversation did not return to 聊天")
    step("open_chip", open_chip)

    def quick():
        n0 = len(state().get("tasks") or [])
        result_json("group", "i3914 say 今天几号", timeout=40)
        s = wait_state(lambda s: any("10 月 25 日" in (x.get("text") or "") for x in (s.get("rows") or [])), 40); o["state_quick"] = s
        if len(s.get("tasks") or []) != n0: fail("今天几号 created a task")
        if not any("10 月 25 日" in (x.get("text") or "") for x in (s.get("rows") or [])): fail("今天几号 not answered inline")
        time.sleep(2); shot("quick-answer")
    step("quick", quick)

    def tabs():
        if not tap_dock("任务"): fail("dock 任务 not found"); return
        r = shot("tab-tasks"); t = _txt(r); o["tab_tasks"] = t[:500]
        for w in ("等你处理", "进行中", "已完成", "Zeabur"):
            if w not in t: fail(f"任务 page missing {w}")
        if not tap_dock("消息"): fail("dock 消息 not found")
        shot("tab-messages")
        if not tap_dock("应用"): fail("dock 应用 not found"); return
        r = shot("tab-apps"); t = _txt(r); o["tab_apps"] = t[:300]
        if "知识库" not in t: fail("应用: 知识库 missing")
        elif t.find("知识库") > t.find("浏览器") >= 0: fail("应用: 知识库 not first")
        if not tap_dock("聊天"): fail("dock 聊天 not found"); return
        r = shot("tab-chat")
        if "已建任务" not in _txt(r): fail("dock 聊天 did not return to the main conversation")
    step("tabs", tabs)

    def dark():
        try:
            theme388(2); time.sleep(3); shot("chat-dark")
            tap_dock("任务"); shot("tab-tasks-dark"); tap_dock("聊天"); theme388(1); time.sleep(2)
        except Exception as e: print("I3914 dark error", e, flush=True)
    step("dark", dark)

    def experiments():
        route("settings", 3); tap_text("测试与上报", wait=3)
        seen = set()
        for i in range(10):
            r = dump(); seen |= set(texts(r))
            if any("聊天主页" in x for x in seen): shot("settings-experiments"); break
            scroll_down()
        if not any("聊天主页" in x for x in seen): fail("实验功能: 聊天主页 row missing")
        back(2)
    step("experiments", experiments)
    print("I3914 RESULT " + json.dumps(o, ensure_ascii=False)[:6000], flush=True)


def b3913(res):
    """[38.9.13] integrated build: 设置 → 实验功能 (one section, every flag) + 浏览器验证 rows; VPS / 电脑 page (change a rule →
    已改 → 恢复默认); 任务 page (项目 / 已归档); 「帮我注册 zeabur.com」 → 立项 sheet without a password field → project; Google sign-in
    → handoff popup named after the project + glass bar + 继续接管; 项目详情; memory find (memory_v2); GeckoView engine reads a page
    and hands it to the user (MinesBrowserActivity)."""
    o = res.setdefault("i3913", {})
    def shot(name):
        r = snap("i3913-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i3913-{name}.png {OUT}/i3913-{name}.png", shell=True)
        return r
    def fail(m): selftest_fail.append("i3913 " + m); print("I3913 FAIL " + m, flush=True)
    def state(): 
        s = result_json("group", "i3913 state", timeout=20)
        return s if isinstance(s, dict) else {}
    def wait_for(label, secs=30):
        t0 = time.time()
        while time.time() - t0 < secs:
            r = dump()
            if r is not None and find(r, label): return r
            time.sleep(2)
        return None
    def fake(trig):
        try: return json.loads(subprocess.run("curl -s http://127.0.0.1:8088/stats", shell=True, capture_output=True, text=True, timeout=10).stdout).get("i3913", {}).get(trig, {})
        except Exception: return {}
    def wait_fake(trig, n, secs):
        t0 = time.time()
        while time.time() - t0 < secs:
            f = fake(trig)
            if len(f.get("results") or []) >= n: return f
            time.sleep(2)
        return fake(trig)
    def step(name, f):
        t0 = time.time()
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); fail(f"{name} error {e}")
        print(f"I3913 STEP {name} {int(time.time() - t0)} s", flush=True)
        if not alive(): crashed.append(f"i3913 {name}"); recover(f"i3913 {name}")

    set_zh(); o["seedmodel"] = seed_model(); healthy_home()
    try: result_json("group", "wallpaper off")
    except Exception: pass
    s0 = state(); o["state0"] = s0; print("I3913 STATE0 " + json.dumps(s0, ensure_ascii=False), flush=True)
    for k, v in (s0.get("flags") or {}).items():
        if v is not True: fail(f"flag {k} not on by default: {v}")
    if not s0.get("flags"): fail(f"no flags in state: {s0}")

    def experiments():
        route("settings", 3); tap_text("测试与上报", wait=3)
        seen = set()
        for i in range(8):
            r = dump(); seen |= set(texts(r))
            if find(r, "实验功能", exact=True) and i < 7:
                shot("settings-experiments"); break
            scroll_down()
        for _ in range(3):
            scroll_down(); r = dump(); seen |= set(texts(r))
        shot("settings-experiments-2")
        want = ("新版 VPS / 电脑 页", "任务项目", "分类记忆", "交接 v2（验证/登录后自动继续）", "回到你操作的那个标签页", "打开 Mines 浏览器", "内网穿透")
        miss = [w for w in want if not any(w in t for t in seen)]
        o["experiments_missing"] = miss
        if miss: fail(f"实验功能 rows missing: {miss}")
        back(2); time.sleep(1)
        route("plus_verify", 3); seen = set()
        for _ in range(6):
            r = dump(); seen |= set(texts(r))
            if any("打开 Mines 浏览器" in t for t in seen): break
            scroll_down()
        shot("verify-rows")
        miss = [w for w in ("交接 v2（验证/登录后自动继续）", "打开 Mines 浏览器") if not any(w in t for t in seen)]
        if miss: fail(f"浏览器验证 rows missing: {miss}")
        back(1); time.sleep(1)

    def vps():
        route("plus_work_location", 5)
        r = shot("vps"); o["vps_txt"] = _txt(r)[:800]
        if r is None or not (find(r, "路由规则") or find(r, "VPS")): print("I3913 vps: 路由规则 not on first screen", flush=True)
        if not tap_scrolled("定时 / 轻任务 交给", tries=8, wait=2): fail("vps: rule 定时 / 轻任务 not reachable"); back(1); return
        r = dump()
        cur = next((t for t, d, x, y, n in nodes(r) if t.startswith("✓ ")), "")
        o["vps_current"] = cur
        pick = None
        # the other dropdown items share the ✓ row's left edge
        cb = next(((x, y, n) for t, d, x, y, n in nodes(r) if t == cur), None)
        if cb:
            lx = int(re.findall(r"\d+", cb[2].get("bounds"))[0])
            for t, d, x, y, n in nodes(r):
                b = re.findall(r"\d+", n.get("bounds", ""))
                if t and t != cur and not t.startswith("✓") and t != "删除这条规则" and len(b) == 4 and abs(int(b[0]) - lx) < 8:
                    pick = (t, x, y); break
        o["vps_pick"] = pick[0] if pick else None
        if not pick: fail(f"vps: no other machine in the dropdown (current {cur!r})"); back(1); return
        sh(f"input tap {pick[1]} {pick[2]}"); time.sleep(2)
        r = shot("vps-changed")
        if r is None or not find(r, "已改", exact=True): fail("vps: 已改 not shown after changing a rule")
        for _ in range(4):
            if find(dump(), "恢复默认", exact=True): break
            sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.4)} {SCREEN[0]//2} {int(SCREEN[1]*0.7)} 300"); time.sleep(1)
        if not tap_text("恢复默认", exact=True, wait=2) and not tap_scrolled("恢复默认", tries=8, wait=2): fail("vps: 恢复默认 not reachable")
        r = shot("vps-restored")
        if r is not None and find(r, "已改", exact=True): fail("vps: 已改 still shown after 恢复默认")
        for _ in range(6): sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.3)} {SCREEN[0]//2} {int(SCREEN[1]*0.8)} 300"); time.sleep(0.5)
        shot("vps-top")
        back(1); time.sleep(1)

    def tasks(tag):
        healthy_home()
        r = dump(); p = find(r, "任务", exact=True)
        if p and p[1] > SCREEN[1] * 0.8: sh(f"input tap {p[0]} {p[1]}"); time.sleep(3)
        else: route("plus_task_projects", 3)
        r = shot(tag); t = _txt(r); o[tag] = t[:600]
        if "项目" not in t: fail(f"{tag}: 项目 missing")
        if "已归档" not in t and "可搜回" not in t: fail(f"{tag}: 已归档 missing")
        return r

    def register():
        out = result_json("group", "i3913 chat 帮我注册 zeabur.com", timeout=60); o["chat"] = out
        r = wait_for("登记并关联账号", 40)
        r = shot("register-sheet"); t = _txt(r)
        if r is None or not find(r, "登记并关联账号"): fail(f"立项 sheet not shown: {state()}"); return
        if "zeabur" not in t.lower(): fail("立项 sheet: site zeabur.com not filled")
        pw = [n.get("text") for n in r.iter("node") if n.get("password") == "true"]
        labels = [x for x in texts(r) if x.strip() in ("密码", "Password", "输入密码")]
        o["sheet_password_fields"] = len(pw) + len(labels)
        if pw or labels: fail(f"立项 sheet has a password field {pw} {labels}")
        tap_text("登记并关联账号", wait=3)
        s = state(); o["state_after_register"] = s
        if not any("zeabur" in (p.get("site") or "") for p in s.get("projects") or []): fail(f"no zeabur project after 登记: {s}")
        if not s.get("taskName"): fail(f"TaskName.of empty for the project chat: {s}")
        shot("register-chat")

    def handoff():
        result_json("group", "i3913 say OAUTHTEST open google sign-in", timeout=60)
        r = wait_for("在 Chrome 里打开", 45)
        r = shot("handoff-popup"); t = _txt(r)
        s = state(); hs = [h for h in s.get("handoffs") or [] if h.get("kind") == "OAUTH_EXTERNAL"]
        o["handoffs"] = hs
        if not hs: fail(f"no OAUTH_EXTERNAL handoff: {s}"); return
        lab = hs[-1].get("label") or ""
        if "Zeabur" not in lab and "zeabur" not in lab: fail(f"handoff label not the project name: {lab!r} (taskName {s.get('taskName')!r})")
        if r is None or not find(r, "需要你"): fail("handoff popup not seen")
        tap_text("稍后", exact=True, wait=2.5)
        r = shot("handoff-bar")
        if r is None or not find(r, "继续接管"): fail("glass bar with 继续接管 not seen"); return
        tap_text("继续接管", exact=True, wait=4)
        s = state(); hs2 = [h for h in s.get("handoffs") or [] if h.get("id") == hs[-1].get("id")]
        o["handoff_after"] = hs2
        # [38.9.13] HandoffBroker drops a DONE handoff from its list; then the chat must carry 「交接完成 handoff_id=…」
        r = shot("handoff-after")
        done_msg = r is not None and find(r, "交接完成 handoff_id=" + str(hs[-1].get("id")))
        if hs2 and hs2[0].get("state") != "DONE" or (not hs2 and not done_msg): fail(f"继续接管 did not finish the handoff: {hs2}")

    def project():
        tasks("tasks-project")
        if not tap_scrolled("Zeabur", tries=4, wait=3): fail("project row Zeabur not on 任务 page"); return
        r = shot("project-detail"); t = _txt(r)
        if "尝试记录" not in t and "第1次" not in t: fail("项目详情 not shown")
        back(1)

    def memory():
        result_json("group", "i3913 chat MEMFIND write then find", timeout=60)
        f = wait_fake("MEMFIND", 2, 60); o["memfind"] = f
        if "memory" not in (f.get("tools") or []): fail(f"memory tool (memory_v2) not offered: {f.get('tools')}")
        rs = f.get("results") or []
        if len(rs) < 2 or "SMOKEMEM-7731" not in rs[1]: fail(f"memory find did not return the written entry: {rs}")
        time.sleep(3); shot("memory-chat")

    def gecko():
        result_json("group", "i3913 chat GECKOREAD read the page", timeout=60)
        f = wait_fake("GECKOREAD", 2, 120); o["gecko"] = f
        rs = f.get("results") or []
        if len(rs) < 2 or "GECKO-SMOKE-OK" not in rs[1]: fail(f"gecko get_text did not read the page: {[x[:300] for x in rs]}")
        if any("没能启动" in x for x in rs): fail("gecko fell back to the WebView (Mines 浏览器没能启动)")
        if rs and not any("[Mines 浏览器" in x for x in rs): fail("no [Mines 浏览器 …] footer on gecko results")
        top = ""
        for _ in range(20):
            top = sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -2")
            if "MinesBrowserActivity" in top: break
            time.sleep(2)
        o["gecko_top"] = top.strip()[-200:]
        time.sleep(4); r = shot("mines-browser")
        if "MinesBrowserActivity" not in top: fail("wait_for_human on gecko did not open MinesBrowserActivity")
        elif r is not None and not find(r, "交还"): print("I3913 gecko: 交还 button not in dump (GeckoView surface)", flush=True)
        if not tap_text("好了，交还给 Mines", wait=3): back(1)
        f = wait_fake("GECKOREAD", 3, 60); o["gecko_after"] = (f.get("results") or [])[2:3]

    def tunnel():
        o["servers"] = result_json("group", "i3911 servers", timeout=30)
        o["tunnelseed"] = result_json("group", "i3913 tunnelseed", timeout=30); print("I3913 TUNNEL " + json.dumps(o["tunnelseed"], ensure_ascii=False), flush=True)
        route("plus_server/smoke-mengyun", 5)
        r = None
        for _ in range(8):
            r = dump()
            if r is not None and find(r, "经 AWS 穿透"): break
            scroll_down()
        r = shot("server-tunnel"); t = _txt(r); o["server_tunnel_txt"] = t[:600]
        if "内网穿透（经 AWS）" not in t or "经 AWS 穿透" not in t: fail("服务器详情 → 内网穿透 section not shown")
        back(1); time.sleep(1)
        route("plus_work_location", 5)
        seen = ""
        for _ in range(6):
            r = dump(); seen += " " + _txt(r)
            if "内网穿透：" in seen: break
            scroll_down()
        shot("vps-tunnel-status"); o["vps_tunnel_status"] = re.findall(r"内网穿透：\S+", seen)
        if not o["vps_tunnel_status"]: page_missing.append("i3913 vps: no 内网穿透 status on any machine card (梦云 card not bound to the seeded server?)")
        back(1); time.sleep(1)

    def monitor():
        """[38.9.13] machine monitor: 梦云 bound to the runner's sshd, the app key authorised there, its card opened → real readings"""
        sd = result_json("group", "i3913 sshseed", timeout=30); pub = (sd or {}).get("pub", "") if isinstance(sd, dict) else ""
        o["sshseed"] = bool(pub)
        if not pub: fail(f"sshseed gave no public key: {sd}"); return
        subprocess.run("sudo mkdir -p /root/.ssh && sudo chmod 700 /root/.ssh && sudo tee -a /root/.ssh/authorized_keys >/dev/null && sudo chmod 600 /root/.ssh/authorized_keys",
                       shell=True, input=pub.strip() + "\n", text=True, timeout=20)
        route("plus_work_location", 5)
        r = None
        for _ in range(8):
            r = dump()
            if r is not None and find(r, "负载"): break
            scroll_down()
        if r is None or not find(r, "负载"): fail("monitor: panel not open on the 梦云 card"); shot("vps-monitor"); back(1); return
        got = None
        for _ in range(25):
            r = dump(); t = texts(r) if r is not None else []
            i = t.index("CPU") if "CPU" in t else -1   # the CPU % only comes from a reading (「—」 before)
            if i >= 0 and i + 1 < len(t) and re.fullmatch(r"\d+%", t[i + 1]): got = t[i + 1]; break
            time.sleep(2)
        # the gauges near the top of the screen, so gauges + tiles are in one picture
        p = find(dump(), "CPU") or find(dump(), "负载")
        if p and p[1] > SCREEN[1] * 0.35:
            sh(f"input swipe {SCREEN[0]//2} {p[1]} {SCREEN[0]//2} {int(SCREEN[1]*0.2)} 600"); time.sleep(2)
        r = shot("vps-monitor"); j = " ".join(texts(r)) if r is not None else ""
        o["monitor_txt"] = j[:900]
        o["monitor_cpu"] = got
        if not got: fail("monitor: no live reading (CPU %) within 50 s")
        time.sleep(11); r = shot("vps-monitor-10s"); j += " " + (" ".join(texts(r)) if r is not None else "")
        sh(f"input swipe {SCREEN[0]//2} {int(SCREEN[1]*0.75)} {SCREEN[0]//2} {int(SCREEN[1]*0.35)} 600"); time.sleep(2)
        r = shot("vps-monitor-carriers"); j += " " + (" ".join(texts(r)) if r is not None else "")
        miss = [w for w in ("CPU", "内存", "磁盘", "负载", "流量", "电信", "联通", "移动") if w not in j]
        if miss: page_missing.append(f"i3913 monitor: not on screen {miss}")
        if not re.search(r"余 \d+天", j): fail("monitor: 余 N天 not shown")
        if "∞" not in j: fail("monitor: traffic allowance ∞ not shown")
        back(1); time.sleep(1)

    step("experiments", experiments)
    step("vps", vps)
    step("tasks", lambda: tasks("tasks"))
    step("register", register)
    step("handoff", handoff)
    step("project", project)
    step("memory", memory)
    step("gecko", gecko)
    step("tunnel", tunnel)
    step("monitor", monitor)
    print("I3913 RESULT " + json.dumps(o, ensure_ascii=False)[:12000], flush=True)


def b39113(res):
    """[38.9.11.3] (1) provider latency: one streaming request through the real OpenAIProvider with a stalled local 'Clash'
    (accepts, never answers) on 127.0.0.1:7890 — 38.9.11.2 networking (legacy) vs the new per-host routes; (2) the sky never
    stops: fog recordings light / dark, before = 38.9.11.2 (a11y freeze rule + slow cross-faded drift, a fake 'other a11y
    service' = Q仔), after = new with Mines' own accessibility service really enabled + idle, plus scrolls / taps during the
    recording and a governor level-2 step; motion + luminance analysed offline; (3) SSH key exchange with InstaBox."""
    o = res.setdefault("i39113", {})
    T0 = time.time()
    SKYONLY = "skyonly" in (open("hark-build/smoke/target").read().splitlines()[1:2] or [""])[0]
    DIAGSOAK = "diagsoak" in (open("hark-build/smoke/target").read().splitlines()[1:2] or [""])[0]
    def step(name, f):
        t0 = time.time()
        if SKYONLY and name != "skyall": return   # [38.9.11.3 h] sky-drift-only run
        if DIAGSOAK and name not in ("diag", "diagsoak", "skyshort"): return   # [38.9.11.3.1] keystore-ANR hotfix gate
        if t0 - T0 > 80 * 60:
            page_missing.append(f"i39113 {name} skipped (time budget)"); print(f"I39113 SKIP {name}", flush=True); return
        try: f()
        except Exception as e:
            import traceback; traceback.print_exc(); page_missing.append(f"i39113 {name} error {e}")
        print(f"I39113 STEP {name} {int(time.time() - t0)} s", flush=True)
        if not alive(): crashed.append(f"i39113 {name}"); recover(f"i39113 {name}")
    def shot(name):
        r = snap("i39113-" + name)
        subprocess.run(f"cp {OUT}/{step_no:02d}-i39113-{name}.png {OUT}/i39113-{name}.png", shell=True)
        return r
    print("I39113 screen", sh("wm size").strip(), sh("wm density").strip(), flush=True)
    set_zh()
    try: result_json("group", "wallpaper off")
    except Exception: pass

    def latency():
        print(adb("reverse", "tcp:7890", "tcp:3131"), flush=True)   # stalled 'Clash'
        cases = [
            ("legacy", "127.0.0.1:7890", "https://api.deepseek.com/v1", "deepseek-chat"),
            ("new", "127.0.0.1:7890", "https://api.deepseek.com/v1", "deepseek-chat"),
            ("legacy", "127.0.0.1:7890", "https://token.sensenova.cn/v1", "SenseChat-5"),
            ("new", "127.0.0.1:7890", "https://token.sensenova.cn/v1", "SenseChat-5"),
            ("legacy", "127.0.0.1:7890", "https://api.openai.com/v1", "gpt-4o-mini"),
            ("new", "127.0.0.1:7890", "https://api.openai.com/v1", "gpt-4o-mini"),
            ("new", "127.0.0.1:7890", "http://10.0.2.2:8088/v1", "smoke-model"),
            ("new", "none", "https://api.deepseek.com/v1", "deepseek-chat"),
        ]
        o["latency"] = []
        for mode, px, base, model in cases:
            r = result_json("group", f"i39113 latency {mode} {px} {base} {model}", timeout=150)
            o["latency"].append(r); print("I39113 LAT", json.dumps(r, ensure_ascii=False), flush=True)
        # same host again right away: the remembered route (no probe)
        r = result_json("group", "i39113 latency new 127.0.0.1:7890 https://api.openai.com/v1 gpt-4o-mini", timeout=150)
        o["latency_repeat_note"] = "latency hook forgets routes per call; repeat = fresh probe"; o["latency"].append(r)
        print(adb("reverse", "tcp:7890", "tcp:3130"), flush=True)   # a working open proxy: foreign host should go through it
        r = result_json("group", "i39113 latency new 127.0.0.1:7890 https://api.openai.com/v1 gpt-4o-mini", timeout=150)
        o["latency"].append(r); print("I39113 LAT open-proxy", json.dumps(r, ensure_ascii=False), flush=True)
        adb("reverse", "--remove", "tcp:7890")
        result_json("group", "i39113 routeforget", timeout=15)
        bad = [x for x in o["latency"] if isinstance(x, dict) and x.get("mode") == "new" and x.get("ms", 0) > 25000]
        if bad: selftest_fail.append(f"i39113 new routes still slow (>25 s): {[(b.get('base'), b.get('ms'), b.get('outcome')) for b in bad]}")

    def sky():
        A11Y = f"{PKG}/com.openminis.app.accessibility.MinisAccessibilityService"
        for mode, sfx in ((1, "light"), (2, "dark")):
            theme388(mode); healthy_home()
            o[f"gpu_{sfx}"] = result_json("group", "i396 skygpu on", timeout=40)
            result_json("group", "i396 skymode smooth", timeout=20)
            for leg in ("before", "after"):
                tag = f"{leg}-{sfx}"
                result_json("group", f"i39113 skydrift {'legacy' if leg == 'before' else 'new'}", timeout=15)
                result_json("group", "i39112 skyfreeze on", timeout=15)          # real signals (no smoke ignore)
                result_json("group", "i39113 a11yfake 1", timeout=15)           # Q仔-like other a11y service present
                if leg == "after":
                    sh(f"settings put secure enabled_accessibility_services {A11Y}"); sh("settings put secure accessibility_enabled 1"); time.sleep(2)
                    o[f"a11y_enabled_{sfx}"] = sh("settings get secure enabled_accessibility_services").strip()
                result_json("group", "i39112 skylevel 0", timeout=15)
                result_json("group", "i396 sky fog", timeout=20); b381_home(); time.sleep(8)
                o[f"skystate_pre_{tag}"] = result_json("group", "i39113 skystate", timeout=15)
                sh("rm -f /sdcard/sky.mp4")
                p = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "14", "--bit-rate", "12000000", "/sdcard/sky.mp4"])
                t0 = time.time(); ev = []
                def at(sec):
                    while time.time() - t0 < sec: time.sleep(0.05)
                # 0–4 s idle · 4–7 s touch: scroll down / up + key events · 7–9.5 s governor level 2 · 9.5–14 s idle
                at(4.0); ev.append(("touch", round(time.time() - t0, 2)))
                sh("input swipe 540 1700 540 1300 400"); sh("input keyevent 59"); sh("input swipe 540 1300 540 1700 400"); sh("input keyevent 59")
                at(7.0); result_json("group", "i39112 skylevel 2", timeout=10); ev.append(("level2", round(time.time() - t0, 2)))
                at(9.5); result_json("group", "i39112 skylevel 0", timeout=10); ev.append(("level0", round(time.time() - t0, 2)))
                try: p.wait(timeout=30)
                except Exception: p.kill()
                time.sleep(1)
                print(adb("pull", "/sdcard/sky.mp4", f"{OUT}/i39113-fog-{tag}.mp4"), flush=True)
                o[f"events_{tag}"] = ev
                o[f"skystate_{tag}"] = result_json("group", "i39113 skystate", timeout=15)
                print("I39113 SKY", tag, json.dumps(o[f"skystate_{tag}"], ensure_ascii=False)[:600], flush=True)
                shot(f"fog-{tag}")
                if leg == "after":
                    sh("settings put secure enabled_accessibility_services ''"); sh("settings put secure accessibility_enabled 0")
            result_json("group", "i39113 a11yfake -1", timeout=15); result_json("group", "i39113 skydrift new", timeout=15)
            result_json("group", "i39112 skylevel 0", timeout=15)
            result_json("group", "i396 skygpu off", timeout=30)

    def ssh():
        o["ssh_instabox"] = result_json("group", "i39112 sshprobe api.instabox.sh 2222", timeout=60)
        o["ssh_local_ed25519"] = result_json("group", "i39112 sshprobe 10.0.2.2 22", timeout=60)
        print("I39113 SSH", o["ssh_instabox"], "|", o["ssh_local_ed25519"], flush=True)
        r = str(o["ssh_instabox"])
        if "negotiation fail" in r.lower() or ("hostkey=ssh-ed25519" not in r and "connected ssh-ed25519" not in r):
            selftest_fail.append(f"i39113 InstaBox kex: {r}")

    def keys():
        # 测试全部密钥: provider with 3 keys on the fake endpoint, #2 → 401; tap the card on the providers page, then the hook's state
        theme388(1); healthy_home()
        o["key_seed"] = result_json("group", "i39113 keyseed", timeout=30)
        route("providers", 6); r = shot("keys-card"); t = _txt(r)
        if "测试全部密钥" not in t: page_missing.append(f"i39113 测试全部密钥 card not on providers page ({t[:160]})")
        if tap_text("开始", exact=True, wait=12): shot("keys-result")
        else: page_missing.append("i39113 开始 (测试全部密钥) not found")
        o["key_check"] = result_json("group", "i39113 keycheck", timeout=90); print("I39113 KEYS", o["key_check"], flush=True)
        k = o["key_check"] if isinstance(o["key_check"], dict) else {}
        if k.get("bad2") is not True or k.get("good1") is not False: selftest_fail.append(f"i39113 key marks wrong: {k}")
        if any(x and x.endswith("-bad-2") for x in (k.get("picks") or [])) or "bad-2" in "".join(str(x) for x in (k.get("picks") or [])):
            selftest_fail.append(f"i39113 key pool still picks the invalid key: {k.get('picks')}")
        if "#2 无效" not in "".join(k.get("summary") or []): selftest_fail.append(f"i39113 key summary: {k.get('summary')}")
        back(1); time.sleep(1)
        result_json("group", "i39113 keyclean", timeout=30)

    def ledger():
        # 台账: two saved accounts on a fake sign-in page; the chat names the 测试 one → filled without the card, chip on screen
        theme388(1); healthy_home()
        o["ledger"] = result_json("group", "i39113 ledger 用测试号登录一下看看", timeout=60); print("I39113 LEDGER", o["ledger"], flush=True)
        time.sleep(0.5); shot("ledger-chip")
        l = o["ledger"] if isinstance(o["ledger"], dict) else {}
        f = {}
        try: f = json.loads(l.get("fields") or "{}")
        except Exception: pass
        if f.get("u") != "test@ledger-smoke.test" or f.get("pl") != len("pw-test-22222"): selftest_fail.append(f"i39113 台账 fill wrong: {l}")
        if l.get("cardShown"): selftest_fail.append("i39113 台账: password card popped although the account is saved")
        if "pw-test" in json.dumps(l) or "pw-main" in json.dumps(l): selftest_fail.append("i39113 台账: a password reached the agent text")
        o["ledger_named"] = result_json("group", "i39113 ledger 换 main@ledger-smoke.test 登录", timeout=60)
        try:
            if json.loads((o["ledger_named"] or {}).get("fields") or "{}").get("u") != "main@ledger-smoke.test": selftest_fail.append(f"i39113 台账 named account not used: {o['ledger_named']}")
        except Exception as e: selftest_fail.append(f"i39113 台账 named: {e}")
        result_json("group", "i39113 ledgerclean", timeout=30)

    def crash():
        theme388(1); healthy_home()
        o["crash"] = result_json("group", "i39113 crashdemo", timeout=30); print("I39113 CRASH", o["crash"], flush=True)
        time.sleep(0.5); shot("crash-card")
        c = o["crash"] if isinstance(o["crash"], dict) else {}
        lk = c.get("leak"); lk = [] if lk in (None, "", "[]") else lk   # the app sends the list as the string "[]" when nothing leaked
        if lk or not c.get("kept") or "ANR" not in str(c.get("card")): selftest_fail.append(f"i39113 crash card / redaction: {c}")
        tap_text("关闭", exact=True, wait=2)

    def errzh():
        # [38.9.11.3 f] Chinese-only error lines: a real 每日简报 run on fake feeds (403 / cut connection / ok) + TTS model line + chat filter
        theme388(1); healthy_home()
        o["errzh"] = result_json("group", "i39113 errzh", timeout=150); print("I39113 ERRZH", o["errzh"], flush=True)
        z = o["errzh"] if isinstance(o["errzh"], dict) else {}
        lines = [str(x) for x in (z.get("notes") or [])] + [str(z.get("lastError") or ""), str(z.get("tts") or ""), str(z.get("legacy") or "")]
        eng = [l for l in lines if l and re.search(r"[A-Za-z]{4,}", re.sub(r"(Reddit|Hacker News|Memoh|tts-1|LocalLLaMA|HTTP)", "", l))]
        if eng: selftest_fail.append(f"i39113 errzh English left: {eng}")
        if not any("拒绝访问" in l for l in lines): selftest_fail.append(f"i39113 errzh no 403 line: {lines}")
        if not any("连接中断" in l for l in lines): selftest_fail.append(f"i39113 errzh no cut-connection line: {lines}")
        cc = z.get("chatCapable") or {}
        if cc.get("tts-1") or cc.get("text-embedding-3-small") or cc.get("dall-e-3") or not cc.get("gpt-4o-mini"): selftest_fail.append(f"i39113 chatCapable wrong: {cc}")
        route("plus_digest", 8); time.sleep(2); r = shot("errzh-brief"); o["errzh_page"] = _txt(r)[:600]
        back(1); time.sleep(1)

    def diag():
        # [38.9.11.3 g] 远程诊断: the emulator connects to a test relay on the runner (ws://10.0.2.2:8097, relay.py from
        # hark-build/smoke), streams data with no secrets, runs every whitelisted command, rejects everything else.
        import base64 as _b64
        D = {"steps": {}}
        tok = os.environ.get("DIAG_TOKEN", "mdk_smoke_token_0123456789abcdef")
        env = dict(os.environ, DIAG_CTL=os.environ.get("DIAG_CTL", os.path.abspath("diag.sock")))
        def ctl(*a, extra=None):
            e = dict(env, **(extra or {}))
            r = subprocess.run(["python3", "hark-build/smoke/diag_ctl.py", *a], env=e, capture_output=True, text=True, timeout=30)
            return r.stdout.strip()
        def tail(sid, n=400):
            return [json.loads(x) for x in ctl("tail", sid, str(n)).splitlines() if x.startswith("{")]
        def wait_result(sid, cid, sec=20, kind=("result", "rejected")):
            t1 = time.time()
            while time.time() - t1 < sec:
                for x in tail(sid):
                    if x.get("t") in kind and x.get("id") == cid: return x
                time.sleep(1)
            return None
        def send(sid, cmd, args=None, extra=None):
            r = ctl("send", sid, cmd, json.dumps(args or {}, ensure_ascii=False), extra=extra)
            try: return json.loads(r).get("id")
            except Exception: return None
        code = "mdiag1:" + _b64.urlsafe_b64encode(json.dumps({"t": tok, "e": [["ws://10.0.2.2:8097/mdiag/dev", ""]]}).encode()).decode().rstrip("=")
        theme388(1); healthy_home()
        D["code"] = result_json("group", "i39113 diag code " + code, timeout=20)
        st0 = result_json("group", "i39113 diag state", timeout=20); D["state_off"] = st0
        result_json("group", "i39113 diag on", timeout=20)
        sid = ""; t1 = time.time()
        while time.time() - t1 < 40:
            st = result_json("group", "i39113 diag state", timeout=20)
            if isinstance(st, dict) and st.get("connected"): sid = st.get("sid", ""); break
            time.sleep(2)
        D["state_on"] = st; print("I39113 DIAG state", st, flush=True)
        if not sid:
            selftest_fail.append(f"i39113 diag: never connected ({st})"); o["diag"] = D; return
        if "诊断已连接（双向）" not in str(st.get("status")): selftest_fail.append(f"i39113 diag status wrong: {st}")
        # the settings row (normal text size) while connected
        route("settings", 3); tap_text("测试与上报", wait=3)
        for _ in range(4):
            if find(dump(), "远程诊断"): break
            scroll_down()
        r = shot("diag-settings"); D["settings_txt"] = _txt(r)[:400]; back(2); time.sleep(1)
        # some page switches + one forced 400 ms main-thread stall (watchdog), then wait for the 10 s jank flush
        for rt in ("settings", "plus_digest", "providers"):
            route(rt, 1.5); back(1); time.sleep(0.8)
        result_json("group", "i39113 diag stall 450", timeout=20); time.sleep(12)
        # every whitelisted command
        for cmd, args in (("ping", {}), ("dump_main_thread_now", {}), ("set_log_level", {"level": "info"}), ("get_recent_crash", {}),
                          ("show_notice", {"text": "测试通知：诊断正常"})):
            cid = send(sid, cmd, args); x = wait_result(sid, cid) if cid else None
            D["steps"][cmd] = (x or {}).get("t", "none"); D.setdefault("bodies", {})[cmd] = json.dumps((x or {}).get("body", {}), ensure_ascii=False)[:300]
            if not x or x.get("t") != "result": selftest_fail.append(f"i39113 diag {cmd}: {x}")
        time.sleep(1); stn = result_json("group", "i39113 diag state", timeout=20)
        r = shot("diag-notice"); D["notice_seen"] = "测试通知：诊断正常" in _txt(r) or "测试通知" in str((stn or {}).get("notice"))
        if not D["notice_seen"]: selftest_fail.append("i39113 diag show_notice banner not shown")
        cid = send(sid, "capture_jank_trace", {"seconds": 4})
        for rt in ("settings", "plus_digest"):
            route(rt, 1); back(1); time.sleep(0.5)
        x = wait_result(sid, cid, 20); D["steps"]["capture_jank_trace"] = (x or {}).get("t", "none")
        D["trace"] = {k: (x or {}).get("body", {}).get(k) for k in ("frames", "janky", "slowest_ms", "p90_ms", "pages")}
        if not x or x.get("t") != "result": selftest_fail.append(f"i39113 diag capture_jank_trace: {x}")
        cid = send(sid, "request_screenshot_of_mines_only"); time.sleep(3)
        r = shot("diag-shot-ask"); D["shot_ask_seen"] = "允许发送截图" in _txt(r)
        result_json("group", "i39113 diag shot allow", timeout=20)
        x = wait_result(sid, cid, 25); b = (x or {}).get("body", {})
        D["steps"]["request_screenshot_of_mines_only"] = (x or {}).get("t", "none"); D["shot"] = {"w": b.get("w"), "h": b.get("h"), "jpeg_kb": len(b.get("jpeg_b64", "")) * 3 // 4 // 1024}
        if not D["shot_ask_seen"] or not b.get("jpeg_b64"): selftest_fail.append(f"i39113 diag screenshot: ask={D['shot_ask_seen']} body={str(b)[:200]}")
        # [g2] signed remote config: bounded keys, shown in-app, one-tap 「撤销」, all undone when 诊断 turns off
        cfg = {}
        for k, v in (("sky_fps_cap", 15), ("nav_anim", "off"), ("sky_mode", "off")):
            cid = send(sid, "set_config", {"key": k, "value": v}); x = wait_result(sid, cid)
            cfg[k] = (x or {}).get("body"); 
            if not x or x.get("t") != "result": selftest_fail.append(f"i39113 diag set_config {k}: {x}")
        stc = result_json("group", "i39113 diag state", timeout=20); D["config_state"] = {k: (stc or {}).get(k) for k in ("changes", "navAnimOff", "fpsCap", "skyMode")}
        if not (isinstance(stc, dict) and stc.get("fpsCap") == 15 and stc.get("navAnimOff") is True and stc.get("skyMode") == "off" and len(stc.get("changes") or []) == 3):
            selftest_fail.append(f"i39113 diag config not applied: {D['config_state']}")
        route("settings", 2); back(1); time.sleep(1)   # one page flip with nav_anim off
        r = shot("diag-config-card"); tc = _txt(r); D["config_card_seen"] = "诊断改了" in tc and "撤销" in tc
        if not D["config_card_seen"]: selftest_fail.append("i39113 diag config card not shown")
        tapped = tap_text("撤销", exact=True, wait=2)   # the user's one tap undoes the top change
        st2 = result_json("group", "i39113 diag state", timeout=20); D["after_one_tap"] = (st2 or {}).get("changes")
        if not tapped or len((st2 or {}).get("changes") or []) != 2: selftest_fail.append(f"i39113 diag one-tap revert failed: tapped={tapped} {D['after_one_tap']}")
        D["config_results"] = cfg
        cfgbad = {}
        for k, v in (("sky_fps_cap", 120), ("screen_brightness", "10"), ("shell", "id")):
            cid = send(sid, "set_config", {"key": k, "value": v}); cfgbad[k] = (wait_result(sid, cid) or {}).get("reason")
        D["config_rejected"] = cfgbad
        if any(r != "参数不合法" for r in cfgbad.values()): selftest_fail.append(f"i39113 diag bad config not rejected: {cfgbad}")
        # must be refused by the phone
        bad = {}
        cid = send(sid, "rm_rf", {}, extra={"DIAG_TEST_UNLISTED": "1"}); bad["unlisted"] = (wait_result(sid, cid) or {}).get("reason")
        cid = send(sid, "ping", {}, extra={"DIAG_TEST_BADSIG": "1"}); bad["badsig"] = (wait_result(sid, cid) or {}).get("reason")
        cid = send(sid, "set_log_level", {"level": "verbose"}); bad["badarg"] = (wait_result(sid, cid) or {}).get("reason")
        cid = send(sid, "show_notice", {"text": "打开 https://evil.example 领奖"}); bad["link_notice"] = (wait_result(sid, cid) or {}).get("reason")
        cid = send(sid, "capture_jank_trace", {"seconds": 300}); bad["too_long"] = (wait_result(sid, cid) or {}).get("reason")
        D["rejected"] = bad; print("I39113 DIAG rejected", bad, flush=True)
        if not str(bad.get("unlisted", "")).startswith("不在白名单"): selftest_fail.append(f"i39113 diag unknown command not rejected: {bad}")
        if bad.get("badsig") != "签名不对": selftest_fail.append(f"i39113 diag bad signature not rejected: {bad}")
        if any(bad.get(k) != "参数不合法" for k in ("badarg", "link_notice", "too_long")): selftest_fail.append(f"i39113 diag bad args not rejected: {bad}")
        # what reached the relay: kinds + no secrets / chat content
        lines = tail(sid, 2000); raw = json.dumps(lines, ensure_ascii=False)
        kinds = {}
        for x in lines: kinds[x.get("t")] = kinds.get(x.get("t"), 0) + 1
        # the relay consumes the phone's hello (needs a valid 64-hex k) and logs it as relay/connected with the hello's dev fields
        kinds["hello"] = sum(1 for x in lines if x.get("t") == "relay" and x.get("ev") == "connected" and (x.get("dev") or {}).get("app") == "Mines")
        D["kinds"] = kinds
        D["stall_ms"] = [x.get("ms") for x in lines if x.get("t") == "stall"][:8]
        D["jank_pages"] = sorted({x.get("page") for x in lines if x.get("t") == "jank"})
        leaks = [w for w in ("sk-smokesecret", "MinesSmoke-2026", "13690688658", "hunter2", "smoke-key", "你好，我是测试模型", "GARBAGETEST") if w in raw]
        leaks += re.findall(r"sk-[A-Za-z0-9]{12,}", raw)[:3]
        D["leaks"] = leaks
        if leaks: selftest_fail.append(f"i39113 diag payload leaked: {leaks}")
        if not kinds.get("config_reverted"): selftest_fail.append(f"i39113 diag one-tap revert not logged to the relay: {kinds}")
        for k in ("hello", "mem", "jank", "stall"):
            if not kinds.get(k): selftest_fail.append(f"i39113 diag no '{k}' event: {kinds}")
        result_json("group", "i39113 diag off", timeout=20); time.sleep(3)
        D["state_after_off"] = result_json("group", "i39113 diag state", timeout=20)
        so = D["state_after_off"] if isinstance(D["state_after_off"], dict) else {}
        if so.get("changes") or so.get("navAnimOff") or so.get("fpsCap") or so.get("skyMode") == "off":
            selftest_fail.append(f"i39113 diag off did not undo remote config: {so}")
        D["live_after_off"] = (json.loads(ctl("list") or "{}").get("live") or {})
        if sid in D["live_after_off"]: selftest_fail.append("i39113 diag still connected after off")
        result_json("group", "i39113 diag code none", timeout=20)
        o["diag"] = D; print("I39113 DIAG " + json.dumps(D, ensure_ascii=False)[:5000], flush=True)
        subprocess.run(f"cp diag-data/{sid}.jsonl {OUT}/diag-session.jsonl", shell=True)
        healthy_home()

    step("ssh", ssh)
    step("keys", keys)
    step("ledger", ledger)
    step("crash", crash)
    step("errzh", errzh)
    step("diag", diag)

    def stress():
        # [38.9.11.3 f] user 15:43: crashes on the phone → rapid page switching, chat open/close, rotation, background/foreground,
        # process death in background, then a seeded monkey run inside Mines. Counts FATAL / ANR from the crash buffer + logcat.
        theme388(1); healthy_home()
        sh("logcat -b crash -c"); t0 = sh("date +%s").strip(); st = {"loops": 0, "dead": 0, "notes": []}
        def chat_row():
            for t, d, x, y, n in nodes(dump()):
                if any(k in (t + d) for k in ("GARBAGETEST", "你好，我是测试模型", "hello")) and y > 300: return (x, y)
        sh("settings put system accelerometer_rotation 0")
        for i in range(12):
            c = chat_row()
            if c: sh(f"input tap {c[0]} {c[1]}"); time.sleep(0.6)
            sh("input keyevent KEYCODE_BACK"); time.sleep(0.3)
            if c: sh(f"input tap {c[0]} {c[1]}"); time.sleep(0.25); sh("input keyevent KEYCODE_BACK"); time.sleep(0.25)   # open/close mid-transition
            selftest("route", ["settings", "plus_digest", "providers", "plus_knowledge"][i % 4], timeout=20); time.sleep(0.4)
            sh("input keyevent KEYCODE_BACK"); time.sleep(0.3)
            if i % 3 == 0:
                sh("settings put system user_rotation 1"); time.sleep(1.2); sh("settings put system user_rotation 0"); time.sleep(1.2)
            if i % 4 == 1:
                sh("input keyevent KEYCODE_HOME"); time.sleep(0.8)
                sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(1.5)
            if i % 6 == 5:   # process death while in the background, then restore
                sh("input keyevent KEYCODE_HOME"); time.sleep(0.8); sh(f"am kill {PKG}"); time.sleep(0.8)
                sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(3)
            st["loops"] += 1
            if not alive(): st["dead"] += 1; st["notes"].append(f"not alive after loop {i}"); relaunch(); time.sleep(3)
        sh("settings put system user_rotation 0")
        shot("stress-after-loops")
        # seeded monkey inside Mines (no system keys, no app switches; nav + touch + motion)
        mk = sh(f"monkey -p {PKG} -s 39113 --throttle 120 --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --ignore-security-exceptions -v 1200 2>&1 | tail -5", timeout=600)
        st["monkey_tail"] = mk.strip()[-400:]
        st["monkey_crash"] = ("CRASH" in mk) or ("Monkey aborted" in mk and "crash" in mk.lower())
        st["monkey_anr"] = "NOT RESPONDING" in mk
        sh("settings put system user_rotation 0")
        if not alive(): relaunch(); time.sleep(4)
        cb = adb("logcat", "-d", "-v", "threadtime", "-b", "crash", timeout=60)
        st["fatal"] = cb.count("FATAL EXCEPTION"); st["crash_buffer"] = cb[-3000:]
        an = sh(f"logcat -d -t '{t0}.000' -b main,system | grep -c 'ANR in {PKG}'").strip() if t0.isdigit() else "?"
        st["anr"] = an
        o["stress"] = st; print("I39113 STRESS", json.dumps(st, ensure_ascii=False)[:5000], flush=True)
        shot("stress-end")
        if st["fatal"] or st["monkey_crash"] or st["monkey_anr"] or (an.isdigit() and int(an) > 0):
            selftest_fail.append(f"i39113 stress: fatal={st['fatal']} monkey_crash={st['monkey_crash']} anr={an}")
        healthy_home()

    def skyall():
        # [38.9.11.3 h] user 2026-10-21 20:17 「所有壁纸都不会动」: every sky scene × light / dark × (default, animator scale 0,
        # battery saver) × (shader path = phones, stills path = no shader): two screenshots 5 s apart must differ across the
        # sky within 3 s (sky_frames_39113; a clock tick or a cursor changes 1–2 grid cells, a drifting sky most of them)
        R = {}
        scales0 = [sh(f"settings get global {g}").strip() for g in ("animator_duration_scale", "window_animation_scale", "transition_animation_scale")]
        for cname, scale, saver in (("default", 1, False), ("anim0", 0, False), ("saver", 1, True)):
            sh(f"settings put global animator_duration_scale {scale}; settings put global window_animation_scale {scale}; settings put global transition_animation_scale {scale}")
            if saver: sh("cmd battery unplug; cmd power set-mode 1")
            else: sh("cmd power set-mode 0; cmd battery reset")
            for mode, sfx in ((1, "light"), (2, "dark")):
                theme388(mode); healthy_home()
                result_json("group", "i396 skymode smooth", timeout=20)
                for path in ("gpu", "stills"):
                    result_json("group", f"i396 skygpu {'on' if path == 'gpu' else 'off'}", timeout=40)
                    sky_frames_39113("new", cname, sfx, path, R, keep=(cname == "default"))
        sh("cmd power set-mode 0; cmd battery reset")
        for g, v in zip(("animator_duration_scale", "window_animation_scale", "transition_animation_scale"), scales0):
            sh(f"settings put global {g} {v if v not in ('', 'null') else 1}")
        o["skyall_scales_before"] = scales0
        result_json("group", "i396 skygpu off", timeout=30); result_json("group", "i396 sky auto", timeout=20)
        o["skyall"] = R
        print("I39113 SKYALL_SUMMARY " + json.dumps({k: v["cells3s"] for k, v in R.items()}), flush=True)

    def diagsoak():
        # [38.9.11.3.1] real phone ANR 2026-10-21 23:41 with 诊断 on: main thread waited ~5–12 s on Android Keystore
        # (EncryptedSharedPreferences re-opened on every credential check). Sustained run with 诊断 on: chat open/close
        # (loadSession → tool list → credential checks), page switches, pings over the relay every ~30 s, then the stress
        # block with 诊断 still on. Gate: ANR 0, FATAL 0, no relay stall > 2 s, no stall stack in Keystore / Tink / EncryptedPrefs.
        import base64 as _b64
        S = {"loops": 0, "dead": 0, "pings_sent": 0, "pings_ok": 0}
        tok = os.environ.get("DIAG_TOKEN", "mdk_smoke_token_0123456789abcdef")
        env = dict(os.environ, DIAG_CTL=os.environ.get("DIAG_CTL", os.path.abspath("diag.sock")))
        def ctl(*a):
            return subprocess.run(["python3", "hark-build/smoke/diag_ctl.py", *a], env=env, capture_output=True, text=True, timeout=30).stdout.strip()
        def tail(sid, n=4000):
            return [json.loads(x) for x in ctl("tail", sid, str(n)).splitlines() if x.startswith("{")]
        code = "mdiag1:" + _b64.urlsafe_b64encode(json.dumps({"t": tok, "e": [["ws://10.0.2.2:8097/mdiag/dev", ""]]}).encode()).decode().rstrip("=")
        theme388(1); healthy_home()
        sh("logcat -b crash -c"); t0 = sh("date +%s").strip()
        result_json("group", "i39113 diag code " + code, timeout=20); result_json("group", "i39113 diag on", timeout=20)
        sid = ""; t1 = time.time(); st = None
        while time.time() - t1 < 40:
            st = result_json("group", "i39113 diag state", timeout=20)
            if isinstance(st, dict) and st.get("connected"): sid = st.get("sid", ""); break
            time.sleep(2)
        if not sid:
            selftest_fail.append(f"i39113 diagsoak: never connected ({st})"); o["diagsoak"] = S; return
        def chat_row():
            for t, d, x, y, n in nodes(dump()):
                if any(k in (t + d) for k in ("GARBAGETEST", "你好，我是测试模型", "hello")) and y > 300: return (x, y)
        T1 = time.time(); last_ping = 0.0; pending = []
        while time.time() - T1 < 8 * 60:
            c = chat_row()
            if c:
                sh(f"input tap {c[0]} {c[1]}"); time.sleep(1.2)
                sh("input swipe 540 900 540 1600 200"); time.sleep(0.4)
                sh("input keyevent KEYCODE_BACK"); time.sleep(0.5)
            selftest("route", ["settings", "plus_digest", "providers", "plus_knowledge"][S["loops"] % 4], timeout=20); time.sleep(0.6)
            sh("input keyevent KEYCODE_BACK"); time.sleep(0.4)
            if S["loops"] % 5 == 4:
                sh("input keyevent KEYCODE_HOME"); time.sleep(0.8)
                sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(1.5)
            if time.time() - last_ping > 30:
                last_ping = time.time()
                try: cid = json.loads(ctl("send", sid, "ping", "{}")).get("id")
                except Exception: cid = None
                S["pings_sent"] += 1
                if cid: pending.append(cid)
            S["loops"] += 1
            if not alive(): S["dead"] += 1; relaunch(); time.sleep(3)
        time.sleep(3)
        got = {x.get("id") for x in tail(sid) if x.get("t") == "result"}
        S["pings_ok"] = sum(1 for c in pending if c in got)
        shot("diagsoak-after-loops")
        stress()   # 诊断 still on
        time.sleep(12)
        lines = tail(sid)
        stalls = [x for x in lines if x.get("t") == "stall"]
        S["stalls"] = len(stalls)
        # [38.9.12 driver] a main thread parked in HardwareRenderer (nSyncAndDrawFrame / nSetStopped) is waiting for the
        # emulator's software-GPU RenderThread (SwiftShader), not running app code: gated separately at 4 s (smoke 39826658586:
        # 2045 ms in nSetStopped on HOME, app-code max 780 ms)
        def _render_wait(x): return any("HardwareRenderer" in l for l in x.get("stack", "").split("\n")[1:3])
        S["render_wait_ms_max"] = max([x.get("ms", 0) for x in stalls if _render_wait(x)] or [0])
        stalls = [x for x in stalls if not _render_wait(x)] + [x for x in stalls if _render_wait(x) and x.get("ms", 0) > 4000]
        S["stall_ms_max"] = max([x.get("ms", 0) for x in stalls] or [0])
        S["stall_top"] = sorted([(x.get("ms"), x.get("page")) for x in stalls], key=lambda v: -(v[0] or 0))[:6]
        ks = [x for x in stalls if re.search(r"keystore|EncryptedSharedPreferences|crypto\.tink|EncryptedPrefsFactory", x.get("stack", ""), re.I)]
        S["keystore_stalls"] = len(ks); S["keystore_stall_stacks"] = [x.get("stack", "")[:600] for x in ks[:2]]
        S["reconnects"] = sum(1 for x in lines if x.get("t") == "relay" and x.get("ev") == "connected")
        S["live_at_end"] = sid in (json.loads(ctl("list") or "{}").get("live") or {})
        cb = adb("logcat", "-d", "-v", "threadtime", "-b", "crash", timeout=60)
        S["fatal"] = cb.count("FATAL EXCEPTION")
        S["anr"] = sh(f"logcat -d -t '{t0}.000' -b main,system | grep -c 'ANR in {PKG}'").strip() if t0.isdigit() else "?"
        o["diagsoak"] = S; print("I39113 DIAGSOAK " + json.dumps(S, ensure_ascii=False)[:5000], flush=True)
        subprocess.run(f"cp diag-data/{sid}.jsonl {OUT}/diagsoak-session.jsonl", shell=True)
        if S["fatal"] or S["dead"] or (S["anr"].isdigit() and int(S["anr"]) > 0) or S["anr"] == "?":
            selftest_fail.append(f"i39113 diagsoak: fatal={S['fatal']} dead={S['dead']} anr={S['anr']}")
        if S["keystore_stalls"]: selftest_fail.append(f"i39113 diagsoak: main-thread stall in Keystore/EncryptedPrefs: {S['keystore_stall_stacks'][:1]}")
        if S["stall_ms_max"] > 2000: selftest_fail.append(f"i39113 diagsoak: main-thread stall {S['stall_ms_max']} ms: {S['stall_top']}")
        if S["pings_sent"] and S["pings_ok"] < 0.8 * S["pings_sent"]: selftest_fail.append(f"i39113 diagsoak: pings {S['pings_ok']}/{S['pings_sent']}")
        result_json("group", "i39113 diag off", timeout=20); time.sleep(2); result_json("group", "i39113 diag code none", timeout=20)
        healthy_home()

    def skyshort():
        # [38.9.11.3.1] short sky regression check: default + animator scale 0, light + dark, automatic path (no forced
        # shader — the forced-shader skyall hangs SwiftShader). Recorded with tag 'hf', compared offline with h2's skyall.
        R = {}
        scales0 = [sh(f"settings get global {g}").strip() for g in ("animator_duration_scale", "window_animation_scale", "transition_animation_scale")]
        sh("cmd power set-mode 0; cmd battery reset")
        for cname, scale in (("default", 1), ("anim0", 0)):
            sh(f"settings put global animator_duration_scale {scale}; settings put global window_animation_scale {scale}; settings put global transition_animation_scale {scale}")
            for mode, sfx in ((1, "light"), (2, "dark")):
                theme388(mode); healthy_home()
                result_json("group", "i396 skymode smooth", timeout=20)
                result_json("group", "i396 skygpu off", timeout=40)
                sky_frames_39113("hf", cname, sfx, "stills", R, keep=(sfx == "light"))
        for g, v in zip(("animator_duration_scale", "window_animation_scale", "transition_animation_scale"), scales0):
            sh(f"settings put global {g} {v if v not in ('', 'null') else 1}")
        result_json("group", "i396 sky auto", timeout=20)
        o["skyshort"] = R
        print("I39113 SKYSHORT_SUMMARY " + json.dumps({k: v["cells3s"] for k, v in R.items()}), flush=True)

    step("diagsoak", diagsoak)
    step("skyshort", skyshort)
    step("latency", latency)
    step("sky", sky)
    step("skyall", skyall)
    result_json("group", "i396 sky auto static", timeout=20)
    if "stress" in (open("hark-build/smoke/target").read().splitlines()[1:2] or [""])[0]: step("stress", stress)
    print("I39113 RESULT " + json.dumps(o, ensure_ascii=False)[:14000], flush=True)


if __name__ == "__main__":
    main()
