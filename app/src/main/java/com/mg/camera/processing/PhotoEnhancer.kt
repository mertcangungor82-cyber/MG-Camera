package com.mg.camera.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.mg.camera.model.AiLevel
import com.mg.camera.model.CameraMode
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * First production image pipeline for M&G Camera.
 *
 * This is deliberately deterministic: it never hallucinates text, faces or scene details.
 * It performs adaptive tone mapping, color recovery, shadow-aware denoise and detail sharpening.
 * Multi-frame fusion and neural super-resolution are plugged in later at a higher layer.
 */
class PhotoEnhancer(private val context: Context) {

    data class Result(
        val uri: Uri?,
        val width: Int,
        val height: Int,
        val processingMs: Long,
        val note: String
    )

    private data class Preset(
        val toneStrength: Float,
        val shadowLift: Float,
        val highlightCompression: Float,
        val saturation: Float,
        val sharpen: Float,
        val darkDenoise: Float,
        val gamma: Float
    )

    fun enhance(source: Uri, aiLevel: AiLevel, mode: CameraMode): Result {
        val start = System.currentTimeMillis()
        val decoded = decodeOriented(source)
            ?: return Result(null, 0, 0, 0, "decode_failed")

        val preset = presetFor(aiLevel, mode)
        val width = decoded.width
        val height = decoded.height
        val count = width * height

        // Protect lower-memory devices. CameraX usually outputs a binned still, but an OEM may expose 64 MP.
        // We keep the original untouched; the AI derivative is capped to a safe working size.
        val working = if (count > MAX_WORKING_PIXELS) {
            val scale = kotlin.math.sqrt(MAX_WORKING_PIXELS.toDouble() / count.toDouble()).toFloat()
            Bitmap.createScaledBitmap(
                decoded,
                max(1, (width * scale).toInt()),
                max(1, (height * scale).toInt()),
                true
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded

        val w = working.width
        val h = working.height
        val pixels = IntArray(w * h)
        working.getPixels(pixels, 0, w, 0, 0, w, h)
        working.recycle()

        val bounds = estimateLumaBounds(pixels)
        toneAndColor(pixels, bounds.first, bounds.second, preset)
        detailPass(pixels, w, h, preset)

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, w, 0, 0, w, h)
        val uri = writeEnhanced(output, mode)
        output.recycle()

        return Result(
            uri = uri,
            width = w,
            height = h,
            processingMs = System.currentTimeMillis() - start,
            note = if (w == width && h == height) "full_resolution" else "safe_scaled_${w}x$h"
        )
    }

    private fun presetFor(aiLevel: AiLevel, mode: CameraMode): Preset {
        if (aiLevel == AiLevel.OFF && mode == CameraMode.PHOTO) {
            return Preset(0.10f, 0.03f, 0.05f, 1.01f, 0.04f, 0.04f, 1.0f)
        }
        return when (mode) {
            CameraMode.NIGHT -> Preset(0.64f, 0.28f, 0.36f, 1.08f, 0.12f, 0.34f, 0.94f)
            CameraMode.SUPER_ZOOM -> Preset(0.50f, 0.12f, 0.20f, 1.07f, 0.30f, 0.10f, 0.97f)
            CameraMode.AI -> when (aiLevel) {
                AiLevel.NATURAL -> Preset(0.32f, 0.08f, 0.15f, 1.03f, 0.10f, 0.09f, 0.99f)
                AiLevel.AI -> Preset(0.50f, 0.13f, 0.23f, 1.08f, 0.18f, 0.13f, 0.97f)
                AiLevel.AI_MAX -> Preset(0.66f, 0.18f, 0.30f, 1.12f, 0.25f, 0.18f, 0.95f)
                AiLevel.OFF -> Preset(0.18f, 0.04f, 0.08f, 1.01f, 0.06f, 0.05f, 1.0f)
            }
            else -> when (aiLevel) {
                AiLevel.NATURAL -> Preset(0.28f, 0.07f, 0.13f, 1.03f, 0.10f, 0.08f, 0.99f)
                AiLevel.AI -> Preset(0.46f, 0.11f, 0.20f, 1.07f, 0.17f, 0.12f, 0.98f)
                AiLevel.AI_MAX -> Preset(0.60f, 0.16f, 0.28f, 1.11f, 0.23f, 0.16f, 0.96f)
                AiLevel.OFF -> Preset(0.10f, 0.03f, 0.05f, 1.01f, 0.04f, 0.04f, 1.0f)
            }
        }
    }

    private fun estimateLumaBounds(pixels: IntArray): Pair<Float, Float> {
        val hist = IntArray(256)
        val stride = max(1, pixels.size / 250_000)
        var samples = 0
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
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
            if (cumulative >= lowTarget) { low = v; break }
        }
        cumulative = 0
        for (v in 0..255) {
            cumulative += hist[v]
            if (cumulative >= highTarget) { high = v; break }
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
            val a = (c ushr 24) and 0xFF
            var r = ((c shr 16) and 0xFF).toFloat()
            var g = ((c shr 8) and 0xFF).toFloat()
            var b = (c and 0xFF).toFloat()

            val y = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(0.5f)
            var t = ((y - low) * invRange).coerceIn(0f, 1f)
            // Smooth S-curve avoids harsh clipping from a simple levels operation.
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
            // Vibrance: already-saturated pixels receive less extra saturation.
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val chroma = (maxC - minC) / 255f
            val sat = 1f + (p.saturation - 1f) * (1f - 0.65f * chroma)
            r = (gray + (r - gray) * sat).coerceIn(0f, 255f)
            g = (gray + (g - gray) * sat).coerceIn(0f, 255f)
            b = (gray + (b - gray) * sat).coerceIn(0f, 255f)

            pixels[i] = (a shl 24) or
                (r.toInt() shl 16) or
                (g.toInt() shl 8) or
                b.toInt()
        }
    }

    private fun detailPass(pixels: IntArray, w: Int, h: Int, p: Preset) {
        if (w < 3 || h < 3) return
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

                val cr = (center shr 16) and 0xFF
                val cg = (center shr 8) and 0xFF
                val cb = center and 0xFF
                val luma = (54 * cr + 183 * cg + 19 * cb) shr 8

                val darkness = ((80f - luma) / 80f).coerceIn(0f, 1f)
                val denoise = p.darkDenoise * darkness
                val sharpen = p.sharpen * (1f - 0.75f * darkness)

                val lr = (((left shr 16) and 0xFF) + ((right shr 16) and 0xFF) + ((up shr 16) and 0xFF) + ((down shr 16) and 0xFF)) * 0.25f
                val lg = (((left shr 8) and 0xFF) + ((right shr 8) and 0xFF) + ((up shr 8) and 0xFF) + ((down shr 8) and 0xFF)) * 0.25f
                val lb = ((left and 0xFF) + (right and 0xFF) + (up and 0xFF) + (down and 0xFF)) * 0.25f

                val dr = cr * (1f - denoise) + lr * denoise
                val dg = cg * (1f - denoise) + lg * denoise
                val db = cb * (1f - denoise) + lb * denoise

                val nr = (dr + (dr - lr) * sharpen).coerceIn(0f, 255f).toInt()
                val ng = (dg + (dg - lg) * sharpen).coerceIn(0f, 255f).toInt()
                val nb = (db + (db - lb) * sharpen).coerceIn(0f, 255f).toInt()
                val a = (center ushr 24) and 0xFF
                pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
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
        val name = "NOVACAM_AI_${mode.name}_${System.currentTimeMillis()}.jpg"
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
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
            } ?: throw IllegalStateException("Cannot open output stream")
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
        // ~24 MP ARGB working set + source array is already substantial on mid-range devices.
        private const val MAX_WORKING_PIXELS = 8_500_000
    }
}
