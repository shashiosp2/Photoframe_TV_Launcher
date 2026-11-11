package com.madhu.glasslauncher;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class PhotoDatabase {
    private static final String TAG = "PhotoDatabase";
    private static final String DB_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/.launcher.db";

    private static PhotoDatabase instance;
    private SQLiteDatabase db;

    private PhotoDatabase() {
        initDb();
    }

    public static synchronized PhotoDatabase getInstance() {
        if (instance == null) {
            instance = new PhotoDatabase();
        }
        return instance;
    }

    private synchronized void initDb() {
        try {
            File f = new File(DB_PATH);
            db = SQLiteDatabase.openOrCreateDatabase(f, null);
            db.execSQL("CREATE TABLE IF NOT EXISTS photos ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "url TEXT UNIQUE, "
                    + "shown_count INTEGER DEFAULT 0, "
                    + "last_shown INTEGER DEFAULT 0, "
                    + "discovered_at INTEGER DEFAULT 0"
                    + ");");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_shown ON photos(shown_count, last_shown);");
        } catch (Throwable t) {
            Log.e(TAG, "Error initializing SQLite database at " + DB_PATH, t);
        }
    }

    private synchronized boolean checkDb() {
        if (db == null || !db.isOpen()) {
            initDb();
        }
        return db != null && db.isOpen();
    }

    /**
     * Checks if media (music or video) is currently active across the system.
     */
    public static boolean isMediaActive(Context ctx) {
        if (ctx == null) return false;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            if (am.isMusicActive()) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                List<AudioPlaybackConfiguration> configs = am.getActivePlaybackConfigurations();
                if (configs != null && !configs.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error checking media active", t);
        }
        return false;
    }

    /**
     * Synchronizes discovered WebDAV photo URLs with the database in a single transaction.
     * Inserts new URLs and deletes URLs that are no longer present.
     */
    public synchronized int syncPhotos(List<String> discoveredUrls) {
        if (!checkDb() || discoveredUrls == null) return 0;
        int count = 0;
        try {
            db.beginTransaction();
            try {
                long now = System.currentTimeMillis();

                // Find the average shown_count of existing photos so newly discovered photos
                // don't monopolize the rotation by starting at 0
                int baselineShown = 0;
                Cursor cAvg = db.rawQuery("SELECT AVG(shown_count) FROM photos", null);
                if (cAvg != null && cAvg.moveToFirst()) {
                    baselineShown = cAvg.getInt(0);
                    cAvg.close();
                }

                for (String url : discoveredUrls) {
                    if (url == null || url.trim().isEmpty()) continue;
                    ContentValues cv = new ContentValues();
                    cv.put("url", url.trim());
                    cv.put("discovered_at", now);
                    cv.put("shown_count", baselineShown);
                    // Insert or ignore if duplicate URL
                    long id = db.insertWithOnConflict("photos", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                    if (id != -1) count++;
                }

                // Delete URLs that no longer exist on server if we got a valid non-empty list
                if (!discoveredUrls.isEmpty()) {
                    HashSet<String> current = new HashSet<String>(discoveredUrls);
                    Cursor c = db.rawQuery("SELECT id, url FROM photos", null);
                    if (c != null) {
                        List<Long> toDelete = new ArrayList<Long>();
                        while (c.moveToNext()) {
                            long id = c.getLong(0);
                            String u = c.getString(1);
                            if (!current.contains(u)) {
                                toDelete.add(id);
                            }
                        }
                        c.close();
                        for (Long id : toDelete) {
                            db.delete("photos", "id = ?", new String[]{String.valueOf(id)});
                        }
                    }
                }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            Log.i(TAG, "Synced " + discoveredUrls.size() + " photos into database (new additions: " + count + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Error syncing photos into SQLite database", t);
        }
        return getPhotoCount();
    }

    /**
     * Gets the total count of photos stored in the database.
     */
    public synchronized int getPhotoCount() {
        if (!checkDb()) return 0;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM photos", null);
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error getting photo count", t);
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    /**
     * Checks if all photos have been shown in the current cycle.
     * Cycle is complete if all photos have been shown at least once in the current round.
     */
    public synchronized boolean isCycleComplete() {
        if (!checkDb()) return false;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT MIN(shown_count), MAX(shown_count) FROM photos", null);
            if (c != null && c.moveToFirst()) {
                int min = c.getInt(0);
                int max = c.getInt(1);
                // Cycle complete when min catches up to max
                return max > 0 && min >= max;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error checking cycle completion", t);
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    /**
     * Resets shown_count for all photos to 0 to start a fresh cycle.
     */
    public synchronized void resetCycle() {
        if (!checkDb()) return;
        try {
            db.execSQL("UPDATE photos SET shown_count = 0");
        } catch (Throwable t) {
            Log.e(TAG, "Error resetting cycle", t);
        }
    }

    /**
     * Fetches the next photo URL ordered by shown_count ASC, RANDOM() LIMIT 1.
     * Updates shown_count and last_shown timestamp.
     */
    public synchronized String getNextPhotoUrl() {
        if (!checkDb()) return null;
        Cursor c = null;
        long photoId = -1;
        String url = null;
        int shownCount = 0;
        try {
            c = db.rawQuery("SELECT id, url, shown_count FROM photos ORDER BY shown_count ASC, RANDOM() LIMIT 1", null);
            if (c != null && c.moveToFirst()) {
                photoId = c.getLong(0);
                url = c.getString(1);
                shownCount = c.getInt(2);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error fetching next photo URL", t);
        } finally {
            if (c != null) c.close();
        }

        if (photoId != -1 && url != null) {
            try {
                ContentValues cv = new ContentValues();
                cv.put("shown_count", shownCount + 1);
                cv.put("last_shown", System.currentTimeMillis());
                db.update("photos", cv, "id = ?", new String[]{String.valueOf(photoId)});
            } catch (Throwable t) {
                Log.e(TAG, "Error updating shown_count", t);
            }
        }

        return url;
    }
}
