/* ============================================================
 * firebase-app.js — Firebase Realtime Database via REST (Spark Plan)
 * Fungsi realtime asli memakai Server-Sent Events (?auth=... tidak wajib
 * bila aturan database terbuka). Tidak ada simulasi: semua request
 * langsung ke https://<db>.firebaseio.com/<path>.json
 * ============================================================ */
(function (global) {
  "use strict";

  class RestDB {
    constructor(url) {
      this.url = url.replace(/\/+$/, "");
      if (!/^https?:\/\/.+/.test(this.url)) throw new Error("URL Firebase tidak valid");
      this._es = {}; // path -> EventSource
    }

    _u(path) { return this.url + "/" + path + ".json"; }

    async get(path) {
      const r = await fetch(this._u(path), { headers: { "Content-Type": "application/json" } });
      if (!r.ok) throw new Error("HTTP " + r.status + " saat GET /" + path);
      return await r.json(); // null bila kosong
    }

    async set(path, value) {
      const r = await fetch(this._u(path), {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(value === undefined ? null : value)
      });
      if (!r.ok) throw new Error("HTTP " + r.status + " saat SET /" + path);
      return await r.json();
    }

    async update(path, partial) {
      const r = await fetch(this._u(path), {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(partial)
      });
      if (!r.ok) throw new Error("HTTP " + r.status + " saat UPDATE /" + path);
      return await r.json();
    }

    /** Subscribe perubahan realtime (SSE bawaan Firebase REST API). */
    onValue(path, cb, onError) {
      if (this._es[path]) this._es[path].close();
      const es = new EventSource(this._u(path));
      es.onmessage = (e) => {
        try { cb(JSON.parse(e.data)); } catch (err) { /* payload kosong */ }
      };
      es.onerror = () => {
        if (onError) onError();
        // browser otomatis mencoba reconnect utk EventSource
      };
      this._es[path] = es;
      return es;
    }

    off(path) {
      if (this._es[path]) { this._es[path].close(); delete this._es[path]; }
    }

    closeAll() { Object.keys(this._es).forEach(p => this.off(p)); }
  }

  global.ParentalFB = {
    init: function (dbUrl) { return new RestDB(dbUrl); }
  };
})(window);
