package com.nmerza.ndk.camera

import android.graphics.ImageFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.nmerza.ndk.camera.CameraManager.Companion.yuvLayout


class FrameAnalyzer(
    private val frame: YuvFrame,
    private val onFrameAvailable: (YuvFrame) -> Unit
) : ImageAnalysis.Analyzer {

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {

        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }

        try {
            if (image.format != ImageFormat.YUV_420_888) {
                return
            }

            val planes = image.planes

            frame.width = image.width
            frame.height = image.height
            frame.rotationDegrees = image.imageInfo.rotationDegrees

            frame.yBuffer = planes[0].buffer
            frame.uBuffer = planes[1].buffer
            frame.vBuffer = planes[2].buffer

            frame.yRowStride = planes[0].rowStride
            frame.uRowStride = planes[1].rowStride
            frame.vRowStride = planes[2].rowStride

            frame.uPixelStride = planes[1].pixelStride
            frame.vPixelStride = planes[2].pixelStride

            // IMPORTANT: rewind only, never copy
            frame.yBuffer.rewind()
            frame.uBuffer.rewind()
            frame.vBuffer.rewind()


            onFrameAvailable(frame)

        } finally {
            image.close()
        }
    }
}