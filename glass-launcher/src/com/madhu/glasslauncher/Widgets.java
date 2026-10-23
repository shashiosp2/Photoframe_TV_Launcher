package com.madhu.glasslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

/** Small, dependency-free pieces used by the home-screen widgets. */
final class Widgets {
    private Widgets() { }

    // ------------------------------------------------------------------ network

    /** Plain HTTPS GET, 10 s timeouts. Call off the main thread. */
    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) GlassHome/1.2");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } finally { c.disconnect(); }
    }

    // ------------------------------------------------------------------ weather (Open-Meteo)

    static final class Weather {
        double temp, feels, hi, lo; int code; boolean day; long fetched;
    }

    static String weatherUrl(double lat, double lon) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min&timezone=Asia%2FKolkata&forecast_days=1";
    }

    static Weather parseWeather(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONObject cur = o.getJSONObject("current");
        JSONObject d = o.getJSONObject("daily");
        Weather w = new Weather();
        w.temp = cur.getDouble("temperature_2m");
        w.feels = cur.getDouble("apparent_temperature");
        w.code = cur.getInt("weather_code");
        w.day = cur.optInt("is_day", 1) == 1;
        w.hi = d.getJSONArray("temperature_2m_max").getDouble(0);
        w.lo = d.getJSONArray("temperature_2m_min").getDouble(0);
        return w;
    }

    static String weatherText(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    // ------------------------------------------------------------------ Nifty 50 (Yahoo chart API, unofficial)

    static final String NIFTY_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?interval=1m&range=1d";

    static final class Quote {
        double price, prevClose; float[] series; long marketTime; long fetched;
    }

    static Quote parseQuote(String json) throws Exception {
        JSONObject res = new JSONObject(json).getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject meta = res.getJSONObject("meta");
        Quote q = new Quote();
        q.price = meta.getDouble("regularMarketPrice");
        q.prevClose = meta.has("chartPreviousClose") ? meta.getDouble("chartPreviousClose") : meta.optDouble("previousClose", q.price);
        q.marketTime = meta.optLong("regularMarketTime", 0) * 1000L;
        float[] s = new float[0];
        try {
            JSONArray close = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close");
            float[] tmp = new float[close.length()];
            int n = 0;
            for (int i = 0; i < close.length(); i++) {
                if (close.isNull(i)) continue;
                double v = close.optDouble(i, Double.NaN);
                if (!Double.isNaN(v)) tmp[n++] = (float) v;
            }
            s = new float[n];
            System.arraycopy(tmp, 0, s, 0, n);
        } catch (Exception ignored) { }
        q.series = s;
        return q;
    }

    static String encodeQuote(Quote q) {
        StringBuilder sb = new StringBuilder();
        sb.append(q.price).append('|').append(q.prevClose).append('|').append(q.marketTime).append('|');
        for (int i = 0; i < q.series.length; i++) { if (i > 0) sb.append(','); sb.append(q.series[i]); }
        return sb.toString();
    }

    static Quote decodeQuote(String s) {
        String[] p = s.split("\\|", -1);
        Quote q = new Quote();
        q.price = Double.parseDouble(p[0]); q.prevClose = Double.parseDouble(p[1]); q.marketTime = Long.parseLong(p[2]);
        if (p[3].length() == 0) { q.series = new float[0]; return q; }
        String[] v = p[3].split(",");
        q.series = new float[v.length];
        for (int i = 0; i < v.length; i++) q.series[i] = Float.parseFloat(v[i]);
        return q;
    }

    /** NSE cash session, Mon–Fri 09:15–15:30 IST (holidays not accounted for). */
    static boolean marketOpen() {
        Calendar c = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return m >= 9 * 60 + 15 && m <= 15 * 60 + 30;
    }

    // ------------------------------------------------------------------ views

    /** Month grid, Monday first, today in a white disc. Pure canvas drawing. */
    static final class MonthView extends View {
        final float dp;
        final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG), dow = new Paint(Paint.ANTI_ALIAS_FLAG),
                num = new Paint(Paint.ANTI_ALIAS_FLAG), disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Typeface reg, bold;
        static final String[] DOW = {"S", "M", "T", "W", "T", "F", "S"};
        static final String[] MONTH = {"January", "February", "March", "April", "May", "June", "July",
                "August", "September", "October", "November", "December"};

        MonthView(Context c, float dp, Typeface reg, Typeface med, Typeface bold) {
            super(c);
            this.dp = dp;
            this.reg = reg; this.bold = bold;
            title.setTypeface(med); title.setColor(0xF2FFFFFF); title.setTextSize(15 * dp);
            dow.setTypeface(med); dow.setColor(0x73FFFFFF); dow.setTextSize(10 * dp); dow.setTextAlign(Paint.Align.CENTER);
            num.setTypeface(reg); num.setTextSize(12.5f * dp); num.setTextAlign(Paint.Align.CENTER);
            disc.setColor(0xF2FFFFFF);
        }

        @Override protected void onDraw(Canvas cv) {
            Calendar now = Calendar.getInstance();
            int year = now.get(Calendar.YEAR), month = now.get(Calendar.MONTH), today = now.get(Calendar.DAY_OF_MONTH);
            float w = getWidth();
            cv.drawText(MONTH[month] + " " + year, 0, 15 * dp, title);
            float cell = w / 7f, top = 36 * dp, rowH = 27 * dp;
            for (int i = 0; i < 7; i++) {
                dow.setColor(i == 0 || i == 6 ? 0x59FFFFFF : 0x99FFFFFF);
                cv.drawText(DOW[i], cell * i + cell / 2, top, dow);
            }
            Calendar c = Calendar.getInstance();
            c.set(year, month, 1);
            int first = c.get(Calendar.DAY_OF_WEEK) - 1;   // Sunday = 0
            int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
            for (int d = 1; d <= days; d++) {
                int idx = first + d - 1, col = idx % 7, row = idx / 7;
                float cx = cell * col + cell / 2, cy = top + 12 * dp + row * rowH + rowH / 2;
                boolean weekend = col == 0 || col == 6;
                num.setTypeface(weekend ? reg : bold);
                if (d == today) {
                    cv.drawCircle(cx, cy - 4.2f * dp, 11.5f * dp, disc);
                    num.setColor(0xFF05070D);
                } else num.setColor(weekend ? 0x73FFFFFF : 0xF2FFFFFF);
                cv.drawText(String.valueOf(d), cx, cy, num);
            }
        }
    }

    /** Intraday line with a soft fill. Green when above previous close, red below. */
    static final class Sparkline extends View {
        final float dp;
        float[] data = new float[0];
        float base = Float.NaN;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                baseLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        Sparkline(Context c, float dp) {
            super(c);
            this.dp = dp;
            line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(1.5f * dp);
            line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeCap(Paint.Cap.ROUND);
            baseLine.setColor(0x26FFFFFF); baseLine.setStrokeWidth(1);
        }
        void set(float[] d, float prevClose) { data = d; base = prevClose; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            if (data.length < 2) return;
            float w = getWidth(), h = getHeight(), pad = 2 * dp;
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (float v : data) { min = Math.min(min, v); max = Math.max(max, v); }
            float range = Math.max(max - min, 1e-3f);
            // NSE session is 375 one-minute bars; draw partial days left-aligned
            float n = Math.max(data.length - 1, 374);
            Path p = new Path();
            for (int i = 0; i < data.length; i++) {
                float x = w * i / n, y = pad + (h - 2 * pad) * (1 - (data[i] - min) / range);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            boolean up = Float.isNaN(base) || data[data.length - 1] >= base;
            int col = up ? 0xFF34D399 : 0xFFF87171;
            if (!Float.isNaN(base) && base >= min && base <= max) {
                float by = pad + (h - 2 * pad) * (1 - (base - min) / range);
                cv.drawLine(0, by, w, by, baseLine);
            }
            Path f = new Path(p);
            f.lineTo(w * (data.length - 1) / n, h); f.lineTo(0, h); f.close();
            fill.setShader(new LinearGradient(0, 0, 0, h, (col & 0x00FFFFFF) | 0x40000000, col & 0x00FFFFFF, Shader.TileMode.CLAMP));
            cv.drawPath(f, fill);
            line.setColor(col);
            cv.drawPath(p, line);
        }
    }

    /** Thin rounded usage bar for the RAM gauge. */
    static final class Bar extends View {
        float frac;
        final float dp;
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bar(Context c, float dp) { super(c); this.dp = dp; track.setColor(0x26FFFFFF); }
        void set(float f) { frac = Math.max(0, Math.min(1, f)); invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            cv.drawRoundRect(new RectF(0, 0, w, h), r, r, track);
            fillP.setColor(frac > 0.85f ? 0xFFFBBF24 : 0xD9FFFFFF);
            if (frac > 0) cv.drawRoundRect(new RectF(0, 0, Math.max(h, w * frac), h), r, r, fillP);
        }
    }

    /** Line-art weather glyphs: sun, moon, cloud, rain, storm, fog. */
    static final class WeatherIcon extends Drawable {
        int code; boolean day = true;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        WeatherIcon(float dp) {
            p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f * dp);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas c) {
            float u = getBounds().width() / 24f;
            c.save(); c.translate(getBounds().left, getBounds().top);
            boolean clear = code == 0, partly = code == 1 || code == 2;
            if (clear || partly) {
                float cx = partly ? 9 * u : 12 * u, cy = partly ? 9 * u : 12 * u, r = partly ? 3.5f * u : 4.5f * u;
                if (day) {
                    c.drawCircle(cx, cy, r, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * i / 4;
                        c.drawLine(cx + (float) Math.cos(a) * (r + 2 * u), cy + (float) Math.sin(a) * (r + 2 * u),
                                cx + (float) Math.cos(a) * (r + 3.6f * u), cy + (float) Math.sin(a) * (r + 3.6f * u), p);
                    }
                } else {
                    float R = r + u;
                    Path m = new Path();
                    m.addCircle(cx, cy, R, Path.Direction.CW);
                    Path bite = new Path();
                    bite.addCircle(cx + 0.55f * R, cy - 0.35f * R, 0.85f * R, Path.Direction.CW);
                    m.op(bite, Path.Op.DIFFERENCE);
                    c.drawPath(m, p);
                }
                if (clear) { c.restore(); return; }
            }
            // cloud
            Path cl = new Path();
            float by = partly ? 19 * u : 16 * u;
            cl.moveTo(6 * u, by);
            cl.lineTo(18 * u, by);
            cl.arcTo(new RectF(15 * u, by - 7 * u, 22 * u, by), 90, -180);
            cl.arcTo(new RectF(9 * u, by - 11 * u, 18 * u, by - 2 * u), -20, -150);
            cl.arcTo(new RectF(3 * u, by - 7 * u, 10 * u, by), 270, -180);
            c.drawPath(cl, p);
            if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
                for (int i = 0; i < 3; i++) c.drawLine((8 + 4 * i) * u, 19 * u, (7 + 4 * i) * u, 22 * u, p);
            } else if (code >= 95) {
                Path b = new Path();
                b.moveTo(13 * u, 17 * u); b.lineTo(10.5f * u, 20.5f * u); b.lineTo(13.5f * u, 20.5f * u); b.lineTo(11 * u, 23.5f * u);
                c.drawPath(b, p);
            } else if (code == 45 || code == 48) {
                c.drawLine(5 * u, 19.5f * u, 19 * u, 19.5f * u, p);
                c.drawLine(7 * u, 22.5f * u, 17 * u, 22.5f * u, p);
            }
            c.restore();
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
