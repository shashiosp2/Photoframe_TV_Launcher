# Acer 50" Google TV: full project history

Owner: Madhu · TV: Acer R4G (MOKA / Realtek), Google TV, Android 14 (SDK 34), 2 GB RAM, 32-bit ARM
Launcher: **Glass Home** (`com.madhu.glasslauncher`), current **v1.4**
Companion docs: `README.md` (how-tos), `SUMMARY.md` (state by topic). This file is the chronological log.

---

## Timeline

### Mon 19 Oct 2026: setup, debloat, launcher v1.0–v1.1

| Time (IST) | What happened |
|---|---|
| 21:27 | Request: debloat the TV, replace its launcher with an Apple-TV-style home, verify every step with screenshots from the TV. |
| 21:30 | Claude's shell on the Mac runs in a sandbox that can't reach the home LAN, and Terminal can't be typed into. Fix: `runner.sh`/`runner.py`, a job queue you start in Terminal. It runs only ADB steps (Python `adb-shell` in `.venv`, base64 pushes with md5 check). TV on port 5555. "Allow USB debugging" accepted. |
| 21:35 | **Backup** to `backup/`: package lists, getprop, all settings, default home, TV inputs. |
| 21:37 | Sideload test: plain and HOME-category probe APKs install fine. The launcher is possible on this TV. |
| 21:40 | `dumpsys package` read for 44 vague manufacturer packages, then triaged (telemetry / ads / background / keep). |
| 21:45 | Your choices: keep Netflix, Prime, YouTube, JioHotstar, FanCode, Sun NXT, ZEE5, SonyLIV, YT Music, YT Kids, Play Store, Chromecast. Turn off Assistant, Miracast, updaters, unused apps and preloads. Dock: YouTube, Sun NXT, JioHotstar, Netflix. |
| 21:38–21:46 | **Debloat:** 29 packages disabled in 6 batches with a screenshot after each. Source menu and HDMI checked. `input keyevent 178` doesn't open sources on this set; `VIEW_INPUTS` does. |
| 21:50 | Launcher built without Gradle (Ubuntu aapt/dx/apksigner, framework views only; Google Maven blocked, so no AndroidX). Outfit font (OFL). |
| 21:52 | Installed and given the HOME role, but the stock launcher still won (priority 2). |
| 21:55 | Disabling the stock launcher: the setup wizard's `RecoveryActivity` took HOME and looped. Re-enabled the stock launcher. ADB can't disable a single component on Android 14. **Decision: disable the whole setup wizard.** |
| 21:58 | Wi-Fi dropped once mid-step (cause not found); recovered when Wi-Fi settings were opened. |
| 22:03 | Glass Home is HOME. All D-pad edges, Inputs dropdown → HDMI 1, app launch, remember-last-tile and the `dock.txt` override verified. |
| 22:14 | Asked: remap the remote's Prime button to Sun NXT. **Not possible without root.** Scancode 763 becomes vendor keycode 2055, and Realtek's global key handler launches Prime by a hard-coded name. Detect-and-redirect workaround declined. |
| 22:22 | **v1.1:** midnight-blue → black gradient background. |
| 22:3x | `restore.sh`, `install.sh`, `tvadb.py`, README, SUMMARY delivered. |

### Fri 23 Oct 2026: widgets (v1.2–v1.4), then the black-screen incident

| Time (IST) | What happened |
|---|---|
| 07:08 | Asked for a Bluetooth pill plus widgets (weather, screen time, Nifty, calendar, system stats). Plan agreed: pinned top bar, widgets on the right, RAM gauge far right. Power draw, network speed, CPU and GPU dropped. |
| 07:20 | Runner crashed (a queued job file was moved away mid-job). Patched so a missing job file can't crash it. |
| 07:24 | Android's usage history on this TV is unusable: its records are dated **March 2027** after a clock glitch. Screen time switched to Glass Home's own screen-on/off log. |
| 07:30 | **v1.2:** top bar (Inputs, Wi-Fi, Bluetooth, Settings, RAM) and widgets: weather (Open-Meteo, hourly), Nifty 50 (Yahoo unofficial, 60 s in market hours), screen time (7-day average), calendar. Dropdown and bar made opaque. |
| 07:41 | Questions: data retention, RAM 1.6/2.0 GB, top-bar look, left layout, calendar, Bluetooth. |
| 07:45 | **RAM explained:** ~656 MB reserved by the chip for video/graphics (CMA), apps you've closed kept in memory (YouTube ~295 MB), ~400 MB actually free. Normal. |
| 07:47 | **v1.3:** caches limited (weather/Nifty: one entry, dropped after 24 h; screen time: 7 days). Top bar floats with a fade only while scrolling. App-name title removed, clock centred. Calendar Sunday-first, bold weekdays, grey weekends. Temp files on the TV cleaned. |
| 07:53 | **v1.4:** clock left-aligned again, `hh:mm` with a small AM/PM. |
| 07:55 | Bluetooth pill error. Both of the TV's Bluetooth settings screens need `WRITE_SECURE_SETTINGS`. (Correction: Claude's earlier "it works" check had seen a page opened by its own ADB test.) **Bluetooth pill removed**, as you asked. |
| 08:03 | **Audio output pill** added. Optical can't be detected (it's one-way). Switching works without extra permissions through Realtek's own `OutputDeviceReceiver` toggle. Verified Optical ↔ TV speaker, then left on TV speaker. |
| ~09:xx | **Power cut at the socket → black home screen.** Remote volume, HDMI and YouTube keys still worked. |
| ~09:55 | The home router lost internet. The Mac moved to the iPhone hotspot, so the TV was unreachable. |
| 10:07 | The TV joined the iPhone hotspot; the runner connected over IPv6. The TV's Settings app crashed once while joining Wi-Fi (its own bug). |
| 10:12 | **Root cause found in the TV's own code:** Acer's `FallbackHome.SetPmodServiceJob` → `disableOrEnableApk(1)` re-enables 60 hard-coded packages on **every boot** (unless the factory `P_MODE` is set). It re-enabled 20 of ours, including the setup wizard, whose HOME screen (priority 1) beats Glass Home (third-party apps are capped at 0), so it looped. It hadn't shown before because there was no reboot between 19 and 23 Oct. |
| 10:25 | **Fix A+ (agreed):** the 20 listed apps are **hidden for user 0** (`pm uninstall -k --user 0`, see `hidden-packages.txt`) instead of disabled, so the boot job skips them. This is an exception to the original "never uninstall" rule, and it's reversible. |
| 10:30 | Source menu, HDMI 1 and widgets verified. `restore.sh` and `install.sh` updated for hidden apps. |
| 10:38 | **Real power-socket test passed:** Glass Home came up by itself, 0 hidden apps came back, 11 disabled apps stayed off, and the wizard never started. |

---

## Final state (23 Oct 2026, 10:38)

| Item | State |
|---|---|
| Home | Glass Home v1.4 (only HOME candidates: Glass Home + Settings fallback) |
| Hidden for user 0 (20) | setupwraith, katniss, apps.ota, apps.authota, realsil.ota, tv.dfuservice, apps.tv.dreamx, videos, play.games, android.feedback, apps.biz.mt, mk.tv.meeting.service, function.audiolink, mk.tv.timeclock, apps.tvmanager, apps.esticker, apps.esticker3, function.gdpr, apps.scast, creative.fastscreen.tv |
| Disabled (11, not on Acer's list, so they stay off) | stock launcher (launcherx), autoinstalls stub, File Manager+, feedbackconsent, S-Channel (mk.wd), wifihotspot, JioCinema, mk.tv.systemserver, MX Player, partnercustomizer, YuppTV |
| Kept | Live TV and all inputs, source menu, tvmidware, remote/key handlers, Gboard, Play Store/Services, TV Settings, Chromecast, USB Media Player, all chosen streaming apps |
| Network | TV currently on the iPhone hotspot. Switch back to home Wi-Fi when the router has internet. |
| Permissions given to Glass Home | Location (Wi-Fi name only). INTERNET is normal. No Bluetooth, no usage access, no secure-settings grant. |

## Glass Home versions

| Version | Change |
|---|---|
| 1.0 | Black Apple-TV-style home: dock, 4-column grid, spring glass highlight, glow from banner colour, Inputs dropdown |
| 1.1 | Midnight-blue → black gradient |
| 1.2 | Pinned top bar, Bluetooth pill, RAM gauge, weather / Nifty / screen-time / calendar widgets |
| 1.3 | Data limits, floating top bar, centred clock, no app-name title, Sunday-first bold calendar |
| 1.4 | `hh:mm` + small AM/PM, left clock; Bluetooth pill replaced by the audio output pill |

## Things that are impossible or limited on this TV (without root)

| Wanted | Why not |
|---|---|
| Remap the Prime button | Hard-wired in Realtek's global key handler (keycode 2055) |
| Disable one component (e.g. only the wizard's HOME screen) | Android 14 blocks component-state changes from ADB |
| Open the Bluetooth page from an app | Its settings screen requires `WRITE_SECURE_SETTINGS` |
| Detect a speaker on optical | S/PDIF is one-way |
| Power draw, GPU load, system usage history | Not exposed (no power meter; GPU files blocked; usage history dated 2027) |
| Stop Acer's boot job | Controlled by the factory `P_MODE` flag |

## Lessons learned

- **Always test with a real reboot** after disabling system apps. Acer's boot job silently undid 20 of 29 after the first power cut.
- On this TV, disabling the stock launcher plus the setup wizard is only safe if the wizard is **hidden**, not disabled.
- When verifying, start from a clean state. A page left open by an earlier ADB command can fake a "pass".
- `pm list packages` without `--user 0` also lists apps installed for the second, unused profile (user 10).
- Files committed over existing names on the Mac can come out stale; use new file names, then `mv`.

## How to continue later

1. Turn **USB debugging** on (Settings → System → Developer options).
2. `bash runner.sh <TV_IP>` (IPv6 is fine). Accept the prompt on the TV.
3. Point Claude at this file plus `README.md`/`SUMMARY.md`.
4. Undo everything: `bash restore.sh <TV_IP>` (`--remove-launcher` also uninstalls Glass Home).
5. When done: Ctrl-C the runner and turn USB debugging off.
