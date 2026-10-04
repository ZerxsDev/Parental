package com.company.parental;

/**
 * Konfigurasi koneksi ke Firebase Realtime Database (Spark Plan).
 *
 * CARA MENGISI (1 menit):
 *  1. Buka https://console.firebase.google.com -> proyek Anda (Spark Plan).
 *  2. Build -> Realtime Database -> salin URL database, contoh:
 *     "https://parental-default-rtdb.firebaseio.com"
 *  3. (Opsional) Jika memakai REST + auth token, isi FIREBASE_AUTH_TOKEN dari
 *     project settings. Untuk Spark Plan cukup pakai aturan database terbuka
 *     untuk read/write path "devices" & "commands" saat pengembangan.
 *
 * Aplikasi ini bekerja dalam 2 mode otomatis:
 *  - Mode A (Firebase SDK): jika app/google-services.json ada dan plugin
 *    google-services diaktifkan di build.gradle.
 *  - Mode B (REST fallback): jika DB_URL di bawah diisi, aplikasi menulis &
 *    membaca lewat REST API Firebase tanpa perlu google-services.json.
 */
public final class Config {

    /** Isi dengan URL Realtime Database Firebase Anda. */
    public static final String DB_URL = "https://YOUR-PROJECT-ID-default-rtdb.firebaseio.com";

    /** Kosongkan jika database memakai aturan publik utk development. */
    public static final String AUTH_PARAM = "";

    /** Interval heartbeat perangkat (ms). Spark plan sangat cukup utk ini. */
    public static final long HEARTBEAT_MS = 15_000L;

    /** Interval polling perintah via REST (ms) bila SDK Firebase tidak dipakai. */
    public static final long POLL_MS = 5_000L;

    /** Password HTML default utk membuka panel lokal anak (bisa diganti dari web). */
    public static final String DEFAULT_HTML_PASSWORD = "1234";

    private Config() {}

    public static String deviceUrl(String deviceId, String path) {
        String base = DB_URL.endsWith("/") ? DB_URL.substring(0, DB_URL.length() - 1) : DB_URL;
        StringBuilder sb = new StringBuilder(base)
                .append("/").append(path).append("/").append(deviceId).append(".json");
        if (AUTH_PARAM != null && !AUTH_PARAM.isEmpty()) {
            sb.append("?auth=").append(AUTH_PARAM);
        }
        return sb.toString();
    }

    public static String collectionUrl(String path) {
        String base = DB_URL.endsWith("/") ? DB_URL.substring(0, DB_URL.length() - 1) : DB_URL;
        StringBuilder sb = new StringBuilder(base).append("/").append(path).append(".json");
        if (AUTH_PARAM != null && !AUTH_PARAM.isEmpty()) {
            sb.append("?auth=").append(AUTH_PARAM);
        }
        return sb.toString();
    }
}
