# CLAUDE.md: Acer TV debloat + Glass Home launcher

Context for Claude Code. This-household details (TV IP, Wi-Fi, weather location, local folders) are in `CLAUDE.local.md`, which is gitignored. Read `HISTORY.md` (timeline), `SUMMARY.md` (state by topic) and `README.md` (how-tos) before changing anything.

## The device
- Acer 50" **R4G** (manufacturer MOKA, Realtek chipset), Google TV, **Android 14 / SDK 34**, 2 GB RAM, 32-bit ARM (armeabi-v7a), UI 1920×1080 at density 320 (960×540 dp).
- ADB over TCP, port 5555. The IP comes from DHCP and can change; scripts take it as the first argument or from `TV_IP`. Nothing in the repo hardcodes it.
- The owner must turn **USB debugging** on before any work and off afterwards (Settings → System → Developer options). Nothing is plugged into the TV's USB ports.

## How to talk to the TV
- Claude Code runs on the Mac, so it can reach the TV directly. Use the Python client, not stock `adb`: macOS Local Network privacy blocks the `adb` binary.
  - `.venv/bin/python tvadb.py <IP> "shell cmd" ...` · `--push LOCAL REMOTE` · `--install APK`
  - First run of any script creates `.venv` (`adb-shell rsa pyasn1 cryptography`).
- `runner.sh`/`runner.py` (queue in `queue/`, results in `results/`, screenshots in `shots/`) exist only because the earlier session ran in a sandbox without LAN access. In Claude Code you can call `tvadb.py` directly; the runner still works if preferred.
- Screenshot: `screencap -p /data/local/tmp/s.png`, then pull with `base64` over the shell (native pull/push can hang). Delete temp files afterwards.
- Run **one `pm` command per shell call**; chained ones can stop early.

## Working rules agreed with the owner
- Verify every change with a screenshot from the TV and look at it. Start from a clean state (press HOME first) so a leftover window can't fake a pass.
- Disable apps in small batches with a screenshot between batches.
- Ask before anything that depends on the owner's preferences, and before anything that can't be undone.
- **After any change to which apps are enabled or hidden, test with a real reboot** (the owner can power-cycle at the socket; `reboot` from a detached shell didn't work).
- Keep Glass Home light: no blur, no video, refresh only while home is visible.

## Critical gotchas (learned the hard way)
1. **Acer's boot job re-enables apps.** `com.android.tv.settings/.system.FallbackHome` runs `SetPmodServiceJob` → `disableOrEnableApk(1)` on every boot. That force-re-enables any *disabled* app on a hard-coded list of 60 (setup wizard, katniss, dreamx, updaters, MK apps, …). Apps on that list must be **hidden** (`pm uninstall -k --user 0`), not disabled. See `hidden-packages.txt`.
2. **Setup wizard = black screen.** `com.google.android.tungsten.setupwraith/.RecoveryActivity` claims HOME at priority 1. Android always prefers a higher-priority system HOME over Glass Home (third-party apps are capped at 0). With the stock launcher disabled it loops and the screen goes black. Never un-hide or enable it alone; `restore.sh` re-enables the stock launcher first.
3. Shell **cannot** change single-component state on Android 14 ("Shell cannot change component state").
4. `pm list packages` without `--user 0` also lists apps installed for user 10. Use `--user 0`.
5. The TV's Bluetooth settings screens require `WRITE_SECURE_SETTINGS`. Glass Home deliberately has no Bluetooth pill.
6. The Prime Video remote key (scancode 763 → vendor keycode 2055) is hard-wired in `com.realtek.android.globalkeyhandler`. It can't be remapped without root.
7. Android usage-stats history on this TV is dated 2027 (clock glitch) and unusable, so screen time is self-logged by Glass Home.
8. `input keyevent 178` doesn't open the source menu; use `am start -a com.android.tv.action.VIEW_INPUTS`.

## Current state (23 Oct 2026)
- HOME = Glass Home v1.4 (`com.madhu.glasslauncher`, versionCode 9). Source is at v1.5 (versionCode 10: no built-in weather location), not yet built or installed. Stock launcher (`com.google.android.apps.tv.launcherx`) disabled.
- 20 apps hidden for user 0 (`hidden-packages.txt`), 11 disabled (`disabled-packages.txt`). Passed a real power-socket reboot test.
- Glass Home permissions: location (Wi-Fi name only), INTERNET. Audio output is switched via Realtek's exported `com.realtek.slices/.OutputDeviceReceiver` (`com.realtek.device_output_key`, toggles TV speaker ↔ optical); current value is Settings.Global `speaker_index`.

## Glass Home build
- `glass-launcher/build.sh`: no Gradle and no AndroidX. Uses `$ANDROID_HOME`/`~/Library/Android/sdk` (build-tools + platforms) if present, else Debian packages (`aapt dalvik-exchange zipalign apksigner android-sdk-platform-23`). Output: `glass-launcher/GlassHome.apk`.
- Sign with **`glass-launcher/debug.jks`** (password `android`). A different key means Android refuses to update the installed app.
- Bump `versionCode` in `AndroidManifest.xml` for each release. Install: `bash install.sh <IP>` builds then installs; `--apk FILE` installs a ready-made APK. No APK is committed (`*.apk` is gitignored).
- Code: `src/com/madhu/glasslauncher/HomeActivity.java` (UI, focus model, top bar, dropdowns, widgets wiring) and `Widgets.java` (network fetch, weather/Nifty parsing, calendar, sparkline, RAM bar, weather icons). Fonts: Outfit 200–600 (OFL) in `assets/fonts`.
- Runtime config, no rebuild needed: `/sdcard/Android/data/com.madhu.glasslauncher/files/` → `dock.txt`, `wallpaper.jpg`, `widgets.txt` (lat/lon/place).

## Files
| Path | Purpose |
|---|---|
| `install.sh`, `restore.sh`, `tvadb.py` | Install Glass Home / undo everything / ADB helper |
| `runner.sh`, `runner.py` | Optional job-queue runner |
| `disabled-packages.txt`, `hidden-packages.txt` | Exact current debloat state |
| `backup/` | Pre-change backup (19 Oct): package lists, getprop, settings, TV inputs, dumpsys of vendor packages, audio-setting snapshots |
| `glass-launcher/` | Launcher source, build script, signing key |
| `queue/ results/ done/ shots/` | Runner working dirs (empty) |

Not in the repo: `backup/` (device identifiers), `.venv`, runner logs, screenshots, and the vendor APKs pulled for decompiling (`TvSettingsVendor.apk`, `RtkMenuSliceProvider.apk`, `RtkGlobalKeyHandler.apk`). Pull them again from the TV if needed.
