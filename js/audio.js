// Ses efektleri WebAudio ile anlık sentezlenir (harici ses dosyası yok).
let ctx = null;
let noiseBuf = null;

export function initAudio() {
  if (ctx) return;
  const AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) return;
  ctx = new AC();
  noiseBuf = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate);
  const d = noiseBuf.getChannelData(0);
  for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
}

function noise(t, dur, freq, type, gain) {
  const src = ctx.createBufferSource();
  src.buffer = noiseBuf;
  const f = ctx.createBiquadFilter();
  f.type = type;
  f.frequency.value = freq;
  const g = ctx.createGain();
  g.gain.setValueAtTime(gain, t);
  g.gain.exponentialRampToValueAtTime(0.001, t + dur);
  src.connect(f).connect(g).connect(ctx.destination);
  src.start(t, Math.random() * 0.5, dur);
}

function ping(t, freq, dur, gain) {
  const o = ctx.createOscillator();
  o.type = 'sine';
  o.frequency.value = freq;
  const g = ctx.createGain();
  g.gain.setValueAtTime(gain, t);
  g.gain.exponentialRampToValueAtTime(0.001, t + dur);
  o.connect(g).connect(ctx.destination);
  o.start(t);
  o.stop(t + dur);
}

export function playShatter(material = 'glass') {
  if (!ctx) return;
  const t = ctx.currentTime;
  if (material === 'electronic') {
    noise(t, 0.5, 900, 'lowpass', 0.9);
    noise(t, 0.25, 4000, 'highpass', 0.4);
    for (let i = 0; i < 4; i++) ping(t + Math.random() * 0.15, 1200 + Math.random() * 2500, 0.2, 0.06);
    return;
  }
  const bright = material === 'glass';
  noise(t, bright ? 0.45 : 0.35, bright ? 3500 : 1800, 'highpass', 0.8);
  noise(t, 0.15, 500, 'lowpass', 0.6);
  const n = bright ? 9 : 5;
  for (let i = 0; i < n; i++) {
    ping(t + Math.random() * 0.25, (bright ? 2500 : 1500) + Math.random() * 4000,
      0.15 + Math.random() * 0.5, 0.05);
  }
}

export function playHit() {
  if (!ctx) return;
  const t = ctx.currentTime;
  ping(t, 2200 + Math.random() * 800, 0.25, 0.12);
  noise(t, 0.08, 2000, 'bandpass', 0.4);
}

export function playMiss() {
  if (!ctx) return;
  const t = ctx.currentTime;
  const o = ctx.createOscillator();
  o.frequency.setValueAtTime(160, t);
  o.frequency.exponentialRampToValueAtTime(60, t + 0.15);
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.5, t);
  g.gain.exponentialRampToValueAtTime(0.001, t + 0.2);
  o.connect(g).connect(ctx.destination);
  o.start(t);
  o.stop(t + 0.2);
}

export function playThrow() {
  if (!ctx) return;
  noise(ctx.currentTime, 0.18, 800, 'bandpass', 0.25);
}
