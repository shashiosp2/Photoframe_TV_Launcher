#!/bin/bash
# Start the Acer TV ADB runner. Usage: bash runner.sh [TV_IP]   (or set TV_IP in the environment)
cd "$(dirname "$0")"
IP="${1:-$TV_IP}"
[ -n "$IP" ] || { echo "TV IP needed: pass it as the first argument or set TV_IP" >&2; exit 1; }
if [ ! -x .venv/bin/python ]; then
  echo "Creating venv and installing adb-shell (one time)..."
  python3 -m venv .venv && .venv/bin/pip install -q --upgrade pip && .venv/bin/pip install -q adb-shell rsa pyasn1 cryptography || exit 1
fi
exec .venv/bin/python -u runner.py "$IP" 5555
