#!/bin/sh
# Install the Mines link worker (systemd). Run as root on the server:
#   curl -fsSL https://cdn.jsdelivr.net/gh/ddfc1991/openminis-agent-android@hark-minis-build/hark-build/vps-worker/install.sh | sh -s -- [--port 8443] [--ssh-mux]
# Re-running keeps the existing token and certificate (the phone stays paired).
# Prints a one-line 连接码 for Mines (also saved in /etc/mines-link/phone.txt,
# which Mines can read by itself over SSH).
set -e
PORT=8443; MUX=""
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --ssh-mux) MUX="--ssh-mux"; shift ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
done
[ "$(id -u)" = 0 ] || { echo "请用 root 运行"; exit 1; }
command -v python3 >/dev/null || { (apt-get update -qq && apt-get install -y -qq python3) || yum install -y -q python3 || dnf install -y -q python3; }
command -v openssl >/dev/null || { (apt-get install -y -qq openssl) || yum install -y -q openssl || dnf install -y -q openssl; }
SRC="${MINES_LINK_SRC:-https://cdn.jsdelivr.net/gh/ddfc1991/openminis-agent-android@hark-minis-build/hark-build/vps-worker/mines-link.py}"
mkdir -p /etc/mines-link /opt/mines-link
if [ -f "$(dirname "$0")/mines-link.py" ]; then cp "$(dirname "$0")/mines-link.py" /opt/mines-link/mines-link.py
else curl -fsSL --retry 3 "$SRC" -o /opt/mines-link/mines-link.py || curl -fsSL --retry 3 \
  "https://raw.githubusercontent.com/ddfc1991/openminis-agent-android/hark-minis-build/hark-build/vps-worker/mines-link.py" -o /opt/mines-link/mines-link.py; fi
python3 -c "import ast,sys;ast.parse(open('/opt/mines-link/mines-link.py').read())"
cd /etc/mines-link
[ -s token ] || (head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > token)
chmod 600 token
if [ ! -s cert.pem ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=$(hostname)" -keyout key.pem -out cert.pem >/dev/null 2>&1
  chmod 600 key.pem
fi
PIN=$(openssl x509 -in cert.pem -outform der | openssl dgst -sha256 | awk '{print $NF}')
if [ -n "$MUX" ] && [ "$PORT" = 443 ] && ss -ltnp 2>/dev/null | grep -q ':443 .*sshd'; then
  echo "注意：443 端口现在被 sshd 占用。--ssh-mux 模式会自己把 SSH 转给 22 端口，请先从 sshd 配置里去掉 Port 443 再重跑。"; exit 1
fi
cat > /etc/systemd/system/mines-link.service <<EOF
[Unit]
Description=Mines link worker (phone's HTTPS channel)
After=network-online.target
[Service]
ExecStart=/usr/bin/env python3 /opt/mines-link/mines-link.py --port $PORT $MUX
Restart=always
RestartSec=2
User=root
MemoryMax=200M
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable mines-link >/dev/null 2>&1
systemctl restart mines-link
command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q active && ufw allow "$PORT"/tcp >/dev/null || true
command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1 && { firewall-cmd --permanent --add-port="$PORT"/tcp >/dev/null; firewall-cmd --reload >/dev/null; } || true
IP=$(curl -s --max-time 5 https://api.ipify.org || hostname -I | awk '{print $1}')
LINE="mines-link://$IP:$PORT/mines#token=$(cat token)&pin=$PIN"
echo "$LINE" > /etc/mines-link/phone.txt; chmod 600 /etc/mines-link/phone.txt
sleep 1
systemctl is-active mines-link >/dev/null && echo "✓ mines-link 已运行（端口 $PORT）" || { echo "✗ 没启动成功："; journalctl -u mines-link -n 20 --no-pager; exit 1; }
echo "云服务器安全组需要放行 TCP $PORT。"
echo "Mines 连接码（在 Mines → 工作位置 → 服务器 → 快速线路 里会自动读取，也可以粘贴）："
echo "$LINE"
