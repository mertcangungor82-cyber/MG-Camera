package com.mg.camera.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.mg.camera.model.CameraMode
import com.mg.camera.model.CaptureSettings
import com.mg.camera.model.FlashState
import com.mg.camera.processing.ProcessingPipeline
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingPaused = false

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var mode: CameraMode = CameraMode.PHOTO
    private var flashState: FlashState = FlashState.AUTO
    private var lastZoom = 1f
    private var maxZoom = 10f
    private var minZoom = 1f
    private var exposureIndex = 0
    private var targetPhotoSize: Size? = null
    private var selectedCameraId: String? = null
    private var extensionsManager: ExtensionsManager? = null
    private var hdrRequested = false
    private var activeExtensionMode = ExtensionMode.NONE

    private val hardware = OemCameraCapabilities(context)
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val processingPipeline = ProcessingPipeline(context)

    var onZoomChanged: ((Float, Float) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onExposureInfo: ((Int, Int, Int, Float) -> Unit)? = null
    var onResolutionChanged: ((Size) -> Unit)? = null

    fun start(onReady: (() -> Unit)? = null, onError: ((Throwable) -> Unit)? = null) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                selectedCameraId = hardware.bestCameraId(lensFacing)

                val extFuture = ExtensionsManager.getInstanceAsync(context, provider!!)
                extFuture.addListener({
                    extensionsManager = runCatching { extFuture.get() }.getOrNull()
                    try {
                        ensureDefaultResolution()
                        bindForMode()
                        onReady?.invoke()
                    } catch (t: Throwable) {
                        onError?.invoke(t)
                    }
                }, mainExecutor)
            } catch (t: Throwable) {
                onError?.invoke(t)
            }
        }, mainExecutor)
    }

    fun setMode(newMode: CameraMode) {
        if (recording != null) return
        mode = newMode
        bindForMode()
    }

    fun currentMode(): CameraMode = mode

    fun setHdrEnabled(enabled: Boolean) {
        if (hdrRequested == enabled) return
        hdrRequested = enabled
        if (provider != null && (mode == CameraMode.PHOTO || mode == CameraMode.NIGHT)) {
            bindForMode()
        }
    }


    fun supportedPhotoResolutions(): List<Size> {
        if (selectedCameraId == null) {
            selectedCameraId = hardware.bestCameraId(lensFacing)
        }
        return hardware.resolutions(selectedCameraId).map { it.size }
    }

    fun currentCameraId(): String? = selectedCameraId

    fun currentCameraHardwareLabel(): String = hardware.cameraLabel(selectedCameraId)

    fun currentResolutionIsHighResolution(): Boolean =
        hardware.isHighResolution(selectedCameraId, targetPhotoSize)

    fun isPhotoResolutionHigh(size: Size): Boolean =
        hardware.isHighResolution(selectedCameraId, size)

    fun isPhotoResolutionMaximum(size: Size): Boolean =
        hardware.isMaximumResolution(selectedCameraId, size)

    fun currentPhotoResolution(): Size? = targetPhotoSize

    fun selectPhotoResolution(size: Size) {
        if (recording != null || mode == CameraMode.VIDEO) return
        val supported = supportedPhotoResolutions()
        val exact = supported.firstOrNull { it.width == size.width && it.height == size.height }
        if (exact == null) {
            onStatus?.invoke("Bu çözünürlük kamera tarafından sunulmuyor")
            return
        }
        targetPhotoSize = exact
        bindForMode()
    }

    private fun ensureDefaultResolution() {
        val sizes = supportedPhotoResolutions()
        if (sizes.isEmpty()) return

        val current = targetPhotoSize
        if (current != null && sizes.any { it.width == current.width && it.height == current.height }) return

        // Prefer a high-quality binned mode around 12–20 MP for everyday capture.
        // If the OEM exposes a 48/64 MP path, it stays available in the MP picker.
        targetPhotoSize = sizes.firstOrNull {
            it.width.toLong() * it.height.toLong() <= 20_000_000L
        } ?: sizes.first()
    }

    @Suppress("DEPRECATION")
    private fun bindForMode() {
        val cameraProvider = provider ?: return
        cameraProvider.unbindAll()

        val baseSelector = createCameraSelector(lensFacing)
        val selector = extensionSelectorFor(baseSelector)

        try {
            if (mode == CameraMode.VIDEO) {
                val preview = createPreview()
                val selectorQuality = QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(selectorQuality)
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                videoCapture = vc
                imageCapture = null
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
            } else {
                ensureDefaultResolution()
                val preview = createPreview()
                val capture = createImageCapture(targetPhotoSize)
                imageCapture = capture
                videoCapture = null
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                targetPhotoSize?.let { onResolutionChanged?.invoke(it) }
            }

            observeZoom()
            observeExposure()
            applyFlashState()
            setZoom(lastZoom)
            setExposureIndex(exposureIndex)
            val ext = extensionLabel()
            onStatus?.invoke(
                if (ext.isNotEmpty()) "${mode.label} • $ext"
                else "${mode.label} hazır"
            )
        } catch (requestedError: Throwable) {
            // Some OEM HALs advertise a large JPEG size but reject it when Preview + ImageCapture
            // are bound together. Fall back to a safe <=12MP advertised JPEG before giving up.
            if (mode != CameraMode.VIDEO) {
                val safe = supportedPhotoResolutions().firstOrNull {
                    it.width.toLong() * it.height.toLong() <= 12_500_000L
                }
                if (safe != null && (targetPhotoSize == null ||
                        safe.width != targetPhotoSize!!.width || safe.height != targetPhotoSize!!.height)) {
                    runCatching {
                        cameraProvider.unbindAll()
                        val preview = createPreview()
                        val capture = createImageCapture(safe)
                        imageCapture = capture
                        videoCapture = null
                        camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                        targetPhotoSize = safe
                        onResolutionChanged?.invoke(safe)
                        observeZoom()
                        observeExposure()
                        applyFlashState()
                        setZoom(lastZoom)
                        setExposureIndex(exposureIndex)
                        onStatus?.invoke("Kamera güvenli ${formatMp(safe)} moduna geçti")
                        return
                    }
                }
            }

            try {
                cameraProvider.unbindAll()
                val preview = createPreview()
                val fallbackCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(98)
                    .build()
                    .also { it.flashMode = toImageCaptureFlash(flashState) }
                imageCapture = fallbackCapture
                videoCapture = null
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, fallbackCapture)
                targetPhotoSize = null
                observeZoom()
                observeExposure()
                applyFlashState()
                onStatus?.invoke("Uyumluluk modu aktif")
            } catch (second: Throwable) {
                onStatus?.invoke("Kamera açılamadı: ${second.message ?: second.javaClass.simpleName}")
            }
        }
    }

    private fun extensionSelectorFor(base: CameraSelector): CameraSelector {
        activeExtensionMode = ExtensionMode.NONE
        val desired = when {
            mode == CameraMode.NIGHT -> ExtensionMode.NIGHT
            mode == CameraMode.PORTRAIT -> ExtensionMode.BOKEH
            mode == CameraMode.PHOTO && hdrRequested -> ExtensionMode.HDR
            else -> ExtensionMode.NONE
        }
        if (desired == ExtensionMode.NONE) return base

        val manager = extensionsManager ?: return base
        val available = runCatching {
            manager.isExtensionAvailable(base, desired)
        }.getOrDefault(false)
        if (!available) return base

        return runCatching {
            manager.getExtensionEnabledCameraSelector(base, desired)
        }.onSuccess {
            activeExtensionMode = desired
        }.getOrElse {
            activeExtensionMode = ExtensionMode.NONE
            base
        }
    }

    private fun extensionLabel(): String = when (activeExtensionMode) {
        ExtensionMode.HDR -> "OEM HDR"
        ExtensionMode.NIGHT -> "OEM NIGHT"
        ExtensionMode.BOKEH -> "OEM BOKEH"
        ExtensionMode.FACE_RETOUCH -> "OEM FACE"
        ExtensionMode.AUTO -> "OEM AUTO"
        else -> ""
    }

    private fun createCameraSelector(facing: Int): CameraSelector {
        val preferred = selectedCameraId ?: hardware.bestCameraId(facing).also {
            selectedCameraId = it
        }
        val builder = CameraSelector.Builder().requireLensFacing(facing)

        if (preferred != null) {
            builder.addCameraFilter { cameraInfos ->
                val exact = cameraInfos.filter { info ->
                    runCatching {
                        Camera2CameraInfo.from(info).cameraId == preferred
                    }.getOrDefault(false)
                }
                if (exact.isNotEmpty()) exact else cameraInfos
            }
        }
        return builder.build()
    }

    private fun createPreview(): Preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }

    @Suppress("DEPRECATION")
    private fun createImageCapture(target: Size?): ImageCapture {
        val captureMode = if (mode == CameraMode.PRO) {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        } else {
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }

        val quality = 100

        val builder = ImageCapture.Builder()
            .setCaptureMode(captureMode)
            .setJpegQuality(quality)

        if (target != null && activeExtensionMode == ExtensionMode.NONE) {
            val highResolution = hardware.isHighResolution(selectedCameraId, target)
            val selector = ResolutionSelector.Builder()
                .setAllowedResolutionMode(
                    if (highResolution) ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                    else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
                )
                .setResolutionStrategy(
                    ResolutionStrategy(
                        targetForDisplayRotation(target),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            builder.setResolutionSelector(selector)
        }

        return builder.build().also {
            it.flashMode = toImageCaptureFlash(flashState)
        }
    }

    private fun targetForDisplayRotation(raw: Size): Size {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val displayIsPortrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
        return if (displayIsPortrait && raw.width > raw.height) {
            Size(raw.height, raw.width)
        } else if (!displayIsPortrait && raw.height > raw.width) {
            Size(raw.height, raw.width)
        } else {
            raw
        }
    }

    fun switchCamera() {
        if (recording != null) return
        val cameraProvider = provider ?: return
        val target = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val selector = CameraSelector.Builder().requireLensFacing(target).build()
        if (cameraProvider.hasCamera(selector)) {
            lensFacing = target
            selectedCameraId = hardware.bestCameraId(target)
            lastZoom = 1f
            exposureIndex = 0
            targetPhotoSize = null
            ensureDefaultResolution()
            bindForMode()
        }
    }

    fun isFrontCamera(): Boolean = lensFacing == CameraSelector.LENS_FACING_FRONT

    fun cycleFlash(): FlashState {
        flashState = flashState.next()
        if (camera?.cameraInfo?.hasFlashUnit() != true && flashState == FlashState.ON) {
            flashState = FlashState.OFF
        }
        applyFlashState()
        return flashState
    }

    fun setZoom(requested: Float) {
        val value = requested.coerceIn(minZoom, maxZoom)
        lastZoom = value
        camera?.cameraControl?.setZoomRatio(value)
        onZoomChanged?.invoke(value, maxZoom)
    }

    fun currentZoom(): Float = lastZoom
    fun maxZoom(): Float = maxZoom

    fun setExposureIndex(requested: Int) {
        val c = camera ?: return
        val state = c.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            onExposureInfo?.invoke(0, 0, 0, 0f)
            return
        }
        val range = state.exposureCompensationRange
        exposureIndex = requested.coerceIn(range.lower, range.upper)
        c.cameraControl.setExposureCompensationIndex(exposureIndex)
        val step = state.exposureCompensationStep.toFloat()
        onExposureInfo?.invoke(exposureIndex, range.lower, range.upper, step)
    }

    fun resetExposure() = setExposureIndex(0)

    fun focusAt(x: Float, y: Float) {
        val c = camera ?: return
        val factory: MeteringPointFactory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        c.cameraControl.startFocusAndMetering(action)
        onStatus?.invoke("Odaklanıyor…")
    }

    fun takePhoto(
        settings: CaptureSettings,
        onProcessing: (Boolean) -> Unit,
        onSaved: (Uri, Boolean, String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Fotoğraf modu aktif değil"))
            return
        }

        val needsProcessing = requiresPostProcessing(settings)
        val stamp = System.currentTimeMillis()
        val name = if (needsProcessing) {
            "MG_SOURCE_${settings.mode.name}_$stamp.jpg"
        } else {
            "MG_${settings.mode.name}_$stamp.jpg"
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    if (needsProcessing) "Pictures/MGCamera/Original" else "Pictures/MGCamera"
                )
            }
        }

        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val original = outputFileResults.savedUri ?: run {
                        onError(IllegalStateException("MediaStore URI alınamadı"))
                        return
                    }

                    if (!needsProcessing || settings.mode == CameraMode.PRO) {
                        val size = targetPhotoSize
                        val note = if (size != null) {
                            "Native JPEG • ${size.width}×${size.height} • ${formatMp(size)}"
                        } else {
                            "Native JPEG • kamera çözünürlüğü"
                        }
                        onSaved(original, false, note)
                        return
                    }

                    onProcessing(true)
                    processingExecutor.execute {
                        try {
                            val result = processingPipeline.process(original, settings)
                            if (!settings.keepOriginal && result.uri != null && result.uri != original) {
                                runCatching { context.contentResolver.delete(original, null, null) }
                            }
                            ContextCompat.getMainExecutor(context).execute {
                                onProcessing(false)
                                if (result.uri != null) {
                                    onSaved(
                                        result.uri,
                                        true,
                                        "${result.width}×${result.height} • ${result.processingMs} ms • ${result.note}"
                                    )
                                } else {
                                    onSaved(original, false, "İşleme başarısız; native JPEG korundu")
                                }
                            }
                        } catch (t: Throwable) {
                            ContextCompat.getMainExecutor(context).execute {
                                onProcessing(false)
                                onSaved(original, false, "İşleme atlandı; native JPEG korundu")
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    private fun requiresPostProcessing(settings: CaptureSettings): Boolean {
        if (activeExtensionMode != ExtensionMode.NONE) return false
        if (settings.mode == CameraMode.PRO) return false
        if (settings.mode == CameraMode.PHOTO &&
            settings.aiLevel.name == "OFF" &&
            !settings.hdrEnabled) return false

        return settings.aiLevel.name != "OFF" ||
            settings.hdrEnabled ||
            settings.mode == CameraMode.AI ||
            settings.mode == CameraMode.NIGHT ||
            settings.mode == CameraMode.PORTRAIT ||
            settings.mode == CameraMode.SUPER_ZOOM
    }

    fun toggleVideo(
        onRecordingState: (Boolean) -> Unit,
        onSaved: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val active = recording
        if (active != null) {
            active.stop()
            return
        }

        val capture = videoCapture ?: run {
            onError(IllegalStateException("Video modu aktif değil"))
            return
        }

        val name = "MG_VIDEO_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MGCamera")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = capture.output.prepareRecording(context, outputOptions)
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }

        recordingPaused = false
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    onRecordingState(true)
                    onStatus?.invoke("REC • video kaydı")
                }
                is VideoRecordEvent.Finalize -> {
                    val hadError = event.hasError()
                    recording?.close()
                    recording = null
                    recordingPaused = false
                    onRecordingState(false)
                    if (!hadError) {
                        onSaved(event.outputResults.outputUri)
                        onStatus?.invoke("Video kaydedildi")
                    } else {
                        onError(IllegalStateException("Video hatası: ${event.error}"))
                    }
                }
                else -> Unit
            }
        }
    }

    fun togglePauseVideo(): Boolean {
        val active = recording ?: return false
        if (recordingPaused) {
            active.resume()
            recordingPaused = false
            onStatus?.invoke("REC • devam ediyor")
        } else {
            active.pause()
            recordingPaused = true
            onStatus?.invoke("Video duraklatıldı")
        }
        return recordingPaused
    }

    fun stopRecordingIfNeeded() {
        recording?.stop()
    }

    fun shutdown() {
        stopRecordingIfNeeded()
        processingExecutor.shutdown()
    }

    private fun observeZoom() {
        val c = camera ?: return
        c.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
            minZoom = state.minZoomRatio
            maxZoom = state.maxZoomRatio
            val actual = state.zoomRatio
            lastZoom = actual
            onZoomChanged?.invoke(actual, maxZoom)
        }
    }

    private fun observeExposure() {
        val c = camera ?: return
        val state = c.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            onExposureInfo?.invoke(0, 0, 0, 0f)
            return
        }
        val range = state.exposureCompensationRange
        exposureIndex = exposureIndex.coerceIn(range.lower, range.upper)
        onExposureInfo?.invoke(
            exposureIndex,
            range.lower,
            range.upper,
            state.exposureCompensationStep.toFloat()
        )
    }

    private fun applyFlashState() {
        if (camera?.cameraInfo?.hasFlashUnit() != true) {
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
            runCatching { camera?.cameraControl?.enableTorch(false) }
            return
        }

        imageCapture?.flashMode = toImageCaptureFlash(flashState)
        if (mode == CameraMode.VIDEO) {
            camera?.cameraControl?.enableTorch(flashState == FlashState.ON)
        } else {
            camera?.cameraControl?.enableTorch(false)
        }
    }

    private fun toImageCaptureFlash(state: FlashState): Int = when (state) {
        FlashState.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashState.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashState.ON -> ImageCapture.FLASH_MODE_ON
    }

    private fun formatMp(size: Size): String {
        val mp = size.width.toLong() * size.height.toLong() / 1_000_000.0
        return if (abs(mp - mp.toInt()) < 0.12) {
            "${mp.toInt()}MP"
        } else {
            "%.1fMP".format(java.util.Locale.US, mp)
        }
    }
}
