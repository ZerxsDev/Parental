package com.company.children;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * HALAMAN 2 — DASHBOARD ANAK.
 * Ditampilkan setelah sesi ditautkan & disimpan (sessionId + token).
 * Hanya menampilkan info; seluruh kontrol berasal dari web parental.
 */
public class DashboardActivity extends AppCompatActivity {

    private Prefs prefs;
    private TextView tvStatus, tvLock, tvBlock, tvSesi;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_dashboard);
        prefs = new Prefs(this);

        // Pastikan service penjaga berjalan sejak dashboard terbuka
        Intent i = new Intent(this, ControlService.class);
        i.setAction(ControlService.ACT_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);

        tvStatus = findViewById(R.id.tvDashStatus);
        tvLock   = findViewById(R.id.tvLockState);
        tvBlock  = findViewById(R.id.tvBlockState);
        tvSesi   = findViewById(R.id.tvSessionId);

        findViewById(R.id.btnChangeServer).setOnClickListener(v -> changeServer());
        findViewById(R.id.btnUnpair).setOnClickListener(v -> unpair());

        ControlService.events.observe(this, this::handleEvent);
        refresh();
    }

    /** Perbarui teks indikator sesuai state yang dikirim orang tua */
    private void refresh() {
        boolean lock  = prefs.get(Prefs.K_LOCK, false);
        boolean block = prefs.get(Prefs.K_BLOCK, false);
        String list   = prefs.get(Prefs.K_LIST, "");
        int n = list.isEmpty() ? 0 : list.split(",").length;

        tvLock.setText("Mode kunci layar orang tua: " + (lock ? "ON 🔒" : "OFF"));
        tvBlock.setText("Blokir aplikasi: " + (block ? "ON" : "OFF")
                + " (" + n + " aplikasi)"
                + (list.isEmpty() ? "" : "\n" + list.replace(",", "\n")));
        tvSesi.setText("Sesi: " + prefs.get(Prefs.K_SESSION, "-")
                + "\nServer: " + Config.SERVER_URL);
    }

    private void handleEvent(String ev) {
        runOnUiThread(() -> {
            if (ev == null) return;
            if (ev.startsWith("STATUS|"))
                tvStatus.setText("Koneksi ke server parental: " + ev.substring(7));
            else refresh();
        });
    }

    /** Ubah alamat server (IP komputer tempat node server.js berjalan) */
    private void changeServer() {
        final EditText e = new EditText(this);
        e.setText(Config.SERVER_URL);
        new AlertDialog.Builder(this)
                .setTitle("Alamat server parental")
                .setMessage("Contoh: http://192.168.1.10:3000")
                .setView(e)
                .setPositiveButton("Simpan", (d, w) -> {
                    String url = e.getText().toString().trim();
                    if (!url.startsWith("http")) url = "http://" + url;
                    prefs.put(Prefs.K_SERVER, url);
                    Config.SERVER_URL = url;
                    recreate(); // service akan connect ulang
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Putuskan tautan => hapus sesi, kembali ke halaman izin/kode */
    private void unpair() {
        new AlertDialog.Builder(this)
                .setTitle("Putuskan tautan?")
                .setMessage("Perangkat ini tidak lagi dikontrol web parental.")
                .setPositiveButton("Ya", (d, w) -> {
                    prefs.clearSession();
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
