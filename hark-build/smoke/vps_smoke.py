#!/usr/bin/env python3
"""VPS-screen crash smoke test for the Mines release APK on an emulator.

Drives the real UI with adb + uiautomator (no test APK, release build as
shipped): launch -> home Today card -> 工作位置 -> add a reachable SSH server
(the runner's own sshd at 10.0.2.2, password login + key install) -> VPS mode
-> server detail -> add an unreachable server -> 立即检测 -> Settings ->
工作位置. After every step it checks the app process is still alive and at the
end prints every FATAL EXCEPTION from logcat. Exit 1 when the app crashed.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

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
    e = edits(dump())
    print("  edit texts:", [n.get("text") for _, _, n in e], flush=True)
    # label, key, base url (order on the configure page): URL first, the IME then hides less
    if len(e) >= 3:
        fill_edit(2, "http://10.0.2.2:8088")
        sh("input keyevent KEYCODE_BACK"); time.sleep(1)
        fill_edit(1, "sk-smoke-000000000000000000000000")
    sh("input keyevent KEYCODE_BACK"); time.sleep(1)
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
    if not tap_text("smoke-model", wait=2):
        if tap_any(["Add Model Manually"], wait=3):
            fill_edit(0, "smoke-model", clear=False)
            tap_any(["Add", "Save", "OK", "添加"], wait=3)
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
        sh("input keyevent KEYCODE_BACK"); time.sleep(3)
    root = snap("home-with-chat")
    check("home list (TodayCard) shown")


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


def user_like_state():
    """Wallpaper on home + glass + desk pet on, like the user's phone, then cold start."""
    for _ in range(2):
        if tap_any(["Settings", "设置"], wait=3, exact=True): break
    snap("settings")
    for _ in range(6):
        if tap_any(["壁纸与形象"], wait=4): break
        scroll_down()
    snap("personalize")
    tap_any(["极光"], wait=2, exact=True)
    for _ in range(3):
        if toggle_near("首页也用这张壁纸"): break
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
    if not tap_text("工作位置", wait=6):
        # Today card may be collapsed: tap the greeting card to expand
        tap_text("好", wait=2)
        tap_text("工作位置", wait=6)
    snap("work-location")
    if not check("open 工作位置 from home"):
        relaunch(); tap_text("工作位置", wait=6)

    # Reachable server (runner sshd), password → key install
    add_server("smoke", SSH_HOST, SSH_PASS)
    time.sleep(20)
    snap("after-add-reachable")
    if not check("add reachable server + authorize"):
        relaunch(); tap_text("工作位置", wait=6)

    # VPS mode
    tap_text("重活交给服务器", wait=15)
    snap("vps-mode")
    if not check("switch to 服务器 mode"):
        relaunch(); tap_text("工作位置", wait=6)

    # Server detail (stats over JSch)
    tap_text("smoke", exact=True, wait=20)
    snap("server-detail")
    if not check("open server detail"):
        relaunch(); tap_text("工作位置", wait=6)
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
    tap_text("服务器", exact=True, wait=8)
    snap("server-chip")
    check("open 服务器 chip")
    sh("input keyevent KEYCODE_BACK"); time.sleep(2)

    # Cold start with VPS mode + servers saved (the user's state)
    sh(f"am force-stop {PKG}"); time.sleep(2)
    relaunch()
    snap("cold-start-vps")
    check("cold start in VPS mode")
    tap_text("工作位置", wait=10)
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
    # Mines' own crash log (build-28+), readable through the app's files only for debuggable builds; logcat tag MinesCrash
    for l in log:
        if "MinesCrash" in l:
            print(l)
    sys.exit(1 if (crashed or fatal) else 0)


if __name__ == "__main__":
    main()
