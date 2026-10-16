#!/usr/bin/env python3
"""VPS-screen crash smoke test for the Mines release APK on an emulator.

Drives the real UI with adb + uiautomator (no test APK, release build as
shipped): launch -> home Today card -> 工作位置 -> add a reachable SSH server
(the runner's own sshd at 10.0.2.2, password login + key install) -> VPS mode
-> server detail -> add an unreachable server -> 立即检测 -> Settings ->
工作位置. After every step it checks the app process is still alive and at the
end prints every FATAL EXCEPTION from logcat. Exit 1 when the app crashed.
"""
import json, os, re, struct, subprocess, sys, time, xml.etree.ElementTree as ET

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
    fill_field(root, "地址", host)
    if port:
        root = dump(); fill_field(root, "端口", str(port), clear=True)
    if password:
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
    sh("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
    adb("logcat", "-c")
    lc = subprocess.Popen(f"adb logcat -v threadtime > {OUT}/logcat.txt", shell=True)
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

    if FAST:   # [38.7 v11d] user-approved targeted gate: linking, white pages, persistence, chosen platform rows
        res = {}
        try:
            result_json("group", "wallpaper off"); anim(True)
        except Exception as e:
            print("fast prep error", e, flush=True)
        if UI388: fns = (b388_ui, b389_ui, b3891_ui) if UI3891 else (b388_ui, b389_ui) if UI389 else (b388_ui,)
        elif HUB: fns = ()
        elif B388: fns = (b387_linkall, b387_noblack, b388_weblogin, b388_mailbox, b388_presets)
        else: fns = (b387_linkall, b387_noblack, b387_persist, b387_platforms)
        if HUB: fns = tuple(fns) + (b3891_hub,)   # [38.9.1 c] hub runs after the UI passes
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
                sh(f"input tap {edits[1][0]} {edits[1][1]}"); time.sleep(.6); sh("input text ABCDEFGHIJKLMNOP"); sh("input keyevent 111")
                tap_text("验证并添加", wait=18)
                r = shot388("mail3891-163-result"); o["mail-163-ui"] = _txt(r)[:300]
            back(2)
    step("mailbox", mail)
    # 8 in-app browser: 手机版 / 电脑版 chip per site + 用系统浏览器打开; 一键关联 e-mail login fake
    def br():
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


def finish(lc):
    lc.terminate()
    time.sleep(1)
    log = open(f"{OUT}/logcat.txt", errors="replace").read().splitlines()
    fatal = [i for i, l in enumerate(log)
             if ("FATAL EXCEPTION" in l or "Fatal signal" in l or " ANR in " in l)
             and PKG in "\n".join(log[i:i + 4])]
    print("=" * 30, "FATAL EXCEPTIONS:", len(fatal), flush=True)
    for i in fatal:
        print("\n".join(log[max(0, i - 3): i + 60]))
        print("-" * 30)
    print("crashed steps:", crashed)
    print("selftest failures:", selftest_fail)
    print("pages not confirmed:", page_missing)
    # Mines' own crash log (build-28+), readable through the app's files only for debuggable builds; logcat tag MinesCrash
    for l in log:
        if "MinesCrash" in l:
            print(l)
    sys.exit(1 if (crashed or fatal or selftest_fail) else 0)


if __name__ == "__main__":
    main()

