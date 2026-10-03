package com.mg.camera.model

enum class FlashState(val label: String) {
    AUTO("AUTO"), OFF("OFF"), ON("ON");

    fun next(): FlashState = entries[(ordinal + 1) % entries.size]
}
