package com.skyprus.kirgec

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private fun rand(a: Float, b: Float) = a + Random.nextFloat() * (b - a)

/** Kamera görüntüsünden kesilmiş nesne parçası. */
class Shard(
    val img: Bitmap,
    val path: Path,
    val lx: Float,
    val ly: Float,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var vr: Float,
    val life: Float,
) {
    var rot = 0f
    var age = 0f
}

/**
 * Ekranda görünen kamera karesinden nesnenin kutusunu keser. Köşelerdeki arka plan
 * elips maske ile atılır; kenar piksellerinin ortalaması nesnenin arkasındaki zemin rengidir.
 */
fun snapshot(frame: Bitmap, box: RectF): Cutout? {
    val l = box.left.roundToInt().coerceIn(0, frame.width - 2)
    val t = box.top.roundToInt().coerceIn(0, frame.height - 2)
    val r = box.right.roundToInt().coerceIn(l + 2, frame.width)
    val b = box.bottom.roundToInt().coerceIn(t + 2, frame.height)
    val w = r - l
    val h = b - t
    if (w < 4 || h < 4) return null
    val crop = Bitmap.createBitmap(frame, l, t, w, h)

    var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
    fun sample(x: Int, y: Int) {
        val c = crop.getPixel(x, y)
        rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
    }
    val step = max(1, max(w, h) / 40)
    for (x in 0 until w step step) sample(x, 0)
    for (y in 0 until h step step) { sample(0, y); sample(w - 1, y) }
    val bg = Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())

    val masked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(crop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    Canvas(masked).drawOval(RectF(-w * 0.06f, -h * 0.06f, w * 1.06f, h * 1.06f), paint)
    crop.recycle()
    return Cutout(masked, RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()), bg, null, null)
}

/** Çarpma noktasından yayılan radyal kırık desenine göre parçalar üretir. */
fun makeShards(cut: Cutout, impactX: Float, impactY: Float, power: Float, d: Float): List<Shard> {
    val img = cut.obj
    val box = cut.box
    val w = img.width.toFloat()
    val h = img.height.toFloat()
    val ix = (impactX - box.left).coerceIn(0f, w)
    val iy = (impactY - box.top).coerceIn(0f, h)
    val bigR = hypot(w, h) * 1.1f
    val sectors = Random.nextInt(9, 15)
    val angles = (0 until sectors).map { (it + rand(-0.3f, 0.3f)) / sectors * 2f * PI.toFloat() }.sorted()
    val rings = floatArrayOf(0f, bigR * rand(0.08f, 0.14f), bigR * rand(0.22f, 0.32f), bigR * rand(0.45f, 0.55f), bigR)

    fun pt(a: Float, r: Float) = floatArrayOf(ix + cos(a) * r, iy + sin(a) * r)

    val out = ArrayList<Shard>()
    for (i in 0 until sectors) {
        val a0 = angles[i]
        val a1 = angles[(i + 1) % sectors] + if (i == sectors - 1) 2f * PI.toFloat() else 0f
        for (j in 0 until rings.size - 1) {
            val r0 = rings[j]
            val r1 = rings[j + 1]
            val am = (a0 + a1) / 2f + rand(-0.1f, 0.1f)
            val poly = if (r0 == 0f) {
                listOf(floatArrayOf(ix, iy), pt(a0, r1), pt(am, r1 * rand(0.9f, 1.1f)), pt(a1, r1))
            } else {
                listOf(pt(a0, r0), pt(a0, r1), pt(am, r1 * rand(0.9f, 1.1f)), pt(a1, r1), pt(a1, r0))
            }
            var cx = 0f; var cy = 0f
            for (p in poly) { cx += p[0]; cy += p[1] }
            cx /= poly.size; cy /= poly.size
            // Kutunun tamamen dışındaki parçaları atla
            if (cx < -w * 0.1f || cx > w * 1.1f || cy < -h * 0.1f || cy > h * 1.1f) continue
            val path = Path()
            poly.forEachIndexed { k, p ->
                if (k == 0) path.moveTo(p[0] - cx, p[1] - cy) else path.lineTo(p[0] - cx, p[1] - cy)
            }
            path.close()
            val dx = cx - ix
            val dy = cy - iy
            val dist = hypot(dx, dy).coerceAtLeast(1f)
            val speed = rand(150f, 520f) * power * d * (1.2f - min(1f, dist / bigR))
            out += Shard(
                img, path, cx, cy,
                x = box.left + cx, y = box.top + cy,
                vx = dx / dist * speed + rand(-60f, 60f) * d,
                vy = dy / dist * speed - rand(80f, 260f) * power * d,
                vr = rand(-8f, 8f),
                life = rand(1.1f, 1.8f),
            )
        }
    }
    return out
}

fun updateShards(shards: MutableList<Shard>, dt: Float, floorY: Float, d: Float) {
    val iter = shards.iterator()
    while (iter.hasNext()) {
        val s = iter.next()
        s.age += dt
        s.vy += 1400f * d * dt
        s.x += s.vx * dt
        s.y += s.vy * dt
        s.rot += s.vr * dt
        if (s.y > floorY && s.vy > 0) {
            s.vy *= -0.3f; s.vx *= 0.6f; s.vr *= 0.5f; s.y = floorY
        }
        if (s.age >= s.life) iter.remove()
    }
}

private val shardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.argb(140, 255, 255, 255)
    strokeWidth = 1.5f
}
private val shardPaint = Paint(Paint.FILTER_BITMAP_FLAG)

fun drawShards(c: Canvas, shards: List<Shard>) {
    for (s in shards) {
        val a = (1f - (s.age / s.life).pow(3)).coerceIn(0f, 1f)
        val alpha = (a * 255).toInt()
        c.save()
        c.translate(s.x, s.y)
        c.rotate(Math.toDegrees(s.rot.toDouble()).toFloat())
        c.save()
        c.clipPath(s.path)
        shardPaint.alpha = alpha
        c.drawBitmap(s.img, -s.lx, -s.ly, shardPaint)
        c.restore()
        shardEdge.alpha = (alpha * 0.55f).toInt()
        c.drawPath(s.path, shardEdge)
        c.restore()
    }
}

// ---------- Çatlaklar ----------

/** Çatlak çizgileri; noktalar kutuya göre 0..1 aralığında: [x0,y0,x1,y1,...] */
fun makeCracks(nx: Float, ny: Float, count: Int = 7): List<FloatArray> = List(count) { i ->
    var a = i.toFloat() / count * 2f * PI.toFloat() + rand(-0.3f, 0.3f)
    var x = nx
    var y = ny
    val steps = Random.nextInt(3, 7)
    val pts = FloatArray((steps + 1) * 2)
    pts[0] = x; pts[1] = y
    for (k in 1..steps) {
        a += rand(-0.5f, 0.5f)
        val len = rand(0.05f, 0.14f)
        x += cos(a) * len
        y += sin(a) * len
        pts[k * 2] = x; pts[k * 2 + 1] = y
    }
    pts
}

private val crackDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; color = Color.argb(150, 0, 0, 0); strokeJoin = Paint.Join.ROUND
}
private val crackLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; color = Color.argb(230, 255, 255, 255); strokeJoin = Paint.Join.ROUND
}
private val crackPath = Path()

fun drawCracks(c: Canvas, box: RectF, cracks: List<FloatArray>, d: Float) {
    c.save()
    c.clipRect(box)
    crackPath.reset()
    for (line in cracks) {
        for (k in 0 until line.size / 2) {
            val px = box.left + line[k * 2] * box.width()
            val py = box.top + line[k * 2 + 1] * box.height()
            if (k == 0) crackPath.moveTo(px, py) else crackPath.lineTo(px, py)
        }
    }
    crackDark.strokeWidth = 3f * d
    crackLight.strokeWidth = 1.2f * d
    c.drawPath(crackPath, crackDark)
    c.drawPath(crackPath, crackLight)
    c.restore()
}

// ---------- Enkaz ----------

class Debris(val x: Float, val y: Float, val s: Float, val r: Float, val k: Int)

/**
 * Kırılan nesnenin bıraktığı iz. [patch] varsa nesnenin yeri çevresinden doldurulmuş görüntüyle örtülür;
 * yoksa zemin rengiyle yumuşak bir örtü çizilir. [anchor], nesne takip ediliyorsa kutusunun kırılma
 * anındaki hâlidir: kutu kaydıkça örtü de onunla birlikte kayar.
 */
class Wreck(
    val start: Long,
    val until: Long,
    val bg: Int,
    val debrisColor: Int,
    val box: RectF,
    val patch: Bitmap? = null,
    val patchBox: RectF? = null,
) {
    val debris = List(14) {
        Debris(rand(0.1f, 0.9f), rand(0.85f, 1f), rand(0.03f, 0.08f), rand(0f, PI.toFloat()), Random.nextInt(3, 6))
    }
}

private val wreckFill = Paint(Paint.ANTI_ALIAS_FLAG)
private val patchPaint = Paint(Paint.FILTER_BITMAP_FLAG)
private val patchDst = RectF()
private val debrisFill = Paint(Paint.ANTI_ALIAS_FLAG)
private val debrisEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
private val debrisPath = Path()
private val ovalRect = RectF()

/**
 * Kırılan nesnenin yerini örter ve altına enkaz yığını çizer.
 * @param box nesnenin şu anki kutusu (takip ediliyorsa kayar, değilse kırılma anındaki kutu)
 */
fun drawWreck(c: Canvas, w: Wreck, box: RectF, now: Long, d: Float) {
    val fadeIn = min(1f, (now - w.start) / 120f)
    val fadeOut = min(1f, (w.until - now) / 800f)
    val a = min(fadeIn, fadeOut).coerceIn(0f, 1f)
    if (a <= 0f) return
    val patch = w.patch
    val pb = w.patchBox
    if (patch != null && pb != null) {
        val dx = box.left - w.box.left
        val dy = box.top - w.box.top
        patchDst.set(pb.left + dx, pb.top + dy, pb.right + dx, pb.bottom + dy)
        patchPaint.alpha = (a * 255).toInt()
        c.drawBitmap(patch, null, patchDst, patchPaint)
        drawDebris(c, w, box, a, d)
        return
    }
    val cx = box.centerX()
    val cy = box.centerY()
    val rr = Color.red(w.bg); val gg = Color.green(w.bg); val bb = Color.blue(w.bg)
    wreckFill.shader = RadialGradient(
        cx, cy, max(box.width(), box.height()) * 0.6f,
        intArrayOf(Color.argb(247, rr, gg, bb), Color.argb(230, rr, gg, bb), Color.argb(0, rr, gg, bb)),
        floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
    )
    wreckFill.alpha = (a * 255).toInt()
    ovalRect.set(cx - box.width() * 0.62f, cy - box.height() * 0.62f, cx + box.width() * 0.62f, cy + box.height() * 0.62f)
    c.drawOval(ovalRect, wreckFill)
    drawDebris(c, w, box, a, d)
}

private fun drawDebris(c: Canvas, w: Wreck, box: RectF, a: Float, d: Float) {
    debrisFill.color = w.debrisColor
    debrisFill.alpha = (Color.alpha(w.debrisColor) * a).toInt()
    debrisEdge.color = Color.WHITE
    debrisEdge.alpha = (150 * a).toInt()
    debrisEdge.strokeWidth = d
    for (p in w.debris) {
        val px = box.left + p.x * box.width()
        val py = box.top + p.y * box.height()
        val sz = p.s * max(box.width(), 60f * d)
        debrisPath.reset()
        for (i in 0 until p.k) {
            val ang = p.r + i.toFloat() / p.k * 2f * PI.toFloat()
            val rad = sz * if (i % 2 == 1) 0.6f else 1f
            val x = px + cos(ang) * rad
            val y = py + sin(ang) * rad * 0.6f
            if (i == 0) debrisPath.moveTo(x, y) else debrisPath.lineTo(x, y)
        }
        debrisPath.close()
        c.drawPath(debrisPath, debrisFill)
        c.drawPath(debrisPath, debrisEdge)
    }
}
