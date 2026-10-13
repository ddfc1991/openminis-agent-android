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
                return ET.fromstring(x[x.index("<hierarchy"):])
            except Exception:
                pass
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
    subprocess.run(f"adb exec-out screencap -p > {OUT}/{tag}.png", shell=True)
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
    out = sh(f"content call --uri {SELFTEST_URI} --method {method} --arg '{arg}'", timeout=timeout)
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
    if j.get("gec") != "70AED27457006C255086B4F079B9FE44A4D10827C4AFDFC3C45583B6F40D7DDF":
        selftest_fail.append(f"b34 edge token {j}")
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


def build31_pages():
    open_page("应用 page", ["账号："], ["搜索应用", "应用"])
    open_page("消息 feed page", [("消息", True), ("消息 ", False)], ["我关注的", "全部"], linger=8)
    open_page("server page", ["服务器："], ["服务器", "smoke"])
    open_page("voice page", ["实时语音"], ["语音", "通话", "Gemini"], linger=6)


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
