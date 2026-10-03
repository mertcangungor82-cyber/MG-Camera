package com.mg.camera.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
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

    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val processingPipeline = ProcessingPipeline(context)

    var onZoomChanged: ((Float, Float) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onExposureInfo: ((Int, Int, Int, Float) -> Unit)? = null

    fun start(onReady: (() -> Unit)? = null, onError: ((Throwable) -> Unit)? = null) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                bindForMode()
                onReady?.invoke()
            } catch (t: Throwable) {
                onError?.invoke(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setMode(newMode: CameraMode) {
        if (recording != null) return
        mode = newMode
        bindForMode()
    }

    fun currentMode(): CameraMode = mode

    @Suppress("DEPRECATION")
    private fun bindForMode() {
        val cameraProvider = provider ?: return
        cameraProvider.unbindAll()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

        try {
            camera = if (mode == CameraMode.VIDEO) {
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
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
            } else {
                val captureMode = if (mode == CameraMode.PHOTO || mode == CameraMode.PRO) {
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                } else {
                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                }
                val quality = when (mode) {
                    CameraMode.SUPER_ZOOM, CameraMode.PORTRAIT, CameraMode.NIGHT -> 100
                    else -> 98
                }
                val ic = ImageCapture.Builder()
                    .setCaptureMode(captureMode)
                    .setJpegQuality(quality)
                    .build().also { it.flashMode = toImageCaptureFlash(flashState) }
                imageCapture = ic
                videoCapture = null
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, ic)
            }
            observeZoom()
            observeExposure()
            applyFlashState()
            setZoom(lastZoom)
            setExposureIndex(exposureIndex)
            onStatus?.invoke("${mode.label} hazır")
        } catch (first: Throwable) {
            onStatus?.invoke("Kamera yapılandırması yeniden deneniyor…")
            try {
                cameraProvider.unbindAll()
                val fallbackCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build().also { it.flashMode = toImageCaptureFlash(flashState) }
                imageCapture = fallbackCapture
                videoCapture = null
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, fallbackCapture)
                observeZoom()
                observeExposure()
                applyFlashState()
                onStatus?.invoke("Uyumluluk modu aktif")
            } catch (second: Throwable) {
                onStatus?.invoke("Kamera açılamadı: ${second.message ?: second.javaClass.simpleName}")
            }
        }
    }

    fun switchCamera() {
        if (recording != null) return
        val cameraProvider = provider ?: return
        val target = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else CameraSelector.LENS_FACING_BACK
        val selector = CameraSelector.Builder().requireLensFacing(target).build()
        if (cameraProvider.hasCamera(selector)) {
            lensFacing = target
            lastZoom = 1f
            exposureIndex = 0
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
        val stamp = System.currentTimeMillis()
        val name = "MG_ORIGINAL_${settings.mode.name}_$stamp.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MGCamera/Original")
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
                    val needsProcessing = settings.aiLevel.name != "OFF" ||
                        settings.hdrEnabled ||
                        settings.mode == CameraMode.AI ||
                        settings.mode == CameraMode.NIGHT ||
                        settings.mode == CameraMode.PORTRAIT ||
                        settings.mode == CameraMode.SUPER_ZOOM

                    if (!needsProcessing || settings.mode == CameraMode.PRO) {
                        onSaved(original, false, "Orijinal • PRO")
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
                                    onSaved(original, false, "İşleme başarısız; orijinal korundu")
                                }
                            }
                        } catch (t: Throwable) {
                            ContextCompat.getMainExecutor(context).execute {
                                onProcessing(false)
                                onError(t)
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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
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
}
