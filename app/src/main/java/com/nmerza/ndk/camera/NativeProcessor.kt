package com.nmerza.ndk.camera

import java.nio.ByteBuffer
import java.nio.IntBuffer

object NativeProcessor {


    init {
        // Load the native library
        System.loadLibrary("native-lib")
    }
    external fun setYuvLayout(layout: Int)
    /**
     * Process a single YUV frame in native code.
     *
     * All buffers must be direct ByteBuffers and rewound before calling.
     */
    external fun processYuvFrame(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        outputArgbBuffer: IntBuffer
    )
}
