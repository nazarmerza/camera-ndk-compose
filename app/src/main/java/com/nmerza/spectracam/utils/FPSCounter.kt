package com.nmerza.spectracam.utils

import android.util.Log

class FPSCounter(
    private val tag: String
) {

    private var lastTime  = System.nanoTime()
    private var frameCount = 0

    fun tick(){
        frameCount++
        val now = System.nanoTime()
        val elapsedSec = (now - lastTime) / 1_000_000_000.0
        if (elapsedSec >= 1.0) {
            val fps = frameCount / elapsedSec
            Log.d(tag, "$tag FPS: %.1f".format(fps))
            frameCount = 0
            lastTime = now
        }

    }

    fun reset() {
        frameCount = 0
        lastTime = System.nanoTime()
    }

}