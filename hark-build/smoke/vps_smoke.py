#!/usr/bin/env python3
"""VPS-screen crash smoke test for the Mines release APK on an emulator.

Drives the real UI with adb + uiautomator (no test APK, release build as
shipped): launch -> home Today card -> 工作位置 -> add a reachable SSH server
(the runner's own sshd at 10.0.2.2, password login + key install) -> VPS mode
-> server detail -> add an unreachable server -> 立即检测 -> Settings ->
工作位置. After every step it checks the app process is still alive and at the
end prints every FATAL EXCEPTION from logcat. Exit 1 when the app crashed.
"""
import json, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

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
                sh(f"input tap {target[0]} {target[1]}"); time.sleep(1.0); type_text(value); time.sleep(0.6)
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
    for _ in range(5):
        r = dump()
        if r is not None and find(r, "最近对话"): return r
        sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"); time.sleep(8)
    return dump()


def b381():
    """[build-38.1] board-accurate screens at 1080x2400 / 480 dpi, Chinese app locale, a loud wallpaper set:
    home, 消息, detail, chat, settings, 语音设置, call + 声音 sheet, 账号连接, 定时任务 and old pages."""
    res = {}
    sh(f"cmd locale set-app-locales {PKG} --locales zh-CN"); time.sleep(1)
    sh("wm size 1080x2400"); sh("wm density 480"); time.sleep(3)
    m = re.search(r"Override size: (\d+)x(\d+)", sh("wm size")) or re.search(r"(\d+)x(\d+)", sh("wm size"))
    if m: SCREEN[0], SCREEN[1] = int(m.group(1)), int(m.group(2))
    print("b381 screen", SCREEN, flush=True)
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
    if row: sh(f"input tap {row[0]} {row[1]}"); time.sleep(5); shot381("chat"); back()
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
    print(f"B381 {res}", flush=True)


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
