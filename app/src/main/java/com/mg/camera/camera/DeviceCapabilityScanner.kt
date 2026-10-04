package com.mg.camera.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Exports a hardware/OEM camera profile that M&G Camera can use for device-specific tuning.
 * The report contains only camera capabilities and build/device identifiers.
 */
class DeviceCapabilityScanner(private val context: Context) {

    fun scan(): String = OemCameraCapabilities(context).diagnosticJson()

    fun exportToDocuments(): Uri? {
        val json = scan()
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val relativePath = "Documents/MGCamera"
            val displayName = "MGCamera_device_profile.json"

            runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(displayName, "$relativePath/"),
                    null
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val old = Uri.withAppendedPath(collection, id.toString())
                        runCatching { resolver.delete(old, null, null) }
                    }
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(collection, values) ?: return null
            return try {
                resolver.openOutputStream(uri, "w")?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
                val ready = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, ready, null, null)
                uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }

        return null
    }
}
