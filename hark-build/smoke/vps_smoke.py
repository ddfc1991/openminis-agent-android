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
        for lab in ("以后再说", "以后", "跳过", "稍后", "知道了", "不用了", "取消", "Allow", "允许", "ALLOW", "While using the app"):
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
        if find(root, "工作位置：") or find(root, "工作位置", exact=True) or find(root, "Welcome to Mines"): return root
        sh("input keyevent KEYCODE_BACK"); time.sleep(2.5)
        if not alive(): return None
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


def build31_pages():
    open_page("账号连接 page", ["账号："], ["账号连接"])
    open_page("消息汇总 page", [("消息", True), ("消息 ", False)], ["消息汇总"], linger=8)
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
