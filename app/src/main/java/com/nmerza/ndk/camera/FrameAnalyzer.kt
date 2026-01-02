package com.nmerza.ndk.camera

import android.graphics.ImageFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.nmerza.ndk.utils.FPSCounter


class FrameAnalyzer(
    private val frame: YuvFrame,
    private val onFrameAvailable: (YuvFrame) -> Unit
) : ImageAnalysis.Analyzer {

    private val fpsCounter = FPSCounter("Analyzer")

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            if (mediaImage.format != ImageFormat.YUV_420_888) {
                return
            }

// Manually copy YUV planes into frame
            frame.width = imageProxy.width
            frame.height = imageProxy.height

            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            frame.yBuffer = yPlane.buffer
            frame.uBuffer = uPlane.buffer
            frame.vBuffer = vPlane.buffer

            frame.yRowStride = yPlane.rowStride
            frame.uRowStride = uPlane.rowStride
            frame.vRowStride = vPlane.rowStride

            frame.uPixelStride = uPlane.pixelStride
            frame.vPixelStride = vPlane.pixelStride

            frame.rotationDegrees = imageProxy.imageInfo.rotationDegrees

            fpsCounter.tick()  // call once per frame

            onFrameAvailable(frame)


            // IMPORTANT: rewind only, never copy
            frame.yBuffer.rewind()
            frame.uBuffer.rewind()
            frame.vBuffer.rewind()


        } finally {
            imageProxy.close()
        }
    }
}