package com.company.children;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Device Admin receiver (izin device admin, KECUALI launcher).
 * Dicatat di AndroidManifest + res/xml/device_admin.xml.
 */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        // Admin aktif: orang tua bisa lock layar & kelola password kebijakan
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Peringatan bila anak mencoba mematikan device admin dari Settings
        return "Mematikan izin ini membuat parental control tidak berfungsi.";
    }
}
