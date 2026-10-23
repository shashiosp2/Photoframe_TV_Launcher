package com.madhu.glasslauncher;

import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.database.ContentObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
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
import android.os.HandlerThread;
import android.os.Process;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Glass Home: a small Apple-TV-style launcher for Android TV.
 * Pure framework views (no AndroidX), absolute layout in dp for a 960x540dp screen,
 * a hand-rolled focus model so D-pad navigation is deterministic.
 */
public class HomeActivity extends Activity {

    // Default dock (left to right). Override without rebuilding: put package names, one per
    // line, in /sdcard/Android/data/com.madhu.glasslauncher/files/dock.txt
    static final String[] DEFAULT_DOCK = {
            "com.google.android.youtube.tv", "com.suntv.sunnxt", "in.startv.hotstar", "com.netflix.ninja"};
    static final HashSet<String> HIDDEN = new HashSet<String>();
    static {
        HIDDEN.add("com.madhu.glasslauncher");
        HIDDEN.add("com.android.tv.settings");      // reachable from the Settings pill
    }

    static final int TOP = 0, DOCK = 1, GRID = 2;
    static final int COLS = 4;

    // geometry in dp (960 x 540 dp screen)
    static final float SIDE = 48, BAND_Y = 348, BAND_H = 160, BAND_PAD = 22;
    static final float TILE_W = 182, TILE_H = 102, LABEL_H = 22;
    static final float GRID_TOP = 584, ROW_PITCH = 150, GRID_FOCUS_Y = 170;
    static final float SCALE = 1.08f;

    float dp;
    Typeface fThin, fLight, fReg, fMed, fBold;
    View topScrim;
    PackageManager pm;
    SharedPreferences prefs;
    Handler handler = new Handler();

    FrameLayout root, content, overlay;
    Pane pane;
    View glow, glass, dropdown;
    GradientDrawable glowDrawable;
    TextView appName, greeting, wifiText, audioText;
    LinearLayout pillRow;
    FrameLayout topBar;
    static final int P_INPUTS = 0, P_WIFI = 1, P_AUDIO = 2, P_SETTINGS = 3;
    final View[] pills = new View[4];
    final TextView[] pillText = new TextView[4];

    // top bar: RAM gauge (display only)
    Widgets.Bar ramBar;
    TextView ramText;

    // widgets (right side)
    static final float WX = 476, WY = 84, WCOL = 220, CALX = 704, CALW = 208, WBOTTOM = 326;
    TextView wTemp, wCond, wSub, wPlace, wHiLo, nVal, nChg, nStatus, stVal, stSub;
    Widgets.WeatherIcon wIcon;
    ImageView wIconView;
    Widgets.Sparkline spark;
    Widgets.MonthView month;
    HandlerThread netThread;
    Handler net;
    double lat = Double.NaN, lon = Double.NaN;   // no default; set in files/widgets.txt
    String place = "Weather";

    final ArrayList<App> dockApps = new ArrayList<App>();
    final ArrayList<App> gridApps = new ArrayList<App>();
    final ArrayList<Tile> dockTiles = new ArrayList<Tile>();
    final ArrayList<Tile> gridTiles = new ArrayList<Tile>();
    final ArrayList<View> gridViews = new ArrayList<View>();
    final HashMap<String, Integer> colorCache = new HashMap<String, Integer>();

    int zone = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0;
    boolean visible, longPressed;
    float curTrans = 0;
    RectF glassRect = new RectF();
    float glassRadius;
    ValueAnimator moveAnim, glowAnim;
    int glowColor = 0xFF3A3A40;
    Tile scaledTile;

    // dropdown state
    final ArrayList<InputItem> inputs = new ArrayList<InputItem>();
    final ArrayList<TextView> inputRows = new ArrayList<TextView>();
    int inputIdx = 0;
    boolean dropdownOpen;

    static class App { String pkg, label; ResolveInfo ri; Drawable banner; boolean isBanner; }
    static class Tile { App app; FrameLayout card; TextView label; float x, y; }
    static class InputItem { String label, id; boolean liveTv, current; int audio = -1; }
    int dropdownPill = P_INPUTS;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        pm = getPackageManager();
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        fThin = font("outfit_200.ttf"); fLight = font("outfit_300.ttf");
        fReg = font("outfit_400.ttf"); fMed = font("outfit_500.ttf"); fBold = font("outfit_600.ttf");
        zone = prefs.getInt("zone", DOCK);
        if (zone == TOP) zone = DOCK;
        topIdx = prefs.getInt("top", 0); dockIdx = prefs.getInt("dock", 0); gridIdx = prefs.getInt("grid", 0);
        netThread = new HandlerThread("glass-net", Process.THREAD_PRIORITY_BACKGROUND);
        netThread.start();
        net = new Handler(netThread.getLooper());
        readWidgetConfig();
        buildUi();
        buildWidgets();
        loadApps();
        registerReceivers();
        updateWifi();
        updateAudioPill();
        updateGreeting();
        showCachedWidgets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        updateWifi();
        updateAudioPill();
        updateGreeting();
        month.invalidate();
        handler.removeCallbacks(ramTick); handler.post(ramTick);
        maybeFetchWeather();
        handler.removeCallbacks(niftyTick); handler.post(niftyTick);
        maybeComputeScreenTime();
        root.post(new Runnable() { public void run() { focusCurrent(false); } });
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        closeDropdown();
        handler.removeCallbacks(ramTick);
        handler.removeCallbacks(niftyTick);
        handler.removeCallbacks(weatherTick);
        prefs.edit().putInt("zone", zone).putInt("top", topIdx).putInt("dock", dockIdx).putInt("grid", gridIdx).apply();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // HOME pressed while already home: go back to the dock, keep the last dock tile.
        if (visible) { closeDropdown(); zone = DOCK; focusCurrent(true); }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception e) { }
        try { unregisterReceiver(netReceiver); } catch (Exception e) { }
        try { unregisterReceiver(sysReceiver); } catch (Exception e) { }
        try { getContentResolver().unregisterContentObserver(audioObserver); } catch (Exception e) { }
        try { unregisterReceiver(screenReceiver); } catch (Exception e) { }
        handler.removeCallbacksAndMessages(null);
        netThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { /* launcher: never finish */ }

    Typeface font(String n) {
        try { return Typeface.createFromAsset(getAssets(), "fonts/" + n); }
        catch (Exception e) { return Typeface.create("sans-serif-light", Typeface.NORMAL); }
    }

    int px(float d) { return Math.round(d * dp); }

    // ------------------------------------------------------------------ UI

    void buildUi() {
        root = new FrameLayout(this);
        // midnight blue (top-left) fading to black (bottom-right); a static drawable, no animation
        GradientDrawable bgGrad = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF14213D, 0xFF0A1022, 0xFF03050B, 0xFF000000});
        root.setBackground(bgGrad);
        root.setClipChildren(false);

        // optional wallpaper (pitch black when absent)
        File wp = new File(getExternalFilesDir(null), "wallpaper.jpg");
        if (!wp.exists()) wp = new File(getExternalFilesDir(null), "wallpaper.png");
        if (wp.exists()) {
            Bitmap bm = decodeScaled(wp, 1920);
            if (bm != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(bm);
                iv.setColorFilter(0x80000000);
                root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            }
        }

        pane = new Pane(this);
        root.addView(pane, new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(this);
        content.setClipChildren(false);
        pane.addView(content);

        // ambient glow behind the dock
        glow = new View(this);
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(px(330));
        glowDrawable.setGradientCenter(0.5f, 0.5f);
        setGlowColor(glowColor);
        glow.setBackground(glowDrawable);
        content.addView(glow, lp(1200, 720, -120, BAND_Y + BAND_H / 2 - 360));

        // top-left: greeting, clock, date
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.LEFT);
        greeting = text(17, fReg, 0x99FFFFFF);
        clockBox.addView(greeting);
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setBaselineAligned(true);          // AM/PM sits on the clock's baseline
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("hh:mm"); clock.setFormat24Hour("HH:mm");
        styleText(clock, 104, fThin, 0xFFFFFFFF);
        clock.setLetterSpacing(-0.01f);
        timeRow.addView(clock);
        TextClock ampm = new TextClock(this);
        ampm.setFormat12Hour("a"); ampm.setFormat24Hour("");   // hidden in 24-hour mode
        styleText(ampm, 18, fLight, 0x99FFFFFF);
        ampm.setAllCaps(true);
        ampm.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams apl = new LinearLayout.LayoutParams(-2, -2);
        apl.leftMargin = px(8);
        timeRow.addView(ampm, apl);
        clockBox.addView(timeRow);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM"); date.setFormat24Hour("EEEE, d MMMM");
        styleText(date, 20, fLight, 0xB3FFFFFF);
        clockBox.addView(date);
        FrameLayout clockArea = new FrameLayout(this);
        clockArea.addView(clockBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.LEFT));
        content.addView(clockArea, lp(WX - SIDE - 24, WBOTTOM - WY, SIDE, WY));


        // focused dock app name above the dock
        appName = text(46, fLight, 0xF2FFFFFF);
        appName.setSingleLine(true);
        appName.setMaxWidth(px(WX - SIDE - 24));
        appName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        appName.setShadowLayer(px(18), 0, 0, 0x55000000);
        FrameLayout.LayoutParams al = lpWrap(SIDE + 4, 0);
        al.gravity = Gravity.TOP | Gravity.LEFT;
        al.topMargin = px(BAND_Y - 74);
        // app name is shown under the focused dock tile, so the big title is not added to the layout

        // dock band
        View band = new View(this);
        GradientDrawable bd = new GradientDrawable();
        bd.setCornerRadius(px(28));
        bd.setColor(0x14FFFFFF);
        bd.setStroke(Math.max(1, px(0.75f)), 0x14FFFFFF);
        band.setBackground(bd);
        content.addView(band, lp(960 - 2 * SIDE, BAND_H, SIDE, BAND_Y));

        buildTopBar();

        // overlay layer: glass highlight + inputs dropdown (not scrolled)
        overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        glass = new View(this);
        glass.setBackground(new Glass());
        overlay.addView(glass, new FrameLayout.LayoutParams(1, 1));

        setContentView(root);
    }

    View pill(final int idx, Drawable icon, String label) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(20));
        bg.setColor(0x24FFFFFF);
        bg.setStroke(Math.max(1, px(0.75f)), 0x1FFFFFFF);
        p.setBackground(bg);
        p.setPadding(px(label == null ? 11 : 15), 0, px(label == null ? 11 : 17), 0);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setAlpha(0.8f);
        p.addView(iv, new LinearLayout.LayoutParams(px(18), px(18)));
        if (label != null) {
            TextView t = text(15, fReg, 0xCCFFFFFF);
            t.setText(label);
            t.setSingleLine(true);
            t.setMaxWidth(px(170));
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.leftMargin = px(8);
            p.addView(t, tl);
            pillText[idx] = t;
        }
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(label == null ? px(40) : -2, px(40));
        if (idx > 0) l.leftMargin = px(12);
        pillRow.addView(p, l);
        return p;
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

    Tile makeTile(App a, float x, float y) {
        final Tile t = new Tile();
        t.app = a; t.x = x; t.y = y;
        t.card = new FrameLayout(this);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(px(10));
        cbg.setColor(0xFF1C1C1F);
        t.card.setBackground(cbg);
        t.card.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(10)); }
        });
        t.card.setClipToOutline(true);
        if (a.isBanner) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            t.card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // no banner: icon + name on a dark card
            LinearLayout ll = new LinearLayout(this);
            ll.setOrientation(LinearLayout.VERTICAL);
            ll.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.banner);
            ll.addView(iv, new LinearLayout.LayoutParams(px(44), px(44)));
            TextView n = text(13, fReg, 0xCCFFFFFF);
            n.setText(a.label);
            n.setSingleLine(true);
            n.setPadding(px(8), px(8), px(8), 0);
            ll.addView(n);
            t.card.addView(ll, new FrameLayout.LayoutParams(-1, -1));
        }
        content.addView(t.card, lp(TILE_W, TILE_H, x, y));
        t.label = text(14, fReg, 0xEBFFFFFF);
        t.label.setText(a.label);
        t.label.setGravity(Gravity.CENTER);
        t.label.setSingleLine(true);
        t.label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.label.setAlpha(0f);
        content.addView(t.label, lp(TILE_W + 20, LABEL_H, x - 10, y + TILE_H + 9));
        return t;
    }

    // ------------------------------------------------------------------ apps

    void loadApps() {
        // remove old tiles
        for (Tile t : dockTiles) { content.removeView(t.card); content.removeView(t.label); }
        for (Tile t : gridTiles) { content.removeView(t.card); content.removeView(t.label); }
        for (View v : gridViews) content.removeView(v);
        dockTiles.clear(); gridTiles.clear(); gridViews.clear(); dockApps.clear(); gridApps.clear();
        scaledTile = null;

        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        HashMap<String, App> byPkg = new HashMap<String, App>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (HIDDEN.contains(pkg) || byPkg.containsKey(pkg)) continue;
            App a = new App();
            a.pkg = pkg; a.ri = ri;
            a.label = String.valueOf(ri.loadLabel(pm)).trim();
            Drawable bn = ri.activityInfo.loadBanner(pm);
            if (bn == null) bn = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (bn != null) { a.banner = bn; a.isBanner = true; }
            else { a.banner = ri.loadIcon(pm); a.isBanner = false; }
            byPkg.put(pkg, a);
        }
        for (String pkg : readDock()) {
            App a = byPkg.remove(pkg);
            if (a != null && dockApps.size() < 4) dockApps.add(a);
        }
        gridApps.addAll(byPkg.values());
        Collections.sort(gridApps, new Comparator<App>() {
            public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
        });

        // dock tiles, evenly spaced inside the band
        float inner = 960 - 2 * SIDE - 2 * BAND_PAD;
        float gap = (inner - COLS * TILE_W) / (COLS - 1);
        float x0 = SIDE + BAND_PAD;
        for (int i = 0; i < dockApps.size(); i++)
            dockTiles.add(makeTile(dockApps.get(i), x0 + i * (TILE_W + gap), BAND_Y + BAND_PAD));

        // grid
        TextView hdr = text(16, fMed, 0x80FFFFFF);
        hdr.setText("All apps");
        hdr.setLetterSpacing(0.04f);
        content.addView(hdr, lpWrap(x0 + 2, GRID_TOP - 34));
        gridViews.add(hdr);
        for (int i = 0; i < gridApps.size(); i++)
            gridTiles.add(makeTile(gridApps.get(i), x0 + (i % COLS) * (TILE_W + gap), GRID_TOP + (i / COLS) * ROW_PITCH));
        int rows = (gridApps.size() + COLS - 1) / COLS;
        float h = Math.max(540, GRID_TOP + rows * ROW_PITCH + 30);
        content.setLayoutParams(new ViewGroup.LayoutParams(-1, px(h)));

        // keep glass on top of tiles, clamp remembered indices
        dockIdx = clamp(dockIdx, 0, Math.max(0, dockTiles.size() - 1));
        gridIdx = clamp(gridIdx, 0, Math.max(0, gridTiles.size() - 1));
        if (zone == GRID && gridTiles.isEmpty()) zone = DOCK;
        if (zone == DOCK && dockTiles.isEmpty()) zone = gridTiles.isEmpty() ? TOP : GRID;
        root.post(new Runnable() { public void run() { focusCurrent(false); } });
    }

    List<String> readDock() {
        ArrayList<String> out = new ArrayList<String>();
        File f = new File(getExternalFilesDir(null), "dock.txt");
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

    // ------------------------------------------------------------------ focus model

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean center = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (dropdownOpen) closeDropdown();
                else if (zone != DOCK) { zone = DOCK; focusCurrent(true); }
            }
            return true;
        }
        if (center) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) longPressed = false;
                else if (!longPressed && e.getRepeatCount() >= 8) { longPressed = true; appInfo(); }
            } else if (e.getAction() == KeyEvent.ACTION_UP && !longPressed) {
                activate();
            }
            return true;
        }
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (dropdownOpen) { dropdownKey(k); return true; }
        int n = gridTiles.size();
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (zone == DOCK) zone = TOP;
                else if (zone == GRID) {
                    if (gridIdx < COLS) { if (!dockTiles.isEmpty()) { zone = DOCK; dockIdx = Math.min(gridIdx, dockTiles.size() - 1); } else zone = TOP; }
                    else gridIdx -= COLS;
                } else return true;   // top bar: nothing above
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (zone == TOP) {
                    if (!dockTiles.isEmpty()) zone = DOCK;
                    else if (n > 0) { zone = GRID; gridIdx = 0; }
                    else return true;
                } else if (zone == DOCK) {
                    if (n == 0) return true;
                    zone = GRID; gridIdx = Math.min(dockIdx, n - 1);
                } else {
                    int row = gridIdx / COLS, lastRow = (n - 1) / COLS;
                    if (row >= lastRow) return true;
                    gridIdx = Math.min(gridIdx + COLS, n - 1);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (zone == TOP) { if (topIdx == 0) return true; topIdx--; }
                else if (zone == DOCK) { if (dockIdx == 0) return true; dockIdx--; }
                else { if (gridIdx % COLS == 0) return true; gridIdx--; }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (zone == TOP) { if (topIdx == pills.length - 1) return true; topIdx++; }
                else if (zone == DOCK) { if (dockIdx >= dockTiles.size() - 1) return true; dockIdx++; }
                else { if (gridIdx % COLS == COLS - 1 || gridIdx >= n - 1) return true; gridIdx++; }
                break;
        }
        focusCurrent(true);
        return true;
    }

    void focusCurrent(boolean animate) {
        if (content.getWidth() == 0) return;
        Tile tile = null;
        RectF target = new RectF();
        float radius;
        float trans;
        if (zone == TOP) {
            View p = pills[topIdx];
            float[] o = offset(p, root);
            target.set(o[0], o[1], o[0] + p.getWidth(), o[1] + p.getHeight());
            target.inset(-px(2), -px(2));
            radius = px(22);
            trans = 0;
        } else {
            tile = zone == DOCK ? dockTiles.get(dockIdx) : gridTiles.get(gridIdx);
            float w = px(TILE_W) * SCALE, h = px(TILE_H) * SCALE;
            float cx = px(tile.x) + px(TILE_W) / 2f, cy = px(tile.y) + px(TILE_H) / 2f;
            target.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            target.inset(-px(2.5f), -px(2.5f));
            radius = px(13);
            if (zone == DOCK) trans = 0;
            else {
                int row = gridIdx / COLS;
                trans = -(px(GRID_TOP + row * ROW_PITCH) - px(GRID_FOCUS_Y));
                float min = -(content.getHeight() - root.getHeight());
                trans = Math.max(trans, Math.min(0, min));
            }
        }
        target.offset(0, trans);

        // pills
        for (int i = 0; i < pills.length; i++) {
            boolean f = zone == TOP && i == topIdx;
            GradientDrawable bg = (GradientDrawable) pills[i].getBackground();
            bg.setColor(f ? 0x40FFFFFF : 0x24FFFFFF);
            if (pillText[i] != null) pillText[i].setTextColor(f ? 0xFFFFFFFF : 0xCCFFFFFF);
            ((ViewGroup) pills[i]).getChildAt(0).setAlpha(f ? 1f : 0.8f);
        }
        // tiles: scale the focused one, show its name
        if (scaledTile != null && scaledTile != tile) {
            scaledTile.card.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
            scaledTile.label.animate().alpha(0f).setDuration(120).start();
        }
        if (tile != null && scaledTile != tile) {
            tile.card.animate().scaleX(SCALE).scaleY(SCALE).setDuration(animate ? 320 : 0).setInterpolator(new OvershootInterpolator(1.6f)).start();
            tile.label.animate().alpha(1f).setDuration(animate ? 180 : 0).start();
        }
        scaledTile = tile;
        if (zone == DOCK && tile != null) showDockApp(tile.app, animate);
        else if (zone == TOP && !dockTiles.isEmpty()) showDockApp(dockTiles.get(dockIdx).app, animate);

        moveGlass(target, radius, trans, animate);
    }

    void moveGlass(final RectF to, final float toRadius, final float toTrans, boolean animate) {
        if (moveAnim != null) moveAnim.cancel();
        if (!animate || glassRect.isEmpty()) {
            glassRect.set(to); glassRadius = toRadius; curTrans = toTrans;
            content.setTranslationY(curTrans);
            applyGlass();
            return;
        }
        final RectF from = new RectF(glassRect);
        final float fromR = glassRadius, fromT = curTrans;
        moveAnim = ValueAnimator.ofFloat(0f, 1f);
        moveAnim.setDuration(440);
        moveAnim.setInterpolator(new Spring());
        moveAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                glassRect.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                        lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
                glassRadius = lerp(fromR, toRadius, Math.min(1f, t));
                // scrolling uses the same spring but never overshoots past the target
                float st = Math.min(1f, t);
                curTrans = lerp(fromT, toTrans, st);
                content.setTranslationY(curTrans);
                applyGlass();
            }
        });
        moveAnim.start();
    }

    void applyGlass() {
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) glass.getLayoutParams();
        int w = Math.round(glassRect.width()), h = Math.round(glassRect.height());
        if (l.width != w || l.height != h) { l.width = w; l.height = h; glass.setLayoutParams(l); }
        topScrim.setAlpha(Math.max(0f, Math.min(1f, -curTrans / px(70))));
        glass.setTranslationX(glassRect.left);
        glass.setTranslationY(glassRect.top);
        ((Glass) glass.getBackground()).radius = glassRadius;
        glass.invalidate();
    }

    String shownPkg;
    void showDockApp(App a, boolean animate) {
        if (a.pkg.equals(shownPkg)) return;
        shownPkg = a.pkg;
        appName.setText(a.label);
        if (animate) { appName.setAlpha(0f); appName.animate().alpha(1f).setDuration(220).start(); }
        // glow follows the tile horizontally and takes the banner's colour
        Tile t = dockTiles.get(dockIdx);
        float cx = px(t.x + TILE_W / 2) - px(480);
        int c = colorFor(a);
        if (animate) {
            glow.animate().translationX(cx).setDuration(500).setInterpolator(new DecelerateInterpolator()).start();
            if (glowAnim != null) glowAnim.cancel();
            glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, c);
            glowAnim.setDuration(500);
            glowAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator va) { setGlowColor((Integer) va.getAnimatedValue()); }
            });
            glowAnim.start();
        } else {
            glow.setTranslationX(cx);
            setGlowColor(c);
        }
    }

    void setGlowColor(int c) {
        glowColor = c;
        int inner = (c & 0x00FFFFFF) | 0x66000000;
        int mid = (c & 0x00FFFFFF) | 0x22000000;
        glowDrawable.setColors(new int[]{inner, mid, 0x00000000});
    }

    int colorFor(App a) {
        Integer cached = colorCache.get(a.pkg);
        if (cached != null) return cached;
        int out = 0xFF5A5A66;
        try {
            Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            Drawable d = a.banner.getConstantState() != null ? a.banner.getConstantState().newDrawable().mutate() : a.banner;
            d.setBounds(0, 0, 32, 18);
            d.draw(cv);
            float[] hsv = new float[3];
            double r = 0, g = 0, bl = 0, wsum = 0;
            for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double w = hsv[1] * hsv[1] * hsv[2] + 0.002;
                r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; wsum += w;
            }
            b.recycle();
            if (wsum > 0) {
                int avg = Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
                Color.colorToHSV(avg, hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.6f); hsv[2] = 0.9f; }
                else { hsv[1] = 0f; hsv[2] = 0.55f; }
                out = Color.HSVToColor(hsv);
            }
        } catch (Exception e) { }
        colorCache.put(a.pkg, out);
        return out;
    }

    // ------------------------------------------------------------------ actions

    void activate() {
        if (dropdownOpen) { pickInput(); return; }
        if (zone == TOP) {
            if (topIdx == P_INPUTS) openDropdown(P_INPUTS);
            else if (topIdx == P_WIFI) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else if (topIdx == P_AUDIO) openDropdown(P_AUDIO);
            else start(new Intent(Settings.ACTION_SETTINGS));
            return;
        }
        Tile t = zone == DOCK ? dockTiles.get(dockIdx) : gridTiles.get(gridIdx);
        Intent i = pm.getLeanbackLaunchIntentForPackage(t.app.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(t.app.pkg);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            i.setClassName(t.app.ri.activityInfo.packageName, t.app.ri.activityInfo.name);
        }
        start(i);
    }

    void appInfo() {
        if (zone == TOP || dropdownOpen) return;
        Tile t = zone == DOCK ? dockTiles.get(dockIdx) : gridTiles.get(gridIdx);
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + t.app.pkg)));
    }

    void start(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        catch (Exception e) { android.widget.Toast.makeText(this, "Can't open that", android.widget.Toast.LENGTH_SHORT).show(); }
    }

    // ------------------------------------------------------------------ inputs dropdown

    void loadInputs() {
        inputs.clear();
        ArrayList<TvInputInfo> hdmi = new ArrayList<TvInputInfo>();
        TvInputInfo av = null;
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput()) continue;
                if (info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
                else if (info.getType() == TvInputInfo.TYPE_COMPOSITE && av == null) av = info;
            }
        } catch (Exception e) { }
        Collections.sort(hdmi, new Comparator<TvInputInfo>() {
            public int compare(TvInputInfo a, TvInputInfo b) { return Long.compare(hwId(a.getId()), hwId(b.getId())); }
        });
        for (int i = 0; i < hdmi.size(); i++) {
            InputItem it = new InputItem();
            it.id = hdmi.get(i).getId();
            String l = customLabel(hdmi.get(i));
            it.label = (l == null || l.length() == 0 || l.equalsIgnoreCase("HDMI")) ? "HDMI " + (i + 1) : l.replaceAll("^HDMI(\\d)$", "HDMI $1");
            inputs.add(it);
        }
        if (av != null) { InputItem it = new InputItem(); it.id = av.getId(); it.label = "AV"; inputs.add(it); }
        InputItem live = new InputItem(); live.liveTv = true; live.label = "Live TV"; inputs.add(live);
    }

    String customLabel(TvInputInfo info) {
        try {
            Object o = TvInputInfo.class.getMethod("loadCustomLabel", Context.class).invoke(info, this);
            if (o != null && o.toString().length() > 0) return o.toString();
        } catch (Exception e) { }
        CharSequence l = info.loadLabel(this);
        return l == null ? null : l.toString();
    }

    static long hwId(String id) {
        int i = id.lastIndexOf("/HW");
        try { return i >= 0 ? Long.parseLong(id.substring(i + 3)) : Long.MAX_VALUE; } catch (Exception e) { return Long.MAX_VALUE; }
    }

    void loadAudioItems() {
        inputs.clear();
        int cur = audioIndex();
        for (int i = 0; i < AUDIO_NAMES.length; i++) {
            if (i == 2 && cur != 2) continue;      // ARC only listed while the TV reports it in use
            InputItem it = new InputItem();
            it.audio = i; it.current = i == cur;
            it.label = i == 1 ? "Optical (S/PDIF)" : AUDIO_NAMES[i];
            inputs.add(it);
        }
    }

    void openDropdown(int pillIdx) {
        dropdownPill = pillIdx;
        if (pillIdx == P_AUDIO) loadAudioItems(); else loadInputs();
        inputRows.clear();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(20));
        bg.setColor(0xFF161A28);
        bg.setStroke(Math.max(1, px(0.75f)), 0x2EFFFFFF);
        panel.setBackground(bg);
        panel.setPadding(px(6), px(6), px(6), px(6));
        for (int i = 0; i < inputs.size(); i++) {
            TextView row = text(16, fReg, 0xD9FFFFFF);
            row.setText(inputs.get(i).current ? inputs.get(i).label + "   ✓" : inputs.get(i).label);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(16), 0, px(16), 0);
            if (inputs.get(i).liveTv && i > 0) {
                View div = new View(this);
                div.setBackgroundColor(0x1FFFFFFF);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, px(0.75f)));
                dl.setMargins(px(12), px(4), px(12), px(4));
                panel.addView(div, dl);
            }
            panel.addView(row, new LinearLayout.LayoutParams(-1, px(40)));
            inputRows.add(row);
        }
        View p = pills[pillIdx];
        float[] o = offset(p, root);
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(px(pillIdx == P_AUDIO ? 220 : 200), -2);
        l.gravity = Gravity.TOP | Gravity.LEFT;
        l.leftMargin = Math.round(o[0]);
        l.topMargin = Math.round(o[1] + p.getHeight() + px(10));
        overlay.addView(panel, l);
        dropdown = panel;
        panel.setPivotX(px(30)); panel.setPivotY(0);
        panel.setAlpha(0f); panel.setScaleX(0.96f); panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
        dropdownOpen = true;
        inputIdx = 0;
        for (int i = 0; i < inputs.size(); i++) if (inputs.get(i).current) inputIdx = i;
        paintInputs();
    }

    void paintInputs() {
        for (int i = 0; i < inputRows.size(); i++) {
            TextView r = inputRows.get(i);
            boolean f = i == inputIdx;
            if (f) {
                GradientDrawable s = new GradientDrawable();
                s.setCornerRadius(px(14));
                s.setColor(0x33FFFFFF);
                s.setStroke(Math.max(1, px(0.75f)), 0x40FFFFFF);
                r.setBackground(s);
            } else r.setBackground(null);
            r.setTextColor(f ? 0xFFFFFFFF : 0xB3FFFFFF);
        }
    }

    void dropdownKey(int k) {
        if (k == KeyEvent.KEYCODE_DPAD_UP) { if (inputIdx > 0) inputIdx--; }
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) { if (inputIdx < inputRows.size() - 1) inputIdx++; }
        else { closeDropdown(); return; }
        paintInputs();
    }

    void closeDropdown() {
        if (!dropdownOpen) return;
        dropdownOpen = false;
        final View d = dropdown;
        dropdown = null;
        d.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() { public void run() { overlay.removeView(d); } }).start();
    }

    void pickInput() {
        InputItem it = inputs.get(inputIdx);
        closeDropdown();
        if (it.audio >= 0) { setAudioOutput(it.audio); return; }
        if (it.liveTv) {
            Intent i = pm.getLeanbackLaunchIntentForPackage("com.realtek.tv");
            if (i == null) i = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
            start(i);
        } else {
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.id));
            try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
            catch (Exception e) { i.setPackage("com.realtek.tv"); start(i); }
        }
    }

    // ------------------------------------------------------------------ status

    void updateGreeting() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : h < 22 ? "Good evening" : "Good night");
        // one wake-up at the next hour boundary, no polling
        handler.removeCallbacks(greetTick);
        long ms = (60 - c.get(Calendar.MINUTE)) * 60000L - c.get(Calendar.SECOND) * 1000L + 500;
        handler.postDelayed(greetTick, ms);
    }
    final Runnable greetTick = new Runnable() { public void run() { updateGreeting(); } };

    @SuppressWarnings("deprecation")
    void updateWifi() {
        String label = "Offline";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) {
                if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) label = "Ethernet";
                else {
                    label = "Wi-Fi";
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    WifiInfo wi = wm.getConnectionInfo();
                    String ssid = wi == null ? null : wi.getSSID();
                    if (ssid != null && !ssid.contains("unknown ssid")) label = ssid.replace("\"", "");
                }
            }
        } catch (Exception e) { }
        if (wifiText != null) wifiText.setText(label);
        if (zone == TOP && content.getWidth() > 0)
            root.post(new Runnable() { public void run() { focusCurrent(false); } });
    }

    final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { updateWifi(); }
    };
    final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { colorCache.clear(); loadApps(); }
    };

    void registerReceivers() {
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        reg(pkgReceiver, pf);
        IntentFilter nf = new IntentFilter();
        nf.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        nf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        reg(netReceiver, nf);
        IntentFilter bf = new IntentFilter();
        bf.addAction(Intent.ACTION_DATE_CHANGED);
        bf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        reg(sysReceiver, bf);
        getContentResolver().registerContentObserver(Settings.Global.getUriFor("speaker_index"), false, audioObserver);
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        reg(screenReceiver, sf);
        if (((android.os.PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) screenOn();
    }

    void reg(BroadcastReceiver r, IntentFilter f) {
        try {   // registerReceiver(r, f, RECEIVER_EXPORTED) – needed when targeting API 34
            Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class)
                    .invoke(this, r, f, 0x2);
        } catch (Exception e) { registerReceiver(r, f); }
    }

    // ------------------------------------------------------------------ top bar (pinned, not scrolled)

    void buildTopBar() {
        topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFA05080F, 0xF205080F, 0xB305080F, 0x0005080F}));
        topScrim.setAlpha(0f);
        root.addView(topScrim, new FrameLayout.LayoutParams(-1, px(124)));
        topBar = new FrameLayout(this);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, px(64)));

        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pills[P_INPUTS] = pill(P_INPUTS, new Icon(Icon.INPUT), "Inputs");
        pills[P_WIFI] = pill(P_WIFI, new Icon(Icon.WIFI), "Wi-Fi");
        pills[P_AUDIO] = pill(P_AUDIO, new Icon(Icon.AUDIO), "TV speaker");
        pills[P_SETTINGS] = pill(P_SETTINGS, new Icon(Icon.GEAR), null);
        wifiText = pillText[P_WIFI];
        audioText = pillText[P_AUDIO];
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, px(40));
        pl.gravity = Gravity.TOP | Gravity.LEFT;
        pl.topMargin = px(12); pl.leftMargin = px(SIDE);
        topBar.addView(pillRow, pl);

        // RAM gauge, far right, display only
        LinearLayout ram = new LinearLayout(this);
        ram.setOrientation(LinearLayout.HORIZONTAL);
        ram.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = cap("RAM");
        ram.addView(lab);
        ramBar = new Widgets.Bar(this, dp);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(px(96), px(6));
        bl.leftMargin = px(10); bl.rightMargin = px(10);
        ram.addView(ramBar, bl);
        ramText = text(13, fReg, 0xB3FFFFFF);
        ram.addView(ramText);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, px(40));
        rl.gravity = Gravity.TOP | Gravity.RIGHT;
        rl.topMargin = px(12); rl.rightMargin = px(SIDE);
        topBar.addView(ram, rl);
    }

    TextView cap(String s) {
        TextView t = text(10.5f, fMed, 0x80FFFFFF);
        t.setText(s.toUpperCase(Locale.US));
        t.setLetterSpacing(0.1f);
        t.setSingleLine(true);
        return t;
    }

    LinearLayout card(float x, float y, float w, float h) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(18));
        bg.setColor(0x12FFFFFF);
        bg.setStroke(Math.max(1, px(0.75f)), 0x1AFFFFFF);
        c.setBackground(bg);
        c.setPadding(px(14), px(11), px(14), px(10));
        content.addView(c, lp(w, h, x, y));
        return c;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    // ------------------------------------------------------------------ widgets (right side)

    void buildWidgets() {
        // weather
        LinearLayout w = card(WX, WY, WCOL, 80);
        FrameLayout wh = new FrameLayout(this);
        wPlace = cap(place);
        wh.addView(wPlace);
        wHiLo = cap("");
        FrameLayout.LayoutParams hl2 = new FrameLayout.LayoutParams(-2, -2);
        hl2.gravity = Gravity.RIGHT;
        wh.addView(wHiLo, hl2);
        w.addView(wh, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout wr = row();
        wIcon = new Widgets.WeatherIcon(dp);
        wIconView = new ImageView(this);
        wIconView.setImageDrawable(wIcon);
        wIconView.setAlpha(0.9f);
        wr.addView(wIconView, new LinearLayout.LayoutParams(px(26), px(26)));
        wTemp = text(28, fLight, 0xF2FFFFFF);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = px(8); tl.rightMargin = px(10);
        wr.addView(wTemp, tl);
        LinearLayout wc = new LinearLayout(this);
        wc.setOrientation(LinearLayout.VERTICAL);
        wCond = text(13, fReg, 0xD9FFFFFF); wCond.setSingleLine(true);
        wSub = text(11, fReg, 0x8CFFFFFF); wSub.setSingleLine(true);
        wc.addView(wCond); wc.addView(wSub);
        wr.addView(wc);
        LinearLayout.LayoutParams wrl = new LinearLayout.LayoutParams(-1, -2);
        wrl.topMargin = px(6);
        w.addView(wr, wrl);

        // nifty
        LinearLayout n = card(WX, WY + 88, WCOL, 84);
        FrameLayout nh = new FrameLayout(this);
        nh.addView(cap("Nifty 50"));
        nStatus = cap("");
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2);
        sl.gravity = Gravity.RIGHT;
        nh.addView(nStatus, sl);
        n.addView(nh, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout nr = row();
        nVal = text(22, fLight, 0xF2FFFFFF);
        nr.addView(nVal);
        nChg = text(13, fMed, 0xB3FFFFFF);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = px(10);
        nr.addView(nChg, cl);
        LinearLayout.LayoutParams nrl = new LinearLayout.LayoutParams(-1, -2);
        nrl.topMargin = px(5);
        n.addView(nr, nrl);
        spark = new Widgets.Sparkline(this, dp);
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(-1, px(18));
        spl.topMargin = px(5);
        n.addView(spark, spl);

        // screen time
        LinearLayout st = card(WX, WY + 180, WCOL, WBOTTOM - WY - 180);
        st.addView(cap("Screen time · daily avg"));
        LinearLayout sr = row();
        stVal = text(22, fLight, 0xF2FFFFFF);
        sr.addView(stVal);
        stSub = text(11.5f, fReg, 0x8CFFFFFF);
        LinearLayout.LayoutParams ssl = new LinearLayout.LayoutParams(-2, -2);
        ssl.leftMargin = px(10);
        sr.addView(stSub, ssl);
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, -2);
        srl.topMargin = px(5);
        st.addView(sr, srl);

        // calendar
        LinearLayout cal = card(CALX, WY, CALW, WBOTTOM - WY);
        month = new Widgets.MonthView(this, dp, fReg, fMed, fBold);
        cal.addView(month, new LinearLayout.LayoutParams(-1, -1));

        wTemp.setText("—"); wCond.setText("Loading…"); wSub.setText("");
        nVal.setText("—"); nChg.setText(""); nStatus.setText("");
        stVal.setText("—"); stSub.setText("");
    }

    void showCachedWidgets() {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor ed = prefs.edit().remove("n_json");          // old full-JSON cache (v1.2)
        if (now - prefs.getLong("w_at", 0) > DAY || !hasLocation() || !locKey().equals(prefs.getString("w_loc", "")))
            ed.remove("w_json").remove("w_at").remove("w_loc");             // stale, no location, or the location changed
        if (now - prefs.getLong("n_at", 0) > DAY) ed.remove("n_cache").remove("n_at");
        ed.apply();
        String wj = prefs.getString("w_json", null);
        if (wj != null) showWeather(wj);
        String nc = prefs.getString("n_cache", null);
        if (nc != null) try { showQuote(Widgets.decodeQuote(nc)); } catch (Exception e) { }
        if (prefs.contains("st_avg")) showScreenTime(prefs.getLong("st_avg", 0), prefs.getInt("st_days", 7));
    }

    /** Optional files/widgets.txt: lat=…, lon=…, place=… (one per line). */
    void readWidgetConfig() {
        File f = new File(getExternalFilesDir(null), "widgets.txt");
        if (!f.exists()) return;
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0 || line.trim().startsWith("#")) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                if (k.equals("lat")) lat = Double.parseDouble(v);
                else if (k.equals("lon")) lon = Double.parseDouble(v);
                else if (k.equals("place")) place = v;
            }
            r.close();
        } catch (Exception e) { }
    }

    boolean hasLocation() { return !Double.isNaN(lat) && !Double.isNaN(lon); }

    /** Identifies the cached weather's location, so a new widgets.txt doesn't show the old place's weather. */
    String locKey() { return hasLocation() ? lat + "," + lon : ""; }

    // ------------------------------------------------------------------ RAM (every 5 s while visible, local only)

    final Runnable ramTick = new Runnable() {
        public void run() {
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long used = mi.totalMem - mi.availMem;
                ramBar.set(used / (float) mi.totalMem);
                ramText.setText(String.format(Locale.US, "%.1f / %.1f GB", used / 1073741824.0, mi.totalMem / 1073741824.0));
            } catch (Exception e) { }
            if (visible) handler.postDelayed(this, 5000);
        }
    };

    // ------------------------------------------------------------------ audio output pill
    // The TV keeps the chosen output in Settings.Global "speaker_index" (0 TV speaker, 1 S/PDIF optical,
    // 2 HDMI ARC). Changing it needs a system app, so we ask Realtek's own receiver
    // (com.realtek.slices/.OutputDeviceReceiver), which toggles TV speaker <-> optical and calls the
    // audio service. Optical is one-way: whether a speaker is attached and on cannot be detected.
    static final String[] AUDIO_NAMES = {"TV speaker", "Optical", "HDMI ARC"};

    int audioIndex() {
        try { return Settings.Global.getInt(getContentResolver(), "speaker_index", 0); }
        catch (Exception e) { return 0; }
    }

    void updateAudioPill() {
        int i = audioIndex();
        setPillText(audioText, i >= 0 && i < AUDIO_NAMES.length ? AUDIO_NAMES[i] : "Audio");
    }

    void setPillText(TextView t, String s) {
        if (t == null || s.equals(t.getText().toString())) return;
        t.setText(s);
        if (zone == TOP && content.getWidth() > 0)
            root.post(new Runnable() { public void run() { focusCurrent(false); } });
    }

    final ContentObserver audioObserver = new ContentObserver(handler) {
        public void onChange(boolean self) { updateAudioPill(); }
    };

    void setAudioOutput(final int target) {
        if (target == 2) { start(new Intent(Settings.ACTION_SETTINGS)); return; }   // ARC needs HDMI-CEC calls: use TV settings
        final int[] tries = {0};
        handler.post(new Runnable() {
            public void run() {
                if (audioIndex() == target) { updateAudioPill(); return; }
                if (tries[0]++ >= 2) {
                    android.widget.Toast.makeText(HomeActivity.this, "Couldn't switch audio output", android.widget.Toast.LENGTH_SHORT).show();
                    updateAudioPill();
                    return;
                }
                try {
                    Intent b = new Intent("com.realtek.device_output_key");
                    b.setClassName("com.realtek.slices", "com.realtek.slices.OutputDeviceReceiver");
                    sendBroadcast(b);
                } catch (Exception e) { android.util.Log.w("GlassHome", "audio toggle failed: " + e); }
                handler.postDelayed(this, 900);
            }
        });
    }

    final BroadcastReceiver sysReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { month.invalidate(); }
    };

    // ------------------------------------------------------------------ weather: hourly

    static final long HOUR = 3600000L;
    final Runnable weatherTick = new Runnable() { public void run() { maybeFetchWeather(); } };

    void maybeFetchWeather() {
        handler.removeCallbacks(weatherTick);
        if (!hasLocation()) { wCond.setText("No location"); wSub.setText("Set it in widgets.txt"); return; }
        long age = System.currentTimeMillis() - prefs.getLong("w_at", 0);
        if (age >= HOUR) fetchWeather();
        else if (visible) handler.postDelayed(weatherTick, HOUR - age);
    }

    void fetchWeather() {
        net.post(new Runnable() {
            public void run() {
                try {
                    final String js = Widgets.get(Widgets.weatherUrl(lat, lon));
                    Widgets.parseWeather(js);   // validate before caching
                    handler.post(new Runnable() {
                        public void run() {
                            prefs.edit().putString("w_json", js).putLong("w_at", System.currentTimeMillis())
                                    .putString("w_loc", locKey()).apply();
                            showWeather(js);
                            if (visible) handler.postDelayed(weatherTick, HOUR);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        public void run() {
                            if (!prefs.contains("w_json")) { wCond.setText("Unavailable"); wSub.setText("Retrying in 10 min"); }
                            if (visible) handler.postDelayed(weatherTick, 10 * 60000L);
                        }
                    });
                }
            }
        });
    }

    void showWeather(String js) {
        try {
            Widgets.Weather w = Widgets.parseWeather(js);
            wTemp.setText(Math.round(w.temp) + "°");
            wCond.setText(Widgets.weatherText(w.code));
            wSub.setText("Feels " + Math.round(w.feels) + "°");
            wHiLo.setText("H " + Math.round(w.hi) + "°  L " + Math.round(w.lo) + "°");
            wIcon.code = w.code; wIcon.day = w.day;
            wIconView.invalidate();
        } catch (Exception e) { }
    }

    // ------------------------------------------------------------------ Nifty: every minute in market hours

    final Runnable niftyTick = new Runnable() {
        public void run() {
            boolean open = Widgets.marketOpen();
            long age = System.currentTimeMillis() - prefs.getLong("n_at", 0);
            // outside market hours fetch at most every 6 h, just to pick up the close
            if (open || age > 6 * HOUR || !prefs.contains("n_cache")) fetchNifty();
            else showNiftyStatus();
            if (visible) handler.postDelayed(this, open ? 60000L : 5 * 60000L);
        }
    };

    void fetchNifty() {
        net.post(new Runnable() {
            public void run() {
                try {
                    final Widgets.Quote q = Widgets.parseQuote(Widgets.get(Widgets.NIFTY_URL));
                    handler.post(new Runnable() {
                        public void run() {
                            prefs.edit().putString("n_cache", Widgets.encodeQuote(q)).putLong("n_at", System.currentTimeMillis()).apply();
                            showQuote(q);
                        }
                    });
                } catch (Exception e) {
                    handler.post(new Runnable() {
                        public void run() { if (!prefs.contains("n_cache")) { nVal.setText("—"); nStatus.setText("UNAVAILABLE"); } }
                    });
                }
            }
        });
    }

    long niftyTime;
    void showQuote(Widgets.Quote q) {
        try {
            nVal.setText(String.format(Locale.US, "%,.2f", q.price));
            double ch = q.price - q.prevClose, pct = q.prevClose == 0 ? 0 : ch * 100 / q.prevClose;
            nChg.setText(String.format(Locale.US, "%s %.2f%%", ch >= 0 ? "▲" : "▼", Math.abs(pct)));
            nChg.setTextColor(ch >= 0 ? 0xFF34D399 : 0xFFF87171);
            spark.set(q.series, (float) q.prevClose);
            niftyTime = q.marketTime;
            showNiftyStatus();
        } catch (Exception e) { }
    }

    void showNiftyStatus() {
        String t = niftyTime > 0 ? new java.text.SimpleDateFormat("H:mm", Locale.US).format(new java.util.Date(niftyTime)) : "";
        nStatus.setText((Widgets.marketOpen() ? "LIVE " : "CLOSED ") + t);
    }

    // ------------------------------------------------------------------ screen time: once a day, 7-day daily average

    static final long DAY = 86400000L;

    String dayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    void maybeComputeScreenTime() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean have = prefs.contains("st_avg");
        if (!dayKey().equals(prefs.getString("st_day", "")) && (h >= 6 || !have)) computeScreenTime();
        else if (!have) { stVal.setText("—"); stSub.setText("tracking since today"); }
    }

    // Android's usage history on this TV is unusable (its buckets are dated 2027 after a clock glitch),
    // so Glass Home keeps its own log: screen-on spans from SCREEN_ON/OFF broadcasts, summed per day.
    static String dayKey(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    static long midnight(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Credit [from, to) to per-day totals, splitting at midnights. */
    void addScreenSpan(long from, long to) {
        if (from <= 0 || to <= from || to - from > 2 * DAY) return;   // ignore nonsense (clock jumps)
        SharedPreferences.Editor ed = prefs.edit();
        long t = from;
        while (t < to) {
            long next = Math.min(to, midnight(t) + DAY);
            String k = "std_" + dayKey(t);
            ed.putLong(k, prefs.getLong(k, 0) + (next - t));
            t = next;
        }
        ed.apply();
    }

    void screenOn() {
        long now = System.currentTimeMillis();
        if (prefs.getLong("st_on", 0) == 0) prefs.edit().putLong("st_on", now).apply();
        if (!prefs.contains("st_start")) prefs.edit().putLong("st_start", now).apply();
    }

    void screenOff() {
        long now = System.currentTimeMillis(), on = prefs.getLong("st_on", 0);
        addScreenSpan(on, now);
        prefs.edit().putLong("st_on", 0).apply();
    }

    final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) screenOn(); else screenOff();
        }
    };

    void computeScreenTime() {
        long now = System.currentTimeMillis(), today = midnight(now);
        // close any span still open from before midnight so yesterday is complete
        long on = prefs.getLong("st_on", 0);
        if (on > 0 && on < today) { addScreenSpan(on, today); prefs.edit().putLong("st_on", today).apply(); }
        long start = prefs.getLong("st_start", now);
        int days = (int) Math.min(7, (today - midnight(start)) / DAY);   // complete days tracked
        if (days <= 0) { stVal.setText("—"); stSub.setText("tracking since today"); return; }
        long total = 0;
        for (int d = 1; d <= days; d++) total += prefs.getLong("std_" + dayKey(today - d * DAY + 3600000L), 0);
        long avg = total / days;
        // keep today + the last 7 days only
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : new ArrayList<String>(prefs.getAll().keySet()))
            if (k.startsWith("std_")) {
                boolean keep = false;
                for (int d = 0; d <= 7; d++) if (k.equals("std_" + dayKey(today - d * DAY + 3600000L))) keep = true;
                if (!keep) ed.remove(k);
            }
        ed.putLong("st_avg", avg).putInt("st_days", days).putString("st_day", dayKey()).apply();
        showScreenTime(avg, days);
    }

    void showScreenTime(long avg, int days) {
        long m = avg / 60000L;
        stVal.setText(m >= 60 ? (m / 60) + "h " + String.format(Locale.US, "%02d", m % 60) + "m" : m + "m");
        stSub.setText(days >= 7 ? "last 7 days" : "last " + days + (days == 1 ? " day" : " days"));
    }


    // ------------------------------------------------------------------ helpers

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

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

    /** Lays out its single child at full width and natural (unbounded) height; scrolling = translationY. */
    static class Pane extends ViewGroup {
        Pane(Context c) { super(c); setClipChildren(false); }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                int hs = lp != null && lp.height > 0 ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(h), MeasureSpec.EXACTLY);
                c.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY), hs);
            }
        }
        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                c.layout(0, 0, c.getMeasuredWidth(), c.getMeasuredHeight());
            }
        }
    }

    /** Damped spring: overshoots ~8% then settles. */
    static class Spring implements TimeInterpolator {
        public float getInterpolation(float t) { return (float) (1 - Math.exp(-7 * t) * Math.cos(9 * t)); }
    }

    /** The translucent glass highlight: faint fill, top sheen, bright hairline edge. No blur. */
    class Glass extends Drawable {
        float radius = px(13);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
                edge = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        Glass() {
            fill.setColor(0x1AFFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(px(1.5f));
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(px(5f));
            halo.setColor(0x14FFFFFF);
        }
        @Override public void draw(Canvas c) {
            r.set(getBounds());
            RectF hr = new RectF(r); hr.inset(-px(2.5f), -px(2.5f));
            c.drawRoundRect(hr, radius + px(2.5f), radius + px(2.5f), halo);
            r.inset(px(0.75f), px(0.75f));
            c.drawRoundRect(r, radius, radius, fill);
            sheen.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, radius, radius, sheen);
            edge.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xB3FFFFFF, 0x40FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, radius, radius, edge);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Small line icons for the pills. */
    class Icon extends Drawable {
        static final int INPUT = 0, WIFI = 1, GEAR = 2, AUDIO = 3;
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
            if (kind == INPUT) {
                c.drawRoundRect(new RectF(4 * u, 3 * u, 16.5f * u, 13 * u), 2 * u, 2 * u, p);
                c.drawLine(7 * u, 16 * u, 13.5f * u, 16 * u, p);
                c.drawLine(1.5f * u, 8 * u, 9.5f * u, 8 * u, p);
                Path a = new Path();
                a.moveTo(7 * u, 5.5f * u); a.lineTo(9.5f * u, 8 * u); a.lineTo(7 * u, 10.5f * u);
                c.drawPath(a, p);
            } else if (kind == WIFI) {
                float cx = 9 * u, cy = 15 * u;
                for (int i = 1; i <= 3; i++) {
                    float rr = i * 4f * u;
                    c.drawArc(new RectF(cx - rr, cy - rr, cx + rr, cy + rr), 225, 90, false, p);
                }
                Paint dot = new Paint(p); dot.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, 1.2f * u, dot);
            } else if (kind == AUDIO) {
                Path b = new Path();
                b.moveTo(2.5f * u, 7 * u); b.lineTo(5.5f * u, 7 * u); b.lineTo(9.5f * u, 3.5f * u);
                b.lineTo(9.5f * u, 14.5f * u); b.lineTo(5.5f * u, 11 * u); b.lineTo(2.5f * u, 11 * u); b.close();
                c.drawPath(b, p);
                c.drawArc(new RectF(8.5f * u, 6 * u, 14.5f * u, 12 * u), -50, 100, false, p);
                c.drawArc(new RectF(8 * u, 3 * u, 17.5f * u, 15 * u), -50, 100, false, p);
            } else {
                float cx = 9 * u, cy = 9 * u;
                c.drawCircle(cx, cy, 5f * u, p);
                c.drawCircle(cx, cy, 2.2f * u, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    c.drawLine(cx + (float) Math.cos(a) * 5.2f * u, cy + (float) Math.sin(a) * 5.2f * u,
                            cx + (float) Math.cos(a) * 7.6f * u, cy + (float) Math.sin(a) * 7.6f * u, p);
                }
            }
            c.restore();
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
