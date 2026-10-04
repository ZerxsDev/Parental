package com.company.parental;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/** Util utk daftar aplikasi pihak ketiga (blokir aplikasi). */
public class AppUtil {

    public static class AppItem {
        public final String pkg;
        public final String name;
        public final boolean system;
        AppItem(String p, String n, boolean s) { pkg = p; name = n; system = s; }
    }

    /** Ambil semua aplikasi yang punya launcher activity. */
    public static List<AppItem> listLaunchableApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        List<AppItem> out = new ArrayList<>();
        String myPkg = ctx.getPackageName();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(myPkg)) continue; // jangan blokir diri sendiri
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                CharSequence label = pm.getApplicationLabel(ai);
                out.add(new AppItem(pkg, label != null ? label.toString() : pkg, sys));
            } catch (PackageManager.NameNotFoundException ignore) { }
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Label aplikasi dari package name (untuk layar blokir). */
    public static String labelOf(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l != null ? l.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }
}
