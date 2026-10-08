// Kamera görüntüsünden kesilen parçanın kırılma efekti.
const rand = (a, b) => a + Math.random() * (b - a);

// Nesne kutusunun o anki kamera görüntüsünü elips maskeli bir tuvale kopyalar.
export function snapshot(video, box, map) {
  const w = Math.max(2, Math.round(box.w));
  const h = Math.max(2, Math.round(box.h));
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const g = c.getContext('2d');
  const s = map.scale;
  g.drawImage(video, (box.x - map.ox) / s, (box.y - map.oy) / s, box.w / s, box.h / s, 0, 0, w, h);

  // Kenar piksellerinin ortalaması ≈ nesnenin arkasındaki zemin rengi
  let bg = [60, 60, 60];
  try {
    const top = g.getImageData(0, 0, w, 2).data;
    const left = g.getImageData(0, 0, 2, h).data;
    const right = g.getImageData(w - 2, 0, 2, h).data;
    let r = 0, gg = 0, b = 0, n = 0;
    for (const arr of [top, left, right]) {
      for (let i = 0; i < arr.length; i += 4) { r += arr[i]; gg += arr[i + 1]; b += arr[i + 2]; n++; }
    }
    bg = [r / n, gg / n, b / n].map(Math.round);
  } catch (_) { /* çapraz kaynak koruması: varsayılan renk */ }

  // Köşelerdeki arka planı at, ortadaki nesneyi tut
  g.globalCompositeOperation = 'destination-in';
  g.beginPath();
  g.ellipse(w / 2, h / 2, w * 0.56, h * 0.56, 0, 0, Math.PI * 2);
  g.fill();
  return { canvas: c, bg };
}

// Çarpma noktasından yayılan radyal kırık desenine göre parçalar üretir.
export function makeShards(img, box, impact, power) {
  const w = img.width, h = img.height;
  const ix = impact.x - box.x, iy = impact.y - box.y;
  const R = Math.hypot(w, h) * 1.1;
  const sectors = Math.round(rand(9, 14));
  const angles = [];
  for (let i = 0; i < sectors; i++) angles.push((i + rand(-0.3, 0.3)) / sectors * Math.PI * 2);
  angles.sort((a, b) => a - b);
  const rings = [0, R * rand(0.08, 0.14), R * rand(0.22, 0.32), R * rand(0.45, 0.55), R];

  const pt = (a, r) => [ix + Math.cos(a) * r, iy + Math.sin(a) * r];
  const shards = [];
  for (let i = 0; i < sectors; i++) {
    const a0 = angles[i], a1 = angles[(i + 1) % sectors] + (i === sectors - 1 ? Math.PI * 2 : 0);
    for (let j = 0; j < rings.length - 1; j++) {
      const r0 = rings[j], r1 = rings[j + 1];
      const am = (a0 + a1) / 2 + rand(-0.1, 0.1);
      const poly = r0 === 0
        ? [[ix, iy], pt(a0, r1), pt(am, r1 * rand(0.9, 1.1)), pt(a1, r1)]
        : [pt(a0, r0), pt(a0, r1), pt(am, r1 * rand(0.9, 1.1)), pt(a1, r1), pt(a1, r0)];
      // Polygon merkezi; kutu dışında kalan parçaları atla
      let cx = 0, cy = 0;
      for (const p of poly) { cx += p[0]; cy += p[1]; }
      cx /= poly.length; cy /= poly.length;
      if (cx < -w * 0.1 || cx > w * 1.1 || cy < -h * 0.1 || cy > h * 1.1) continue;
      const dx = cx - ix, dy = cy - iy;
      const d = Math.hypot(dx, dy) || 1;
      const speed = rand(150, 520) * power * (1.2 - Math.min(1, d / R));
      shards.push({
        img, poly: poly.map(([x, y]) => [x - cx, y - cy]), lx: cx, ly: cy,
        x: box.x + cx, y: box.y + cy,
        vx: dx / d * speed + rand(-60, 60),
        vy: dy / d * speed - rand(80, 260) * power,
        rot: 0, vr: rand(-8, 8), life: rand(1.1, 1.8), age: 0,
      });
    }
  }
  return shards;
}

export function updateShards(shards, dt, floorY) {
  for (const s of shards) {
    s.age += dt;
    s.vy += 1400 * dt;
    s.x += s.vx * dt;
    s.y += s.vy * dt;
    s.rot += s.vr * dt;
    if (s.y > floorY && s.vy > 0) { s.vy *= -0.3; s.vx *= 0.6; s.vr *= 0.5; s.y = floorY; }
  }
  return shards.filter((s) => s.age < s.life);
}

export function drawShards(ctx, shards) {
  for (const s of shards) {
    const a = Math.max(0, 1 - Math.pow(s.age / s.life, 3));
    ctx.save();
    ctx.globalAlpha = a;
    ctx.translate(s.x, s.y);
    ctx.rotate(s.rot);
    ctx.beginPath();
    s.poly.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
    ctx.closePath();
    ctx.save();
    ctx.clip();
    ctx.drawImage(s.img, -s.lx, -s.ly);
    ctx.restore();
    ctx.strokeStyle = 'rgba(255,255,255,0.55)';
    ctx.lineWidth = 1;
    ctx.stroke();
    ctx.restore();
  }
}

// Çatlak çizgileri (0..1 aralığında, kutuya göre normalize)
export function makeCracks(nx, ny, count = 7) {
  const lines = [];
  for (let i = 0; i < count; i++) {
    let a = (i / count) * Math.PI * 2 + rand(-0.3, 0.3);
    let x = nx, y = ny;
    const pts = [[x, y]];
    const steps = Math.round(rand(3, 6));
    for (let k = 0; k < steps; k++) {
      a += rand(-0.5, 0.5);
      const len = rand(0.05, 0.14);
      x += Math.cos(a) * len;
      y += Math.sin(a) * len;
      pts.push([x, y]);
    }
    lines.push(pts);
  }
  return lines;
}

export function drawCracks(ctx, box, cracks) {
  ctx.save();
  ctx.beginPath();
  ctx.rect(box.x, box.y, box.w, box.h);
  ctx.clip();
  ctx.lineJoin = 'round';
  for (const [color, width] of [['rgba(0,0,0,0.6)', 3], ['rgba(255,255,255,0.9)', 1.2]]) {
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    for (const line of cracks) {
      ctx.beginPath();
      line.forEach(([x, y], i) => {
        const px = box.x + x * box.w, py = box.y + y * box.h;
        i ? ctx.lineTo(px, py) : ctx.moveTo(px, py);
      });
      ctx.stroke();
    }
  }
  ctx.restore();
}

// Kırılan nesnenin yerini zemin rengiyle örter ve altına enkaz yığını çizer.
export function makeDebris(count) {
  const out = [];
  for (let i = 0; i < count; i++) {
    out.push({ x: rand(0.1, 0.9), y: rand(0.85, 1.0), s: rand(0.03, 0.08), r: rand(0, Math.PI), k: Math.round(rand(3, 5)) });
  }
  return out;
}

export function drawWreck(ctx, wreck, box, now) {
  const fadeIn = Math.min(1, (now - wreck.start) / 150);
  const fadeOut = Math.min(1, (wreck.until - now) / 800);
  const a = Math.max(0, Math.min(fadeIn, fadeOut));
  if (a <= 0) return;
  const [r, g, b] = wreck.bg;
  ctx.save();
  ctx.globalAlpha = a;
  const cx = box.x + box.w / 2, cy = box.y + box.h / 2;
  const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.max(box.w, box.h) * 0.6);
  grad.addColorStop(0, `rgba(${r},${g},${b},0.97)`);
  grad.addColorStop(0.7, `rgba(${r},${g},${b},0.9)`);
  grad.addColorStop(1, `rgba(${r},${g},${b},0)`);
  ctx.fillStyle = grad;
  ctx.beginPath();
  ctx.ellipse(cx, cy, box.w * 0.62, box.h * 0.62, 0, 0, Math.PI * 2);
  ctx.fill();
  // Enkaz
  for (const d of wreck.debris) {
    const px = box.x + d.x * box.w, py = box.y + d.y * box.h;
    const sz = d.s * Math.max(box.w, 60);
    ctx.beginPath();
    for (let i = 0; i < d.k; i++) {
      const ang = d.r + (i / d.k) * Math.PI * 2;
      const rr = sz * (i % 2 ? 0.6 : 1);
      const x = px + Math.cos(ang) * rr, y = py + Math.sin(ang) * rr * 0.6;
      i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
    }
    ctx.closePath();
    ctx.fillStyle = wreck.debrisColor;
    ctx.fill();
    ctx.strokeStyle = 'rgba(255,255,255,0.6)';
    ctx.lineWidth = 1;
    ctx.stroke();
  }
  ctx.restore();
}
