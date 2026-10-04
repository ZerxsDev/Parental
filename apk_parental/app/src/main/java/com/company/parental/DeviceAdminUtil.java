package com.company.parental;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Helper Device Admin utk fitur KUNCI LAYAR anak. */
public class DeviceAdminUtil {

    public static ComponentName component(Context ctx) {
        return new ComponentName(ctx, ParentDeviceAdminReceiver.class);
    }

    public static boolean isActive(Context ctx) {
        DevicePolicyManager dpm =
                (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(component(ctx));
    }

    /** Minta user mengaktifkan admin (satu kali saja, layar sistem). */
    public static Intent enableIntent(Context ctx) {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Parental butuh akses ini utk mengunci layar perangkat dari web panel.");
        return i;
    }

    /** Kunci layar sekarang (butuh admin aktif). Return true jika sukses. */
    public static boolean lockNow(Context ctx) {
        if (!isActive(ctx)) return false;
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.lockNow();
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Matikan mode kunci-permanen: izinkan lagi unlock normal oleh anak. */
    public static void removeAdminIfActive(Context ctx) {
        DevicePolicyManager dpm =
                (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null && dpm.isAdminActive(component(ctx))) {
            dpm.removeActiveAdmin(component(ctx));
        }
    }
}
