import { initAudio, playShatter, playHit, playMiss, playThrow } from './audio.js';
import {
  snapshot, makeShards, updateShards, drawShards,
  makeCracks, drawCracks, makeDebris, drawWreck,
} from './shatter.js';

// COCO-SSD sınıflarından kırılabilir sayılanlar
const BREAKABLE = {
  'bottle':        { label: 'Şişe',       hp: 1, points: 100, material: 'glass' },
  'wine glass':    { label: 'Kadeh',      hp: 1, points: 150, material: 'glass' },
  'cup':           { label: 'Bardak',     hp: 1, points: 80,  material: 'ceramic' },
  'vase':          { label: 'Vazo',       hp: 1, points: 200, material: 'ceramic' },
  'bowl':          { label: 'Kase',       hp: 1, points: 80,  material: 'ceramic' },
  'clock':         { label: 'Saat',       hp: 2, points: 180, material: 'glass' },
  'potted plant':  { label: 'Saksı',      hp: 2, points: 120, material: 'ceramic' },
  'tv':            { label: 'Televizyon', hp: 3, points: 400, material: 'electronic' },
  'laptop':        { label: 'Laptop',     hp: 2, points: 300, material: 'electronic' },
  'cell phone':    { label: 'Telefon',    hp: 1, points: 150, material: 'electronic' },
  'mouse':         { label: 'Fare',       hp: 1, points: 60,  material: 'electronic' },
  'remote':        { label: 'Kumanda',    hp: 1, points: 60,  material: 'electronic' },
  'keyboard':      { label: 'Klavye',     hp: 2, points: 100, material: 'electronic' },
  'microwave':     { label: 'Mikrodalga', hp: 3, points: 300, material: 'electronic' },
  'toaster':       { label: 'Tost Mak.',  hp: 2, points: 200, material: 'electronic' },
  'manual':        { label: 'Hedef',      hp: 2, points: 150, material: 'glass' },
};

const AMMO = {
  ball:  { radius: 34, duration: 0.55, power: 0.8, damage: 1, hitPad: 22 },
  stone: { radius: 22, duration: 0.38, power: 1.25, damage: 2, hitPad: 8 },
};

const WRECK_MS = 6000;     // kırılan nesnenin "yok" görüneceği süre
const LOST_MS = 1200;      // görülmeyen hedefin silinme süresi
const RELOAD_MS = 280;

const $ = (id) => document.getElementById(id);
const video = $('camera');
const canvas = $('fx');
const ctx = canvas.getContext('2d');

const state = {
  W: 0, H: 0, dpr: 1,
  map: { scale: 1, ox: 0, oy: 0 },
  modelReady: false,
  targets: [],          // {id, cls, x,y,w,h, hp, lastSeen, manual, cracks, brokenUntil, wreck}
  projectiles: [],
  shards: [],
  particles: [],
  popups: [],
  ammo: 'ball',
  showBoxes: true,
  markMode: false,
  drawing: null,
  pointer: null,
  lastThrow: 0,
  shake: 0,
  flash: 0,
  score: 0, broken: 0, shots: 0,
  nextId: 1,
};

const stoneShape = Array.from({ length: 9 }, (_, i) => {
  const a = (i / 9) * Math.PI * 2;
  return [Math.cos(a) * (0.75 + Math.random() * 0.3), Math.sin(a) * (0.7 + Math.random() * 0.3)];
});

// ---------- Kurulum ----------
function resize() {
  state.dpr = Math.min(window.devicePixelRatio || 1, 2);
  state.W = window.innerWidth;
  state.H = window.innerHeight;
  canvas.width = state.W * state.dpr;
  canvas.height = state.H * state.dpr;
  updateMap();
}

// object-fit: cover ile video koordinatı → ekran koordinatı
function updateMap() {
  const vw = video.videoWidth || state.W, vh = video.videoHeight || state.H;
  const scale = Math.max(state.W / vw, state.H / vh);
  state.map = { scale, ox: (state.W - vw * scale) / 2, oy: (state.H - vh * scale) / 2 };
}

async function start() {
  $('error').textContent = '';
  initAudio();
  try {
    await openCamera();
  } catch (e) {
    $('error').textContent = cameraErrorText(e);
    return;
  }
  $('start').classList.add('hidden');
  $('hud').classList.remove('hidden');
  $('controls').classList.remove('hidden');
  resize();
  requestAnimationFrame(loop);
  loadModel();
  listBackCameras();
}

// ---------- Kamera ----------
function cameraErrorText(e) {
  const name = e && e.name;
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return 'Kamera izni verilmedi. Adres çubuğundaki kilit simgesinden kamera iznini aç ve sayfayı yenile.';
  }
  if (name === 'NotReadableError' || name === 'AbortError' || name === 'TrackStartError') {
    return 'Kamera başlatılamadı: başka bir uygulama (kamera, görüntülü arama vb.) kullanıyor olabilir. ' +
      'O uygulamaları kapatıp tekrar dene. Olmazsa sayfayı Chrome\'da aç (⋮ → Chrome\'da aç).';
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return 'Bu cihazda kullanılabilir kamera bulunamadı.';
  return 'Kameraya erişilemedi: ' + ((e && (e.message || e.name)) || e) + '. Sayfanın HTTPS üzerinden açıldığından emin ol.';
}

const cam = { stream: null, devices: [], index: -1 };

// Bazı Android telefonlar desteklemediği çözünürlük istenince kamerayı hiç açmaz
// ("Could not start video source"). Bu yüzden önce güvenli ayarlarla açılır,
// olmazsa ayarlar gevşetilir; çözünürlük açıldıktan sonra yükseltilmeye çalışılır.
const CAMERA_TRIES = [
  { width: { ideal: 1280 }, height: { ideal: 720 } },
  {},
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getStream(deviceId) {
  let lastErr;
  for (const extra of CAMERA_TRIES) {
    for (const withFacing of deviceId ? [false] : [true, false]) {
      const v = { ...extra };
      if (deviceId) v.deviceId = { exact: deviceId };
      else if (withFacing) v.facingMode = { ideal: 'environment' };
      try {
        return await navigator.mediaDevices.getUserMedia({ audio: false, video: Object.keys(v).length ? v : true });
      } catch (e) {
        lastErr = e;
        // İzin reddi tekrar denemekle düzelmez
        if (e.name === 'NotAllowedError' || e.name === 'SecurityError') throw e;
        // Kamera az önce bırakıldıysa (ör. lens değiştirme) donanımın kendine gelmesini bekle
        await sleep(300);
      }
    }
  }
  throw lastErr;
}

async function openCamera(deviceId) {
  cam.stream?.getTracks().forEach((t) => t.stop());
  cam.stream = null;
  cam.stream = await getStream(deviceId);
  video.srcObject = cam.stream;
  await video.play();
  const track = cam.stream.getVideoTracks()[0];
  // Destekleniyorsa sürekli otomatik odak/pozlama
  const caps = track.getCapabilities?.() || {};
  const adv = {};
  if (caps.focusMode?.includes('continuous')) adv.focusMode = 'continuous';
  if (caps.exposureMode?.includes('continuous')) adv.exposureMode = 'continuous';
  if (caps.whiteBalanceMode?.includes('continuous')) adv.whiteBalanceMode = 'continuous';
  if (Object.keys(adv).length) await track.applyConstraints({ advanced: [adv] }).catch(() => {});
  upgradeResolution(track, caps);
  updateMap();
}

// Kamera çalışırken çözünürlüğü yükseltmeyi dene: başarısız olursa görüntü eski ayarda devam eder
async function upgradeResolution(track, caps) {
  const s = track.getSettings?.() || {};
  const maxW = caps.width?.max || 1920, maxH = caps.height?.max || 1080;
  const long = Math.min(1920, Math.max(maxW, maxH)), short = Math.min(1080, Math.min(maxW, maxH));
  if (!s.width || Math.max(s.width, s.height) >= long) return;
  const landscape = s.width >= s.height;
  const before = video.currentTime;
  try {
    await track.applyConstraints({
      width: { ideal: landscape ? long : short },
      height: { ideal: landscape ? short : long },
    });
  } catch (_) {
    return;
  }
  // Bazı cihazlarda görüntü donabilir: donarsa güvenli ayarlarla yeniden aç
  await sleep(1500);
  if (track.readyState === 'ended' || (video.currentTime === before && !video.paused)) {
    try {
      await track.applyConstraints({ width: { ideal: 1280 }, height: { ideal: 720 } });
    } catch (_) { /* önemsiz */ }
  }
  updateMap();
}

// Bazı telefonlar varsayılan olarak geniş açı (daha bulanık) lensi açar; kullanıcı lensler arasında geçebilsin
async function listBackCameras() {
  try {
    const all = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'videoinput');
    const back = all.filter((d) => /back|rear|arka|environment|camera2 0/i.test(d.label));
    cam.devices = back.length ? back : all;
    const current = cam.stream.getVideoTracks()[0].getSettings?.().deviceId;
    cam.index = Math.max(0, cam.devices.findIndex((d) => d.deviceId === current));
    $('camBtn').classList.toggle('hidden', cam.devices.length < 2);
  } catch (_) { /* önemsiz */ }
}

async function nextCamera() {
  if (cam.devices.length < 2) return;
  cam.index = (cam.index + 1) % cam.devices.length;
  const d = cam.devices[cam.index];
  try {
    await openCamera(d.deviceId);
    setStatus(`Kamera: ${d.label || 'Kamera ' + (cam.index + 1)}`);
  } catch (_) {
    setStatus('Bu kamera açılamadı, varsayılan kameraya dönülüyor.');
    await openCamera().catch(() => setStatus('Kamera açılamadı. Sayfayı yenile.'));
  }
}

// ---------- Nesne tanıma ----------
// Model küçültülmüş kareyle beslenir (model zaten 300x300'e indirger); bu hem hızlı hem de veri kopyalamayı azaltır.
const DETECT_SIZE = 400;
const det = { worker: null, busy: false, canvas: document.createElement('canvas'), ratio: 1, mainModel: null };

function loadModel() {
  try {
    det.worker = new Worker('js/detector-worker.js');
  } catch (_) {
    return loadMainThreadModel();
  }
  det.worker.onmessage = (e) => {
    const m = e.data;
    if (m.type === 'ready') {
      setStatus('Kırılabilir eşya aranıyor… (şişe, bardak, vazo, ekran…)');
      state.modelReady = true;
      scheduleDetect(0);
    } else if (m.type === 'result') {
      det.busy = false;
      ingest(m.preds.map((p) => ({ ...p, bbox: p.bbox.map((v) => v * det.ratio) })));
      // Telefonu yormamak için tahmin süresine göre bekle
      scheduleDetect(Math.max(80, m.ms * 0.75));
    } else if (m.type === 'error') {
      det.worker.terminate();
      det.worker = null;
      loadMainThreadModel();
    }
  };
  det.worker.onerror = () => {
    det.worker?.terminate();
    det.worker = null;
    loadMainThreadModel();
  };
  det.worker.postMessage({ type: 'init' });
}

// Worker desteklenmezse yedek: ana iş parçacığında, daha seyrek
const TF_URLS = [
  'https://cdn.jsdelivr.net/npm/@tensorflow/tfjs@4.22.0/dist/tf.min.js',
  'https://cdn.jsdelivr.net/npm/@tensorflow-models/coco-ssd@2.2.3/dist/coco-ssd.min.js',
];

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const el = document.createElement('script');
    el.src = src;
    el.onload = resolve;
    el.onerror = reject;
    document.head.appendChild(el);
  });
}

async function loadMainThreadModel() {
  try {
    for (const src of TF_URLS) await loadScript(src);
  } catch (_) { /* aşağıda ele alınır */ }
  if (!window.cocoSsd) {
    setStatus('Nesne tanıma yüklenemedi — "Hedef çiz" ile kendin işaretleyebilirsin.');
    return;
  }
  try {
    det.mainModel = await cocoSsd.load({ base: 'lite_mobilenet_v2' });
    state.modelReady = true;
    setStatus('Kırılabilir eşya aranıyor… (şişe, bardak, vazo, ekran…)');
    scheduleDetect(0);
  } catch (e) {
    setStatus('Model yüklenemedi — "Hedef çiz" ile kendin işaretle.');
  }
}

function scheduleDetect(ms) {
  setTimeout(detectStep, ms);
}

function grabSmallFrame() {
  const vw = video.videoWidth, vh = video.videoHeight;
  const k = Math.min(1, DETECT_SIZE / Math.max(vw, vh));
  const w = Math.round(vw * k), h = Math.round(vh * k);
  if (det.canvas.width !== w || det.canvas.height !== h) { det.canvas.width = w; det.canvas.height = h; }
  det.canvas.getContext('2d').drawImage(video, 0, 0, w, h);
  det.ratio = vw / w;
  return det.canvas;
}

async function detectStep() {
  if (det.busy) return;
  // Sekme arka plandaysa veya video hazır değilse bekle
  if (document.hidden || video.readyState < 2 || !video.videoWidth) return scheduleDetect(300);
  det.busy = true;
  const frame = grabSmallFrame();
  if (det.worker) {
    try {
      const bitmap = await createImageBitmap(frame);
      det.worker.postMessage({ type: 'detect', bitmap }, [bitmap]);
    } catch (_) {
      det.busy = false;
      scheduleDetect(300);
    }
    return;
  }
  const t0 = performance.now();
  try {
    const preds = await det.mainModel.detect(frame, 20, 0.4);
    ingest(preds.map((p) => ({ ...p, bbox: p.bbox.map((v) => v * det.ratio) })));
  } catch (_) { /* tek kare hatası önemsiz */ }
  det.busy = false;
  // Ana iş parçacığında çalışırken zamanın çoğunu animasyona bırak
  scheduleDetect(Math.max(250, (performance.now() - t0) * 3));
}

function setStatus(t) { $('status').textContent = t; }

// ---------- Takip ----------
function iou(a, b) {
  const x1 = Math.max(a.x, b.x), y1 = Math.max(a.y, b.y);
  const x2 = Math.min(a.x + a.w, b.x + b.w), y2 = Math.min(a.y + a.h, b.y + b.h);
  const inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
  return inter / (a.w * a.h + b.w * b.h - inter || 1);
}

function ingest(preds) {
  const now = performance.now();
  const { scale, ox, oy } = state.map;
  const used = new Set();
  for (const p of preds) {
    const info = BREAKABLE[p.class];
    if (!info) continue;
    const [vx, vy, vw, vh] = p.bbox;
    const box = { x: vx * scale + ox, y: vy * scale + oy, w: vw * scale, h: vh * scale };
    let best = null, bestScore = 0.15;
    for (const t of state.targets) {
      if (t.manual || t.cls !== p.class || used.has(t)) continue;
      const s = iou(t, box);
      if (s > bestScore) { best = t; bestScore = s; }
    }
    if (best) {
      const k = 0.5; // yumuşatma
      best.x += (box.x - best.x) * k; best.y += (box.y - best.y) * k;
      best.w += (box.w - best.w) * k; best.h += (box.h - best.h) * k;
      best.lastSeen = now;
      used.add(best);
    } else {
      const t = { id: state.nextId++, cls: p.class, ...box, hp: info.hp, lastSeen: now, cracks: [] };
      state.targets.push(t);
      used.add(t);
    }
  }
  state.targets = state.targets.filter((t) => t.manual || now - t.lastSeen < LOST_MS || (t.wreck && now < t.wreck.until));
  const live = state.targets.filter((t) => !isBroken(t, now)).length;
  if (state.modelReady) {
    setStatus(live ? `${live} kırılabilir hedef görüldü — at!` : 'Kırılabilir eşya aranıyor… (şişe, bardak, vazo, ekran…)');
  }
}

const isBroken = (t, now) => t.wreck && now < t.wreck.until;

// ---------- Girdi ----------
function onDown(e) {
  if (e.target !== canvas) return;
  canvas.setPointerCapture?.(e.pointerId);
  const p = { x: e.clientX, y: e.clientY, t: performance.now() };
  if (state.markMode) { state.drawing = { x0: p.x, y0: p.y, x1: p.x, y1: p.y }; return; }
  state.pointer = p;
}

function onMove(e) {
  if (state.drawing) { state.drawing.x1 = e.clientX; state.drawing.y1 = e.clientY; }
}

function onUp(e) {
  if (state.drawing) {
    const d = state.drawing;
    state.drawing = null;
    const box = { x: Math.min(d.x0, d.x1), y: Math.min(d.y0, d.y1), w: Math.abs(d.x1 - d.x0), h: Math.abs(d.y1 - d.y0) };
    if (box.w > 25 && box.h > 25) {
      state.targets.push({ id: state.nextId++, cls: 'manual', ...box, hp: BREAKABLE.manual.hp, manual: true, lastSeen: performance.now(), cracks: [] });
      setMarkMode(false);
      setStatus('Hedef eklendi — şimdi fırlat!');
    }
    return;
  }
  const p0 = state.pointer;
  state.pointer = null;
  if (!p0) return;
  const now = performance.now();
  if (now - state.lastThrow < RELOAD_MS) return;
  const x1 = e.clientX, y1 = e.clientY;
  const dx = x1 - p0.x, dy = y1 - p0.y;
  const dist = Math.hypot(dx, dy);
  let tx = x1, ty = y1;
  if (dist > 20) {
    if (dy > -10) return; // aşağı kaydırma atış değil
    // Kaydırma hızı ne kadar yüksekse, top o yöne o kadar ileri gider
    const speed = dist / Math.max(16, now - p0.t); // px/ms
    const extra = Math.min(state.H * 0.12, speed * 25);
    tx = x1 + (dx / dist) * extra;
    ty = y1 + (dy / dist) * extra;
  }
  tx = Math.max(0, Math.min(state.W, tx));
  ty = Math.max(0, Math.min(state.H, ty));
  throwAt(tx, ty);
}

function throwAt(tx, ty) {
  const a = AMMO[state.ammo];
  state.lastThrow = performance.now();
  state.shots++;
  const sx = state.W / 2, sy = state.H + a.radius * 0.2;
  // Küçük sapma — mükemmel nişan yok
  const spread = state.ammo === 'stone' ? 6 : 12;
  state.projectiles.push({
    type: state.ammo, sx, sy,
    tx: tx + (Math.random() - 0.5) * spread, ty: ty + (Math.random() - 0.5) * spread,
    t: 0, arc: Math.min(220, Math.hypot(tx - sx, ty - sy) * 0.35), spin: 0,
  });
  playThrow();
  updateHud();
}

// ---------- Çarpışma ----------
function impact(p) {
  const a = AMMO[p.type];
  const now = performance.now();
  const hits = state.targets.filter((t) => !isBroken(t, now) &&
    p.tx > t.x - a.hitPad && p.tx < t.x + t.w + a.hitPad &&
    p.ty > t.y - a.hitPad && p.ty < t.y + t.h + a.hitPad);
  if (!hits.length) {
    playMiss();
    burst(p.tx, p.ty, 8, ['#c9b8a0', '#8d7d68'], 120);
    return;
  }
  // En küçük (en önde olması muhtemel) hedefi seç
  hits.sort((m, n) => m.w * m.h - n.w * n.h);
  const t = hits[0];
  const info = BREAKABLE[t.cls];
  t.hp -= a.damage;
  const nx = (p.tx - t.x) / t.w, ny = (p.ty - t.y) / t.h;
  if (t.hp > 0) {
    t.cracks.push(...makeCracks(nx, ny, 6));
    playHit();
    burst(p.tx, p.ty, 10, ['#fff', '#cfefff'], 160);
    state.shake = Math.max(state.shake, 4);
    popup(p.tx, p.ty - 20, 'Çatladı!', '#cfefff');
    return;
  }
  shatter(t, { x: p.tx, y: p.ty }, a.power, info);
}

function shatter(t, at, power, info) {
  const now = performance.now();
  const box = { x: t.x, y: t.y, w: t.w, h: t.h };
  const { canvas: img, bg } = snapshot(video, box, state.map);
  state.shards.push(...makeShards(img, box, at, power));
  burst(at.x, at.y, 30, info.material === 'electronic' ? ['#fff', '#9ad7ff', '#ffd23f'] : ['#fff', '#bff3ff', '#e9fdff'], 380);
  playShatter(info.material);
  navigator.vibrate?.(info.material === 'electronic' ? [40, 30, 60] : 50);
  state.shake = 14;
  state.flash = 0.35;
  t.cracks = [];
  t.wreck = {
    start: now, until: now + WRECK_MS, bg, debris: makeDebris(14),
    debrisColor: info.material === 'electronic' ? 'rgba(40,40,45,0.9)' : 'rgba(200,235,245,0.55)',
  };
  t.hp = info.hp;
  state.score += info.points;
  state.broken++;
  popup(at.x, at.y - 30, `${info.label} kırıldı! +${info.points}`, '#ffd23f');
  updateHud();
  if (t.manual) setTimeout(() => { state.targets = state.targets.filter((x) => x !== t); }, WRECK_MS);
}

function burst(x, y, n, colors, speed) {
  for (let i = 0; i < n; i++) {
    const a = Math.random() * Math.PI * 2, v = Math.random() * speed;
    state.particles.push({
      x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v - speed * 0.4,
      life: 0.4 + Math.random() * 0.6, age: 0, size: 1.5 + Math.random() * 3.5,
      color: colors[(Math.random() * colors.length) | 0], rot: Math.random() * 6,
    });
  }
}

function popup(x, y, text, color) {
  // Yazı ekrandan taşmasın
  ctx.font = 'bold 22px system-ui, sans-serif';
  const half = ctx.measureText(text).width / 2 + 8;
  x = Math.max(half, Math.min(state.W - half, x));
  y = Math.max(110, Math.min(state.H - 140, y));
  state.popups.push({ x, y, text, color, age: 0 });
}

function updateHud() {
  $('score').textContent = state.score;
  $('broken').textContent = state.broken;
  $('shots').textContent = state.shots;
}

// ---------- Döngü ----------
let last = performance.now();
function loop(now) {
  const dt = Math.min(0.05, (now - last) / 1000);
  last = now;
  update(dt, now);
  render(now);
  requestAnimationFrame(loop);
}

function update(dt, now) {
  for (const p of state.projectiles) {
    p.t += dt / AMMO[p.type].duration;
    p.spin += dt * 12;
    if (p.t >= 1 && !p.done) { p.done = true; impact(p); }
  }
  state.projectiles = state.projectiles.filter((p) => !p.done);
  state.shards = updateShards(state.shards, dt, state.H - 30);
  for (const q of state.particles) {
    q.age += dt; q.vy += 900 * dt; q.x += q.vx * dt; q.y += q.vy * dt; q.rot += dt * 8;
  }
  state.particles = state.particles.filter((q) => q.age < q.life);
  for (const u of state.popups) { u.age += dt; u.y -= 40 * dt; }
  state.popups = state.popups.filter((u) => u.age < 1.3);
  state.shake *= Math.pow(0.002, dt);
  state.flash = Math.max(0, state.flash - dt * 1.5);
  // Süresi dolan enkaz → nesne "yenilenir", tekrar kırılabilir
  for (const t of state.targets) if (t.wreck && now >= t.wreck.until) t.wreck = null;
}

function render(now) {
  const { dpr, W, H } = state;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, W, H);
  if (state.shake > 0.3) {
    ctx.translate((Math.random() - 0.5) * state.shake, (Math.random() - 0.5) * state.shake);
    // Kamera görüntüsünü de salla
    video.style.transform = `translate(${Math.round((Math.random() - 0.5) * state.shake)}px, ${Math.round((Math.random() - 0.5) * state.shake)}px)`;
  } else if (video.style.transform) {
    video.style.transform = '';
  }

  for (const t of state.targets) {
    if (isBroken(t, now)) { drawWreck(ctx, t.wreck, t, now); continue; }
    if (t.cracks.length) drawCracks(ctx, t, t.cracks);
    if (state.showBoxes) drawTargetMarker(t, now);
  }

  drawShards(ctx, state.shards);

  for (const q of state.particles) {
    ctx.globalAlpha = 1 - q.age / q.life;
    ctx.fillStyle = q.color;
    ctx.save();
    ctx.translate(q.x, q.y);
    ctx.rotate(q.rot);
    ctx.beginPath();
    ctx.moveTo(0, -q.size); ctx.lineTo(q.size * 0.7, q.size); ctx.lineTo(-q.size * 0.7, q.size * 0.4);
    ctx.fill();
    ctx.restore();
  }
  ctx.globalAlpha = 1;

  for (const p of state.projectiles) drawProjectile(p);
  if (state.drawing) drawSelection(state.drawing);
  if (!state.markMode) drawHand(now);

  for (const u of state.popups) {
    ctx.globalAlpha = Math.min(1, 2 * (1.3 - u.age));
    ctx.font = 'bold 22px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.lineWidth = 4;
    ctx.strokeStyle = 'rgba(0,0,0,0.7)';
    ctx.strokeText(u.text, u.x, u.y);
    ctx.fillStyle = u.color;
    ctx.fillText(u.text, u.x, u.y);
  }
  ctx.globalAlpha = 1;

  if (state.flash > 0) {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = `rgba(255,255,255,${state.flash})`;
    ctx.fillRect(0, 0, W, H);
  }
}

function drawTargetMarker(t, now) {
  const info = BREAKABLE[t.cls];
  const pulse = 0.6 + 0.4 * Math.sin(now / 250);
  const c = Math.min(22, t.w / 3, t.h / 3);
  ctx.strokeStyle = `rgba(255,210,63,${0.5 + 0.4 * pulse})`;
  ctx.lineWidth = 3;
  ctx.beginPath();
  for (const [x, y, sx, sy] of [[t.x, t.y, 1, 1], [t.x + t.w, t.y, -1, 1], [t.x, t.y + t.h, 1, -1], [t.x + t.w, t.y + t.h, -1, -1]]) {
    ctx.moveTo(x + sx * c, y); ctx.lineTo(x, y); ctx.lineTo(x, y + sy * c);
  }
  ctx.stroke();
  const label = info.hp > 1 ? `${info.label} ${'●'.repeat(Math.max(0, t.hp))}` : info.label;
  ctx.font = 'bold 13px system-ui, sans-serif';
  ctx.textAlign = 'left';
  const tw = ctx.measureText(label).width;
  ctx.fillStyle = 'rgba(0,0,0,0.55)';
  ctx.fillRect(t.x, t.y - 20, tw + 10, 18);
  ctx.fillStyle = '#ffd23f';
  ctx.fillText(label, t.x + 5, t.y - 6);
}

function drawSelection(d) {
  ctx.setLineDash([8, 6]);
  ctx.strokeStyle = '#ffd23f';
  ctx.lineWidth = 2;
  ctx.strokeRect(Math.min(d.x0, d.x1), Math.min(d.y0, d.y1), Math.abs(d.x1 - d.x0), Math.abs(d.y1 - d.y0));
  ctx.setLineDash([]);
}

function projectilePos(p) {
  const e = 1 - Math.pow(1 - p.t, 1.6); // ileriye doğru yavaşlayan hareket (derinlik hissi)
  return {
    x: p.sx + (p.tx - p.sx) * e,
    y: p.sy + (p.ty - p.sy) * e - p.arc * 4 * e * (1 - e),
    r: AMMO[p.type].radius * (1 - 0.72 * e),
  };
}

function drawProjectile(p) {
  const { x, y, r } = projectilePos(p);
  drawAmmo(p.type, x, y, r, p.spin);
}

function drawAmmo(type, x, y, r, spin) {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(spin);
  if (type === 'ball') {
    const g = ctx.createRadialGradient(-r * 0.35, -r * 0.35, r * 0.1, 0, 0, r);
    g.addColorStop(0, '#fff'); g.addColorStop(1, '#bdbdbd');
    ctx.fillStyle = g;
    ctx.beginPath(); ctx.arc(0, 0, r, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = '#222';
    for (let i = 0; i < 6; i++) {
      const a = (i / 5) * Math.PI * 2;
      const px = i === 5 ? 0 : Math.cos(a) * r * 0.62, py = i === 5 ? 0 : Math.sin(a) * r * 0.62;
      ctx.beginPath();
      for (let k = 0; k < 5; k++) {
        const b = (k / 5) * Math.PI * 2 - Math.PI / 2;
        ctx.lineTo(px + Math.cos(b) * r * 0.22, py + Math.sin(b) * r * 0.22);
      }
      ctx.fill();
    }
  } else {
    const g = ctx.createRadialGradient(-r * 0.3, -r * 0.3, r * 0.1, 0, 0, r);
    g.addColorStop(0, '#a49a8e'); g.addColorStop(1, '#4e4740');
    ctx.fillStyle = g;
    ctx.beginPath();
    stoneShape.forEach(([sx, sy], i) => (i ? ctx.lineTo(sx * r, sy * r) : ctx.moveTo(sx * r, sy * r)));
    ctx.closePath(); ctx.fill();
    ctx.strokeStyle = 'rgba(0,0,0,0.3)'; ctx.lineWidth = 1.5; ctx.stroke();
  }
  ctx.restore();
}

// FPS tarzı: elde bekleyen top/taş ve nişangah
function drawHand(now) {
  const ready = now - state.lastThrow > RELOAD_MS;
  const a = AMMO[state.ammo];
  const bob = Math.sin(now / 400) * 3;
  const y = state.H - 90 + bob + (ready ? 0 : 60);
  ctx.fillStyle = 'rgba(0,0,0,0.25)';
  ctx.beginPath(); ctx.ellipse(state.W / 2, state.H - 50, a.radius * 1.3, a.radius * 0.35, 0, 0, Math.PI * 2); ctx.fill();
  drawAmmo(state.ammo, state.W / 2, y, a.radius * 1.3, 0.3);
  const p = state.pointer;
  if (p) {
    ctx.strokeStyle = 'rgba(255,255,255,0.7)'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.arc(p.x, p.y, 16, 0, Math.PI * 2); ctx.stroke();
  }
}

function setMarkMode(on) {
  state.markMode = on;
  $('markBtn').classList.toggle('active', on);
  if (on) setStatus('Kırmak istediğin şeyin etrafına parmağınla kutu çiz.');
}

// ---------- Olaylar ----------
$('startBtn').addEventListener('click', start);
window.addEventListener('resize', resize);
video.addEventListener('loadedmetadata', updateMap);
video.addEventListener('resize', updateMap);
canvas.addEventListener('pointerdown', onDown);
canvas.addEventListener('pointermove', onMove);
canvas.addEventListener('pointerup', onUp);
canvas.addEventListener('pointercancel', () => { state.pointer = null; state.drawing = null; });
for (const btn of document.querySelectorAll('[data-ammo]')) {
  btn.addEventListener('click', () => {
    state.ammo = btn.dataset.ammo;
    document.querySelectorAll('[data-ammo]').forEach((b) => b.classList.toggle('active', b === btn));
  });
}
$('camBtn').addEventListener('click', nextCamera);
$('markBtn').addEventListener('click', () => setMarkMode(!state.markMode));
$('boxBtn').addEventListener('click', () => {
  state.showBoxes = !state.showBoxes;
  $('boxBtn').classList.toggle('active', !state.showBoxes);
});

// Test/hata ayıklama için
window.__game = { state, throwAt, ingest };
