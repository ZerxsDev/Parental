package com.company.parental;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Panel lokal (di perangkat anak) yg dilindungi PASSWORD HTML.
 * - Login memakai form HTML/WebView-style password (Config.DEFAULT_HTML_PASSWORD,
 *   bisa diganti dari web panel).
 * - Menu: kunci layar on/off, blokir aplikasi on/off + pilih aplikasi, ganti password.
 */
public class MainActivity extends AppCompatActivity {

    private Prefs prefs;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final OkHttpClient http = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        showLoginGate();
    }

    // ================= LOGIN PASSWORD (HTML form via WebView) =================
    private void showLoginGate() {
        setContentView(R.layout.activity_login);
        WebViewPasswordGate gate = findViewById(R.id.webGate);
        gate.setup(prefs.getHtmlPassword(), new WebViewPasswordGate.Callback() {
            @Override
            public void onSuccess() {
                ParentService.ensureRunning(MainActivity.this);
                showDashboard();
            }
            @Override
            public void onWrong() {
                Toast.makeText(MainActivity.this,
                        "Password salah!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================= DASHBOARD =================
    private void showDashboard() {
        setContentView(R.layout.activity_main);

        TextView tvDevice = findViewById(R.id.tvDeviceInfo);
        tvDevice.setText("Perangkat ID: " + prefs.getDeviceId()
                + "\n" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " (Android " + android.os.Build.VERSION.RELEASE
                + ", API " + android.os.Build.VERSION.SDK_INT + ")");

        // --- Kunci Layar ---
        CheckBox swLock = findViewById(R.id.swLock);
        swLock.setChecked(prefs.isLockEnabled());
        if (!DeviceAdminUtil.isActive(this)) {
            Button btnAdmin = findViewById(R.id.btnActiveAdmin);
            btnAdmin.setVisibility(View.VISIBLE);
            btnAdmin.setOnClickListener(v ->
                    startActivity(DeviceAdminUtil.enableIntent(this)));
        } else {
            findViewById(R.id.btnActiveAdmin).setVisibility(View.GONE);
        }
        swLock.setOnCheckedChangeListener((b, checked) -> {
            prefs.setLockEnabled(checked);
            if (checked) {
                boolean ok = DeviceAdminUtil.lockNow(this);
                Toast.makeText(this, ok ? "Layar dikunci." :
                        "Aktifkan Device Admin dulu!", Toast.LENGTH_SHORT).show();
            }
            sendCommand(buildCmd("LOCK_" + (checked ? "ON" : "OFF"), null));
        });

        // --- Blokir Aplikasi ---
        CheckBox swBlock = findViewById(R.id.swBlock);
        swBlock.setChecked(prefs.isBlockListEnabled());
        swBlock.setOnCheckedChangeListener((b, checked) -> {
            prefs.setBlockListEnabled(checked);
            if (!AppMonitor.hasUsageAccessPermission(this)) {
                startActivity(AppMonitor.usageAccessSettingsIntent());
            }
            JSONObject o = buildCmd("BLOCK_" + (checked ? "ON" : "OFF"), null);
            try { o.put("packages", new JSONArray(
                    new ArrayList<>(prefs.getBlockedPackages()))); } catch (Exception ignore) {}
            sendCommand(o);
            updateBlockCount();
        });

        Button btnPick = findViewById(R.id.btnPickApps);
        btnPick.setOnClickListener(v -> showAppPicker());

        Button btnPw = findViewById(R.id.btnChangePassword);
        btnPw.setOnClickListener(v -> showPasswordDialog());

        updateBlockCount();
    }

    private void updateBlockCount() {
        TextView tv = findViewById(R.id.tvBlockedCount);
        if (tv != null) {
            tv.setText("Diblokir: " + prefs.getBlockedPackages().size() + " aplikasi");
        }
    }

    // ================= PICKER APLIKASI =================
    private void showAppPicker() {
        List<AppUtil.AppItem> apps = AppUtil.listLaunchableApps(this);
        Set<String> blocked = prefs.getBlockedPackages();
        String[] names = new String[apps.size()];
        boolean[] checks = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i).name + "\n(" + apps.get(i).pkg + ")";
            checks[i] = blocked.contains(apps.get(i).pkg);
        }
        new AlertDialog.Builder(this)
                .setTitle("Pilih aplikasi yang diblokir")
                .setMultiChoiceItems(names, checks, (d, idx, isChecked) ->
                        checks[idx] = isChecked)
                .setPositiveButton("Simpan", (d, w) -> {
                    Set<String> sel = new HashSet<>();
                    for (int i = 0; i < apps.size(); i++)
                        if (checks[i]) sel.add(apps.get(i).pkg);
                    prefs.setBlockedPackages(sel);
                    JSONObject o = buildCmd("SET_BLOCK_LIST", null);
                    try {
                        o.put("packages", new JSONArray(new ArrayList<>(sel)));
                        o.put("enabled", prefs.isBlockListEnabled());
                    } catch (Exception ignore) {}
                    sendCommand(o);
                    updateBlockCount();
                    Toast.makeText(this,
                            sel.size() + " aplikasi diblokir.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ================= GANTI PASSWORD =================
    private void showPasswordDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Password HTML baru");
        LinearLayout ll = new LinearLayout(this);
        ll.setPadding(48, 24, 48, 0);
        ll.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Ganti Password HTML")
                .setView(ll)
                .setPositiveButton("OK", (d, w) -> {
                    String pw = input.getText().toString().trim();
                    if (pw.length() < 4) {
                        Toast.makeText(this, "Minimal 4 karakter.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.setHtmlPassword(pw);
                    JSONObject o = buildCmd("SET_HTML_PASSWORD", null);
                    try { o.put("password", pw); } catch (Exception ignore) {}
                    sendCommand(o);
                    Toast.makeText(this, "Password diganti.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ================= FIREBASE COMMAND =================
    private JSONObject buildCmd(String action, JSONObject extra) {
        JSONObject o = new JSONObject();
        try {
            o.put("action", action);
            o.put("token", System.currentTimeMillis());
            if (extra != null) {
                for (java.util.Iterator<String> it = extra.keys(); it.hasNext(); ) {
                    String k = it.next();
                    o.put(k, extra.get(k));
                }
            }
        } catch (Exception ignore) {}
        return o;
    }

    /** Tulis perintah juga ke Firebase agar state sinkron dgn web panel. */
    private void sendCommand(JSONObject cmd) {
        String url = Config.deviceUrl(prefs.getDeviceId(), "commands");
        RequestBody rb = RequestBody.create(cmd.toString(),
                MediaType.parse("application/json; charset=utf-8"));
        Request req = new Request.Builder().url(url).put(rb).build();
        io.execute(() -> {
            try (Response r = http.newCall(req).execute()) { /* best effort */ }
            catch (IOException ignore) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ParentService.ensureRunning(this);
    }
}
