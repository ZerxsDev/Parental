package com.company.children;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Mulai ulang service kontrol setelah HP di-reboot (jika sesi masih tersimpan). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && Prefs.isLinked(context)) {
            ControlService.start(context);
        }
    }
}
