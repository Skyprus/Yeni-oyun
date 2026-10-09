package com.skyprus.odagolf

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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

/**
 * Oda Golf: kamera odayı gösterir, oyuncu kareyi dondurur; odadaki eşyalar engel olur ve
 * top onlardan sektirilerek deliğe sokulur. Bütün oyun [HoleView]'dadır.
 */
class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var hole: HoleView
    private lateinit var sfx: Sfx
    @Volatile private var segmenter: Segmenter? = null
    @Volatile private var scanner: ScanDetector? = null
    // Modeller ve tüm görüntü işleri tek arka plan iş parçacığında: yükleme bitmeden iş başlamaz
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
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
        hole = findViewById(R.id.hole)
        // TextureView tabanlı mod: kare yakalama (getBitmap) güvenilir çalışır
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        sfx = Sfx(this)
        hole.sfx = sfx
        hole.haptics = { vibrate() }
        hole.frameProvider = { previewView.bitmap }
        hole.scanRoom = { frame, done ->
            worker.execute {
                val dets = try { scanner?.scan(frame) ?: emptyList() } catch (e: Throwable) { emptyList() }
                hole.post { done(dets) }
            }
        }
        hole.segmentIn = { frame, x, y, done ->
            worker.execute {
                val cut = try { segmenter?.cut(frame, x, y) } catch (e: Throwable) { null }
                hole.post { done(cut) }
            }
        }

        val controls = findViewById<View>(R.id.controls)
        ViewCompat.setOnApplyWindowInsetsListener(hole) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            hole.insetTop = bars.top
            controls.updatePadding(bottom = bars.bottom)
            insets
        }

        val btnAdd = findViewById<TextView>(R.id.btnAdd)
        btnAdd.setOnClickListener { hole.addMode = !hole.addMode }
        hole.onAddModeChanged = { btnAdd.isSelected = it }
        val btnRamp = findViewById<TextView>(R.id.btnRamp)
        btnRamp.setOnClickListener { hole.rampMode = !hole.rampMode }
        hole.onRampModeChanged = { btnRamp.isSelected = it }
        findViewById<View>(R.id.btnRoom).setOnClickListener {
            hole.addMode = false
            hole.rampMode = false
            hole.newRoom()
        }
        findViewById<View>(R.id.btnNewHole).setOnClickListener {
            hole.addMode = false
            hole.rampMode = false
            hole.newHole(advance = false)
        }
        findViewById<View>(R.id.btnPermission).setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        worker.execute {
            segmenter = Segmenter.create(applicationContext)
            scanner = ScanDetector.create(applicationContext)
            if (scanner == null) hole.post { hole.hint("Eşya tanıma açılamadı — ➕ Eşya ile kendin ekleyebilirsin") }
        }
        hole.onShown()

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
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()
                )
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                previewView.post { focusCenter() }
            } catch (e: Exception) {
                hole.hint("Kamera açılamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focusCenter() {
        val cam = camera ?: return
        if (previewView.width == 0) return
        val point = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun vibrate() {
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vib?.hasVibrator() != true) return
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30, 60, 40), -1))
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.execute {
            segmenter?.close()
            segmenter = null
            scanner?.close()
            scanner = null
        }
        worker.shutdown()
        sfx.release()
    }
}
