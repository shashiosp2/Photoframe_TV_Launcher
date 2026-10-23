#!/usr/bin/env python3
"""Acer TV ADB runner.
Executes ONLY ADB steps from JSON job files in ./queue, writes results to ./results
and screenshots to ./shots. No local shell commands are ever run. Ctrl-C to stop.
Job file: {"steps":[{"op":"shell","cmd":"..."}, {"op":"shot","name":"x"}, ...]}
ops: shell(cmd,timeout) | shot(name) | key(codes,delay) | pull(remote,local) |
     push(local,remote) [base64 chunks + md5 verify] | install(local) | sleep(s) | save(cmd,local)
"""
import sys, os, json, time, base64, hashlib, traceback, glob, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
HOST = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("TV_IP") or sys.exit("TV IP needed: pass it as the first argument or set TV_IP")
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 5555

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner
from adb_shell.auth.keygen import keygen

def safe(p):
    full = os.path.realpath(os.path.join(HERE, p))
    if not full.startswith(HERE + os.sep):
        raise ValueError("path outside acer-tv folder: " + p)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    return full

def signer():
    kdir = os.path.expanduser("~/.android"); os.makedirs(kdir, exist_ok=True)
    k = os.path.join(kdir, "adbkey")
    if not os.path.exists(k):
        keygen(k)
    with open(k) as f: priv = f.read()
    with open(k + ".pub") as f: pub = f.read()
    return PythonRSASigner(pub, priv)

dev = None
def connect():
    global dev
    while True:
        try:
            d = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=15.)
            print(f"[runner] connecting to {HOST}:{PORT} ... if the TV shows 'Allow USB debugging?', tick 'Always allow' and press OK", flush=True)
            d.connect(rsa_keys=[signer()], auth_timeout_s=90.)
            dev = d
            print("[runner] connected", flush=True)
            status(True)
            return
        except Exception as e:
            print("[runner] connect failed:", repr(e), "- retrying in 5s", flush=True)
            status(False, repr(e)); time.sleep(5)

def status(ok, err=""):
    with open(os.path.join(HERE, "status.json"), "w") as f:
        json.dump({"connected": ok, "host": HOST, "err": err, "t": time.time()}, f)

def sh(cmd, timeout=60):
    return dev.shell(cmd, timeout_s=timeout, read_timeout_s=timeout, transport_timeout_s=timeout)

def pull_b64(remote, local):
    out = sh(f"base64 '{remote}' | tr -d '\\n'", timeout=300)
    data = base64.b64decode(out.strip())
    with open(safe(local), "wb") as f: f.write(data)
    return len(data)

def run_step(s):
    op = s["op"]
    if op == "shell":
        return sh(s["cmd"], s.get("timeout", 60))
    if op == "save":  # shell output straight to a local file
        out = sh(s["cmd"], s.get("timeout", 120))
        with open(safe(s["local"]), "w") as f: f.write(out)
        return f"saved {len(out)} chars to {s['local']}"
    if op == "shot":
        sh("screencap -p /data/local/tmp/_shot.png", 30)
        n = pull_b64("/data/local/tmp/_shot.png", f"shots/{s['name']}.png")
        return f"shots/{s['name']}.png ({n} bytes)"
    if op == "key":
        res = []
        for c in s["codes"]:
            res.append(sh(f"input keyevent {c}", 15)); time.sleep(s.get("delay", 0.6))
        return "keys sent: " + " ".join(map(str, s["codes"]))
    if op == "sleep":
        time.sleep(s["s"]); return "slept"
    if op == "pull":
        return f"{pull_b64(s['remote'], s['local'])} bytes"
    if op in ("push", "install"):
        local = safe(s["local"]); remote = s.get("remote", "/data/local/tmp/" + os.path.basename(local))
        data = open(local, "rb").read(); md5 = hashlib.md5(data).hexdigest()
        b = base64.b64encode(data).decode(); CH = 60000
        sh(f"rm -f '{remote}.b64' '{remote}'")
        for i in range(0, len(b), CH):
            sh(f"printf '%s' '{b[i:i+CH]}' >> '{remote}.b64'", 60)
        sh(f"base64 -d '{remote}.b64' > '{remote}' && rm -f '{remote}.b64'", 120)
        rmd5 = sh(f"md5sum '{remote}'").split()[0]
        if rmd5 != md5: raise RuntimeError(f"md5 mismatch local {md5} remote {rmd5}")
        msg = f"pushed {len(data)} bytes md5 ok {md5}"
        if op == "install":
            msg += "\n" + sh(f"pm install -r -t '{remote}'", 300)
        return msg
    raise ValueError("unknown op " + op)

def main():
    connect()
    print("[runner] watching queue/ - leave this window open. Ctrl-C to stop.", flush=True)
    while True:
        jobs = sorted(glob.glob(os.path.join(HERE, "queue", "*.json")))
        if not jobs:
            time.sleep(1); continue
        j = jobs[0]; name = os.path.basename(j)[:-5]
        try:
            job = json.load(open(j))
        except Exception:
            time.sleep(1); continue  # still being written
        print(f"[runner] job {name}: {len(job['steps'])} step(s)", flush=True)
        results = []
        for s in job["steps"]:
            for attempt in (1, 2):
                try:
                    out = run_step(s); results.append({"step": s if s['op'] not in ('push','install') else {'op':s['op'],'local':s['local']}, "ok": True, "out": out}); break
                except Exception as e:
                    if attempt == 1 and not isinstance(e, (ValueError,)):
                        print("[runner] error, reconnecting:", repr(e), flush=True)
                        try: dev.close()
                        except Exception: pass
                        connect(); continue
                    results.append({"step": s, "ok": False, "out": repr(e)}); break
        with open(os.path.join(HERE, "results", name + ".json"), "w") as f:
            json.dump(results, f, indent=1)
        try: shutil.move(j, os.path.join(HERE, "done", name + ".json"))
        except Exception: pass
        print(f"[runner] job {name} done", flush=True)

if __name__ == "__main__":
    try: main()
    except KeyboardInterrupt: print("\n[runner] stopped")
