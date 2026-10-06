/**
 * parental — server Node.js + Socket.IO (versi 1.0)
 * ------------------------------------------------
 * Menjembatani aplikasi "children" di HP anak dengan website parental.
 *
 * Protokol event:
 *  APK -> server : "register" {pairCode, model, android, version}   (belum bertaut)
 *                  "hello"    {sessionId, token, model, android}     (reconnect)
 *                  "state"    {lockActive, blockActive, blockedList, hasPassword, blockedPkg}
 *  Server -> APK : "paired"   {sessionId, token}   => APK menyimpan SESI
 *                  "cmd"      {action:'lock'|'unlock'|'setPassword'|'blockApps'|'unblockApps'|'forceLock'}
 *                  "unpair"   => APK kembali ke halaman kode
 *  Web   -> server : "pair" {code} | "subscribe" {sessionId} | "cmd" {...} | "unpair" {}
 *  Server -> Web  : "status" {online, device:{...}, state:{...}}
 *
 * Jalankan: npm install && node server.js  → buka http://IP-KOMPUTER:3000
 */
const express = require('express');
const http = require('http');
const crypto = require('crypto');
const path = require('path');
const os = require('os');
const { Server } = require('socket.io');

const PORT = process.env.PORT || 3000;

const app = express();
app.use(express.static(path.join(__dirname, 'public')));

const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

/* ============================ STATE SERVER ============================ */
const childSockets = new Map();  // pairCode  -> socket anak (belum ditautkan)
const devices      = new Map();  // sessionId -> data perangkat (bertahan s/d unpair)
const parentSockets = new Map(); // sessionId -> Set(socket web orang tua)

function randHex(n) { return crypto.randomBytes(n).toString('hex'); }

function sendToChild(sessionId, event, payload) {
  const d = devices.get(sessionId);
  if (!d || !d.socket || !d.socket.connected) return false;
  d.socket.emit(event, payload);
  return true;
}

/** Kirim kondisi perangkat terbaru ke semua tab web yang subscribe sesi ini */
function pushStatus(sessionId) {
  const d = devices.get(sessionId);
  if (!d) return;
  const set = parentSockets.get(sessionId);
  if (!set || set.size === 0) return;
  const msg = {
    online: !!(d.socket && d.socket.connected),
    device: {
      sessionId,
      model: d.info.model || '-',
      android: d.info.android || '-',
      version: d.info.version || '1.0',
      pairedAt: d.pairedAt
    },
    state: d.state
  };
  set.forEach(s => s.emit('status', msg));
}

/* ============================== SOCKET IO ============================= */
io.on('connection', (socket) => {

  /* ---------- sisi APLIKASI ANAK (children) ---------- */

  // Anak BELUM punya sesi: daftarkan kode 6 digitnya agar bisa ditautkan web
  socket.on('register', (data) => {
    if (!data || !data.pairCode) return;
    const code = String(data.pairCode);
    childSockets.set(code, socket);
    socket.data.kind = 'child';
    socket.data.pairCode = code;
    // simpan info perangkat utk "kotak info perangkat" nanti
    socket.__info = {
      model: data.model || '-',
      android: data.android || '-',
      version: data.version || '1.0'
    };
    console.log('[child] register kode', code, socket.__info.model);
  });

  // Anak SUDAH punya sesi (reconnect / restart service): sambungkan kembali
  socket.on('hello', (data) => {
    if (!data || !data.sessionId || !data.token) return;
    const d = devices.get(data.sessionId);
    if (!d || d.token !== data.token) {
      socket.emit('unpair');          // sesi sudah dihapus => pairing ulang
      return;
    }
    d.socket = socket;
    if (data.model) d.info.model = data.model;
    if (data.android) d.info.android = data.android;
    socket.data.kind = 'child';
    socket.data.sessionId = data.sessionId;
    console.log('[child] reconnect sesi', data.sessionId);
    pushStatus(data.sessionId);
  });

  // Anak melapor state AKTUAL (habis terima perintah / watchdog blokir aktif)
  socket.on('state', (st) => {
    const sid = socket.data.sessionId;
    if (!sid) return;
    const d = devices.get(sid);
    if (d) {
      d.state = Object.assign({}, d.state, st || {});
      pushStatus(sid);
    }
  });

  /* ---------- sisi WEBSITE PARENTAL ---------- */

  // Orang tua memasukkan KODE 6 DIGIT dari kotak APK => tautkan perangkat
  socket.on('pair', (data, cb) => {
    const reply = typeof cb === 'function' ? cb : () => {};
    const code = String((data && data.code) || '').trim();
    const child = childSockets.get(code);
    if (!child) {
      reply({ ok: false, msg: 'Kode tidak ditemukan. Pastikan aplikasi children terbuka dan kode benar.' });
      return;
    }
    childSockets.delete(code);

    const sessionId = randHex(6);
    const token = randHex(16);
    devices.set(sessionId, {
      socket: child,                                   // socket ANAK
      token,
      info: child.__info || { model: '-', android: '-', version: '1.0' },
      state: {
        lockActive: false, blockActive: false,
        blockedList: '', hasPassword: false, blockedPkg: ''
      },
      pairedAt: new Date().toISOString()
    });

    child.data.kind = 'child';
    child.data.sessionId = sessionId;
    child.data.pairCode = null;   // kode sudah terpakai, jangan hapus mapping saat disconnect

    // Beri tahu APK: SIMPAN SESI lalu tampilkan dashboard anak
    child.emit('paired', { sessionId, token });

    socket.data.kind = 'parent';
    socket.data.sessionId = sessionId;
    if (!parentSockets.has(sessionId)) parentSockets.set(sessionId, new Set());
    parentSockets.get(sessionId).add(socket);

    reply({ ok: true, sessionId });
    pushStatus(sessionId);
    console.log('[parent] pair sukses kode', code, '=> sesi', sessionId);
  });

  // Web subscribe sesi (mis. setelah refresh, sessionId disimpan di localStorage)
  socket.on('subscribe', (data) => {
    const sid = data && data.sessionId;
    if (!sid || !devices.has(sid)) return;
    socket.data.kind = 'parent';
    socket.data.sessionId = sid;
    if (!parentSockets.has(sid)) parentSockets.set(sid, new Set());
    parentSockets.get(sid).add(socket);
    pushStatus(sid);
  });

  // ORANG TUA MENGIRIM PERINTAH KONTROL ke aplikasi anak
  socket.on('cmd', (data) => {
    const sid = (data && data.sessionId) || socket.data.sessionId;
    if (!sid || !devices.has(sid)) return;
    const d = devices.get(sid);
    const action = data.action;

    if (action === 'setPassword') {
      // password dibuat orang tua di web, dikirim ke APK utk disimpan lokal
      sendToChild(sid, 'cmd', { action, password: String(data.password || '') });
      return;
    }
    if (action === 'lock') {
      d.state.lockActive = true;                    // optimistik => indikator ON
      sendToChild(sid, 'cmd', { action });
      pushStatus(sid);
      return;
    }
    if (action === 'unlock') {
      d.state.lockActive = false;
      sendToChild(sid, 'cmd', { action });
      pushStatus(sid);
      return;
    }
    if (action === 'blockApps') {
      d.state.blockActive = true;
      d.state.blockedList = (data.list || []).join(',');
      sendToChild(sid, 'cmd', { action, list: data.list || [] });
      pushStatus(sid);
      return;
    }
    if (action === 'unblockApps') {
      d.state.blockActive = false;
      d.state.blockedList = '';
      sendToChild(sid, 'cmd', { action });
      pushStatus(sid);
      return;
    }
    if (action === 'forceLock') {
      sendToChild(sid, 'cmd', { action });          // device admin: matikan layar
    }
  });

  // Web memutuskan tautan
  socket.on('unpair', (data) => {
    const sid = (data && data.sessionId) || socket.data.sessionId;
    if (!sid) return;
    const d = devices.get(sid);
    if (d && d.socket) d.socket.emit('unpair');
    devices.delete(sid);
    parentSockets.delete(sid);
    console.log('[parent] unpair sesi', sid);
  });

  /* ---------- putus koneksi ---------- */
  socket.on('disconnect', () => {
    if (socket.data.kind === 'child' && socket.data.sessionId) {
      const d = devices.get(socket.data.sessionId);
      if (d && d.socket === socket) { d.socket = null; pushStatus(socket.data.sessionId); }
    } else if (socket.data.kind === 'parent' && socket.data.sessionId) {
      const set = parentSockets.get(socket.data.sessionId);
      if (set) set.delete(socket);
    } else if (socket.data.pairCode) {
      childSockets.delete(socket.data.pairCode);
    }
  });
});

/* ================================ HTTP ================================ */
server.listen(PORT, '0.0.0.0', () => {
  const nets = os.networkInterfaces();
  const ips = [];
  for (const k of Object.keys(nets))
    for (const n of nets[k])
      if (n.family === 'IPv4' && !n.internal) ips.push(n.address);
  console.log('======================================================');
  console.log(' parental server jalan:');
  console.log('   lokal : http://localhost:' + PORT);
  ips.forEach(ip => console.log('   LAN   : http://' + ip + ':' + PORT));
  console.log(' Isi alamat LAN tsb di Config.SERVER_URL aplikasi children.');
  console.log('======================================================');
});
