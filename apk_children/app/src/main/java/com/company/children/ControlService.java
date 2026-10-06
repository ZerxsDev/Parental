package com.company.children;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Service latar belakang: menjaga koneksi Socket.IO ke server parental,
 * menerima perintah (lock/unlock/setPassword/blockApps), dan menegakkan
 * blokir aplikasi dengan menampilkan LockActivity (overlay penuh layar).
 *
 * Protokol event (harus sama persis dgn web_parental/server.js):
 *  child -> server : "register" {pairCode}                     (belum bertaut)
 *                    "hello"    {sessionId,token,model,android} (sudah bertaut)
 *                    "state"    {lockActive, blockedPkg}        (lapor balik)
 *  server -> child : "paired"   {sessionId, token}              (simpan sesi!)
 *                    "cmd"      {action: lock|unlock|setPassword|blockApps|forceLock}
 */
public class ControlService extends Service {

    public static final String ACT_START = "start";

    /** Event bus utk Activity: CODE|xxx | PAIRED|sid|tok | ERR|msg | STATUS|terhubung|putus */
    public static final MutableLiveData<String> events = new MutableLiveData<>();

    private static Socket socket;         // statis => bisa diakses Dashboard/LockActivity
    private Prefs prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable watchdog;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        startForegroundNotif();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        connect();
        startWatchdog();
        return START_STICKY;   // service dijaga agar tetap hidup
    }

    /* ============================ NOTIF ============================ */
    private void startForegroundNotif() {
        String CH = "children_ctrl";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CH, "Parental Control", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = Build.VERSION.SDK_INT >= 31
                ? PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getActivity(this, 0, open, 0);
        Notification n = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                        .setContentTitle("children")
                        .setContentText("Parental control aktif")
                        .setSmallIcon(R.drawable.ic_launcher_fallback)
                        .setContentIntent(pi).build()
                : new Notification.Builder(this)
                        .setContentTitle("children")
                        .setContentText("Parental control aktif")
                        .setSmallIcon(R.drawable.ic_launcher_fallback)
                        .setContentIntent(pi).build();
        startForeground(42, n);
    }

    /* ========================= KONEKSI SOCKET ====================== */
    private synchronized void connect() {
        try {
            if (socket != null && socket.connected()) return;
            IO.Options opt = new IO.Options();
            opt.reconnection = true;
            opt.transports   = new String[]{"websocket", "polling"};
            if (socket == null) socket = IO.socket(Config.SERVER_URL, opt);

            socket.on(Socket.EVENT_CONNECT, a -> onSocketConnect());
            socket.on("paired",  j -> onPaired(j));
            socket.on("cmd",     j -> onCmd(j));
            socket.on(Socket.EVENT_DISCONNECT,
                    a -> events.postValue("STATUS|putus"));
            socket.connect();
        } catch (Exception e) {
            events.postValue("ERR|" + e.getMessage());
        }
    }

    private void onSocketConnect() {
        events.postValue("STATUS|terhubung");
        try {
            if (prefs.hasSession()) {
                // Sudah pernah ditautkan: kirim hello berisi sesi tersimpan
                JSONObject o = new JSONObject();
                o.put("sessionId", prefs.get(Prefs.K_SESSION, ""));
                o.put("token",     prefs.get(Prefs.K_TOKEN, ""));
                o.put("model",     Build.MODEL);
                o.put("android",   "Android " + Build.VERSION.RELEASE);
                o.put("version",   "1.0");
                socket.emit("hello", o);
            } else {
                // Belum ditautkan: minta kode 6 digit baru dari server
                requestNewCodeInternal();
            }
        } catch (Exception ignored) {}
    }

    /** Dibuat statis supaya MainActivity/Dashboard bisa minta kode baru */
    public static void requestNewCode() {
        ControlService s = instance;
        if (s != null) s.requestNewCodeInternal();
    }
    private static ControlService instance;

    private void requestNewCodeInternal() {
        try {
            if (socket == null || !socket.connected()) return;
            String code = String.format("%06d",
                    new Random().nextInt(1000000));       // 6 digit random
            prefs.put(Prefs.K_PAIR, code);
            JSONObject o = new JSONObject();
            o.put("pairCode", code);
            o.put("model", Build.MODEL);
            o.put("android", "Android " + Build.VERSION.RELEASE);
            o.put("version", "1.0");
            socket.emit("register", o);
            events.postValue("CODE|" + code);             // tampilkan di kotak
        } catch (Exception ignored) {}
    }

    /** Server mengonfirmasi web sudah memasukkan kode => SIMPAN SESI */
    private void onPaired(Object[] args) {
        try {
            JSONObject j = (JSONObject) args[0];
            prefs.put(Prefs.K_SESSION, j.getString("sessionId"));
            prefs.put(Prefs.K_TOKEN,   j.getString("token"));
            events.postValue("PAIRED|" + j.getString("sessionId")
                    + "|" + j.getString("token"));
        } catch (Exception ignored) {}
    }

    /* ===================== PERINTAH DARI WEB ======================= */
    private void onCmd(Object[] args) {
        try {
            JSONObject j = (JSONObject) args[0];
            String action = j.optString("action");

            if ("setPassword".equals(action)) {
                prefs.put(Prefs.K_PWD, j.optString("password", ""));

            } else if ("lock".equals(action)) {
                prefs.put(Prefs.K_LOCK, true);
                showLock("screen");            // overlay kunci layar

            } else if ("unlock".equals(action)) {
                prefs.put(Prefs.K_LOCK, false);
                hideLock();                    // buka dari web parental

            } else if ("blockApps".equals(action)) {
                JSONArray arr = j.optJSONArray("list");
                StringBuilder sb = new StringBuilder();
                if (arr != null)
                    for (int i = 0; i < arr.length(); i++)
                        sb.append(i > 0 ? "," : "").append(arr.getString(i));
                prefs.put(Prefs.K_BLOCK, true);
                prefs.put(Prefs.K_LIST, sb.toString());
                enforceBlockNow();             // langsung cek foreground

            } else if ("unblockApps".equals(action)) {
                prefs.put(Prefs.K_BLOCK, false);
                prefs.put(Prefs.K_LIST, "");

            } else if ("forceLock".equals(action)) {
                // Bonus device admin: matikan layar seketika
                try {
                    android.app.admin.DevicePolicyManager dpm = (android.app.admin
                            .DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                    ComponentName who = new ComponentName(this, AdminReceiver.class);
                    if (dpm.isAdminActive(who)) dpm.lockNow();
                } catch (Exception ignored) {}
            }

            // Laporkan state balik ke web (indikator on/off selalu akurat)
            reportState(null);
            events.postValue("LOCKSTATE|" + (prefs.get(Prefs.K_LOCK, false) ? 1 : 0));
        } catch (Exception ignored) {}
    }

    /** Kirim status aktual perangkat ke socket parent */
    public static void reportState(String blockedPkg) {
        ControlService s = instance;
        if (s == null) return;
        s.reportStateInternal(blockedPkg);
    }
    private void reportStateInternal(String blockedPkg) {
        try {
            if (socket == null || !socket.connected()) return;
            JSONObject o = new JSONObject();
            o.put("lockActive", prefs.get(Prefs.K_LOCK, false));
            o.put("blockActive", prefs.get(Prefs.K_BLOCK, false));
            o.put("blockedList", prefs.get(Prefs.K_LIST, ""));
            o.put("hasPassword", !prefs.get(Prefs.K_PWD, "").isEmpty());
            o.put("blockedPkg", blockedPkg == null ? "" : blockedPkg);
            socket.emit("state", o);
        } catch (Exception ignored) {}
    }

    /* ==================== OVERLAY / KUNCI LAYAR ==================== */
    private void showLock(String reason) {
        Intent i = new Intent(this, LockActivity.class);
        i.putExtra("reason", reason);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(i);
    }

    private void hideLock() {
        LockActivity.dismissIfShowing();
    }

    /** Tegakkan daftar blokir SEKARANG juga (tanpa menunggu watchdog) */
    private void enforceBlockNow() {
        h.post(() -> checkBlockedApp(true));
    }

    /** Watchdog tiap 1 detik: bila blokir aktif & app foreground terdaftar => kunci */
    private void startWatchdog() {
        if (watchdog != null) h.removeCallbacks(watchdog);
        watchdog = new Runnable() {
            @Override public void run() {
                checkBlockedApp(false);
                h.postDelayed(this, 1000);
            }
        };
        h.postDelayed(watchdog, 1000);
    }

    private void checkBlockedApp(boolean force) {
        if (!prefs.get(Prefs.K_BLOCK, false)) return;
        String list = prefs.get(Prefs.K_LIST, "");
        if (list.isEmpty()) return;
        String fg = ScreenGuard.getForegroundPackage(this);
        if (fg == null) return;
        if (fg.equals(getPackageName())) return;          // jangan kunci diri sendiri
        if (fg.startsWith("com.android.systemui")) return;
        if (fg.startsWith("com.android.settings")) return; // halaman izin tetap bisa dibuka
        for (String p : list.split(",")) {
            if (p.trim().equalsIgnoreCase(fg)) {
                showLock("app:" + fg);
                reportStateInternal(fg);
                return;
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (h != null && watchdog != null) h.removeCallbacks(watchdog);
        // socket sengaja tidak ditutup (statis) agar sesi bertahan saat restart service
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Bila anak menghapus task dari Recents, service tetap jalan (START_STICKY)
    }
}
