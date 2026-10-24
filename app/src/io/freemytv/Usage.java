package io.freemytv;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Today's TV time per app (Android's usage stats), for the drawer's second floor. Reading them takes the usage access
 * that HomeMode allows through ADB in Free My TV mode (Original takes it back). Nothing leaves the TV.
 */
final class Usage {
    private Usage() {}

    static final class App {
        String pkg, label;
        long ms;
    }

    static boolean allowed(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * The apps opened since midnight that have their own icon in All Apps (no home screens, no system pieces), the
     * longest first. Off the main thread: the system reads its files.
     */
    static List<App> today(Context c) {
        List<App> out = new ArrayList<>();
        if (!allowed(c)) return out;
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        if (usm == null) return out;
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        Map<String, UsageStats> all = usm.queryAndAggregateUsageStats(midnight.getTimeInMillis(), System.currentTimeMillis());
        PackageManager pm = c.getPackageManager();
        for (UsageStats s : all.values()) {
            long ms = s.getTotalTimeInForeground();
            String pkg = s.getPackageName();
            // Not this app, nor the TV's settings (time spent there is setup, not watching).
            if (ms < 60_000 || pkg.equals(c.getPackageName()) || pkg.equals("com.android.tv.settings")
                    || pkg.equals("com.android.settings")) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            if (pm.queryIntentActivities(launch, 0).isEmpty()) continue;
            App a = new App();
            a.pkg = pkg;
            a.ms = ms;
            try {
                a.label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            out.add(a);
        }
        Collections.sort(out, (x, y) -> Long.compare(y.ms, x.ms));
        return out;
    }
}
