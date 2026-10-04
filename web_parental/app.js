/* ============================================================
 * app.js — Web Panel Parental Control
 * Server: Firebase Realtime Database (Spark Plan) via REST+SSE.
 * Struktur data (sama persis dengan APK com.company.parental):
 *   devices/{id}  -> heartbeat {model, online, lockEnabled, blockEnabled,...}
 *   commands/{id} -> perintah  {action, token, packages, password}
 *   acks/{id}     -> konfirmasi eksekusi dari HP anak
 * ============================================================ */
"use strict";

const ONLINE_WINDOW_MS = 45000; // heartbeat tiap 15 dtk; >45 dtk = offline

let db = null;          // instance RestDB
let panelPassword = ""; // password HTML utk login web
let selectedDevice = null;
let devicesCache = {};

const $ = (id) => document.getElementById(id);

/* ---------------- LOGIN ---------------- */
$("btnLogin").addEventListener("click", async () => {
  const url = $("dbUrlInput").value.trim();
  const pw = $("pwInput").value;
  if (!/^https?:\/\/.+\.(firebaseio\.com|firebasedatabase\.app)(\/.*)?$/i.test(url)) {
    return msg("loginMsg", "URL harus URL Realtime Database Firebase Anda.", "err");
  }
  if (pw.length < 4) return msg("loginMsg", "Password minimal 4 karakter.", "err");

  try {
    db = window.ParentalFB.init(url);
    await db.get("devices"); // uji koneksi sungguhan ke Firebase
  } catch (e) {
    return msg("loginMsg", "Gagal koneksi: " + e.message, "err");
  }

  panelPassword = pw;
  localStorage.setItem("parental_db_url", url);
  localStorage.setItem("parental_pw", pw);

  $("loginScreen").classList.add("hidden");
  $("panelScreen").classList.remove("hidden");
  startRealtime();
});

// isi otomatis dari sesi sebelumnya
(function restore() {
  const u = localStorage.getItem("parental_db_url");
  if (u) $("dbUrlInput").value = u;
})();

$("btnLogout").addEventListener("click", () => {
  if (db) db.closeAll();
  location.reload();
});

/* ---------------- REALTIME DEVICES ---------------- */
function startRealtime() {
  $("connState").textContent = "Terhubung ✓";
  $("connState").className = "pill pill-on";

  db.onValue("devices", (data) => {
    devicesCache = data || {};
    renderDevices();
    log(JSON.stringify(devicesCache, null, 2));
  }, () => {
    $("connState").textContent = "Reconnecting…";
    $("connState").className = "pill pill-off";
  });

  db.onValue("acks", (data) => {
    if (data && selectedDevice && data[selectedDevice]) {
      const a = data[selectedDevice];
      msg("cmdMsg", "HP menjawab: " + a.lastAction +
        " (" + new Date(a.appliedAt).toLocaleTimeString() + ")", "ok");
    }
  });
}

function renderDevices() {
  const box = $("deviceBox");
  const sel = $("deviceSelect");
  const ids = Object.keys(devicesCache);
  box.innerHTML = "";
  sel.innerHTML = '<option value="">— pilih perangkat —</option>';

  if (!ids.length) {
    box.innerHTML = '<div class="empty">Belum ada perangkat terhubung.</div>';
    return;
  }

  for (const id of ids) {
    const d = devicesCache[id] || {};
    const online = (Date.now() - (d.lastSeen || 0)) < ONLINE_WINDOW_MS;
    const el = document.createElement("div");
    el.className = "dev" + (id === selectedDevice ? " sel" : "");
    el.innerHTML =
      `<b><span class="dot ${online ? "on" : "off"}"></span>${esc(d.deviceName || d.model || "Android")}</b>
       <small>ID: ${esc(id)}</small>
       <small>${esc(d.brand || "")} ${esc(d.model || "")} · Android ${esc(d.androidVersion || "?")} (API ${esc(String(d.sdkInt || "?"))})</small>
       <small>Lock: <b>${d.lockEnabled ? "ON" : "OFF"}</b> · Blokir: <b>${d.blockEnabled ? "ON" : "OFF"}</b> (${d.blockedCount || 0} app)</small>
       <small>Terakhir: ${esc(d.seenAtText || "-")}</small>`;
    el.addEventListener("click", () => selectDevice(id));
    box.appendChild(el);

    const o = document.createElement("option");
    o.value = id;
    o.textContent = `${d.deviceName || d.model || "Android"} [${id}] ${online ? "● online" : "○ offline"}`;
    sel.appendChild(o);
  }

  if (selectedDevice) syncControlsFor(selectedDevice);
}

$("deviceSelect").addEventListener("change", (e) => selectDevice(e.target.value));
$("btnRefreshOne").addEventListener("click", () => {
  if (selectedDevice) syncControlsFor(selectedDevice);
  else msg("cmdMsg", "Pilih perangkat dulu.", "err");
});

function selectDevice(id) {
  selectedDevice = id;
  $("deviceSelect").value = id;
  renderDevices();
  if (id) syncControlsFor(id);
}

function syncControlsFor(id) {
  const d = devicesCache[id] || {};
  $("tgLock").checked = !!d.lockEnabled;
  $("stLock").textContent = d.lockEnabled ? "ON" : "OFF";
  $("tgBlock").checked = !!d.blockEnabled;
  $("stBlock").textContent = d.blockEnabled ? "ON" : "OFF";
  // muat daftar package tersimpan
  db.get("commands/" + id).then((c) => {
    if (c && Array.isArray(c.packages)) $("pkgList").value = c.packages.join("\n");
  }).catch(() => {});
}

/* ---------------- PERINTAH KE FIREBASE ---------------- */
async function sendCommand(action, extra) {
  if (!selectedDevice) { msg("cmdMsg", "Pilih perangkat di kotak Perangkat Terhubung!", "err"); return false; }
  const cmd = Object.assign({ action: action, token: Date.now() }, extra || {});
  try {
    await db.set("commands/" + selectedDevice, cmd);
    msg("cmdMsg", "Perintah '" + action + "' dikirim ke perangkat " + selectedDevice + "…", "ok");
    return true;
  } catch (e) {
    msg("cmdMsg", "Gagal kirim: " + e.message, "err");
    return false;
  }
}

$("tgLock").addEventListener("change", async (e) => {
  const on = e.target.checked;
  if (await sendCommand(on ? "LOCK_ON" : "LOCK_OFF")) {
    $("stLock").textContent = on ? "ON" : "OFF";
  } else { e.target.checked = !on; }
});

$("tgBlock").addEventListener("change", async (e) => {
  const on = e.target.checked;
  if (await sendCommand(on ? "BLOCK_ON" : "BLOCK_OFF")) {
    $("stBlock").textContent = on ? "ON" : "OFF";
  } else { e.target.checked = !on; }
});

$("btnSavePkgs").addEventListener("click", async () => {
  const pkgs = $("pkgList").value.split(/\s+/).map(s => s.trim()).filter(Boolean);
  if (!pkgs.length) return msg("cmdMsg", "Daftar package kosong.", "err");
  await sendCommand("SET_BLOCK_LIST", {
    packages: pkgs,
    enabled: $("tgBlock").checked
  });
});

$("btnSetPw").addEventListener("click", async () => {
  const p = $("newPw").value.trim();
  if (p.length < 4) return msg("cmdMsg", "Password minimal 4 karakter.", "err");
  if (await sendCommand("SET_HTML_PASSWORD", { password: p })) {
    panelPassword = p;
    localStorage.setItem("parental_pw", p);
    $("newPw").value = "";
  }
});

/* ---------------- UTIL ---------------- */
function msg(id, text, cls) {
  const el = $(id);
  el.textContent = text;
  el.className = "msg " + (cls || "");
}
function log(text) { $("rawLog").textContent = text; }
function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// refresh label "online" tiap 10 detik tanpa request baru
setInterval(() => { if (db) renderDevices(); }, 10000);
