# Acer 50" Google TV: debloat + Glass Home launcher (work log)

Done 19 Oct 2026. Everything is reversible. See `README.md` for how-tos.

## The TV

| Item | Value |
|---|---|
| Model | Acer R4G (manufacturer MOKA, Realtek chipset), 50" |
| OS | Google TV, Android 14 (SDK 34), build `UKR1.260308`, 32-bit ARM |
| RAM | 2 GB (≈350 MB free at idle) |
| Display | 3840×2160 panel, UI at 1920×1080, density 320 (960×540 dp) |
| Network | Wi-Fi, DHCP address (scripts take it as an argument or `TV_IP`) |
| Remote | IR (`IR_rca`), Bluetooth off |
| Inputs | HDMI 1–3, AV, ATV, Cable, Antenna (Live TV app `com.realtek.tv`) |

## 1. Connection

- The Mac can't reach the TV from Claude's sandbox, so a **runner** (`runner.sh` → `runner.py`) ran in your Terminal. It ran ADB steps queued in `queue/` and saved results to `results/` and screenshots to `shots/`.
- ADB client: the Python `adb-shell` package in `.venv/`, not stock adb (avoids the macOS Local Network block). File pushes use base64 chunks with an md5 check. APKs are installed with `pm install -r` over the shell.
- "Allow USB debugging?" was accepted once with **Always allow**. The key is in `~/.android/adbkey`.
- `tvadb.py IP "cmd" …` / `--push` / `--install` is the day-to-day helper. You confirmed it works.

## 2. Backup (before any change)

`backup/`: full package list, the disabled list (empty then), system-only and third-party lists, `getprop`, all `settings` (system/secure/global), the default home, TV inputs, the leanback app list, and `dumpsys package` for 44 manufacturer packages.

## 3. Debloat: 29 packages disabled (`pm disable-user --user 0`, never uninstalled)

Done in 6 batches, with a screenshot after each. Home, Live TV, the inputs and the source menu were checked every time.

| Batch | Disabled | Why |
|---|---|---|
| 1 Telemetry | `com.google.android.feedback`, `com.android.tv.feedbackconsent`, `com.apps.function.gdpr`, `com.mk.tv.systemserver` | Feedback upload, consent screens, Firebase-only stub |
| 2 Content/ads | `com.apps.mk.wd` (S-Channel), `com.apps.esticker`, `com.apps.esticker3`, `android.autoinstalls.config.scbc.device`, `com.realtek.leanbacklauncher.partnercustomizer`, `com.google.android.apps.tv.dreamx` | Ad activities, shop-demo overlays, promo auto-installs, stock-launcher partner rows, Google ambient screensaver |
| 3 Background/preloaded | `com.apps.tvmanager`, `com.apps.scast`, `com.mk.tv.meeting.service`, `com.apps.biz.mt`, `com.apps.function.audiolink`, `com.mk.tv.timeclock`, `com.apps.wifihotspot` | Cleaner, signage/cast duplicate, Meeting Mode, Audio Link, World Clock, Hotspot |
| 4 Unused apps | MX Player, JioCinema, YuppTV, Google TV/Play Movies, Play Games, File Manager+ | Not ticked by you |
| 5 Voice + Miracast | `com.google.android.katniss`, `com.creative.fastscreen.tv` | Your choice |
| 6 Updates | `com.apps.ota`, `com.apps.authota`, `com.realsil.ota`, `com.google.android.tv.dfuservice` | Your choice (TV + remote firmware) |

**Kept:** Netflix, Prime Video, YouTube, JioHotstar, FanCode, Sun NXT, ZEE5, SonyLIV, YouTube Music, YouTube Kids, Play Store, Play Services, Chromecast, USB Media Player, Live TV + all input services, source menu, `tvmidware`, remote/BT/key handlers, Gboard, TV Settings.

## 4. Glass Home launcher (`com.madhu.glasslauncher`)

- Sideloads work on this TV, and so does a third-party HOME app (tested with probe APKs first).
- Built without Gradle: Java + Ubuntu `aapt`/`dx`/`apksigner`, framework views only. Google's Maven was blocked from the build machine, so there's no AndroidX/RecyclerView. minSdk/targetSdk 34, `<queries>` for LEANBACK_LAUNCHER.
- **Design:** midnight-blue → black gradient (v1.1; v1.0 was pure black). Greeting, large thin Outfit clock and date at top left. Frosted pills at top right: Inputs, Wi-Fi name, Settings. Dock in a lighter rounded band: **YouTube, Sun NXT, JioHotstar, Netflix**. The focused dock app's name shows large above the dock, with a glow in the banner's colour. One spring-animated glass highlight. The focused tile scales up and shows its name. A 4-column "All apps" grid scrolls up on Down.
- **Navigation:** Up/Down move exactly one row. Up from the dock goes to the pills, and the ends of the pill row don't fall through. Back jumps to the dock. The last focused tile is remembered. Long-press OK opens app info.
- **Inputs dropdown:** HDMI 1–3, AV, Live TV, switched through the TV input framework (passthrough URIs).
- **Light:** no blur, video or polling. About 51 MB PSS.
- **Config without rebuilding:** `dock.txt` and `wallpaper.jpg` in `/sdcard/Android/data/com.madhu.glasslauncher/files/` (tested).

## 5. Making it the home screen

- `cmd package set-home-activity` gave Glass Home the HOME role, but the stock launcher still won until it was disabled.
- With the stock launcher disabled, the setup wizard (`setupwraith/.RecoveryActivity`, HOME priority 1) took over and looped. ADB can't disable a single component on Android 14, so with your OK the **whole setup wizard** was disabled.
- Disabled for this: `com.google.android.apps.tv.launcherx`, `com.google.android.tungsten.setupwraith`. HOME now resolves to Glass Home, with Settings' `FallbackHome` as a safety net.
- Location permission was granted so the Wi-Fi pill can show the network name.

## 6. Checked on the TV (screenshots in `shots/`)

Each batch screenshot; source menu; HDMI switch; first launcher render; HOME from Settings; all D-pad edge cases; the Inputs dropdown → HDMI 1; app launch (YouTube Music) → HOME returns to the same tile; dock.txt override; gradient on the dock and grid.

## 7. Not done / known issues

- **Prime Video button → Sun NXT: not possible without root.** The remote sends scancode 763, which becomes vendor keycode 2055. `com.realtek.android.globalkeyhandler` launches Prime by a hard-coded package name. You declined the "detect Prime opening, then open Sun NXT on top" workaround.
- **Wi-Fi dropped once** during the home switch and came back when Wi-Fi settings were opened. The cause wasn't found. If it repeats, run `restore.sh`.
- The `input keyevent 178` (TV_INPUT) injection doesn't open the source menu on this set. The physical button wasn't tested.

## 8. Files

| File | Use |
|---|---|
| `restore.sh [IP] [--remove-launcher]` | Re-enable everything in `disabled-packages.txt` (31 entries), Google TV home back |
| `install.sh [IP] [--apk FILE]` | Build and install Glass Home and make it home |
| `tvadb.py` | ADB helper used by both |
| `disabled-packages.txt` | Exact list of disabled packages |
| `README.md` | How to re-enable one app, change the dock/wallpaper, rebuild |
| `glass-launcher/` | Source, `build.sh`, `debug.jks` (**keep it**: updates must use the same key), APKs 1.0/1.1 |
| `backup/`, `shots/` | Pre-change backup; all screenshots |
| `runner.sh`/`runner.py` | Setup-time job runner (not needed day to day) |

## 9. Reminders

- Turn **USB debugging off** when done (Settings → System → Developer options). Turn it on again to run any script.
- A factory reset brings back the stock launcher, the setup wizard and every disabled app.
- Nothing is plugged into the TV's USB ports, so the USB power cut some sets apply while debugging is on doesn't matter here.

## 10. v1.2 update (23 Oct 2026): top bar + widgets

- **Pinned top bar:** pills on the left (Inputs, Wi-Fi, **Bluetooth**, Settings), **RAM gauge** at the far right (display only). The clock block moved down.
- **Widgets on the right:** Weather (Open-Meteo, location from `widgets.txt`, hourly), Nifty 50 (Yahoo unofficial, 60 s in market hours), Screen time (7-day daily average, once a day), Month calendar.
- **Dropped by choice:** power draw (no meter on this TV), network speed, CPU, GPU.
- **Screen time:** Android's usage history on this TV is unusable (buckets dated March 2027), so Glass Home logs screen-on/off itself, starting 23 Oct. The average fills in over the first week.
- **Verified on the TV:** layout, weather, Nifty, top-bar navigation ends, Inputs dropdown (now opaque), grid scrolling under the opaque bar. Memory about 68 MB PSS.
- **Runner fix:** it no longer crashes if a queued job file disappears.

## 11. v1.3 polish (23 Oct 2026)

- **Top bar:** no slab or divider line; pills float on the gradient and a soft fade appears only while the grid scrolls under them.
- **Left view:** app-name title removed (the name already shows under the dock tile). Greeting, a larger clock and the date are centred in the left area.
- **Calendar:** starts on Sunday, weekdays in bold, Saturday/Sunday in grey.
- **Bluetooth pill:** opens Remotes & Accessories (Bluetooth switch). Google TV 14 has no Bluetooth activity; it's the settings slice `content://com.android.tv.settings.accessories.sliceprovider/general`.
- **Data limits:** weather and Nifty keep one overwritten entry and drop it after 24 h; Nifty is stored compactly. Screen time keeps today + 7 days.
- **RAM 1.6/2.0 GB checked:** about 656 MB is reserved by the chip for video/graphics (CMA). Apps you've closed stay cached (e.g. YouTube about 295 MB) and are freed when needed. Real headroom is about 400 MB. Normal for a 2 GB Android TV.
- The disabled OTA apps were still running from before we disabled them (the TV hasn't restarted since 18 Oct). They won't start after the next restart.
- Cleaned the leftover temporary files in `/data/local/tmp` on the TV.

## 12. v1.4 (23 Oct 2026): clock format + audio output pill

- **Clock:** left-aligned again (vertically centred), 12-hour with a leading zero (`07:53`) and a small AM/PM on the baseline. 24-hour mode shows `HH:mm` with no AM/PM.
- **Bluetooth pill removed.** Both of the TV's Bluetooth settings screens require `WRITE_SECURE_SETTINGS`. (Corrected: my earlier v1.3 "works" check had seen a page left open by my own ADB test.)
- **Audio output pill:** shows TV speaker / Optical (S/PDIF) / HDMI ARC (only when active). It switches via Realtek's exported `OutputDeviceReceiver` toggle, with no extra permission. Verified on the TV: Optical → TV speaker → Optical, with `setSpeakerOutput` in the log. Optical can't detect whether a speaker is connected or on, since the link is one-way.
- `BLUETOOTH_CONNECT` permission removed from the app and from `install.sh`. The TV was set back to TV speaker after testing.

## 13. Black home screen after power cut: cause and fix (23 Oct 2026)

- **Symptom:** after a power-socket switch-off, the home screen was black. Remote volume, HDMI and YouTube keys still worked.
- **Cause (from the TV's own code):** Acer/Realtek's `FallbackHome.SetPmodServiceJob` → `disableOrEnableApk(1)` re-enables 60 hard-coded packages on every boot. It re-enabled 20 of ours, including the setup wizard, whose HOME screen (priority 1) beats Glass Home (third-party apps are capped at priority 0). The wizard then looped trying to restore the disabled stock launcher. It hadn't shown before because the TV didn't reboot between 19 Oct and 23 Oct.
- **Fix (chosen: A+):** those 20 apps are now **hidden for user 0** (`pm uninstall -k --user 0`, listed in `hidden-packages.txt`), so the boot job skips them. This is an agreed exception to the original "never uninstall system packages" rule. It's reversible with `cmd package install-existing` / `restore.sh` / factory reset.
- **Verified:** HOME resolves only to Glass Home (plus the Settings fallback). Source menu, HDMI switching and widgets all work.
- **Also seen:** the TV's Settings app crashed once while joining Wi-Fi (a Google/Realtek bug, unrelated to our changes). During the outage the TV was on the iPhone hotspot, and the runner used its IPv6 address.
