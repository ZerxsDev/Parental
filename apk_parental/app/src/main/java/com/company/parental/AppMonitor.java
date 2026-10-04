package com.company.parental;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import java.util.List;
import java.util.Set;

/**
 * Monitor aplikasi foreground. Bila aplikasi yang dibuka anak ada di daftar
 * blokir dan blokir aktif, tampilkan BlockActivity (layar penghalang).
 */
public class AppMonitor implements Runnable {

    private static final long INTERVAL_MS = 800L;

    private final Context ctx;
    private final Prefs prefs;
    private volatile boolean stopped = false;
    private String lastShownPkg = "";

    public AppMonitor(Context ctx, Prefs prefs) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
    }

    public void stop() { stopped = true; }

    @Override
    public void run() {
        if (stopped) return;
        try {
            Set<String> blocked = prefs.getBlockedPackages();
            boolean enabled = prefs.isBlockListEnabled();
            String fg = currentForegroundPackage();
            ParentService.lastForegroundPkg = fg == null ? "" : fg;

            if (enabled && fg != null && !fg.isEmpty() && blocked.contains(fg)) {
                showBlock(fg);
            } else {
                lastShownPkg = "";
            }
        } catch (Exception ignore) { }

        // jadwalkan ulang di main thread
        android.os.Handler h = new android.os.Handler(
                android.os.Looper.getMainLooper());
        h.postDelayed(this, INTERVAL_MS);
    }

    private void showBlock(String pkg) {
        if (pkg.equals(lastShownPkg)) return; // sudah ditampilk
        lastShownPkg = pkg;
        Intent i = new Intent(ctx, BlockActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("pkg", pkg);
        try { ctx.startActivity(i); } catch (Exception ignore) { }
    }

    /** Deteksi foreground app: UsageStats (butuh izin khusus) atau fallback running tasks. */
    private String currentForegroundPackage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                        ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                android.app.usage.UsageEvents events = usm.queryEvents(now - 3500, now + 1000);
                android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
                String pkg = null;
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                            || event.getEventType() == 1 /* RESUMED on newer APIs */) {
                        pkg = event.getPackageName();
                    }
                }
                if (pkg != null) return pkg;
            } catch (Exception ignore) {
                // izin Usage Access belum diberikan
            }
        }
        // Fallback utk API <26 tanpa izin usage stats
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                return tasks.get(0).topActivity.getPackageName();
            }
        } catch (Exception ignore) { }
        return null;
    }

    /** Cek apakah izin Usage Access sudah diberikan (untuk UI peringatan). */
    public static boolean hasUsageAccessPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true;
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                    ctx.getApplicationContext().getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents events = usm.queryEvents(now - 10_000, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            // Jika tidak punya izin, queryEvents mengembalikan objek kosong tapi tidak throw;
            // cara andal: cek appOps.
            android.app.AppOpsManager aom = (android.app.AppOpsManager)
                    ctx.getApplicationContext().getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception ex) {
            return false;
        }
    }

    public static Intent usageAccessSettingsIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }
}
