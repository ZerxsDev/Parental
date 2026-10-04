package com.company.parental;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Service inti:
 *  1. Heartbeat perangkat ke Firebase (agar muncul di "kotak perangkat terhubung" web panel).
 *  2. Polling perintah dari web panel (lock on/off, block on/off, set list, ganti password HTML).
 *  3. Monitor foreground app utk menampilkan layar blokir.
 *
 * Menggunakan REST API Firebase Realtime Database (berlaku di Spark Plan),
 * sehingga bekerja tanpa google-services.json. Bila Anda mengaktifkan plugin
 * google-services + Firebase SDK, logika REST ini tetap kompatibel (pilih salah satu).
 */
public class ParentService extends Service {

    public static volatile String lastForegroundPkg = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private OkHttpClient http;
    private Prefs prefs;
    private AppMonitor monitor;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        http = new OkHttpClient();
        startHeartbeatLoop();
        startCommandPollLoop();
        monitor = new AppMonitor(this, prefs);
        handler.post(monitor);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (monitor != null) monitor.stop();
        // Start ulang agar proteksi tidak mati
        Intent i = new Intent(this, ParentService.class);
        try { startService(i); } catch (Exception ignore) { }
    }

    // ================= HEARTBEAT =================
    private void startHeartbeatLoop() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                sendHeartbeat();
                handler.postDelayed(this, Config.HEARTBEAT_MS);
            }
        });
    }

    private void sendHeartbeat() {
        try {
            JSONObject j = new JSONObject();
            j.put("model", android.os.Build.MODEL);
            j.put("brand", android.os.Build.BRAND);
            j.put("androidVersion", android.os.Build.VERSION.RELEASE);
            j.put("sdkInt", android.os.Build.VERSION.SDK_INT);
            j.put("lastSeen", System.currentTimeMillis());
            j.put("seenAtText", new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US)
                    .format(new Date()));
            j.put("online", true);
            j.put("lockEnabled", prefs.isLockEnabled());
            j.put("blockEnabled", prefs.isBlockListEnabled());
            j.put("blockedCount", prefs.getBlockedPackages().size());
            j.put("deviceName", Settings.Global.getString(getContentResolver(),
                    Settings.Global.DEVICE_NAME));
            postJson(Config.deviceUrl(prefs.getDeviceId(), "devices"), j.toString());
        } catch (Exception e) {
            // network belum tersedia; coba lagi pada tick berikutnya
        }
    }

    // ================= COMMAND POLLING =================
    private void startCommandPollLoop() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                fetchCommands();
                handler.postDelayed(this, Config.POLL_MS);
            }
        });
    }

    private void fetchCommands() {
        String url = Config.deviceUrl(prefs.getDeviceId(), "commands");
        Request req = new Request.Builder().url(url).get().build();
        io.execute(() -> {
            try (Response r = http.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return;
                String body = r.body().string();
                if (body == null || body.equals("null")) return;
                JSONObject cmd = new JSONObject(body);
                long token = cmd.optLong("token", 0);
                if (token == 0 || token <= prefs.getLastCmdToken()) return; // sudah diproses

                String action = cmd.optString("action", "");
                applyCommand(action, cmd);
                prefs.setLastCmdToken(token);

                // Tulis ack supaya web panel tahu perintah dieksekusi
                try {
                    JSONObject ack = new JSONObject();
                    ack.put("lastAction", action);
                    ack.put("appliedAt", System.currentTimeMillis());
                    ack.put("ok", true);
                    postJson(Config.deviceUrl(prefs.getDeviceId(), "acks"), ack.toString());
                } catch (Exception ignore) { }
            } catch (IOException | org.json.JSONException ignore) { }
        });
    }

    private void applyCommand(String action, JSONObject cmd) {
        switch (action) {
            case "LOCK_ON":
                prefs.setLockEnabled(true);
                handler.post(() -> DeviceAdminUtil.lockNow(ParentService.this));
                break;

            case "LOCK_OFF":
                prefs.setLockEnabled(false);
                // Layar bisa dibuka kembali oleh anak seperti biasa
                handler.post(() -> {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_HOME);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                });
                break;

            case "BLOCK_ON":
                prefs.setBlockListEnabled(true);
                break;

            case "BLOCK_OFF":
                prefs.setBlockListEnabled(false);
                handler.post(this::dismissBlockScreen);
                break;

            case "SET_BLOCK_LIST": {
                Set<String> pkgs = JsonUtil.toStringSet(cmd.optJSONArray("packages"));
                prefs.setBlockedPackages(pkgs);
                boolean enabled = cmd.optBoolean("enabled", prefs.isBlockListEnabled());
                prefs.setBlockListEnabled(enabled);
                break;
            }

            case "SET_HTML_PASSWORD": {
                String pw = cmd.optString("password", "");
                if (!pw.isEmpty()) prefs.setHtmlPassword(pw);
                break;
            }

            default:
                break;
        }
    }

    private void dismissBlockScreen() {
        Intent i = new Intent(this, BlockActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("dismiss", true);
        // BlockActivity.onResume akan finish sendiri karena flag block mati
        try { startActivity(i); } catch (Exception ignore) { }
    }

    // ================= HTTP HELPERS =================
    private void postJson(String url, String json) {
        RequestBody rb = RequestBody.create(json,
                MediaType.parse("application/json; charset=utf-8"));
        Request req = new Request.Builder().url(url).put(rb).build();
        io.execute(() -> {
            try (Response ignored = http.newCall(req).execute()) { /* ok / gagal diam-diam */ }
            catch (IOException ignored2) { }
        });
    }

    public static void ensureRunning(Context ctx) {
        Intent i = new Intent(ctx, ParentService.class);
        try { ctx.startService(i); } catch (Exception ignore) { }
    }
}
