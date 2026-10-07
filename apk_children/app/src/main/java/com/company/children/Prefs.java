package com.company.children;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Penyimpanan sesi & state kontrol (SharedPreferences).
 * Nilai-nilai ini dipertahankan antar restart aplikasi.
 */
public class Prefs {
    private static final String FILE = "children_session";

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- Sesi pairing ----
    public static String getCode(Context c)      { return get(c).getString("code", null); }
    public static void   setCode(Context c, String v) { get(c).edit().putString("code", v).apply(); }

    public static boolean isLinked(Context c)    { return get(c).getBoolean("linked", false); }
    public static void    setLinked(Context c, boolean v) { get(c).edit().putBoolean("linked", v).apply(); }

    // ---- State dari web parental ----
    public static boolean isLockOn(Context c)    { return get(c).getBoolean("lock_on", false); }
    public static void    setLockOn(Context c, boolean v) { get(c).edit().putBoolean("lock_on", v).apply(); }

    public static String getPassword(Context c)  { return get(c).getString("password", ""); }
    public static void    setPassword(Context c, String v) { get(c).edit().putString("password", v).apply(); }

    public static boolean isBlockOn(Context c)   { return get(c).getBoolean("block_on", false); }
    public static void    setBlockOn(Context c, boolean v) { get(c).edit().putBoolean("block_on", v).apply(); }

    /** Daftar package yang diblokir, dipisah koma. */
    public static String getBlockListRaw(Context c) { return get(c).getString("block_list", ""); }
    public static void   setBlockListRaw(Context c, String v) { get(c).edit().putString("block_list", v).apply(); }

    public static void clearAll(Context c)       { get(c).edit().clear().apply(); }
}
