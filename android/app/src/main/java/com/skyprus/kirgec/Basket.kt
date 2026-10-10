package com.skyprus.kirgec

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Basket modu: top, kamerada görülen cisimlerin üstünden sekerek sanal potaya atılır.
 * Cisimler merdiven gibi dizilmelidir: yan yana ve her biri bir öncekinden daha aşağıda.
 * Oyun, görülen cisimler içinden bu kurala uyan en uzun zinciri bulur ve potayı son basamağın
 * ötesine yerleştirir. Fizik: yerçekimi, cisim kutularıyla daire–dikdörtgen çarpışması,
 * çember uçları ve pano.
 */
class BasketGame(private val d: Float) {

    var sfx: Sfx? = null
    /** Puan olayı: (puan, yazı, x, y) */
    var onScore: ((Int, String, Float, Float) -> Unit)? = null

    /** Sırayla basamaklar (ekran koordinatı). */
    var chain: List<RectF> = emptyList()
        private set
    private var others: List<RectF> = emptyList()
    /** +1: merdiven soldan sağa iner, -1: sağdan sola. */
    private var dir = 1
    var message = ""
        private set

    // Pota
    private var hoopOk = false
    private var rimY = 0f
    private var rimL = 0f
    private var rimR = 0f
    private var boardX = 0f
    private var netSwing = 0f

    // Top
    private class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float) {
        var age = 0f
        var spin = 0f
        val touched = ArrayList<Int>()
        var lastHit = -1
        var lastHitT = 0f
        var rimHits = 0
        var scored = false
    }
    private var ball: Ball? = null
    private var frozenChain: List<RectF> = emptyList()

    // Nişan
    var aiming = false
        private set
    private var downX = 0f
    private var downY = 0f
    private var pullX = 0f
    private var pullY = 0f

    var baskets = 0
        private set
    var shots = 0
        private set

    private val r = 15f * d
    private val g = 1900f * d
    private val maxPull = 170f * d

    // ---------- Dizilim ----------

    /**
     * Aday kutulardan kurala uyan en uzun merdiveni ve potanın yerini hesaplar.
     * Top uçarken dizilim dondurulur (cisimler uçuş sırasında oynamasın).
     */
    fun updateCourse(cands: List<RectF>, w: Int, h: Int, insetTop: Int) {
        if (ball != null) return
        val screenA = w.toFloat() * h
        val plats = cands
            .filter { val a = it.width() * it.height(); a > screenA * 0.002f && a < screenA * 0.3f && it.width() < w * 0.6f }
            .map { platform(it) }
        val right = longestChain(plats.sortedBy { it.centerX() }, 1, w)
        val left = longestChain(plats.sortedByDescending { it.centerX() }, -1, w)
        val best = if (left.size > right.size) left else right
        dir = if (left.size > right.size) -1 else 1
        chain = if (best.size >= 2) best else emptyList()
        others = plats.filter { p -> chain.none { it === p } }
        message = when {
            chain.isNotEmpty() -> "Merdiven hazır: ${chain.size} basamak · topu geri çek, nişan al, bırak → 1 numaranın üstüne at"
            plats.isEmpty() -> "Cisim görülmedi: bardak, kutu, kitap gibi eşyaları masaya yan yana koy (🎯/⬚ ile de ekleyebilirsin)"
            plats.size == 1 -> "En az 2 cisim gerekli: yanına bir tane daha, biraz daha alçağa koy"
            else -> "Cisimleri merdiven gibi diz: yan yana ve her biri bir öncekinden daha aşağıda"
        }
        placeHoop(w, h, insetTop)
    }

    /** Tespit kutuları gevşektir: yanlardan biraz daraltılır, üst kenar hafif aşağı alınır. */
    private fun platform(b: RectF): RectF {
        val sx = b.width() * 0.08f
        return RectF(b.left + sx, b.top + b.height() * 0.04f, b.right - sx, b.bottom)
    }

    private fun longestChain(sorted: List<RectF>, sign: Int, w: Int): List<RectF> {
        val n = sorted.size
        if (n == 0) return emptyList()
        val len = IntArray(n) { 1 }
        val prev = IntArray(n) { -1 }
        for (i in 0 until n) for (j in 0 until i) {
            if (step(sorted[j], sorted[i], sign, w) && len[j] + 1 > len[i]) { len[i] = len[j] + 1; prev[i] = j }
        }
        var end = len.indices.maxByOrNull { len[it] } ?: return emptyList()
        val out = ArrayList<RectF>()
        while (end >= 0) { out += sorted[end]; end = prev[end] }
        return out.reversed()
    }

    /** a'dan b'ye geçerli bir basamak mı: yan yana (az örtüşme, makul aralık) ve b daha aşağıda. */
    private fun step(a: RectF, b: RectF, sign: Int, w: Int): Boolean {
        if (b.top < a.top + 15 * d) return false
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap > 0.35f * min(a.width(), b.width())) return false
        val gap = if (sign > 0) b.left - a.right else a.left - b.right
        val ahead = if (sign > 0) b.centerX() > a.centerX() else b.centerX() < a.centerX()
        return ahead && gap < w * 0.45f
    }

    /**
     * Pota son basamağın ötesine ve belirgin şekilde aşağıya konur (basamaklardan seken top oraya
     * düşebilsin). Ekran dar olduğu için yana sığmazsa son basamağın altına iner.
     * Değerler, örnek merdivenlerde binlerce atışın simülasyonuyla seçildi.
     */
    private fun placeHoop(w: Int, h: Int, insetTop: Int) {
        val last = chain.lastOrNull()
        if (last == null) { hoopOk = false; return }
        val rimW = (last.width() * 1.1f).coerceIn(95 * d, 135 * d)
        var cx = if (dir > 0) last.right + 10 * d + rimW / 2 else last.left - 10 * d - rimW / 2
        cx = cx.coerceIn(rimW / 2 + 12 * d, w - rimW / 2 - 12 * d)
        var y = last.top + 140 * d
        if (cx - rimW / 2 < last.right && cx + rimW / 2 > last.left) y = max(y, last.bottom + 90 * d)
        rimY = y.coerceIn(insetTop + 140 * d, h - 200 * d)
        rimL = cx - rimW / 2
        rimR = cx + rimW / 2
        boardX = if (dir > 0) rimR + 8 * d else rimL - 8 * d
        hoopOk = true
    }

    // ---------- Girdi ----------

    private fun launchPoint(w: Int, h: Int) = floatArrayOf(w / 2f, h - 175 * d)

    fun onDown(x: Float, y: Float) {
        if (ball != null) return
        aiming = true
        downX = x; downY = y
        pullX = 0f; pullY = 0f
    }

    fun onMove(x: Float, y: Float) {
        if (!aiming) return
        var px = x - downX
        var py = y - downY
        val l = hypot(px, py)
        if (l > maxPull) { px *= maxPull / l; py *= maxPull / l }
        pullX = px; pullY = py
    }

    fun onUp(w: Int, h: Int) {
        if (!aiming) return
        aiming = false
        if (hypot(pullX, pullY) < 20 * d) return
        val (lx, ly) = launchPoint(w, h).let { it[0] to it[1] }
        val v = launchVelocity()
        ball = Ball(lx, ly, v[0], v[1], r)
        frozenChain = chain
        shots++
        sfx?.throwSound()
        pullX = 0f; pullY = 0f
    }

    /** Çekmenin tersi yönünde; tam çekişte ekranın üstüne ulaşacak kadar güçlü. */
    private fun launchVelocity(): FloatArray {
        val k = 9.6f
        return floatArrayOf(-pullX * k, -pullY * k)
    }

    // ---------- Fizik ----------

    fun update(dt: Float, w: Int, h: Int) {
        netSwing = max(0f, netSwing - dt * 1.5f)
        val b = ball ?: return
        val steps = 4
        repeat(steps) { stepBall(b, dt / steps, w, frozenChain, live = true) }
        if (b.y > h + 3 * b.r || b.age > 12f) {
            if (!b.scored) {
                val n = orderedHits(b)
                onScore?.invoke(0, if (n > 0) "Kaçtı! ($n basamak)" else "Kaçtı!", b.x.coerceIn(60 * d, w - 60 * d), h - 260 * d)
            }
            ball = null
        }
    }

    /**
     * Bir fizik adımı. [live] false ise (nişan önizlemesi) ses/puan yan etkisi olmaz.
     * @return bu adımda bir basamağa, çembere ya da panoya çarptıysa true
     */
    private fun stepBall(b: Ball, sdt: Float, w: Int, plats: List<RectF>, live: Boolean): Boolean {
        val prevY = b.y
        var hit = false
        b.vy += g * sdt
        b.x += b.vx * sdt
        b.y += b.vy * sdt
        b.age += sdt
        b.spin += b.vx / b.r * sdt
        // Ekran kenarları
        if (b.x < b.r) { b.x = b.r; b.vx = abs(b.vx) * 0.7f }
        if (b.x > w - b.r) { b.x = w - b.r; b.vx = -abs(b.vx) * 0.7f }
        // Basamaklar
        plats.forEachIndexed { i, p ->
            if (collideRect(b, p, 0.8f)) {
                hit = true
                if (live) hitPlatform(b, i) else if (i !in b.touched) b.touched += i
            }
        }
        if (hoopOk) {
            if (collideCircle(b, rimL, rimY, 3.5f * d) || collideCircle(b, rimR, rimY, 3.5f * d)) {
                hit = true
                if (live) rimHit(b)
            }
            val board = RectF(boardX - 3 * d, rimY - 95 * d, boardX + 3 * d, rimY + 12 * d)
            if (collideRect(b, board, 0.6f)) { hit = true; if (live) sfx?.bounce() }
            // Sayı: top çemberin içinden aşağı doğru geçti
            if (live && !b.scored && b.vy > 0 && prevY < rimY && b.y >= rimY && b.x > rimL + b.r * 0.4f && b.x < rimR - b.r * 0.4f) {
                b.scored = true
                score(b)
            }
        }
        return hit
    }

    /** Daire–dikdörtgen çarpışması; çarptıysa topu dışarı iter ve hızı yansıtır. */
    private fun collideRect(b: Ball, rc: RectF, e: Float): Boolean {
        val cx = b.x.coerceIn(rc.left, rc.right)
        val cy = b.y.coerceIn(rc.top, rc.bottom)
        var nx = b.x - cx
        var ny = b.y - cy
        var dist = hypot(nx, ny)
        if (dist >= b.r) return false
        if (dist < 1e-3f) {
            // Merkez kutunun içinde: en yakın kenardan dışarı
            val dl = b.x - rc.left; val dr = rc.right - b.x; val dt = b.y - rc.top; val db = rc.bottom - b.y
            when (min(min(dl, dr), min(dt, db))) {
                dt -> { nx = 0f; ny = -1f; b.y = rc.top - b.r }
                db -> { nx = 0f; ny = 1f; b.y = rc.bottom + b.r }
                dl -> { nx = -1f; ny = 0f; b.x = rc.left - b.r }
                else -> { nx = 1f; ny = 0f; b.x = rc.right + b.r }
            }
            dist = 1f
        } else {
            nx /= dist; ny /= dist
            b.x = cx + nx * b.r
            b.y = cy + ny * b.r
        }
        bounce(b, nx, ny, e)
        return true
    }

    private fun collideCircle(b: Ball, x: Float, y: Float, rr: Float): Boolean {
        var nx = b.x - x
        var ny = b.y - y
        val dist = hypot(nx, ny)
        if (dist >= b.r + rr || dist < 1e-3f) return false
        nx /= dist; ny /= dist
        b.x = x + nx * (b.r + rr)
        b.y = y + ny * (b.r + rr)
        bounce(b, nx, ny, 0.6f)
        return true
    }

    private fun bounce(b: Ball, nx: Float, ny: Float, e: Float) {
        val vn = b.vx * nx + b.vy * ny
        if (vn >= 0f) return
        val tx = -ny
        val ty = nx
        val vt = (b.vx * tx + b.vy * ty) * 0.92f
        val vnOut = -vn * e
        b.vx = nx * vnOut + tx * vt
        b.vy = ny * vnOut + ty * vt
    }

    private fun hitPlatform(b: Ball, i: Int) {
        if (b.lastHit == i && b.age - b.lastHitT < 0.15f) return
        b.lastHit = i
        b.lastHitT = b.age
        if (i !in b.touched) b.touched += i
        flashes += floatArrayOf(i.toFloat(), SystemClock.uptimeMillis().toFloat())
        sfx?.bounce()
    }

    private fun rimHit(b: Ball) {
        b.rimHits++
        sfx?.melee(Weapon.WRENCH)
    }

    /** Basamaklara sırayla (1, 2, 3, …) kaç tanesine değildiği. */
    private fun orderedHits(b: Ball): Int {
        var n = 0
        for (i in b.touched) { if (i == n) n++ else if (i > n) break }
        return n
    }

    private fun score(b: Ball) {
        if (b.touched.isEmpty()) {
            // Doğrudan atış sayılmaz: modun amacı cisimlerden sektirmek
            sfx?.swish()
            onScore?.invoke(0, "Sayılmadı — önce cisimlere sektir!", (rimL + rimR) / 2, rimY - 40 * d)
            return
        }
        baskets++
        netSwing = 1f
        val n = orderedHits(b)
        val full = frozenChain.isNotEmpty() && n == frozenChain.size
        var pts = 100 + 75 * n
        if (b.rimHits == 0) pts += 50 // çembere değmeden: "file"
        if (full) pts += 200
        sfx?.swish()
        if (full) sfx?.cheer()
        val text = when {
            full -> "TAM MERDİVEN! +$pts"
            b.rimHits == 0 && n > 0 -> "Swish! $n basamak +$pts"
            n > 0 -> "Basket! $n basamak +$pts"
            else -> "Basket! (sırasız) +$pts"
        }
        onScore?.invoke(pts, text, (rimL + rimR) / 2, rimY - 40 * d)
    }

    // ---------- Çizim ----------

    private val flashes = ArrayList<FloatArray>() // [basamak, zaman]
    private val stepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(110, 255, 255, 255)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(170, 255, 200, 90); strokeCap = Paint.Cap.ROUND
    }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8A1E.toInt() }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(220, 230, 60, 40) }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFF6A13.toInt(); strokeCap = Paint.Cap.ROUND }
    private val netPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(220, 245, 245, 245) }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF2A1A10.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()

    fun draw(c: Canvas, w: Int, h: Int) {
        val now = SystemClock.uptimeMillis()
        val course = if (ball != null) frozenChain else chain
        // Merdivene dahil olmayan cisimler: soluk kesikli çerçeve
        otherPaint.strokeWidth = 1.5f * d
        otherPaint.pathEffect = DashPathEffect(floatArrayOf(6 * d, 6 * d), 0f)
        for (o in others) c.drawRect(o, otherPaint)
        // Basamaklar: üst kenar vurgusu, sıra numarası, aralarında ok
        flashes.removeAll { now - it[1] > 400 }
        course.forEachIndexed { i, p ->
            val fl = flashes.lastOrNull { it[0].toInt() == i }?.let { 1f - (now - it[1]) / 400f } ?: 0f
            if (fl > 0f) {
                glowPaint.shader = RadialGradient(p.centerX(), p.top, p.width() * 0.8f,
                    intArrayOf(Color.argb((160 * fl).toInt(), 255, 180, 60), 0), null, Shader.TileMode.CLAMP)
                c.drawCircle(p.centerX(), p.top, p.width() * 0.8f, glowPaint)
            }
            stepPaint.color = 0xFFFF8A1E.toInt()
            stepPaint.alpha = 230
            stepPaint.strokeWidth = (4 + 4 * fl) * d
            c.drawLine(p.left, p.top, p.right, p.top, stepPaint)
            stepPaint.strokeWidth = 1.5f * d
            stepPaint.alpha = 120
            c.drawRect(p, stepPaint)
            val bx = p.centerX()
            val by = p.top - 18 * d
            c.drawCircle(bx, by, 12 * d, badgeFill)
            badgeText.textSize = 14 * d
            c.drawText((i + 1).toString(), bx, by + 5 * d, badgeText)
            if (i > 0) {
                val a = course[i - 1]
                val x0 = a.centerX(); val y0 = a.top - 34 * d
                val x1 = p.centerX(); val y1 = p.top - 34 * d
                arrowPaint.strokeWidth = 2.5f * d
                arrowPaint.pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), (now / 30f) % (14 * d) * -1)
                path.reset()
                path.moveTo(x0, y0)
                path.quadTo((x0 + x1) / 2, min(y0, y1) - 40 * d, x1, y1)
                c.drawPath(path, arrowPaint)
            }
        }
        if (hoopOk) drawHoop(c, now)

        val b = ball
        if (b != null) {
            drawBall(c, b.x, b.y, b.r, b.spin)
        } else if (course.isNotEmpty()) {
            val (lx, ly) = launchPoint(w, h).let { it[0] to it[1] }
            val bob = if (aiming) 0f else sin(now / 400f) * 3 * d
            val bx = lx + pullX * 0.35f
            val by = ly + pullY * 0.35f + bob
            tmp.set(bx - r * 1.2f, ly + r * 1.1f, bx + r * 1.2f, ly + r * 1.5f)
            c.drawOval(tmp, shadowPaint)
            drawBall(c, bx, by, r * 1.5f, 0.4f)
            if (aiming && hypot(pullX, pullY) >= 20 * d) {
                // Tahmini yol: gerçek fizikle, ilk iki çarpmaya kadar (sonrası oyuncunun becerisi)
                val v = launchVelocity()
                val sim = Ball(lx, ly, v[0], v[1], r)
                var hits = 0
                var k = 0
                val dtSim = 1f / 240f
                var acc = 0f
                var wasHit = false
                while (sim.age < 2.2f && sim.y < h + r && hits < 2) {
                    val hitNow = stepBall(sim, dtSim, w, chain, live = false)
                    // Yuvarlanırken art arda gelen temaslar tek sekme sayılır
                    if (hitNow && !wasHit) {
                        hits++
                        dotPaint.alpha = 255
                        c.drawCircle(sim.x, sim.y, 6 * d, dotPaint)
                    }
                    wasHit = hitNow
                    acc += dtSim
                    if (acc >= 0.035f) {
                        acc = 0f
                        k++
                        dotPaint.alpha = (230 - min(k, 40) * 4).coerceAtLeast(60)
                        c.drawCircle(sim.x, sim.y, (3.6f - min(k, 30) * 0.06f) * d, dotPaint)
                    }
                }
            }
        }
    }

    private fun drawHoop(c: Canvas, now: Long) {
        // Pano
        val bl = if (dir > 0) boardX else boardX - 6 * d
        tmp.set(bl, rimY - 95 * d, bl + 6 * d, rimY + 12 * d)
        boardPaint.shader = LinearGradient(tmp.left, tmp.top, tmp.right, tmp.top, Color.argb(235, 250, 250, 250), Color.argb(235, 200, 205, 210), Shader.TileMode.CLAMP)
        c.drawRect(tmp, boardPaint)
        boardEdge.strokeWidth = 1.5f * d
        c.drawRect(tmp, boardEdge)
        // File: çemberden aşağı daralan ipler; sayıda sallanır
        val cx = (rimL + rimR) / 2
        val rw = rimR - rimL
        val sw = netSwing * 10 * d * sin(now / 40f)
        netPaint.strokeWidth = 1.4f * d
        val bottomY = rimY + rw * 0.75f
        for (k in 0..6) {
            val t = k / 6f
            val xTop = rimL + rw * t
            val xBot = cx - rw * 0.32f + rw * 0.64f * t + sw
            c.drawLine(xTop, rimY, xBot, bottomY, netPaint)
            if (k < 6) {
                val xTop2 = rimL + rw * (t + 1f / 6)
                val xBot2 = cx - rw * 0.32f + rw * 0.64f * (t + 1f / 6) + sw
                c.drawLine(xTop, rimY, xBot2, bottomY, netPaint)
                c.drawLine(xTop2, rimY, xBot, bottomY, netPaint)
            }
        }
        // Çember (hafif eğik elips)
        rimPaint.strokeWidth = 4 * d
        tmp.set(rimL, rimY - rw * 0.12f, rimR, rimY + rw * 0.12f)
        c.drawOval(tmp, rimPaint)
    }

    private fun drawBall(c: Canvas, x: Float, y: Float, rr: Float, spin: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(Math.toDegrees(spin.toDouble()).toFloat())
        ballPaint.shader = RadialGradient(-rr * 0.35f, -rr * 0.35f, rr * 1.4f,
            intArrayOf(0xFFFFA45C.toInt(), 0xFFE86A1C.toInt(), 0xFF8A3A0C.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, rr, ballPaint)
        seamPaint.strokeWidth = max(1f, rr * 0.08f)
        c.drawLine(-rr, 0f, rr, 0f, seamPaint)
        c.drawLine(0f, -rr, 0f, rr, seamPaint)
        tmp.set(-rr * 1.6f, -rr, -rr * 0.35f, rr)
        c.drawArc(tmp, -60f, 120f, false, seamPaint)
        tmp.set(rr * 0.35f, -rr, rr * 1.6f, rr)
        c.drawArc(tmp, 120f, 120f, false, seamPaint)
        c.restore()
    }
}
