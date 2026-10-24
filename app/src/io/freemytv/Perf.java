package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV's CPU and memory over the last two minutes, for the drawer's performance chart. Sampled every 2 s only while
 * the drawer is open. Memory comes from Android; the CPU from /proc/stat, which apps can't read since Android 8, so it
 * goes through this app's own ADB (as the switch does), on one connection kept while sampling.
 */
final class Perf {
    static final int SAMPLES = 60;
    private static final long EVERY = 2000;

    interface Listener { void onPerf(); }

    /** Oldest first: percent of CPU busy (NaN: unknown) and of memory in use. */
    final float[] cpu = new float[SAMPLES], ram = new float[SAMPLES];
    int count;
    volatile long totalMem, usedMem;
    volatile boolean cpuKnown;

    private final Context c;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;
    private Adb adb;
    private long lastTotal, lastIdle;
    private int adbFailures;

    Perf(Context c, Listener listener) {
        this.c = c.getApplicationContext();
        this.listener = listener;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            io.execute(Perf.this::sample);
            main.postDelayed(this, EVERY);
        }
    };

    void start() {
        if (running) return;
        running = true;
        main.post(tick);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
        io.execute(() -> {
            if (adb != null) adb.close();
            adb = null;
            lastTotal = 0;
        });
    }

    /** On the io thread: one sample of each, then the screen is told on the main thread. */
    private void sample() {
        float busy = readCpu();
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        float used = Float.NaN;
        if (am != null) {
            am.getMemoryInfo(mi);
            totalMem = mi.totalMem;
            usedMem = mi.totalMem - mi.availMem;
            if (mi.totalMem > 0) used = 100f * usedMem / mi.totalMem;
        }
        float cpuNow = busy, ramNow = used;
        main.post(() -> {
            if (!running) return;
            if (count == SAMPLES) {
                System.arraycopy(cpu, 1, cpu, 0, SAMPLES - 1);
                System.arraycopy(ram, 1, ram, 0, SAMPLES - 1);
                count--;
            }
            cpu[count] = cpuNow;
            ram[count] = ramNow;
            count++;
            listener.onPerf();
        });
    }

    /** Percent busy since the last read (NaN on the first one, or without ADB). */
    private float readCpu() {
        if (adbFailures >= 3) return Float.NaN;   // debugging off: don't knock every 2 s
        try {
            if (adb == null) adb = Adb.connect(c, 5_000, null);
            String line = adb.shell("head -1 /proc/stat").trim();
            String[] f = line.split("\\s+");
            if (f.length < 5 || !f[0].equals("cpu")) return Float.NaN;
            long total = 0, idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            float busy = Float.NaN;
            if (lastTotal > 0 && total > lastTotal) busy = 100f * (1 - (idle - lastIdle) / (float) (total - lastTotal));
            lastTotal = total;
            lastIdle = idle;
            adbFailures = 0;
            cpuKnown = true;
            return busy;
        } catch (IOException | RuntimeException e) {
            adbFailures++;
            AppLog.w("perf: " + e.getMessage());
            if (adb != null) adb.close();
            adb = null;
            return Float.NaN;
        }
    }
}
