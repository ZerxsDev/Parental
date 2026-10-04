package com.company.parental;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * Layar overlay pemblokir aplikasi. Muncul di atas aplikasi yang diblokir.
 * Tidak bisa ditutup dgn tombol back. Keluar hanya lewat panel Parental
 * (password HTML) atau perintah OFF dari web panel.
 */
public class BlockActivity extends Activity {

    private boolean showingHome = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block);

        String pkg = getIntent().getStringExtra("pkg");
        TextView tv = findViewById(R.id.tvBlockedInfo);
        if (pkg != null) {
            tv.setText("Aplikasi \"" + AppUtil.labelOf(this, pkg) + "\"\ndiblokir oleh Parental Control.");
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String pkg = intent.getStringExtra("pkg");
        TextView tv = findViewById(R.id.tvBlockedInfo);
        if (pkg != null) {
            tv.setText("Aplikasi \"" + AppUtil.labelOf(this, pkg) + "\"\ndiblokir oleh Parental Control.");
        }
    }

    /** Blok tombol back supaya anak tidak bisa keluar dari layar blokir. */
    @Override
    public void onBackPressed() { /* sengaja dikosongkan */ }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
            return true; // ditelan
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Jika blocklist sudah mati (mis. user memasukkan password benar), tutup.
        Prefs p = new Prefs(this);
        if (!p.isBlockListEnabled()) {
            finishAndGoHome();
        }
    }

    private void finishAndGoHome() {
        if (showingHome) return;
        showingHome = true;
        finish();
    }
}
