package com.skyprus.kirgec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Alev (0), duman (1) ya da kıvılcım (2) parçacığı. */
class FireParticle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val life: Float, val size: Float, val kind: Int,
) {
    var age = 0f
}

/**
 * Molotof yangını. Bir nesneye isabet ettiyse nesne [igniteMs] boyunca yanıp kararır, sonra patlar;
 * ardından enkaz bir süre daha tüter. Boş yere düştüyse yalnızca yerde yanar.
 */
class Fire(
    val x: Float,
    val y: Float,
    val target: Target?,
    val cut: Cutout?,
    val box: RectF?,         // yanan nesnenin ateşlendiği andaki kutusu
    val start: Long,
    val igniteMs: Long,
    val until: Long,
) {
    var exploded = false
    var loopStream = 0
    var emit = 0f
}

/** Gökten düşen dev kaya. Düştükten sonra [until]'e kadar yerinde kalır. */
class Boulder(
    val x: Float,
    val y: Float,
    val r: Float,
    val fallSec: Float,
) {
    var t = 0f
    var restY = y          // kayanın yerleşeceği yükseklik (merkez)
    var landed = false
    var until = 0L
    var target: Target? = null
    var anchor: RectF? = null      // çarptığı hedefin o anki kutusu (hedef kayarsa kaya da kayar)
    var crushed: Cutout? = null    // ezilen nesne (yassılmış hâli çizilir)
    var crushBox: RectF? = null
    val rot = Random.nextFloat() * 360f
    val shape = FloatArray(22).also {
        for (i in 0 until 11) {
            val a = i / 11f * 2f * PI.toFloat()
            val k = 0.78f + Random.nextFloat() * 0.25f
            it[i * 2] = cos(a) * k
            it[i * 2 + 1] = sin(a) * k * 0.85f
        }
    }
}

// ---------- Çizim yardımcıları ----------

/** Yumuşak, ortası parlak dairesel sprite (alev ya da duman için). */
fun softSprite(inner: Int, mid: Int, size: Int = 64): Bitmap {
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(inner, mid, Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
    }
    Canvas(b).drawCircle(size / 2f, size / 2f, size / 2f, p)
    return b
}

private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
private val addPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
private val emberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val spriteDst = RectF()

fun drawFireParticles(c: Canvas, list: List<FireParticle>, flame: Bitmap, smoke: Bitmap) {
    // Önce duman (normal karışım), sonra alevler ve kıvılcımlar (toplamalı, parlak)
    for (p in list) if (p.kind == 1) {
        val k = p.age / p.life
        val s = p.size * (1f + k * 1.8f)
        spriteDst.set(p.x - s, p.y - s, p.x + s, p.y + s)
        spritePaint.alpha = (150 * (1f - k)).toInt().coerceIn(0, 255)
        c.drawBitmap(smoke, null, spriteDst, spritePaint)
    }
    for (p in list) when (p.kind) {
        0 -> {
            val k = p.age / p.life
            val s = p.size * (1f - k * 0.6f)
            spriteDst.set(p.x - s, p.y - s * 1.3f, p.x + s, p.y + s)
            addPaint.alpha = (230 * (1f - k)).toInt().coerceIn(0, 255)
            c.drawBitmap(flame, null, spriteDst, addPaint)
        }
        2 -> {
            emberPaint.color = Color.rgb(255, 180 + Random.nextInt(60), 60)
            emberPaint.alpha = (255 * (1f - p.age / p.life)).toInt().coerceIn(0, 255)
            c.drawCircle(p.x, p.y, p.size, emberPaint)
        }
    }
}

private val rockFill = Paint(Paint.ANTI_ALIAS_FLAG)
private val rockEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(120, 0, 0, 0) }
private val rockCrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(90, 30, 25, 20) }
private val rockPath = Path()

fun drawRock(c: Canvas, x: Float, y: Float, r: Float, rotDeg: Float, shape: FloatArray, alpha: Int, d: Float) {
    c.save()
    c.translate(x, y)
    c.rotate(rotDeg)
    rockPath.reset()
    val n = shape.size / 2
    for (i in 0 until n) {
        val px = shape[i * 2] * r
        val py = shape[i * 2 + 1] * r
        if (i == 0) rockPath.moveTo(px, py) else rockPath.lineTo(px, py)
    }
    rockPath.close()
    rockFill.shader = RadialGradient(-r * 0.35f, -r * 0.4f, r * 1.4f,
        intArrayOf(0xFFB3A898.toInt(), 0xFF6E6457.toInt(), 0xFF3A332B.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    rockFill.alpha = alpha
    c.drawPath(rockPath, rockFill)
    rockEdge.strokeWidth = 2 * d
    rockEdge.alpha = alpha / 2
    c.drawPath(rockPath, rockEdge)
    rockCrack.strokeWidth = 1.6f * d
    rockCrack.alpha = alpha * 90 / 255
    c.drawLine(-r * 0.4f, -r * 0.1f, r * 0.05f, r * 0.2f, rockCrack)
    c.drawLine(r * 0.05f, r * 0.2f, r * 0.35f, r * 0.05f, rockCrack)
    c.drawLine(-r * 0.1f, -r * 0.5f, r * 0.15f, -r * 0.3f, rockCrack)
    c.restore()
}

private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val ragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8CFC0.toInt() }
private val bottlePath = Path()

/** Ağzında yanan bez olan cam şişe (molotof). Orijin şişenin ortası, boyu ~2.6*s. */
fun drawMolotov(c: Canvas, x: Float, y: Float, s: Float, rotDeg: Float, flame: Bitmap, now: Long) {
    c.save()
    c.translate(x, y)
    c.rotate(rotDeg)
    bottlePath.reset()
    bottlePath.moveTo(-0.55f * s, 1.2f * s)
    bottlePath.lineTo(-0.55f * s, -0.2f * s)
    bottlePath.quadTo(-0.55f * s, -0.6f * s, -0.2f * s, -0.75f * s)
    bottlePath.lineTo(-0.2f * s, -1.25f * s)
    bottlePath.lineTo(0.2f * s, -1.25f * s)
    bottlePath.lineTo(0.2f * s, -0.75f * s)
    bottlePath.quadTo(0.55f * s, -0.6f * s, 0.55f * s, -0.2f * s)
    bottlePath.lineTo(0.55f * s, 1.2f * s)
    bottlePath.close()
    glassPaint.shader = LinearGradient(-0.55f * s, 0f, 0.55f * s, 0f,
        intArrayOf(0xCC2E6B3A.toInt(), 0xEE6FBF7A.toInt(), 0xCC1F4A28.toInt()), null, Shader.TileMode.CLAMP)
    c.drawPath(bottlePath, glassPaint)
    // İçindeki sıvı
    glassPaint.shader = null
    glassPaint.color = 0x99D9822B.toInt()
    c.drawRect(-0.5f * s, 0.2f * s, 0.5f * s, 1.15f * s, glassPaint)
    // Bez fitil
    c.drawRect(-0.16f * s, -1.55f * s, 0.16f * s, -1.15f * s, ragPaint)
    // Titreyen alev
    val fl = 1f + 0.15f * sin(now / 45f)
    spriteDst.set(-0.6f * s * fl, -2.6f * s * fl, 0.6f * s * fl, -1.3f * s)
    addPaint.alpha = 255
    c.drawBitmap(flame, null, spriteDst, addPaint)
    c.restore()
}

private val charPaint = Paint(Paint.FILTER_BITMAP_FLAG)

/** Yanan nesnenin silüeti, [k] (0..1) oranında kömürleşmiş koyu tonla. */
fun drawCharred(c: Canvas, cut: Cutout, dst: RectF, k: Float) {
    charPaint.colorFilter = PorterDuffColorFilter(Color.argb((k * 220).toInt().coerceIn(0, 255), 25, 12, 5), PorterDuff.Mode.SRC_ATOP)
    c.drawBitmap(cut.obj, null, dst, charPaint)
}

/** Kayanın altında yassılmış nesne: silüet yüksekliğinin üçte birine ezilmiş, koyulaşmış. */
fun drawCrushed(c: Canvas, cut: Cutout, box: RectF, alpha: Int) {
    val h = box.height() * 0.32f
    spriteDst.set(box.left - box.width() * 0.06f, box.bottom - h, box.right + box.width() * 0.06f, box.bottom)
    charPaint.colorFilter = PorterDuffColorFilter(Color.argb(120, 20, 18, 15), PorterDuff.Mode.SRC_ATOP)
    charPaint.alpha = alpha
    c.drawBitmap(cut.obj, null, spriteDst, charPaint)
    charPaint.alpha = 255
}

/** Kesitin opak bir pikselini rastgele seçer (alevler nesnenin üstünden çıksın diye). */
fun randomOpaquePoint(cut: Cutout, dst: RectF): FloatArray? {
    val b = cut.obj
    repeat(6) {
        val px = Random.nextInt(b.width)
        val py = Random.nextInt(b.height)
        if (Color.alpha(b.getPixel(px, py)) > 128) {
            return floatArrayOf(dst.left + px / b.width.toFloat() * dst.width(), dst.top + py / b.height.toFloat() * dst.height())
        }
    }
    return null
}
