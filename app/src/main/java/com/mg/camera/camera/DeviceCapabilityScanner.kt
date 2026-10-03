package com.mg.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

class DeviceCapabilityScanner(private val context: Context) {

    fun scan(): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val root = JSONObject()
        root.put("manufacturer", android.os.Build.MANUFACTURER)
        root.put("model", android.os.Build.MODEL)
        root.put("device", android.os.Build.DEVICE)
        root.put("android", android.os.Build.VERSION.RELEASE)
        root.put("sdk", android.os.Build.VERSION.SDK_INT)

        val cameras = JSONArray()
        manager.cameraIdList.forEach { cameraId ->
            val c = manager.getCameraCharacteristics(cameraId)
            val item = JSONObject()
            item.put("id", cameraId)
            item.put("lensFacing", lensFacingName(c.get(CameraCharacteristics.LENS_FACING)))
            item.put("hardwareLevel", hardwareLevelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
            item.put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1)
            item.put("maxDigitalZoom", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble() ?: 1.0)
            item.put("minFocusDistance", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.toDouble() ?: 0.0)
            item.put("focalLengths", floatArrayToJson(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
            item.put("apertures", floatArrayToJson(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)))

            val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            item.put("raw", capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
            item.put("manualSensor", capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
            item.put("logicalMultiCamera", capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA))
            item.put("burstCapture", capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE))

            val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
            item.put("oisAvailable", ois.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON })

            val videoStab = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
            item.put("videoStabilizationModes", intArrayToJson(videoStab))

            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val fpsArray = JSONArray()
            ranges?.forEach { fpsArray.put("${it.lower}-${it.upper}") }
            item.put("fpsRanges", fpsArray)

            val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            if (pixelArray != null) item.put("sensorPixelArray", "${pixelArray.width}x${pixelArray.height}")

            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (active != null) item.put("activeArray", "${active.width()}x${active.height()}")

            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            item.put("maxJpeg", maxSizeString(map?.getOutputSizes(ImageFormat.JPEG)))
            item.put("maxYuv", maxSizeString(map?.getOutputSizes(ImageFormat.YUV_420_888)))

            cameras.put(item)
        }
        root.put("cameras", cameras)
        return root.toString(2)
    }

    private fun maxSizeString(sizes: Array<Size>?): String {
        val s = sizes?.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: return "unknown"
        val mp = s.width.toLong() * s.height.toLong() / 1_000_000.0
        return "${s.width}x${s.height} (%.1f MP)".format(mp)
    }

    private fun lensFacingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun hardwareLevelName(value: Int?): String = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun floatArrayToJson(values: FloatArray?): JSONArray = JSONArray().apply {
        values?.forEach { put(it.toDouble()) }
    }

    private fun intArrayToJson(values: IntArray): JSONArray = JSONArray().apply {
        values.forEach { put(it) }
    }
}
