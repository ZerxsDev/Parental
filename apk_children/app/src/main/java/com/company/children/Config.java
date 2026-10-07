package com.company.children;

/**
 * Konfigurasi koneksi ke server parental control.
 *
 * PENTING - YANG HARUS DIUBAH:
 *  Ganti LHOST di bawah dengan IP komputer tempat server node.js berjalan
 *  (IP orang tua). Cara cek: jalankan `hostname -I` (Linux) atau `ipconfig`
 *  (Windows) di PC orang tua. HP anak dan PC orang tua harus satu WiFi/Jaringan.
 */
public final class Config {
    public static final String LHOST = "192.168.1.10"; // <-- IP ORANG TUA (WAJIB DIGANTI)
    public static final int    LPORT = 8888;
    public static final String SERVER_URL = "http://" + LHOST + ":" + LPORT;
    public static final String APP_VERSION = "1.0";

    private Config() {}
}
