package com.company.children;

import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Overlay pengunci layar. Ditampilkan oleh ControlService ketika:
 *  - web parental mengaktifkan MODE KUNCI LAYAR (lock on), atau
 *  - mode BLOKIR APLIKASI aktif dan aplikasi foreground ada di daftar blokir.
 *
 * Overlay tidak bisa ditutup kecuali:
 *  1. Anak memasukkan password yang benar (dibuat orang tua di web), atau
 *  2. Orang tua mematikan lock dari web parental (event set-lock off ->
 *     service mengirim ACTION_DISMISS).
 */
public class LockActivity extends AppCompatActivity {

    private static volatile boolean visible = false;
    public static final String ACTION_DISMISS = "com.company.children.DISMISS_LOCK";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // jendela di atas status bar + tangkap semua tombol fisik
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_lock);

        TextView reason = findViewById(R.id.tvReason);
        String r = getIntent() != null ? getIntent().getStringExtra("reason") : "lock";
        reason.setText("block".equals(r)
                ? "Aplikasi ini diblokir oleh orang tua."
                : "Layar dikunci oleh orang tua.");

        final EditText pass = findViewById(R.id.etPass);
        Button ok = findViewById(R.id.btnOk);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ok.setOnClickListener(v -> checkPassword(pass.getText().toString()));

        Button refresh = findViewById(R.id.btnRefresh);
        refresh.setOnClickListener(v -> {
            ControlService.start(this);           // sambar koneksi/service
            Toast.makeText(this, "Mencoba sambungkan ulang...", Toast.LENGTH_SHORT).show();
        });
    }

    private void checkPassword(String input) {
        String want = Prefs.getPassword(this);
        if (!want.isEmpty() && input.equals(want)) {
            finish();
        } else {
            Toast.makeText(this, "Password salah!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
    }

    /** Service memanggil ini saat orang tua mematikan lock dari web. */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && ACTION_DISMISS.equals(intent.getAction())) {
            finish();
        }
    }

    @Override
    public void onBackPressed() { /* sengaja kosong: back tidak boleh menutup overlay */ }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // tahan HOME juga bila memungkinkan (tidak selalu berhasil di semua ROM,
        // tapi FLAG_SHOW_WHEN_LOCKED + fullscreen membuat overlay tetap di atas)
        return true;
    }

    public static boolean isVisible() { return visible; }
}
