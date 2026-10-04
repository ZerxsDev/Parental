package com.company.parental;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Receiver Device Admin utk force-lock layar perangkat anak. */
public class ParentDeviceAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Peringatan standar bila anak mencoba melepas admin.
        return "Jika Parental Control dinonaktifkan, orang tua tidak dapat mengunci layar perangkat ini.";
    }
}
