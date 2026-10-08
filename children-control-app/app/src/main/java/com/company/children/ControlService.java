package com.company.children;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Service koneksi realtime (socket.io) antara hp anak dan server parental.
 * Tugas:
 *  1. Kirim "pair" (kode 6 digit + info perangkat) -> server balas "paired" (sesi disimpan).
 *  2. Terima "control_update" dari web orang tua -> simpan state & tampilkan overlay.
 *  3. Monitor aplikasi foreground -> bila diblokir, tampilkan overlay blokir.
 * Catatan CodeAssist: tanpa lambda (dipakai anonymous inner class), tanpa
 * androidx.core (Notification dibuat manual, API 23+ aman).
 */
public class ControlService extends Service {

    public static final String CHANNEL_ID = "children_control";
    private static final int NOTIF_ID = 100;

    private Socket socket;
    private SessionStore store;
    private Handler handler;
    private ForegroundAppMonitor monitor;

    /** Dipanggil LockOverlayActivity agar monitor berhenti sementara. */
    public static volatile boolean PAUSED = false;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new SessionStore(this);
        handler = new Handler(Looper.getMainLooper());
        PAUSED = false;
        startForegroundNotification();
        connect();
        startMonitoring();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------------ socket

    private void connect() {
        try {
            // CATATAN: opsi "forceTLS" TIDAK ADA di socket.io-client Java 1.x
            // (itulah penyebab error "forceTLS cannot be resolved").
            // Untuk server http:// cukup set secure = false.
            IO.Options opts = new IO.Options();
            opts.reconnection = true;
            opts.secure = false;
            socket = IO.socket(URI.create(Config.SERVER_URL), opts);

            socket.on(Socket.EVENT_CONNECT, new io.socket.emitter.Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    sendPairOrHello();
                    updateStatus("Terhubung ke server parental");
                }
            });

            socket.on("paired", new io.socket.emitter.Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    try {
                        JSONObject data = (JSONObject) args[0];
                        store.saveSession(
                                data.getString("sessionId"),
                                data.optString("password", ""),
                                data.optBoolean("lockEnabled", false),
                                data.optBoolean("blockEnabled", false),
                                csvFromJson(data.optJSONArray("blockedPackages")));
                        broadcastState();
                        reportStateToServer();
                        requestApply();
                    } catch (Exception ignored) {}
                }
            });

            socket.on("control_update", new io.socket.emitter.Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    try {
                        JSONObject data = (JSONObject) args[0];
                        if (data.has("password"))        store.setPassword(data.getString("password"));
                        if (data.has("lockEnabled"))     store.setLockEnabled(data.getBoolean("lockEnabled"));
                        if (data.has("blockEnabled"))    store.setBlockEnabled(data.getBoolean("blockEnabled"));
                        if (data.has("blockedPackages"))
                            store.setBlockedPackages(csvFromJson(data.getJSONArray("blockedPackages")));
                        broadcastState();
                        reportStateToServer();
                        requestApply();
                    } catch (Exception ignored) {}
                }
            });

            socket.on("unlinked", new io.socket.emitter.Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    store.clear();
                    broadcastState();
                    stopSelf();
                }
            });

            socket.on(Socket.EVENT_DISCONNECT, new io.socket.emitter.Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    updateStatus("Terputus, mencoba reconnect...");
                }
            });

            socket.connect();
        } catch (Exception e) {
            updateStatus("Gagal koneksi: " + e.getMessage());
        }
    }

    /** Laporkan state terkini ke server supaya panel web orang tua tetap sinkron. */
    private void reportStateToServer() {
        if (socket == null || !store.isPaired()) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("sessionId", store.getSessionId());
            obj.put("password", store.getPassword());
            obj.put("lockEnabled", store.isLockEnabled());
            obj.put("blockEnabled", store.isBlockEnabled());
            JSONArray pkgs = new JSONArray();
            for (String p : blockedList(this)) pkgs.put(p);
            obj.put("blockedPackages", pkgs);
            socket.emit("state", obj);
        } catch (Exception ignored) {}
    }

    /** Terapkan state kontrol di MAIN thread (startActivity dari thread socket tidak aman). */
    private void requestApply() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                applyControlState();
            }
        });
    }

    /** Kirim pair jika belum tertaut, kalau sudah kirim hello (rebind sesi). */
    private void sendPairOrHello() {
        try {
            if (store.isPaired()) {
                socket.emit("hello", new JSONObject().put("sessionId", store.getSessionId()));
            } else {
                JSONObject info = new JSONObject();
                info.put("code", store.getOrCreatePairCode());
                info.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
                info.put("androidVersion", Build.VERSION.RELEASE);
                info.put("appPackage", getPackageName());
                socket.emit("pair", info);
            }
        } catch (Exception ignored) {}
    }

    private String csvFromJson(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(arr.optString(i));
            }
        }
        return sb.toString();
    }

    private void updateStatus(String s) {
        Intent i = new Intent(MainActivity.ACTION_STATUS);
        i.putExtra("status", s);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void broadcastState() {
        Intent i = new Intent(MainActivity.ACTION_STATE);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    // ------------------------------------------------------------------ control

    /** Tampilkan / tutup overlay sesuai state kontrol terbaru (dijalankan di main thread). */
    private void applyControlState() {
        if (store.isLockEnabled()) {
            // sudah tampil? jangan start ulang terus-menerus
            Intent i = new Intent(this, LockOverlayActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(LockOverlayActivity.EXTRA_MODE, LockOverlayActivity.MODE_LOCK);
            startActivity(i);
        } else {
            // kunci mati -> tutup overlay bila masih tampil
            sendDismiss();
        }
    }

    private void sendDismiss() {
        sendBroadcast(new Intent(LockOverlayActivity.ACTION_DISMISS)
                .setPackage(getPackageName()));
    }

    public static List<String> blockedList(Context ctx) {
        List<String> out = new ArrayList<>();
        String csv = new SessionStore(ctx).getBlockedPackages();
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ monitor

    private void startMonitoring() {
        monitor = new ForegroundAppMonitor();
        monitor.start();
    }

    /** Deteksi aplikasi foreground via UsageStats (butuh izin usage access). */
    private class ForegroundAppMonitor implements Runnable {
        void start() { handler.postDelayed(this, 1000); }

        @Override
        public void run() {
            if (PAUSED) { handler.postDelayed(this, 1000); return; }
            try {
                if (store.isBlockEnabled()) {
                    String fg = currentForegroundPackage();
                    if (fg != null && !fg.equals(getPackageName())
                            && blockedList(ControlService.this).contains(fg)) {
                        Intent i = new Intent(ControlService.this, LockOverlayActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        i.putExtra(LockOverlayActivity.EXTRA_MODE, LockOverlayActivity.MODE_BLOCK);
                        i.putExtra(LockOverlayActivity.EXTRA_PACKAGE, fg);
                        startActivity(i);
                    }
                }
            } catch (Exception ignored) {}
            handler.postDelayed(this, 1000);
        }
    }

    private String currentForegroundPackage() {
        // Cara utama: UsageStatsManager (API 23+, butuh izin PACKAGE_USAGE_STATS /
        // "Akses Penggunaan Aplikasi" yang diberikan user dari Settings).
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager)
                            getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents events = usm.queryEvents(now - 5000, now);
            android.app.usage.UsageEvents.Event ev =
                    new android.app.usage.UsageEvents.Event();
            String pkg = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = ev.getPackageName();
                }
            }
            if (pkg != null) return pkg;
        } catch (Exception ignored) {}
        // Fallback lama: getRunningAppProcesses (hanya mengembalikan data lengkap
        // bila diizinkan OEM; umumnya hanya proses sendiri di Android baru).
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list != null) {
                for (ActivityManager.RunningAppProcessInfo p : list) {
                    if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                            && !p.processName.equals(getPackageName())) {
                        return p.processName;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ------------------------------------------------------------------ notif

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Parental Control", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE); // FLAG_IMMUTABLE aman mulai API 23
        // Notification dibuat manual (TANPA androidx.core / NotificationCompat)
        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("children")
                    .setContentText("Parental control aktif")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("children")
                    .setContentText("Parental control aktif")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        }
        startForeground(NOTIF_ID, n);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (socket != null) socket.disconnect();
        PAUSED = false;
    }
}
