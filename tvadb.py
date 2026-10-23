#!/usr/bin/env python3
"""Talk to the TV over ADB/TCP with the adb-shell package (works around macOS Local
Network privacy blocking the stock adb binary).

Usage: tvadb.py IP [ARGS...]   ARGS run in order, each as its own shell call:
  "any shell command"           run it (one pm command per call - chained ones can stop early)
  --push LOCAL REMOTE           copy a file (base64 chunks + md5 check; native push can hang)
  --install APK                 push to /data/local/tmp and `pm install -r -t` (adb install hangs on
                                Android 14 waiting for an on-screen confirmation)
"""
import sys, os, base64, hashlib
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner
from adb_shell.auth.keygen import keygen

def push(d, local, remote):
    data = open(local, "rb").read(); md5 = hashlib.md5(data).hexdigest()
    b = base64.b64encode(data).decode()
    d.shell(f"rm -f '{remote}.b64' '{remote}'")
    for i in range(0, len(b), 60000):
        d.shell(f"printf '%s' '{b[i:i+60000]}' >> '{remote}.b64'", timeout_s=60)
    d.shell(f"base64 -d '{remote}.b64' > '{remote}' && rm -f '{remote}.b64'", timeout_s=120)
    r = d.shell(f"md5sum '{remote}'").split()[0]
    if r != md5: sys.exit(f"md5 mismatch for {remote}: {md5} vs {r}")
    print(f"pushed {local} -> {remote} ({len(data)} bytes, md5 ok)")

ip, args = sys.argv[1], sys.argv[2:]
k = os.path.expanduser("~/.android/adbkey")
if not os.path.exists(k):
    os.makedirs(os.path.dirname(k), exist_ok=True); keygen(k)
s = PythonRSASigner(open(k + ".pub").read(), open(k).read())
d = AdbDeviceTcp(ip, 5555, default_transport_timeout_s=15.)
print(f"Connecting to {ip}:5555 (accept 'Allow USB debugging?' on the TV if it appears)...", flush=True)
d.connect(rsa_keys=[s], auth_timeout_s=90.)
i = 0
while i < len(args):
    a = args[i]
    if a == "--push":
        push(d, args[i+1], args[i+2]); i += 3; continue
    if a == "--install":
        push(d, args[i+1], "/data/local/tmp/install.apk")
        print(d.shell("pm install -r -t /data/local/tmp/install.apk", timeout_s=300).strip()); i += 2; continue
    print(f"$ {a}\n{d.shell(a, timeout_s=300, read_timeout_s=300).rstrip()}", flush=True); i += 1
d.close()
