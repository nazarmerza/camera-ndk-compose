#include <jni.h>
#include <cstdint>
#include <android/log.h>

#define LOG_TAG "NativeProcessor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Helper macro for safe pointer access
#define PTR_AT_ROW(ptr, row, rowStride) ((ptr) + (row) * (rowStride))

extern "C"
JNIEXPORT void JNICALL
Java_com_nmerza_ndk_camera_NativeProcessor_processYuvFrame(
        JNIEnv *env,
        jobject thiz,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint width,
        jint height,
        jint yRowStride,
        jint uRowStride,
        jint vRowStride,
        jint uPixelStride,
        jint vPixelStride,
        jobject outputArgbBuffer // direct IntBuffer
) {
    // Get raw pointers
    uint8_t* yPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    uint8_t* uPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    uint8_t* vPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    uint32_t* argbPtr = static_cast<uint32_t*>(env->GetDirectBufferAddress(outputArgbBuffer));

    if (!yPtr || !uPtr || !vPtr || !argbPtr) {
        LOGD("One of the buffers is null");
        return;
    }

    // Iterate over all pixels
    for (int y = 0; y < height; y++) {
        uint8_t* yRow = PTR_AT_ROW(yPtr, y, yRowStride);

        // UV planes are half resolution for 4:2:0
        int uvRowIndex = y / 2;
        uint8_t* uRow = PTR_AT_ROW(uPtr, uvRowIndex, uRowStride);
        uint8_t* vRow = PTR_AT_ROW(vPtr, uvRowIndex, vRowStride);

        for (int x = 0; x < width; x++) {
            uint8_t Y = yRow[x];

            // Grayscale: copy Y to R/G/B
            uint8_t gray = Y;
            uint32_t argb = (0xFF << 24) | (gray << 16) | (gray << 8) | gray;

            argbPtr[y * width + x] = argb;

            // If you wanted color conversion:
            // int uvColIndex = x / 2;
            // uint8_t U = uRow[uvColIndex * uPixelStride];
            // uint8_t V = vRow[uvColIndex * vPixelStride];
            // ... convert YUV -> RGB
        }
    }

    LOGD("Finished grayscale conversion for %dx%d", width, height);
}
