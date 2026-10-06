package com.company.children;

/**
 * Konfigurasi koneksi ke server parental (node.js + socket.io).
 *
 * >>> YANG HARUS KAMU UBAH <<<
 * SERVER_URL = alamat IP komputer tempat "node server.js" berjalan,
 * port 3000. HP anak & komputer harus berada di satu WiFi/LAN yang sama,
 * atau gunakan tunnel (mis. ngrok) lalu isi URL tunnel-nya.
 * Contoh: "http://192.168.1.10:3000"
 */
public final class Config {
    public static String SERVER_URL = "http://192.168.1.100:3000";
    public static final int PORT = 3000;

    private Config() {}
}
