package com.mg.camera.model

enum class AiLevel(val label: String) {
    OFF("OFF"),
    NATURAL("NATURAL"),
    AI("AI"),
    AI_MAX("AI MAX");

    fun next(): AiLevel = entries[(ordinal + 1) % entries.size]
}
