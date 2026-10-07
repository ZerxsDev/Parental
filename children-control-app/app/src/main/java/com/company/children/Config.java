package com.company.children;

/**
 * Konfigurasi koneksi ke server parental.
 *
 * PENTING: ubah LHOST menjadi IP WiFi laptop/PC orang tua
 * (tempat server Node.js berjalan). Keduanya harus berada
 * di jaringan WiFi yang sama. Contoh: "192.168.1.7".
 */
public final class Config {
    public static final String LHOST = "192.168.1.100"; // IP WIFI ORTU
    public static final int LPORT = 8888;               // PORT server
    public static final String SERVER_URL = "http://" + LHOST + ":" + LPORT;

    private Config() {}
}
