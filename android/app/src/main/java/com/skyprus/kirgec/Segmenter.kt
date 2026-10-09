package com.skyprus.kirgec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Kırılacak nesnenin kamera karesinden kesilmiş hâli.
 * @param obj nesnenin pikselleri (nesne dışı saydam), [box] boyutunda
 * @param patch nesnenin yerini örtecek, çevresinden doldurulmuş arka plan (kenarları yumuşak), [patchBox] boyutunda
 */
class Cutout(
    val obj: Bitmap,
    val box: RectF,
    val bg: Int,
    val patch: Bitmap?,
    val patchBox: RectF?,
)

/**
 * Dokunulan noktadaki nesneyi, ne olduğunu bilmeden, tam şekliyle ayırır
 * (MediaPipe Interactive Segmenter, "magic touch" modeli). Arka plan iş parçacığında çağrılmalıdır.
 */
class Segmenter private constructor(private val seg: InteractiveSegmenter) {

    /**
     * @param frame ekranda görünen kare (ekran pikselleri)
     * @return nesne bulunamazsa ya da seçilen alan duvar/zemin gibi çok büyükse null
     */
    fun cut(frame: Bitmap, x: Float, y: Float): Cutout? {
        val fw = frame.width
        val fh = frame.height
        if (fw < 8 || fh < 8) return null
        val k = WORK / max(fw, fh).toFloat()
        val sw = (fw * k).roundToInt().coerceAtLeast(8)
        val sh = (fh * k).roundToInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(frame, sw, sh, true)

        val nx = (x / fw).coerceIn(0f, 1f)
        val ny = (y / fh).coerceIn(0f, 1f)
        val result = try {
            seg.segment(
                BitmapImageBuilder(small).build(),
                InteractiveSegmenter.RegionOfInterest.create(NormalizedKeypoint.create(nx, ny)),
            )
        } catch (e: Exception) {
            Log.w(TAG, "segment failed", e)
            return null
        }
        val maskImage = result.categoryMask().orElse(null) ?: return null
        val mw = maskImage.width
        val mh = maskImage.height
        val buf = ByteBufferExtractor.extract(maskImage)
        buf.rewind()
        val raw = ByteArray(mw * mh)
        buf.get(raw, 0, min(raw.size, buf.remaining()))

        // Maskede hangi değerin "nesne" olduğu sürüme göre değişebilir: dokunulan nokta nesnenin
        // üzerinde olduğundan, o noktanın çevresindeki çoğunluk değeri nesne kabul edilir.
        val kx = (nx * (mw - 1)).roundToInt()
        val ky = (ny * (mh - 1)).roundToInt()
        val counts = HashMap<Int, Int>()
        for (yy in max(0, ky - 2)..min(mh - 1, ky + 2)) for (xx in max(0, kx - 2)..min(mw - 1, kx + 2)) {
            val v = raw[yy * mw + xx].toInt() and 0xFF
            counts[v] = (counts[v] ?: 0) + 1
        }
        val fgVal = counts.maxByOrNull { it.value }?.key ?: return null

        // Dokunulan noktadan başlayarak bağlı nesne bölgesini doldur (kopuk lekeleri at)
        val fg = BooleanArray(mw * mh)
        val stack = IntArray(mw * mh)
        var sp = 0
        var count = 0
        var minX = mw; var minY = mh; var maxX = -1; var maxY = -1
        val start = ky * mw + kx
        if ((raw[start].toInt() and 0xFF) != fgVal) return null
        stack[sp++] = start
        fg[start] = true
        while (sp > 0) {
            val i = stack[--sp]
            val px = i % mw
            val py = i / mw
            count++
            if (px < minX) minX = px; if (px > maxX) maxX = px
            if (py < minY) minY = py; if (py > maxY) maxY = py
            if (px > 0) push(i - 1, raw, fg, fgVal, stack, sp).also { sp = it }
            if (px < mw - 1) push(i + 1, raw, fg, fgVal, stack, sp).also { sp = it }
            if (py > 0) push(i - mw, raw, fg, fgVal, stack, sp).also { sp = it }
            if (py < mh - 1) push(i + mw, raw, fg, fgVal, stack, sp).also { sp = it }
        }
        val frac = count.toFloat() / (mw * mh)
        if (frac < MIN_FRAC || frac > MAX_FRAC) return null
        // İki ya da daha fazla ekran kenarına dayanan geniş alan: duvar, zemin, masa yüzeyi — kırılmaz
        val edges = listOf(minX <= 1, minY <= 1, maxX >= mw - 2, maxY >= mh - 2).count { it }
        if (edges >= 2 && frac > 0.06f) return null
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1

        // Maske koordinatı → ekran koordinatı
        val sx = fw / mw.toFloat()
        val sy = fh / mh.toFloat()
        val box = Rect(
            (minX * sx).toInt().coerceIn(0, fw - 2), (minY * sy).toInt().coerceIn(0, fh - 2),
            ((maxX + 1) * sx).roundToInt().coerceIn(2, fw), ((maxY + 1) * sy).roundToInt().coerceIn(2, fh),
        )
        if (box.width() < 4 || box.height() < 4) return null

        // Nesnenin pikselleri: kare × yumuşak maske
        val maskSmall = maskBitmap(fg, mw, minX, minY, bw, bh, dilate = 0)
        val maskBig = Bitmap.createScaledBitmap(maskSmall, box.width(), box.height(), true)
        val obj = Bitmap.createBitmap(frame, box.left, box.top, box.width(), box.height())
            .copy(Bitmap.Config.ARGB_8888, true)
        Canvas(obj).drawBitmap(maskBig, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })

        // Arka plan: nesnenin yeri çevresinden doldurulur (difüzyonla boşluk doldurma)
        val pad = max(4, (max(bw, bh) * 0.2f).roundToInt())
        val rx0 = max(0, minX - pad); val ry0 = max(0, minY - pad)
        val rx1 = min(mw - 1, maxX + pad); val ry1 = min(mh - 1, maxY + pad)
        val rw = rx1 - rx0 + 1
        val rh = ry1 - ry0 + 1
        val smallScaled = if (small.width == mw && small.height == mh) small
        else Bitmap.createScaledBitmap(small, mw, mh, true)
        val px = IntArray(rw * rh)
        smallScaled.getPixels(px, 0, rw, rx0, ry0, rw, rh)
        val holeBig = dilate(fg, mw, mh, 2)
        val hole = BooleanArray(rw * rh) { i -> holeBig[(ry0 + i / rw) * mw + rx0 + i % rw] }
        val bg = averageOutside(px, hole)
        inpaint(px, hole, rw, rh, bg)
        val patchSmall = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        // Örtü yalnızca deliğin biraz dışına kadar uzanır; kenarları yukarı ölçeklemede yumuşar
        val cover = dilate(fg, mw, mh, 4)
        for (i in px.indices) {
            val gx = rx0 + i % rw
            val gy = ry0 + i / rw
            val inCover = cover[gy * mw + gx]
            val a = if (holeBig[gy * mw + gx]) 255 else if (inCover) 170 else 0
            px[i] = (px[i] and 0x00FFFFFF) or (a shl 24)
        }
        patchSmall.setPixels(px, 0, rw, 0, 0, rw, rh)
        val patchBox = RectF(rx0 * sx, ry0 * sy, (rx1 + 1) * sx, (ry1 + 1) * sy)
        val patch = Bitmap.createScaledBitmap(
            patchSmall,
            patchBox.width().roundToInt().coerceAtLeast(2),
            patchBox.height().roundToInt().coerceAtLeast(2),
            true,
        )
        return Cutout(obj, RectF(box), bg, patch, patchBox)
    }

    fun close() = seg.close()

    companion object {
        private const val TAG = "Segmenter"
        private const val MODEL = "magic_touch.tflite"
        private const val WORK = 512f       // segmentasyon çözünürlüğü (uzun kenar)
        private const val MIN_FRAC = 0.0015f // çok küçük: büyük ihtimalle hatalı seçim
        private const val MAX_FRAC = 0.25f   // çok büyük: duvar/zemin, kırılmaz

        fun create(context: Context): Segmenter? = try {
            val options = InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
            Segmenter(InteractiveSegmenter.createFromOptions(context, options))
        } catch (e: Throwable) {
            Log.w(TAG, "could not create segmenter", e)
            null
        }

        private fun push(j: Int, raw: ByteArray, fg: BooleanArray, v: Int, stack: IntArray, sp: Int): Int {
            if (fg[j] || (raw[j].toInt() and 0xFF) != v) return sp
            fg[j] = true
            stack[sp] = j
            return sp + 1
        }

        private fun maskBitmap(fg: BooleanArray, mw: Int, x0: Int, y0: Int, w: Int, h: Int, dilate: Int): Bitmap {
            val src = if (dilate > 0) dilate(fg, mw, fg.size / mw, dilate) else fg
            val px = IntArray(w * h) { i -> if (src[(y0 + i / w) * mw + x0 + i % w]) Color.WHITE else 0 }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        /** Maskeyi r piksel genişletir (kare çekirdek: önce yatay, sonra dikey geçiş). */
        fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
            val tmp = BooleanArray(m.size)
            for (y in 0 until h) {
                var last = -100000
                for (x in 0 until w) {
                    if (m[y * w + x]) last = x
                    if (x - last <= r) tmp[y * w + x] = true
                }
                last = 100000
                for (x in w - 1 downTo 0) {
                    if (m[y * w + x]) last = x
                    if (last - x <= r) tmp[y * w + x] = true
                }
            }
            val out = BooleanArray(m.size)
            for (x in 0 until w) {
                var last = -100000
                for (y in 0 until h) {
                    if (tmp[y * w + x]) last = y
                    if (y - last <= r) out[y * w + x] = true
                }
                last = 100000
                for (y in h - 1 downTo 0) {
                    if (tmp[y * w + x]) last = y
                    if (last - y <= r) out[y * w + x] = true
                }
            }
            return out
        }

        private fun averageOutside(px: IntArray, hole: BooleanArray): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (i in px.indices) if (!hole[i]) {
                val c = px[i]; r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
            if (n == 0) return Color.DKGRAY
            return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }

        /**
         * Deliği dış kenarından içeri doğru, bilinen komşuların ortalamasıyla katman katman doldurur,
         * sonra hafif yumuşatıp az gürültü ekler (düz renk lekesi gibi görünmesin).
         */
        fun inpaint(px: IntArray, hole: BooleanArray, w: Int, h: Int, fallback: Int) {
            val r = FloatArray(px.size) { Color.red(px[it]).toFloat() }
            val g = FloatArray(px.size) { Color.green(px[it]).toFloat() }
            val b = FloatArray(px.size) { Color.blue(px[it]).toFloat() }
            val known = BooleanArray(px.size) { !hole[it] }
            var remaining = hole.count { it }
            val newly = IntArray(px.size)
            var guard = 0
            while (remaining > 0 && guard++ < 2000) {
                var nNew = 0
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (known[i]) continue
                    var sr = 0f; var sg = 0f; var sb = 0f; var c = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val xx = x + dx; val yy = y + dy
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                        val j = yy * w + xx
                        if (known[j]) { sr += r[j]; sg += g[j]; sb += b[j]; c++ }
                    }
                    if (c > 0) {
                        r[i] = sr / c; g[i] = sg / c; b[i] = sb / c
                        newly[nNew++] = i
                    }
                }
                if (nNew == 0) break
                for (k in 0 until nNew) known[newly[k]] = true
                remaining -= nNew
            }
            // Ulaşılamayan pikseller (ör. tüm bölge delikse)
            for (i in px.indices) if (!known[i]) {
                r[i] = Color.red(fallback).toFloat(); g[i] = Color.green(fallback).toFloat(); b[i] = Color.blue(fallback).toFloat()
            }
            // Harmonik dolgu: delik, kenarlarındaki renkler arasında en pürüzsüz geçişle doldurulur
            // (Laplace denklemi, yerinde Gauss-Seidel). Kenardan içe doldurmanın çizgi izlerini giderir.
            repeat(300) {
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x
                    if (!hole[i]) continue
                    r[i] = (r[i - 1] + r[i + 1] + r[i - w] + r[i + w]) * 0.25f
                    g[i] = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w]) * 0.25f
                    b[i] = (b[i - 1] + b[i + 1] + b[i - w] + b[i + w]) * 0.25f
                }
            }
            // Doku: aynı satırda deliğin hemen dışındaki bölgenin ince ayrıntısı (piksel − 3x3 ortalaması)
            // ayna gibi yansıtılarak eklenir; mermer damarı, kumaş dokusu gibi desenler kabaca devam eder.
            fun hp(ch: Int, q: Int): Float {
                val qx = q % w
                val qy = q / w
                if (qx < 1 || qy < 1 || qx >= w - 1 || qy >= h - 1) return 0f
                var sum = 0
                for (dy in -1..1) for (dx in -1..1) sum += (px[q + dy * w + dx] shr ch) and 0xFF
                return ((px[q] shr ch) and 0xFF) - sum / 9f
            }
            val outPx = IntArray(px.size)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (!hole[i]) { outPx[i] = px[i]; continue }
                var l = x
                while (l >= 0 && hole[y * w + l]) l--
                var rr = x
                while (rr < w && hole[y * w + rr]) rr++
                var src = -1
                var best = Int.MAX_VALUE
                if (l >= 0) { val m = l - (x - l); if (m >= 1 && x - l < best && !hole[y * w + m]) { best = x - l; src = y * w + m } }
                if (rr < w) { val m = rr + (rr - x); if (m < w - 1 && rr - x < best && !hole[y * w + m]) { src = y * w + m } }
                val tr = if (src >= 0) hp(16, src) * 0.85f else 0f
                val tg = if (src >= 0) hp(8, src) * 0.85f else 0f
                val tb = if (src >= 0) hp(0, src) * 0.85f else 0f
                outPx[i] = Color.rgb(
                    (r[i] + tr).roundToInt().coerceIn(0, 255),
                    (g[i] + tg).roundToInt().coerceIn(0, 255),
                    (b[i] + tb).roundToInt().coerceIn(0, 255),
                )
            }
            System.arraycopy(outPx, 0, px, 0, px.size)
        }
    }
}
