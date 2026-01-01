package com.nmerza.ndk.camera

import java.nio.ByteBuffer

/**
 * Holds the YUV planes and metadata for a camera frame.
 * Buffers are non-nullable to ensure safe usage in native processing.
 */
data class YuvFrame(
    var width: Int = 0,
    var height: Int = 0,
    var yBuffer: ByteBuffer = ByteBuffer.allocate(0),
    var uBuffer: ByteBuffer = ByteBuffer.allocate(0),
    var vBuffer: ByteBuffer = ByteBuffer.allocate(0),
    var yRowStride: Int = 0,
    var uRowStride: Int = 0,
    var vRowStride: Int = 0,
    var uPixelStride: Int = 0,
    var vPixelStride: Int = 0,
    var rotationDegrees: Int = 0
) {

    /**
     * Populate the YUV planes from ImageProxy planes.
     * This replaces the old `updateFromImageProxy` method.
     */
    fun updateFromPlanes(
        yPlane: ByteBuffer,
        uPlane: ByteBuffer,
        vPlane: ByteBuffer,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ) {
        this.yBuffer = yPlane
        this.uBuffer = uPlane
        this.vBuffer = vPlane
        this.yRowStride = yRowStride
        this.uRowStride = uRowStride
        this.vRowStride = vRowStride
        this.uPixelStride = uPixelStride
        this.vPixelStride = vPixelStride
        this.width = width
        this.height = height
        this.rotationDegrees = rotationDegrees
    }
}
