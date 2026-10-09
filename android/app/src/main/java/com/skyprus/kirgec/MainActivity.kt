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
    private lateinit var hole: HoleView
    private lateinit var sfx: Sfx
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var detector: Detector? = null
    @Volatile private var segmenter: Segmenter? = null
    @Volatile private var scanner: ScanDetector? = null
    @Volatile private var scanning = false
    private val segExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
        hole = findViewById(R.id.hole)
        // TextureView tabanlı mod: oyun katmanı üstte kalır ve kare yakalama (getBitmap) güvenilir çalışır
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        sfx = Sfx(this)
        game.sfx = sfx
        game.shakeTarget = previewView
        game.frameProvider = { previewView.bitmap }
        game.haptics = { vibrate(it) }
        game.onFocusRequest = { x, y -> focusAt(x, y) }
        game.segmentAt = { x, y, done -> segmentAt(x, y, done) }
        game.onZoom = { factor ->
            camera?.let { cam ->
                val z = cam.cameraInfo.zoomState.value
                if (z != null) {
                    val ratio = (z.zoomRatio * factor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(ratio)
                    game.hint("Yakınlaştırma: ${"%.1f".format(ratio)}x")
                }
            }
        }

        val controls = findViewById<View>(R.id.controls)
        val holeControls = findViewById<View>(R.id.holeControls)
        ViewCompat.setOnApplyWindowInsetsListener(game) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            game.insetTop = bars.top
            hole.insetTop = bars.top
            controls.updatePadding(bottom = bars.bottom)
            holeControls.updatePadding(bottom = bars.bottom)
            insets
        }
        setupHole(controls, holeControls)

        val weaponButtons = mapOf(
            Weapon.BALL to findViewById<TextView>(R.id.btnBall),
            Weapon.STONE to findViewById<TextView>(R.id.btnStone),
            Weapon.SLING to findViewById<TextView>(R.id.btnSling),
            Weapon.BAT to findViewById<TextView>(R.id.btnBat),
            Weapon.WRENCH to findViewById<TextView>(R.id.btnWrench),
            Weapon.MOLOTOV to findViewById<TextView>(R.id.btnMolotov),
            Weapon.BOULDER to findViewById<TextView>(R.id.btnBoulder),
        )
        weaponButtons.getValue(Weapon.BALL).isSelected = true
        for ((w, btn) in weaponButtons) {
            btn.setOnClickListener {
                game.weapon = w
                weaponButtons.forEach { (k, b) -> b.isSelected = k == w }
                game.selectMode = false
                game.drawMode = false
                game.hint(when {
                    w == Weapon.SLING -> "Sapan: parmağını aşağı çek, nişan al, bırak"
                    w == Weapon.MOLOTOV -> "Molotof: arabaya (ya da herhangi bir şeye) fırlat — yanar ve patlar"
                    w == Weapon.BOULDER -> "Kaya: hedefe dokun — gökten dev bir kaya düşer"
                    w == Weapon.BALL -> "Top: fırlat — seçili nesneler varsa aralarında seker"
                    w.thrown -> "${w.label}: yukarı kaydır ya da dokun → fırlat"
                    else -> "${w.label}: vurmak istediğin yere dokun"
                })
            }
        }
        val btnSelect = findViewById<TextView>(R.id.btnSelect)
        btnSelect.setOnClickListener { game.selectMode = !game.selectMode }
        game.onSelectModeChanged = { btnSelect.isSelected = it }
        val btnDraw = findViewById<TextView>(R.id.btnDraw)
        btnDraw.setOnClickListener { game.drawMode = !game.drawMode }
        game.onDrawModeChanged = { btnDraw.isSelected = it }
        val btnScan = findViewById<TextView>(R.id.btnScan)
        btnScan.setOnClickListener { scan(btnScan) }
        val btnBoxes = findViewById<TextView>(R.id.btnBoxes)
        btnBoxes.setOnClickListener {
            game.showBoxes = !game.showBoxes
            btnBoxes.isSelected = !game.showBoxes
        }
        findViewById<View>(R.id.btnPermission).setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        segExecutor.execute {
            segmenter = Segmenter.create(applicationContext)
            scanner = ScanDetector.create(applicationContext)
        }

        // Model kamera açılırken arka planda yüklenir
        analysisExecutor.execute {
            val det = Detector.create(applicationContext)
            det?.onResult = { dets, iw, ih ->
                val ms = det?.lastMs ?: 0L
                game.post { game.ingest(dets, iw, ih, ms) }
            }
            det?.onError = { msg -> game.post { game.info = "⚠ $msg" } }
            detector = det
            runOnUiThread { game.setModelReady(det != null) }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Mini golf modu: oda dondurulur, eşyalar engel olur, top deliğe sokulur. */
    private fun setupHole(controls: View, holeControls: View) {
        hole.sfx = sfx
        hole.haptics = { vibrate(it) }
        hole.frameProvider = { previewView.bitmap }
        hole.scanRoom = { frame, done ->
            // Tarayıcı ve ayırıcı aynı iş parçacığında yüklenir: bu iş, yükleme bittikten sonra çalışır
            segExecutor.execute {
                val dets = try { scanner?.scan(frame) ?: emptyList() } catch (e: Throwable) { emptyList() }
                hole.post { done(dets) }
            }
        }
        hole.segmentIn = { frame, x, y, done ->
            segExecutor.execute {
                val cut = try { segmenter?.cut(frame, x, y) } catch (e: Throwable) { null }
                hole.post { done(cut) }
            }
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
        fun showGolf(on: Boolean) {
            hole.visibility = if (on) View.VISIBLE else View.GONE
            game.visibility = if (on) View.GONE else View.VISIBLE
            holeControls.visibility = if (on) View.VISIBLE else View.GONE
            controls.visibility = if (on) View.GONE else View.VISIBLE
            if (on) hole.onShown()
        }
        findViewById<View>(R.id.btnGolf).setOnClickListener { showGolf(true) }
        findViewById<View>(R.id.btnBreak).setOnClickListener { showGolf(false) }
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
                            // Yüksek çözünürlük: kare kare parçalı tanımada küçük nesneler de seçilebilsin
                            ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
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

    /** Ayrıntılı tarama: ekrandaki kareyi parça parça tarar, kırılabilir eşyaları seçer. */
    private fun scan(button: View) {
        if (scanning) return
        val sc = scanner
        if (sc == null) { game.hint("Tarayıcı henüz hazır değil, birazdan tekrar dene."); return }
        val frame = previewView.bitmap ?: return
        scanning = true
        button.isSelected = true
        game.hint("Taranıyor… telefonu sabit tut")
        segExecutor.execute {
            val dets = try { sc.scan(frame) } catch (e: Throwable) { emptyList() } finally { frame.recycle() }
            game.post {
                scanning = false
                button.isSelected = false
                game.addScan(dets)
            }
        }
    }

    /** Ekrandaki kareyi alır, (x, y)'deki nesneyi arka planda ayırır, sonucu ana iş parçacığına verir. */
    private fun segmentAt(x: Float, y: Float, done: (Cutout?) -> Unit) {
        val frame = previewView.bitmap
        if (frame == null) { done(null); return }
        segExecutor.execute {
            val cut = try {
                segmenter?.cut(frame, x, y)
            } catch (e: Throwable) {
                null
            } finally {
                frame.recycle()
            }
            game.post { done(cut) }
        }
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
        segExecutor.execute {
            segmenter?.close()
            segmenter = null
            scanner?.close()
            scanner = null
        }
        segExecutor.shutdown()
        sfx.release()
    }
}
