#!/bin/sh
# [Mines-38.9.13 tunnel_v1] reverse tunnel from this machine to the Mines AWS hub. Installed only when the user taps
# 「开启穿透」 in Mines. Undo: 「关闭穿透」 (or: systemctl disable --now mines-tunnel / rc-update del mines-tunnel).
N=mines-tunnel
if [ "$(id -u)" = 0 ]; then D=/etc/$N; else D=$HOME/.$N; fi
mkdir -p "$D" && chmod 700 "$D" || { echo "MINES_ERR 不能创建 $D"; exit 2; }
if ! command -v ssh >/dev/null 2>&1; then
  (apt-get install -y openssh-client || apk add openssh-client || yum install -y openssh-clients) >/dev/null 2>&1
fi
command -v ssh >/dev/null 2>&1 || { echo "MINES_ERR 这台机器没有 ssh 客户端"; exit 3; }
[ -f "$D/key" ] || ssh-keygen -q -t ed25519 -N '' -C "mines-tunnel@$(hostname)" -f "$D/key" || { echo "MINES_ERR 生成钥匙失败"; exit 4; }
cat > "$D/run.sh" <<'EOF'
#!/bin/sh
D=$(cd "$(dirname "$0")" && pwd)
exec ssh -N -i "$D/key" -o BatchMode=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$D/known_hosts" \
  -p @HUB_PORT@ @FORWARDS@ @HUB_USER@@@HUB_HOST@
EOF
chmod 700 "$D/run.sh"
if [ "$(id -u)" = 0 ] && [ -d /run/systemd/system ]; then
  cat > /etc/systemd/system/$N.service <<EOF
[Unit]
Description=Mines reverse tunnel to AWS
After=network-online.target
Wants=network-online.target
[Service]
ExecStart=$D/run.sh
Restart=always
RestartSec=10
[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload && systemctl enable $N >/dev/null 2>&1
  START="systemctl restart $N"; echo "MINES_SVC systemd"
elif [ "$(id -u)" = 0 ] && command -v openrc-run >/dev/null 2>&1; then
  cat > /etc/init.d/$N <<EOF
#!/sbin/openrc-run
description="Mines reverse tunnel to AWS"
command="$D/run.sh"
supervisor=supervise-daemon
respawn_delay=10
respawn_max=0
depend() { need net; }
EOF
  chmod 755 /etc/init.d/$N; rc-update add $N default >/dev/null 2>&1
  START="rc-service $N restart || rc-service $N start"; echo "MINES_SVC openrc"
else
  cat > "$D/loop.sh" <<'EOF'
#!/bin/sh
D=$(cd "$(dirname "$0")" && pwd)
while :; do "$D/run.sh"; sleep 10; done
EOF
  chmod 700 "$D/loop.sh"
  START="pkill -f [m]ines-tunnel/loop.sh; pkill -f [m]ines-tunnel/key; nohup $D/loop.sh"
  (crontab -l 2>/dev/null | grep -v "$N/loop.sh"; echo "@reboot $D/loop.sh") | crontab - 2>/dev/null
  echo "MINES_SVC loop"
fi
echo "MINES_PUB $(cat "$D/key.pub")"
# (re)start after the reply is out: when Mines is connected through this very tunnel, the restart cuts the session
(sleep 2; eval "$START") >/dev/null 2>&1 </dev/null &
