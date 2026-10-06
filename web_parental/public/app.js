/**
 * parental 1.0 — logika web orang tua
 * Alur:
 *  1. Buka website => minta kode 6 digit (dari kotak aplikasi children).
 *  2. Pair sukses  => tampilkan KOTAK INFO PERANGKAT + KOTAK CONTROL.
 *  3. Control:
 *     - Kunci layar : tombol ON/OFF + indikator pill + teks password ortu
 *     - Blokir app  : tombol ON/OFF + input package + list yang diblokir
 */
const socket = io();   // otomatis connect ke server yg menyajikan halaman ini

// ---------- elemen ----------
const $ = (id) => document.getElementById(id);
const viewPair = $('viewPair'), viewDash = $('viewDash');
const connBadge = $('connBadge'), pairMsg = $('pairMsg');

let sessionId = localStorage.getItem('parental_session') || null;
let lockActive = false, blockActive = false;
let blockedList = [];      // array package
let hasPassword = false, passText = '';

// ---------- koneksi server ----------
socket.on('connect', () => {
  connBadge.textContent = 'server: terhubung';
  connBadge.className = 'badge on';
  // Bila pernah pair sebelumnya, langsung subscribe sesi lama
  if (sessionId) socket.emit('subscribe', { sessionId });
});
socket.on('disconnect', () => {
  connBadge.textContent = 'server: terputus';
  connBadge.className = 'badge off';
});

// ---------- pairing dengan kode 6 digit ----------
$('btnPair').onclick = () => {
  const code = $('inpCode').value.trim();
  if (!/^\d{6}$/.test(code)) {
    setPairMsg('Kode harus 6 digit angka.', true); return;
  }
  setPairMsg('Menghubungi perangkat…', false);
  socket.emit('pair', { code }, (res) => {
    if (res && res.ok) {
      sessionId = res.sessionId;
      localStorage.setItem('parental_session', sessionId);
      showDash();
    } else {
      setPairMsg((res && res.msg) || 'Gagal menautkan.', true);
    }
  });
};
function setPairMsg(t, err) {
  pairMsg.textContent = t;
  pairMsg.className = 'msg ' + (err ? 'err' : 'ok');
}

// ---------- status real-time dari APK anak ----------
socket.on('status', (msg) => {
  // kotak info perangkat
  const d = msg.device || {}, st = msg.state || {};
  $('devOnline').innerHTML = '<span class="dot ' + (msg.online ? 'on' : 'off') + '"></span>'
    + (msg.online ? 'online (terhubung)' : 'offline (HP mati/aplikasi ditutup)');
  $('devModel').textContent = d.model || '-';
  $('devAndroid').textContent = d.android || '-';
  $('devVersion').textContent = d.version || '-';
  $('devSession').textContent = d.sessionId || sessionId || '-';
  $('devPairedAt').textContent = d.pairedAt ? new Date(d.pairedAt).toLocaleString() : '-';

  // sinkron state kontrol
  lockActive = !!st.lockActive;
  blockActive = !!st.blockActive;
  blockedList = (st.blockedList || '').split(',').filter(Boolean);
  if (st.hasPassword !== undefined) hasPassword = st.hasPassword;

  renderControls();

  // notifikasi aplikasi yang baru saja dicoba anak
  if (st.blockedPkg) {
    $('lastBlocked').classList.remove('hidden');
    $('lastBlockedPkg').textContent = st.blockedPkg;
  } else {
    $('lastBlocked').classList.add('hidden');
  }
});

// ---------- render indikator/tombol/list ----------
function renderControls() {
  // kunci layar
  $('lockInd').textContent = lockActive ? 'ON' : 'OFF';
  $('lockInd').className = 'pill ' + (lockActive ? 'on' : 'off');
  $('btnLock').textContent = lockActive ? 'Buka' : 'Kunci';
  $('passText').textContent = hasPassword ? passText : '(belum diatur)';

  // blokir aplikasi
  $('blockInd').textContent = blockActive ? 'ON' : 'OFF';
  $('blockInd').className = 'pill ' + (blockActive ? 'on' : 'off');
  $('btnBlock').textContent = blockActive ? 'Nonaktifkan' : 'Aktifkan';

  const ul = $('listBlocked');
  ul.innerHTML = '';
  if (blockedList.length === 0) {
    ul.innerHTML = '<li style="background:#eceff1;border:none;color:#78909c">- kosong -</li>';
  } else {
    blockedList.forEach((p) => {
      const li = document.createElement('li');
      const s = document.createElement('span'); s.textContent = p;
      const x = document.createElement('button');
      x.className = 'small ghost'; x.textContent = '✕';
      x.onclick = () => {
        blockedList = blockedList.filter(i => i !== p);
        sendBlockList();
      };
      li.appendChild(s); li.appendChild(x); ul.appendChild(li);
    });
  }
}

// ---------- aksi kontrol ----------
$('btnLock').onclick = () => {
  socket.emit('cmd', { action: lockActive ? 'unlock' : 'lock' });
  lockActive = !lockActive;         // optimistik; server/APK akan mengoreksi
  renderControls();
};

$('btnSetPass').onclick = () => {
  const p = $('inpPass').value;
  if (!p) { alert('Password tidak boleh kosong.'); return; }
  passText = p; hasPassword = true;
  localStorage.setItem('parental_pass', p);   // utk tampilan teks di web saja
  socket.emit('cmd', { action: 'setPassword', password: p });
  renderControls();
};

$('btnForceLock').onclick = () => socket.emit('cmd', { action: 'forceLock' });

$('btnAddPkg').onclick = () => {
  const v = $('inpPkg').value.trim();
  if (!v) return;
  if (!blockedList.includes(v)) blockedList.push(v);
  $('inpPkg').value = '';
  sendBlockList();
};

function sendBlockList() {
  blockActive = blockedList.length > 0;
  socket.emit('cmd', { action: blockActive ? 'blockApps' : 'unblockApps', list: blockedList });
  renderControls();
}

$('btnBlock').onclick = () => {
  if (blockedList.length === 0 && !blockActive) {
    alert('Tambahkan minimal satu package aplikasi dulu.'); return;
  }
  blockActive = !blockActive;
  socket.emit('cmd', { action: blockActive ? 'blockApps' : 'unblockApps', list: blockedList });
  renderControls();
};

$('btnUnpair').onclick = () => {
  if (!confirm('Putuskan tautan perangkat anak?')) return;
  socket.emit('unpair', {});
  localStorage.removeItem('parental_session');
  sessionId = null;
  viewDash.classList.add('hidden');
  viewPair.classList.remove('hidden');
  setPairMsg('Tautan diputus.', false);
};

// ---------- navigasi antar layar ----------
function showDash() {
  viewPair.classList.add('hidden');
  viewDash.classList.remove('hidden');
  passText = localStorage.getItem('parental_pass') || '';
  renderControls();
}

// Restore sesi saat refresh halaman
window.addEventListener('load', () => {
  if (sessionId) showDash();
});
