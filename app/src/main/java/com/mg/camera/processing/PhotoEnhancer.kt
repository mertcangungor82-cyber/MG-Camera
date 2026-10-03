package com.mg.camera.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.mg.camera.model.AiLevel
import com.mg.camera.model.CameraMode
import com.mg.camera.model.CaptureSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * M&G Camera computational still pipeline.
 *
 * It intentionally avoids inventing scene detail. The pipeline combines adaptive tone mapping,
 * smart-HDR tone compression, scene-aware color, shadow denoise, edge-preserving detail and a
 * deterministic portrait background treatment. The source JPEG can optionally be retained.
 */
class PhotoEnhancer(private val context: Context) {

    data class Result(
        val uri: Uri?,
        val width: Int,
        val height: Int,
        val processingMs: Long,
        val note: String
    )

    private enum class Scene(val label: String) {
        NIGHT("LOW LIGHT"),
        GREENERY("GREENERY"),
        SKY("SKY"),
        DOCUMENT("TEXT"),
        PORTRAIT("PORTRAIT"),
        GENERAL("GENERAL")
    }

    private data class Preset(
        val toneStrength: Float,
        val shadowLift: Float,
        val highlightCompression: Float,
        val saturation: Float,
        val sharpen: Float,
        val darkDenoise: Float,
        val gamma: Float
    )

    fun enhance(source: Uri, settings: CaptureSettings): Result {
        val start = System.currentTimeMillis()
        val decoded = decodeOriented(source)
            ?: return Result(null, 0, 0, 0, "decode_failed")

        val originalWidth = decoded.width
        val originalHeight = decoded.height
        val originalCount = originalWidth.toLong() * originalHeight.toLong()
        val pixelCap = if (settings.mode == CameraMode.SUPER_ZOOM) {
            SUPER_ZOOM_WORKING_PIXELS
        } else {
            MAX_WORKING_PIXELS
        }

        val working = if (originalCount > pixelCap) {
            val scale = sqrt(pixelCap.toDouble() / originalCount.toDouble()).toFloat()
            Bitmap.createScaledBitmap(
                decoded,
                max(1, (originalWidth * scale).toInt()),
                max(1, (originalHeight * scale).toInt()),
                true
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded

        val w = working.width
        val h = working.height
        val pixels = IntArray(w * h)
        working.getPixels(pixels, 0, w, 0, 0, w, h)
        working.recycle()

        val scene = detectScene(pixels, w, h, settings.mode)
        val preset = presetFor(settings.aiLevel, settings.mode, settings.hdrEnabled, scene)
        val bounds = estimateLumaBounds(pixels)
        toneAndColor(pixels, bounds.first, bounds.second, preset)
        detailPass(pixels, w, h, preset)

        var output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, w, 0, 0, w, h)

        if (settings.mode == CameraMode.PORTRAIT) {
            val portrait = applyPortraitEffect(output)
            if (portrait !== output) output.recycle()
            output = portrait
        }

        val uri = writeEnhanced(output, settings.mode)
        output.recycle()

        val scaled = w != originalWidth || h != originalHeight
        val flags = buildList {
            add(scene.label)
            if (settings.hdrEnabled) add("SMART HDR")
            if (settings.mode == CameraMode.PORTRAIT) add("DEPTH FX")
            if (settings.mode == CameraMode.SUPER_ZOOM) add("ZOOM DETAIL")
            if (scaled) add("SAFE ${w}x${h}")
        }

        return Result(
            uri = uri,
            width = w,
            height = h,
            processingMs = System.currentTimeMillis() - start,
            note = flags.joinToString(" • ")
        )
    }

    private fun presetFor(
        aiLevel: AiLevel,
        mode: CameraMode,
        hdr: Boolean,
        scene: Scene
    ): Preset {
        var p = when (mode) {
            CameraMode.NIGHT -> Preset(0.72f, 0.34f, 0.40f, 1.06f, 0.11f, 0.42f, 0.92f)
            CameraMode.PORTRAIT -> Preset(0.42f, 0.10f, 0.20f, 1.03f, 0.10f, 0.11f, 0.99f)
            CameraMode.SUPER_ZOOM -> Preset(0.50f, 0.11f, 0.20f, 1.05f, 0.34f, 0.10f, 0.98f)
            CameraMode.AI -> when (aiLevel) {
                AiLevel.NATURAL -> Preset(0.32f, 0.08f, 0.15f, 1.03f, 0.10f, 0.09f, 0.99f)
                AiLevel.AI -> Preset(0.50f, 0.13f, 0.23f, 1.08f, 0.18f, 0.13f, 0.97f)
                AiLevel.AI_MAX -> Preset(0.64f, 0.18f, 0.30f, 1.11f, 0.24f, 0.18f, 0.95f)
                AiLevel.OFF -> Preset(0.18f, 0.04f, 0.08f, 1.01f, 0.06f, 0.05f, 1.0f)
            }
            CameraMode.PRO -> Preset(0f, 0f, 0f, 1f, 0f, 0f, 1f)
            else -> when (aiLevel) {
                AiLevel.NATURAL -> Preset(0.28f, 0.07f, 0.13f, 1.03f, 0.10f, 0.08f, 0.99f)
                AiLevel.AI -> Preset(0.44f, 0.11f, 0.20f, 1.07f, 0.16f, 0.12f, 0.98f)
                AiLevel.AI_MAX -> Preset(0.58f, 0.16f, 0.27f, 1.10f, 0.22f, 0.16f, 0.96f)
                AiLevel.OFF -> Preset(0.10f, 0.03f, 0.05f, 1.01f, 0.04f, 0.04f, 1.0f)
            }
        }

        p = when (scene) {
            Scene.NIGHT -> p.copy(
                shadowLift = (p.shadowLift + 0.08f).coerceAtMost(0.42f),
                darkDenoise = (p.darkDenoise + 0.10f).coerceAtMost(0.52f),
                saturation = min(p.saturation, 1.06f)
            )
            Scene.GREENERY -> p.copy(saturation = min(1.12f, p.saturation + 0.025f))
            Scene.SKY -> p.copy(highlightCompression = min(0.38f, p.highlightCompression + 0.06f))
            Scene.DOCUMENT -> p.copy(saturation = min(p.saturation, 1.01f), sharpen = min(0.36f, p.sharpen + 0.10f))
            Scene.PORTRAIT -> p.copy(saturation = min(p.saturation, 1.05f), sharpen = min(p.sharpen, 0.16f))
            Scene.GENERAL -> p
        }

        if (hdr && mode != CameraMode.PRO) {
            p = p.copy(
                toneStrength = min(0.78f, p.toneStrength + 0.09f),
                shadowLift = min(0.42f, p.shadowLift + 0.08f),
                highlightCompression = min(0.46f, p.highlightCompression + 0.10f)
            )
        }
        return p
    }

    private fun detectScene(pixels: IntArray, w: Int, h: Int, mode: CameraMode): Scene {
        if (mode == CameraMode.PORTRAIT) return Scene.PORTRAIT
        if (pixels.isEmpty()) return Scene.GENERAL

        val stride = max(1, pixels.size / 80_000)
        var samples = 0
        var lumaSum = 0.0
        var satSum = 0.0
        var greenScore = 0
        var blueScore = 0
        var brightNeutral = 0
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val sat = if (hi == 0) 0f else (hi - lo).toFloat() / hi.toFloat()
            val y = (54 * r + 183 * g + 19 * b) shr 8
            lumaSum += y
            satSum += sat
            if (g > r * 1.10f && g > b * 1.08f && g > 65) greenScore++
            if (b > r * 1.10f && b > g * 1.03f && b > 75) blueScore++
            if (y > 178 && sat < 0.12f) brightNeutral++
            samples++
            i += stride
        }
        if (samples == 0) return Scene.GENERAL
        val avgLuma = lumaSum / samples
        val greenRatio = greenScore.toDouble() / samples
        val blueRatio = blueScore.toDouble() / samples
        val neutralRatio = brightNeutral.toDouble() / samples

        return when {
            avgLuma < 72.0 || mode == CameraMode.NIGHT -> Scene.NIGHT
            neutralRatio > 0.55 && avgLuma > 145.0 -> Scene.DOCUMENT
            greenRatio > 0.25 -> Scene.GREENERY
            blueRatio > 0.22 -> Scene.SKY
            else -> Scene.GENERAL
        }
    }

    private fun estimateLumaBounds(pixels: IntArray): Pair<Float, Float> {
        val hist = IntArray(256)
        val stride = max(1, pixels.size / 250_000)
        var samples = 0
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val y = ((54 * r + 183 * g + 19 * b) shr 8).coerceIn(0, 255)
            hist[y]++
            samples++
            i += stride
        }
        if (samples == 0) return 0f to 255f
        val lowTarget = (samples * 0.008f).toInt()
        val highTarget = (samples * 0.992f).toInt()
        var cumulative = 0
        var low = 0
        var high = 255
        for (v in 0..255) {
            cumulative += hist[v]
            if (cumulative >= lowTarget) {
                low = v
                break
            }
        }
        cumulative = 0
        for (v in 0..255) {
            cumulative += hist[v]
            if (cumulative >= highTarget) {
                high = v
                break
            }
        }
        if (high - low < 72) {
            val mid = (high + low) / 2
            low = max(0, mid - 36)
            high = min(255, mid + 36)
        }
        return low.toFloat() to high.toFloat()
    }

    private fun toneAndColor(pixels: IntArray, low: Float, high: Float, p: Preset) {
        val invRange = 1f / max(1f, high - low)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 255
            var r = ((c shr 16) and 255).toFloat()
            var g = ((c shr 8) and 255).toFloat()
            var b = (c and 255).toFloat()

            val y = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(0.5f)
            var t = ((y - low) * invRange).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            t = t * (1f - p.toneStrength) + smooth * p.toneStrength
            t = t.toDouble().pow(p.gamma.toDouble()).toFloat()

            val shadowWeight = ((0.45f - t) / 0.45f).coerceIn(0f, 1f)
            val highlightWeight = ((t - 0.68f) / 0.32f).coerceIn(0f, 1f)
            t += shadowWeight * p.shadowLift * (1f - t)
            t -= highlightWeight * p.highlightCompression * (t - 0.68f)
            t = t.coerceIn(0f, 1f)

            val targetY = t * 255f
            val ratio = (targetY / y).coerceIn(0.45f, 2.25f)
            r = (r * ratio).coerceIn(0f, 255f)
            g = (g * ratio).coerceIn(0f, 255f)
            b = (b * ratio).coerceIn(0f, 255f)

            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val chroma = (maxC - minC) / 255f
            val sat = 1f + (p.saturation - 1f) * (1f - 0.65f * chroma)
            r = (gray + (r - gray) * sat).coerceIn(0f, 255f)
            g = (gray + (g - gray) * sat).coerceIn(0f, 255f)
            b = (gray + (b - gray) * sat).coerceIn(0f, 255f)

            pixels[i] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
    }

    private fun detailPass(pixels: IntArray, w: Int, h: Int, p: Preset) {
        if (w < 3 || h < 3 || (p.sharpen <= 0f && p.darkDenoise <= 0f)) return
        val src = pixels.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val center = src[i]
                val left = src[i - 1]
                val right = src[i + 1]
                val up = src[i - w]
                val down = src[i + w]

                val cr = (center shr 16) and 255
                val cg = (center shr 8) and 255
                val cb = center and 255
                val luma = (54 * cr + 183 * cg + 19 * cb) shr 8

                val darkness = ((80f - luma) / 80f).coerceIn(0f, 1f)
                val denoise = p.darkDenoise * darkness
                val sharpen = p.sharpen * (1f - 0.75f * darkness)

                val lr = (((left shr 16) and 255) + ((right shr 16) and 255) + ((up shr 16) and 255) + ((down shr 16) and 255)) * 0.25f
                val lg = (((left shr 8) and 255) + ((right shr 8) and 255) + ((up shr 8) and 255) + ((down shr 8) and 255)) * 0.25f
                val lb = ((left and 255) + (right and 255) + (up and 255) + (down and 255)) * 0.25f

                val dr = cr * (1f - denoise) + lr * denoise
                val dg = cg * (1f - denoise) + lg * denoise
                val db = cb * (1f - denoise) + lb * denoise

                val nr = (dr + (dr - lr) * sharpen).coerceIn(0f, 255f).toInt()
                val ng = (dg + (dg - lg) * sharpen).coerceIn(0f, 255f).toInt()
                val nb = (db + (db - lb) * sharpen).coerceIn(0f, 255f).toInt()
                val a = (center ushr 24) and 255
                pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /**
     * Deterministic center-weighted portrait rendering. It does not invent a depth map: it softly
     * preserves the central subject zone and blurs only the surrounding background.
     */
    private fun applyPortraitEffect(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 64 || h < 64) return source

        val sw = max(16, w / 22)
        val sh = max(16, h / 22)
        val tiny = Bitmap.createScaledBitmap(source, sw, sh, true)
        val background = Bitmap.createScaledBitmap(tiny, w, h, true)
        tiny.recycle()

        val foreground = source.copy(Bitmap.Config.ARGB_8888, true)
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        val feather = min(w, h) * 0.055f
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
        }
        val rect = RectF(
            w * 0.16f,
            h * 0.08f,
            w * 0.84f,
            h * 0.92f
        )
        maskCanvas.drawOval(rect, maskPaint)

        val fgCanvas = Canvas(foreground)
        val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        fgCanvas.drawBitmap(mask, 0f, 0f, cutPaint)
        cutPaint.xfermode = null
        mask.recycle()

        Canvas(background).drawBitmap(foreground, 0f, 0f, null)
        foreground.recycle()
        return background
    }

    private fun decodeOriented(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val orientation = resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun writeEnhanced(bitmap: Bitmap, mode: CameraMode): Uri? {
        val name = "MG_${mode.name}_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MGCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 97, out)
            } ?: throw IllegalStateException("Çıktı akışı açılamadı")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    companion object {
        private const val MAX_WORKING_PIXELS = 8_500_000L
        private const val SUPER_ZOOM_WORKING_PIXELS = 11_000_000L
    }
}
