/**
 * parental - Server Parental Control
 * Node.js + Express + Socket.IO
 *
 * Jalankan  : npm install && npm start
 * Buka web  : http://LHOST:8888  (dari browser laptop/HP orang tua)
 * APK anak  : hubungkan ke http://LHOST:8888 (Config.java LHOST/LPORT)
 *
 * Protokol socket.io:
 *  child -> server : pair {code, deviceName, androidVersion, appPackage}
 *                    hello {sessionId}            (reconnect, rebind sesi)
 *                    state {sessionId, ...}       (lapor state saat ini)
 *  server -> child : paired {sessionId, password, lockEnabled, blockEnabled, blockedPackages}
 *                    pair_error {message}
 *                    control_update {password?, lockEnabled?, blockEnabled?, blockedPackages?}
 *                    unlinked
 *  parent -> server: parent_login {code}         (masukkan kode 6 digit dari apk)
 *                    set_password {password}
 *                    set_lock {enabled}
 *                    set_block {enabled}
 *                    add_blocked {pkg}
 *                    remove_blocked {pkg}
 *                    unpair
 *  server -> parent: login_ok {device, state}
 *                    login_error {message}
 *                    child_state {...}           (info perangkat & state realtime)
 *                    unpaired
 */

const path = require('path');
const crypto = require('crypto');
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');

const PORT = process.env.PORT ? parseInt(process.env.PORT) : 8888; // LPORT

const app = express();
app.use(express.static(path.join(__dirname, 'public')));

const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

/**
 * devices: Map<code, Device>
 * Device = {
 *   code, sessionId, socketId, online,
 *   info: { deviceName, androidVersion, appPackage },
 *   state: { password, lockEnabled, blockEnabled, blockedPackages: [] },
 *   parentSocketId
 * }
 */
const devices = new Map();

function publicState(d) {
  return {
    code: d.code,
    sessionId: d.sessionId,
    online: d.online,
    lastSeen: d.lastSeen,
    info: d.info,
    state: d.state,
  };
}

/** Cari device berdasar sessionId atau socket. */
function findDeviceByParent(socket) {
  for (const d of devices.values()) {
    if (d.parentSocketId === socket.id) return d;
  }
  return null;
}

function pushToChild(d, event, payload) {
  if (d.socketId) io.to(d.socketId).emit(event, payload);
}
function pushToParent(d, event, payload) {
  if (d.parentSocketId) io.to(d.parentSocketId).emit(event, payload);
}

io.on('connection', (socket) => {

  // ============================ PERANGKAT ANAK (APK) ============================

  socket.on('pair', (msg) => {
    try {
      const code = String(msg.code || '').trim();
      if (!/^\d{6}$/.test(code)) {
        return socket.emit('pair_error', { message: 'Kode pairing tidak valid' });
      }

      let d = devices.get(code);
      if (!d) {
        d = {
          code,
          sessionId: crypto.randomBytes(12).toString('hex'),
          socketId: null,
          parentSocketId: null,
          online: false,
          lastSeen: Date.now(),
          info: {
            deviceName: msg.deviceName || 'Unknown Android',
            androidVersion: msg.androidVersion || '?',
            appPackage: msg.appPackage || 'com.company.children',
          },
          state: {
            password: '',
            lockEnabled: false,
            blockEnabled: false,
            blockedPackages: [],
          },
        };
        devices.set(code, d);
        console.log(`[pair] perangkat baru dibuat, kode=${code} sesi=${d.sessionId}`);
      } else {
        d.info = {
          deviceName: msg.deviceName || d.info.deviceName,
          androidVersion: msg.androidVersion || d.info.androidVersion,
          appPackage: msg.appPackage || d.info.appPackage,
        };
      }

      d.socketId = socket.id;
      d.online = true;
      d.lastSeen = Date.now();
      socket.data.sessionId = d.sessionId;

      // Kirim ulang seluruh sesi ke apk agar tersimpan di hp anak
      socket.emit('paired', {
        sessionId: d.sessionId,
        password: d.state.password,
        lockEnabled: d.state.lockEnabled,
        blockEnabled: d.state.blockEnabled,
        blockedPackages: d.state.blockedPackages,
      });
      if (d.parentSocketId) pushToParent(d, 'child_state', publicState(d));
      console.log(`[pair] perangkat kode=${code} connect socket=${socket.id}`);
    } catch (e) {
      console.error('pair error', e);
    }
  });

  // Reconnect: apk sudah punya sessionId, rebind socket
  socket.on('hello', (msg) => {
    const sid = msg && msg.sessionId;
    for (const d of devices.values()) {
      if (d.sessionId === sid) {
        d.socketId = socket.id;
        d.online = true;
        d.lastSeen = Date.now();
        socket.data.sessionId = d.sessionId;
        socket.emit('paired', {
          sessionId: d.sessionId,
          password: d.state.password,
          lockEnabled: d.state.lockEnabled,
          blockEnabled: d.state.blockEnabled,
          blockedPackages: d.state.blockedPackages,
        });
        if (d.parentSocketId) pushToParent(d, 'child_state', publicState(d));
        console.log(`[hello] rebind sesi=${sid} socket=${socket.id}`);
        return;
      }
    }
    // sesi tidak dikenal -> minta pairing ulang
    socket.emit('unlinked', { message: 'Sesi tidak dikenal, pairing ulang' });
  });

  // ============================ WEB ORANG TUA ============================

  socket.on('parent_login', (msg) => {
    const code = String((msg && msg.code) || '').trim();
    const d = devices.get(code);
    if (!d) {
      return socket.emit('login_error', {
        message: `Kode ${code} tidak ditemukan. Pastikan aplikasi children di hp anak sudah terbuka dan terhubung ke server ini.`,
      });
    }
    if (d.parentSocketId && d.parentSocketId !== socket.id) {
      io.to(d.parentSocketId).emit('unpaired', { message: 'Diambil alih oleh sesi parental lain.' });
    }
    d.parentSocketId = socket.id;
    socket.data.code = code;
    socket.emit('login_ok', { device: publicState(d) });
    console.log(`[parent] login menautkan kode=${code} socket=${socket.id}`);
  });

  function withDevice(socket, fn) {
    const d = devices.get(socket.data.code) || findDeviceByParent(socket);
    if (!d) return socket.emit('login_error', { message: 'Belum login' });
    fn(d);
  }

  socket.on('set_password', (msg) => withDevice(socket, (d) => {
    d.state.password = String((msg && msg.password) || '');
    pushToChild(d, 'control_update', { password: d.state.password });
    pushToParent(d, 'child_state', publicState(d));
    console.log(`[ctrl] kode=${d.code} password diubah`);
  }));

  socket.on('set_lock', (msg) => withDevice(socket, (d) => {
    d.state.lockEnabled = !!(msg && msg.enabled);
    pushToChild(d, 'control_update', { lockEnabled: d.state.lockEnabled });
    pushToParent(d, 'child_state', publicState(d));
    console.log(`[ctrl] kode=${d.code} lock=${d.state.lockEnabled}`);
  }));

  socket.on('set_block', (msg) => withDevice(socket, (d) => {
    d.state.blockEnabled = !!(msg && msg.enabled);
    pushToChild(d, 'control_update', { blockEnabled: d.state.blockEnabled });
    pushToParent(d, 'child_state', publicState(d));
    console.log(`[ctrl] kode=${d.code} block=${d.state.blockEnabled}`);
  }));

  socket.on('add_blocked', (msg) => withDevice(socket, (d) => {
    const pkg = String((msg && msg.pkg) || '').trim();
    if (!pkg) return;
    if (!d.state.blockedPackages.includes(pkg)) d.state.blockedPackages.push(pkg);
    pushToChild(d, 'control_update', { blockedPackages: d.state.blockedPackages });
    pushToParent(d, 'child_state', publicState(d));
    console.log(`[ctrl] kode=${d.code} blokir +${pkg}`);
  }));

  socket.on('remove_blocked', (msg) => withDevice(socket, (d) => {
    const pkg = String((msg && msg.pkg) || '').trim();
    d.state.blockedPackages = d.state.blockedPackages.filter(p => p !== pkg);
    pushToChild(d, 'control_update', { blockedPackages: d.state.blockedPackages });
    pushToParent(d, 'child_state', publicState(d));
    console.log(`[ctrl] kode=${d.code} blokir -${pkg}`);
  }));

  socket.on('unpair', () => withDevice(socket, (d) => {
    pushToChild(d, 'unlinked', {});
    devices.delete(d.code);
    socket.data.code = null;
    d.parentSocketId = null;
    socket.emit('unpaired', { message: 'Perangkat dilepas dari parental.' });
    console.log(`[ctrl] kode=${d.code} unpair oleh parent`);
  }));

  // ============================ DISCONNECT ============================

  socket.on('disconnect', () => {
    for (const d of devices.values()) {
      if (d.socketId === socket.id) {
        d.socketId = null;
        d.online = false;
        if (d.parentSocketId) pushToParent(d, 'child_state', publicState(d));
        console.log(`[child] offline kode=${d.code}`);
      }
      if (d.parentSocketId === socket.id) {
        d.parentSocketId = null;
      }
    }
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('==================================================');
  console.log(' parental - Web Parental Control berjalan');
  console.log(` Server  : http://0.0.0.0:${PORT}`);
  console.log(` Web     : http://IP-WIFI-ORTU:${PORT}`);
  console.log(' APK anak harus mengisi LHOST = IP WiFi yang sama');
  console.log('==================================================');
});
