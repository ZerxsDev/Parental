package com.company.parental;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Gerbang login HTML: form password asli yang dirender WebView (HTML/CSS/JS).
 * Password divalidasi ke sisi Java (SharedPreferences), bukan simulasi JS.
 */
public class WebViewPasswordGate extends WebView {

    public interface Callback {
        void onSuccess();
        void onWrong();
    }

    private String expectedPassword = "";
    private Callback callback;

    public WebViewPasswordGate(Context ctx) { super(wrap(ctx)); init(); }
    public WebViewPasswordGate(Context ctx, AttributeSet a) { super(wrap(ctx), a); init(); }

    /** WebView wajib di app context (Theme.MaterialComponents tersedia). */
    private static Context wrap(Context ctx) {
        if (ctx instanceof android.app.Activity) return ctx;
        return new ContextWrapper(ctx);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void init() {
        setBackgroundColor(0xFF0D47A1);
        getSettings().setJavaScriptEnabled(true);
        setWebViewClient(new WebViewClient());
        addJavascriptInterface(new Bridge(), "AndroidBridge");
    }

    public void setup(String password, Callback cb) {
        this.expectedPassword = password == null ? "" : password;
        this.callback = cb;
        loadDataWithBaseURL(null, HTML, "text/html", "utf-8", null);
    }

    private class Bridge {
        @JavascriptInterface
        public void submit(String pass) {
            final boolean ok = pass != null && pass.equals(expectedPassword);
            post(() -> {
                if (callback == null) return;
                if (ok) callback.onSuccess();
                else {
                    callback.onWrong();
                    loadUrl("javascript:wrongPass()");
                }
            });
        }
    }

    private static final String HTML =         "<!DOCTYPE html>\n" +
        "<html lang=\"id\">\n" +
        "<head>\n" +
        "<meta charset=\"utf-8\">\n" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
        "<style>\n" +
        "  * { box-sizing: border-box; margin: 0; padding: 0; font-family: sans-serif; }\n" +
        "  body {\n" +
        "    min-height: 100vh; display: flex; align-items: center; justify-content: center;\n" +
        "    background: linear-gradient(160deg,#0D47A1,#1565C0);\n" +
        "  }\n" +
        "  .card {\n" +
        "    width: 88%; max-width: 340px; background: #fff; border-radius: 16px;\n" +
        "    padding: 28px 22px; box-shadow: 0 12px 30px rgba(0,0,0,.35); text-align: center;\n" +
        "  }\n" +
        "  .lock { font-size: 42px; }\n" +
        "  h1 { font-size: 19px; color: #0D47A1; margin: 8px 0 2px; }\n" +
        "  p.sub { font-size: 12px; color: #666; margin-bottom: 18px; }\n" +
        "  input[type=password] {\n" +
        "    width: 100%; padding: 12px 14px; font-size: 15px; border: 2px solid #90A4AE;\n" +
        "    border-radius: 8px; outline: none; margin-bottom: 12px;\n" +
        "  }\n" +
        "  input[type=password]:focus { border-color: #1565C0; }\n" +
        "  button {\n" +
        "    width: 100%; padding: 12px; font-size: 15px; font-weight: bold; color: #fff;\n" +
        "    background: #1565C0; border: 0; border-radius: 8px; cursor: pointer;\n" +
        "  }\n" +
        "  button:active { background: #0D47A1; }\n" +
        "  #err { color: #D32F2F; font-size: 12px; margin-top: 10px; min-height: 16px; }\n" +
        "</style>\n" +
        "</head>\n" +
        "<body>\n" +
        "  <div class=\"card\">\n" +
        "    <div class=\"lock\">&#128274;</div>\n" +
        "    <h1>Parental Control</h1>\n" +
        "    <p class=\"sub\">Masukkan password HTML orang tua untuk membuka panel</p>\n" +
        "    <form onsubmit=\"return kirim()\">\n" +
        "      <input id=\"pw\" type=\"password\" placeholder=\"Password\" autofocus autocomplete=\"off\">\n" +
        "      <button type=\"submit\">BUKA PANEL</button>\n" +
        "    </form>\n" +
        "    <div id=\"err\"></div>\n" +
        "  </div>\n" +
        "<script>\n" +
        "  function kirim(){\n" +
        "    var v = document.getElementById('pw').value;\n" +
        "    if(!v){ document.getElementById('err').innerText='Password jangan kosong'; return false; }\n" +
        "    AndroidBridge.submit(v);\n" +
        "    return false;\n" +
        "  }\n" +
        "  function wrongPass(){\n" +
        "    document.getElementById('err').innerText='Password salah!';\n" +
        "    document.getElementById('pw').value='';\n" +
        "  }\n" +
        "</script>\n" +
        "</body>\n" +
        "</html>\n";

}
