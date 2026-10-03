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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
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
import java.io.File
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController
    private var currentMode = CameraMode.PHOTO
    private var aiLevel = AiLevel.AI
    private var lastSavedUri: Uri? = null
    private var recording = false

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

        cameraController = CameraController(this, this, binding.previewView).apply {
            onZoomChanged = { zoom, max ->
                binding.zoomReadout.text = "%.1f×".format(zoom)
                binding.zoomReadout.visibility = if (abs(zoom - 1f) > 0.03f) View.VISIBLE else View.GONE
                binding.statusText.text = "%.1f× / max %.1f×".format(zoom, max)
                updateZoomChips(zoom)
            }
            onStatus = { binding.statusText.text = it }
        }

        setupControls()
        setupGestures()
        requestPermissionsOrStart()
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

        binding.aiLevelButton.setOnClickListener {
            aiLevel = aiLevel.next()
            binding.aiLevelButton.text = if (aiLevel == AiLevel.OFF) "AI OFF" else "✦ ${aiLevel.label}"
            binding.aiLevelButton.setBackgroundResource(
                if (aiLevel == AiLevel.OFF) R.drawable.bg_chip else R.drawable.bg_chip_active
            )
            binding.aiLevelButton.setTextColor(
                if (aiLevel == AiLevel.OFF) Color.WHITE else Color.rgb(7, 16, 24)
            )
        }

        val modeMap = linkedMapOf(
            binding.modeNight to CameraMode.NIGHT,
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
                binding.captureButton.setBackgroundResource(
                    if (mode == CameraMode.VIDEO) R.drawable.bg_capture_video else R.drawable.bg_capture
                )
                when (mode) {
                    CameraMode.SUPER_ZOOM -> binding.statusText.text = "Super Zoom • gerçek piksel korumalı işleme"
                    CameraMode.NIGHT -> binding.statusText.text = "Night • düşük ışık profili"
                    CameraMode.PRO -> binding.statusText.text = "Pro • manuel kontroller sonraki motor paketinde"
                    CameraMode.VIDEO -> binding.statusText.text = "Video • FHD/UHD otomatik kalite seçimi"
                    else -> Unit
                }
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
            view.setOnClickListener {
                cameraController.setZoom(zoom)
            }
        }

        binding.switchCameraButton.setOnClickListener { cameraController.switchCamera() }
        binding.captureButton.setOnClickListener {
            if (currentMode == CameraMode.VIDEO) captureVideo() else capturePhoto()
        }
        binding.galleryButton.setOnClickListener { openLastCapture() }
        binding.profileButton.setOnClickListener { showDeviceProfile() }
    }

    private fun setupGestures() {
        var startZoom = 1f
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                startZoom = cameraController.currentZoom()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (startZoom * detector.scaleFactor).coerceAtLeast(1f)
                // Update start point continuously so long pinches remain smooth.
                startZoom = cameraController.currentZoom()
                cameraController.setZoom(startZoom * detector.scaleFactor)
                return true
            }
        })

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                cameraController.focusAt(e.x, e.y)
                return true
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun capturePhoto() {
        binding.captureButton.isEnabled = false
        cameraController.takePhoto(
            aiLevel = aiLevel,
            onProcessing = { processing ->
                binding.processingOverlay.visibility = if (processing) View.VISIBLE else View.GONE
                binding.captureButton.isEnabled = !processing
            },
            onSaved = { uri, enhanced, info ->
                lastSavedUri = uri
                binding.captureButton.isEnabled = true
                binding.processingOverlay.visibility = View.GONE
                binding.statusText.text = if (enhanced) "AI fotoğraf hazır • $info" else "Fotoğraf hazır • $info"
                toast(if (enhanced) "AI fotoğraf kaydedildi" else getString(R.string.photo_saved))
            },
            onError = {
                binding.captureButton.isEnabled = true
                binding.processingOverlay.visibility = View.GONE
                showError(it)
            }
        )
    }

    private fun captureVideo() {
        // If microphone permission was denied, CameraController records a silent video instead of failing.
        cameraController.toggleVideo(
            onRecordingState = { active ->
                recording = active
                binding.captureButton.setBackgroundResource(
                    if (active) R.drawable.bg_chip_active else R.drawable.bg_capture_video
                )
                binding.switchCameraButton.isEnabled = !active
            },
            onSaved = { uri ->
                lastSavedUri = uri
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
        val type = if (currentMode == CameraMode.VIDEO) "video/*" else "image/*"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Throwable) {
            toast("Galeri uygulaması açılamadı")
        }
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

    private fun showError(t: Throwable) {
        binding.statusText.text = "Hata • ${t.message ?: t.javaClass.simpleName}"
        toast(t.message ?: t.javaClass.simpleName)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        cameraController.shutdown()
        super.onDestroy()
    }
}
