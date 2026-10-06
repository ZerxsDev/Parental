package com.company.children;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import java.util.List;

/**
 * Utilitas pemantau layar & aplikasi foreground.
 * - isScreenInteractive(): overlay tidak perlu ditampilkan saat layar mati
 *   (LockActivity juga memakai FLAG_SHOW_WHEN_LOCKED agar tetap menutupi).
 * - getForegroundPackage(): dipakai utk menegakkan DAFTAR BLOKIR APLIKASI
 *   dari web parental (bila app foreground ada di daftar blokir => tampilkan
 *   overlay kunci "Aplikasi diblokir").
 */
public class ScreenGuard {

    public static boolean isScreenInteractive(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km == null || km.isKeyguardRestrictedInputMode() == false
                || !km.inKeyguardRestrictedInputMode();
    }

    /** Nama package aplikasi yang sedang tampil di depan (butuh izin
     *  PACKAGE_USAGE_STATS; fallback ke ActivityManager utk API lama). */
    @SuppressWarnings("deprecation")
    public static String getForegroundPackage(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                long now = System.currentTimeMillis();
                android.app.usage.UsageStatsManager usm =
                        (android.app.usage.UsageStatsManager)
                                c.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    android.app.usage.UsageEvents events =
                            usm.queryEvents(now - 10_000, now + 1_000);
                    android.app.usage.UsageEvents.Event ev =
                            new android.app.usage.UsageEvents.Event();
                    String pkg = null;
                    while (events.hasNextEvent()) {
                        events.getNextEvent(ev);
                        if (ev.getEventType()
                                == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            pkg = ev.getPackageName();
                        }
                    }
                    if (pkg != null) return pkg;
                }
            }
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                return tasks.get(0).topComponent.getPackageName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Cek apakah izin "Usage Access" sudah diberikan (Settings > Special app access) */
    public static boolean hasUsageAccess(Context c) {
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager)
                            c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            return usm != null
                    && usm.queryUsageStats(
                            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                            now - 1000, now).size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Intent halaman Settings utk memberi izin Usage Access */
    public static Intent usageAccessIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }
}
