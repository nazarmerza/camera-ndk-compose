package com.nmerza.ndk.camera

enum class YuvLayout(val value: Int) {
    UNKNOWN(0),
    PLANAR(1),
    SEMI_PLANAR_NV12(2),
    SEMI_PLANAR_NV21(3)
}