package com.madhu.glasslauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * Launcher Settings Activity.
 * Minimalist, remote-friendly Apple TV style settings screen.
 */
public class SettingsActivity extends Activity {

    float dp;
    Typeface fThin, fLight, fReg, fMed, fBold;
    SharedPreferences prefs;

    LinearLayout menuList;
    final ArrayList<SettingItem> items = new ArrayList<SettingItem>();
    final ArrayList<View> itemViews = new ArrayList<View>();
    int selectedIdx = 0;

    static class SettingItem {
        String title, subtitle;
        Runnable action;
        SettingItem(String t, String s, Runnable a) { title = t; subtitle = s; action = a; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        fThin = font("outfit_200.ttf"); fLight = font("outfit_300.ttf");
        fReg = font("outfit_400.ttf"); fMed = font("outfit_500.ttf"); fBold = font("outfit_600.ttf");

        buildUi();
    }

    Typeface font(String n) {
        try { return Typeface.createFromAsset(getAssets(), "fonts/" + n); }
        catch (Exception e) { return Typeface.create("sans-serif-light", Typeface.NORMAL); }
    }

    int px(float d) { return Math.round(d * dp); }

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0E1626, 0xFF080D1A, 0xFF03050A, 0xFF000000});
        root.setBackground(bg);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(px(48), px(36), px(48), px(36));

        // Header
        TextView title = new TextView(this);
        title.setText("Launcher Settings");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        title.setTypeface(fMed);
        title.setTextColor(0xFFFFFFFF);
        layout.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Customize clocks, remote photo storage, and app shelf");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sub.setTypeface(fLight);
        sub.setTextColor(0x99FFFFFF);
        LinearLayout.LayoutParams subl = new LinearLayout.LayoutParams(-2, -2);
        subl.topMargin = px(4);
        subl.bottomMargin = px(24);
        layout.addView(sub, subl);

        // Menu items
        menuList = new LinearLayout(this);
        menuList.setOrientation(LinearLayout.VERTICAL);
        layout.addView(menuList, new LinearLayout.LayoutParams(-1, -2));

        loadItems();
        renderItems();

        root.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    void loadItems() {
        items.clear();

        // 1. Dual Clock Format
        boolean is24 = prefs.getBoolean("clock_24h", false);
        items.add(new SettingItem("Clock Format", is24 ? "24-Hour (16:15)" : "12-Hour (4:15 PM)", new Runnable() {
            public void run() {
                boolean cur = prefs.getBoolean("clock_24h", false);
                prefs.edit().putBoolean("clock_24h", !cur).apply();
                loadItems();
                renderItems();
                Toast.makeText(SettingsActivity.this, "Clock format changed", Toast.LENGTH_SHORT).show();
            }
        }));

        // 2. Dual Timezone Status
        items.add(new SettingItem("Dual Timezones", "CST (America/Chicago)  •  IST (Asia/Kolkata) below CST", new Runnable() {
            public void run() {
                Toast.makeText(SettingsActivity.this, "CST and IST clocks active on home screen", Toast.LENGTH_SHORT).show();
            }
        }));

        // 3. Inactivity Auto-Return to Home (Screensaver readiness)
        items.add(new SettingItem("Inactivity Return to Home (5 min)", "Auto-returns to Home when idle without media, enabling photo screensaver", new Runnable() {
            public void run() {
                Toast.makeText(SettingsActivity.this, "5-min idle watchdog active for ambient photos screensaver", Toast.LENGTH_SHORT).show();
            }
        }));

        // 4. WebDAV / HTTP Storage Status
        File wpJpg = new File(getExternalFilesDir(null), "wallpaper.jpg");
        File wpPng = new File(getExternalFilesDir(null), "wallpaper.png");
        boolean hasWp = wpJpg.exists() || wpPng.exists();
        String wpStatus = hasWp ? "Custom wallpaper loaded" : "Place wallpaper.jpg in files/ or configure WebDAV";
        items.add(new SettingItem("Photo & WebDAV Storage", wpStatus, new Runnable() {
            public void run() {
                Toast.makeText(SettingsActivity.this, "WebDAV cleartext enabled. Path: " + getExternalFilesDir(null), Toast.LENGTH_LONG).show();
            }
        }));

        // 5. Reset App Shelf Order
        items.add(new SettingItem("Reset App Shelf Order", "Restores original dock and alphabetical app order", new Runnable() {
            public void run() {
                try {
                    new File(getExternalFilesDir(null), "apps_order.txt").delete();
                    new File(getExternalFilesDir(null), "dock.txt").delete();
                    new File(getExternalFilesDir(null), "grid.txt").delete();
                } catch (Exception e) { }
                Toast.makeText(SettingsActivity.this, "App order reset", Toast.LENGTH_SHORT).show();
            }
        }));

        // 6. Open Android TV System Settings
        items.add(new SettingItem("Android TV System Settings", "Network, display, device preferences, and remotes", new Runnable() {
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(SettingsActivity.this, "Can't open TV settings", Toast.LENGTH_SHORT).show();
                }
            }
        }));
    }

    void renderItems() {
        menuList.removeAllViews();
        itemViews.clear();

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final SettingItem item = items.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(px(20), px(12), px(20), px(12));

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(14));
            bg.setColor(i == selectedIdx ? 0x3338BDF8 : 0x14FFFFFF);
            if (i == selectedIdx) {
                bg.setStroke(px(1.5f), 0xFF38BDF8);
            }
            row.setBackground(bg);

            TextView t = new TextView(this);
            t.setText(item.title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTypeface(fMed);
            t.setTextColor(i == selectedIdx ? 0xFFFFFFFF : 0xE6FFFFFF);
            row.addView(t);

            TextView s = new TextView(this);
            s.setText(item.subtitle);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setTypeface(fLight);
            s.setTextColor(i == selectedIdx ? 0xCCFFFFFF : 0x80FFFFFF);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = px(3);
            row.addView(s, sl);

            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = px(10);
            menuList.addView(row, rl);
            itemViews.add(row);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();

        if (k == KeyEvent.KEYCODE_DPAD_UP) {
            if (selectedIdx > 0) {
                selectedIdx--;
                renderItems();
            }
            return true;
        } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (selectedIdx < items.size() - 1) {
                selectedIdx++;
                renderItems();
            }
            return true;
        } else if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (selectedIdx >= 0 && selectedIdx < items.size()) {
                items.get(selectedIdx).action.run();
            }
            return true;
        } else if (k == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
