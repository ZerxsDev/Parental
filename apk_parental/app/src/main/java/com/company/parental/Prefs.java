package com.company.parental;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Penyimpanan state lokal perangkat anak. */
public class Prefs {
    private static final String FILE = "parental_prefs";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** ID unik perangkat (dipakai sebagai key di Firebase). */
    public String getDeviceId() {
        String id = sp.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            sp.edit().putString("device_id", id).apply();
        }
        return id;
    }

    // ===== Kunci layar (lock) =====
    public boolean isLockEnabled()      { return sp.getBoolean("lock_enabled", false); }
    public void setLockEnabled(boolean b){ sp.edit().putBoolean("lock_enabled", b).apply(); }

    // ===== Blokir aplikasi =====
    public boolean isBlockListEnabled() { return sp.getBoolean("block_enabled", false); }
    public void setBlockListEnabled(boolean b) { sp.edit().putBoolean("block_enabled", b).apply(); }

    public Set<String> getBlockedPackages() {
        return new HashSet<>(sp.getStringSet("blocked_pkgs", Collections.emptySet()));
    }

    public void setBlockedPackages(Set<String> pkgs) {
        sp.edit().putStringSet("blocked_pkgs", new HashSet<>(pkgs)).apply();
    }

    // ===== Password HTML panel lokal =====
    public String getHtmlPassword() {
        return sp.getString("html_password", Config.DEFAULT_HTML_PASSWORD);
    }

    public void setHtmlPassword(String pw) {
        sp.edit().putString("html_password", pw).apply();
    }

    /** Token terakhir yang sudah diproses (anti perintah ganda). */
    public long getLastCmdToken() { return sp.getLong("last_cmd", 0L); }
    public void setLastCmdToken(long t) { sp.edit().putLong("last_cmd", t).apply(); }
}
