package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bing's photo of the day as the home screen's background (bing_wallpaper in config.properties), where there are no
 * picks and none of your photos. Checked every few hours while the home screen is shown; a new day's photo (1920×1080,
 * no key) replaces the one kept in the app's files, and is built into a Backdrop the way your photos are.
 */
final class Wallpaper {
    private static final String API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=";
    private static final long CHECK_EVERY = 3 * 3_600_000L;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Backdrop backdrop;
    private static long builtStamp, checkedAt;
    private static boolean busy;

    private Wallpaper() {}

    static boolean on() {
        return Config.BING_WALLPAPER;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    /**
     * Today's photo, ready to draw, or null (off, or the first one still on its way). The previous one stays until a new
     * one is ready; then `ready` runs.
     */
    static synchronized Backdrop backdrop(Context c, Runnable ready) {
        if (!on()) return null;
        Context app = c.getApplicationContext();
        File f = file(app);
        long now = System.currentTimeMillis();
        boolean check = now - checkedAt > CHECK_EVERY || now < checkedAt;
        long have = builtStamp;
        if (!busy && (check || (f.isFile() && f.lastModified() != have))) {
            busy = true;
            if (check) checkedAt = now;
            worker.execute(() -> {
                Backdrop b = null;
                long stamp = 0;
                try {
                    if (check) download(app, f);
                    stamp = f.lastModified();
                    if (f.isFile() && stamp != have) {
                        Bitmap src = BitmapFactory.decodeFile(f.getPath());
                        if (src != null) b = Backdrop.make(src, true, true, true);
                    }
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("wallpaper: " + e.getMessage());
                }
                synchronized (Wallpaper.class) {
                    busy = false;
                    if (b == null) return;
                    backdrop = b;
                    builtStamp = stamp;
                }
                main.post(ready);
            });
        }
        return backdrop;
    }

    /** Fetches the photo when Bing has a new one (its start date changed), written as .part first. */
    private static void download(Context c, File f) throws Exception {
        String market = Ui.language.replaceAll("-.*", "") + "-" + Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
        JSONObject image = new JSONObject(HomeData.get(API + market)).getJSONArray("images").getJSONObject(0);
        String date = image.getString("startdate");
        SharedPreferences sp = c.getSharedPreferences("wallpaper", Context.MODE_PRIVATE);
        if (date.equals(sp.getString("date", "")) && f.isFile()) return;
        File part = new File(f.getPath() + ".part");
        HttpURLConnection http = (HttpURLConnection) new URL("https://www.bing.com" + image.getString("urlbase") + "_1920x1080.jpg").openConnection();
        http.setConnectTimeout(6000);
        http.setReadTimeout(15000);
        http.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        } finally {
            http.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("can't name " + f.getName());
        sp.edit().putString("date", date).putString("title", image.optString("title")).apply();
        AppLog.w("wallpaper: " + date + " " + image.optString("title"));
    }
}
