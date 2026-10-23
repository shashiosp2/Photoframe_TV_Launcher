#!/bin/bash
# Build and install Glass Home on the TV, then make it the default home.
# Usage: bash install.sh [TV_IP] [--apk FILE]   (or set TV_IP in the environment)
#   default      build glass-launcher/ first (needs a JDK + Android build tools, see README)
#   --apk FILE   install an already-built APK instead (e.g. one from a GitHub release)
set -e
cd "$(dirname "$0")"
IP="$TV_IP"; APK=""
while [ $# -gt 0 ]; do
  case "$1" in --apk) APK="$2"; shift;; *) IP="$1";; esac
  shift
done
[ -n "$IP" ] || { echo "TV IP needed: pass it as the first argument or set TV_IP" >&2; exit 1; }
if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv && .venv/bin/pip install -q adb-shell rsa pyasn1 cryptography
fi
if [ -z "$APK" ]; then bash glass-launcher/build.sh; APK=glass-launcher/GlassHome.apk; fi
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
PKG=com.madhu.glasslauncher
.venv/bin/python tvadb.py "$IP" \
  --install "$APK" \
  "pm grant $PKG android.permission.ACCESS_FINE_LOCATION" \
  "pm grant $PKG android.permission.ACCESS_COARSE_LOCATION" \
  "pm uninstall -k --user 0 com.google.android.tungsten.setupwraith" \
  "pm disable-user --user 0 com.google.android.apps.tv.launcherx" \
  "cmd package set-home-activity $PKG/.HomeActivity" \
  "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME" \
  "input keyevent KEYCODE_HOME"
echo "Done. Glass Home should now be on screen."
