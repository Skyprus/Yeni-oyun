package com.skyprus.kirgec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import android.view.View
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Topun çarpıp sektiği oda eşyası. [bounce]: sekme katsayısı (yumuşak eşya topu yutar, sert eşya fırlatır). */
private class Obstacle(val name: String, val box: RectF, val bounce: Float, val kind: Int) {
    var cut: Cutout? = null
    var mask: IntArray? = null
    var mw = 0
    var mh = 0
    var flash = 0f
}

private class Ball(var x: Float, var y: Float) {
    var vx = 0f
    var vy = 0f
    val speed get() = hypot(vx, vy)
}

private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, val life: Float) {
    var age = 0f
}

private class Note(val x: Float, var y: Float, val text: String, val color: Int) {
    var age = 0f
}

private const val KIND_SOFT = 0
private const val KIND_NORMAL = 1
private const val KIND_HARD = 2

/** Yumuşak eşyalar topu yutar, sert eşyalar güçlü sektirir. */
private val SOFT_CLASSES = setOf(
    "couch", "bed", "teddy bear", "backpack", "handbag", "suitcase", "person", "dog", "cat", "potted plant", "umbrella",
)
private val HARD_CLASSES = setOf(
    "tv", "laptop", "refrigerator", "oven", "microwave", "sink", "toilet", "bottle", "wine glass", "cup", "vase",
    "bowl", "clock", "cell phone", "keyboard", "toaster", "car", "truck", "bus", "motorcycle", "bicycle", "scissors",
)

/**
 * Mini golf modu: kamera karesi dondurulur, odadaki eşyalar bulunur ve engele dönüşür.
 * Top, eşyaların gerçek silüetlerinden ve ekran kenarlarından (odanın duvarları) seker;
 * amaç onu en az vuruşla deliğe sokmak. Delik genellikle bir eşyanın arkasına konur.
 */
class HoleView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val tr = Locale("tr")

    /** Ekranda görünen canlı kamera karesi (ekran pikselleri). */
    var frameProvider: (() -> Bitmap?)? = null
    /** Karedeki eşyaları bulur; sonuç ana iş parçacığında, ekran koordinatıyla gelir. */
    var scanRoom: ((Bitmap, (List<Det>) -> Unit) -> Unit)? = null
    /** Karede (x, y)'deki eşyanın silüetini ayırır; sonuç ana iş parçacığında gelir. */
    var segmentIn: ((Bitmap, Float, Float, (Cutout?) -> Unit) -> Unit)? = null
    var sfx: Sfx? = null
    var haptics: ((Material) -> Unit)? = null
    var insetTop = 0
    var onAddModeChanged: ((Boolean) -> Unit)? = null

    /** Eşya ekleme modu: dokunulan eşya engel olur (ya da kaldırılır), sürükleyerek kutu çizilir. */
    var addMode = false
        set(v) {
            field = v
            drawing = null
            onAddModeChanged?.invoke(v)
            if (frozen != null) hint(if (v) "Eşyaya dokun: engel olsun / kalksın · ya da etrafına kutu çiz" else readyHint())
        }

    private var frozen: Bitmap? = null
    private var scanning = false
    private var seen: List<Det> = emptyList()
    private val obstacles = ArrayList<Obstacle>()

    // Çarpışma ızgarası: hücre başına engel numarası (0 = boş)
    private var cell = 1f
    private var gw = 0
    private var gh = 0
    private var grid = IntArray(0)

    private val ball = Ball(0f, 0f)
    private var moving = false
    private var sinking = 0f        // >0: top deliğe düşüyor
    private var lipCool = 0f
    private var touched = HashSet<Obstacle>()
    private val trail = ArrayDeque<FloatArray>()
    private var holeX = 0f
    private var holeY = 0f
    private var teeX = 0f
    private var teeY = 0f
    private var holeNo = 0
    private var strokes = 0
    private var par = 2
    private var total = 0          // toplam (vuruş - par)
    private var holeReady = false

    private var status = ""
    private var statusT = 0L
    private val sparks = ArrayList<Spark>()
    private val notes = ArrayList<Note>()
    private var lastFrame = 0L

    // Dokunma
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var aiming = false
    private var pointerDown = false
    private var drawing: RectF? = null

    // ---------- Alan ve perspektif ----------

    private fun top() = insetTop + 118 * d
    private fun bottom() = height - 78 * d
    private fun left() = 6 * d
    private fun right() = width - 6 * d

    /** Uzaktaki (ekranın üstü) her şey daha küçük ve daha yavaş görünür. */
    private fun persp(y: Float) = (0.55f + 0.45f * ((y - top()) / max(1f, bottom() - top())).coerceIn(0f, 1f))
    private fun ballR(y: Float) = BALL_R * d * persp(y)
    private fun holeR(y: Float) = ballR(y) * 1.9f

    fun hint(text: String) {
        status = text
        statusT = SystemClock.uptimeMillis()
    }

    private fun readyHint() =
        if (obstacles.isEmpty()) "Engel yok — ➕ Eşya ile odadaki eşyaları ekle, sonra parmağını geri çekip bırak"
        else "Parmağını geri çek, bırak · ${obstacles.size} eşyadan sekebilirsin"

    /** Mod açıldığında: oda kurulmadıysa yol göster. */
    fun onShown() {
        if (frozen == null) hint("Telefonu odaya çevir, ekrana dokun ya da 📸 Oda'ya bas")
    }

    // ---------- Oda kurulumu ----------

    /** Canlı kareyi dondurur, eşyaları bulur ve engel yapar, ilk deliği yerleştirir. */
    fun newRoom() {
        if (scanning) return
        val frame = frameProvider?.invoke()
        if (frame == null || width == 0) { hint("Kamera görüntüsü alınamadı, birazdan tekrar dene."); return }
        frozen = frame
        obstacles.clear()
        seen = emptyList()
        rebuildGrid()
        holeReady = false
        moving = false
        sinking = 0f
        total = 0
        holeNo = 0
        scanning = true
        hint("Oda taranıyor… eşyalar engele dönüşecek")
        val scan = scanRoom
        if (scan == null) { onScanned(frame, emptyList()); return }
        scan(frame) { dets -> if (frozen === frame) onScanned(frame, dets) }
    }

    private fun onScanned(frame: Bitmap, dets: List<Det>) {
        scanning = false
        seen = dets
        val area = width * height.toFloat()
        val picked = ArrayList<Det>()
        for (det in dets.filter { it.score >= 0.35f }.sortedByDescending { it.score }) {
            val b = det.box
            val a = b.width() * b.height()
            if (a > area * 0.35f || b.width() < 22 * d || b.height() < 22 * d) continue
            if (b.bottom < top() || b.top > bottom()) continue
            if (picked.any { iou(it.box, b) > 0.45f }) continue
            picked += det
            if (picked.size >= 12) break
        }
        for (det in picked) obstacles += makeObstacle(det.cls, RectF(det.box))
        rebuildGrid()
        newHole(advance = true)
        hint(if (picked.isEmpty()) "Eşya bulunamadı — ➕ Eşya ile kendin ekle ya da duvarlardan sektir"
        else "${picked.size} eşya engel oldu: " + picked.groupBy { nameOf(it.cls) }.keys.joinToString(", "))
        // Silüetler arka planda gelir; kutular yerini gerçek şekle bırakır
        for (o in obstacles.toList()) {
            segmentIn?.invoke(frame, o.box.centerX(), o.box.centerY()) { cut ->
                if (frozen !== frame || o !in obstacles || cut == null) return@invoke
                if (iou(cut.box, o.box) > 0.3f && o.box.contains(cut.box.centerX(), cut.box.centerY())) {
                    attachCut(o, cut)
                    rebuildGrid()
                }
            }
        }
    }

    private fun nameOf(cls: String?): String {
        if (cls == null) return "Eşya"
        val n = BREAKABLES[cls]?.label ?: OTHER_LABELS[cls] ?: cls
        return n.replaceFirstChar { it.titlecase(tr) }
    }

    private fun makeObstacle(cls: String?, box: RectF): Obstacle {
        val c = cls ?: ""
        val kind = when {
            c in SOFT_CLASSES -> KIND_SOFT
            c in HARD_CLASSES -> KIND_HARD
            else -> KIND_NORMAL
        }
        val bounce = when (kind) { KIND_SOFT -> 0.32f; KIND_HARD -> 0.9f; else -> 0.68f }
        return Obstacle(nameOf(cls), box, bounce, kind)
    }

    private fun attachCut(o: Obstacle, cut: Cutout) {
        val w = cut.obj.width
        val h = cut.obj.height
        val px = IntArray(w * h)
        cut.obj.getPixels(px, 0, w, 0, 0, w, h)
        o.cut = cut
        o.mask = px
        o.mw = w
        o.mh = h
        o.box.set(cut.box)
    }

    private fun clsFor(box: RectF): String? {
        val best = seen.maxByOrNull { iou(it.box, box) } ?: return null
        return if (iou(best.box, box) > 0.3f) best.cls else null
    }

    /** Ekle modunda dokunuş: eşyanın üstündeyse kaldırır, değilse oradaki eşyayı ayırıp engel yapar. */
    private fun toggleAt(x: Float, y: Float) {
        val frame = frozen ?: return
        obstacles.filter { solidOf(it, x, y) || it.box.contains(x, y) }
            .minByOrNull { it.box.width() * it.box.height() }?.let {
                obstacles.remove(it)
                rebuildGrid()
                hint("${it.name} kaldırıldı · ${obstacles.size} engel")
                return
            }
        hint("Eşya ayrılıyor…")
        val seg = segmentIn
        if (seg == null) { hint("Ayırıcı hazır değil — etrafına kutu çiz"); return }
        seg.invoke(frame, x, y) { cut ->
            if (frozen !== frame) return@invoke
            if (cut == null) {
                hint("Burada eşya bulunamadı (duvar/zemin çok büyük) — etrafına kutu çizebilirsin")
                return@invoke
            }
            val o = makeObstacle(clsFor(cut.box), RectF(cut.box))
            attachCut(o, cut)
            addObstacle(o)
        }
    }

    private fun addObstacle(o: Obstacle) {
        obstacles += o
        o.flash = 1f
        rebuildGrid()
        // Top ya da delik yeni engelin içinde kaldıysa kenara al
        if (!moving && sinking == 0f && blockedAt(ball.x, ball.y, ballR(ball.y))) freeSpot(ball.x, ball.y)?.let { ball.x = it[0]; ball.y = it[1] }
        if (holeReady && blockedAt(holeX, holeY, holeR(holeY))) {
            freeSpot(holeX, holeY)?.let { holeX = it[0]; holeY = it[1] }
        }
        val kind = when (o.kind) { KIND_SOFT -> "yumuşak"; KIND_HARD -> "sert"; else -> "orta" }
        hint("${o.name} engel oldu ($kind) · ${obstacles.size} engel")
    }

    // ---------- Çarpışma ızgarası ----------

    private fun rebuildGrid() {
        if (width == 0 || height == 0) return
        cell = max(3f, 3f * d)
        gw = ceil(width / cell).toInt()
        gh = ceil(height / cell).toInt()
        grid = IntArray(gw * gh)
        for ((i, o) in obstacles.withIndex()) {
            val b = o.box
            val gx0 = (b.left / cell).toInt().coerceIn(0, gw - 1)
            val gx1 = (b.right / cell).toInt().coerceIn(0, gw - 1)
            val gy0 = (b.top / cell).toInt().coerceIn(0, gh - 1)
            val gy1 = (b.bottom / cell).toInt().coerceIn(0, gh - 1)
            for (gy in gy0..gy1) for (gx in gx0..gx1) {
                if (solidOf(o, (gx + 0.5f) * cell, (gy + 0.5f) * cell)) grid[gy * gw + gx] = i + 1
            }
        }
    }

    /** Nokta eşyanın (silüeti varsa silüetinin, yoksa kutusunun) içinde mi? */
    private fun solidOf(o: Obstacle, x: Float, y: Float): Boolean {
        val b = o.box
        if (!b.contains(x, y)) return false
        val m = o.mask ?: return true
        val mx = ((x - b.left) / b.width() * o.mw).toInt().coerceIn(0, o.mw - 1)
        val my = ((y - b.top) / b.height() * o.mh).toInt().coerceIn(0, o.mh - 1)
        return (m[my * o.mw + mx] ushr 24) > 110
    }

    /** Noktadaki engel numarası (0 = boş). */
    private fun cellAt(x: Float, y: Float): Int {
        val gx = (x / cell).toInt()
        val gy = (y / cell).toInt()
        if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) return 0
        return grid[gy * gw + gx]
    }

    private fun blockedAt(x: Float, y: Float, r: Float): Boolean {
        if (x - r < left() || x + r > right() || y - r < top() || y + r > bottom()) return true
        if (cellAt(x, y) != 0) return true
        for (k in 0 until 12) {
            val a = k / 12f * 2f * PI.toFloat()
            if (cellAt(x + cos(a) * r, y + sin(a) * r) != 0) return true
        }
        return false
    }

    /** (x, y)'ye en yakın, topun sığdığı boş nokta. */
    private fun freeSpot(x: Float, y: Float): FloatArray? {
        val r = BALL_R * d * 1.3f
        var ring = 0f
        while (ring < max(width, height)) {
            val n = max(1, (ring / (6 * d)).toInt() * 2)
            for (k in 0 until n) {
                val a = k / n.toFloat() * 2f * PI.toFloat()
                val px = x + cos(a) * ring
                val py = y + sin(a) * ring
                if (!blockedAt(px, py, r)) return floatArrayOf(px, py)
            }
            ring += 6 * d
        }
        return null
    }

    // ---------- Delik yerleştirme ----------

    /**
     * Yeni delik: top başlangıç noktasına döner, delik ulaşılabilir boş bir yere konur.
     * Mümkünse başlangıçtan doğrudan görünmeyen (bir eşyanın arkasında) bir yer seçilir,
     * böylece topu sektirmek gerekir.
     */
    fun newHole(advance: Boolean = true) {
        if (frozen == null || width == 0) return
        if (!advance && holeReady && strokes > 0) total += strokes + 1 - par // pas geçmenin cezası
        teeX = width / 2f
        teeY = bottom() - 46 * d
        if (blockedAt(teeX, teeY, ballR(teeY) * 1.3f)) freeSpot(teeX, teeY)?.let { teeX = it[0]; teeY = it[1] }
        ball.x = teeX; ball.y = teeY; ball.vx = 0f; ball.vy = 0f
        moving = false
        sinking = 0f
        strokes = 0
        trail.clear()
        holeNo++

        val reach = reachable(teeX, teeY)
        val span = bottom() - top()
        val open = ArrayList<FloatArray>()
        val hidden = ArrayList<FloatArray>()
        repeat(500) {
            val x = left() + 30 * d + Random.nextFloat() * (right() - left() - 60 * d)
            val y = top() + 20 * d + Random.nextFloat() * (span * 0.72f)
            if (hypot(x - teeX, y - teeY) < span * 0.38f) return@repeat
            if (blockedAt(x, y, holeR(y) * 1.5f)) return@repeat
            if (reach != null && !reach[cellIndex(x, y)]) return@repeat
            if (lineBlocked(teeX, teeY, x, y)) hidden += floatArrayOf(x, y) else open += floatArrayOf(x, y)
        }
        val pick = when {
            hidden.isNotEmpty() && (open.isEmpty() || Random.nextFloat() < 0.75f) -> hidden.random().also { par = 3 }
            open.isNotEmpty() -> open.maxByOrNull { hypot(it[0] - teeX, it[1] - teeY) * (0.6f + Random.nextFloat()) }!!.also { par = 2 }
            else -> floatArrayOf(width / 2f, top() + 50 * d).also { par = 3 }
        }
        holeX = pick[0]
        holeY = pick[1]
        holeReady = true
        hint(if (par == 3) "Delik $holeNo · Par 3 — delik eşyanın arkasında, sektirmen gerek!"
        else "Delik $holeNo · Par 2 — parmağını geri çek, bırak")
    }

    private fun cellIndex(x: Float, y: Float): Int {
        val gx = (x / cell).toInt().coerceIn(0, gw - 1)
        val gy = (y / cell).toInt().coerceIn(0, gh - 1)
        return gy * gw + gx
    }

    /** Topun sığdığı hücrelerden, başlangıçtan ulaşılabilenler (taşkın doldurma). */
    private fun reachable(sx: Float, sy: Float): BooleanArray? {
        if (gw == 0) return null
        val k = ceil(ballR(bottom()) / cell).toInt()
        // Engeller topun yarıçapı kadar şişirilir
        val rowMax = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var hit = false
            for (dx in -k..k) {
                val x = gx + dx
                if (x in 0 until gw && grid[gy * gw + x] != 0) { hit = true; break }
            }
            rowMax[gy * gw + gx] = hit
        }
        val blocked = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var hit = false
            for (dy in -k..k) {
                val y = gy + dy
                if (y in 0 until gh && rowMax[y * gw + gx]) { hit = true; break }
            }
            val cx = (gx + 0.5f) * cell
            val cy = (gy + 0.5f) * cell
            blocked[gy * gw + gx] = hit || cx < left() + k * cell || cx > right() - k * cell ||
                cy < top() + k * cell || cy > bottom() - k * cell
        }
        val visited = BooleanArray(gw * gh)
        val start = cellIndex(sx, sy)
        if (blocked[start]) return null
        val queue = IntArray(gw * gh)
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val i = queue[head++]
            val x = i % gw
            val y = i / gw
            for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx !in 0 until gw || ny !in 0 until gh) continue
                val j = ny * gw + nx
                if (!visited[j] && !blocked[j]) { visited[j] = true; queue[tail++] = j }
            }
        }
        return visited
    }

    /** İki nokta arasındaki düz yol (topun genişliğiyle) bir eşyaya çarpıyor mu? */
    private fun lineBlocked(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val len = hypot(x1 - x0, y1 - y0)
        val nx = -(y1 - y0) / max(1f, len)
        val ny = (x1 - x0) / max(1f, len)
        val steps = (len / (cell * 0.7f)).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            val r = ballR(y) * 0.8f
            if (cellAt(x, y) != 0 || cellAt(x + nx * r, y + ny * r) != 0 || cellAt(x - nx * r, y - ny * r) != 0) return true
        }
        return false
    }

    // ---------- Girdi ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount > 1) {
            pointerDown = false; aiming = false; drawing = null
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; curX = e.x; curY = e.y
                pointerDown = true
                aiming = frozen != null && !addMode && !moving && sinking == 0f && !scanning && strokes < MAX_STROKES
                if (addMode) drawing = RectF(e.x, e.y, e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                curX = e.x; curY = e.y
                drawing?.set(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                curX = e.x; curY = e.y
                onUp(e.x, e.y)
            }
            MotionEvent.ACTION_CANCEL -> { pointerDown = false; aiming = false; drawing = null }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun onUp(x: Float, y: Float) {
        if (!pointerDown) return
        pointerDown = false
        val dist = hypot(x - downX, y - downY)
        if (frozen == null) { if (!scanning) newRoom(); return }
        if (addMode) {
            val box = drawing
            drawing = null
            if (box != null && box.width() > 25 * d && box.height() > 25 * d) {
                addObstacle(makeObstacle(clsFor(box), box))
            } else if (dist < 20 * d) toggleAt(x, y)
            return
        }
        if (!aiming) return
        aiming = false
        val shot = shotVelocity() ?: run {
            if (dist < 20 * d) hint("Parmağını topun gideceği yönün tersine çek, sonra bırak")
            return
        }
        ball.vx = shot[0]
        ball.vy = shot[1]
        moving = true
        strokes++
        touched.clear()
        trail.clear()
        sfx?.putt()
    }

    /** Çekme vektörünün tersi yönünde, çekme miktarıyla orantılı hız; çok kısa çekişte null. */
    private fun shotVelocity(): FloatArray? {
        val px = downX - curX
        val py = downY - curY
        val len = hypot(px, py)
        if (len < 18 * d) return null
        val power = (len / (MAX_PULL * d)).coerceAtMost(1f)
        val v = MAX_SPEED * d * power * (0.45f + 0.55f * power) // kısa çekişte ince ayar
        return floatArrayOf(px / len * v, py / len * v)
    }

    // ---------- Fizik ----------

    /**
     * Topu dt kadar ilerletir. Duvarlardan ve eşyaların silüetlerinden seker.
     * [preview] true ise ses/efekt yok; çarpılan engel sayısı döner.
     */
    private fun step(b: Ball, dt: Float, preview: Boolean): Int {
        var bounces = 0
        val sp = b.speed
        val n = ceil(sp * dt / max(1f, ballR(b.y) * 0.35f)).toInt().coerceIn(1, 60)
        val h = dt / n
        repeat(n) {
            val p = persp(b.y)
            val r = ballR(b.y)
            b.x += b.vx * p * h
            b.y += b.vy * p * h
            // Duvarlar: ekranın kenarları
            var wall = false
            if (b.x - r < left()) { b.x = left() + r; if (b.vx < 0) { b.vx = -b.vx * WALL_BOUNCE; wall = true } }
            if (b.x + r > right()) { b.x = right() - r; if (b.vx > 0) { b.vx = -b.vx * WALL_BOUNCE; wall = true } }
            if (b.y - r < top()) { b.y = top() + r; if (b.vy < 0) { b.vy = -b.vy * WALL_BOUNCE; wall = true } }
            if (b.y + r > bottom()) { b.y = bottom() - r; if (b.vy > 0) { b.vy = -b.vy * WALL_BOUNCE; wall = true } }
            if (wall) {
                bounces++
                if (!preview && b.speed > 120 * d) { sfx?.bounce(); puff(b.x, b.y, 0xFFE8E2D6.toInt(), 5) }
            }
            // Eşyalar: topun çevresindeki dolu noktalar çarpma yönünü verir
            var sx = 0f
            var sy = 0f
            var hits = 0
            var hitId = 0
            for (k in 0 until 16) {
                val a = k / 16f * 2f * PI.toFloat()
                val ca = cos(a)
                val sa = sin(a)
                val id = cellAt(b.x + ca * r, b.y + sa * r)
                if (id != 0) { sx += ca; sy += sa; hits++; hitId = id }
            }
            val center = cellAt(b.x, b.y)
            if (center != 0) hitId = center
            if (hits > 0 || center != 0) {
                var nx = -sx
                var ny = -sy
                var nl = hypot(nx, ny)
                if (nl < 1e-3f) { nx = -b.vx; ny = -b.vy; nl = max(1e-3f, hypot(nx, ny)) }
                nx /= nl; ny /= nl
                // İçinden çıkana kadar normal boyunca it
                var guard = 0
                while (guard++ < 24 && (cellAt(b.x, b.y) != 0 || ringHit(b.x, b.y, r))) {
                    b.x += nx * cell * 0.5f
                    b.y += ny * cell * 0.5f
                }
                val vn = b.vx * nx + b.vy * ny
                if (vn < 0) {
                    val o = obstacles.getOrNull(hitId - 1)
                    val e = o?.bounce ?: 0.7f
                    val tx = b.vx - vn * nx
                    val ty = b.vy - vn * ny
                    val keep = if (o?.kind == KIND_SOFT) 0.75f else 0.94f
                    b.vx = tx * keep - e * vn * nx
                    b.vy = ty * keep - e * vn * ny
                    bounces++
                    if (!preview && o != null) {
                        o.flash = 1f
                        touched += o
                        if (-vn > 90 * d) {
                            sfx?.bounce()
                            puff(b.x - nx * r, b.y - ny * r, if (o.kind == KIND_SOFT) 0xFFB8F5A0.toInt() else 0xFF9DEBFF.toInt(), 7)
                        }
                    }
                }
            }
            // Sürtünme
            val s = b.speed
            if (s > 0f) {
                val ns = max(0f, s * exp(-FRICTION * h) - DECEL * d * h)
                b.vx *= ns / s
                b.vy *= ns / s
            }
            if (!preview && moving && checkHole(h)) return bounces
        }
        return bounces
    }

    private fun ringHit(x: Float, y: Float, r: Float): Boolean {
        for (k in 0 until 16) {
            val a = k / 16f * 2f * PI.toFloat()
            if (cellAt(x + cos(a) * r, y + sin(a) * r) != 0) return true
        }
        return false
    }

    /** Top deliğin üstündeyse ve yeterince yavaşsa düşer; hızlıysa ağzından döner. */
    private fun checkHole(h: Float): Boolean {
        lipCool = max(0f, lipCool - h)
        val hr = holeR(holeY)
        val dx = holeX - ball.x
        val dy = holeY - ball.y
        val dist = hypot(dx, dy)
        if (dist > hr) return false
        val s = ball.speed
        if (s < CAPTURE * d || (dist < hr * 0.4f && s < CAPTURE * 1.8f * d)) {
            sink()
            return true
        }
        if (lipCool == 0f) {
            // Ağızdan dönme: delik merkezine doğru biraz çekilip sapar
            lipCool = 0.25f
            val pull = 0.35f
            ball.vx = ball.vx * 0.82f + dx / max(1f, dist) * s * pull
            ball.vy = ball.vy * 0.82f + dy / max(1f, dist) * s * pull
            note(holeX, holeY - 30 * d, "Az kaldı!", 0xFFFFE08A.toInt())
            sfx?.bounce()
        }
        return false
    }

    private fun sink() {
        moving = false
        sinking = 1f
        ball.vx = 0f; ball.vy = 0f
        sfx?.cup()
        haptics?.invoke(Material.CERAMIC)
        val diff = strokes - par
        total += diff
        val word = when {
            strokes == 1 -> "Tek vuruşta! 🏆"
            diff <= -2 -> "Kartal! 🦅"
            diff == -1 -> "Birdie! 🐦"
            diff == 0 -> "Par ✓"
            else -> "+$diff"
        }
        val bank = if (touched.isNotEmpty()) " · ${touched.size} eşyadan sekti" else ""
        note(holeX, holeY - 40 * d, word, 0xFFFFD23F.toInt())
        hint("Delik $holeNo: $strokes vuruş$bank")
        repeat(40) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val v = (80 + Random.nextFloat() * 260) * d
            val colors = intArrayOf(0xFFFFD23F.toInt(), 0xFFFF5D5D.toInt(), 0xFF6BE3FF.toInt(), 0xFF8CFF8C.toInt(), Color.WHITE)
            sparks += Spark(holeX, holeY, cos(a) * v, sin(a) * v - 220 * d, colors[Random.nextInt(colors.size)], 0.9f + Random.nextFloat() * 0.6f)
        }
        later(1900)
    }

    /** Kısa bir beklemeden sonra sıradaki delik (bu arada oda ya da delik değiştiyse vazgeçer). */
    private fun later(ms: Long) {
        val room = frozen
        val n = holeNo
        postDelayed({ if (frozen === room && holeNo == n && !moving) newHole(advance = true) }, ms)
    }

    private fun puff(x: Float, y: Float, color: Int, n: Int) {
        repeat(n) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val v = (40 + Random.nextFloat() * 120) * d
            sparks += Spark(x, y, cos(a) * v, sin(a) * v, color, 0.35f + Random.nextFloat() * 0.25f)
        }
    }

    private fun note(x: Float, y: Float, text: String, color: Int) {
        notes += Note(x.coerceIn(80 * d, max(80 * d, width - 80 * d)), y.coerceAtLeast(top() + 20 * d), text, color)
    }

    // ---------- Döngü ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGrid()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.04f, (now - lastFrame) / 1000f)
        lastFrame = now
        update(dt)
        render(c, now)
        postInvalidateOnAnimation()
    }

    private fun update(dt: Float) {
        if (moving) {
            step(ball, dt, preview = false)
            if (moving) {
                trail.addLast(floatArrayOf(ball.x, ball.y))
                while (trail.size > 14) trail.removeFirst()
                if (ball.speed < STOP * d) {
                    moving = false
                    ball.vx = 0f; ball.vy = 0f
                    if (strokes >= MAX_STROKES) {
                        note(width / 2f, height / 2f, "Olmadı — yeni delik", 0xFFFF8A80.toInt())
                        total += strokes + 1 - par
                        later(1200)
                    } else {
                        hint("Vuruş $strokes · Par $par — devam: parmağını geri çek, bırak")
                    }
                }
            }
        } else if (trail.isNotEmpty()) trail.removeFirst()
        if (sinking > 0f) {
            sinking = max(0.001f, sinking - dt / 0.45f)
            ball.x += (holeX - ball.x) * min(1f, dt * 14f)
            ball.y += (holeY - ball.y) * min(1f, dt * 14f)
        }
        for (o in obstacles) o.flash = max(0f, o.flash - dt * 2.2f)
        val g = 700f * d
        sparks.removeAll { s ->
            s.age += dt; s.vy += g * dt * 0.6f; s.x += s.vx * dt; s.y += s.vy * dt
            s.vx *= 0.985f
            s.age >= s.life
        }
        notes.removeAll { n -> n.age += dt; n.y -= 34f * d * dt; n.age >= 1.6f }
    }

    // ---------- Çizim ----------

    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    private val tintPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelBg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val noteStroke = Paint(notePaint).apply { style = Paint.Style.STROKE; color = Color.argb(190, 0, 0, 0) }
    private val hudBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) }
    private val hudSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 255, 255, 255); textAlign = Paint.Align.CENTER }
    private val hudBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val hudStatus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val promptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val tmpRect = RectF()
    private val tmpPath = Path()
    private val tints = mapOf(
        KIND_SOFT to PorterDuffColorFilter(0xFF8CFF8C.toInt(), PorterDuff.Mode.SRC_ATOP),
        KIND_NORMAL to PorterDuffColorFilter(0xFFFFD23F.toInt(), PorterDuff.Mode.SRC_ATOP),
        KIND_HARD to PorterDuffColorFilter(0xFF6BE3FF.toInt(), PorterDuff.Mode.SRC_ATOP),
    )
    private fun kindColor(k: Int) = when (k) { KIND_SOFT -> 0xFF8CFF8C.toInt(); KIND_HARD -> 0xFF6BE3FF.toInt(); else -> 0xFFFFD23F.toInt() }

    private fun render(c: Canvas, now: Long) {
        val img = frozen
        if (img == null) {
            drawViewfinder(c, now)
            drawHud(c)
            return
        }
        tmpRect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(img, null, tmpRect, bgPaint)
        // Oyun alanının dışı hafif karartılır: duvarların nerede olduğu görünsün
        c.drawRect(0f, 0f, width.toFloat(), top(), dimPaint)
        c.drawRect(0f, bottom(), width.toFloat(), height.toFloat(), dimPaint)
        linePaint.pathEffect = null
        linePaint.color = Color.WHITE
        linePaint.alpha = 70
        linePaint.strokeWidth = 2 * d
        c.drawRect(left(), top(), right(), bottom(), linePaint)

        for (o in obstacles) drawObstacle(c, o, now)
        if (holeReady) drawHole(c, now)

        // İz
        var i = 0
        for (p in trail) {
            i++
            fillPaint.color = Color.WHITE
            fillPaint.alpha = 12 * i
            c.drawCircle(p[0], p[1], ballR(p[1]) * (0.3f + 0.04f * i), fillPaint)
        }
        if (aiming && pointerDown) drawAim(c)
        if (holeReady) drawBall(c)
        if (holeReady && !moving && sinking == 0f && !aiming) drawFlag(c, now)

        for (s in sparks) {
            fillPaint.color = s.color
            fillPaint.alpha = ((1f - s.age / s.life) * 255).toInt().coerceIn(0, 255)
            c.drawCircle(s.x, s.y, 2.6f * d, fillPaint)
        }
        drawing?.let {
            linePaint.color = 0xFFFFD23F.toInt()
            linePaint.alpha = 255
            linePaint.strokeWidth = 2.5f * d
            linePaint.pathEffect = DashPathEffect(floatArrayOf(10 * d, 7 * d), 0f)
            c.drawRect(it, linePaint)
            linePaint.pathEffect = null
        }
        notePaint.textSize = 26 * d
        noteStroke.textSize = 26 * d
        noteStroke.strokeWidth = 5 * d
        for (n in notes) {
            val a = (min(1f, 2f * (1.6f - n.age)) * 255).toInt().coerceIn(0, 255)
            noteStroke.alpha = a * 190 / 255
            notePaint.color = n.color
            notePaint.alpha = a
            c.drawText(n.text, n.x, n.y, noteStroke)
            c.drawText(n.text, n.x, n.y, notePaint)
        }
        drawHud(c)
    }

    /** Oda kurulmadan önce: canlı kameranın üstünde vizör ve yönerge. */
    private fun drawViewfinder(c: Canvas, now: Long) {
        val pulse = 0.6f + 0.4f * sin(now / 300f)
        val m = 28 * d
        val k = 40 * d
        linePaint.color = Color.WHITE
        linePaint.alpha = (220 * pulse).toInt()
        linePaint.strokeWidth = 4 * d
        linePaint.pathEffect = null
        val l = m; val t = top(); val r = width - m; val b = bottom()
        tmpPath.reset()
        for ((x, y, sx, sy) in listOf(
            floatArrayOf(l, t, 1f, 1f), floatArrayOf(r, t, -1f, 1f),
            floatArrayOf(l, b, 1f, -1f), floatArrayOf(r, b, -1f, -1f),
        )) {
            tmpPath.moveTo(x + sx * k, y); tmpPath.lineTo(x, y); tmpPath.lineTo(x, y + sy * k)
        }
        c.drawPath(tmpPath, linePaint)
        val cy = (t + b) / 2f
        promptPaint.textSize = 24 * d
        val lines = if (scanning) listOf("Oda taranıyor…") else listOf("⛳ Odada Mini Golf", "Odaya bak, ekrana dokun:", "eşyalar engel olur, top onlardan seker")
        for ((j, s) in lines.withIndex()) {
            promptPaint.textSize = if (j == 0) 26 * d else 17 * d
            val y = cy + (j - 1) * 34 * d
            tmpRect.set(width / 2f - promptPaint.measureText(s) / 2f - 12 * d, y - 26 * d, width / 2f + promptPaint.measureText(s) / 2f + 12 * d, y + 9 * d)
            c.drawRoundRect(tmpRect, 10 * d, 10 * d, hudBg)
            c.drawText(s, width / 2f, y, promptPaint)
        }
    }

    /** Engel: silüeti malzemesine göre renklenir (yeşil yumuşak, sarı orta, mavi sert), çarpınca parlar. */
    private fun drawObstacle(c: Canvas, o: Obstacle, now: Long) {
        val color = kindColor(o.kind)
        val pulse = 0.5f + 0.5f * sin(now / 400f)
        val cut = o.cut
        if (cut != null) {
            tintPaint.colorFilter = tints[o.kind]
            tintPaint.alpha = (45 + 25 * pulse + 150 * o.flash).toInt().coerceIn(0, 255)
            c.drawBitmap(cut.obj, null, o.box, tintPaint)
        } else {
            fillPaint.color = color
            fillPaint.alpha = (30 + 120 * o.flash).toInt().coerceIn(0, 255)
            c.drawRoundRect(o.box, 8 * d, 8 * d, fillPaint)
            linePaint.color = color
            linePaint.alpha = 200
            linePaint.strokeWidth = 2 * d
            c.drawRoundRect(o.box, 8 * d, 8 * d, linePaint)
        }
        labelText.textSize = 11 * d
        labelText.color = color
        val label = o.name
        val tw = labelText.measureText(label)
        val ly = max(top() + 2 * d, o.box.top - 16 * d)
        c.drawRect(o.box.left, ly, o.box.left + tw + 8 * d, ly + 15 * d, labelBg)
        c.drawText(label, o.box.left + 4 * d, ly + 11.5f * d, labelText)
    }

    private fun drawHole(c: Canvas, now: Long) {
        val r = holeR(holeY)
        // Çim halka, delik (perspektifte basık elips), ağız gölgesi
        fillPaint.color = 0xFF3FA34D.toInt()
        fillPaint.alpha = 150
        tmpRect.set(holeX - r * 2.1f, holeY - r * 0.95f, holeX + r * 2.1f, holeY + r * 0.95f)
        c.drawOval(tmpRect, fillPaint)
        holePaint.shader = RadialGradient(holeX, holeY - r * 0.2f, r, 0xFF000000.toInt(), 0xFF2A2A2A.toInt(), Shader.TileMode.CLAMP)
        tmpRect.set(holeX - r, holeY - r * 0.55f, holeX + r, holeY + r * 0.55f)
        c.drawOval(tmpRect, holePaint)
        linePaint.color = Color.WHITE
        linePaint.alpha = 160
        linePaint.strokeWidth = 1.5f * d
        c.drawOval(tmpRect, linePaint)
        // Hedef halkası: nabız gibi atar
        val k = (now % 1400) / 1400f
        linePaint.alpha = (120 * (1 - k)).toInt()
        tmpRect.set(holeX - r * (1 + k), holeY - r * 0.55f * (1 + k), holeX + r * (1 + k), holeY + r * 0.55f * (1 + k))
        c.drawOval(tmpRect, linePaint)
    }

    /** Delikteki bayrak: top dururken görünür, sallanır. */
    private fun drawFlag(c: Canvas, now: Long) {
        val p = persp(holeY)
        val poleH = 62 * d * p
        val topY = holeY - poleH
        linePaint.color = 0xFFEDEDED.toInt()
        linePaint.alpha = 255
        linePaint.strokeWidth = 2.2f * d * p
        c.drawLine(holeX, holeY, holeX, topY, linePaint)
        val wave = sin(now / 180f) * 4 * d * p
        tmpPath.reset()
        tmpPath.moveTo(holeX, topY)
        tmpPath.quadTo(holeX + 14 * d * p, topY + 4 * d * p + wave, holeX + 30 * d * p, topY + 9 * d * p + wave * 0.5f)
        tmpPath.lineTo(holeX, topY + 20 * d * p)
        tmpPath.close()
        fillPaint.color = 0xFFE53935.toInt()
        fillPaint.alpha = 255
        c.drawPath(tmpPath, fillPaint)
        labelText.textSize = 10 * d
        labelText.color = Color.WHITE
        c.drawText(holeNo.toString(), holeX + 6 * d * p, topY + 13 * d * p, labelText)
    }

    private fun drawBall(c: Canvas) {
        val k = if (sinking > 0f) sinking else 1f
        val r = ballR(ball.y) * k
        if (r < 0.5f) return
        tmpRect.set(ball.x - r * 0.9f, ball.y + r * 0.45f, ball.x + r * 1.1f, ball.y + r * 1.05f)
        c.drawOval(tmpRect, shadowPaint)
        ballPaint.shader = RadialGradient(ball.x - r * 0.35f, ball.y - r * 0.4f, r * 1.3f, Color.WHITE, 0xFFC9CED6.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(ball.x, ball.y, r, ballPaint)
        // Gamzeler
        fillPaint.color = Color.argb(40, 0, 0, 0)
        for (j in 0 until 5) {
            val a = j * 1.3f
            c.drawCircle(ball.x + cos(a) * r * 0.5f, ball.y + sin(a) * r * 0.5f, r * 0.12f, fillPaint)
        }
    }

    /** Nişan: arkaya çekilen sopa çizgisi ve ilk sekmeye kadar (biraz da sonrası) tahmini yol. */
    private fun drawAim(c: Canvas) {
        val shot = shotVelocity() ?: return
        val power = (hypot(downX - curX, downY - curY) / (MAX_PULL * d)).coerceAtMost(1f)
        // Çekme çizgisi: toptan geriye
        val dir = hypot(shot[0], shot[1])
        val ux = shot[0] / dir
        val uy = shot[1] / dir
        val pull = power * 70 * d
        linePaint.color = if (power > 0.9f) 0xFFFF6B6B.toInt() else 0xFFFFD23F.toInt()
        linePaint.alpha = 230
        linePaint.strokeWidth = 4 * d
        c.drawLine(ball.x - ux * ballR(ball.y) * 1.4f, ball.y - uy * ballR(ball.y) * 1.4f,
            ball.x - ux * (ballR(ball.y) * 1.4f + pull), ball.y - uy * (ballR(ball.y) * 1.4f + pull), linePaint)
        // Tahmini yol: aynı fizikle ileri sarılır, ilk sekmeden sonra kısa bir süre daha gösterilir
        val sim = Ball(ball.x, ball.y).also { it.vx = shot[0]; it.vy = shot[1] }
        var bounced = 0
        var after = 0f
        fillPaint.color = Color.WHITE
        var t = 0f
        val h = 1f / 60f
        var dot = 0
        while (t < 2.5f && sim.speed > STOP * d) {
            bounced += step(sim, h, preview = true)
            t += h
            if (bounced > 0) after += h
            if (bounced > 1 || after > 0.18f) break
            if (++dot % 3 == 0) {
                fillPaint.alpha = (230 * (1f - t / 2.5f)).toInt().coerceIn(40, 230)
                c.drawCircle(sim.x, sim.y, 2.6f * d * persp(sim.y), fillPaint)
            }
        }
        // Güç göstergesi
        val r = ballR(ball.y) * 2.2f
        linePaint.color = Color.WHITE
        linePaint.alpha = 60
        linePaint.strokeWidth = 3 * d
        tmpRect.set(ball.x - r, ball.y - r, ball.x + r, ball.y + r)
        c.drawArc(tmpRect, -90f, 360f, false, linePaint)
        linePaint.color = if (power > 0.9f) 0xFFFF6B6B.toInt() else 0xFFFFD23F.toInt()
        linePaint.alpha = 230
        c.drawArc(tmpRect, -90f, 360f * power, false, linePaint)
    }

    private fun drawHud(c: Canvas) {
        val y0 = insetTop + 10 * d
        var x = 12 * d
        hudSmall.textSize = 11 * d
        hudBig.textSize = 20 * d
        val tot = when {
            total > 0 -> "+$total"
            total == 0 -> "E"
            else -> total.toString()
        }
        for ((label, value) in listOf("Delik" to holeNo.toString(), "Vuruş" to strokes.toString(), "Par" to par.toString(), "Toplam" to tot)) {
            val w = 62 * d
            tmpRect.set(x, y0, x + w, y0 + 46 * d)
            c.drawRoundRect(tmpRect, 10 * d, 10 * d, hudBg)
            c.drawText(label, x + w / 2f, y0 + 15 * d, hudSmall)
            c.drawText(value, x + w / 2f, y0 + 38 * d, hudBig)
            x += w + 8 * d
        }
        if (status.isEmpty()) return
        hudStatus.textSize = 13 * d
        val maxW = width - 44 * d
        var text = status
        if (hudStatus.measureText(text) > maxW) {
            val n = hudStatus.breakText(text, true, maxW - hudStatus.measureText("…"), null)
            text = text.substring(0, n) + "…"
        }
        val sy = y0 + 54 * d
        tmpRect.set(12 * d, sy, 12 * d + hudStatus.measureText(text) + 20 * d, sy + 26 * d)
        c.drawRoundRect(tmpRect, 8 * d, 8 * d, hudBg)
        c.drawText(text, 22 * d, sy + 18 * d, hudStatus)
    }

    private companion object {
        const val BALL_R = 11f          // dp, ekranın altında
        const val MAX_PULL = 160f       // dp
        const val MAX_SPEED = 2300f     // dp/s
        const val FRICTION = 0.85f      // 1/s
        const val DECEL = 160f          // dp/s²
        const val STOP = 22f            // dp/s
        const val CAPTURE = 520f        // dp/s: bundan yavaşsa delikten düşer
        const val WALL_BOUNCE = 0.72f
        const val MAX_STROKES = 10
    }
}
