package com.mg.camera.processing

import android.content.Context
import android.net.Uri
import com.mg.camera.model.CaptureSettings

class ProcessingPipeline(context: Context) {
    private val enhancer = PhotoEnhancer(context.applicationContext)

    fun process(source: Uri, settings: CaptureSettings): PhotoEnhancer.Result {
        return enhancer.enhance(source, settings)
    }
}
