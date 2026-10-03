package com.mg.camera.model

data class CaptureSettings(
    val mode: CameraMode,
    val aiLevel: AiLevel,
    val hdrEnabled: Boolean,
    val keepOriginal: Boolean
)
