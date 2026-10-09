package com.skyprus.kirgec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** Kamera görüntüsündeki tek bir tespit (görüntü pikseli koordinatlarında). */
data class Det(val cls: String, val box: RectF, val score: Float)

private const val MODEL = "efficientdet_lite2.tflite"
private const val TAG = "Detector"

private fun baseOptions() = BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build()

private fun toDets(result: ObjectDetectorResult, dx: Float, dy: Float): List<Det> =
    result.detections().mapNotNull { d ->
        val c = d.categories().firstOrNull() ?: return@mapNotNull null
        val b = d.boundingBox()
        Det(c.categoryName(), RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy), c.score())
    }

/** Aynı sınıftan, büyük ölçüde örtüşen tespitlerden yalnızca en güvenilirini bırakır. */
fun nms(dets: List<Det>, thr: Float = 0.5f): List<Det> {
    val out = ArrayList<Det>()
    for (d in dets.sortedByDescending { it.score }) {
        if (out.none { it.cls == d.cls && iou(it.box, d.box) > thr }) out += d
    }
    return out
}

fun iou(a: RectF, b: RectF): Float {
    val x1 = max(a.left, b.left); val y1 = max(a.top, b.top)
    val x2 = min(a.right, b.right); val y2 = min(a.bottom, b.bottom)
    val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
    val union = a.width() * a.height() + b.width() * b.height() - inter
    return if (union > 0f) inter / union else 0f
}

/**
 * Sürekli nesne tanıma (EfficientDet-Lite2, COCO, CPU).
 * Portre görüntüyü kareye sıkıştırmak küçük nesneleri kaybettirdiği için kareler sırayla
 * tam görüntü, üst kare ve alt kare olarak işlenir; sonuçlar tam görüntü koordinatına çevrilir.
 */
class Detector private constructor(private val detector: ObjectDetector) {
    private val busy = AtomicBoolean(false)
    private var lastTs = 0L
    private var pass = 0
    @Volatile private var offX = 0f
    @Volatile private var offY = 0f
    @Volatile private var fullW = 0
    @Volatile private var fullH = 0
    var onResult: ((List<Det>, Int, Int) -> Unit)? = null
    /** Son tahminin süresi (ms). */
    @Volatile var lastMs = 0L

    /** CameraX analiz karesi: önceki tahmin bitmediyse kare atlanır (telefon yorulmaz). */
    fun analyze(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val frame: Pair<Bitmap, Int>? = try {
            Pair(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }
        if (frame == null) {
            busy.set(false)
            return
        }
        val (bitmap, rotation) = frame
        try {
            val upright = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, false)
            } else bitmap
            val w = upright.width
            val h = upright.height
            val side = min(w, h)
            // 0: tam görüntü, 1: baş taraftaki kare, 2: son taraftaki kare
            val input = when (pass++ % 3) {
                0 -> { offX = 0f; offY = 0f; upright }
                1 -> { offX = 0f; offY = 0f; Bitmap.createBitmap(upright, 0, 0, side, side) }
                else -> {
                    offX = (w - side).toFloat(); offY = (h - side).toFloat()
                    Bitmap.createBitmap(upright, w - side, h - side, side, side)
                }
            }
            fullW = w; fullH = h
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts
            detector.detectAsync(BitmapImageBuilder(input).build(), ts)
        } catch (e: Exception) {
            Log.w(TAG, "detect failed", e)
            busy.set(false)
        }
    }

    private fun handle(result: ObjectDetectorResult, @Suppress("UNUSED_PARAMETER") image: MPImage) {
        val dets = toDets(result, offX, offY)
        lastMs = SystemClock.uptimeMillis() - result.timestampMs()
        busy.set(false)
        onResult?.invoke(dets, fullW, fullH)
    }

    fun close() = detector.close()

    companion object {
        fun create(context: Context): Detector? = try {
            var holder: Detector? = null
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMaxResults(20)
                .setScoreThreshold(0.25f)
                .setResultListener { result: ObjectDetectorResult, image: MPImage -> holder?.handle(result, image) }
                .setErrorListener { e: RuntimeException ->
                    Log.w(TAG, "detector error", e)
                    holder?.busy?.set(false)
                }
                .build()
            Detector(ObjectDetector.createFromOptions(context, options)).also { holder = it }
        } catch (e: Throwable) {
            Log.w(TAG, "could not create detector", e)
            null
        }
    }
}

/**
 * Ayrıntılı tarama: ekrandaki kareyi yüksek çözünürlükte, örtüşen kare parçalara bölüp
 * her birini ayrı ayrı tarar. Küçük ve uzaktaki nesneleri de bulur; ~1 sn sürer.
 */
class ScanDetector private constructor(private val detector: ObjectDetector) {

    /** @return ekran koordinatında tespitler */
    fun scan(frame: Bitmap): List<Det> {
        val w = frame.width
        val h = frame.height
        val all = ArrayList<Det>()
        // Tam görüntü
        all += run(frame, 0, 0, w, h)
        // Örtüşen kare parçalar (kenarın %60'ı, %35 örtüşme)
        val side = (min(w, h) * 0.6f).toInt().coerceAtLeast(64)
        val step = (side * 0.65f).toInt().coerceAtLeast(32)
        val xs = positions(w, side, step)
        val ys = positions(h, side, step)
        for (y in ys) for (x in xs) all += run(frame, x, y, side, side)
        return nms(all, 0.4f)
    }

    private fun positions(total: Int, side: Int, step: Int): List<Int> {
        if (side >= total) return listOf(0)
        val out = ArrayList<Int>()
        var p = 0
        while (p + side < total) { out += p; p += step }
        out += total - side
        return out
    }

    private fun run(frame: Bitmap, x: Int, y: Int, w: Int, h: Int): List<Det> = try {
        val crop = if (x == 0 && y == 0 && w == frame.width && h == frame.height) frame
        else Bitmap.createBitmap(frame, x, y, w, h)
        val k = min(1f, 640f / max(w, h))
        val input = if (k < 1f) Bitmap.createScaledBitmap(crop, (w * k).toInt(), (h * k).toInt(), true) else crop
        val res = detector.detect(BitmapImageBuilder(input).build())
        toDets(res, 0f, 0f).map {
            Det(it.cls, RectF(it.box.left / k + x, it.box.top / k + y, it.box.right / k + x, it.box.bottom / k + y), it.score)
        }
    } catch (e: Exception) {
        Log.w(TAG, "scan tile failed", e)
        emptyList()
    }

    fun close() = detector.close()

    companion object {
        fun create(context: Context): ScanDetector? = try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(25)
                .setScoreThreshold(0.3f)
                .build()
            ScanDetector(ObjectDetector.createFromOptions(context, options))
        } catch (e: Throwable) {
            Log.w(TAG, "could not create scan detector", e)
            null
        }
    }
}
