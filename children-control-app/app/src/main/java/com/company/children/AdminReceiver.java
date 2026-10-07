package com.company.children;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Receiver Device Admin.
 * Saat mode kunci layar diaktifkan dari web, service memanggil
 * lockNow() melalui DevicePolicyManager (butuh izin device admin).
 */
public class AdminReceiver extends android.app.admin.DeviceAdminReceiver {

    public static ComponentName component(Context ctx) {
        return new ComponentName(ctx, AdminReceiver.class);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Peringatan bila anak mencoba mencabut device admin
        return "Mencabut device admin akan menonaktifkan parental control.";
    }

    public static void forceLock(Context ctx) {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isAdminActive(component(ctx))) {
                dpm.lockNow();
            }
        } catch (Exception ignored) {}
    }
}
