package com.mg.camera.processing

import android.content.Context
import android.net.Uri
import com.mg.camera.model.AiLevel
import com.mg.camera.model.CameraMode

/**
 * Single entry point for still processing.
 * Future modules (multi-frame fusion, semantic masks, neural SR) slot in here without changing UI/camera code.
 */
class ProcessingPipeline(context: Context) {
    private val enhancer = PhotoEnhancer(context.applicationContext)

    fun process(source: Uri, level: AiLevel, mode: CameraMode): PhotoEnhancer.Result {
        return enhancer.enhance(source, level, mode)
    }
}
