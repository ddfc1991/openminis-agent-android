# [Mines-net-26] Shared helpers for the apk/pip wrappers, mines-doctor and mines-devkit.
# Sourced, not executed.

# host:port out of a proxy URL (drops scheme, user:pass@ and path).
minis_hostport() {
    echo "$1" | sed -e 's#^[a-zA-Z0-9+.-]*://##' -e 's#^[^@/]*@##' -e 's#/.*$##'
}

# minis_tcp_ok <host> <port>: something accepts a TCP connection within ~3 s.
minis_tcp_ok() {
    local h=$1 p=$2
    h=${h#[}; h=${h%]}
    timeout 4 nc -w 3 "$h" "$p" </dev/null >/dev/null 2>&1
}

minis_proxy_url() { echo "${https_proxy:-${HTTPS_PROXY:-${http_proxy:-${HTTP_PROXY:-}}}}"; }

# If the exported proxy does not even accept TCP, drop it for this command
# instead of sending every download into a dead port.
minis_check_proxy() {
    local p hp h port
    p=$(minis_proxy_url)
    [ -n "$p" ] || return 0
    hp=$(minis_hostport "$p"); h=${hp%:*}; port=${hp##*:}
    [ "$port" = "$hp" ] && port=80
    if ! minis_tcp_ok "$h" "$port"; then
        echo "⚠ 代理 $h:$port 连不上，这次直连（设置 → 模型路由与分流 里检查代理，或在 设置 → 沙箱与防杀 把沙箱代理设为「不用代理」）" >&2
        unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy
        export MINES_PROXY_DROPPED=1
    fi
}

# First use: pick mirrors once before the first download.
minis_mirrors_once() {
    [ -s /etc/minis/mirrors.state ] && return 0
    echo "首次使用：正在测速选择软件源…" >&2
    mines-mirror auto "$@" >&2 || true
}

# run_logged <logfile> <cmd...>: run, show output, keep a copy, return the real exit code.
minis_run_logged() {
    local log=$1 rcf rc
    shift
    rcf=$(mktemp)
    { "$@"; echo $? > "$rcf"; } 2>&1 | tee "$log"
    rc=$(cat "$rcf" 2>/dev/null); rm -f "$rcf"
    return "${rc:-1}"
}
