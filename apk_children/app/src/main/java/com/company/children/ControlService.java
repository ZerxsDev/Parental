package com.company.children;

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

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Service latar belakang (foreground) yang:
 *  1. Menjaga koneksi socket.io ke server parental.
 *  2. Menerima perintah: lock on/off, password, blokir aplikasi on/off/list.
 *  3. Menampilkan overlay LockActivity saat lock/blokir aktif.
 *  4. Loop monitor 1 detik: jika mode blokir aktif dan aplikasi foreground
 *     ada di daftar blokir -> tampilkan overlay.
 */
public class ControlService extends Service {

    private static final String CH_ID = "children_ctrl";

    private static volatile boolean running = false;
    private Socket socket;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable monitorLoop;

    public static void start(Context c) {
        Intent i = new Intent(c, ControlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        startForeground(101, buildNotif("children terhubung ke server parental"));
        connectSocket();
        startMonitor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_LINKED.equals(intent.getAction())) {
                // sesi ditautkan dari web -> simpan + tampilkan dashboard anak
                Prefs.setLinked(this, true);
                sendBroadcast(new Intent(ACTION_LINKED));
            } else if (ACTION_DISMISS.equals(intent.getAction())) {
                dismissOverlay();
            }
            applyStateToUi(); // evaluasi ulang kondisi overlay
        }
        return START_STICKY;
    }

    public static final String ACTION_LINKED  = "com.company.children.LINKED";
    public static final String ACTION_DISMISS = "com.company.children.DISMISS_LOCK";

    @Override
    public void onDestroy() {
        running = false;
        if (monitorLoop != null) handler.removeCallbacks(monitorLoop);
        if (socket != null) socket.disconnect();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------ socket.io

    private void connectSocket() {
        try {
            IO.Options opts = new IO.Options();
            opts.reconnection = true;
            opts.transports = new String[]{"websocket", "polling"};
            socket = IO.socket(Config.SERVER_URL, opts);

            socket.on(Socket.EVENT_CONNECT, args -> {
                String code = Prefs.getCode(ControlService.this);
                JSONObject info = new JSONObject();
                try {
                    info.put("code", code == null ? "" : code);
                    info.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                    info.put("os", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
                    info.put("version", Config.APP_VERSION);
                    info.put("battery", batteryLevel());
                } catch (Exception ignored) {}
                socket.emit("child-online", info);
                updateNotif("Terhubung: " + Config.SERVER_URL);
            });

            socket.on(Socket.EVENT_DISCONNECT, args ->
                    updateNotif("Reconnecting ke " + Config.SERVER_URL + " ..."));

            socket.on("linked", args -> {
                // web memasukkan kode yang benar -> simpan sesi & tampilkan dashboard
                Prefs.setLinked(ControlService.this, true);
                sendBroadcast(new Intent(ACTION_LINKED));
                updateNotif("Perangkat tertaut dengan web parental ✔");
            });

            // ---- Perintah dari web parental ----
            socket.on("set-lock", args -> {
                boolean on = args.length > 0 && truthy(args[0]);
                String pass = args.length > 1 ? String.valueOf(args[1]) : "";
                Prefs.setLockOn(this, on);
                Prefs.setPassword(this, pass);
                if (on) forceDeviceLockOnce();
                else dismissOverlay();               // orang tua membuka lock dari web
                applyStateToUi();
            });

            socket.on("set-block", args -> {
                boolean on = args.length > 0 && truthy(args[0]);
                List<String> list = new ArrayList<>();
                if (args.length > 1 && args[1] instanceof JSONArray) {
                    JSONArray ja = (JSONArray) args[1];
                    try {
                        for (int i = 0; i < ja.length(); i++) list.add(ja.getString(i));
                    } catch (Exception ignored) {}
                }
                Prefs.setBlockOn(this, on);
                Prefs.setBlockListRaw(this, android.text.TextUtils.join(",", list));
                applyStateToUi();
            });

            socket.connect();
        } catch (Exception e) {
            updateNotif("Gagal koneksi: " + e.getMessage());
        }
    }

    private static boolean truthy(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o == null) return false;
        String s = String.valueOf(o).toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("on");
    }

    // ------------------------------------------------ monitor aplikasi foreground

    private void startMonitor() {
        monitorLoop = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                try { evaluateOverlay(); } catch (Exception ignored) {}
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(monitorLoop);
    }

    /** Tentukan apakah overlay harus tampil sekarang, lalu mulai/kunci layar bila perlu. */
    private void evaluateOverlay() {
        boolean lockOn  = Prefs.isLockOn(this);
        boolean blockOn = Prefs.isBlockOn(this);
        boolean shouldShow = lockOn;

        if (!shouldShow && blockOn) {
            String fg = AppUtil.getForegroundPackage(this);
            List<String> blocked = blockedPackages();
            if (fg != null && !fg.equals(getPackageName()) && blocked.contains(fg)) {
                shouldShow = true;
            }
        }

        if (shouldShow && !LockActivity.isVisible()) {
            showLock(lockOn ? "lock" : "block");
        }
    }

    private void applyStateToUi() {
        handler.post(this::evaluateOverlay);
    }

    private void showLock(String reason) {
        Intent i = new Intent(this, LockActivity.class);
        i.putExtra("reason", reason);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(i);
    }

    /** Tutup overlay (orang tua mematikan lock dari web / password benar). */
    private void dismissOverlay() {
        Intent i = new Intent(this, LockActivity.class);
        i.setAction(LockActivity.ACTION_DISMISS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        if (LockActivity.isVisible()) finishOverlayDirectly();
    }

    private void finishOverlayDirectly() {
        // LockActivity.onNewIntent akan memanggil finish() saat menerima ACTION_DISMISS.
    }

    /** Saat lock ON pertama kali: kunci layar perangkat sekali (butuh device admin). */
    private void forceDeviceLockOnce() {
        try {
            android.app.admin.DevicePolicyManager dpm =
                    (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName cn =
                    new android.content.ComponentName(this, AdminReceiver.class);
            if (dpm.isAdminActive(cn)) dpm.lockNow();
        } catch (Exception ignored) {}
    }

    public static List<String> parseList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private List<String> blockedPackages() {
        return parseList(Prefs.getBlockListRaw(this));
    }

    // ------------------------------------------------ notifikasi

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "children control", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("children")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(101, buildNotif(text));
    }

    private int batteryLevel() {
        try {
            android.content.Intent batt = registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = batt.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = batt.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            return (int) (100f * level / scale);
        } catch (Exception e) {
            return -1;
        }
    }
}
