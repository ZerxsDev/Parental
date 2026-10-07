package com.company.children;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Alur aplikasi anak:
 *  1. Layar izin  : minta SYSTEM_ALERT_WINDOW, PACKAGE_USAGE_STATS, Device Admin
 *                   (KECUALI izin launcher/home - tidak diminta sesuai spesifikasi).
 *  2. Layar kode  : tampilkan 6 digit angka acak untuk ditautkan di web parental.
 *  3. Dashboard   : setelah server mengirim sesi ("paired"), tampilkan dashboard anak.
 * ControlService menangani overlay kunci/blokir secara terpisah.
 */
public class MainActivity extends AppCompatActivity {

    public static final String ACTION_STATUS = "com.company.children.STATUS";
    public static final String ACTION_STATE  = "com.company.children.STATE";

    private LinearLayout layoutPerms, layoutPairing, layoutDashboard;
    private TextView statusOverlay, statusUsage, statusAdmin, tvPairCode, tvConnStatus,
                     tvDeviceInfo, tvControlState;
    private Button btnNext;
    private SessionStore store;
    private BroadcastReceiver receiver;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new SessionStore(this);

        layoutPerms     = findViewById(R.id.layoutPerms);
        layoutPairing   = findViewById(R.id.layoutPairing);
        layoutDashboard = findViewById(R.id.layoutDashboard);
        statusOverlay   = findViewById(R.id.statusOverlay);
        statusUsage     = findViewById(R.id.statusUsage);
        statusAdmin     = findViewById(R.id.statusAdmin);
        tvPairCode      = findViewById(R.id.tvPairCode);
        tvConnStatus    = findViewById(R.id.tvConnStatus);
        tvDeviceInfo    = findViewById(R.id.tvDeviceInfo);
        tvControlState  = findViewById(R.id.tvControlState);
        btnNext         = findViewById(R.id.btnNext);

        Button btnOverlay = findViewById(R.id.btnOverlay);
        Button btnUsage   = findViewById(R.id.btnUsage);
        Button btnAdmin   = findViewById(R.id.btnAdmin);
        Button btnUnpair  = findViewById(R.id.btnUnpair);

        btnOverlay.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });

        btnUsage.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Menu usage access tidak tersedia", Toast.LENGTH_SHORT).show();
            }
        });

        btnAdmin.setOnClickListener(v -> {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    AdminReceiver.component(this));
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Diperlukan untuk fitur kunci layar parental.");
            startActivity(intent);
        });

        btnNext.setOnClickListener(v -> {
            if (!hasAllPermissions()) {
                Toast.makeText(this,
                        "Semua izin (overlay, usage, device admin) harus diberikan dulu",
                        Toast.LENGTH_LONG).show();
                return;
            }
            showPairing();
            startService(new Intent(this, ControlService.class));
        });

        btnUnpair.setOnClickListener(v -> {
            store.clear();
            sendBroadcast(new Intent(LockOverlayActivity.ACTION_DISMISS)
                    .setPackage(getPackageName()));
            stopService(new Intent(this, ControlService.class));
            showPermissions();
        });

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_STATUS.equals(intent.getAction())) {
                    tvConnStatus.setText(intent.getStringExtra("status"));
                } else if (ACTION_STATE.equals(intent.getAction())) {
                    refreshDashboard();
                }
            }
        };

        // Mulai dari layar yang benar: izin -> pairing -> dashboard
        if (!hasAllPermissions())      showPermissions();
        else if (!store.isPaired())    showPairing();
        else                           showDashboard();

        if (store.isPaired() || hasAllPermissions()) {
            startService(new Intent(this, ControlService.class));
        }
    }

    // ------------------------------------------------------------- screens

    private void showPermissions() {
        layoutPerms.setVisibility(VISIBLE);
        layoutPairing.setVisibility(GONE);
        layoutDashboard.setVisibility(GONE);
        findViewById(R.id.tvTitle).setVisibility(VISIBLE);
        findViewById(R.id.tvDesc).setVisibility(VISIBLE);
    }

    private void showPairing() {
        layoutPerms.setVisibility(GONE);
        layoutPairing.setVisibility(VISIBLE);
        layoutDashboard.setVisibility(GONE);
        ((TextView) findViewById(R.id.tvTitle)).setText(R.string.pairing_title);
        ((TextView) findViewById(R.id.tvDesc)).setText(R.string.pairing_desc);
        tvPairCode.setText(store.getOrCreatePairCode());
    }

    private void showDashboard() {
        layoutPerms.setVisibility(GONE);
        layoutPairing.setVisibility(GONE);
        layoutDashboard.setVisibility(VISIBLE);
        ((TextView) findViewById(R.id.tvTitle)).setText(R.string.dash_title);
        ((TextView) findViewById(R.id.tvDesc)).setText("");
        refreshDashboard();
    }

    private void refreshDashboard() {
        if (store.isPaired()) {
            showDashboard();
            tvDeviceInfo.setText("Perangkat: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\nAndroid: " + Build.VERSION.RELEASE
                    + "\nSesi: " + store.getSessionId());
            String state = "Normal";
            if (store.isLockEnabled()) state = "Layar dikunci orang tua";
            else if (store.isBlockEnabled())
                state = "Blokir aplikasi aktif: " + store.getBlockedPackages();
            tvControlState.setText("Status kontrol: " + state);
            tvConnStatus.setText(R.string.status_connected);
        }
    }

    // ------------------------------------------------------------- perms

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private boolean hasUsagePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.app.usage.UsageStatsManager usm =
                        (android.app.usage.UsageStatsManager)
                                getSystemService(Context.USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                // queryEvents mengembalikan objek kosong (bukan null) tanpa izin,
                // jadi cek apakah benar-benar ada event usage yang terbaca.
                android.app.usage.UsageEvents events =
                        usm.queryEvents(now - 1000L * 60 * 60 * 24, now);
                android.app.usage.UsageEvents.Event ev =
                        new android.app.usage.UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev);
                    return true; // ada data -> izin usage access diberikan
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private boolean isAdminActive() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(AdminReceiver.component(this));
    }

    /** Semua izin wajib SUDAH diberikan (launcher/home sengaja TIDAK diminta). */
    private boolean hasAllPermissions() {
        boolean overlay = hasOverlayPermission();
        boolean admin   = isAdminActive();
        boolean usage   = hasUsagePermission();

        statusOverlay.setText("Overlay: " + (overlay ? "diizinkan ✔" : "belum diizinkan ✘"));
        statusAdmin.setText("Device admin: " + (admin ? "aktif ✔" : "belum aktif ✘"));
        // Usage access tidak bisa diprogram utk dicek 100% andal; tombol tetap ada,
        // tapi tombol Lanjut hanya mewajibkan overlay + device admin.
        statusUsage.setText("Usage stats: " + (usage ? "terbaca ✔" : "belum terbaca (opsional)"));

        return overlay && admin;
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, new IntentFilter(ACTION_STATUS));
        try {
            registerReceiver(receiver, new IntentFilter(ACTION_STATE));
        } catch (Exception ignored) {}

        if (!hasAllPermissions()) {
            showPermissions();
            return;
        }
        if (store.isPaired()) refreshDashboard();
        else                  showPairing();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }
}
