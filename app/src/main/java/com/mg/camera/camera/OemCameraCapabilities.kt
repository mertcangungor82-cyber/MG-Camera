package com.mg.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hardware-facing camera capability probe.
 *
 * Important: many OEMs expose binned JPEG sizes in the normal stream map while placing
 * full-resolution/remosaic sizes in the high-resolution or maximum-resolution maps.
 * This class keeps those paths separate so the UI never invents a megapixel option.
 */
class OemCameraCapabilities(context: Context) {

    data class ResolutionInfo(
        val size: Size,
        val highResolution: Boolean,
        val maximumResolution: Boolean
    )

    private val manager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun bestCameraId(lensFacing: Int): String? {
        val wanted = if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        return manager.cameraIdList
            .mapNotNull { id ->
                runCatching {
                    val c = manager.getCameraCharacteristics(id)
                    if (c.get(CameraCharacteristics.LENS_FACING) != wanted) return@runCatching null
                    id to scoreCamera(c)
                }.getOrNull()
            }
            .filterNotNull()
            .maxByOrNull { it.second }
            ?.first
    }

    fun resolutions(cameraId: String?): List<ResolutionInfo> {
        if (cameraId == null) return emptyList()
        val c = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return emptyList()

        val result = linkedMapOf<String, ResolutionInfo>()

        fun add(sizes: Array<Size>?, high: Boolean, maximum: Boolean) {
            sizes?.forEach { size ->
                if (size.width <= 0 || size.height <= 0) return@forEach
                val key = "${size.width}x${size.height}"
                val old = result[key]
                if (old == null || maximum || (high && !old.highResolution)) {
                    result[key] = ResolutionInfo(size, high, maximum)
                }
            }
        }

        val normalMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        add(normalMap?.getOutputSizes(ImageFormat.JPEG), high = false, maximum = false)
        add(normalMap?.getHighResolutionOutputSizes(ImageFormat.JPEG), high = true, maximum = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            add(maxMap?.getOutputSizes(ImageFormat.JPEG), high = true, maximum = true)
            add(maxMap?.getHighResolutionOutputSizes(ImageFormat.JPEG), high = true, maximum = true)
        }

        return result.values.sortedByDescending {
            it.size.width.toLong() * it.size.height.toLong()
        }
    }

    fun isHighResolution(cameraId: String?, size: Size?): Boolean {
        if (cameraId == null || size == null) return false
        return resolutions(cameraId).any {
            it.size.width == size.width &&
                it.size.height == size.height &&
                it.highResolution
        }
    }

    fun isMaximumResolution(cameraId: String?, size: Size?): Boolean {
        if (cameraId == null || size == null) return false
        return resolutions(cameraId).any {
            it.size.width == size.width &&
                it.size.height == size.height &&
                it.maximumResolution
        }
    }

    fun supportsUltraHighResolution(cameraId: String?): Boolean {
        if (cameraId == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val c = manager.getCameraCharacteristics(cameraId)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
        }.getOrDefault(false)
    }

    fun cameraLabel(cameraId: String?): String {
        if (cameraId == null) return "camera ?"
        val c = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return "camera $cameraId"
        val sensor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                ?: c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        } else {
            c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        }
        val mp = sensor?.let { it.width.toLong() * it.height.toLong() / 1_000_000.0 }
        return if (mp != null) "camera $cameraId • %.1fMP sensor".format(mp) else "camera $cameraId"
    }

    fun diagnosticJson(): String {
        val root = JSONObject()
        root.put("manufacturer", Build.MANUFACTURER)
        root.put("model", Build.MODEL)
        root.put("device", Build.DEVICE)
        root.put("sdk", Build.VERSION.SDK_INT)

        val cameras = JSONArray()
        manager.cameraIdList.forEach { id ->
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: return@forEach
            val item = JSONObject()
            item.put("id", id)
            item.put("facing", facingName(c.get(CameraCharacteristics.LENS_FACING)))

            c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                item.put("pixelArray", "${it.width}x${it.height}")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.let {
                    item.put("pixelArrayMaximum", "${it.width}x${it.height}")
                }
            }

            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            item.put(
                "ultraHighResolutionSensor",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
            )

            val resolutionArray = JSONArray()
            resolutions(id).forEach { info ->
                resolutionArray.put(
                    JSONObject()
                        .put("size", "${info.size.width}x${info.size.height}")
                        .put(
                            "mp",
                            info.size.width.toLong() * info.size.height.toLong() / 1_000_000.0
                        )
                        .put("highResolution", info.highResolution)
                        .put("maximumResolution", info.maximumResolution)
                )
            }
            item.put("jpegResolutions", resolutionArray)

            val vendorCharacteristics = JSONObject()
            c.keys
                .filter { key ->
                    val n = key.name
                    n.startsWith("com.transsion.") || n.startsWith("com.mediatek.")
                }
                .forEach { key ->
                    runCatching {
                        val value = c.get(key)
                        vendorCharacteristics.put(key.name, valueToString(value))
                    }
                }
            item.put("vendorCharacteristics", vendorCharacteristics)

            val requestKeys = JSONArray()
            runCatching { c.availableCaptureRequestKeys }.getOrNull()
                ?.filter {
                    it.name.startsWith("com.transsion.") ||
                        it.name.startsWith("com.mediatek.")
                }
                ?.forEach { requestKeys.put(it.name) }
            item.put("vendorCaptureRequestKeys", requestKeys)

            cameras.put(item)
        }
        root.put("cameras", cameras)
        return root.toString(2)
    }

    private fun scoreCamera(c: CameraCharacteristics): Long {
        var best = 0L

        fun consider(size: Size?) {
            if (size != null) {
                best = maxOf(best, size.width.toLong() * size.height.toLong())
            }
        }

        consider(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            consider(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION))
        }

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        map?.getOutputSizes(ImageFormat.JPEG)?.forEach(::consider)
        map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.forEach(::consider)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            maxMap?.getOutputSizes(ImageFormat.JPEG)?.forEach(::consider)
            maxMap?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.forEach(::consider)
        }

        return best
    }

    private fun facingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun valueToString(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> value.joinToString(prefix = "[", postfix = "]") { (it.toInt() and 0xff).toString() }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
        else -> value.toString()
    }
}
