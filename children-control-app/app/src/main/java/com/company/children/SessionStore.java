package com.company.children;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Penyimpanan sesi & state kontrol di perangkat anak.
 * - pairCode   : kode 6 digit acak utk ditautkan di web parental
 * - sessionId  : id sesi yg diberikan server setelah pairing sukses
 * - lockEnabled: mode kunci layar aktif?
 * - blockEnabled: mode blokir aplikasi aktif?
 * - blockedPackages : daftar package yang diblokir, dipisah koma
 */
public class SessionStore {

    private static final String PREFS = "children_session";

    private final SharedPreferences sp;

    public SessionStore(Context ctx) {
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Ambil (atau buat sekali) kode pairing 6 digit acak. */
    public String getOrCreatePairCode() {
        String code = sp.getString("pair_code", null);
        if (code == null) {
            code = String.format("%06d", new java.util.Random().nextInt(1000000));
            sp.edit().putString("pair_code", code).apply();
        }
        return code;
    }

    public void setSessionId(String sid) { sp.edit().putString("session_id", sid).apply(); }
    public String getSessionId()          { return sp.getString("session_id", null); }
    public boolean isPaired()             { return getSessionId() != null; }

    public void setPassword(String pw)    { sp.edit().putString("password", pw).apply(); }
    public String getPassword()           { return sp.getString("password", ""); }

    public void setLockEnabled(boolean b) { sp.edit().putBoolean("lock_enabled", b).apply(); }
    public boolean isLockEnabled()        { return sp.getBoolean("lock_enabled", false); }

    public void setBlockEnabled(boolean b){ sp.edit().putBoolean("block_enabled", b).apply(); }
    public boolean isBlockEnabled()       { return sp.getBoolean("block_enabled", false); }

    public void setBlockedPackages(String csv) { sp.edit().putString("blocked_pkgs", csv).apply(); }
    public String getBlockedPackages()         { return sp.getString("blocked_pkgs", ""); }

    /** Simpan seluruh sesi (dipakai saat server mengirim 'paired'). */
    public void saveSession(String sessionId, String password, boolean lock,
                            boolean block, String blockedCsv) {
        sp.edit()
          .putString("session_id", sessionId)
          .putString("password", password == null ? "" : password)
          .putBoolean("lock_enabled", lock)
          .putBoolean("block_enabled", block)
          .putString("blocked_pkgs", blockedCsv == null ? "" : blockedCsv)
          .apply();
    }

    public void clear() { sp.edit().clear().apply(); }
}
