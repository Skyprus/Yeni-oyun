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

/** Kamera görüntüsündeki tek bir tespit (görüntü pikseli koordinatlarında). */
data class Det(val cls: String, val box: RectF, val score: Float)

/**
 * MediaPipe nesne tanıyıcı (EfficientDet-Lite0, COCO). Mümkünse GPU'da çalışır.
 * Sonuçlar arka plan iş parçacığında [onResult] ile bildirilir.
 */
class Detector private constructor(
    private val detector: ObjectDetector,
    val usingGpu: Boolean,
) {
    private val busy = AtomicBoolean(false)
    private var lastTs = 0L
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
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts
            detector.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (e: Exception) {
            Log.w(TAG, "detect failed", e)
            busy.set(false)
        }
    }

    private fun handle(result: ObjectDetectorResult, image: MPImage) {
        busy.set(false)
        val dets = result.detections().mapNotNull { d ->
            val c = d.categories().firstOrNull() ?: return@mapNotNull null
            Det(c.categoryName(), RectF(d.boundingBox()), c.score())
        }
        lastMs = SystemClock.uptimeMillis() - result.timestampMs()
        onResult?.invoke(dets, image.width, image.height)
    }

    fun close() = detector.close()

    companion object {
        private const val TAG = "Detector"
        private const val MODEL = "efficientdet_lite2.tflite"

        /**
         * Model int8 (işlemci için sıkıştırılmış) olduğundan CPU'da çalıştırılır; GPU delegesi
         * bu modelde bazı telefonlarda hiç sonuç döndürmüyor. Model yüklenemezse null döner.
         */
        fun create(context: Context): Detector? {
            for (delegate in listOf(Delegate.CPU)) {
                try {
                    var holder: Detector? = null
                    val options = ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder()
                                .setModelAssetPath(MODEL)
                                .setDelegate(delegate)
                                .build()
                        )
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setMaxResults(15)
                        .setScoreThreshold(0.25f)
                        .setResultListener { result: ObjectDetectorResult, image: MPImage ->
                            holder?.handle(result, image)
                        }
                        .setErrorListener { e: RuntimeException ->
                            Log.w(TAG, "detector error", e)
                            holder?.busy?.set(false)
                        }
                        .build()
                    val od = ObjectDetector.createFromOptions(context, options)
                    return Detector(od, delegate == Delegate.GPU).also { holder = it }
                } catch (e: Throwable) {
                    Log.w(TAG, "could not create detector with $delegate", e)
                }
            }
            return null
        }
    }
}
