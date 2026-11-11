# Photoframe TV Launcher

A lightweight, minimalist Apple TV-inspired ambient photo frame launcher for Android TV and Google TV (specifically optimized for NVIDIA SHIELD Android TV and generic Android TV devices).

Built directly with the native Android toolchain (no Gradle overhead, no AndroidX bulk, ~30–50 MB PSS memory footprint).

![Photoframe TV Launcher](docs/glass-home.png)

---

## Key Features

- **Apple TV-Style Single-Row Interface**:
  - Clean horizontal app shelf featuring smooth D-pad horizontal navigation.
  - Custom rounded icon tiles with crisp 2.0 dp high-contrast focus outline (no distracting radial glows).
  - Quick action buttons (e.g. Gemini Assistant trigger, Standby/Sleep button).
- **WebDAV Ambient Photo Frame Slideshow**:
  - Connects directly to local or remote WebDAV shares (Nextcloud, OwnCloud, Synology, rclone, etc.).
  - High-performance recursive socket crawler (`PROPFIND` Depth: 1) traversing complex directory trees.
  - Automatic EXIF orientation detection and rotation (90°, 180°, 270°).
  - Ambient diffuse background blur/glow behind letterboxed/un-cropped photos to preserve aspect ratios while filling 16:9 widescreen displays.
- **Zero-Heap SQLite Indexing (`/sdcard/.launcher.db`)**:
  - Caches file lists in a persistent local SQLite database.
  - Least-shown rotation algorithm prevents duplicate repeats until the full photo collection has cycled.
  - Cycle detection prevents unbounded recursion on symbolic links or directory loops.
  - Zero-heap overhead keeps memory consumption minimal on RAM-constrained streaming devices.
- **Intelligent Inactivity & Ambient Mode**:
  - **Auto-Hiding App Shelf**: When no D-pad activity is detected after a configurable timeout (default: 15s), the app row smoothly slides down out of view, and the status clock repositions to the bottom edge.
  - **Instant Recall**: Touching any D-pad key immediately slides the app shelf and clock back into active view.
  - **Inactivity Watchdog Service**: Android Accessibility Service monitors global inactivity across all apps and automatically returns to the ambient launcher home after idle periods.
  - **Standby Screen Lock**: Built-in black standby screen lock with zero-light output and wake-on-keypress.
- **Dynamic Live Hot-Reloading (`/sdcard/.launcherconfig`)**:
  - Monitored via Linux `FileObserver` in real-time. Change your WebDAV path, slideshow interval, or clock timezones without restarting the launcher.
- **Playback Awareness**:
  - Background indexing pauses automatically whenever `AudioManager` detects active media playback, eliminating stutter during 4K streaming.

---

## Configuration (`/sdcard/.launcherconfig`)

The launcher reads configuration from `/sdcard/.launcherconfig`. When edited or pushed via ADB, changes apply immediately without restarting.

### Sample Configuration

```ini
# WebDAV Slideshow Source
webdav_url=http://192.168.1.100:8080/remote.php/webdav/Photos/
webdav_username=myuser
webdav_password=mypassword

# Slideshow interval in seconds
slideshow_interval_sec=30

# Inactivity auto-hide timeout for app dock (in seconds)
inactivity_timeout_sec=15

# Global TV inactivity watchdog timeout before returning to launcher (in minutes)
screensaver_timeout_min=10

# Dual Clocks / Timezones
local_tz_label=Local
local_tz_id=America/Chicago
secondary_tz_label=UTC
secondary_tz_id=UTC

# Dock Apps (package names to pin on the main shelf)
pinned_apps=com.google.android.youtube.tv,com.netflix.ninja,org.xbmc.kodi,com.plexapp.android
```

Push changes to the TV anytime:
```bash
adb push .launcherconfig /sdcard/.launcherconfig
```

---

## Building from Source

The project uses a pure Android CLI toolchain (`aapt`, `d8`, `apksigner`, `javac`) requiring only JDK 17+ and the Android SDK command-line tools.

### Prerequisites

- JDK 17 or higher
- Android SDK Build Tools (API 30+)
- `ANDROID_HOME` or `ANDROID_SDK_ROOT` set in your environment

### Build Command

```bash
cd glass-launcher
bash build.sh
```

The resulting signed APK will be generated at `glass-launcher/GlassHome.apk`.

---

## Installation via ADB

1. Enable **Developer Options** and **Network Debugging** on your Android TV device.
2. Connect to the TV:
   ```bash
   adb connect <TV_IP>:5555
   ```
3. Install the launcher APK:
   ```bash
   adb install -r glass-launcher/GlassHome.apk
   ```
4. Push your initial configuration file:
   ```bash
   adb push sample.launcherconfig /sdcard/.launcherconfig
   ```
5. *(Optional)* Enable the Inactivity Watchdog Accessibility Service:
   ```bash
   adb shell settings put secure enabled_accessibility_services com.madhu.glasslauncher/.InactivityWatchdogService
   adb shell settings put secure accessibility_enabled 1
   ```
6. Set Glass Home as default launcher:
   Press the **Home** button on your remote and select **Glass Home** -> **Always**.

---

## Architecture Overview

```
glass-launcher/
├── AndroidManifest.xml                    # App manifests, permissions & accessibility service declarations
├── build.sh                               # Pure CLI build script (javac -> d8 -> aapt -> apksigner)
├── strip_params.py                        # Helper to strip method parameter metadata for older runtimes
├── assets/
│   └── default_wallpaper.jpg              # Bundled fallback wallpaper
├── res/
│   ├── layout/                            # Layout XMLs (single row, focus indicators, overlays)
│   ├── xml/
│   │   └── accessibility_service_config.xml # Watchdog accessibility configuration
│   └── drawable/                          # Vector drawables & focus selectors
└── src/com/madhu/glasslauncher/
    ├── HomeActivity.java                  # Main launcher UI, auto-hide animator, FileObserver
    ├── InactivityWatchdogService.java     # System accessibility listener, standby lock screen
    ├── PhotoDatabase.java                 # SQLite indexer (/sdcard/.launcher.db)
    ├── WebdavCrawler.java                 # Socket-level HTTP/1.1 WebDAV recursive parser
    └── SettingsActivity.java              # Configuration settings UI
```

---

## License

MIT License. Feel free to modify and customize for your own TV setup.
