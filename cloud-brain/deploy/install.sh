#!/bin/bash
# mines-brain installer (idempotent). Run on the AWS hub as ubuntu with sudo: bash install.sh /tmp/mines-brain-src
# Opens no port: listens on 127.0.0.1:8790; the phone uses `ssh -L` through its existing SSH 22 session.
set -euo pipefail
SRC=${1:-/tmp/mines-brain-src}
id mbrain >/dev/null 2>&1 || sudo useradd --system --home /var/lib/mines-brain --shell /usr/sbin/nologin mbrain
sudo install -d -o mbrain -g mbrain -m 0700 /var/lib/mines-brain
sudo install -d -o root -g root -m 0755 /opt/mines-brain /opt/mines-brain/mines_brain
sudo install -o root -g root -m 0644 "$SRC"/mines_brain/*.py /opt/mines-brain/mines_brain/
# token (kept across reinstalls); a 0600 copy for ubuntu so the phone can read it once over its own SSH login
if ! sudo test -s /var/lib/mines-brain/token; then
  python3 -c "import secrets;print('mb_'+secrets.token_urlsafe(32))" | sudo tee /var/lib/mines-brain/token >/dev/null
fi
sudo chown mbrain:mbrain /var/lib/mines-brain/token; sudo chmod 0600 /var/lib/mines-brain/token
install -d -m 0700 /home/ubuntu/.mines-brain
sudo cat /var/lib/mines-brain/token > /home/ubuntu/.mines-brain/token; chmod 0600 /home/ubuntu/.mines-brain/token
# shell jail = the existing devbox container (127.0.0.1:22022): brain's own key, appended once
if ! sudo test -s /var/lib/mines-brain/devbox_ed; then
  sudo -u mbrain ssh-keygen -q -t ed25519 -N '' -C mines-brain@aws -f /var/lib/mines-brain/devbox_ed
fi
PUB=$(sudo cat /var/lib/mines-brain/devbox_ed.pub)
sudo docker exec devbox sh -c "mkdir -p /root/.ssh && chmod 700 /root/.ssh && touch /root/.ssh/authorized_keys && grep -qF '$PUB' /root/.ssh/authorized_keys || echo '$PUB' >> /root/.ssh/authorized_keys"
sudo install -o root -g root -m 0644 "$SRC"/deploy/mines-brain.service /etc/systemd/system/mines-brain.service
sudo systemctl daemon-reload
sudo systemctl enable mines-brain >/dev/null 2>&1
sudo systemctl restart mines-brain
sleep 2
systemctl is-active mines-brain
curl -s 127.0.0.1:8790/v1/health; echo
