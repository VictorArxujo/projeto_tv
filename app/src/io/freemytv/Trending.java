package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What's trending this week (TMDB, shows and movies), with the poster and the installed app that streams it here, for
 * the drawer's second floor. Needs the TMDB key; fetched every 6 hours while the home screen is shown, kept on disk.
 */
final class Trending {
    static final int MAX = 8;
    private static final long EVERY = 6 * 3_600_000L;
    private static final String POSTERS = "https://image.tmdb.org/t/p/w342", SCENES = "https://image.tmdb.org/t/p/w1280";

    static final class Item {
        String title, kind, app, pkg;   // app: the HomeApps key of the service that streams it here (null: none installed)
        String overview = "";
        File scene;                     // its backdrop (16:9) on disk, for the home screen's featured title; null: none
        Bitmap poster;
    }

    interface Listener { void onTrending(); }

    private static Trending instance;

    static synchronized Trending get(Context c) {
        if (instance == null) instance = new Trending(c.getApplicationContext());
        return instance;
    }

    private final Context c;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<Item> items = new ArrayList<>();
    private volatile long fetchedAt, failedAt;
    private volatile boolean fetching, loaded;
    private Listener listener;
    private int posterW = 1, posterH = 1;

    private Trending(Context c) {
        this.c = c;
    }

    List<Item> items() {
        return items;
    }

    /** The posters are decoded at the size they're drawn (the poster's width and height on screen). */
    void setPosterSize(int w, int h) {
        posterW = Math.max(1, w);
        posterH = Math.max(1, h);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Loads what's on disk once, then fetches again when stale (never more than once every 2 minutes after a failure). */
    void refreshIfStale() {
        if (!Recs.hasKey(c) || fetching) return;
        long now = System.currentTimeMillis();
        boolean stale = now - fetchedAt > EVERY || now < fetchedAt;
        if (loaded && (!stale || (failedAt != 0 && now - failedAt < 2 * 60_000L))) return;
        fetching = true;
        io.execute(() -> {
            try {
                if (!loaded) {
                    loaded = true;
                    File list = listFile();
                    if (list.exists()) {
                        fetchedAt = list.lastModified();
                        items = build(new JSONArray(read(new FileInputStream(list))));
                        tell();
                    }
                }
                if (System.currentTimeMillis() - fetchedAt > EVERY) {
                    JSONArray fresh = fetch();
                    try (OutputStream out = new FileOutputStream(listFile())) {
                        out.write(fresh.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    fetchedAt = System.currentTimeMillis();
                    items = build(fresh);
                    tell();
                }
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("trending: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    private void tell() {
        main.post(() -> { if (listener != null) listener.onTrending(); });
    }

    private File listFile() {
        return new File(c.getFilesDir(), "trending-2.json");   // -2: with the scenes and overviews
    }

    /** This week's trending shows and movies, each with the first streaming provider here that an installed app plays. */
    private JSONArray fetch() throws Exception {
        Recs recs = Recs.get(c);
        String region = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
        String lang = Ui.language.replaceAll("-.*", "") + "-" + region;
        JSONArray results = recs.tmdb("/trending/all/week?language=" + lang).getJSONArray("results");
        JSONArray out = new JSONArray();
        for (int i = 0; i < results.length() && out.length() < MAX; i++) {
            JSONObject r = results.getJSONObject(i);
            String kind = r.optString("media_type");
            String poster = r.optString("poster_path", "");
            if (!(kind.equals("tv") || kind.equals("movie")) || poster.isEmpty() || poster.equals("null")) continue;
            String scene = r.optString("backdrop_path", "");
            JSONObject item = new JSONObject().put("title", r.optString(kind.equals("tv") ? "name" : "title"))
                    .put("kind", kind).put("poster", poster).put("overview", r.optString("overview", ""));
            try {
                JSONObject here = recs.tmdb("/" + kind + "/" + r.getLong("id") + "/watch/providers")
                        .getJSONObject("results").optJSONObject(region);
                JSONArray flat = here == null ? null : here.optJSONArray("flatrate");
                for (int j = 0; flat != null && j < flat.length(); j++) {
                    JSONObject p = flat.getJSONObject(j);
                    String[] app = recs.streamingApp(p.optString("provider_name"), p.optInt("provider_id"));
                    if (app == null) continue;
                    item.put("app", app[0]).put("pkg", app[1]);
                    break;
                }
            } catch (Exception e) {
                AppLog.w("trending providers: " + e.getMessage());
            }
            download(POSTERS, poster);
            if (!scene.isEmpty() && !scene.equals("null")) {
                try {
                    download(SCENES, scene);
                    item.put("scene", scene);
                } catch (IOException e) {
                    AppLog.w("trending scene: " + e.getMessage());
                }
            }
            out.put(item);
        }
        return out;
    }

    private List<Item> build(JSONArray list) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.title = o.optString("title");
            it.kind = o.optString("kind");
            it.app = o.has("app") ? o.optString("app") : null;
            it.pkg = o.has("pkg") ? o.optString("pkg") : null;
            it.poster = decode(posterFile(o.optString("poster")));
            it.overview = o.optString("overview", "");
            File scene = o.has("scene") ? posterFile(o.optString("scene")) : null;
            it.scene = scene != null && scene.exists() ? scene : null;
            out.add(it);
        }
        return out;
    }

    private File posterFile(String path) {
        return new File(new File(c.getCacheDir(), "posters"), path.replaceAll("[^A-Za-z0-9._-]", ""));
    }

    private void download(String base, String path) throws IOException {
        File f = posterFile(path);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection http = (HttpURLConnection) new URL(base + path).openConnection();
        http.setConnectTimeout(8000);
        http.setReadTimeout(15000);
        try {
            if (http.getResponseCode() != 200) throw new IOException("image HTTP " + http.getResponseCode());
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        } finally {
            http.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("can't name " + f.getName());
    }

    /** The poster at the size it's drawn (one copy in memory per poster, ~300 KB each). */
    private Bitmap decode(File f) {
        if (!f.exists()) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b == null) return null;
            Bitmap out = Bitmap.createScaledBitmap(b, posterW, posterH, true);
            if (out != b) b.recycle();
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = s.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
