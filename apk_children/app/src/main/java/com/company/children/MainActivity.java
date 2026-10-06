package com.company.children;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Random;

/**
 * HALAMAN 1 — Permintaan izin & kode pasangan 6 digit.
 * Alur sesuai request:
 *  1. Saat aplikasi dibuka, minta SEMUA permission (runtime) + Device Admin,
 *     KECUALI launcher (tidak pernah meminta role HOME).
 *  2. Setelah semua izin diberikan => tampilkan kotak berisi 6 digit angka
 *     random yang dikirim ke server dan harus dimasukkan di web parental.
 *  3. Ketika server mengirim "paired" (sesi tersimpan) => buka DashboardActivity.
 */
public class MainActivity extends AppCompatActivity {

    private Prefs prefs;
    private TextView tvPerm, tvCode, tvServer, boxCode;
    private String pairCode = "";

    // Permission runtime yang diminta (tidak ada izin launcher/Home)
    private static final String[] RUNTIME_PERMS;
    static {
        if (Build.VERSION.SDK_INT >= 33) {
            RUNTIME_PERMS = new String[]{
                android.Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            RUNTIME_PERMS = new String[]{};
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        tvPerm   = findViewById(R.id.tvPermStatus);
        tvCode   = findViewById(R.id.tvPairCode);
        tvServer = findViewById(R.id.tvServerInfo);
        boxCode  = findViewById(R.id.boxCode);

        findViewById(R.id.btnGrantAll).setOnClickListener(v -> requestEverything());
        findViewById(R.id.btnRegenerate).setOnClickListener(v -> sendNewCode());

        // Bila SESI sudah tersimpan (sudah pernah ditautkan) => langsung dashboard
        if (prefs.hasSession()) {
            goDashboard();
            return;
        }

        ControlService.events.observe(this, ev -> handleEvent(ev));
        checkPermsAndShow();
    }

    /** Bangun daftar permintaan izin berurutan (button/tombol trigger) */
    private void requestEverything() {
        // 1) Runtime permissions standar
        if (RUNTIME_PERMS.length > 0 && Build.VERSION.SDK_INT >= 33) {
            requestPermissions(RUNTIME_PERMS, 100);
        }
        // 2) Special access: overlay (SYSTEM_ALERT_WINDOW)
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
        // 3) WRITE_SETTINGS (ubah pengaturan sistem)
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
        // 4) Usage access (deteksi aplikasi foreground utk blokir aplikasi)
        if (!ScreenGuard.hasUsageAccess(this)) {
            startActivity(ScreenGuard.usageAccessIntent());
        }
        // 5) DEVICE ADMIN (kecuali launcher — kita tidak pernah minta role Home)
        if (!prefs.get("admin_active", false)) {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    new android.content.ComponentName(this, AdminReceiver.class));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Diperlukan agar orang tua dapat mengunci layar dari jauh.");
            startActivity(i);
        }
    }

    /** Cek apakah SELURUH izin (kecuali launcher) sudah lengkap */
    private boolean allPermsGranted() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return false;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this)) return false;
        if (!ScreenGuard.hasUsageAccess(this)) return false;
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        // device admin
        android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager)
                        getSystemService(DEVICE_POLICY_SERVICE);
        boolean admin = dpm.isAdminActive(
                new android.content.ComponentName(this, AdminReceiver.class));
        prefs.put("admin_active", admin);
        return admin;
    }

    /** Perbarui tampilan: status izin ATAU kotak kode 6 digit */
    private void checkPermsAndShow() {
        if (allPermsGranted()) {
            tvPerm.setText("Semua izin diberikan (launcher sengaja tidak diminta). ✔");
            boxCode.setVisibility(TextView.VISIBLE);
            findViewById(R.id.btnGrantAll).setVisibility(TextView.GONE);
            connectAndShowCode();
        } else {
            tvPerm.setText("Beri semua izin terlebih dahulu (kecuali launcher), " +
                    "lalu tekan tombol di bawah.");
            boxCode.setVisibility(TextView.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.hasSession()) checkPermsAndShow();
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p,
                                           @NonNull int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        checkPermsAndShow();
    }

    /** Hubungkan socket child tanpa sesi; server akan balas 'code' (6 digit) */
    private void connectAndShowCode() {
        Intent i = new Intent(this, ControlService.class);
        i.setAction(ControlService.ACT_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    /** Kirim ulang permintaan kode baru ke server */
    private void sendNewCode() {
        ControlService.requestNewCode();
    }

    /** Event dari ControlService (socket thread) */
    private void handleEvent(String ev) {
        runOnUiThread(() -> {
            if (ev == null) return;
            if (ev.startsWith("CODE|")) {           // server kirim kode 6 digit
                pairCode = ev.substring(5);
                tvCode.setText(pairCode);
                tvServer.setText("server: " + Config.SERVER_URL);
            } else if (ev.startsWith("PAIRED|")) {  // web sudah menautkan => simpan sesi
                String[] s = ev.split("\\|");
                prefs.put(Prefs.K_SESSION, s[1]);
                prefs.put(Prefs.K_TOKEN,   s[2]);
                goDashboard();                      // tampilkan dashboard anak
            } else if (ev.startsWith("ERR|")) {
                tvPerm.setText("Koneksi gagal: " + ev.substring(4) +
                        "\nPastikan server parental berjalan & alamat IP benar.");
            }
        });
    }

    private void goDashboard() {
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }

    /** Dialog sederhana input teks (dipakai utk ubah server URL) */
    public static String promptText(Activity a, String title, String init, boolean numeric) {
        final EditText e = new EditText(a);
        e.setText(init);
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        final String[] out = new String[1];
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(e)
                .setPositiveButton("OK", (d, w) -> out[0] = e.getText().toString())
                .setNegativeButton("Batal", null)
                .setCancelable(false)
                .show();
        return out[0];
    }
}
