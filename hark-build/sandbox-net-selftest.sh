#!/bin/sh
# [Mines-net-26] CI: boot the app's own Alpine rootfs (aarch64, run under qemu) with
# the app's default_mount overlay, its env (etc/minis/net.env + CA bundle built the
# way SandboxNet.prepareRootfs does), and the mirrors/pip.conf minis-mirror writes.
# Then install real packages through the same wrappers the phone uses, and prove
# that a stalled mirror and a dead proxy no longer hang anything. Exit 1 = build fails.
set -u
fail() { echo "::error::sandbox net self-test: $*"; exit 1; }
step() { echo; echo "===== $* ($(date +%T))"; }

step "overlay default_mount (RootfsManager.applyDefaultMountOverlay)"
cp -a /mnt/dm/. / || fail "overlay"
chmod +x /usr/local/bin/* 2>/dev/null
[ -s /etc/ssl/minis-mozilla.pem ] || fail "Mozilla CA bundle missing from the APK assets"
cat /etc/ssl/minis-mozilla.pem /etc/ssl/certs/ca-certificates.crt > /etc/ssl/minis-ca.pem
set -a; . /etc/minis/net.env; set +a
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin HOME=/root TZ=UTC
printf 'vpn=0\ntransport=CI\ndns=\nipv6=0\nproxy_source=none\nsig=ci-1\n' > /etc/minis/net.state
cat /etc/alpine-release; command -v apk pip

step "minis-mirror auto apk pip npm"
minis-mirror auto apk pip npm || fail "no working mirror"
cat /etc/apk/repositories /etc/pip.conf /root/.npmrc

step "apk update && apk add --no-cache gcc"
apk update || fail "apk update"
apk add --no-cache gcc musl-dev || fail "apk add gcc"
printf 'int main(void){return 0;}\n' > /tmp/t.c && gcc /tmp/t.c -o /tmp/t && /tmp/t || fail "gcc does not work"

step "python3 + pip download requests"
apk add --no-cache python3 py3-pip || fail "apk add python3 py3-pip"
pip download -q -d /tmp/pd requests || fail "pip download requests"
ls /tmp/pd | grep -q '^requests-' || fail "requests wheel missing"
python3 -c "import ssl,urllib.request; urllib.request.urlopen('https://pypi.org/simple/pip/', timeout=20).read(100); print('python TLS ok')" || fail "python TLS to pypi.org"

step "stalled apk mirror: guard stops it, mirror is re-picked, apk succeeds"
python3 -c "
import socket,time,os
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); s.bind(('127.0.0.1',8099)); s.listen(64)
if os.fork(): raise SystemExit
c=[]
end=time.time()+900
while time.time()<end: c.append(s.accept()[0])
" || fail "blackhole"
printf 'http://127.0.0.1:8099/alpine/v3.21/main\n' > /etc/apk/repositories
t0=$(date +%s)
MINIS_APK_STALL=15 timeout 600 apk update; rc=$?
echo "apk update rc=$rc after $(( $(date +%s) - t0 ))s"
[ "$rc" = 0 ] || fail "apk did not recover from a stalled mirror (rc=$rc)"
grep -q 8099 /etc/apk/repositories && fail "mirror was not switched away from the stalled one"

step "dead proxy exported: dropped for the command, apk still works"
https_proxy=http://127.0.0.1:9 http_proxy=http://127.0.0.1:9 apk update || fail "apk with a dead proxy"
https_proxy=http://127.0.0.1:9 pip download -q -d /tmp/pd3 six || fail "pip with a dead proxy"

step "stalled pip index: pip times out, wrapper re-picks, download succeeds"
cp /etc/pip.conf /tmp/pip.conf.ok
sed -i 's#^index-url = .*#index-url = http://127.0.0.1:8099/simple/#; /^extra-index-url/d' /etc/pip.conf
t0=$(date +%s)
MINIS_PIP_STALL=30 timeout 900 pip download -q -d /tmp/pd2 six; rc=$?
echo "pip rc=$rc after $(( $(date +%s) - t0 ))s"
[ "$rc" = 0 ] || fail "pip did not recover from a stalled index (rc=$rc)"

step "minis-doctor (must finish and print a verdict)"
timeout 300 minis-doctor > /tmp/doctor.txt 2>&1; rc=$?
cat /tmp/doctor.txt
[ "$rc" = 0 ] || fail "minis-doctor rc=$rc"
grep -qE 'MINES_DOCTOR_(OK|PROBLEMS)' /tmp/doctor.txt || fail "minis-doctor printed no verdict"

step "minis-devkit check / vps-command"
minis-devkit vps-command | grep -q MINES_DEVKIT_OK || fail "devkit vps-command"
echo; echo "SANDBOX_NET_SELFTEST_OK"
