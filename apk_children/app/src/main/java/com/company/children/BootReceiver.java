package com.company.children;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Receiver: start ulang ControlService setiap HP selesai boot,
 * agar kunci layar/blokir aplikasi tetap aktif walau HP dimatikan anak.
 */
public class BootReceiver extends android.content.BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!android.app.admin.DevicePolicyManager.ACTION_DEVICE_ADMIN_DISABLED
                .equals(i.getAction())) {
            Intent s = new Intent(c, ControlService.class);
            s.setAction(ControlService.ACT_START);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        }
    }
}
