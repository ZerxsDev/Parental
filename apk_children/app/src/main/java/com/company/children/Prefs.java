package com.company.children;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Penyimpanan lokal (SharedPreferences): kode pasangan 6 digit, sesi
 * (sessionId + token) yang dikirim server setelah web menautkan perangkat,
 * dan state kontrol dari orang tua.
 */
public class Prefs {
    private static final String FILE = "children_prefs";

    // Kunci penyimpanan
    public static final String K_SERVER   = "server_url";
    public static final String K_PAIR     = "pair_code";     // 6 digit random utk web
    public static final String K_SESSION  = "session_id";    // sesi dari server
    public static final String K_TOKEN    = "auth_token";    // token utk re-Connect
    public static final String K_PWD      = "lock_password"; // password dr ortu
    public static final String K_LOCK     = "screen_lock";   // boolean mode kunci
    public static final String K_BLOCK    = "block_enabled"; // boolean blokir app
    public static final String K_LIST     = "blocked_list";  // csv package diblokir

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        // Server URL tersimpan diprioritaskan bila pernah diubah user
        String saved = sp.getString(K_SERVER, null);
        if (saved != null && !saved.isEmpty()) Config.SERVER_URL = saved;
    }

    public void put(String k, String v)  { sp.edit().putString(k, v).apply(); }
    public void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
    public String get(String k, String def)   { return sp.getString(k, def); }
    public boolean get(String k, boolean def) { return sp.getBoolean(k, def); }

    /** Sesi dianggap tersimpan bila sessionId & token ada => langsung dashboard */
    public boolean hasSession() {
        return get(K_SESSION, "") != null && !get(K_SESSION, "").isEmpty()
            && !get(K_TOKEN, "").isEmpty();
    }

    /** Hapus tautan/sesi (unpair) */
    public void clearSession() {
        sp.edit().remove(K_SESSION).remove(K_TOKEN).remove(K_PAIR).apply();
    }
}
