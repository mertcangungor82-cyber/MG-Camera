package com.mg.camera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mg.camera.camera.CameraController
import com.mg.camera.camera.DeviceCapabilityScanner
import com.mg.camera.databinding.ActivityMainBinding
import com.mg.camera.model.AiLevel
import com.mg.camera.model.CameraMode
import com.mg.camera.model.CaptureSettings
import java.io.File
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("mg_camera", Context.MODE_PRIVATE) }

    private var currentMode = CameraMode.PHOTO
    private var aiLevel = AiLevel.AI
    private var lastSavedUri: Uri? = null
    private var lastCaptureIsVideo = false
    private var recording = false
    private var videoPaused = false
    private var recordStartedAt = 0L

    private var hdrEnabled = true
    private var gridEnabled = true
    private var keepOriginal = false
    private var timerSeconds = 0
    private var evMin = 0
    private var evMax = 0
    private var evStep = 0f
    private var userOpenedExposurePanel = false

    private val videoTicker = object : Runnable {
        override fun run() {
            if (!recording) return
            val elapsed = ((System.currentTimeMillis() - recordStartedAt) / 1000L).coerceAtLeast(0L)
            val mm = elapsed / 60
            val ss = elapsed % 60
            binding.statusText.text = if (videoPaused) {
                "PAUSED • %02d:%02d".format(Locale.US, mm, ss)
            } else {
                "REC • %02d:%02d".format(Locale.US, mm, ss)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) startCamera() else toast(getString(R.string.permission_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        loadPreferences()
        applyPreferenceUi()

        cameraController = CameraController(this, this, binding.previewView).apply {
            onZoomChanged = { zoom, max ->
                binding.zoomReadout.text = "%.1f×".format(Locale.US, zoom)
                binding.zoomReadout.visibility = if (abs(zoom - 1f) > 0.03f) View.VISIBLE else View.GONE
                updateZoomChips(zoom)
                if (!recording) binding.statusText.text = "%.1f× • max %.1f×".format(Locale.US, zoom, max)
            }
            onStatus = {
                if (!recording) binding.statusText.text = it
            }
            onExposureInfo = { current, min, max, step ->
                evMin = min
                evMax = max
                evStep = step
                binding.exposureSeek.isEnabled = max > min
                binding.exposureSeek.max = (max - min).coerceAtLeast(1)
                val progress = (current - min).coerceIn(0, binding.exposureSeek.max)
                if (binding.exposureSeek.progress != progress) binding.exposureSeek.progress = progress
                updateExposureLabel(current)
            }
        }

        setupControls()
        setupGestures()
        setupExposureControl()
        requestPermissionsOrStart()
    }

    private fun loadPreferences() {
        hdrEnabled = prefs.getBoolean("hdr", true)
        gridEnabled = prefs.getBoolean("grid", true)
        keepOriginal = prefs.getBoolean("keep_original", false)
        timerSeconds = prefs.getInt("timer", 0)
        aiLevel = runCatching { AiLevel.valueOf(prefs.getString("ai", AiLevel.AI.name) ?: AiLevel.AI.name) }
            .getOrDefault(AiLevel.AI)
    }

    private fun applyPreferenceUi() {
        binding.gridView.visibility = if (gridEnabled) View.VISIBLE else View.GONE
        updateHdrUi()
        updateTimerUi()
        updateAiUi()
    }

    private fun requestPermissionsOrStart() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            startCamera()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun startCamera() {
        cameraController.start(
            onReady = { binding.statusText.text = "M&G Camera • kamera hazır" },
            onError = { showError(it) }
        )
    }

    private fun setupControls() {
        binding.flashButton.setOnClickListener {
            val state = cameraController.cycleFlash()
            binding.flashButton.text = "⚡ ${state.label}"
        }

        binding.hdrButton.setOnClickListener {
            if (currentMode == CameraMode.PRO || currentMode == CameraMode.VIDEO) {
                toast("HDR bu modda devre dışı")
                return@setOnClickListener
            }
            hdrEnabled = !hdrEnabled
            prefs.edit().putBoolean("hdr", hdrEnabled).apply()
            updateHdrUi()
        }

        binding.aiLevelButton.setOnClickListener {
            if (currentMode == CameraMode.PRO || currentMode == CameraMode.VIDEO) return@setOnClickListener
            aiLevel = aiLevel.next()
            prefs.edit().putString("ai", aiLevel.name).apply()
            updateAiUi()
        }

        binding.timerButton.setOnClickListener {
            if (recording) return@setOnClickListener
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 5
                5 -> 10
                else -> 0
            }
            prefs.edit().putInt("timer", timerSeconds).apply()
            updateTimerUi()
        }

        binding.evButton.setOnClickListener {
            userOpenedExposurePanel = binding.exposurePanel.visibility != View.VISIBLE
            binding.exposurePanel.visibility = if (userOpenedExposurePanel) View.VISIBLE else View.GONE
        }

        binding.settingsButton.setOnClickListener { showSettings() }
        binding.settingsButton.setOnLongClickListener {
            showDeviceProfile()
            true
        }

        val modeMap = linkedMapOf(
            binding.modeNight to CameraMode.NIGHT,
            binding.modePortrait to CameraMode.PORTRAIT,
            binding.modePhoto to CameraMode.PHOTO,
            binding.modeAi to CameraMode.AI,
            binding.modeZoom to CameraMode.SUPER_ZOOM,
            binding.modeVideo to CameraMode.VIDEO,
            binding.modePro to CameraMode.PRO
        )
        modeMap.forEach { (view, mode) ->
            view.setOnClickListener {
                if (recording) return@setOnClickListener
                currentMode = mode
                cameraController.setMode(mode)
                updateModeChips(modeMap)
                updateModeUi(mode)
            }
        }

        val zoomMap = linkedMapOf(
            binding.zoom1 to 1f,
            binding.zoom2 to 2f,
            binding.zoom3 to 3f,
            binding.zoom5 to 5f,
            binding.zoom10 to 10f
        )
        zoomMap.forEach { (view, zoom) ->
            view.setOnClickListener { cameraController.setZoom(zoom) }
        }

        binding.switchCameraButton.setOnClickListener { cameraController.switchCamera() }
        binding.captureButton.setOnClickListener {
            if (currentMode == CameraMode.VIDEO) captureVideo() else startTimedPhoto()
        }
        binding.galleryButton.setOnClickListener { openLastCapture() }
        binding.videoPauseButton.setOnClickListener {
            videoPaused = cameraController.togglePauseVideo()
            binding.videoPauseButton.text = if (videoPaused) "RESUME" else "PAUSE"
        }
    }

    private fun updateModeUi(mode: CameraMode) {
        binding.captureButton.setBackgroundResource(
            if (mode == CameraMode.VIDEO) R.drawable.bg_capture_video else R.drawable.bg_capture
        )
        binding.aiLevelButton.isEnabled = mode != CameraMode.PRO && mode != CameraMode.VIDEO
        binding.hdrButton.isEnabled = mode != CameraMode.PRO && mode != CameraMode.VIDEO
        binding.timerButton.isEnabled = mode != CameraMode.VIDEO
        binding.exposurePanel.visibility = if (mode == CameraMode.PRO || userOpenedExposurePanel) View.VISIBLE else View.GONE

        binding.statusText.text = when (mode) {
            CameraMode.NIGHT -> "Night • düşük ışık tonlama + gölge denoise"
            CameraMode.PORTRAIT -> "Portrait • merkez konu + yumuşak arka plan"
            CameraMode.PHOTO -> "Photo • hızlı ve dengeli çekim"
            CameraMode.AI -> "AI • sahne algılama + akıllı renk/ton"
            CameraMode.SUPER_ZOOM -> "Super Zoom • dijital zoom + detay güçlendirme"
            CameraMode.VIDEO -> "Video • cihazın desteklediği en iyi kalite"
            CameraMode.PRO -> "Pro • EV + odak + zoom, işleme kapalı"
        }
    }

    private fun updateHdrUi() {
        binding.hdrButton.text = if (hdrEnabled) "HDR ON" else "HDR OFF"
        binding.hdrButton.setBackgroundResource(if (hdrEnabled) R.drawable.bg_chip_active else R.drawable.bg_chip)
        binding.hdrButton.setTextColor(if (hdrEnabled) Color.rgb(7, 16, 24) else Color.WHITE)
    }

    private fun updateAiUi() {
        binding.aiLevelButton.text = if (aiLevel == AiLevel.OFF) "AI OFF" else "✦ ${aiLevel.label}"
        binding.aiLevelButton.setBackgroundResource(if (aiLevel == AiLevel.OFF) R.drawable.bg_chip else R.drawable.bg_chip_active)
        binding.aiLevelButton.setTextColor(if (aiLevel == AiLevel.OFF) Color.WHITE else Color.rgb(7, 16, 24))
    }

    private fun updateTimerUi() {
        binding.timerButton.text = if (timerSeconds == 0) "⏱ OFF" else "⏱ ${timerSeconds}s"
    }

    private fun setupExposureControl() {
        binding.exposureSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cameraController.setExposureIndex(evMin + progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.exposureReset.setOnClickListener { cameraController.resetExposure() }
    }

    private fun updateExposureLabel(index: Int) {
        val ev = index * evStep
        val label = "EV %+.1f".format(Locale.US, ev)
        binding.exposureLabel.text = label
        binding.evButton.text = label
    }

    private fun setupGestures() {
        var startZoom = 1f
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                startZoom = cameraController.currentZoom()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                startZoom = cameraController.currentZoom()
                cameraController.setZoom(startZoom * detector.scaleFactor)
                return true
            }
        })

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                cameraController.focusAt(e.x, e.y)
                showFocusIndicator(e.x, e.y)
                return true
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val v = binding.focusIndicator
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.alpha = 1f
        v.scaleX = 1.35f
        v.scaleY = 1.35f
        v.x = (x - v.width / 2f).coerceAtLeast(0f)
        v.y = (y - v.height / 2f).coerceAtLeast(0f)
        v.animate().scaleX(1f).scaleY(1f).setDuration(160L).withEndAction {
            v.animate().alpha(0f).setStartDelay(550L).setDuration(250L).withEndAction {
                v.visibility = View.GONE
            }.start()
        }.start()
    }

    private fun startTimedPhoto() {
        if (timerSeconds <= 0) {
            capturePhotoNow()
            return
        }
        binding.captureButton.isEnabled = false
        runCountdown(timerSeconds)
    }

    private fun runCountdown(value: Int) {
        if (value <= 0) {
            binding.countdownText.visibility = View.GONE
            capturePhotoNow()
            return
        }
        binding.countdownText.text = value.toString()
        binding.countdownText.visibility = View.VISIBLE
        haptic(20L)
        handler.postDelayed({ runCountdown(value - 1) }, 1000L)
    }

    private fun capturePhotoNow() {
        haptic(35L)
        binding.captureButton.isEnabled = false
        val effectiveAi = if (currentMode == CameraMode.PRO) AiLevel.OFF else aiLevel
        val effectiveHdr = hdrEnabled && currentMode != CameraMode.PRO
        val settings = CaptureSettings(
            mode = currentMode,
            aiLevel = effectiveAi,
            hdrEnabled = effectiveHdr,
            keepOriginal = keepOriginal
        )
        cameraController.takePhoto(
            settings = settings,
            onProcessing = { processing ->
                binding.processingOverlay.visibility = if (processing) View.VISIBLE else View.GONE
                binding.captureButton.isEnabled = !processing
            },
            onSaved = { uri, enhanced, info ->
                lastSavedUri = uri
                lastCaptureIsVideo = false
                binding.captureButton.isEnabled = true
                binding.processingOverlay.visibility = View.GONE
                binding.statusText.text = if (enhanced) "M&G fotoğraf hazır • $info" else "Fotoğraf hazır • $info"
                toast(if (enhanced) "M&G fotoğraf kaydedildi" else getString(R.string.photo_saved))
            },
            onError = {
                binding.captureButton.isEnabled = true
                binding.processingOverlay.visibility = View.GONE
                showError(it)
            }
        )
    }

    private fun captureVideo() {
        haptic(35L)
        cameraController.toggleVideo(
            onRecordingState = { active ->
                recording = active
                videoPaused = false
                binding.videoPauseButton.text = "PAUSE"
                binding.videoPauseButton.visibility = if (active) View.VISIBLE else View.GONE
                binding.captureButton.setBackgroundResource(
                    if (active) R.drawable.bg_chip_active else R.drawable.bg_capture_video
                )
                binding.switchCameraButton.isEnabled = !active
                binding.settingsButton.isEnabled = !active
                if (active) {
                    recordStartedAt = System.currentTimeMillis()
                    handler.removeCallbacks(videoTicker)
                    handler.post(videoTicker)
                } else {
                    handler.removeCallbacks(videoTicker)
                }
            },
            onSaved = { uri ->
                lastSavedUri = uri
                lastCaptureIsVideo = true
                toast(getString(R.string.video_saved))
            },
            onError = { showError(it) }
        )
    }

    private fun updateModeChips(modeMap: Map<TextView, CameraMode>) {
        modeMap.forEach { (view, mode) ->
            val selected = mode == currentMode
            view.setBackgroundResource(if (selected) R.drawable.bg_chip_active else R.drawable.bg_chip)
            view.setTextColor(if (selected) Color.rgb(7, 16, 24) else ContextCompat.getColor(this, R.color.muted))
        }
    }

    private fun updateZoomChips(zoom: Float) {
        val map = linkedMapOf(
            binding.zoom1 to 1f,
            binding.zoom2 to 2f,
            binding.zoom3 to 3f,
            binding.zoom5 to 5f,
            binding.zoom10 to 10f
        )
        val closest = map.minByOrNull { abs(it.value - zoom) }?.key
        map.keys.forEach { view ->
            val selected = view === closest
            view.setBackgroundResource(if (selected) R.drawable.bg_chip_active else R.drawable.bg_chip)
            view.setTextColor(if (selected) Color.rgb(7, 16, 24) else Color.WHITE)
        }
    }

    private fun openLastCapture() {
        val uri = lastSavedUri ?: run {
            toast("Henüz çekim yok")
            return
        }
        val type = if (lastCaptureIsVideo) "video/*" else "image/*"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Throwable) {
            toast("Galeri uygulaması açılamadı")
        }
    }

    private fun showSettings() {
        val items = arrayOf(
            "Izgara: ${if (gridEnabled) "AÇIK" else "KAPALI"}",
            "Orijinali sakla: ${if (keepOriginal) "EVET" else "HAYIR"}",
            "Cihaz kamera profilini göster",
            "M&G Camera 1.0 hakkında"
        )
        AlertDialog.Builder(this)
            .setTitle("M&G Camera Ayarları")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        gridEnabled = !gridEnabled
                        prefs.edit().putBoolean("grid", gridEnabled).apply()
                        binding.gridView.visibility = if (gridEnabled) View.VISIBLE else View.GONE
                    }
                    1 -> {
                        keepOriginal = !keepOriginal
                        prefs.edit().putBoolean("keep_original", keepOriginal).apply()
                        toast(if (keepOriginal) "Orijinal + işlenmiş fotoğraf saklanacak" else "Yalnızca son işlenmiş fotoğraf saklanacak")
                    }
                    2 -> showDeviceProfile()
                    3 -> AlertDialog.Builder(this)
                        .setTitle("M&G Camera 1.0 Full")
                        .setMessage("Photo, Night, Portrait, AI, Super Zoom, Video ve Pro modları; HDR tonlama, sahne algılama, odak, EV, zoom, zamanlayıcı, grid ve cihaz uyumluluk katmanı içerir. Bazı gelişmiş sonuçlar telefonun kamera donanımına bağlıdır.")
                        .setPositiveButton("TAMAM", null)
                        .show()
                }
            }
            .setNegativeButton("KAPAT", null)
            .show()
    }

    private fun showDeviceProfile() {
        binding.statusText.text = "Kamera donanımı taranıyor…"
        Thread {
            try {
                val report = DeviceCapabilityScanner(this).scan()
                File(filesDir, "device_profile.json").writeText(report)
                runOnUiThread {
                    binding.statusText.text = "Device profile hazır"
                    AlertDialog.Builder(this)
                        .setTitle("M&G Camera Device Profiler")
                        .setMessage(report)
                        .setPositiveButton("KOPYALA") { _, _ ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("M&G Camera device profile", report))
                            toast("Profil panoya kopyalandı")
                        }
                        .setNegativeButton("KAPAT", null)
                        .show()
                }
            } catch (t: Throwable) {
                runOnUiThread { showError(t) }
            }
        }.start()
    }

    private fun haptic(ms: Long) {
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun showError(t: Throwable) {
        binding.statusText.text = "Hata • ${t.message ?: t.javaClass.simpleName}"
        toast(t.message ?: t.javaClass.simpleName)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        if (recording) cameraController.stopRecordingIfNeeded()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cameraController.shutdown()
        super.onDestroy()
    }
}
