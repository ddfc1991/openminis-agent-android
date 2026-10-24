"""launch.py — how a STDIO MCP server is actually started (Mines build-29).

[Mines-mcp-29] Why: on the phone every curated server (memory, thinking,
files, context7, weread) is `npx -y <pkg>`. npx re-resolves the package against
the npm registry on every cold start and node itself starts slowly under PRoot,
so a first call easily needs 20-90 s; the old 60 s default (and any 8 s caller
timeout) failed all of them at once, with the child's stderr thrown away so the
error never said why.

 * resolve(): `npx -y pkg args` → `node <global-install>/bin.js args` when the
   package is installed globally (no registry round-trip, starts offline);
   otherwise npx with --prefer-offline, and a background `npm i -g pkg` so the
   next start is the fast path.
 * stderr goes to /var/minis/mcp-servers/logs/<name>.err; tail() returns its
   last lines for error messages.
 * default_startup(): 180 s for npx/uvx servers that are not yet installed,
   60 s otherwise; an explicit startupTimeoutSeconds always wins.
"""

import json
import os
import shutil
import subprocess

LOG_DIR = "/var/minis/mcp-servers/logs"
GLOBAL_ROOTS = ("/usr/local/lib/node_modules", "/usr/lib/node_modules", "/root/.npm-global/lib/node_modules")


def _pkg_name(spec):
    if spec.startswith("@"):
        rest = spec[1:]
        return "@" + rest.split("@", 1)[0]
    return spec.split("@", 1)[0]


def _global_bin(name):
    roots = list(GLOBAL_ROOTS)
    prefix = os.environ.get("NPM_CONFIG_PREFIX") or os.environ.get("npm_config_prefix")
    if prefix:
        roots.insert(0, os.path.join(prefix, "lib", "node_modules"))
    for root in roots:
        pj = os.path.join(root, name, "package.json")
        try:
            with open(pj, "r", encoding="utf-8") as f:
                meta = json.load(f)
        except (OSError, ValueError):
            continue
        b = meta.get("bin")
        if isinstance(b, str):
            rel = b
        elif isinstance(b, dict) and b:
            short = name.split("/")[-1]
            rel = b.get(short) or b.get(name) or list(b.values())[0]
        else:
            continue
        path = os.path.normpath(os.path.join(root, name, rel))
        if os.path.exists(path):
            return path
    return None


def split_npx(command, args):
    """(flags, spec, rest) for an npx command line, or None."""
    if os.path.basename(command or "") != "npx":
        return None
    a = list(args or [])
    flags = []
    while a and a[0].startswith("-"):
        flags.append(a.pop(0))
    if not a:
        return None
    return flags, a[0], a[1:]


def installed(command, args):
    """True when the server can start without fetching anything."""
    n = split_npx(command, args)
    if n is None:
        return os.path.basename(command or "") not in ("uvx",)
    return _global_bin(_pkg_name(n[1])) is not None


def resolve(command, args):
    n = split_npx(command, args)
    if n is None:
        return command, list(args or [])
    flags, spec, rest = n
    path = _global_bin(_pkg_name(spec))
    node = shutil.which("node")
    if path and node:
        return node, [path] + rest
    extra = [] if "--prefer-offline" in flags else ["--prefer-offline"]
    if not any(f in ("-y", "--yes") for f in flags):
        extra.append("-y")
    background_install(spec)
    return command, flags + extra + [spec] + rest


def background_install(spec):
    """Detached `npm i -g <spec>` so the next start skips npx (best effort)."""
    npm = shutil.which("npm")
    if not npm:
        return
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        marker = os.path.join(LOG_DIR, ".installing-" + spec.replace("/", "_"))
        if os.path.exists(marker):
            return
        open(marker, "w").close()
        logf = open(os.path.join(LOG_DIR, "npm-install.log"), "a")
        subprocess.Popen(
            ["sh", "-c", 'npm install -g --no-audit --no-fund "$1"; rm -f "$2"', "sh", spec, marker],
            stdin=subprocess.DEVNULL, stdout=logf, stderr=logf, start_new_session=True,
        )
    except OSError:
        pass


def default_startup(cfg):
    if not installed(cfg.get("command", ""), cfg.get("args") or []):
        return 180
    return 60


def stderr_path(name):
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
    except OSError:
        pass
    return os.path.join(LOG_DIR, "%s.err" % name.replace("/", "_"))


def open_stderr(name):
    """Fresh stderr file for this start (old one kept as .err.1)."""
    p = stderr_path(name)
    try:
        if os.path.exists(p):
            os.replace(p, p + ".1")
        return open(p, "w")
    except OSError:
        return subprocess.DEVNULL


def tail(name, lines=4):
    try:
        with open(stderr_path(name), "r", encoding="utf-8", errors="replace") as f:
            data = f.read()[-4000:]
    except OSError:
        return ""
    rows = [r.strip() for r in data.splitlines() if r.strip()]
    return " | ".join(rows[-lines:])[:600]


def explain(name, cfg):
    """One line: the likeliest reason a server did not answer, from its stderr."""
    t = tail(name)
    low = t.lower()
    if "enotfound" in low or "eai_again" in low or "getaddrinfo" in low:
        why = "npm 下载不了包（DNS/网络）。运行 mines-doctor 查网络，或在 设置 → 沙箱 换 npm 源"
    elif "etimedout" in low or "econnreset" in low or "socket hang up" in low or "network" in low:
        why = "npm 下载包时网络超时/被重置。运行 mines-doctor"
    elif "e404" in low or "404 not found" in low:
        why = "npm 上没有这个包名"
    elif "cannot find module" in low or "err_module_not_found" in low:
        why = "包装坏了（缺文件），删掉后重新安装"
    elif "eacces" in low or "permission denied" in low:
        why = "没有权限"
    elif "cookie" in low or "token" in low or "unauthor" in low or "api key" in low:
        why = "缺少登录信息（Cookie/令牌），在 MCP 页填写"
    elif t:
        why = "服务器启动出错"
    elif not installed(cfg.get("command", ""), cfg.get("args") or []):
        why = "第一次启动要先从 npm 下载包，还没下载完（后台在装，稍后再试）"
    else:
        why = "进程没有输出任何错误"
    return why + ((" — stderr: " + t) if t else "")
