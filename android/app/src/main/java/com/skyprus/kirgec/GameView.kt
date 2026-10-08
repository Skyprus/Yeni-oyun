package com.skyprus.kirgec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val WRECK_MS = 6000L   // kırılan nesnenin "yok" görüneceği süre
private const val LOST_MS = 1200L    // görülmeyen hedefin silinme süresi
private const val RELOAD_MS = 280L

class Target(
    val cls: String,
    val info: Breakable,
    val box: RectF,
    var hp: Int,
    var lastSeen: Long,
    val manual: Boolean,
) {
    val cracks = ArrayList<FloatArray>()
    var wreck: Wreck? = null
    fun isBroken(now: Long) = wreck?.let { now < it.until } ?: false
}

private class Projectile(
    val ammo: Ammo, val sx: Float, val sy: Float, val tx: Float, val ty: Float, val arc: Float,
) {
    var t = 0f
    var spin = 0f
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
 * Kamera önizlemesinin üstünde duran oyun katmanı: hedef takibi, atış, kırılma efektleri ve HUD.
 * Kendi kendini her karede yeniden çizer (postInvalidateOnAnimation).
 */
class GameView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val d = resources.displayMetrics.density

    /** Ekranda görünen kamera karesini (ekran pikselleriyle) verir. */
    var frameProvider: (() -> Bitmap?)? = null
    var sfx: Sfx? = null
    var haptics: ((Material) -> Unit)? = null
    var shakeTarget: View? = null
    var onMarkModeChanged: ((Boolean) -> Unit)? = null
    var insetTop = 0

    var ammo = Ammo.BALL
    var showBoxes = true
    var markMode = false
        set(v) {
            field = v
            if (v) status = "Kırmak istediğin şeyin etrafına parmağınla kutu çiz."
            onMarkModeChanged?.invoke(v)
        }

    var status = "Kamera açılıyor…"
    private var modelState = 0 // 0 yükleniyor, 1 hazır, -1 yok

    private val targets = ArrayList<Target>()
    private val projectiles = ArrayList<Projectile>()
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

    // Kamera görüntüsü → ekran eşlemesi (önizleme FILL_CENTER ile ekranı doldurur)
    private var imgW = 0
    private var imgH = 0

    // Dokunma durumu
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var pointerDown = false
    private var drawing: RectF? = null
    private var drawStartX = 0f
    private var drawStartY = 0f

    private val stoneShape = FloatArray(18).also {
        for (i in 0 until 9) {
            val a = i / 9f * 2f * PI.toFloat()
            it[i * 2] = cos(a) * (0.75f + Random.nextFloat() * 0.3f)
            it[i * 2 + 1] = sin(a) * (0.7f + Random.nextFloat() * 0.3f)
        }
    }

    // ---------- Model durumu ve tespitler ----------

    fun setModelReady(ok: Boolean, gpu: Boolean) {
        modelState = if (ok) 1 else -1
        status = if (ok) {
            "Kırılabilir eşya aranıyor… (şişe, bardak, vazo, ekran…)" + if (gpu) "" else " [CPU]"
        } else {
            "Nesne tanıma başlatılamadı — \"Hedef çiz\" ile kendin işaretle."
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val x1 = max(a.left, b.left); val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right); val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    /** Ana iş parçacığında çağrılır. Kutular görüntü piksellerindedir (iw x ih). */
    fun ingest(dets: List<Det>, iw: Int, ih: Int) {
        imgW = iw; imgH = ih
        if (width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        val scale = max(width / iw.toFloat(), height / ih.toFloat())
        val ox = (width - iw * scale) / 2f
        val oy = (height - ih * scale) / 2f
        val used = HashSet<Target>()
        for (det in dets) {
            val info = BREAKABLES[det.cls] ?: continue
            val box = RectF(
                det.box.left * scale + ox, det.box.top * scale + oy,
                det.box.right * scale + ox, det.box.bottom * scale + oy,
            )
            var best: Target? = null
            var bestScore = 0.15f
            for (t in targets) {
                if (t.manual || t.cls != det.cls || t in used) continue
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
                val t = Target(det.cls, info, box, info.hp, now, false)
                targets += t
                used += t
            }
        }
        targets.removeAll { !it.manual && now - it.lastSeen > LOST_MS && !it.isBroken(now) }
        if (modelState == 1 && !markMode) {
            val live = targets.count { !it.isBroken(now) }
            status = if (live > 0) "$live kırılabilir hedef görüldü — at!"
            else "Kırılabilir eşya aranıyor… (şişe, bardak, vazo, ekran…)"
        }
    }

    // ---------- Girdi ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (markMode) {
                    drawStartX = e.x; drawStartY = e.y
                    drawing = RectF(e.x, e.y, e.x, e.y)
                } else {
                    downX = e.x; downY = e.y; downT = SystemClock.uptimeMillis(); pointerDown = true
                }
            }
            MotionEvent.ACTION_MOVE -> drawing?.set(
                min(drawStartX, e.x), min(drawStartY, e.y), max(drawStartX, e.x), max(drawStartY, e.y),
            )
            MotionEvent.ACTION_UP -> {
                performClick()
                onUp(e.x, e.y)
            }
            MotionEvent.ACTION_CANCEL -> { pointerDown = false; drawing = null }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun onUp(x: Float, y: Float) {
        drawing?.let { box ->
            drawing = null
            if (box.width() > 25 * d && box.height() > 25 * d) {
                targets += Target("manual", MANUAL_TARGET, RectF(box), MANUAL_TARGET.hp, SystemClock.uptimeMillis(), true)
                markMode = false
                status = "Hedef eklendi — şimdi fırlat!"
            }
            return
        }
        if (!pointerDown) return
        pointerDown = false
        val now = SystemClock.uptimeMillis()
        if (now - lastThrow < RELOAD_MS) return
        val dx = x - downX
        val dy = y - downY
        val dist = hypot(dx, dy)
        var tx = x
        var ty = y
        if (dist > 20 * d) {
            if (dy > -10 * d) return // aşağı kaydırma atış değil
            // Hızlı kaydırma hedefi biraz ileri taşır
            val speed = dist / max(16f, (now - downT).toFloat()) // px/ms
            val extra = min(height * 0.12f, speed * 25f)
            tx = x + dx / dist * extra
            ty = y + dy / dist * extra
        }
        throwAt(tx.coerceIn(0f, width.toFloat()), ty.coerceIn(0f, height.toFloat()))
    }

    private fun handY() = height - 150 * d

    private fun throwAt(tx: Float, ty: Float) {
        lastThrow = SystemClock.uptimeMillis()
        shots++
        val sx = width / 2f
        val sy = handY()
        val spread = (if (ammo == Ammo.STONE) 6f else 12f) * d
        projectiles += Projectile(
            ammo, sx, sy,
            tx + (Random.nextFloat() - 0.5f) * spread,
            ty + (Random.nextFloat() - 0.5f) * spread,
            min(220f * d, hypot(tx - sx, ty - sy) * 0.35f),
        )
        sfx?.throwSound()
    }

    // ---------- Çarpışma ----------

    private fun impact(p: Projectile) {
        val now = SystemClock.uptimeMillis()
        val pad = p.ammo.hitPadDp * d
        val hit = targets
            .filter { !it.isBroken(now) && p.tx > it.box.left - pad && p.tx < it.box.right + pad &&
                p.ty > it.box.top - pad && p.ty < it.box.bottom + pad }
            .minByOrNull { it.box.width() * it.box.height() } // en küçüğü, büyük ihtimalle öndeki
        if (hit == null) {
            sfx?.miss()
            burst(p.tx, p.ty, 8, intArrayOf(0xFFC9B8A0.toInt(), 0xFF8D7D68.toInt()), 120f)
            return
        }
        hit.hp -= p.ammo.damage
        if (hit.hp > 0) {
            hit.cracks += makeCracks((p.tx - hit.box.left) / hit.box.width(), (p.ty - hit.box.top) / hit.box.height(), 6)
            sfx?.hit()
            burst(p.tx, p.ty, 10, intArrayOf(Color.WHITE, 0xFFCFEFFF.toInt()), 160f)
            shake = max(shake, 4f)
            popup(p.tx, p.ty - 20 * d, "Çatladı!", 0xFFCFEFFF.toInt())
            return
        }
        shatter(hit, p.tx, p.ty, p.ammo.power)
    }

    private fun shatter(t: Target, x: Float, y: Float, power: Float) {
        val now = SystemClock.uptimeMillis()
        val info = t.info
        val frame = frameProvider?.invoke()
        var bg = Color.DKGRAY
        if (frame != null) {
            snapshot(frame, t.box)?.let { snap ->
                shards += makeShards(snap, x, y, power, d)
                bg = snap.bgColor
            }
            frame.recycle()
        }
        val electronic = info.material == Material.ELECTRONIC
        burst(
            x, y, 30,
            if (electronic) intArrayOf(Color.WHITE, 0xFF9AD7FF.toInt(), 0xFFFFD23F.toInt())
            else intArrayOf(Color.WHITE, 0xFFBFF3FF.toInt(), 0xFFE9FDFF.toInt()),
            380f,
        )
        sfx?.shatter(info.material)
        haptics?.invoke(info.material)
        shake = 14f
        flash = 0.35f
        t.cracks.clear()
        t.wreck = Wreck(now, now + WRECK_MS, bg, if (electronic) Color.argb(230, 40, 40, 45) else Color.argb(140, 200, 235, 245))
        t.hp = info.hp
        score += info.points
        broken++
        popup(x, y - 30 * d, "${info.label} kırıldı! +${info.points}", 0xFFFFD23F.toInt())
        if (t.manual) postDelayed({ targets.remove(t) }, WRECK_MS)
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
            y.coerceIn(insetTop + 110 * d, max(insetTop + 110 * d, height - 200 * d)),
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
        val landed = ArrayList<Projectile>()
        while (pit.hasNext()) {
            val p = pit.next()
            p.t += dt / p.ammo.duration
            p.spin += dt * 12f
            if (p.t >= 1f) { pit.remove(); landed += p }
        }
        landed.forEach { impact(it) }
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
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFD23F.toInt()
    }
    private val hudBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) }
    private val hudSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 255, 255, 255) ; textAlign = Paint.Align.CENTER }
    private val hudBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val hudStatus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ammoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(64, 0, 0, 0) }
    private val stoneEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(77, 0, 0, 0) }
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

        for (t in targets) {
            val w = t.wreck
            if (w != null && now < w.until) { drawWreck(c, w, t.box, now, d); continue }
            if (t.cracks.isNotEmpty()) drawCracks(c, t.box, t.cracks, d)
            if (showBoxes) drawMarker(c, t, now)
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
            val x = p.sx + (p.tx - p.sx) * e
            val y = p.sy + (p.ty - p.sy) * e - p.arc * 4f * e * (1f - e)
            drawAmmo(c, p.ammo, x, y, p.ammo.radiusDp * d * (1f - 0.72f * e), p.spin)
        }

        drawing?.let {
            selectPaint.strokeWidth = 2 * d
            selectPaint.pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
            c.drawRect(it, selectPaint)
        }
        if (!markMode) drawHand(c, now)

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

    private fun drawAmmo(c: Canvas, type: Ammo, x: Float, y: Float, r: Float, spin: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(Math.toDegrees(spin.toDouble()).toFloat())
        if (type == Ammo.BALL) {
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

    /** FPS tarzı: elde bekleyen top/taş. */
    private fun drawHand(c: Canvas, now: Long) {
        val ready = now - lastThrow > RELOAD_MS
        val r = ammo.radiusDp * d * 1.3f
        val bob = sin(now / 400f) * 3 * d
        val y = handY() + bob + if (ready) 0f else 60 * d
        tmpRect.set(width / 2f - r * 1.0f, handY() + r * 0.8f, width / 2f + r * 1.0f, handY() + r * 1.2f)
        c.drawOval(tmpRect, shadowPaint)
        drawAmmo(c, ammo, width / 2f, y, r, 0.3f)
        if (pointerDown) {
            markerPaint.color = Color.WHITE
            markerPaint.alpha = 180
            markerPaint.strokeWidth = 2 * d
            c.drawCircle(downX, downY, 16 * d, markerPaint)
        }
    }

    private fun drawHud(c: Canvas) {
        val top = insetTop + 10 * d
        var x = 12 * d
        hudSmall.textSize = 11 * d
        hudBig.textSize = 20 * d
        for ((label, value) in listOf("Puan" to score, "Kırılan" to broken, "Atış" to shots)) {
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
    }
}
