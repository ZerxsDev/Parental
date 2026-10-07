package com.company.children;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Device Admin app. Fungsinya:
 *  - Mencegah uninstall paksa (pengaturan -> keamanan -> device admin tidak
 *    bisa dinonaktifkan tanpa pengetahuan orang tua secara fisik).
 *  - Menyediakan policy force-lock (layar terkunci saat perintah lock aktif).
 */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
    }
}
