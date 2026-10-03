package com.mg.camera.processing

/**
 * Contracts for the high-end computational photography stages.
 * They are intentionally explicit so the capture core does not need a rewrite as each engine lands.
 */
interface MultiFrameFusionEngine {
    data class Frame(val timestampNs: Long, val width: Int, val height: Int, val yuvToken: String)
    data class FusionResult(val success: Boolean, val diagnostic: String)
    fun fuse(frames: List<Frame>): FusionResult
}

interface SuperResolutionEngine {
    data class Request(val scale: Float, val textSafe: Boolean, val preserveIdentity: Boolean = true)
    fun isReady(): Boolean
}

interface SemanticSceneEngine {
    enum class Region { FACE, SKIN, HAIR, SKY, TEXT, FOLIAGE, BUILDING, WATER, OTHER }
    fun isReady(): Boolean
}
