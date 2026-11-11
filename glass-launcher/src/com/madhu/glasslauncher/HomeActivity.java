package com.madhu.glasslauncher;

import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.database.ContentObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glass Home: Apple-TV-inspired launcher for Android TV.
 * Dual Timezones (CST on left, IST on right), Sleep & Gemini/Assistant action buttons,
 * single row for all apps with horizontal gliding, bottom control pills (Inputs, Wi-Fi, Audio, Settings),
 * and dynamic on-screen app reordering.
 */
public class HomeActivity extends Activity {

    static final String[] DEFAULT_DOCK = {
            "org.smarttube.stable",
            "com.amazon.amazonvideo.livingroom",
            "com.google.android.youtube.tv",
            "org.videolan.vlc"
    };

    static final HashSet<String> HIDDEN = new HashSet<String>();
    static {
        HIDDEN.add("com.madhu.glasslauncher");
        HIDDEN.add("com.minimal.appletvlauncher");
        HIDDEN.add("com.google.android.tvlauncher");
        HIDDEN.add("com.google.android.tv");
    }

    // geometry in dp (960 x 540 dp screen)
    static final float SIDE = 48;
    static final float ROW_H = 44;
    static final float ROW_Y = 466;
    static final float TIME_Y = 432;
    static final float ACTION_W_GEMINI = 96;
    static final float ACTION_W_SLEEP = 86;
    static final float APP_TILE_W = 78;
    static final float GAP = 12;
    static final float SCALE = 1.08f;

    static final int TYPE_GEMINI = 0, TYPE_SLEEP = 1, TYPE_APP = 2;

    float dp;
    Typeface fThin, fLight, fReg, fMed, fBold;
    PackageManager pm;
    SharedPreferences prefs;
    Handler handler = new Handler();

    FrameLayout root, content, overlay, shelfContainer;
    LinearLayout timeRow;
    View glass;
    TextView cstTag, istTag, deviceNameView;
    View deviceSpacer;
    TextClock clockCst, clockIst;

    boolean shelfAutohide = true;
    int shelfHideTimeout = 8; // seconds
    boolean isShelfHidden = false;

    String deviceName = "NVIDIA AndroidTV";
    String tzLeft = "America/Chicago";
    String tzLeftTag = "CST";
    String tzRight = "Asia/Kolkata";
    String tzRightTag = "IST";
    int watchdogTimeout = 300;

    // WebDAV / Photo Settings
    String webdavUrl = "";
    String webdavFolder = "";
    String webdavUser = "";
    String webdavPass = "";
    int webdavRefreshInterval = 15;
    boolean webdavRecursive = true;
    boolean wallpaperGlowEnabled = true;

    final Runnable shelfHideRunnable = new Runnable() {
        @Override
        public void run() {
            hideShelf(true);
        }
    };

    void resetShelfHideTimer() {
        handler.removeCallbacks(shelfHideRunnable);
        if (shelfAutohide && shelfHideTimeout > 0 && visible) {
            handler.postDelayed(shelfHideRunnable, shelfHideTimeout * 1000L);
        }
    }

    void hideShelf(boolean animate) {
        if (!shelfAutohide || isShelfHidden || !visible || reorderMode) return;
        isShelfHidden = true;

        if (shelfContainer != null) {
            shelfContainer.animate()
                    .translationY(px(80))
                    .alpha(0f)
                    .setDuration(animate ? 400 : 0)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        if (glass != null) {
            glass.animate()
                    .alpha(0f)
                    .setDuration(animate ? 250 : 0)
                    .start();
        }

        if (timeRow != null) {
            // Translate timeRow down by 54dp so it rests right near the bottom screen edge
            timeRow.animate()
                    .translationY(px(54))
                    .setDuration(animate ? 400 : 0)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    void showShelf(boolean animate) {
        resetShelfHideTimer();
        if (!isShelfHidden) return;
        isShelfHidden = false;

        if (shelfContainer != null) {
            shelfContainer.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(animate ? 300 : 0)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        if (glass != null) {
            glass.animate()
                    .alpha(1f)
                    .setDuration(animate ? 300 : 0)
                    .start();
        }

        if (timeRow != null) {
            timeRow.animate()
                    .translationY(0f)
                    .setDuration(animate ? 300 : 0)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }
    // Photo & Glow Views
    ImageView glowA, glowB;
    ImageView photoA, photoB;
    boolean isBufferAActive = true;
    final ExecutorService photoExecutor = Executors.newSingleThreadExecutor();
    boolean isCrawling = false;
    final Random rng = new Random();
    android.os.FileObserver configObserver;

    // Vibrant Glow Palette
    static final int[] GLOW_COLORS = {
            0xFF0EA5E9, // Sky Blue
            0xFF6366F1, // Indigo
            0xFF8B5CF6, // Purple
            0xFFEC4899, // Pink
            0xFFF43F5E, // Rose
            0xFFF59E0B, // Amber
            0xFF10B981, // Emerald
            0xFF06B6D4, // Cyan
            0xFF84CC16, // Lime
            0xFFD946EF  // Fuchsia
    };

    final Runnable photoRotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (visible && webdavRefreshInterval > 0) {
                rotateToNextPhoto();
                handler.postDelayed(this, webdavRefreshInterval * 1000L);
            }
        }
    };

    static class App { String pkg, label; ResolveInfo ri; Drawable banner; boolean isBanner; }
    static class ShelfItem { int type; App app; FrameLayout card; float x, width; }

    final ArrayList<App> apps = new ArrayList<App>();
    final ArrayList<ShelfItem> shelfItems = new ArrayList<ShelfItem>();

    int shelfIdx = 2; // Always default to first real app (YouTube)
    boolean visible, longPressed, reorderMode;
    float curShelfTransX = 0;
    RectF glassRect = new RectF();
    float glassRadius;
    ValueAnimator moveAnim, scrollAnim;
    ShelfItem scaledItem;

    final Runnable longPressRunnable = new Runnable() {
        public void run() {
            if (shelfIdx >= 2) {
                longPressed = true;
                toggleReorderMode();
            }
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        pm = getPackageManager();
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        fThin = font("outfit_200.ttf"); fLight = font("outfit_300.ttf");
        fReg = font("outfit_400.ttf"); fMed = font("outfit_500.ttf"); fBold = font("outfit_600.ttf");
        shelfIdx = 2; // Always default to first real app

        loadConfigFile();
        buildUi();
        loadApps();
        registerReceivers();
        startConfigWatcher();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (reorderMode) toggleReorderMode();
        updateClockFormats();
        startPhotoRotation();
        resetShelfHideTimer();
        root.post(new Runnable() { public void run() { goToFirstApp(false); } });
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        handler.removeCallbacks(shelfHideRunnable);
        stopPhotoRotation();
        if (reorderMode) toggleReorderMode();
    }

    void updateClockFormats() {
        loadConfigFile();
        if (deviceNameView != null) {
            if (deviceName != null && !deviceName.trim().isEmpty()) {
                deviceNameView.setText(deviceName.trim());
                deviceNameView.setVisibility(View.VISIBLE);
                if (deviceSpacer != null) deviceSpacer.setVisibility(View.VISIBLE);
            } else {
                deviceNameView.setVisibility(View.GONE);
                if (deviceSpacer != null) deviceSpacer.setVisibility(View.GONE);
            }
        }
        boolean is24 = prefs.getBoolean("clock_24h", false);
        String tf = is24 ? "H:mm" : "h:mm a";
        if (cstTag != null) cstTag.setText(tzLeftTag);
        if (clockCst != null) {
            clockCst.setTimeZone(tzLeft);
            clockCst.setFormat12Hour(tf);
            clockCst.setFormat24Hour(tf);
        }
        if (istTag != null) istTag.setText(tzRightTag);
        if (clockIst != null) {
            clockIst.setTimeZone(tzRight);
            clockIst.setFormat12Hour(tf);
            clockIst.setFormat24Hour(tf);
        }
    }

    void startConfigWatcher() {
        try {
            File sdcardDir = android.os.Environment.getExternalStorageDirectory();
            configObserver = new android.os.FileObserver(sdcardDir.getAbsolutePath(),
                    android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.MOVED_TO | android.os.FileObserver.MODIFY) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null && path.equals(".launcherconfig")) {
                        android.util.Log.i("GlassHome", ".launcherconfig updated on disk! Hot-reloading live...");
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                updateClockFormats();
                                startPhotoRotation();
                            }
                        });
                    }
                }
            };
            configObserver.startWatching();
            android.util.Log.i("GlassHome", "Live config FileObserver watching /sdcard/.launcherconfig");
        } catch (Throwable t) {
            android.util.Log.w("GlassHome", "Could not start FileObserver on .launcherconfig", t);
        }
    }

    void loadConfigFile() {
        File cfg = new File("/sdcard/.launcherconfig");
        if (!cfg.exists()) {
            File fallback = new File(getExternalFilesDir(null), ".launcherconfig");
            if (fallback.exists()) {
                cfg = fallback;
            } else {
                createDefaultConfigFile(cfg);
            }
        }
        if (!cfg.exists() || !cfg.canRead()) return;

        try {
            BufferedReader r = new BufferedReader(new FileReader(cfg));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toLowerCase();
                String val = line.substring(eq + 1).trim();
                if (key.equals("device_name") || key.equals("device") || key.equals("name")) {
                    deviceName = val;
                } else if (key.equals("timezone_left") || key.equals("tz_left")) {
                    if (val.length() > 0) tzLeft = val;
                } else if (key.equals("timezone_left_tag") || key.equals("tz_left_tag")) {
                    if (val.length() > 0) tzLeftTag = val;
                } else if (key.equals("timezone_right") || key.equals("tz_right")) {
                    if (val.length() > 0) tzRight = val;
                } else if (key.equals("timezone_right_tag") || key.equals("tz_right_tag")) {
                    if (val.length() > 0) tzRightTag = val;
                } else if (key.equals("clock_24h") || key.equals("is_24h")) {
                    boolean b = val.equalsIgnoreCase("true") || val.equals("1");
                    prefs.edit().putBoolean("clock_24h", b).apply();
                } else if (key.equals("screensaver_watchdog") || key.equals("inactivity_watchdog")
                        || key.equals("idle_return_home") || key.equals("watchdog_enabled")) {
                    boolean b = val.equalsIgnoreCase("true") || val.equals("1");
                    prefs.edit().putBoolean("screensaver_watchdog", b).apply();
                } else if (key.equals("inactivity_timeout") || key.equals("timeout")) {
                    try { watchdogTimeout = Integer.parseInt(val); } catch (Exception e) {}
                    prefs.edit().putInt("watchdog_timeout", watchdogTimeout).apply();
                } else if (key.equals("webdav_url") || key.equals("url")) {
                    webdavUrl = val;
                } else if (key.equals("webdav_folder") || key.equals("folder")) {
                    webdavFolder = val;
                } else if (key.equals("webdav_user") || key.equals("user") || key.equals("username")) {
                    webdavUser = val;
                } else if (key.equals("webdav_password") || key.equals("password") || key.equals("pass")) {
                    webdavPass = val;
                } else if (key.equals("webdav_refresh_interval") || key.equals("refresh_interval") || key.equals("interval")) {
                    try { webdavRefreshInterval = Integer.parseInt(val); } catch (Exception e) {}
                } else if (key.equals("webdav_recursive") || key.equals("recursive")) {
                    webdavRecursive = val.equalsIgnoreCase("true") || val.equals("1");
                } else if (key.equals("wallpaper_glow_enabled") || key.equals("glow_enabled") || key.equals("glow")) {
                    wallpaperGlowEnabled = val.equalsIgnoreCase("true") || val.equals("1");
                } else if (key.equals("shelf_autohide") || key.equals("autohide_shelf") || key.equals("autohide")) {
                    shelfAutohide = !"false".equalsIgnoreCase(val) && !val.equals("0");
                } else if (key.equals("shelf_hide_timeout") || key.equals("hide_timeout")) {
                    try { shelfHideTimeout = Integer.parseInt(val); } catch (Exception e) {}
                }
            }
            r.close();
        } catch (Exception e) {
            android.util.Log.e("GlassHome", "Failed to read .launcherconfig", e);
        }
    }

    void createDefaultConfigFile(File f) {
        try {
            PrintWriter pw = new PrintWriter(f);
            pw.println("# Glass Home Launcher Configuration");
            pw.println("# Modify settings here or push via ADB: adb push .launcherconfig /sdcard/.launcherconfig");
            pw.println("");
            pw.println("# Device Name (displayed to the left of the clocks)");
            pw.println("device_name=NVIDIA AndroidTV");
            pw.println("");
            pw.println("# Left Timezone (default: CST America/Chicago)");
            pw.println("timezone_left=America/Chicago");
            pw.println("timezone_left_tag=CST");
            pw.println("");
            pw.println("# Right Timezone (default: IST Asia/Kolkata)");
            pw.println("timezone_right=Asia/Kolkata");
            pw.println("timezone_right_tag=IST");
            pw.println("");
            pw.println("# Clock 24h format (true or false)");
            pw.println("clock_24h=false");
            pw.println("");
            pw.println("# Return to Home when idle without video to trigger screensaver (true or false)");
            pw.println("screensaver_watchdog=true");
            pw.println("");
            pw.println("# Inactivity timeout in seconds before returning to Home (default: 300 for 5 min)");
            pw.println("inactivity_timeout=300");
            pw.close();
        } catch (Exception e) { }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        updateClockFormats();
        showShelf(true);
        goToFirstApp(true);
    }

    @Override
    protected void onDestroy() {
        if (configObserver != null) {
            try { configObserver.stopWatching(); } catch (Exception e) {}
            configObserver = null;
        }
        try { unregisterReceiver(pkgReceiver); } catch (Exception e) { }
        stopPhotoRotation();
        photoExecutor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (reorderMode) {
            toggleReorderMode();
            return;
        }
        if (shelfIdx != 2) {
            goToFirstApp(true);
        }
    }

    Typeface font(String n) {
        try { return Typeface.createFromAsset(getAssets(), "fonts/" + n); }
        catch (Exception e) { return Typeface.create("sans-serif-light", Typeface.NORMAL); }
    }

    int px(float d) { return Math.round(d * dp); }

    // ------------------------------------------------------------------ UI

    void buildUi() {
        root = new FrameLayout(this);
        GradientDrawable bgGrad = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF05070E, 0xFF020408, 0xFF000000, 0xFF000000});
        root.setBackground(bgGrad);
        root.setClipChildren(false);

        // --- Layer 1 & 2: Dual-Buffer Ambient Glow ---
        glowA = new ImageView(this);
        glowA.setScaleType(ImageView.ScaleType.FIT_XY);
        glowA.setAlpha(0f);
        root.addView(glowA, new FrameLayout.LayoutParams(-1, -1));

        glowB = new ImageView(this);
        glowB.setScaleType(ImageView.ScaleType.FIT_XY);
        glowB.setAlpha(0f);
        root.addView(glowB, new FrameLayout.LayoutParams(-1, -1));

        // --- Layer 3 & 4: Dual-Buffer Uncropped Photos (FIT_CENTER) ---
        photoA = new ImageView(this);
        photoA.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoA.setAlpha(1f);
        root.addView(photoA, new FrameLayout.LayoutParams(-1, -1));

        photoB = new ImageView(this);
        photoB.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoB.setAlpha(0f);
        root.addView(photoB, new FrameLayout.LayoutParams(-1, -1));

        // Initial default wallpaper from bundled assets (wallpaper0001.jpg)
        loadDefaultAssetWallpaper();

        // --- Layer 5: Bottom Vignette Overlay (ensures shelf and clocks stay crisp & legible) ---
        View bottomVignette = new View(this);
        GradientDrawable vig = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x55000000, 0xBB000000, 0xEE000000});
        bottomVignette.setBackground(vig);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-1, px(200));
        vlp.gravity = Gravity.BOTTOM;
        root.addView(bottomVignette, vlp);

        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Dual Timezones: CST and IST side-by-side, IST to the right of CST
        boolean is24 = prefs.getBoolean("clock_24h", false);
        String tf = is24 ? "H:mm" : "h:mm a";

        timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);

        // --- Device Name (to the left of both times) ---
        deviceNameView = text(13.5f, fMed, 0xE6FFFFFF);
        deviceNameView.setText(deviceName != null ? deviceName : "");
        timeRow.addView(deviceNameView);

        deviceSpacer = new View(this);
        LinearLayout.LayoutParams dslp = new LinearLayout.LayoutParams(px(24), 1);
        timeRow.addView(deviceSpacer, dslp);

        if (deviceName == null || deviceName.trim().isEmpty()) {
            deviceNameView.setVisibility(View.GONE);
            deviceSpacer.setVisibility(View.GONE);
        }

        // --- Left Timezone ---
        LinearLayout cstBlock = new LinearLayout(this);
        cstBlock.setOrientation(LinearLayout.HORIZONTAL);
        cstBlock.setGravity(Gravity.CENTER_VERTICAL);

        cstTag = text(12.5f, fBold, 0xCC38BDF8);
        cstTag.setText(tzLeftTag);
        cstBlock.addView(cstTag);

        clockCst = new TextClock(this);
        clockCst.setTimeZone(tzLeft);
        clockCst.setFormat12Hour(tf);
        clockCst.setFormat24Hour(tf);
        styleText(clockCst, 17, fMed, 0xFFFFFFFF);
        LinearLayout.LayoutParams clp1 = new LinearLayout.LayoutParams(-2, -2);
        clp1.leftMargin = px(8);
        cstBlock.addView(clockCst, clp1);

        TextClock dateCst = new TextClock(this);
        dateCst.setTimeZone(tzLeft);
        dateCst.setFormat12Hour("·  EEE, d MMM");
        dateCst.setFormat24Hour("·  EEE, d MMM");
        styleText(dateCst, 12.5f, fLight, 0x99FFFFFF);
        LinearLayout.LayoutParams dlp1 = new LinearLayout.LayoutParams(-2, -2);
        dlp1.leftMargin = px(8);
        cstBlock.addView(dateCst, dlp1);

        timeRow.addView(cstBlock);

        // --- Divider Spacer ---
        View spacer = new View(this);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(px(24), 1);
        timeRow.addView(spacer, splp);

        // --- Right Timezone (to the right of Left Timezone) ---
        LinearLayout istBlock = new LinearLayout(this);
        istBlock.setOrientation(LinearLayout.HORIZONTAL);
        istBlock.setGravity(Gravity.CENTER_VERTICAL);

        istTag = text(12.5f, fBold, 0xCC34D399);
        istTag.setText(tzRightTag);
        istBlock.addView(istTag);

        clockIst = new TextClock(this);
        clockIst.setTimeZone(tzRight);
        clockIst.setFormat12Hour(tf);
        clockIst.setFormat24Hour(tf);
        styleText(clockIst, 17, fMed, 0xFFFFFFFF);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(-2, -2);
        clp2.leftMargin = px(8);
        istBlock.addView(clockIst, clp2);

        TextClock dateIst = new TextClock(this);
        dateIst.setTimeZone(tzRight);
        dateIst.setFormat12Hour("·  EEE, d MMM");
        dateIst.setFormat24Hour("·  EEE, d MMM");
        styleText(dateIst, 12.5f, fLight, 0x99FFFFFF);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(-2, -2);
        dlp2.leftMargin = px(8);
        istBlock.addView(dateIst, dlp2);

        timeRow.addView(istBlock);

        // Add timeRow docked at bottom of screen
        content.addView(timeRow, lpWrap(SIDE, TIME_Y));

        // Horizontally scrolling row container for ALL shelf items (Gemini, Sleep, Apps)
        shelfContainer = new FrameLayout(this);
        shelfContainer.setClipChildren(false);
        content.addView(shelfContainer, lpWrap(0, ROW_Y));

        // Overlay layer: selection outline
        overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        glass = new View(this);
        glass.setBackground(new Glass());
        overlay.addView(glass, new FrameLayout.LayoutParams(1, 1));

        setContentView(root);
    }

    TextView text(float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        styleText(t, sp, tf, color);
        return t;
    }

    void styleText(TextView t, float sp, Typeface tf, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
    }

    FrameLayout.LayoutParams lp(float w, float h, float x, float y) {
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(px(w), px(h));
        l.leftMargin = px(x); l.topMargin = px(y);
        l.gravity = Gravity.TOP | Gravity.LEFT;
        return l;
    }

    FrameLayout.LayoutParams lpWrap(float x, float y) {
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(-2, -2);
        l.leftMargin = px(x); l.topMargin = px(y);
        l.gravity = Gravity.TOP | Gravity.LEFT;
        return l;
    }

    // ------------------------------------------------------------------ apps discovery & single shelf

    void loadApps() {
        for (ShelfItem item : shelfItems) shelfContainer.removeView(item.card);
        shelfItems.clear();
        apps.clear();
        scaledItem = null;

        HashSet<String> seen = new HashSet<String>();
        HashMap<String, App> byPkg = new HashMap<String, App>();

        // 1. Leanback activities
        Intent qLean = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        addResolves(pm.queryIntentActivities(qLean, 0), byPkg, seen);

        // 2. Standard activities (sideloaded apps)
        Intent qStd = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        addResolves(pm.queryIntentActivities(qStd, 0), byPkg, seen);

        // 3. Android TV Info category
        Intent qInfo = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO);
        addResolves(pm.queryIntentActivities(qInfo, 0), byPkg, seen);

        // 4. Query all installed applications for any valid launch intent
        try {
            List<ApplicationInfo> allApps = pm.getInstalledApplications(0);
            if (allApps != null) {
                for (ApplicationInfo ai : allApps) {
                    if (seen.contains(ai.packageName) || HIDDEN.contains(ai.packageName)) continue;
                    Intent li = pm.getLeanbackLaunchIntentForPackage(ai.packageName);
                    if (li == null) li = pm.getLaunchIntentForPackage(ai.packageName);
                    if (li != null) {
                        List<ResolveInfo> riList = pm.queryIntentActivities(li, 0);
                        if (riList != null && !riList.isEmpty()) {
                            addResolves(riList, byPkg, seen);
                        }
                    }
                }
            }
        } catch (Exception e) { }

        // Load saved order
        for (String pkg : readAppOrder()) {
            App a = byPkg.remove(pkg);
            if (a != null) apps.add(a);
        }

        // Remaining apps sorted alphabetically A-Z
        ArrayList<App> remaining = new ArrayList<App>(byPkg.values());
        Collections.sort(remaining, new Comparator<App>() {
            public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
        });
        apps.addAll(remaining);

        rebuildShelf();
    }

    void addResolves(List<ResolveInfo> list, HashMap<String, App> byPkg, HashSet<String> seen) {
        if (list == null) return;
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (HIDDEN.contains(pkg) || seen.contains(pkg)) continue;
            seen.add(pkg);
            App a = new App();
            a.pkg = pkg;
            a.ri = ri;
            CharSequence lbl = ri.loadLabel(pm);
            if (lbl == null || lbl.length() == 0) lbl = ri.activityInfo.applicationInfo.loadLabel(pm);
            a.label = lbl == null ? pkg : lbl.toString().trim();
            Drawable bn = ri.activityInfo.loadBanner(pm);
            if (bn == null) bn = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (bn != null) { a.banner = bn; a.isBanner = true; }
            else {
                Drawable ic = ri.loadIcon(pm);
                if (ic == null) ic = ri.activityInfo.applicationInfo.loadIcon(pm);
                a.banner = ic;
                a.isBanner = false;
            }
            byPkg.put(pkg, a);
        }
    }

    void rebuildShelf() {
        for (ShelfItem item : shelfItems) shelfContainer.removeView(item.card);
        shelfItems.clear();
        scaledItem = null;

        float curX = SIDE;

        // 1. GEMINI / ASSISTANT BUTTON (far left of row)
        ShelfItem geminiItem = makeActionTile(TYPE_GEMINI, new Icon(Icon.GEMINI), "Gemini", curX, ACTION_W_GEMINI);
        shelfItems.add(geminiItem);
        curX += ACTION_W_GEMINI + GAP;

        // 2. SLEEP BUTTON (right of Gemini)
        ShelfItem sleepItem = makeActionTile(TYPE_SLEEP, new Icon(Icon.SLEEP), "Sleep", curX, ACTION_W_SLEEP);
        shelfItems.add(sleepItem);
        curX += ACTION_W_SLEEP + GAP;

        // 3. ALL APPS IN 1 COMPACT ROW (index 2 onwards)
        for (int i = 0; i < apps.size(); i++) {
            ShelfItem appItem = makeAppTile(apps.get(i), curX, APP_TILE_W);
            shelfItems.add(appItem);
            curX += APP_TILE_W + GAP;
        }

        shelfIdx = clamp(shelfIdx, 0, Math.max(0, shelfItems.size() - 1));

        root.post(new Runnable() { public void run() { focusCurrent(false); } });
    }

    ShelfItem makeActionTile(final int type, Drawable icon, String label, float x, float w) {
        final ShelfItem item = new ShelfItem();
        item.type = type; item.x = x; item.width = w;
        item.card = new FrameLayout(this);

        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(px(22));
        cbg.setColor(type == TYPE_GEMINI ? 0xFF121B28 : 0xFF141923);
        cbg.setStroke(px(1f), type == TYPE_GEMINI ? 0x4D38BDF8 : 0x2E64748B);
        item.card.setBackground(cbg);
        item.card.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(22)); }
        });
        item.card.setClipToOutline(true);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER);
        ll.setPadding(px(12), 0, px(14), 0);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        ll.addView(iv, new LinearLayout.LayoutParams(px(18), px(18)));

        TextView n = text(13.5f, fMed, type == TYPE_GEMINI ? 0xFF38BDF8 : 0xCCFFFFFF);
        n.setText(label);
        n.setSingleLine(true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.leftMargin = px(8);
        ll.addView(n, nl);

        item.card.addView(ll, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(px(w), px(ROW_H));
        cl.leftMargin = px(x);
        cl.gravity = Gravity.TOP | Gravity.LEFT;
        shelfContainer.addView(item.card, cl);

        return item;
    }

    ShelfItem makeAppTile(App a, float x, float w) {
        final ShelfItem item = new ShelfItem();
        item.type = TYPE_APP; item.app = a; item.x = x; item.width = w;
        item.card = new FrameLayout(this);

        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(px(10));
        cbg.setColor(0xFF1C1C1F);
        item.card.setBackground(cbg);
        item.card.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(10)); }
        });
        item.card.setClipToOutline(true);

        if (a.isBanner) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            item.card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(a.banner);
            FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(px(28), px(28));
            il.gravity = Gravity.CENTER;
            item.card.addView(iv, il);
        }

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(px(w), px(ROW_H));
        cl.leftMargin = px(x);
        cl.gravity = Gravity.TOP | Gravity.LEFT;
        shelfContainer.addView(item.card, cl);

        return item;
    }

    List<String> readAppOrder() {
        ArrayList<String> out = new ArrayList<String>();
        File f = new File(getExternalFilesDir(null), "apps_order.txt");
        if (!f.exists()) f = new File(getExternalFilesDir(null), "dock.txt");
        if (f.exists()) {
            try {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() > 0 && !line.startsWith("#")) out.add(line);
                }
                r.close();
            } catch (Exception e) { out.clear(); }
        }
        if (out.isEmpty()) Collections.addAll(out, DEFAULT_DOCK);
        return out;
    }

    void saveAppOrder() {
        try {
            File f = new File(getExternalFilesDir(null), "apps_order.txt");
            PrintWriter pw = new PrintWriter(f);
            for (App a : apps) pw.println(a.pkg);
            pw.close();
        } catch (Exception e) { }
        try {
            File fd = new File(getExternalFilesDir(null), "dock.txt");
            PrintWriter pw = new PrintWriter(fd);
            for (App a : apps) pw.println(a.pkg);
            pw.close();
        } catch (Exception e) { }
    }

    void toggleReorderMode() {
        if (shelfIdx < 2) return;
        reorderMode = !reorderMode;
        if (reorderMode) {
            Toast.makeText(this, "✦ MOVE MODE: D-pad Left/Right to reorder, OK to save", Toast.LENGTH_SHORT).show();
        } else {
            saveAppOrder();
            Toast.makeText(this, "App order saved", Toast.LENGTH_SHORT).show();
        }
        glass.invalidate();
        focusCurrent(true);
    }

    void goToFirstApp(boolean animate) {
        if (reorderMode) toggleReorderMode();
        shelfIdx = (shelfItems.size() > 2) ? 2 : 0;
        focusCurrent(animate);
    }

    // ------------------------------------------------------------------ focus & navigation

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        android.util.Log.d("GlassHome", "dispatchKeyEvent: code=" + e.getKeyCode() + " action=" + e.getAction());
        int k = e.getKeyCode();

        boolean center = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;

        // Any key press resets the hide timer and wakes the shelf if hidden
        resetShelfHideTimer();
        if (isShelfHidden) {
            showShelf(true);
            if (e.getAction() == KeyEvent.ACTION_UP) {
                return true;
            }
            // For DOWN action when waking up, consume event so accidental clicks/launches don't happen
            if (k != KeyEvent.KEYCODE_HOME) {
                return true;
            }
        }

        if (k == KeyEvent.KEYCODE_HOME) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                goToFirstApp(true);
            }
            return true;
        }

        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (reorderMode) { toggleReorderMode(); return true; }
                if (shelfIdx != 2) {
                    goToFirstApp(true);
                }
            }
            return true;
        }

        if (center) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.isLongPress() || (e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                    handler.removeCallbacks(longPressRunnable);
                    longPressed = true;
                    if (!reorderMode && shelfIdx >= 2) {
                        toggleReorderMode();
                    }
                    return true;
                }
                if (e.getRepeatCount() == 0) {
                    longPressed = false;
                    handler.removeCallbacks(longPressRunnable);
                    if (!reorderMode && shelfIdx >= 2) {
                        handler.postDelayed(longPressRunnable, 500);
                    }
                } else if (!longPressed && !reorderMode && shelfIdx >= 2 && e.getRepeatCount() >= 4) {
                    handler.removeCallbacks(longPressRunnable);
                    longPressed = true;
                    toggleReorderMode();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPressRunnable);
                if (longPressed) {
                    longPressed = false;
                    return true;
                }
                if (reorderMode) {
                    toggleReorderMode();
                } else {
                    activate();
                }
            }
            return true;
        }

        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        handler.removeCallbacks(longPressRunnable);

        if (reorderMode) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && shelfIdx > 2) {
                int appPos = shelfIdx - 2;
                App temp = apps.get(appPos);
                apps.set(appPos, apps.get(appPos - 1));
                apps.set(appPos - 1, temp);
                shelfIdx--;
                rebuildShelf();
                return true;
            } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && shelfIdx < shelfItems.size() - 1) {
                int appPos = shelfIdx - 2;
                App temp = apps.get(appPos);
                apps.set(appPos, apps.get(appPos + 1));
                apps.set(appPos + 1, temp);
                shelfIdx++;
                rebuildShelf();
                return true;
            }
            return true;
        }

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (shelfIdx > 0) shelfIdx--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (shelfIdx < shelfItems.size() - 1) shelfIdx++;
                break;
        }
        focusCurrent(true);
        return true;
    }

    void focusCurrent(boolean animate) {
        if (root.getWidth() == 0 || shelfItems.isEmpty()) return;

        RectF target = new RectF();

        ShelfItem item = shelfItems.get(shelfIdx);

        // Compute horizontal scroll translation for shelfContainer
        float targetTransX = px(480) - px(item.x + item.width / 2f);
        ShelfItem last = shelfItems.get(shelfItems.size() - 1);
        float minTransX = px(960 - SIDE) - px(last.x + last.width);
        if (minTransX > 0) minTransX = 0;
        targetTransX = clamp(targetTransX, minTransX, 0f);

        animateShelfScroll(targetTransX, animate);

        float cardScreenX = px(item.x) + targetTransX;
        float cardScreenY = px(ROW_Y);
        float w = px(item.width) * SCALE, h = px(ROW_H) * SCALE;
        float cx = cardScreenX + px(item.width) / 2f, cy = cardScreenY + px(ROW_H) / 2f;
        target.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        target.inset(-px(2.5f), -px(2.5f));
        float radius = (item.type == TYPE_APP) ? px(10) : px(22);

        // Card scale
        if (scaledItem != null && scaledItem != item) {
            scaledItem.card.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (scaledItem != item || reorderMode) {
            item.card.animate().scaleX(SCALE).scaleY(SCALE).setDuration(animate ? 250 : 0).setInterpolator(new OvershootInterpolator(1.4f)).start();
        }
        scaledItem = item;

        moveGlass(target, radius, animate);
    }

    void animateShelfScroll(float toTransX, boolean animate) {
        if (scrollAnim != null) scrollAnim.cancel();
        if (!animate) {
            curShelfTransX = toTransX;
            shelfContainer.setTranslationX(curShelfTransX);
            return;
        }
        scrollAnim = ValueAnimator.ofFloat(curShelfTransX, toTransX);
        scrollAnim.setDuration(280);
        scrollAnim.setInterpolator(new DecelerateInterpolator());
        scrollAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator va) {
                curShelfTransX = (Float) va.getAnimatedValue();
                shelfContainer.setTranslationX(curShelfTransX);
            }
        });
        scrollAnim.start();
    }

    void moveGlass(final RectF to, final float toRadius, boolean animate) {
        if (moveAnim != null) moveAnim.cancel();
        if (!animate || glassRect.isEmpty()) {
            glassRect.set(to); glassRadius = toRadius;
            applyGlass();
            return;
        }
        final RectF from = new RectF(glassRect);
        final float fromR = glassRadius;
        moveAnim = ValueAnimator.ofFloat(0f, 1f);
        moveAnim.setDuration(280);
        moveAnim.setInterpolator(new Spring());
        moveAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator va) {
                float t = (Float) va.getAnimatedValue();
                glassRect.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                        lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
                glassRadius = lerp(fromR, toRadius, t);
                applyGlass();
            }
        });
        moveAnim.start();
    }

    void applyGlass() {
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) glass.getLayoutParams();
        int left = Math.round(glassRect.left), top = Math.round(glassRect.top);
        int w = Math.round(glassRect.width()), h = Math.round(glassRect.height());
        if (l.leftMargin != left || l.topMargin != top || l.width != w || l.height != h) {
            l.leftMargin = left; l.topMargin = top;
            l.width = w; l.height = h;
            glass.setLayoutParams(l);
        }
        ((Glass) glass.getBackground()).radius = glassRadius;
        glass.invalidate();
    }

    // ------------------------------------------------------------------ actions

    void activate() {
        if (shelfIdx < 0 || shelfIdx >= shelfItems.size()) return;
        ShelfItem item = shelfItems.get(shelfIdx);
        if (item.type == TYPE_GEMINI) {
            triggerAssistant();
            return;
        }
        if (item.type == TYPE_SLEEP) {
            triggerSleep();
            return;
        }

        if (item.app != null) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(item.app.pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(item.app.pkg);
            if (i == null && item.app.ri != null) {
                i = new Intent(Intent.ACTION_MAIN);
                i.setClassName(item.app.ri.activityInfo.packageName, item.app.ri.activityInfo.name);
            }
            if (i != null) start(i);
            else Toast.makeText(this, "Can't launch " + item.app.label, Toast.LENGTH_SHORT).show();
        }
    }

    void triggerSleep() {
        android.util.Log.i("GlassHome", "triggerSleep requested");
        // Ensure accessibility service is prompted if it became inactive
        boolean ok = InactivityWatchdogService.sleepDevice();
        android.util.Log.i("GlassHome", "InactivityWatchdogService.sleepDevice result: " + ok);
        if (!ok) {
            boolean execSuccess = false;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"input", "keyevent", "223"});
                p.waitFor();
                execSuccess = (p.exitValue() == 0);
            } catch (Exception ignored) {}

            if (!execSuccess) {
                try {
                    Runtime.getRuntime().exec(new String[]{"input", "keyevent", "26"});
                } catch (Exception ignored) {}
            }
            Toast.makeText(this, "Sleep requested (enable Accessibility for instant standby)", Toast.LENGTH_SHORT).show();
        }
    }

    void triggerAssistant() {
        try {
            Intent ai = new Intent(Intent.ACTION_ASSIST);
            ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(ai);
        } catch (Exception e) {
            try {
                Intent vi = new Intent(Intent.ACTION_VOICE_COMMAND);
                vi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(vi);
            } catch (Exception e2) {
                Toast.makeText(this, "Assistant unavailable", Toast.LENGTH_SHORT).show();
            }
        }
    }

    void start(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        catch (Exception e) { Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show(); }
    }

    // ------------------------------------------------------------------ receivers

    final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { loadApps(); }
    };

    void registerReceivers() {
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        reg(pkgReceiver, pf);
    }

    void reg(BroadcastReceiver r, IntentFilter f) {
        try {
            Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class)
                    .invoke(this, r, f, 0x2);
        } catch (Exception e) { registerReceiver(r, f); }
    }

    // ------------------------------------------------------------------ helpers & drawables

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    static float[] offset(View v, View ancestor) {
        float x = 0, y = 0;
        while (v != null && v != ancestor) {
            x += v.getLeft(); y += v.getTop();
            v = (View) v.getParent();
        }
        return new float[]{x, y};
    }

    static Bitmap decodeScaled(File f, int maxW) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= maxW) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable t) { return null; }
    }

    static class Spring implements TimeInterpolator {
        public float getInterpolation(float t) { return (float) (1 - Math.exp(-7 * t) * Math.cos(9 * t)); }
    }

    /** Clean selection outline: white border in normal mode, electric cyan in Move Mode. */
    class Glass extends Drawable {
        float radius = px(10);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        Glass() {
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(px(2.0f));
        }
        @Override public void draw(Canvas c) {
            r.set(getBounds());
            r.inset(px(1f), px(1f));
            if (reorderMode) {
                edge.setColor(0xFF38BDF8);
                edge.setStrokeWidth(px(2.5f));
            } else {
                edge.setColor(0xFFFFFFFF);
                edge.setStrokeWidth(px(2.0f));
            }
            c.drawRoundRect(r, radius, radius, edge);
        }
        @Override public void setAlpha(int a) { edge.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { edge.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Small vector icons for Gemini and Sleep. */
    class Icon extends Drawable {
        static final int GEMINI = 0, SLEEP = 1;
        final int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(int k) {
            kind = k;
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(1.6f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height(), u = w / 18f;
            c.save();
            c.translate(getBounds().left, getBounds().top);
            if (kind == GEMINI) {
                // Official Google Gemini 4-point sparkle star
                float cx = 9 * u, cy = 9 * u, R = 7.5f * u;
                Path sp = new Path();
                sp.moveTo(cx, cy - R);
                sp.quadTo(cx, cy, cx + R, cy);
                sp.quadTo(cx, cy, cx, cy + R);
                sp.quadTo(cx, cy, cx - R, cy);
                sp.quadTo(cx, cy, cx, cy - R);
                sp.close();
                Paint pGem = new Paint(p);
                pGem.setStyle(Paint.Style.FILL_AND_STROKE);
                pGem.setColor(0xFF38BDF8);
                c.drawPath(sp, pGem);
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                dot.setColor(Color.WHITE);
                c.drawCircle(cx, cy, 1.3f * u, dot);
            } else if (kind == SLEEP) {
                // Universal power / sleep standby icon
                float cx = 9 * u, cy = 9.5f * u, r = 5.2f * u;
                c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -60, 300, false, p);
                c.drawLine(cx, cy - r - 1.8f * u, cx, cy, p);
            }
            c.restore();
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ==================================================================
    // WebDAV Photo Streaming, Uncropped Display & Dynamic Ambient Glow
    // ==================================================================

    void loadDefaultAssetWallpaper() {
        try {
            InputStream is = getAssets().open("default_wallpaper.jpg");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bm = BitmapFactory.decodeStream(is, null, o);
            is.close();
            if (bm != null) {
                photoA.setImageBitmap(bm);
                photoA.setAlpha(1f);
                if (wallpaperGlowEnabled) {
                    glowA.setImageDrawable(createGlowDrawable(GLOW_COLORS[rng.nextInt(GLOW_COLORS.length)]));
                    glowA.setAlpha(0.45f);
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("GlassHome", "Could not load default asset wallpaper", t);
        }
    }

    void startPhotoRotation() {
        handler.removeCallbacks(photoRotateRunnable);
        if (webdavUrl != null && !webdavUrl.trim().isEmpty() && webdavFolder != null && !webdavFolder.trim().isEmpty()) {
            final PhotoDatabase pdb = PhotoDatabase.getInstance();
            if (pdb.getPhotoCount() == 0 && !isCrawling) {
                crawlWebdavPhotos();
            } else if (webdavRefreshInterval > 0) {
                rotateToNextPhoto();
                handler.postDelayed(photoRotateRunnable, webdavRefreshInterval * 1000L);
            }
        }
    }

    void stopPhotoRotation() {
        handler.removeCallbacks(photoRotateRunnable);
    }

    void crawlWebdavPhotos() {
        if (isCrawling) return;
        isCrawling = true;
        WebdavCrawler.crawlAsync(this, webdavUrl, webdavFolder, webdavUser, webdavPass, webdavRecursive, new WebdavCrawler.CrawlCallback() {
            @Override
            public void onCrawlComplete(final List<String> photos) {
                isCrawling = false;
                if (photos != null && !photos.isEmpty()) {
                    PhotoDatabase.getInstance().syncPhotos(photos);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            rotateToNextPhoto();
                            if (webdavRefreshInterval > 0) {
                                handler.removeCallbacks(photoRotateRunnable);
                                handler.postDelayed(photoRotateRunnable, webdavRefreshInterval * 1000L);
                            }
                        }
                    });
                }
            }
        });
    }

    void rotateToNextPhoto() {
        if (!visible) return;
        // If media is currently active, halt rotation & downloading
        if (PhotoDatabase.isMediaActive(this)) {
            return;
        }

        final PhotoDatabase pdb = PhotoDatabase.getInstance();
        if (pdb.getPhotoCount() == 0) {
            if (!isCrawling) crawlWebdavPhotos();
            return;
        }

        // Check if cycle completed; trigger background crawl and reset cycle
        if (pdb.isCycleComplete() && !isCrawling) {
            android.util.Log.i("GlassHome", "Photo cycle complete. Triggering background re-index.");
            pdb.resetCycle();
            crawlWebdavPhotos();
        }

        final String nextUrl = pdb.getNextPhotoUrl();
        if (nextUrl == null) return;

        photoExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (PhotoDatabase.isMediaActive(HomeActivity.this)) {
                        return;
                    }

                    String authHeader = null;
                    if (webdavUser != null && !webdavUser.isEmpty()) {
                        String userPass = webdavUser + ":" + (webdavPass != null ? webdavPass : "");
                        authHeader = "Basic " + android.util.Base64.encodeToString(userPass.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                    }

                    HttpURLConnection conn = (HttpURLConnection) new URL(nextUrl).openConnection();
                    conn.setRequestMethod("GET");
                    if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);

                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
                        in.close();
                        byte[] imgBytes = baos.toByteArray();

                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length, o);

                        int sample = 1;
                        while (o.outWidth / (sample * 2) >= 1920 && o.outHeight / (sample * 2) >= 1080) {
                            sample *= 2;
                        }

                        o = new BitmapFactory.Options();
                        o.inSampleSize = sample;
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        Bitmap decodedBmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length, o);

                        if (decodedBmp != null) {
                            try {
                                android.media.ExifInterface exif = new android.media.ExifInterface(new java.io.ByteArrayInputStream(imgBytes));
                                int orientation = exif.getAttributeInt(
                                        android.media.ExifInterface.TAG_ORIENTATION,
                                        android.media.ExifInterface.ORIENTATION_NORMAL);
                                int rotationDegrees = 0;
                                switch (orientation) {
                                    case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                                        rotationDegrees = 90;
                                        break;
                                    case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                                        rotationDegrees = 180;
                                        break;
                                    case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                                        rotationDegrees = 270;
                                        break;
                                }
                                if (rotationDegrees != 0) {
                                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                                    matrix.postRotate(rotationDegrees);
                                    Bitmap rotatedBmp = Bitmap.createBitmap(
                                            decodedBmp, 0, 0, decodedBmp.getWidth(), decodedBmp.getHeight(), matrix, true);
                                    if (rotatedBmp != decodedBmp) {
                                        decodedBmp.recycle();
                                        decodedBmp = rotatedBmp;
                                    }
                                }
                            } catch (Throwable ex) {
                                android.util.Log.w("GlassHome", "Could not parse EXIF orientation", ex);
                            }
                        }

                        final Bitmap bmp = decodedBmp;
                        final int glowColor = GLOW_COLORS[rng.nextInt(GLOW_COLORS.length)];

                        if (bmp != null) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    crossfadePhoto(bmp, glowColor);
                                }
                            });
                        }
                    }
                    conn.disconnect();
                } catch (Throwable t) {
                    android.util.Log.e("GlassHome", "Failed loading photo: " + nextUrl, t);
                }
            }
        });
    }

    void crossfadePhoto(final Bitmap bmp, final int glowColor) {
        if (!visible) return;

        final ImageView inPhoto = isBufferAActive ? photoB : photoA;
        final ImageView outPhoto = isBufferAActive ? photoA : photoB;
        final ImageView inGlow = isBufferAActive ? glowB : glowA;
        final ImageView outGlow = isBufferAActive ? glowA : glowB;

        inPhoto.setImageBitmap(bmp);
        inPhoto.setAlpha(0f);

        if (wallpaperGlowEnabled) {
            inGlow.setImageDrawable(createGlowDrawable(glowColor));
            inGlow.setAlpha(0f);
        }

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1200);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                float f = (Float) va.getAnimatedValue();
                inPhoto.setAlpha(f);
                outPhoto.setAlpha(1f - f);
                if (wallpaperGlowEnabled) {
                    inGlow.setAlpha(f * 0.45f);
                    outGlow.setAlpha((1f - f) * 0.45f);
                }
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                isBufferAActive = !isBufferAActive;
            }
        });
        anim.start();
    }

    Drawable createGlowDrawable(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gd.setGradientRadius(px(540));
        gd.setGradientCenter(0.5f, 0.45f);
        int cCore = Color.argb(190, Color.red(color), Color.green(color), Color.blue(color));
        int cMid = Color.argb(90, Color.red(color), Color.green(color), Color.blue(color));
        int cOuter = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
        gd.setColors(new int[]{cCore, cMid, cOuter});
        return gd;
    }
}
