package com.nmerza.spectracam.camera

import android.graphics.ImageFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.nmerza.spectracam.utils.FPSCounter
import java.nio.ByteBuffer


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
                imageProxy.close()
                return
            }

            // Manually copy YUV planes into frame-owned direct ByteBuffers so buffers are
            // valid after imageProxy.close(). ImageProxy plane buffers may be freed when
            // the ImageProxy is closed, which causes native GetDirectBufferAddress to
            // return null.

            frame.width = imageProxy.width
            frame.height = imageProxy.height

            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            // Allocate direct buffers sized to the plane limits and copy data
            val ySize = yPlane.buffer.remaining()
            val uSize = uPlane.buffer.remaining()
            val vSize = vPlane.buffer.remaining()

            val yBufCopy = ByteBuffer.allocateDirect(ySize)
            val uBufCopy = ByteBuffer.allocateDirect(uSize)
            val vBufCopy = ByteBuffer.allocateDirect(vSize)

            // Duplicate and read from source buffers without changing their position
            val ySrc = yPlane.buffer.duplicate()
            val uSrc = uPlane.buffer.duplicate()
            val vSrc = vPlane.buffer.duplicate()

            ySrc.rewind(); uSrc.rewind(); vSrc.rewind()

            yBufCopy.put(ySrc)
            uBufCopy.put(uSrc)
            vBufCopy.put(vSrc)

            // Rewind copies for native access
            yBufCopy.rewind()
            uBufCopy.rewind()
            vBufCopy.rewind()

            frame.yBuffer = yBufCopy
            frame.uBuffer = uBufCopy
            frame.vBuffer = vBufCopy

            frame.yRowStride = yPlane.rowStride
            frame.uRowStride = uPlane.rowStride
            frame.vRowStride = vPlane.rowStride

            frame.uPixelStride = uPlane.pixelStride
            frame.vPixelStride = vPlane.pixelStride

            frame.rotationDegrees = imageProxy.imageInfo.rotationDegrees

            fpsCounter.tick()  // call once per frame

            onFrameAvailable(frame)


        } finally {
            imageProxy.close()
        }
    }
}