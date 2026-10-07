package com.company.children;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.security.SecureRandom;
import java.util.List;

/**
 * Alur MainActivity:
 *  1. Buka aplikasi -> minta SEMUA permission + device admin (KECUALI launcher
 *     home default, karena itu tidak bisa/boleh diambil paksa oleh app biasa).
 *  2. Jika semua izin sudah diberikan -> tampilkan kotak berisi 6 digit angka
 *     random untuk ditautkan ke web parental.
 *  3. Jika web sudah menautkan (event "linked" dari server) -> simpan sesi,
 *     jalankan ControlService, tampilkan dashboard anak.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS   = 100;
    private static final int REQ_ADMIN   = 101;
    private static final int REQ_OVERLAY = 102;
    private static final int REQ_USAGE   = 103;

    private LinearLayout root;
    private boolean permsPending = false;

    /** Menerima broadcast dari ControlService saat web menautkan perangkat. */
    private final android.content.BroadcastReceiver linkReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshAfterResume();
        }
    };

    private static final String[] RUNTIME_PERMS;
    static {
        java.util.ArrayList<String> p = new java.util.ArrayList<>();
        p.add(android.Manifest.permission.INTERNET);
        p.add(android.Manifest.permission.ACCESS_NETWORK_STATE);
        p.add(android.Manifest.permission.WAKE_LOCK);
        p.add(android.Manifest.permission.FOREGROUND_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        RUNTIME_PERMS = p.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 96, 48, 48);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        // generate / pulihkan kode pairing 6 digit (sesi lama tetap berlaku)
        if (Prefs.getCode(this) == null) {
            int n = 100000 + new SecureRandom().nextInt(900000);
            Prefs.setCode(this, String.valueOf(n));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            registerReceiver(linkReceiver,
                    new android.content.IntentFilter(ControlService.ACTION_LINKED));
        } catch (Exception ignored) {}
        // Setelah user kembali dari halaman izin settings, perbarui tampilan
        if (Prefs.isLinked(this)) {
            renderDashboard();
        } else if (!allPermissionsGranted()) {
            requestMissingPermissions();
            renderPermissionStatus();
        } else {
            renderPairCode();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(linkReceiver); } catch (Exception ignored) {}
    }

    // ============================ PERMISSION ============================

    private boolean allPermissionsGranted() {
        return runtimePermsGranted()
                && overlayGranted()
                && adminActive()
                && AppUtil.hasUsageAccess(this);
    }

    private boolean runtimePermsGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        for (String p : RUNTIME_PERMS) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private boolean overlayGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(this);
    }

    private boolean adminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
    }

    private void requestMissingPermissions() {
        permsPending = true;
        // 1) runtime permissions (notifikasi dll)
        if (!runtimePermsGranted() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(RUNTIME_PERMS, REQ_PERMS);
        }
        // 2) overlay (tampilkan di atas layar)
        if (!overlayGranted() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
        }
        // 3) device admin (pengecualian: kita TIDAK meminta izin launcher/home)
        if (!adminActive()) {
            try {
                Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        new ComponentName(this, AdminReceiver.class));
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "children butuh device admin agar tidak bisa di-uninstall & bisa mengunci layar.");
                startActivityForResult(i, REQ_ADMIN);
            } catch (Exception ignored) {}
        }
        // 4) usage access (deteksi aplikasi foreground utk blokir aplikasi)
        if (!AppUtil.hasUsageAccess(this)) {
            AppUtil.requestUsageAccess(this);
        }
    }

    /** Halaman status: checklist izin yang masih kurang + tombol minta ulang. */
    private void renderPermissionStatus() {
        root.removeAllViews();
        title("children v" + Config.APP_VERSION);
        sub("Izinkan semua perizinan berikut agar parental control aktif.\n" +
                "(Kecuali menjadi launcher/home default — tidak diminta.)");

        addRow("Runtime permissions (notifikasi)", runtimePermsGranted());
        addRow("Tampilan overlay (SYSTEM_ALERT_WINDOW)", overlayGranted());
        addRow("Device admin (anti-uninstall + lock)", adminActive());
        addRow("Akses penggunaan (usage access)", AppUtil.hasUsageAccess(this));

        Button b = btn("MINTA IZIN ULANG");
        b.setOnClickListener(v -> { requestMissingPermissions(); renderPermissionStatus(); });
        root.addView(b);
    }

    // ============================ KODE PAIRING ============================

    /** Kotak berisi 6 digit angka random untuk dimasukkan di web parental. */
    private void renderPairCode() {
        root.removeAllViews();
        title("Hubungkan ke Web Parental");
        sub("Buka http://" + Config.LHOST + ":" + Config.LPORT +
                " di browser orang tua, lalu masukkan kode ini:");

        TextView code = new TextView(this);
        code.setText(Prefs.getCode(this));
        code.setTextSize(52f);
        code.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        code.setGravity(Gravity.CENTER);
        code.setPadding(40, 40, 40, 40);
        code.setBackgroundResource(android.R.drawable.editbox_background);
        root.addView(code);

        sub("Menunggu penautan dari web parental...");
        Button b1 = btn("GANTI KODE");
        b1.setOnClickListener(v -> {
            int n = 100000 + new SecureRandom().nextInt(900000);
            Prefs.setCode(this, String.valueOf(n));
            renderPairCode();
        });
        root.addView(b1);

        // tombol debug: simulasikan tautan sukses (untuk uji dashboard tanpa server)
        Button b2 = btn("LANJUT KE DASHBOARD (mode offline/debug)");
        b2.setOnClickListener(v -> {
            Prefs.setLinked(this, true);
            ControlService.start(this);
            renderDashboard();
        });
        root.addView(b2);
    }

    // ============================ DASHBOARD ANAK ============================

    private void renderDashboard() {
        root.removeAllViews();
        title("Dashboard Anak");
        sub("Perangkat tertaut dengan web parental ✔");

        List<String> blocked = ControlService.parseList(Prefs.getBlockListRaw(this));
        addRow("Koneksi server", true);
        addRow("Mode kunci layar (aktif di HP)", Prefs.isLockOn(this));
        addRow("Mode blokir aplikasi", Prefs.isBlockOn(this));
        sub("Aplikasi diblokir (" + blocked.size() + "):");
        for (String pkg : blocked) {
            sub("• " + AppUtil.appLabel(this, pkg) + "  [" + pkg + "]");
        }

        Button b = btn("SELESAI / TUTUP");
        b.setOnClickListener(v -> finish());
        root.addView(b);
    }

    // ============================ HELPER UI ============================

    private void title(String t) {
        TextView x = new TextView(this);
        x.setText(t);
        x.setTextSize(24f);
        x.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        x.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 24;
        x.setLayoutParams(lp);
        root.addView(x);
    }

    private void sub(String t) {
        TextView x = new TextView(this);
        x.setText(t);
        x.setTextSize(14f);
        x.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8; lp.bottomMargin = 8;
        x.setLayoutParams(lp);
        root.addView(x);
    }

    private void addRow(String label, boolean ok) {
        TextView x = new TextView(this);
        x.setText((ok ? "✅ " : "❌ ") + label);
        x.setTextSize(16f);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 6; lp.bottomMargin = 6;
        x.setLayoutParams(lp);
        root.addView(x);
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 32;
        b.setLayoutParams(lp);
        return b;
    }

    // ============================ CALLBACKS ============================

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            runOnUiThread(this::refreshAfterResume);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        refreshAfterResume();
    }

    private void refreshAfterResume() {
        if (Prefs.isLinked(this)) renderDashboard();
        else if (allPermissionsGranted()) renderPairCode();
        else renderPermissionStatus();
    }
}
