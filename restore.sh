#!/bin/bash
# Undo everything: re-enable every package in disabled-packages.txt (incl. the stock
# Google TV launcher and the setup wizard) and make Google TV the default home again.
# Usage: bash restore.sh [TV_IP] [--remove-launcher]   (or set TV_IP in the environment)
#   --remove-launcher  also uninstall Glass Home
cd "$(dirname "$0")"
IP="$TV_IP"; REMOVE=0
for a in "$@"; do case "$a" in --remove-launcher) REMOVE=1;; *) IP="$a";; esac; done
[ -n "$IP" ] || { echo "TV IP needed: pass it as the first argument or set TV_IP" >&2; exit 1; }
if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv && .venv/bin/pip install -q adb-shell rsa pyasn1 cryptography || exit 1
fi
# stock launcher first, so the setup wizard has a home to hand back to
CMDS=("pm enable com.google.android.apps.tv.launcherx")
# apps hidden for user 0 (they survive Acer's boot-time re-enable job only when hidden)
while read -r p; do [ -n "$p" ] && CMDS+=("cmd package install-existing $p" "pm enable $p"); done < hidden-packages.txt
while read -r p; do
  [ -n "$p" ] && [ "$p" != com.google.android.apps.tv.launcherx ] && CMDS+=("pm enable $p")
done < disabled-packages.txt
CMDS+=("cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity")
[ $REMOVE = 1 ] && CMDS+=("pm uninstall com.madhu.glasslauncher")
CMDS+=("pm list packages -d" "input keyevent KEYCODE_HOME")
exec .venv/bin/python tvadb.py "$IP" "${CMDS[@]}"
