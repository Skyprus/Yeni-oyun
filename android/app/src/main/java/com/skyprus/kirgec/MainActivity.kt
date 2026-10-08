package com.skyprus.kirgec

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var game: GameView
    private lateinit var sfx: Sfx
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var detector: Detector? = null
    private var camera: Camera? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionPanel(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        previewView = findViewById(R.id.preview)
        game = findViewById(R.id.game)
        // TextureView tabanlı mod: oyun katmanı üstte kalır ve kare yakalama (getBitmap) güvenilir çalışır
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        sfx = Sfx(this)
        game.sfx = sfx
        game.shakeTarget = previewView
        game.frameProvider = { previewView.bitmap }
        game.haptics = { vibrate(it) }
        game.onFocusRequest = { x, y -> focusAt(x, y) }
        game.onZoom = { factor ->
            camera?.let { cam ->
                val z = cam.cameraInfo.zoomState.value
                if (z != null) {
                    val ratio = (z.zoomRatio * factor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(ratio)
                    game.status = "Yakınlaştırma: ${"%.1f".format(ratio)}x"
                }
            }
        }

        val controls = findViewById<View>(R.id.controls)
        ViewCompat.setOnApplyWindowInsetsListener(game) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            game.insetTop = bars.top
            controls.updatePadding(bottom = bars.bottom)
            insets
        }

        val btnBall = findViewById<TextView>(R.id.btnBall)
        val btnStone = findViewById<TextView>(R.id.btnStone)
        val btnMark = findViewById<TextView>(R.id.btnMark)
        val btnBoxes = findViewById<TextView>(R.id.btnBoxes)
        btnBall.isSelected = true
        btnBall.setOnClickListener {
            game.ammo = Ammo.BALL; btnBall.isSelected = true; btnStone.isSelected = false
        }
        btnStone.setOnClickListener {
            game.ammo = Ammo.STONE; btnStone.isSelected = true; btnBall.isSelected = false
        }
        btnMark.setOnClickListener { game.markMode = !game.markMode }
        game.onMarkModeChanged = { btnMark.isSelected = it }
        btnBoxes.setOnClickListener {
            game.showBoxes = !game.showBoxes
            btnBoxes.isSelected = !game.showBoxes
        }
        findViewById<View>(R.id.btnPermission).setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Model kamera açılırken arka planda yüklenir
        analysisExecutor.execute {
            val det = Detector.create(applicationContext)
            det?.onResult = { dets, iw, ih ->
                val ms = det?.lastMs ?: 0L
                game.post { game.ingest(dets, iw, ih, ms) }
            }
            detector = det
            runOnUiThread { game.setModelReady(det != null) }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionPanel(show: Boolean) {
        findViewById<View>(R.id.permissionPanel).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun startCamera() {
        showPermissionPanel(false)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Toast.makeText(this, "Kamera başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                return@addListener
            }
            // Önizleme ve analiz aynı en-boy oranında: kutular ekranla birebir örtüşsün
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val det = detector
                if (det == null) proxy.close() else det.analyze(proxy)
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                // Açılışta ortaya bir kez odaklan; sonra kamera sürekli otomatik odağa döner
                previewView.post { focusAt(previewView.width / 2f, previewView.height / 2f) }
                game.status = if (detector == null) "Nesne tanıma modeli yükleniyor…" else game.status
            } catch (e: Exception) {
                game.status = "Kamera açılamadı: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        if (previewView.width == 0) return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun vibrate(m: Material) {
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vib?.hasVibrator() != true) return
        val effect = if (m == Material.ELECTRONIC) {
            VibrationEffect.createWaveform(longArrayOf(0, 40, 30, 60), -1)
        } else {
            VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vib.vibrate(effect)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.execute {
            detector?.close()
            detector = null
        }
        analysisExecutor.shutdown()
        sfx.release()
    }
}
