package com.madhu.glasslauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * Background watchdog that detects user inactivity.
 * If no key/UI interaction occurs and no video/audio is playing for 5 minutes,
 * it returns the TV to the Home launcher so the ambient photo screensaver can trigger.
 */
public class InactivityWatchdogService extends AccessibilityService {
    private static final String TAG = "Watchdog";
    private static final long DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L; // 5 minutes
    private static final long CHECK_INTERVAL_MS = 15 * 1000L; // 15 seconds
    private static final long PERIODIC_SYNC_INTERVAL_MS = 60 * 60 * 1000L; // 1 hour
    private long lastSyncTime = 0;

    private static InactivityWatchdogService instance;

    private long lastInteractionTime = SystemClock.uptimeMillis();
    private String currentPackage = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            checkInactivity();
            checkPeriodicSync();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        lastInteractionTime = SystemClock.uptimeMillis();
        handler.removeCallbacks(watchdogRunnable);
        handler.postDelayed(watchdogRunnable, CHECK_INTERVAL_MS);
        Log.i(TAG, "InactivityWatchdogService active. Timeout: 5 min.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        lastInteractionTime = SystemClock.uptimeMillis();
        if (event.getPackageName() != null) {
            currentPackage = event.getPackageName().toString();
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        lastInteractionTime = SystemClock.uptimeMillis();
        return super.onKeyEvent(event);
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "Watchdog interrupted");
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacks(watchdogRunnable);
        super.onDestroy();
    }

    public static boolean sleepDevice() {
        if (instance != null) {
            Log.i(TAG, "Executing GLOBAL_ACTION_LOCK_SCREEN to put device into standby");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                boolean ok = instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                Log.i(TAG, "GLOBAL_ACTION_LOCK_SCREEN returned " + ok);
                if (ok) return true;
            }
            return instance.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        }
        Log.w(TAG, "Watchdog instance is null, cannot performGlobalAction");
        return false;
    }

    private void checkInactivity() {
        // Read dynamic configuration
        boolean enabled = false;
        long timeoutMs = DEFAULT_TIMEOUT_MS;

        java.io.File cfg = new java.io.File("/sdcard/.launcherconfig");
        if (!cfg.exists()) {
            java.io.File fallback = new java.io.File(getExternalFilesDir(null), ".launcherconfig");
            if (fallback.exists()) cfg = fallback;
        }

        if (cfg.exists() && cfg.canRead()) {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(cfg));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String key = line.substring(0, eq).trim().toLowerCase();
                    String val = line.substring(eq + 1).trim();
                    if (key.equals("screensaver_watchdog") || key.equals("inactivity_watchdog")
                            || key.equals("idle_return_home") || key.equals("watchdog_enabled")) {
                        enabled = val.equalsIgnoreCase("true") || val.equals("1");
                    } else if (key.equals("inactivity_timeout") || key.equals("timeout")) {
                        try {
                            int sec = Integer.parseInt(val);
                            if (sec > 0) timeoutMs = sec * 1000L;
                        } catch (Exception ignored) {}
                    }
                }
                r.close();
            } catch (Exception ignored) {}
        } else {
            // Fallback to SharedPreferences if file not readable
            android.content.SharedPreferences sp = getSharedPreferences("state", MODE_PRIVATE);
            enabled = sp.getBoolean("screensaver_watchdog", false);
            int sec = sp.getInt("watchdog_timeout", 300);
            if (sec > 0) timeoutMs = sec * 1000L;
        }

        // If not explicitly "true", do not return to Home
        if (!enabled) {
            return;
        }

        long idleTime = SystemClock.uptimeMillis() - lastInteractionTime;
        if (idleTime < timeoutMs) {
            return;
        }

        // Check media playback state (audio/video playing)
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean isMediaPlaying = false;
        if (am != null) {
            if (am.isMusicActive()) {
                isMediaPlaying = true;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                List<AudioPlaybackConfiguration> configs = am.getActivePlaybackConfigurations();
                if (configs != null && !configs.isEmpty()) {
                    isMediaPlaying = true;
                }
            }
        }

        if (isMediaPlaying) {
            // Video or music is currently playing, don't interrupt the user
            lastInteractionTime = SystemClock.uptimeMillis();
            return;
        }

        // Check if we are already on the launcher or daydream screensaver
        if ("com.madhu.glasslauncher".equals(currentPackage)
                || "com.google.android.backdrop".equals(currentPackage)) {
            return;
        }

        Log.i(TAG, "No video playback and idle for " + (timeoutMs / 1000) + "s. Returning to Home.");
        performGlobalAction(GLOBAL_ACTION_HOME);
        lastInteractionTime = SystemClock.uptimeMillis();
    }

    private void checkPeriodicSync() {
        long now = System.currentTimeMillis();
        if (lastSyncTime == 0) {
            lastSyncTime = now;
            return;
        }

        if (now - lastSyncTime < PERIODIC_SYNC_INTERVAL_MS) {
            return;
        }

        // If media is currently active across the system, defer the 1-hour crawl
        if (PhotoDatabase.isMediaActive(this)) {
            Log.i(TAG, "Media active during 1-hour periodic sync interval. Deferring crawl.");
            return;
        }

        // Read WebDAV settings from /sdcard/.launcherconfig
        String url = "";
        String folder = "";
        String user = "";
        String pass = "";
        boolean recursive = true;

        try {
            java.io.File cf = new java.io.File(android.os.Environment.getExternalStorageDirectory(), ".launcherconfig");
            if (cf.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(cf));
                String l;
                while ((l = br.readLine()) != null) {
                    l = l.trim();
                    if (l.startsWith("#") || !l.contains("=")) continue;
                    int eq = l.indexOf('=');
                    String k = l.substring(0, eq).trim();
                    String v = l.substring(eq + 1).trim();
                    if ("webdav_url".equals(k)) url = v;
                    else if ("webdav_folder".equals(k)) folder = v;
                    else if ("webdav_user".equals(k)) user = v;
                    else if ("webdav_password".equals(k)) pass = v;
                    else if ("webdav_recursive".equals(k)) recursive = !"false".equalsIgnoreCase(v);
                }
                br.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not load .launcherconfig for periodic crawl", t);
        }

        if (url.isEmpty() || folder.isEmpty()) {
            lastSyncTime = now;
            return;
        }

        lastSyncTime = now;
        Log.i(TAG, "Starting 1-hour periodic WebDAV crawl (active or standby)...");
        WebdavCrawler.crawlAsync(this, url, folder, user, pass, recursive, new WebdavCrawler.CrawlCallback() {
            @Override
            public void onCrawlComplete(List<String> photos) {
                if (photos != null && !photos.isEmpty()) {
                    PhotoDatabase.getInstance().syncPhotos(photos);
                    Log.i(TAG, "Periodic 1-hour crawl complete. Synced " + photos.size() + " photos to /sdcard/.launcher.db");
                }
            }
        });
    }
}
