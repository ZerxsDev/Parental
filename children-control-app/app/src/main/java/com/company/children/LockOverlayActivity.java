package com.company.children;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * Overlay pengunci layar penuh.
 * - MODE_LOCK  : orang tua mengaktifkan kunci layar dari web
 * - MODE_BLOCK : aplikasi yang dibuka anak sedang diblokir
 * Overlay tidak bisa ditutup kecuali:
 *   a) password benar, atau
 *   b) orang tua mematikan mode dari web (broadcast ACTION_DISMISS), atau
 *   c) blokir untuk aplikasi itu dilepas (overlay block menutup sendiri
 *      saat aplikasi foreground bukan lagi aplikasi terblokir).
 * Catatan CodeAssist: tanpa lambda (pakai anonymous inner class).
 */
public class LockOverlayActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_PACKAGE = "pkg";
    public static final String ACTION_DISMISS = "com.company.children.DISMISS";
    public static final int MODE_LOCK = 0;
    public static final int MODE_BLOCK = 1;

    private SessionStore store;
    private int mode;
    private String blockedPkg;
    private BroadcastReceiver dismissReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tampilkan di atas keyguard & semua jendela
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_lock_overlay);

        store = new SessionStore(this);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_LOCK);
        blockedPkg = getIntent().getStringExtra(EXTRA_PACKAGE);

        TextView tvTitle = findViewById(R.id.tvOverlayTitle);
        TextView tvApp = findViewById(R.id.tvOverlayApp);
        final EditText etPassword = findViewById(R.id.etPassword);
        Button btnUnlock = findViewById(R.id.btnUnlock);
        final TextView tvWrong = findViewById(R.id.tvWrong);

        if (mode == MODE_BLOCK) {
            tvTitle.setText(R.string.overlay_blocked);
            tvApp.setText("Aplikasi: " + blockedPkg);
        } else {
            tvTitle.setText(R.string.overlay_locked);
            tvApp.setText("");
            // Device admin aktif -> kunci layar fisik juga (overlay tetap tampil saat nyala)
            AdminReceiver.forceLock(this);
        }

        btnUnlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = etPassword.getText().toString();
                String saved = store.getPassword();
                if (!saved.isEmpty() && saved.equals(input)) {
                    finishAndRemoveTaskSafely();
                } else {
                    tvWrong.setText(R.string.wrong_password);
                    etPassword.setText("");
                    Toast.makeText(LockOverlayActivity.this,
                            R.string.wrong_password, Toast.LENGTH_SHORT).show();
                }
            }
        });

        dismissReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // orang tua mematikan mode dari web -> tutup overlay
                finish();
            }
        };
        // dibungkus try/catch untuk kompatibilitas ROM yang menuntut flag ekspor
        try {
            // dibungkus try/catch untuk kompatibilitas ROM yang menuntut flag ekspor
        try {
            registerReceiver(dismissReceiver, new IntentFilter(ACTION_DISMISS));
        } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /** Blokir tombol back & home semaksimal mungkin (home tidak bisa diblokir API <28,
     *  tapi monitor service akan menampilkan overlay lagi). */
    @Override
    public void onBackPressed() {
        // sengaja kosong: tidak ada aksi -> back tidak menutup overlay
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Bila pengguna menekan home, kembali ke depan secepat mungkin
        if (!isFinishing()) {
            ControlService.PAUSED = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ControlService.PAUSED = true;
        // update kondisi: misal mode block tapi aplikasi sudah diganti & tak terblokir
        if (mode == MODE_BLOCK && !store.isBlockEnabled()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ControlService.PAUSED = false;
        if (dismissReceiver != null) {
            try { unregisterReceiver(dismissReceiver); } catch (Exception ignored) {}
        }
    }

    private void finishAndRemoveTaskSafely() {
        finish();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        }
    }
}
