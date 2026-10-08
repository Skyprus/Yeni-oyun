package com.skyprus.kirgec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val WRECK_MS = 7000L   // kırılan nesnenin "yok" görüneceği süre
private const val LOST_MS = 1200L    // görülmeyen hedefin silinme süresi
private const val RELOAD_MS = 280L
private const val SEG_WAIT_MS = 450L // çarpmada segmentasyon sonucunu en fazla bu kadar bekle

class Target(
    val cls: String,
    val info: Breakable,
    val box: RectF,
    var hp: Int,
    var lastSeen: Long,
) {
    val cracks = ArrayList<FloatArray>()
    var wreck: Wreck? = null
    fun isBroken(now: Long) = wreck?.let { now < it.until } ?: false
}

/** Bir vuruş: hedef noktası ve o noktadaki nesnenin (arka planda hesaplanan) kesiti. */
private class PendingHit(val x: Float, val y: Float, val weapon: Weapon) {
    val created = SystemClock.uptimeMillis()
    @Volatile var cut: Cutout? = null
    @Volatile var done = false
    fun ready(now: Long) = done || now - created > SEG_WAIT_MS
}

private class Projectile(val hit: PendingHit, val sx: Float, val sy: Float, val arc: Float) {
    var t = 0f
    var spin = 0f
}

private class Swing(val hit: PendingHit, val start: Long) {
    var t = 0f
    var impacted = false
}

private class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val life: Float, val size: Float, val color: Int, var rot: Float,
) {
    var age = 0f
}

private class Popup(val x: Float, var y: Float, val text: String, val color: Int) {
    var age = 0f
}

/**
 * Kamera önizlemesinin üstünde duran oyun katmanı: hedef takibi, atış/savurma, kırılma efektleri ve HUD.
 * Kendi kendini her karede yeniden çizer (postInvalidateOnAnimation).
 */
class GameView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val d = resources.displayMetrics.density

    /** Ekranda görünen kamera karesini (ekran pikselleriyle) verir. */
    var frameProvider: (() -> Bitmap?)? = null
    /** (x, y) noktasındaki nesneyi arka planda ayırır; sonuç ana iş parçacığında geri çağrılır. */
    var segmentAt: ((Float, Float, (Cutout?) -> Unit) -> Unit)? = null
    var sfx: Sfx? = null
    var haptics: ((Material) -> Unit)? = null
    var shakeTarget: View? = null
    /** Uzun basınca o noktaya odaklan (ekran koordinatı). */
    var onFocusRequest: ((Float, Float) -> Unit)? = null
    /** İki parmakla yakınlaştırma: çarpan. */
    var onZoom: ((Float) -> Unit)? = null
    /** Tanı satırı: modelin gördüğü her şey, tahmin süresi. */
    var info = ""
    var insetTop = 0

    var weapon = Weapon.BALL
    var showBoxes = true
    var status = "Kamera açılıyor…"
    private var modelState = 0 // 0 yükleniyor, 1 hazır, -1 yok

    private val targets = ArrayList<Target>()
    private val looseWrecks = ArrayList<Wreck>() // takip edilmeyen nesnelerin izleri
    private var seen: List<Pair<String, RectF>> = emptyList() // son karede görülen her şey (ekran koordinatı)
    private val projectiles = ArrayList<Projectile>()
    private var swing: Swing? = null
    private val shards = ArrayList<Shard>()
    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private var score = 0
    private var broken = 0
    private var shots = 0
    private var lastThrow = 0L
    private var shake = 0f
    private var flash = 0f
    private var lastFrame = 0L

    // Uzun basışla seçilen nesnenin vurgusu
    private var selection: Cutout? = null
    private var selectionT = 0L

    // Dokunma durumu
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var pointerDown = false

    private val stoneShape = FloatArray(18).also {
        for (i in 0 until 9) {
            val a = i / 9f * 2f * PI.toFloat()
            it[i * 2] = cos(a) * (0.75f + Random.nextFloat() * 0.3f)
            it[i * 2 + 1] = sin(a) * (0.7f + Random.nextFloat() * 0.3f)
        }
    }

    // ---------- Model durumu ve tespitler ----------

    /** Birkaç saniye ekranda kalan bilgi mesajı (otomatik durum mesajları üzerine yazmaz). */
    fun hint(text: String) {
        status = text
        selectionT = SystemClock.uptimeMillis()
    }

    fun setModelReady(ok: Boolean) {
        modelState = if (ok) 1 else -1
        status = if (ok) IDLE_STATUS else "Otomatik tanıma kapalı — yine de istediğin nesneye vurabilirsin."
    }

    private fun iou(a: RectF, b: RectF): Float {
        val x1 = max(a.left, b.left); val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right); val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun labelOf(cls: String) = BREAKABLES[cls]?.label ?: OTHER_LABELS[cls] ?: cls

    /** Ana iş parçacığında çağrılır. Kutular görüntü piksellerindedir (iw x ih). */
    fun ingest(dets: List<Det>, iw: Int, ih: Int, ms: Long) {
        info = "Tanıma ${ms} ms · görülen: " + if (dets.isEmpty()) "—" else dets.sortedByDescending { it.score }
            .joinToString(", ") { labelOf(it.cls) + " " + (it.score * 100).toInt() + "%" }
        if (width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        val scale = max(width / iw.toFloat(), height / ih.toFloat())
        val ox = (width - iw * scale) / 2f
        val oy = (height - ih * scale) / 2f
        fun toScreen(r: RectF) = RectF(r.left * scale + ox, r.top * scale + oy, r.right * scale + ox, r.bottom * scale + oy)
        seen = dets.filter { it.score >= 0.35f }.map { it.cls to toScreen(it.box) }

        val used = HashSet<Target>()
        for (det in dets) {
            val info = BREAKABLES[det.cls] ?: continue
            val box = toScreen(det.box)
            var best: Target? = null
            var bestScore = 0.15f
            for (t in targets) {
                if (t.cls != det.cls || t in used) continue
                val s = iou(t.box, box)
                if (s > bestScore) { best = t; bestScore = s }
            }
            if (best != null) {
                val k = 0.5f // yumuşatma
                best.box.set(
                    best.box.left + (box.left - best.box.left) * k,
                    best.box.top + (box.top - best.box.top) * k,
                    best.box.right + (box.right - best.box.right) * k,
                    best.box.bottom + (box.bottom - best.box.bottom) * k,
                )
                best.lastSeen = now
                used += best
            } else {
                val t = Target(det.cls, info, box, info.hp, now)
                targets += t
                used += t
            }
        }
        targets.removeAll { now - it.lastSeen > LOST_MS && !it.isBroken(now) }
        if (modelState == 1 && now - selectionT > 2500) {
            val live = targets.count { !it.isBroken(now) }
            status = if (live > 0) "$live kırılabilir eşya görüldü — vur! (her şeyi kırabilirsin)" else IDLE_STATUS
        }
    }

    // ---------- Girdi ----------

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            onZoom?.invoke(det.scaleFactor)
            return true
        }
    })
    private var focusRing: FloatArray? = null // x, y
    private var focusT = 0L

    /** Uzun basış: o noktaya odaklan ve oradaki nesneyi seçip vurgula. */
    private val longPress = Runnable {
        if (!pointerDown) return@Runnable
        pointerDown = false
        val x = downX
        val y = downY
        focusRing = floatArrayOf(x, y)
        focusT = SystemClock.uptimeMillis()
        onFocusRequest?.invoke(x, y)
        status = "Seçiliyor…"
        selectionT = SystemClock.uptimeMillis()
        segmentAt?.invoke(x, y) { cut ->
            selectionT = SystemClock.uptimeMillis()
            if (cut != null) {
                selection = cut
                status = "Seçildi: ${nameFor(cut.box)} — şimdi vur!"
            } else {
                selection = null
                status = "Burada kırılabilecek bir nesne bulunamadı (duvar/zemin çok büyük)."
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            // İki parmak: yakınlaştırma, vuruş değil
            pointerDown = false
            removeCallbacks(longPress)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = SystemClock.uptimeMillis(); pointerDown = true
                postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > 12 * d) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                performClick()
                onUp(e.x, e.y)
            }
            MotionEvent.ACTION_CANCEL -> { pointerDown = false; removeCallbacks(longPress) }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun onUp(x: Float, y: Float) {
        if (!pointerDown) return
        pointerDown = false
        val now = SystemClock.uptimeMillis()
        if (now - lastThrow < RELOAD_MS || swing != null) return
        val dx = x - downX
        val dy = y - downY
        val dist = hypot(dx, dy)
        var tx = x
        var ty = y
        if (weapon.thrown && dist > 20 * d) {
            if (dy > -10 * d) return // aşağı kaydırma atış değil
            // Hızlı kaydırma hedefi biraz ileri taşır
            val speed = dist / max(16f, (now - downT).toFloat()) // px/ms
            val extra = min(height * 0.12f, speed * 25f)
            tx = x + dx / dist * extra
            ty = y + dy / dist * extra
        }
        tx = tx.coerceIn(0f, width.toFloat())
        ty = ty.coerceIn(0f, height.toFloat())
        lastThrow = now
        shots++
        val hit = PendingHit(tx, ty, weapon)
        // Nesne kesiti, alet havadayken arka planda hazırlanır
        segmentAt?.invoke(tx, ty) { cut -> hit.cut = cut; hit.done = true } ?: run { hit.done = true }
        if (weapon.thrown) {
            val sx = width / 2f
            val sy = handY()
            projectiles += Projectile(hit, sx, sy, min(220f * d, hypot(tx - sx, ty - sy) * 0.35f))
            sfx?.throwSound()
        } else {
            swing = Swing(hit, now)
            sfx?.swing()
        }
    }

    private fun handY() = height - 150 * d

    // ---------- Çarpışma ----------

    /** Kesitin kutusuyla en çok örtüşen, modelin gördüğü nesnenin Türkçe adı. */
    private fun nameFor(box: RectF): String {
        val best = seen.maxByOrNull { iou(it.second, box) }
        return if (best != null && iou(best.second, box) > 0.3f) labelOf(best.first) else "nesne"
    }

    private fun clsFor(box: RectF): String? {
        val best = seen.maxByOrNull { iou(it.second, box) } ?: return null
        return if (iou(best.second, box) > 0.3f) best.first else null
    }

    private fun inRubble(x: Float, y: Float, now: Long): Boolean {
        for (w in looseWrecks) if (now < w.until && w.box.contains(x, y)) return true
        for (t in targets) if (t.isBroken(now) && t.box.contains(x, y)) return true
        return false
    }

    private fun resolve(h: PendingHit) {
        val now = SystemClock.uptimeMillis()
        val w = h.weapon
        val pad = w.hitPadDp * d
        if (!w.thrown) sfx?.melee(w)

        if (inRubble(h.x, h.y, now)) {
            burst(h.x, h.y, 10, intArrayOf(0xFFBFF3FF.toInt(), 0xFF9AA3AA.toInt()), 140f)
            if (w.thrown) sfx?.miss()
            return
        }
        val target = targets
            .filter { !it.isBroken(now) && h.x > it.box.left - pad && h.x < it.box.right + pad &&
                h.y > it.box.top - pad && h.y < it.box.bottom + pad }
            .minByOrNull { it.box.width() * it.box.height() } // en küçüğü, büyük ihtimalle öndeki
        // Kesit hedefle uyuşmuyorsa (başka bir nesneyi seçtiyse) hedefin kutusundan kesilir
        val cut = h.cut?.takeIf { c -> target == null || iou(c.box, target.box) > 0.1f || target.box.contains(c.box.centerX(), c.box.centerY()) }

        if (target != null) {
            target.hp -= w.damage
            if (target.hp > 0) {
                target.cracks += makeCracks((h.x - target.box.left) / target.box.width(), (h.y - target.box.top) / target.box.height(), 6)
                sfx?.hit()
                burst(h.x, h.y, 10, intArrayOf(Color.WHITE, 0xFFCFEFFF.toInt()), 160f)
                shake = max(shake, if (w.thrown) 4f else 8f)
                popup(h.x, h.y - 20 * d, "Çatladı!", 0xFFCFEFFF.toInt())
                return
            }
            val c = cut ?: fallbackCut(target.box)
            breakObject(c, target.info, target.info.label, target, h)
            return
        }
        if (cut != null) {
            val cls = clsFor(cut.box)
            val info = cls?.let { BREAKABLES[it] } ?: GENERIC_OBJECT
            val name = cls?.let { labelOf(it) }?.replaceFirstChar { it.titlecase(java.util.Locale("tr")) } ?: "Nesne"
            breakObject(cut, info, name, null, h)
            return
        }
        // Iska: duvar, zemin ya da boşluk
        if (w.thrown) sfx?.miss()
        burst(h.x, h.y, 8, intArrayOf(0xFFC9B8A0.toInt(), 0xFF8D7D68.toInt()), 120f)
        shake = max(shake, if (w.thrown) 0f else 5f)
    }

    private fun fallbackCut(box: RectF): Cutout? {
        val frame = frameProvider?.invoke() ?: return null
        val c = snapshot(frame, box)
        frame.recycle()
        return c
    }

    private fun breakObject(cut: Cutout?, info: Breakable, name: String, target: Target?, h: PendingHit) {
        val now = SystemClock.uptimeMillis()
        val electronic = info.material == Material.ELECTRONIC
        val box = RectF(target?.box ?: cut?.box ?: RectF(h.x - 40 * d, h.y - 40 * d, h.x + 40 * d, h.y + 40 * d))
        if (cut != null) shards += makeShards(cut, h.x, h.y, h.weapon.power, d)
        val wreck = Wreck(
            now, now + WRECK_MS, cut?.bg ?: Color.DKGRAY,
            if (electronic) Color.argb(230, 40, 40, 45) else Color.argb(140, 200, 235, 245),
            box, cut?.patch, cut?.patchBox,
        )
        if (target != null) {
            target.wreck = wreck
            target.cracks.clear()
            target.hp = info.hp
        } else {
            looseWrecks += wreck
        }
        if (selection != null && iou(selection!!.box, box) > 0.2f) selection = null
        burst(
            h.x, h.y, 30,
            if (electronic) intArrayOf(Color.WHITE, 0xFF9AD7FF.toInt(), 0xFFFFD23F.toInt())
            else intArrayOf(Color.WHITE, 0xFFBFF3FF.toInt(), 0xFFE9FDFF.toInt()),
            380f,
        )
        sfx?.shatter(info.material)
        haptics?.invoke(info.material)
        shake = if (h.weapon.thrown) 14f else 18f
        flash = 0.3f
        score += info.points
        broken++
        popup(h.x, h.y - 30 * d, "$name kırıldı! +${info.points}", 0xFFFFD23F.toInt())
    }

    private fun burst(x: Float, y: Float, n: Int, colors: IntArray, speed: Float) {
        repeat(n) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val v = Random.nextFloat() * speed * d
            particles += Particle(
                x, y, cos(a) * v, sin(a) * v - speed * d * 0.4f,
                0.4f + Random.nextFloat() * 0.6f, (1.5f + Random.nextFloat() * 3.5f) * d,
                colors[Random.nextInt(colors.size)], Random.nextFloat() * 6f,
            )
        }
    }

    private fun popup(x: Float, y: Float, text: String, color: Int) {
        val half = popupPaint.measureText(text) / 2f + 8 * d
        popups += Popup(
            x.coerceIn(half, max(half, width - half)),
            y.coerceIn(insetTop + 130 * d, max(insetTop + 130 * d, height - 220 * d)),
            text, color,
        )
    }

    // ---------- Döngü ----------

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        update(dt, now)
        render(c, now)
        postInvalidateOnAnimation()
    }

    private fun update(dt: Float, now: Long) {
        val pit = projectiles.iterator()
        val landed = ArrayList<PendingHit>()
        while (pit.hasNext()) {
            val p = pit.next()
            p.t = min(1f, p.t + dt / p.hit.weapon.duration)
            p.spin += dt * 12f
            if (p.t >= 1f && p.hit.ready(now)) { pit.remove(); landed += p.hit }
        }
        landed.forEach { resolve(it) }

        swing?.let { s ->
            s.t = min(1f, s.t + dt / s.hit.weapon.duration)
            if (!s.impacted && s.t >= 0.5f && s.hit.ready(now)) {
                s.impacted = true
                resolve(s.hit)
            }
            if (s.t >= 1f && s.impacted) swing = null
        }

        updateShards(shards, dt, height - 30 * d, d)
        val g = 900f * d
        particles.removeAll { q ->
            q.age += dt; q.vy += g * dt; q.x += q.vx * dt; q.y += q.vy * dt; q.rot += dt * 8f
            q.age >= q.life
        }
        popups.removeAll { u -> u.age += dt; u.y -= 40f * d * dt; u.age >= 1.3f }
        shake *= 0.002f.pow(dt)
        flash = max(0f, flash - dt * 1.5f)
        for (t in targets) if (t.wreck != null && now >= t.wreck!!.until) t.wreck = null
        looseWrecks.removeAll { now >= it.until }
        if (selection != null && now - selectionT > 4000) selection = null
    }

    // ---------- Çizim ----------

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val labelBg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD23F.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = 22 * resources.displayMetrics.density
    }
    private val popupStroke = Paint(popupPaint).apply { style = Paint.Style.STROKE; color = Color.argb(180, 0, 0, 0) }
    private val selectionPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(0xFFFFD23F.toInt(), PorterDuff.Mode.SRC_ATOP)
    }
    private val hudBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) }
    private val hudSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 255, 255, 255); textAlign = Paint.Align.CENTER }
    private val hudBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val hudStatus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hudInfo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 200, 230, 255) }
    private val ammoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(64, 0, 0, 0) }
    private val stoneEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(77, 0, 0, 0) }
    private val weaponPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val weaponEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(120, 0, 0, 0) }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2B2B.toInt() }
    private val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt(); style = Paint.Style.STROKE }
    private val flashPaint = Paint()
    private val tmpPath = Path()
    private val tmpRect = RectF()

    private fun render(c: Canvas, now: Long) {
        c.save()
        if (shake > 0.3f) {
            val sx = ((Random.nextFloat() - 0.5f) * shake * d).roundToInt().toFloat()
            val sy = ((Random.nextFloat() - 0.5f) * shake * d).roundToInt().toFloat()
            c.translate(sx, sy)
            shakeTarget?.translationX = sx
            shakeTarget?.translationY = sy
        } else if (shakeTarget?.translationX != 0f || shakeTarget?.translationY != 0f) {
            shakeTarget?.translationX = 0f
            shakeTarget?.translationY = 0f
        }

        // Önce izler (nesnenin yerini örten doldurma), sonra hedef işaretleri
        for (w in looseWrecks) drawWreck(c, w, w.box, now, d)
        for (t in targets) {
            val w = t.wreck
            if (w != null && now < w.until) { drawWreck(c, w, t.box, now, d); continue }
            if (t.cracks.isNotEmpty()) drawCracks(c, t.box, t.cracks, d)
            if (showBoxes) drawMarker(c, t, now)
        }
        selection?.let { s ->
            val pulse = 0.5f + 0.5f * sin((now - selectionT) / 160f)
            selectionPaint.alpha = (70 + 80 * pulse).toInt()
            c.drawBitmap(s.obj, null, s.box, selectionPaint)
        }

        drawShards(c, shards)

        for (q in particles) {
            particlePaint.color = q.color
            particlePaint.alpha = ((1f - q.age / q.life) * 255).toInt().coerceIn(0, 255)
            c.save()
            c.translate(q.x, q.y)
            c.rotate(Math.toDegrees(q.rot.toDouble()).toFloat())
            tmpPath.reset()
            tmpPath.moveTo(0f, -q.size); tmpPath.lineTo(q.size * 0.7f, q.size); tmpPath.lineTo(-q.size * 0.7f, q.size * 0.4f)
            tmpPath.close()
            c.drawPath(tmpPath, particlePaint)
            c.restore()
        }

        for (p in projectiles) {
            val e = 1f - (1f - p.t).pow(1.6f) // ileriye doğru yavaşlama (derinlik hissi)
            val x = p.sx + (p.hit.x - p.sx) * e
            val y = p.sy + (p.hit.y - p.sy) * e - p.arc * 4f * e * (1f - e)
            drawAmmo(c, p.hit.weapon, x, y, p.hit.weapon.radiusDp * d * (1f - 0.72f * e), p.spin)
        }

        drawHand(c, now)
        focusRing?.let { f ->
            val age = now - focusT
            if (age > 900) focusRing = null else {
                markerPaint.color = 0xFFFFD23F.toInt()
                markerPaint.alpha = (255 * (1f - age / 900f)).toInt().coerceIn(0, 255)
                markerPaint.strokeWidth = 2 * d
                c.drawCircle(f[0], f[1], (40 - 12 * min(1f, age / 250f)) * d, markerPaint)
            }
        }

        for (u in popups) {
            val a = (min(1f, 2f * (1.3f - u.age)) * 255).toInt().coerceIn(0, 255)
            popupStroke.strokeWidth = 4 * d
            popupStroke.alpha = a * 180 / 255
            popupPaint.color = u.color
            popupPaint.alpha = a
            c.drawText(u.text, u.x, u.y, popupStroke)
            c.drawText(u.text, u.x, u.y, popupPaint)
        }
        c.restore()

        drawHud(c)

        if (flash > 0f) {
            flashPaint.color = Color.argb((flash * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }
    }

    private fun drawMarker(c: Canvas, t: Target, now: Long) {
        val b = t.box
        val pulse = 0.6f + 0.4f * sin(now / 250f)
        val k = min(22 * d, min(b.width(), b.height()) / 3f)
        markerPaint.color = 0xFFFFD23F.toInt()
        markerPaint.alpha = ((0.5f + 0.4f * pulse) * 255).toInt()
        markerPaint.strokeWidth = 3 * d
        tmpPath.reset()
        for ((x, y, sx, sy) in listOf(
            floatArrayOf(b.left, b.top, 1f, 1f), floatArrayOf(b.right, b.top, -1f, 1f),
            floatArrayOf(b.left, b.bottom, 1f, -1f), floatArrayOf(b.right, b.bottom, -1f, -1f),
        )) {
            tmpPath.moveTo(x + sx * k, y); tmpPath.lineTo(x, y); tmpPath.lineTo(x, y + sy * k)
        }
        c.drawPath(tmpPath, markerPaint)
        val label = if (t.info.hp > 1) t.info.label + " " + "●".repeat(max(0, t.hp)) else t.info.label
        labelText.textSize = 13 * d
        val tw = labelText.measureText(label)
        val top = max(insetTop.toFloat(), b.top - 20 * d)
        c.drawRect(b.left, top, b.left + tw + 10 * d, top + 18 * d, labelBg)
        c.drawText(label, b.left + 5 * d, top + 14 * d, labelText)
    }

    private fun drawAmmo(c: Canvas, type: Weapon, x: Float, y: Float, r: Float, spin: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(Math.toDegrees(spin.toDouble()).toFloat())
        if (type == Weapon.BALL) {
            ammoPaint.shader = RadialGradient(-r * 0.35f, -r * 0.35f, r * 1.2f, Color.WHITE, 0xFFBDBDBD.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, r, ammoPaint)
            for (i in 0 until 6) {
                val a = i / 5f * 2f * PI.toFloat()
                val px = if (i == 5) 0f else cos(a) * r * 0.62f
                val py = if (i == 5) 0f else sin(a) * r * 0.62f
                tmpPath.reset()
                for (k in 0 until 5) {
                    val b = k / 5f * 2f * PI.toFloat() - PI.toFloat() / 2f
                    val qx = px + cos(b) * r * 0.22f
                    val qy = py + sin(b) * r * 0.22f
                    if (k == 0) tmpPath.moveTo(qx, qy) else tmpPath.lineTo(qx, qy)
                }
                tmpPath.close()
                c.drawPath(tmpPath, darkPaint)
            }
        } else {
            ammoPaint.shader = RadialGradient(-r * 0.3f, -r * 0.3f, r * 1.2f, 0xFFA49A8E.toInt(), 0xFF4E4740.toInt(), Shader.TileMode.CLAMP)
            tmpPath.reset()
            for (i in 0 until 9) {
                val qx = stoneShape[i * 2] * r
                val qy = stoneShape[i * 2 + 1] * r
                if (i == 0) tmpPath.moveTo(qx, qy) else tmpPath.lineTo(qx, qy)
            }
            tmpPath.close()
            c.drawPath(tmpPath, ammoPaint)
            stoneEdge.strokeWidth = 1.5f * d
            c.drawPath(tmpPath, stoneEdge)
        }
        c.restore()
    }

    /** FPS tarzı: elde bekleyen top/taş ya da sağ alttan uzanan sopa/anahtar. */
    private fun drawHand(c: Canvas, now: Long) {
        if (weapon.thrown) {
            val ready = now - lastThrow > RELOAD_MS
            val r = weapon.radiusDp * d * 1.3f
            val bob = sin(now / 400f) * 3 * d
            val y = handY() + bob + if (ready) 0f else 60 * d
            tmpRect.set(width / 2f - r, handY() + r * 0.8f, width / 2f + r, handY() + r * 1.2f)
            c.drawOval(tmpRect, shadowPaint)
            drawAmmo(c, weapon, width / 2f, y, r, 0.3f)
        } else {
            drawMelee(c, now)
        }
        if (pointerDown) {
            markerPaint.color = Color.WHITE
            markerPaint.alpha = 180
            markerPaint.strokeWidth = 2 * d
            c.drawCircle(downX, downY, 16 * d, markerPaint)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun drawMelee(c: Canvas, now: Long) {
        val px = width * 0.8f
        val py = height + 20 * d
        val rest = -18f + sin(now / 500f) * 2f
        val s = swing
        val angle: Float
        var ghosts = false
        if (s != null && s.hit.weapon == weapon) {
            // 0 = dik yukarı, pozitif = sağa. Geriye kaldır → hedefe doğru savur → geri dön
            val target = Math.toDegrees(atan2((s.hit.x - px).toDouble(), (py - s.hit.y).toDouble())).toFloat()
            val windup = rest + 35f
            val follow = target - 30f
            angle = when {
                s.t < 0.3f -> lerp(rest, windup, s.t / 0.3f)
                s.t < 0.55f -> { ghosts = true; lerp(windup, follow, ((s.t - 0.3f) / 0.25f).pow(0.7f)) }
                else -> lerp(follow, rest, (s.t - 0.55f) / 0.45f)
            }
            if (ghosts) {
                for ((back, alpha) in listOf(12f to 50, 24f to 25)) drawWeaponAt(c, px, py, angle + back, alpha)
            }
        } else {
            angle = rest
        }
        drawWeaponAt(c, px, py, angle, 255)
    }

    private fun drawWeaponAt(c: Canvas, px: Float, py: Float, angleDeg: Float, alpha: Int) {
        c.save()
        c.translate(px, py)
        c.rotate(angleDeg)
        val len = min(if (weapon == Weapon.BAT) 320 * d else 250 * d, height * 0.5f)
        if (weapon == Weapon.BAT) drawBat(c, len, alpha) else drawWrench(c, len, alpha)
        c.restore()
    }

    /** Tahta beyzbol sopası, sap orijinde, uç -y yönünde. */
    private fun drawBat(c: Canvas, len: Float, alpha: Int) {
        val g = 5.5f * d
        val b = 14f * d
        tmpPath.reset()
        tmpPath.moveTo(-g, 0f)
        tmpPath.lineTo(-g, -0.32f * len)
        tmpPath.lineTo(-b, -0.9f * len)
        tmpPath.quadTo(-b, -len, 0f, -len)
        tmpPath.quadTo(b, -len, b, -0.9f * len)
        tmpPath.lineTo(g, -0.32f * len)
        tmpPath.lineTo(g, 0f)
        tmpPath.close()
        weaponPaint.shader = LinearGradient(-b, 0f, b, 0f,
            intArrayOf(0xFF7A4A22.toInt(), 0xFFE0A86E.toInt(), 0xFF7A4A22.toInt()), null, Shader.TileMode.CLAMP)
        weaponPaint.alpha = alpha
        c.drawPath(tmpPath, weaponPaint)
        weaponEdge.strokeWidth = 1.5f * d
        weaponEdge.alpha = alpha / 2
        c.drawPath(tmpPath, weaponEdge)
        // Sap bandı
        gripPaint.alpha = alpha
        c.drawRect(-g - 0.5f * d, -0.3f * len, g + 0.5f * d, 0f, gripPaint)
        tapePaint.strokeWidth = 1.2f * d
        tapePaint.alpha = alpha
        var y = -6 * d
        while (y > -0.3f * len) { c.drawLine(-g, y, g, y - 5 * d, tapePaint); y -= 9 * d }
    }

    /** İngiliz anahtarı: çelik gövde, üstte açık ağız, altta halka. */
    private fun drawWrench(c: Canvas, len: Float, alpha: Int) {
        val w = 8f * d
        val headR = 26f * d
        val headY = -0.86f * len
        val body = Path().apply {
            addRoundRect(RectF(-w, headY + headR * 0.6f, w, 0f), 4 * d, 4 * d, Path.Direction.CW)
            addCircle(0f, headY, headR, Path.Direction.CW)
            addCircle(0f, -0.08f * len, 17 * d, Path.Direction.CW)
        }
        val holes = Path().apply {
            addRect(RectF(-9 * d, headY - headR - 2 * d, 9 * d, headY + 3 * d), Path.Direction.CW) // ağız
            addCircle(0f, -0.08f * len, 9 * d, Path.Direction.CW) // halka deliği
        }
        body.op(holes, Path.Op.DIFFERENCE)
        weaponPaint.shader = LinearGradient(-headR, 0f, headR, 0f,
            intArrayOf(0xFF5E646B.toInt(), 0xFFE3E7EB.toInt(), 0xFF5E646B.toInt()), null, Shader.TileMode.CLAMP)
        weaponPaint.alpha = alpha
        c.drawPath(body, weaponPaint)
        weaponEdge.strokeWidth = 1.5f * d
        weaponEdge.alpha = alpha / 2
        c.drawPath(body, weaponEdge)
    }

    private fun drawHud(c: Canvas) {
        val top = insetTop + 10 * d
        var x = 12 * d
        hudSmall.textSize = 11 * d
        hudBig.textSize = 20 * d
        for ((label, value) in listOf("Puan" to score, "Kırılan" to broken, "Vuruş" to shots)) {
            val w = 64 * d
            tmpRect.set(x, top, x + w, top + 46 * d)
            c.drawRoundRect(tmpRect, 10 * d, 10 * d, hudBg)
            c.drawText(label, x + w / 2f, top + 15 * d, hudSmall)
            c.drawText(value.toString(), x + w / 2f, top + 38 * d, hudBig)
            x += w + 8 * d
        }
        hudStatus.textSize = 13 * d
        val maxW = width - 24 * d - 20 * d
        var text = status
        if (hudStatus.measureText(text) > maxW) {
            val n = hudStatus.breakText(text, true, maxW - hudStatus.measureText("…"), null)
            text = text.substring(0, n) + "…"
        }
        val sy = top + 54 * d
        tmpRect.set(12 * d, sy, 12 * d + hudStatus.measureText(text) + 20 * d, sy + 26 * d)
        c.drawRoundRect(tmpRect, 8 * d, 8 * d, hudBg)
        c.drawText(text, 22 * d, sy + 18 * d, hudStatus)

        if (info.isNotEmpty()) {
            hudInfo.textSize = 11 * d
            var t2 = info
            if (hudInfo.measureText(t2) > maxW) {
                val n = hudInfo.breakText(t2, true, maxW - hudInfo.measureText("…"), null)
                t2 = t2.substring(0, n) + "…"
            }
            val iy = sy + 32 * d
            tmpRect.set(12 * d, iy, 12 * d + hudInfo.measureText(t2) + 16 * d, iy + 20 * d)
            c.drawRoundRect(tmpRect, 6 * d, 6 * d, hudBg)
            c.drawText(t2, 20 * d, iy + 14 * d, hudInfo)
        }
    }

    private companion object {
        const val IDLE_STATUS = "Neye vurursan kırılır · uzun bas: seç ve odakla · iki parmak: yakınlaştır"
    }
}
