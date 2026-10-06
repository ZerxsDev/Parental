package com.company.children;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * HALAMAN 3 — OVERLAY KUNCI LAYAR.
 * Ditampilkan oleh ControlService ketika orang tua mengaktifkan mode kunci
 * layar ATAU anak membuka aplikasi yang diblokir. Menutupi seluruh layar:
 *  - FLAG_SHOW_WHEN_LOCKED + TURN_SCREEN_ON + FULLSCREEN + KEEP_SCREEN_ON
 *  - onBackPressed() di-override => tombol back tidak menutup overlay
 *  - Home/Recents tetap bekerja, tapi watchdog service (1 detik) akan
 *    MEMBAWA overlay kembali ke depan, jadi layar tetap terkunci.
 * Overlay hanya hilang bila:
 *  1) password yang diketik = password buatan orang tua (dikirim web), atau
 *  2) orang tua menekan "Buka" di web parental (cmd unlock).
 */
public class LockActivity extends AppCompatActivity {

    private static LockActivity current;    // rujukan agar service bisa menutup
    private String reason = "screen";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Jendela menutupi lockscreen & selalu di depan
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        setContentView(R.layout.activity_lock);
        current = this;
        reason = getIntent() != null ? getIntent().getStringExtra("reason") : "screen";

        TextView tvReason = findViewById(R.id.tvLockReason);
        if (reason != null && reason.startsWith("app:")) {
            tvReason.setText("Aplikasi " + reason.substring(4)
                    + " diblokir orang tua.\nMasukkan password untuk membuka.");
        }

        EditText et = findViewById(R.id.etPassword);
        Button btn = findViewById(R.id.btnUnlock);
        TextView msg = findViewById(R.id.tvLockMsg);

        btn.setOnClickListener(v -> {
            String input = et.getText().toString();
            Prefs p = new Prefs(this);
            String saved = p.get(Prefs.K_PWD, "");
            if (saved.isEmpty()) {
                msg.setText("Orang tua belum mengatur password di web parental.");
                return;
            }
            if (input.equals(saved)) {
                // Password benar => lepas kunci layar & blokir sesi ini
                p.put(Prefs.K_LOCK, false);
                ControlService.reportState(null);
                finishAndRemove();
            } else {
                msg.setText("Password salah!");
                et.setText("");
                // Bonus device admin: kunci fisik layar setelah 3x salah
                DevicePolicyManager dpm = (DevicePolicyManager)
                        getSystemService(DEVICE_POLICY_SERVICE);
                ComponentName who = new ComponentName(this, AdminReceiver.class);
                if (dpm.isAdminActive(who)) {
                    try { dpm.lockNow(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /** Tombol BACK tidak boleh menutup overlay */
    @Override
    public void onBackPressed() { /* sengaja kosong */ }

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String r = i != null ? i.getStringExtra("reason") : null;
        if (r != null) reason = r;
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        // Bila orang tua sudah melepas kunci selagi activity ini dibuat
        Prefs p = new Prefs(this);
        if (!p.get(Prefs.K_LOCK, false)
                && (reason == null || !reason.startsWith("app:"))) {
            finishAndRemove();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Anak menekan Home: panggil ulang diri sendiri (singleTask => ke depan)
        // sehingga overlay kembali menutupi layar dalam hitungan milidetik.
        if (isFinishing()) return;
        android.content.Intent relaunch = new Intent(this, LockActivity.class);
        relaunch.putExtra("reason", reason);
        relaunch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(relaunch);
    }

    /** Tutup overlay sepenuhnya */
    private void finishAndRemove() {
        finishAndRemoveTask();
        finish();
        current = null;
    }

    /** Dipanggil ControlService saat web mengirim perintah unlock */
    public static void dismissIfShowing() {
        runOnUiThreadStatic(() -> {
            if (current != null) {
                current.reason = "screen";
                current.finishAndRemove();
            }
        });
    }

    private static void runOnUiThreadStatic(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
