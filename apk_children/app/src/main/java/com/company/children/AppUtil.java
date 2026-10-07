package com.company.children;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/** Utilitas: deteksi aplikasi foreground & daftar aplikasi terinstal. */
public class AppUtil {

    /**
     * Ambil package aplikasi yang sedang di foreground.
     *  - ActivityManager.getRunningAppProcesses (proses IMPORTANCE_FOREGROUND)
     *  - UsageStatsManager.queryUsageStats (aplikasi dengan lastUsedTimeBoost terbaru)
     */
    @SuppressWarnings("deprecation")
    public static String getForegroundPackage(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        return p.processName;
                    }
                }
            }
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                android.app.usage.UsageStatsManager usm =
                        (android.app.usage.UsageStatsManager)
                                ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                java.util.List<android.app.usage.UsageStats> stats =
                        usm.queryUsageStats(
                                android.app.usage.UsageStatsManager.INTERVAL_BEST,
                                now - 10 * 60 * 1000, now);
                if (stats != null && !stats.isEmpty()) {
                    String recentPkg = null;
                    long recentTime = 0;
                    for (android.app.usage.UsageStats u : stats) {
                        if (u.getLastTimeUsed() > recentTime) {
                            recentTime = u.getLastTimeUsed();
                            recentPkg = u.getPackageName();
                        }
                    }
                    // hanya anggap foreground jika dipakai < 5 detik terakhir
                    if (recentPkg != null && now - recentTime < 5000) return recentPkg;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Cek apakah izin Usage Access sudah diberikan. */
    public static boolean hasUsageAccess(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true;
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager)
                            ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> stats =
                    usm.queryUsageStats(
                            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                            now - 86_400_000L, now);
            return stats != null && !stats.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Buka halaman Settings > Special app access > Usage access. */
    public static void requestUsageAccess(Context ctx) {
        try {
            ctx.startActivity(new android.content.Intent(
                    Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    /** Semua aplikasi non-sistem (bisa diblokir): list [label, packageName]. */
    public static List<String[]> getUserApps(Context ctx) {
        List<String[]> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                CharSequence lab = pm.getApplicationLabel(ai);
                out.add(new String[]{String.valueOf(lab), ai.packageName});
            }
        }
        return out;
    }

    /** Label aplikasi dari package name (fallback: nama package itu sendiri). */
    public static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }
}
