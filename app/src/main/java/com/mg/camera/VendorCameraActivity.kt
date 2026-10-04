package com.mg.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.max

class VendorCameraActivity : AppCompatActivity() {

    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var mpButton: Button
    private lateinit var hdrButton: Button
    private lateinit var nightButton: Button

    private val cameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var cameraId: String? = null
    private var previewSize = Size(1920, 1080)
    private var normalSize = Size(4608, 3456)
    private var captureSize = normalSize

    private var hdRequested = true
    private var hdrEnabled = false
    private var nightEnabled = false
    private var zoom = 1f

    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        startThread()
        if (preview.isAvailable) openCamera() else preview.surfaceTextureListener = surfaceListener
    }

    override fun onPause() {
        closeCamera()
        stopThread()
        super.onPause()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
        }

        mpButton = topButton("MP") {
            hdRequested = !hdRequested
            reopenCamera()
        }
        hdrButton = topButton("HDR") {
            hdrEnabled = !hdrEnabled
            if (hdrEnabled) nightEnabled = false
            updateTopState()
            restartSession()
        }
        nightButton = topButton("NIGHT") {
            nightEnabled = !nightEnabled
            if (nightEnabled) hdrEnabled = false
            updateTopState()
            restartSession()
        }
        val switch = topButton("↻") {
            lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            hdRequested = true
            zoom = 1f
            reopenCamera()
        }
        val lab = topButton("LAB") {
            startActivity(Intent(this, MainActivity::class.java))
        }

        top.addView(mpButton)
        top.addView(hdrButton)
        top.addView(nightButton)
        top.addView(switch)
        top.addView(lab)

        root.addView(
            top,
            FrameLayout.LayoutParams(-1, 70, Gravity.TOP)
        )

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            gravity = Gravity.CENTER
            textSize = 12f
            setPadding(18, 8, 18, 8)
            text = "M&G Cam V5 • OEM ISP"
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = 82 }
        )

        val bottom = FrameLayout(this).apply { setBackgroundColor(0x88000000.toInt()) }
        val shutter = Button(this).apply {
            text = "●"
            textSize = 50f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { capture() }
        }
        bottom.addView(shutter, FrameLayout.LayoutParams(190, 190, Gravity.CENTER))

        root.addView(
            bottom,
            FrameLayout.LayoutParams(-1, 220, Gravity.BOTTOM)
        )

        preview.setOnTouchListener { _, event ->
            if (event.pointerCount >= 2) {
                val dx = event.getX(0) - event.getX(1)
                val dy = event.getY(0) - event.getY(1)
                val span = kotlin.math.sqrt(dx * dx + dy * dy)
                val width = preview.width.coerceAtLeast(1)
                zoom = (1f + span / width * 4f).coerceIn(1f, maxZoom())
                updateRepeating()
            }
            true
        }

        setContentView(root)
        updateTopState()
    }

    private fun topButton(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { action() }
        }

    private fun updateTopState() {
        hdrButton.alpha = if (hdrEnabled) 1f else 0.5f
        nightButton.alpha = if (nightEnabled) 1f else 0.5f
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 55)
            return
        }

        cameraId = bestCameraId(lensFacing)
        val id = cameraId ?: run {
            show("Kamera bulunamadı")
            return
        }
        chooseSizes(id)

        cameraManager.openCamera(
            id,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    show("Kamera hatası: $error")
                }
            },
            bg
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 55 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        }
    }

    private fun bestCameraId(facing: Int): String? =
        cameraManager.cameraIdList
            .mapNotNull { id ->
                runCatching {
                    val c = cameraManager.getCameraCharacteristics(id)
                    if (c.get(CameraCharacteristics.LENS_FACING) != facing) return@runCatching null
                    val p = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    id to ((p?.width?.toLong() ?: 0) * (p?.height?.toLong() ?: 0))
                }.getOrNull()
            }
            .filterNotNull()
            .maxByOrNull { it.second }
            ?.first

    private fun chooseSizes(id: String) {
        val c = cameraManager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        normalSize = map?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) Size(4608, 3456) else Size(3264, 2448)

        val hd = vendorHdSizes(c)
        captureSize = when {
            !hdRequested -> normalSize
            hd.isNotEmpty() -> hd.first()
            lensFacing == CameraCharacteristics.LENS_FACING_FRONT -> Size(6528, 4896)
            else -> normalSize
        }

        previewSize = map?.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(1280, 720)

        val mp = captureSize.width.toLong() * captureSize.height.toLong() / 1_000_000.0
        runOnUiThread {
            mpButton.text = "%.0fMP".format(mp)
            show(
                (if (hdRequested) "OEM HD" else "NORMAL") +
                    " • ${captureSize.width}×${captureSize.height}"
            )
        }
    }

    private fun vendorHdSizes(c: CameraCharacteristics): List<Size> {
        val key = CameraCharacteristics.Key(
            "com.transsion.availableHDStreamConfigurations",
            IntArray::class.java
        )
        val raw = runCatching { c.get(key) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<Size>()
        var i = 0
        while (i + 3 < raw.size) {
            if (raw[i] == 33 && raw[i + 3] == 0) {
                result += Size(raw[i + 1], raw[i + 2])
            }
            i += 4
        }
        return result.distinctBy { "${it.width}x${it.height}" }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
    }

    private fun createSession() {
        val cam = device ?: return
        val texture = preview.surfaceTexture ?: return

        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(texture)

        reader?.close()
        reader = runCatching {
            ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2)
        }.getOrElse {
            fallbackNormal("HD reader reddedildi")
            return
        }

        reader?.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                save(data)
            }
        }, bg)

        val p = previewSurface ?: return
        val imageSurface = reader?.surface ?: return

        val previewRequest = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(p)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            applyVendor(this, still = false)
            applyZoom(this)
        }

        runCatching {
            cam.createCaptureSession(
                listOf(p, imageSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        runCatching {
                            s.setRepeatingRequest(previewRequest.build(), null, bg)
                        }.onFailure { show("Preview hatası: ${it.message}") }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        fallbackNormal("OEM HD session reddedildi")
                    }
                },
                bg
            )
        }.onFailure {
            fallbackNormal("Session hatası: ${it.message}")
        }
    }

    private fun capture() {
        val cam = device ?: return
        val s = session ?: return
        val target = reader?.surface ?: return

        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(target)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            applyVendor(this, still = true)
            applyZoom(this)
        }

        show("M&G çekiyor…")
        runCatching {
            s.capture(
                request.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        show("ISP tamamladı")
                    }
                },
                bg
            )
        }.onFailure { show("Çekim hatası: ${it.message}") }
    }

    private fun applyVendor(builder: CaptureRequest.Builder, still: Boolean) {
        if (hdRequested) {
            setVendor(builder, "com.mediatek.control.capture.remosaicenable", 1)
            setVendor(builder, "com.mediatek.control.capture.seamless.remosaicenable", 1)
            setVendor(builder, "com.transsion.remosaiccustomsensormode", 1)
            setVendor(builder, "com.transsion.remosaicmfnr", 1)
            setVendor(builder, "com.transsion.megSuperresolutionMode", 2)
            setVendor(builder, "com.mediatek.control.capture.hintForIspTuning", 1)
            runCatching { builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false) }
        }

        if (hdrEnabled) {
            setVendor(builder, "com.transsion.hdrMode", 1)
        }
        if (nightEnabled) {
            setVendor(builder, "com.transsion.lowLightMode", 1)
            setVendor(builder, "com.transsion.megSuperlowlightMode", 3)
            setVendor(builder, "com.transsion.camkitNightMode", 2)
            runCatching { builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false) }
        }
        if (still) {
            setVendor(builder, "com.transsion.denoiseMode", 1)
            setVendor(builder, "com.transsion.enhanceImageQuality", 1)
        }
    }

    private fun setVendor(builder: CaptureRequest.Builder, name: String, value: Int) {
        runCatching {
            builder.set(CaptureRequest.Key(name, Int::class.javaObjectType), value)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val id = cameraId ?: return
        val c = cameraManager.getCameraCharacteristics(id)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val z = zoom.coerceIn(1f, maxZoom())
        if (z <= 1.01f) return
        val w = (active.width() / z).toInt()
        val h = (active.height() / z).toInt()
        val left = active.centerX() - w / 2
        val top = active.centerY() - h / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + w, top + h))
    }

    private fun maxZoom(): Float {
        val id = cameraId ?: return 1f
        return cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?.coerceAtMost(10f) ?: 1f
    }

    private fun updateRepeating() {
        val cam = device ?: return
        val s = session ?: return
        val p = previewSurface ?: return
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(p)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyVendor(this, still = false)
            applyZoom(this)
        }
        runCatching { s.setRepeatingRequest(b.build(), null, bg) }
    }

    private fun jpegOrientation(): Int {
        val id = cameraId ?: return 0
        val c = cameraManager.getCameraCharacteristics(id)
        val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rot = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor - rot + 360) % 360
        } else {
            (sensor + rot) % 360
        }
    }

    private fun save(bytes: ByteArray) {
        val name = "MG_${if (hdRequested) "HD" else "PHOTO"}_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MGCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                show("Kayıt açılamadı")
                return
            }
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
        }.onSuccess {
            val mp = captureSize.width.toLong() * captureSize.height.toLong() / 1_000_000.0
            show("Kaydedildi • %.1fMP".format(mp))
        }.onFailure {
            runCatching { contentResolver.delete(uri, null, null) }
            show("Kayıt hatası")
        }
    }

    private fun restartSession() {
        session?.close()
        session = null
        reader?.close()
        reader = null
        createSession()
    }

    private fun reopenCamera() {
        closeCamera()
        openCamera()
    }

    private fun fallbackNormal(reason: String) {
        runOnUiThread {
            if (hdRequested) {
                Toast.makeText(this, "$reason • normal moda dönülüyor", Toast.LENGTH_SHORT).show()
                hdRequested = false
                reopenCamera()
            } else {
                show(reason)
            }
        }
    }

    private fun show(message: String) {
        runOnUiThread { if (::status.isInitialized) status.text = message }
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
    }

    private fun startThread() {
        if (bgThread != null) return
        bgThread = HandlerThread("MGVendorCamera").also { it.start() }
        bg = Handler(bgThread!!.looper)
    }

    private fun stopThread() {
        bgThread?.quitSafely()
        runCatching { bgThread?.join() }
        bgThread = null
        bg = null
    }
}
