#!/usr/bin/env bash
# Pyra Claw hub installer for Raspberry Pi OS Bookworm (64-bit). Run with sudo.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
[[ $EUID -eq 0 ]] || { echo "run as root (sudo)"; exit 1; }

apt-get update
apt-get install -y python3 python3-venv mosquitto mosquitto-clients
systemctl enable --now mosquitto

id pyra &>/dev/null || useradd --system --home /var/lib/pyra-claw --shell /usr/sbin/nologin pyra
install -d -o pyra -g pyra /var/lib/pyra-claw
install -d /etc/pyra-claw /opt/pyra-claw

python3 -m venv /opt/pyra-claw/venv
/opt/pyra-claw/venv/bin/pip install --upgrade pip
/opt/pyra-claw/venv/bin/pip install "$HERE"

if [[ ! -f /etc/pyra-claw/config.toml ]]; then
  TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  sed "s/CHANGE_ME/$TOKEN/" "$HERE/config.example.toml" > /etc/pyra-claw/config.toml
  sed -i 's#/var/lib/pyra-claw/audit.jsonl#/var/lib/pyra-claw/audit.jsonl#' /etc/pyra-claw/config.toml
  chmod 640 /etc/pyra-claw/config.toml; chgrp pyra /etc/pyra-claw/config.toml
  echo "Generated auth token (save it for the Android app): $TOKEN"
fi

install -m 644 "$HERE/systemd/pyra-hub.service" /etc/systemd/system/pyra-hub.service
systemctl daemon-reload
systemctl enable --now pyra-hub
systemctl --no-pager status pyra-hub || true
