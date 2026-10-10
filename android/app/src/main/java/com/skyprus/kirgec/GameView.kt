package com.skyprus.kirgec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import java.util.Locale
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

private const val WRECK_MS = 7000L    // kırılan nesnenin "yok" görüneceği süre
private const val LOST_MS = 1200L     // takip edilen hedef görülmezse silinme süresi
private const val PINNED_MS = 45000L  // seçilen/taranan (sabit) hedeflerin ömrü
private const val RELOAD_MS = 280L
private const val SEG_WAIT_MS = 450L  // çarpmada segmentasyon sonucunu en fazla bu kadar bekle
private const val MAX_BOUNCES = 6

/**
 * Kırılabilir hedef. Otomatik tanınanlar kamera karelerinde takip edilir; [pinned] olanlar
 * (seçilen ya da tarananlar) ekranda sabit kalır, aynı sınıftan bir tespitle örtüşürse onu izler.
 */
class Target(
    val cls: String,
    val info: Breakable,
    val box: RectF,
    var hp: Int,
    var lastSeen: Long,
    val pinned: Boolean = false,
) {
    val cracks = ArrayList<FloatArray>()
    var wreck: Wreck? = null
    var selected = false
    var cut: Cutout? = null
    val created = SystemClock.uptimeMillis()
    fun isBroken(now: Long) = wreck?.let { now < it.until } ?: false
}

/** Bir vuruş: hedef noktası ve o noktadaki nesnenin (arka planda hesaplanan) kesiti. */
private class PendingHit(
    val x: Float,
    val y: Float,
    val weapon: Weapon,
    val chain: Int = 0,
    val visited: Set<Target> = emptySet(),
    val aimed: Target? = null,
) {
    val created = SystemClock.uptimeMillis()
    @Volatile var cut: Cutout? = null
    @Volatile var done = false
    fun ready(now: Long) = done || now - created > SEG_WAIT_MS
}

private class Projectile(
    val hit: PendingHit,
    val sx: Float,
    val sy: Float,
    val arc: Float,
    val dur: Float,
    val s0: Float,
    val s1: Float,
) {
    var t = 0f
    var spin = 0f
}

/** Sekecek nesne kalmayınca yere düşüp seken top. */
private class DropBall(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float) {
    var age = 0f
    var spin = 0f
    var bounces = 0
}

private class Swing(val hit: PendingHit) {
    var t = 0f
    var impacted = false
}

private class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val life: Float, val size: Float, val color: Int, var rot: Float,
) {
    var age = 0f
}

/** Sahneyi aydınlatan geçici ışık (patlama). */
private class Glow(val x: Float, val y: Float, val r: Float, val start: Long, val dur: Long, val color: Int, val peak: Float)

/** Genişleyen şok dalgası / toz halkası. */
private class Ring(val x: Float, val y: Float, val maxR: Float, val start: Long, val dur: Long, val color: Int)

/** Zeminde yanık izi (0) ya da krater çatlakları (1). */
private class Scorch(val x: Float, val y: Float, val rx: Float, val ry: Float, val until: Long, val kind: Int) {
    val cracks = FloatArray(16) { Random.nextFloat() }
}

private class Popup(val x: Float, var y: Float, val text: String, val color: Int) {
    var age = 0f
}

/**
 * Kamera önizlemesinin üstünde duran oyun katmanı: hedef takibi, seçim, atış/savurma/sapan,
 * seken top, kırılma efektleri ve HUD. Kendi kendini her karede yeniden çizer.
 */
class GameView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val tr = Locale("tr")

    /** Ekranda görünen kamera karesini (ekran pikselleriyle) verir. */
    var frameProvider: (() -> Bitmap?)? = null
    /** (x, y) noktasındaki nesneyi arka planda ayırır; sonuç ana iş parçacığında geri çağrılır. */
    var segmentAt: ((Float, Float, (Cutout?) -> Unit) -> Unit)? = null
    var sfx: Sfx? = null
        set(v) { field = v; basket.sfx = v }
    var haptics: ((Material) -> Unit)? = null
    var shakeTarget: View? = null
    /** Uzun basınca o noktaya odaklan (ekran koordinatı). */
    var onFocusRequest: ((Float, Float) -> Unit)? = null
    /** İki parmakla yakınlaştırma: çarpan. */
    var onZoom: ((Float) -> Unit)? = null
    var onSelectModeChanged: ((Boolean) -> Unit)? = null
    /** Tanı satırı: modelin gördüğü her şey, tahmin süresi. */
    var info = ""
    var insetTop = 0

    var weapon = Weapon.BALL
        set(v) { field = v; pulling = false }
    var showBoxes = true
    /** Seçim modu: dokunulan nesne seçilir/seçim kaldırılır, vuruş yapılmaz. */
    var selectMode = false
        set(v) {
            field = v
            if (v && drawMode) { drawMode = false }
            onSelectModeChanged?.invoke(v)
            hint(if (v) "Seç: nesneye dokun (otomatik) ya da etrafına kutu çiz · tekrar dokun: kaldır" else selectionHint())
        }
    /** Çizim modu: parmakla nesnenin etrafına kare/dikdörtgen çizilir, vuruş yapılmaz. */
    var drawMode = false
        set(v) {
            field = v
            if (v && selectMode) { selectMode = false }
            onDrawModeChanged?.invoke(v)
            hint(if (v) "Çiz: kırmak istediğin nesnenin etrafına parmağınla kutu çiz" else selectionHint())
        }
    var onDrawModeChanged: ((Boolean) -> Unit)? = null

    /** Basket modu: kırma yok; top görülen cisimlerden sekerek sanal potaya atılır. */
    val basket = BasketGame(resources.displayMetrics.density).also { b ->
        b.onScore = { pts, text, x, y ->
            score += pts
            popup(x, y, text, if (pts > 0) 0xFFFFB347.toInt() else 0xFFCFEFFF.toInt())
            if (pts > 0) { flash = 0.15f; shake = max(shake, 3f) }
        }
    }
    var basketMode = false
        set(v) {
            field = v
            onBasketModeChanged?.invoke(v)
            if (v) { refreshBasket(); hint(basket.message) } else hint(selectionHint())
        }
    var onBasketModeChanged: ((Boolean) -> Unit)? = null
    private var lastCourse = 0L

    /** Merdiven adayları: takip edilen/seçilen hedefler + modelin gördüğü diğer cisimler. */
    private fun refreshBasket() {
        if (width == 0) return
        val now = SystemClock.uptimeMillis()
        val boxes = ArrayList<RectF>()
        for (t in targets) if (!t.isBroken(now)) boxes += t.box
        for ((_, b) in seen) if (boxes.none { iou(it, b) > 0.5f }) boxes += b
        basket.updateCourse(boxes, width, height, insetTop)
        lastCourse = now
    }
    private val marking get() = selectMode || drawMode
    private var drawing: RectF? = null
    var status = "Kamera açılıyor…"
    private var statusT = 0L
    private var modelState = 0 // 0 yükleniyor, 1 hazır, -1 yok

    private val targets = ArrayList<Target>()
    private val looseWrecks = ArrayList<Wreck>() // takip edilmeyen nesnelerin izleri
    private var seen: List<Pair<String, RectF>> = emptyList() // son karede görülen her şey (ekran koordinatı)
    private val projectiles = ArrayList<Projectile>()
    private val drops = ArrayList<DropBall>()
    private var swing: Swing? = null
    private val shards = ArrayList<Shard>()
    private val fires = ArrayList<Fire>()
    private val fireParticles = ArrayList<FireParticle>()
    private val falling = ArrayList<Pair<Boulder, PendingHit>>()
    private val rocks = ArrayList<Boulder>()
    private val flameSprite = softSprite(0xFFFFF4C2.toInt(), 0xFFFF8A1E.toInt())
    private val smokeSprite = softSprite(0xB0807A72.toInt(), 0x60706A62)
    private val darkSmokeSprite = softSprite(0xF0161210.toInt(), 0x90201C18.toInt())
    private val glows = ArrayList<Glow>()
    private val rings = ArrayList<Ring>()
    private val scorches = ArrayList<Scorch>()
    private var punch = 0f
    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private var score = 0
    private var broken = 0
    private var shots = 0
    private var lastThrow = 0L
    private var shake = 0f
    private var flash = 0f
    private var lastFrame = 0L
    private var lastScale = 1f
    private var lastOx = 0f
    private var lastOy = 0f

    // Dokunma durumu
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var pointerDown = false
    // Sapan
    private var pulling = false
    private var pullX = 0f
    private var pullY = 0f

    private val iconRock = Boulder(0f, 0f, 1f, 1f).shape
    private val stoneShape = FloatArray(18).also {
        for (i in 0 until 9) {
            val a = i / 9f * 2f * PI.toFloat()
            it[i * 2] = cos(a) * (0.75f + Random.nextFloat() * 0.3f)
            it[i * 2 + 1] = sin(a) * (0.7f + Random.nextFloat() * 0.3f)
        }
    }

    // ---------- Durum ----------

    /** Birkaç saniye ekranda kalan bilgi mesajı (otomatik durum mesajları üzerine yazmaz). */
    fun hint(text: String) {
        status = text
        statusT = SystemClock.uptimeMillis()
    }

    fun setModelReady(ok: Boolean) {
        modelState = if (ok) 1 else -1
        hint(if (ok) IDLE_STATUS else "Otomatik tanıma kapalı — yine de istediğin nesneye vurabilirsin.")
    }

    private fun selectedCount(now: Long) = targets.count { it.selected && !it.isBroken(now) }

    private fun selectionHint(): String {
        val n = selectedCount(SystemClock.uptimeMillis())
        return if (n > 0) "$n nesne seçili — topu at, aralarında seksin!" else IDLE_STATUS
    }

    private fun labelOf(cls: String) = BREAKABLES[cls]?.label ?: OTHER_LABELS[cls] ?: cls

    // ---------- Tespitler ----------

    /** Ana iş parçacığında çağrılır. Kutular görüntü piksellerindedir (iw x ih). */
    fun ingest(dets: List<Det>, iw: Int, ih: Int, ms: Long) {
        info = "Tanıma ${ms} ms · görülen: " + if (dets.isEmpty()) "—" else dets.sortedByDescending { it.score }
            .joinToString(", ") { labelOf(it.cls) + " " + (it.score * 100).toInt() + "%" }
        if (width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        lastScale = max(width / iw.toFloat(), height / ih.toFloat())
        lastOx = (width - iw * lastScale) / 2f
        lastOy = (height - ih * lastScale) / 2f
        val screen = dets.map { Det(it.cls, toScreen(it.box), it.score) }
        seen = (screen.filter { it.score >= 0.35f }.map { it.cls to it.box } +
            seen.filter { s -> screen.none { iou(it.box, s.second) > 0.3f } }.take(10)).take(30)
        merge(screen.filter { BREAKABLES.containsKey(it.cls) }, now, pin = false)
        targets.removeAll { t ->
            !t.isBroken(now) && if (t.pinned) now - t.created > PINNED_MS
            else now - t.lastSeen > if (t.selected) PINNED_MS else LOST_MS
        }
        if (basketMode) {
            refreshBasket()
            if (now - statusT > 2500) status = basket.message
            return
        }
        if (modelState == 1 && now - statusT > 2500) {
            val live = targets.count { !it.isBroken(now) }
            status = when {
                selectedCount(now) > 0 -> selectionHint()
                live > 0 -> "$live kırılabilir eşya görüldü — vur! (her şeyi kırabilirsin)"
                else -> IDLE_STATUS
            }
        }
    }

    private fun toScreen(r: RectF) =
        RectF(r.left * lastScale + lastOx, r.top * lastScale + lastOy, r.right * lastScale + lastOx, r.bottom * lastScale + lastOy)

    /** Tespitleri mevcut hedeflerle eşleştirir (takip), yenilerini ekler. */
    private fun merge(dets: List<Det>, now: Long, pin: Boolean): Int {
        var added = 0
        val used = HashSet<Target>()
        for (det in dets) {
            val info = BREAKABLES[det.cls] ?: continue
            var best: Target? = null
            var bestScore = 0.15f
            for (t in targets) {
                if (t.cls != det.cls || t in used) continue
                val s = iou(t.box, det.box)
                if (s > bestScore) { best = t; bestScore = s }
            }
            if (best != null) {
                val k = 0.5f // yumuşatma
                best.box.set(
                    best.box.left + (det.box.left - best.box.left) * k,
                    best.box.top + (det.box.top - best.box.top) * k,
                    best.box.right + (det.box.right - best.box.right) * k,
                    best.box.bottom + (det.box.bottom - best.box.bottom) * k,
                )
                best.lastSeen = now
                if (pin && !best.selected) { best.selected = true; added++ }
                used += best
            } else {
                val t = Target(det.cls, info, RectF(det.box), info.hp, now, pinned = pin)
                t.selected = pin
                targets += t
                used += t
                added++
            }
        }
        return added
    }

    /** Ayrıntılı taramanın sonucu (ekran koordinatı): kırılabilir olanlar seçilir. */
    fun addScan(dets: List<Det>) {
        val now = SystemClock.uptimeMillis()
        val breakables = dets.filter { BREAKABLES.containsKey(it.cls) }
        val n = merge(breakables, now, pin = true)
        seen = (dets.map { it.cls to it.box } + seen).take(40)
        val names = breakables.groupBy { labelOf(it.cls) }.entries.joinToString(", ") { "${it.key} ×${it.value.size}" }
        hint(if (breakables.isEmpty()) "Tarama: kırılabilir eşya bulunamadı (${dets.size} başka nesne). Seç ile kendin işaretle."
        else "Tarama: $names — ${selectedCount(now)} nesne seçili" + if (n == 0) " (zaten seçiliydi)" else "")
    }

    // ---------- Seçim ----------

    private fun selectAt(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        // Seçili bir nesneye dokunulduysa seçimi kaldır
        targets.filter { it.selected && !it.isBroken(now) && it.box.contains(x, y) }
            .minByOrNull { it.box.width() * it.box.height() }?.let { t ->
                t.selected = false
                if (t.pinned) targets.remove(t)
                hint("Seçim kaldırıldı · ${selectionHint()}")
                return
            }
        hint("Seçiliyor…")
        segmentAt?.invoke(x, y) { cut ->
            if (cut == null) {
                hint("Burada seçilebilecek bir nesne bulunamadı (duvar/zemin çok büyük).")
                return@invoke
            }
            val t2 = SystemClock.uptimeMillis()
            // Takip edilen bir hedefle örtüşüyorsa onu seç, değilse sabit hedef oluştur
            val tracked = targets.filter { !it.isBroken(t2) && (iou(it.box, cut.box) > 0.3f || it.box.contains(x, y)) }
                .minByOrNull { it.box.width() * it.box.height() }
            val t = tracked ?: run {
                val cls = clsFor(cut.box)
                val base = cls?.let { BREAKABLES[it] } ?: GENERIC_OBJECT
                val name = cls?.let { labelOf(it).replaceFirstChar { c -> c.titlecase(tr) } } ?: "Nesne"
                Target(cls ?: "object", base.copy(label = name), RectF(cut.box), base.hp, t2, pinned = true)
                    .also { targets += it }
            }
            t.selected = true
            t.cut = cut
            hint("Seçildi: ${t.info.label} · ${selectionHint()}")
        }
    }

    /**
     * Elle çizilen kutu: sabit, seçili bir hedef olur. Kutunun ortasındaki nesne ayrılabilirse
     * gerçek silüetiyle kırılır, ayrılamazsa kutunun kendisi kırılır.
     */
    private fun addBoxTarget(box: RectF) {
        val now = SystemClock.uptimeMillis()
        val cls = clsFor(box)
        val base = cls?.let { BREAKABLES[it] } ?: GENERIC_OBJECT
        val name = cls?.let { labelOf(it).replaceFirstChar { c -> c.titlecase(tr) } } ?: "Hedef"
        val t = Target(cls ?: "box", base.copy(label = name), RectF(box), base.hp, now, pinned = true)
        t.selected = true
        targets += t
        hint("Kutu eklendi: $name · ${selectionHint()}")
        segmentAt?.invoke(box.centerX(), box.centerY()) { cut ->
            if (cut != null && box.contains(cut.box.centerX(), cut.box.centerY()) && iou(cut.box, box) > 0.2f) t.cut = cut
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

    /** Uzun basış: o noktaya odaklan ve oradaki nesneyi seç. */
    private val longPress = Runnable {
        if (!pointerDown || pulling) return@Runnable
        pointerDown = false
        focusRing = floatArrayOf(downX, downY)
        focusT = SystemClock.uptimeMillis()
        onFocusRequest?.invoke(downX, downY)
        selectAt(downX, downY)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            // İki parmak: yakınlaştırma, vuruş değil
            pointerDown = false
            pulling = false
            drawing = null
            removeCallbacks(longPress)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = SystemClock.uptimeMillis(); pointerDown = true
                when {
                    marking -> drawing = RectF(e.x, e.y, e.x, e.y)
                    basketMode -> basket.onDown(e.x, e.y)
                    weapon == Weapon.SLING -> { pulling = true; pullX = 0f; pullY = 0f }
                    else -> postDelayed(longPress, 450)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > 12 * d) removeCallbacks(longPress)
                drawing?.set(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
                if (basketMode) basket.onMove(e.x, e.y)
                if (pulling) {
                    // Lastik en fazla MAX_PULL kadar gerilir
                    var px = e.x - downX
                    var py = e.y - downY
                    val len = hypot(px, py)
                    val maxPull = MAX_PULL * d
                    if (len > maxPull) { px *= maxPull / len; py *= maxPull / len }
                    pullX = px; pullY = py
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                performClick()
                onUp(e.x, e.y)
            }
            MotionEvent.ACTION_CANCEL -> { pointerDown = false; pulling = false; drawing = null; removeCallbacks(longPress) }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun onUp(x: Float, y: Float) {
        if (!pointerDown) return
        pointerDown = false
        if (marking) {
            val box = drawing
            drawing = null
            when {
                box != null && box.width() > 25 * d && box.height() > 25 * d -> addBoxTarget(box)
                selectMode && hypot(x - downX, y - downY) < 20 * d -> selectAt(x, y)
                drawMode -> hint("Kutu çizmek için parmağını nesnenin bir köşesinden karşı köşesine sürükle")
            }
            return
        }
        if (basketMode) {
            basket.onUp(width, height)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (pulling) {
            pulling = false
            fireSling(now)
            return
        }
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
        if (weapon.drop) {
            dropBoulder(tx, ty)
            return
        }
        val hit = newHit(tx, ty, weapon)
        if (weapon.thrown) {
            val sx = width / 2f
            val sy = handY()
            projectiles += Projectile(hit, sx, sy, min(220f * d, hypot(tx - sx, ty - sy) * 0.35f), weapon.duration, 1f, 0.28f)
            sfx?.throwSound()
        } else {
            swing = Swing(hit)
            sfx?.swing()
        }
    }

    /** Vuruşu oluşturur; nesne kesiti alet havadayken arka planda hazırlanır. */
    private fun newHit(x: Float, y: Float, w: Weapon, chain: Int = 0, visited: Set<Target> = emptySet(), aimed: Target? = null): PendingHit {
        val hit = PendingHit(x, y, w, chain, visited, aimed)
        segmentAt?.invoke(x, y) { cut -> hit.cut = cut; hit.done = true } ?: run { hit.done = true }
        return hit
    }

    private fun handY() = height - 150 * d

    // ---------- Sapan ----------

    private fun slingRest() = floatArrayOf(width / 2f, height - 175 * d)

    /** Çekme vektörünün tersi yönünde, çekme miktarıyla orantılı uzaklıkta hedef noktası. */
    private fun slingAim(): FloatArray? {
        val len = hypot(pullX, pullY)
        if (len < 25 * d || pullY < 10 * d) return null
        val (rx, ry) = slingRest().let { it[0] to it[1] }
        val reach = ry - (insetTop + 90 * d)              // tam çekişte ekranın üstüne kadar
        val gain = reach / (MAX_PULL * d * 0.85f)
        val tx = (rx - pullX * gain).coerceIn(0f, width.toFloat())
        val ty = (ry - pullY * gain).coerceIn(insetTop + 40 * d, ry)
        return floatArrayOf(tx, ty, len / (MAX_PULL * d))
    }

    private fun fireSling(now: Long) {
        val aim = slingAim()
        val (rx, ry) = slingRest().let { it[0] to it[1] }
        val sx = rx + pullX
        val sy = ry + pullY
        pullX = 0f; pullY = 0f
        if (aim == null || now - lastThrow < RELOAD_MS) return
        lastThrow = now
        shots++
        val power = aim[2].coerceIn(0.2f, 1f)
        val hit = newHit(aim[0], aim[1], Weapon.SLING)
        projectiles += Projectile(hit, sx, sy, (20 + 50 * (1 - power)) * d, 0.5f - 0.22f * power, 1f, 0.45f)
        sfx?.sling()
    }

    // ---------- Çarpışma ----------

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
        if (w == Weapon.MOLOTOV) { igniteAt(h); return }
        if (!w.thrown) sfx?.melee(w)

        if (inRubble(h.x, h.y, now)) {
            burst(h.x, h.y, 10, intArrayOf(0xFFBFF3FF.toInt(), 0xFF9AA3AA.toInt()), 140f)
            if (w.thrown) sfx?.miss()
            ricochet(h, null)
            return
        }
        val target = h.aimed?.takeIf { !it.isBroken(now) } ?: targets
            .filter { !it.isBroken(now) && h.x > it.box.left - pad && h.x < it.box.right + pad &&
                h.y > it.box.top - pad && h.y < it.box.bottom + pad }
            .minByOrNull { it.box.width() * it.box.height() } // en küçüğü, büyük ihtimalle öndeki
        // Kesit hedefle uyuşmuyorsa (başka bir nesneyi seçtiyse) hedefin kendi kesiti/kutusu kullanılır
        val cut = h.cut?.takeIf { c -> target == null || iou(c.box, target.box) > 0.1f || target.box.contains(c.box.centerX(), c.box.centerY()) }

        if (target != null) {
            target.hp -= w.damage
            if (target.hp > 0) {
                target.cracks += makeCracks((h.x - target.box.left) / target.box.width(), (h.y - target.box.top) / target.box.height(), 6)
                sfx?.hit()
                burst(h.x, h.y, 10, intArrayOf(Color.WHITE, 0xFFCFEFFF.toInt()), 160f)
                shake = max(shake, if (w.thrown) 4f else 8f)
                popup(h.x, h.y - 20 * d, "Çatladı!", 0xFFCFEFFF.toInt())
            } else {
                breakObject(cut ?: target.cut?.takeIf { iou(it.box, target.box) > 0.3f } ?: fallbackCut(target.box),
                    target.info, target.info.label, target, h)
            }
            ricochet(h, target)
            return
        }
        if (cut != null) {
            val cls = clsFor(cut.box)
            val info = cls?.let { BREAKABLES[it] } ?: GENERIC_OBJECT
            val name = cls?.let { labelOf(it).replaceFirstChar { c -> c.titlecase(tr) } } ?: "Nesne"
            breakObject(cut, info, name, null, h)
            ricochet(h, null)
            return
        }
        // Iska: duvar, zemin ya da boşluk
        if (w.thrown) sfx?.miss()
        burst(h.x, h.y, 8, intArrayOf(0xFFC9B8A0.toInt(), 0xFF8D7D68.toInt()), 120f)
        shake = max(shake, if (w.thrown) 0f else 5f)
        ricochet(h, null)
    }

    /**
     * Top, çarptığı yerden henüz kırılmamış en yakın seçili nesneye seker. Seçili nesne kalmadıysa
     * yere düşer. Diğer aletler sekmez.
     */
    private fun ricochet(h: PendingHit, hitTarget: Target?) {
        if (h.weapon != Weapon.BALL) return
        val now = SystemClock.uptimeMillis()
        val visited = if (hitTarget != null) h.visited + hitTarget else h.visited
        val next = if (h.chain >= MAX_BOUNCES) null else targets
            .filter { it.selected && !it.isBroken(now) && it !in visited }
            .minByOrNull { hypot(it.box.centerX() - h.x, it.box.centerY() - h.y) }
        val r = Weapon.BALL.radiusDp * d * 0.28f
        if (next == null) {
            if (h.chain > 0 || hitTarget != null) sfx?.bounce()
            drops += DropBall(h.x, h.y, (Random.nextFloat() - 0.5f) * 160f * d, -220f * d, r)
            return
        }
        val tx = next.box.centerX() + (Random.nextFloat() - 0.5f) * next.box.width() * 0.3f
        val ty = next.box.centerY() + (Random.nextFloat() - 0.5f) * next.box.height() * 0.3f
        val hop = newHit(tx, ty, Weapon.BALL, h.chain + 1, visited, next)
        val dist = hypot(tx - h.x, ty - h.y)
        projectiles += Projectile(hop, h.x, h.y, min(140f * d, dist * 0.35f), (0.22f + dist / (2200f * d)).coerceAtMost(0.45f), 0.28f, 0.28f)
        sfx?.bounce()
        popup(h.x, h.y - 50 * d, "Sekme! ×${h.chain + 2}", 0xFF8EF0FF.toInt())
    }

    private fun fallbackCut(box: RectF): Cutout? {
        val frame = frameProvider?.invoke() ?: return null
        val c = snapshot(frame, box)
        frame.recycle()
        return c
    }

    private fun breakObject(cut: Cutout?, info: Breakable, name: String, target: Target?, h: PendingHit, verb: String = "kırıldı!") {
        val now = SystemClock.uptimeMillis()
        val electronic = info.material == Material.ELECTRONIC || info.material == Material.VEHICLE
        val box = RectF(target?.box ?: cut?.box ?: RectF(h.x - 40 * d, h.y - 40 * d, h.x + 40 * d, h.y + 40 * d))
        if (cut != null) {
            val glint = when (info.material) {
                Material.GLASS -> 1f
                Material.CERAMIC -> 0.4f
                Material.VEHICLE -> 0.3f
                Material.ELECTRONIC -> 0.15f
            }
            shards += makeShards(cut, h.x, h.y, h.weapon.power, d, glint, WRECK_MS - 1000)
        }
        val wreck = Wreck(
            now, now + WRECK_MS, cut?.bg ?: Color.DKGRAY,
            if (electronic) Color.argb(230, 40, 40, 45) else Color.argb(140, 200, 235, 245),
            box, cut?.patch, cut?.patchBox,
        )
        if (target != null) {
            target.wreck = wreck
            target.cracks.clear()
            target.hp = info.hp
            target.selected = false
            if (target.pinned) postDelayed({ targets.remove(target) }, WRECK_MS)
        } else {
            looseWrecks += wreck
        }
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
        val points = info.points * (1 + h.chain)
        score += points
        broken++
        popup(h.x, h.y - 30 * d, "$name $verb +$points", 0xFFFFD23F.toInt())
    }

    // ---------- Molotof ve kaya ----------

    /** Noktadaki (dolgu payıyla) en küçük, kırılmamış hedef. */
    private fun findTarget(x: Float, y: Float, pad: Float, now: Long): Target? = targets
        .filter { !it.isBroken(now) && x > it.box.left - pad && x < it.box.right + pad && y > it.box.top - pad && y < it.box.bottom + pad }
        .minByOrNull { it.box.width() * it.box.height() }

    private fun cutFor(h: PendingHit, target: Target?): Cutout? {
        val c = h.cut?.takeIf { c -> target == null || iou(c.box, target.box) > 0.1f || target.box.contains(c.box.centerX(), c.box.centerY()) }
        if (c != null || target == null) return c
        return target.cut?.takeIf { iou(it.box, target.box) > 0.3f } ?: fallbackCut(target.box)
    }

    private fun nameFor(cut: Cutout?): Pair<Breakable, String> {
        val cls = cut?.let { clsFor(it.box) }
        val info = cls?.let { BREAKABLES[it] } ?: GENERIC_OBJECT
        val name = cls?.let { labelOf(it).replaceFirstChar { c -> c.titlecase(tr) } } ?: "Nesne"
        return info to name
    }

    /** Şişe kırılır, isabet edilen nesne tutuşur; bir süre yanıp patlar. Boşlukta yerde yanar. */
    private fun igniteAt(h: PendingHit) {
        val now = SystemClock.uptimeMillis()
        val target = h.aimed ?: findTarget(h.x, h.y, h.weapon.hitPadDp * d, now)
        val cut = cutFor(h, target)
        val box = target?.box?.let { RectF(it) } ?: cut?.box?.let { RectF(it) }
        val obj = box != null
        val fire = Fire(h.x, h.y, target, cut, box, now, if (obj) 2200L else 0L, now + if (obj) 7200L else 3500L)
        fire.loopStream = sfx?.fireLoop() ?: 0
        fires += fire
        sfx?.shatter(Material.GLASS)
        sfx?.ignite()
        burst(h.x, h.y, 18, intArrayOf(0xFFBFE8C0.toInt(), 0xFFFFB347.toInt(), 0xFFFFE08A.toInt()), 260f)
        repeat(14) { spawnFlame(h.x + (Random.nextFloat() - 0.5f) * 40 * d, h.y, 1.6f) }
        shake = max(shake, 8f)
        popup(h.x, h.y - 30 * d, if (obj) "Tutuştu! 🔥" else "Yerde yanıyor 🔥", 0xFFFFA040.toInt())
    }

    private fun spawnFlame(x: Float, y: Float, scale: Float) {
        fireParticles += FireParticle(
            x, y, (Random.nextFloat() - 0.5f) * 30 * d, -(60 + Random.nextFloat() * 90) * d,
            0.45f + Random.nextFloat() * 0.45f, (12 + Random.nextFloat() * 14) * d * scale, 0,
        )
    }

    private fun spawnSmoke(x: Float, y: Float, scale: Float, dark: Boolean = false) {
        fireParticles += FireParticle(
            x, y, (Random.nextFloat() - 0.5f) * 24 * d, -(30 + Random.nextFloat() * 40) * d * if (dark) 1.4f else 1f,
            (1.6f + Random.nextFloat() * 0.9f) * if (dark) 1.5f else 1f, (16 + Random.nextFloat() * 18) * d * scale, if (dark) 3 else 1,
        )
    }

    private fun spawnEmber(x: Float, y: Float, speed: Float) {
        val a = Random.nextFloat() * 2f * PI.toFloat()
        val v = (0.3f + Random.nextFloat()) * speed * d
        fireParticles += FireParticle(x, y, cos(a) * v, sin(a) * v - speed * d * 0.5f, 0.6f + Random.nextFloat() * 0.8f, (1.2f + Random.nextFloat() * 1.8f) * d, 2)
    }

    /** Yanan nesnenin şu anki kutusu (takip ediliyorsa hedefle birlikte kayar). */
    private fun fireBox(f: Fire): RectF? {
        val b = f.box ?: return null
        val t = f.target ?: return b
        val dx = t.box.left - b.left
        val dy = t.box.top - b.top
        return RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)
    }

    private fun updateFires(dt: Float, now: Long) {
        val iter = fires.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            val el = (now - f.start).toFloat()
            val dst = fireBox(f)
            val intensity = when {
                f.box == null -> 0.7f * (1f - el / (f.until - f.start))
                !f.exploded -> 0.45f + 0.55f * (el / f.igniteMs).coerceAtMost(1f)
                else -> 1f - 0.85f * ((el - f.igniteMs) / (f.until - f.start - f.igniteMs)).coerceIn(0f, 1f)
            }
            val scale = dst?.let { (it.width() / (140 * d)).coerceIn(0.7f, 2.2f) } ?: 1f
            f.intensity = intensity
            f.emit += dt * 55f * intensity
            while (f.emit >= 1f) {
                f.emit -= 1f
                val p = if (dst != null) {
                    f.cut?.let { c -> if (!f.exploded) randomOpaquePoint(c, dst) else null }
                        ?: floatArrayOf(dst.left + Random.nextFloat() * dst.width(), dst.top + dst.height() * (0.35f + 0.65f * Random.nextFloat()))
                } else floatArrayOf(f.x + (Random.nextFloat() - 0.5f) * 60 * d, f.y + (Random.nextFloat() - 0.5f) * 12 * d)
                spawnFlame(p[0], p[1], scale)
                if (f.exploded) {
                    // Patlamadan sonra kalın, kara duman sütunu
                    if (Random.nextFloat() < 0.55f) spawnSmoke(p[0], p[1] - 30 * d * scale, scale * 1.5f, dark = true)
                } else if (Random.nextFloat() < 0.3f) spawnSmoke(p[0], p[1] - 20 * d * scale, scale)
                if (Random.nextFloat() < 0.12f) spawnEmber(p[0], p[1], 120f)
            }
            if (!f.exploded && dst != null && el >= f.igniteMs) explode(f, dst)
            if (now >= f.until) {
                sfx?.stop(f.loopStream)
                iter.remove()
            }
        }
    }

    /** Yanan nesne patlar: alev topu, duman, kıvılcımlar; nesne kırılır ve yanık enkaz kalır. */
    private fun explode(f: Fire, dst: RectF) {
        f.exploded = true
        val now = SystemClock.uptimeMillis()
        val cx = dst.centerX()
        val cy = dst.centerY()
        val h = PendingHit(cx, cy, Weapon.MOLOTOV).also { it.done = true }
        val t = f.target
        if (t != null && !t.isBroken(now)) {
            breakObject(f.cut, t.info, t.info.label, t, h, "patladı! 💥")
        } else if (t == null && f.cut != null) {
            val (info, name) = nameFor(f.cut)
            breakObject(f.cut, info, name, null, h, "patladı! 💥")
        }
        val sc = (dst.width() / (140 * d)).coerceIn(0.8f, 2.5f)
        repeat(45) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val v = (60 + Random.nextFloat() * 260) * d * sc
            fireParticles += FireParticle(cx, cy, cos(a) * v, sin(a) * v - 80 * d, 0.5f + Random.nextFloat() * 0.5f, (18 + Random.nextFloat() * 22) * d * sc, 0)
        }
        repeat(25) { spawnSmoke(cx + (Random.nextFloat() - 0.5f) * dst.width(), cy, sc * 1.4f) }
        repeat(40) { spawnEmber(cx, cy, 420f) }
        val size = max(dst.width(), dst.height())
        glows += Glow(cx, cy, size * 3.5f, now, 900, 0xFFFFC070.toInt(), 0.9f)
        rings += Ring(cx, cy, size * 2.2f, now, 450, Color.WHITE)
        scorches += Scorch(cx, dst.bottom, dst.width() * 0.75f, dst.height() * 0.16f + 10 * d, f.until + 3000, 0)
        repeat(18) { spawnSmoke(cx + (Random.nextFloat() - 0.5f) * dst.width(), cy - dst.height() * 0.3f, sc * 1.6f, dark = true) }
        punch = 0.06f
        sfx?.explosion()
        haptics?.invoke(Material.VEHICLE)
        shake = 28f
        flash = 0.5f
    }

    /** Dokunulan noktaya gökten dev bir kaya düşer. */
    private fun dropBoulder(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        lastThrow = now
        shots++
        val target = findTarget(x, y, Weapon.BOULDER.hitPadDp * d, now)
        val r = ((target?.box?.width() ?: 0f) * 0.5f).coerceIn(70 * d, 170 * d)
        val b = Boulder(x, y, r, Weapon.BOULDER.duration)
        b.restY = if (target != null) target.box.bottom - target.box.height() * 0.32f - r * 0.55f else y - r * 0.35f
        falling += b to newHit(x, y, Weapon.BOULDER, aimed = target)
        sfx?.fall()
    }

    private fun land(b: Boulder, h: PendingHit) {
        val now = SystemClock.uptimeMillis()
        b.landed = true
        b.until = now + WRECK_MS
        val target = h.aimed?.takeIf { !it.isBroken(now) } ?: findTarget(h.x, h.y, h.weapon.hitPadDp * d, now)
        val cut = cutFor(h, target)
        sfx?.impact()
        haptics?.invoke(Material.VEHICLE)
        shake = 32f
        flash = 0.2f
        val groundY = b.restY + b.r * 0.6f
        rings += Ring(b.x, groundY, b.r * 3f, now, 550, 0xFFC9B8A0.toInt())
        scorches += Scorch(b.x, groundY, b.r * 1.5f, b.r * 0.35f, b.until, 1)
        punch = 0.045f
        burst(b.x, groundY, 50, intArrayOf(0xFF9C8B74.toInt(), 0xFF6E6457.toInt(), 0xFFC9B8A0.toInt()), 420f)
        repeat(22) { spawnSmoke(b.x + (Random.nextFloat() - 0.5f) * b.r * 2.4f, groundY, 1.6f) }
        if (target != null) {
            b.target = target
            b.anchor = RectF(target.box)
            b.crushed = cut
            b.crushBox = RectF(target.box)
            breakObject(cut, target.info, target.info.label, target, h, "ezildi!")
        } else if (cut != null) {
            b.crushed = cut
            b.crushBox = RectF(cut.box)
            val (info, name) = nameFor(cut)
            breakObject(cut, info, name, null, h, "ezildi!")
        } else {
            popup(b.x, groundY - b.r, "Gümm!", 0xFFE0D2B8.toInt())
        }
        rocks += b
    }

    /** Kaya hedefle birlikte kayar. */
    private fun rockOffset(b: Boulder): FloatArray {
        val t = b.target ?: return floatArrayOf(0f, 0f)
        val a = b.anchor ?: return floatArrayOf(0f, 0f)
        return floatArrayOf(t.box.left - a.left, t.box.top - a.top)
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
        if (basketMode) {
            if (now - lastCourse > 300) refreshBasket()
            basket.update(dt, width, height)
        }
        val pit = projectiles.iterator()
        val landed = ArrayList<PendingHit>()
        while (pit.hasNext()) {
            val p = pit.next()
            p.t = min(1f, p.t + dt / p.dur)
            p.spin += dt * 12f
            if (p.t >= 1f && p.hit.ready(now)) { pit.remove(); landed += p.hit }
        }
        landed.forEach { resolve(it) }

        val fit = falling.iterator()
        val toLand = ArrayList<Pair<Boulder, PendingHit>>()
        while (fit.hasNext()) {
            val fb = fit.next()
            fb.first.t = min(1f, fb.first.t + dt / fb.first.fallSec)
            if (fb.first.t >= 1f && fb.second.ready(now)) { fit.remove(); toLand += fb }
        }
        toLand.forEach { land(it.first, it.second) }
        rocks.removeAll { now >= it.until }
        glows.removeAll { now - it.start > it.dur }
        rings.removeAll { now - it.start > it.dur }
        scorches.removeAll { now >= it.until }
        punch *= kotlin.math.exp(-10f * dt)
        updateFires(dt, now)
        fireParticles.removeAll { p ->
            p.age += dt
            if (p.kind == 2) p.vy += 500f * d * dt else { p.vx *= 0.98f; p.vy *= 0.985f }
            p.x += p.vx * dt; p.y += p.vy * dt
            p.age >= p.life
        }
        if (fireParticles.size > 900) fireParticles.subList(0, fireParticles.size - 900).clear()

        swing?.let { s ->
            s.t = min(1f, s.t + dt / s.hit.weapon.duration)
            if (!s.impacted && s.t >= 0.5f && s.hit.ready(now)) {
                s.impacted = true
                resolve(s.hit)
            }
            if (s.t >= 1f && s.impacted) swing = null
        }

        val floor = height - 70 * d
        drops.removeAll { b ->
            b.age += dt; b.vy += 1600f * d * dt; b.x += b.vx * dt; b.y += b.vy * dt; b.spin += b.vx / max(1f, b.r) * dt
            if (b.y > floor && b.vy > 0) { b.y = floor; b.vy *= -0.45f; b.vx *= 0.7f; b.bounces++ }
            b.age > 1.6f || b.bounces > 3
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
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFD23F.toInt() }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD23F.toInt() }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
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
    private val woodStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF8B5A2B.toInt()
    }
    private val woodLight = Paint(woodStroke).apply { color = 0xFFC48A52.toInt() }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFFC0392B.toInt() }
    private val pouchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A3426.toInt() }
    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val flashPaint = Paint()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.argb(45, 255, 255, 255)
    }
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scorchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crackPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /** Alevler ve patlamalar çevreyi turuncu ışıkla aydınlatır (toplamalı, titrek). */
    private fun drawLights(c: Canvas, now: Long) {
        for (f in fires) {
            if (f.intensity <= 0.02f) continue
            val dst = fireBox(f)
            val cx = dst?.centerX() ?: f.x
            val cy = dst?.let { it.top + it.height() * 0.6f } ?: f.y
            val r = (dst?.let { max(it.width(), it.height()) * 1.7f } ?: (130 * d)) * (0.92f + 0.08f * sin(now / 70f + f.x))
            val flick = 0.8f + 0.2f * sin(now / 53f) * sin(now / 97f + 1.3f)
            val a = (110 * f.intensity * flick).toInt().coerceIn(0, 255)
            lightPaint.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(a, 255, 150, 50), Color.argb(a / 3, 255, 90, 20), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, lightPaint)
        }
        for (g in glows) {
            val k = (now - g.start).toFloat() / g.dur
            val a = (255 * g.peak * (1f - k).pow(2)).toInt().coerceIn(0, 255)
            val r = g.r * (0.6f + 0.4f * k)
            lightPaint.shader = RadialGradient(g.x, g.y, r,
                intArrayOf(Color.argb(a, Color.red(g.color), Color.green(g.color), Color.blue(g.color)), Color.argb(a / 4, 255, 80, 20), 0),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(g.x, g.y, r, lightPaint)
        }
        for (rg in rings) {
            val k = (now - rg.start).toFloat() / rg.dur
            ringPaint.color = rg.color
            ringPaint.alpha = (200 * (1f - k)).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = (14 * (1f - k) + 1) * d
            c.drawOval(rg.x - rg.maxR * k, rg.y - rg.maxR * k * 0.45f, rg.x + rg.maxR * k, rg.y + rg.maxR * k * 0.45f, ringPaint)
        }
    }

    /** Zemindeki yanık lekesi ya da kayanın açtığı çatlaklı krater. */
    private fun drawScorch(c: Canvas, s: Scorch, now: Long) {
        val a = ((s.until - now) / 1500f).coerceIn(0f, 1f)
        val col = if (s.kind == 0) Color.rgb(20, 12, 6) else Color.rgb(40, 34, 28)
        scorchPaint.shader = RadialGradient(s.x, s.y, max(s.rx, s.ry),
            intArrayOf(Color.argb((170 * a).toInt(), Color.red(col), Color.green(col), Color.blue(col)), Color.argb((90 * a).toInt(), Color.red(col), Color.green(col), Color.blue(col)), 0),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.save()
        c.scale(1f, s.ry / s.rx, s.x, s.y)
        c.drawCircle(s.x, s.y, s.rx, scorchPaint)
        c.restore()
        if (s.kind == 1) {
            crackPaint2.color = Color.argb((150 * a).toInt(), 25, 20, 15)
            crackPaint2.strokeWidth = 2 * d
            for (i in 0 until 8) {
                val ang = (i + s.cracks[i] * 0.6f) / 8f * 2f * PI.toFloat()
                val len = s.rx * (0.9f + s.cracks[i + 8] * 0.8f)
                val mx = s.x + cos(ang) * len * 0.55f
                val my = s.y + sin(ang) * len * 0.55f * (s.ry / s.rx) + (s.cracks[i] - 0.5f) * 6 * d
                c.drawLine(s.x + cos(ang) * s.rx * 0.5f, s.y + sin(ang) * s.ry * 0.5f, mx, my, crackPaint2)
                c.drawLine(mx, my, s.x + cos(ang + 0.15f) * len, s.y + sin(ang + 0.15f) * len * (s.ry / s.rx), crackPaint2)
            }
        }
    }
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

        if (punch > 0.002f) {
            c.scale(1f + punch, 1f + punch, width / 2f, height / 2f)
            shakeTarget?.scaleX = 1f + punch
            shakeTarget?.scaleY = 1f + punch
        } else if (shakeTarget?.scaleX != 1f) {
            shakeTarget?.scaleX = 1f
            shakeTarget?.scaleY = 1f
        }

        // Zemin izleri: yanık ve krater
        for (sc in scorches) drawScorch(c, sc, now)
        // Önce izler (nesnenin yerini örten doldurma), sonra hedef işaretleri
        for (w in looseWrecks) drawWreck(c, w, w.box, now, d)
        var order = 0
        for (t in targets) {
            val w = t.wreck
            if (w != null && now < w.until) { drawWreck(c, w, t.box, now, d); continue }
            if (t.cracks.isNotEmpty()) drawCracks(c, t.box, t.cracks, d)
            if (basketMode) continue
            if (t.selected) drawSelected(c, t, ++order, now)
            else if (showBoxes) drawMarker(c, t, now)
        }

        // Yanan nesneler kararır; patlama sonrası yanık izi
        for (f in fires) {
            val dst = fireBox(f) ?: continue
            val cut = f.cut
            if (!f.exploded && cut != null) {
                drawCharred(c, cut, dst, ((now - f.start).toFloat() / f.igniteMs).coerceIn(0f, 1f) * 0.85f)
            }
        }
        // Kayalar: altında yassılmış nesne, üstünde kaya
        for (b in rocks) {
            val off = rockOffset(b)
            val a = (255 * min(1f, (b.until - now) / 800f)).toInt().coerceIn(0, 255)
            val cb = b.crushBox
            val cr = b.crushed
            if (cr != null && cb != null) {
                drawCrushed(c, cr, RectF(cb.left + off[0], cb.top + off[1], cb.right + off[0], cb.bottom + off[1]), a)
            }
            drawRock(c, b.x + off[0], b.restY + off[1], b.r, b.rot, b.shape, a, d)
        }

        drawShards(c, shards, d)
        drawFireParticles(c, fireParticles, flameSprite, smokeSprite, darkSmokeSprite)
        drawLights(c, now)
        // Düşen kayalar: hedefte büyüyen gölge, hız çizgileri
        for ((b, _) in falling) {
            val e = b.t * b.t
            val y = -b.r * 1.5f + (b.restY + b.r * 1.5f) * e
            shadowPaint.alpha = (140 * b.t).toInt()
            tmpRect.set(b.x - b.r * 0.9f * b.t, b.restY + b.r * 0.5f, b.x + b.r * 0.9f * b.t, b.restY + b.r * 0.75f)
            c.drawOval(tmpRect, shadowPaint)
            shadowPaint.alpha = 64
            markerPaint.color = Color.WHITE
            markerPaint.alpha = 90
            markerPaint.strokeWidth = 2 * d
            for (k in -1..1) c.drawLine(b.x + k * b.r * 0.4f, y - b.r * 1.1f, b.x + k * b.r * 0.4f, y - b.r * 2.2f, markerPaint)
            drawRock(c, b.x, y, b.r, b.rot + b.t * 40f, b.shape, 255, d)
        }

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
            val r = p.hit.weapon.radiusDp * d * (p.s0 + (p.s1 - p.s0) * e)
            // Yüzeydeki gölge (yay olmadan düz yol) ve hareket izi
            val gx = p.sx + (p.hit.x - p.sx) * e
            val gy = p.sy + (p.hit.y - p.sy) * e + r * 0.9f
            shadowPaint.alpha = (25 + 55 * e).toInt()
            tmpRect.set(gx - r * 1.1f, gy - r * 0.3f, gx + r * 1.1f, gy + r * 0.3f)
            c.drawOval(tmpRect, shadowPaint)
            shadowPaint.alpha = 64
            val pe = 1f - (1f - max(0f, p.t - 0.06f)).pow(1.6f)
            val px0 = p.sx + (p.hit.x - p.sx) * pe
            val py0 = p.sy + (p.hit.y - p.sy) * pe - p.arc * 4f * pe * (1f - pe)
            trailPaint.strokeWidth = r * 1.1f
            c.drawLine(px0, py0, x, y, trailPaint)
            drawAmmo(c, p.hit.weapon, x, y, r, p.spin)
        }
        for (b in drops) {
            ammoPaint.alpha = 255
            drawAmmo(c, Weapon.BALL, b.x, b.y, b.r, b.spin)
        }

        drawing?.let {
            selectPaint.strokeWidth = 2.5f * d
            selectPaint.pathEffect = DashPathEffect(floatArrayOf(10 * d, 7 * d), 0f)
            c.drawRect(it, selectPaint)
        }
        if (basketMode) basket.draw(c, width, height)
        else if (!marking) drawHand(c, now)
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

    /** Seçili nesne: silüeti sarı parlar, köşesinde sıra numarası. */
    private fun drawSelected(c: Canvas, t: Target, n: Int, now: Long) {
        val pulse = 0.5f + 0.5f * sin(now / 180f)
        val cut = t.cut
        if (cut != null) {
            selectionPaint.alpha = (60 + 80 * pulse).toInt()
            c.drawBitmap(cut.obj, null, t.box, selectionPaint)
        }
        markerPaint.color = 0xFFFFD23F.toInt()
        markerPaint.alpha = (150 + 100 * pulse).toInt()
        markerPaint.strokeWidth = 2.5f * d
        c.drawRoundRect(t.box, 8 * d, 8 * d, markerPaint)
        val r = 11 * d
        val bx = t.box.left + r * 0.6f
        val by = max(insetTop + r, t.box.top + r * 0.6f)
        c.drawCircle(bx, by, r, badgeFill)
        badgeText.textSize = 13 * d
        c.drawText(n.toString(), bx, by + 4.5f * d, badgeText)
        labelText.textSize = 12 * d
        val label = if (t.info.hp > 1) t.info.label + " " + "●".repeat(max(0, t.hp)) else t.info.label
        c.drawRect(bx + r + 2 * d, by - 9 * d, bx + r + 10 * d + labelText.measureText(label), by + 9 * d, labelBg)
        c.drawText(label, bx + r + 6 * d, by + 4 * d, labelText)
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
        if (type == Weapon.MOLOTOV) {
            drawMolotov(c, x, y, r * 0.75f, Math.toDegrees(spin.toDouble()).toFloat(), flameSprite, SystemClock.uptimeMillis())
            return
        }
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

    /** FPS tarzı: elde bekleyen top/taş, sapan ya da sağ alttan uzanan sopa/anahtar. */
    private fun drawHand(c: Canvas, now: Long) {
        when {
            weapon == Weapon.SLING -> drawSling(c, now)
            weapon.drop -> {
                // Kaya elde değil, gökte: köşede küçük bir simge ve nişan ipucu
                val ready = now - lastThrow > RELOAD_MS
                drawRock(c, width - 60 * d, height - 150 * d, 34 * d, 15f, iconRock, if (ready) 255 else 90, d)
            }
            weapon == Weapon.MOLOTOV -> {
                val ready = now - lastThrow > RELOAD_MS
                val bob = sin(now / 400f) * 3 * d
                drawMolotov(c, width / 2f, handY() + bob + if (ready) 0f else 80 * d, 30 * d, -12f, flameSprite, now)
            }
            weapon.thrown -> {
                val ready = now - lastThrow > RELOAD_MS
                val r = weapon.radiusDp * d * 1.3f
                val bob = sin(now / 400f) * 3 * d
                val y = handY() + bob + if (ready) 0f else 60 * d
                tmpRect.set(width / 2f - r, handY() + r * 0.8f, width / 2f + r, handY() + r * 1.2f)
                c.drawOval(tmpRect, shadowPaint)
                drawAmmo(c, weapon, width / 2f, y, r, 0.3f)
            }
            else -> drawMelee(c, now)
        }
        if (pointerDown && !pulling) {
            markerPaint.color = Color.WHITE
            markerPaint.alpha = 180
            markerPaint.strokeWidth = 2 * d
            c.drawCircle(downX, downY, 16 * d, markerPaint)
        }
    }

    /** Y biçimli tahta sapan; lastik çekilince torba parmağı izler, nişan yolu noktalarla gösterilir. */
    private fun drawSling(c: Canvas, now: Long) {
        val (rx, ry) = slingRest().let { it[0] to it[1] }
        val tipDx = 40 * d
        val tipY = ry - 6 * d
        val forkY = ry + 60 * d
        // Gövde ve çatal
        woodStroke.strokeWidth = 16 * d
        woodLight.strokeWidth = 5 * d
        tmpPath.reset()
        tmpPath.moveTo(rx, height + 20 * d)
        tmpPath.lineTo(rx, forkY)
        tmpPath.moveTo(rx, forkY + 4 * d)
        tmpPath.quadTo(rx - tipDx * 0.9f, forkY - 10 * d, rx - tipDx, tipY)
        tmpPath.moveTo(rx, forkY + 4 * d)
        tmpPath.quadTo(rx + tipDx * 0.9f, forkY - 10 * d, rx + tipDx, tipY)
        c.drawPath(tmpPath, woodStroke)
        c.drawPath(tmpPath, woodLight)

        val ready = now - lastThrow > RELOAD_MS
        val px = rx + pullX
        val py = ry + pullY + if (pulling) 0f else 6 * d
        val stretch = hypot(pullX, pullY) / (MAX_PULL * d)
        bandPaint.strokeWidth = (5 - 2.5f * stretch) * d
        // Arka lastik, torba ve taş, ön lastik
        c.drawLine(rx + tipDx, tipY, px, py, bandPaint)
        tmpRect.set(px - 14 * d, py - 7 * d, px + 14 * d, py + 7 * d)
        c.drawRoundRect(tmpRect, 6 * d, 6 * d, pouchPaint)
        if (ready) drawAmmo(c, Weapon.SLING, px, py - 4 * d, 11 * d, 0.4f)
        c.drawLine(rx - tipDx, tipY, px, py, bandPaint)

        if (pulling) {
            val aim = slingAim() ?: return
            val power = aim[2].coerceIn(0.2f, 1f)
            val arc = (20 + 50 * (1 - power)) * d
            aimPaint.alpha = 220
            for (i in 1..14) {
                val tt = i / 15f
                val e = 1f - (1f - tt).pow(1.6f)
                val x = px + (aim[0] - px) * e
                val y = py + (aim[1] - py) * e - arc * 4f * e * (1f - e)
                aimPaint.alpha = (230 * (1f - tt * 0.6f)).toInt()
                c.drawCircle(x, y, (3.5f - 2f * tt) * d, aimPaint)
            }
            markerPaint.color = Color.WHITE
            markerPaint.alpha = 200
            markerPaint.strokeWidth = 2 * d
            c.drawCircle(aim[0], aim[1], 12 * d, markerPaint)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun drawMelee(c: Canvas, now: Long) {
        val px = width * 0.8f
        val py = height + 20 * d
        val rest = -18f + sin(now / 500f) * 2f
        val s = swing
        val angle: Float
        if (s != null && s.hit.weapon == weapon) {
            // 0 = dik yukarı, pozitif = sağa. Geriye kaldır → hedefe doğru savur → geri dön
            val target = Math.toDegrees(atan2((s.hit.x - px).toDouble(), (py - s.hit.y).toDouble())).toFloat()
            val windup = rest + 35f
            val follow = target - 30f
            var ghosts = false
            angle = when {
                s.t < 0.3f -> lerp(rest, windup, s.t / 0.3f)
                s.t < 0.55f -> { ghosts = true; lerp(windup, follow, ((s.t - 0.3f) / 0.25f).pow(0.7f)) }
                else -> lerp(follow, rest, (s.t - 0.55f) / 0.45f)
            }
            if (ghosts) for ((back, alpha) in listOf(12f to 50, 24f to 25)) drawWeaponAt(c, px, py, angle + back, alpha)
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
        val stats = if (basketMode) listOf("Puan" to score, "Basket" to basket.baskets, "Atış" to basket.shots)
        else listOf("Puan" to score, "Kırılan" to broken, "Vuruş" to shots)
        for ((label, value) in stats) {
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
        const val MAX_PULL = 170f // dp
        const val IDLE_STATUS = "Neye vurursan kırılır · 🎯 Seç: hedefleri işaretle · 🔍 Tara: eşyaları bul"
    }
}
