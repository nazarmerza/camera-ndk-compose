#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <cstdint>

#define LOG_TAG "native-lib"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define CLAMP(x,a,b) std::fmax(a, std::fmin(b,(x)))

#include "filters/BlueArchitecture.hpp"

//-----------------------------
// LUT setup
//-----------------------------
static constexpr int LUT_DIM = 33;
using LutPtr = const float (*)[33][33][33][3];
static LutPtr gLut = &BlueArchitecture;

//-----------------------------
// YUV Layout
//-----------------------------
enum class YuvLayout {
    UNKNOWN = 0,
    PLANAR = 1,
    SEMI_PLANAR_NV12 = 2,
    SEMI_PLANAR_NV21 = 3
};

static YuvLayout gYuvLayout = YuvLayout::UNKNOWN;

// Optional: swap U/V in planar mode (for emulator quirk)
static bool gPlanarUVSwapped = false;

//-----------------------------
// JNI entry: set YUV layout
//-----------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_nmerza_ndk_camera_NativeProcessor_setYuvLayout(
        JNIEnv*, jobject thiz, jint layout
) {
    switch(layout) {
        case 1: gYuvLayout = YuvLayout::PLANAR; break;
        case 2: gYuvLayout = YuvLayout::SEMI_PLANAR_NV12; break;
        case 3: gYuvLayout = YuvLayout::SEMI_PLANAR_NV21; break;
        default: gYuvLayout = YuvLayout::UNKNOWN;
    }
    gPlanarUVSwapped = false; // reset swap flag
}

//-----------------------------
// Trilinear LUT sampling
//-----------------------------
static inline void apply_lut(float r, float g, float b, float out[3]) {
    float rx = r * (LUT_DIM - 1);
    float gx = g * (LUT_DIM - 1);
    float bx = b * (LUT_DIM - 1);

    int x = (int)rx;
    int y = (int)gx;
    int z = (int)bx;

    float dx = rx - x;
    float dy = gx - y;
    float dz = bx - z;

    int x1 = std::min(x + 1, LUT_DIM - 1);
    int y1 = std::min(y + 1, LUT_DIM - 1);
    int z1 = std::min(z + 1, LUT_DIM - 1);

    for (int c = 0; c < 3; ++c) {
        float c00 = (*gLut)[z ][y ][x ][c] * (1 - dx) + (*gLut)[z ][y ][x1][c] * dx;
        float c10 = (*gLut)[z ][y1][x ][c] * (1 - dx) + (*gLut)[z ][y1][x1][c] * dx;
        float c01 = (*gLut)[z1][y ][x ][c] * (1 - dx) + (*gLut)[z1][y ][x1][c] * dx;
        float c11 = (*gLut)[z1][y1][x ][c] * (1 - dx) + (*gLut)[z1][y1][x1][c] * dx;

        float c0 = c00 * (1 - dy) + c10 * dy;
        float c1 = c01 * (1 - dy) + c11 * dy;

        out[c] = CLAMP(c0 * (1 - dz) + c1 * dz, 0.f, 1.f);
    }
}

//-----------------------------
// YUV -> ARGB conversion
//-----------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_nmerza_ndk_camera_NativeProcessor_processYuvFrame(
        JNIEnv* env,
        jobject,
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
        jobject outArgbBuffer
) {
    auto* Y = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* U = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* V = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    auto* out = static_cast<uint32_t*>(env->GetDirectBufferAddress(outArgbBuffer));

    if (!Y || !U || !V || !out) {
        LOGD("Null buffer received");
        return;
    }

    float lutRGB[3];

    for (int j = 0; j < height; ++j) {
        int yRow = j * yRowStride;
        int uvRowU = (j >> 1) * uRowStride;
        int uvRowV = (j >> 1) * vRowStride;

        for (int i = 0; i < width; ++i) {
            int yIdx = yRow + i;
            float Yf = static_cast<float>(Y[yIdx] & 0xFF);
            float Uf = 0.f, Vf = 0.f;

            switch(gYuvLayout) {
                case YuvLayout::SEMI_PLANAR_NV12: {
                    int uvIdx = uvRowU + (i & ~1);
                    Uf = static_cast<float>(U[uvIdx] & 0xFF);
                    Vf = static_cast<float>(U[uvIdx + 1] & 0xFF);
                    break;
                }
                case YuvLayout::SEMI_PLANAR_NV21: {
                    int uvIdx = uvRowV + (i & ~1);
                    Vf = static_cast<float>(V[uvIdx] & 0xFF);
                    Uf = static_cast<float>(V[uvIdx + 1] & 0xFF);
                    break;
                }
                case YuvLayout::PLANAR: {
                    int uIdx = uvRowU + (i >> 1) * uPixelStride;
                    int vIdx = uvRowV + (i >> 1) * vPixelStride;
                    Uf = static_cast<float>(U[uIdx] & 0xFF);
                    Vf = static_cast<float>(V[vIdx] & 0xFF);

                    // Swap U/V for emulator quirk if needed
                    if (gPlanarUVSwapped) std::swap(Uf, Vf);
                    break;
                }
                default: { // fallback NV21
                    int uvIdx = uvRowV + (i & ~1);
                    Vf = static_cast<float>(V[uvIdx] & 0xFF);
                    Uf = static_cast<float>(V[uvIdx + 1] & 0xFF);
                    break;
                }
            }

            // YUV -> RGB
            float C = Yf - 16.f;
            float D = Uf - 128.f;
            float E = Vf - 128.f;

            float r = (298.f*C + 409.f*E + 128.f)/256.f;
            float g = (298.f*C - 100.f*D - 208.f*E + 128.f)/256.f;
            float b = (298.f*C + 516.f*D + 128.f)/256.f;

            r = CLAMP(r / 255.f, 0.f, 1.f);
            g = CLAMP(g / 255.f, 0.f, 1.f);
            b = CLAMP(b / 255.f, 0.f, 1.f);

            apply_lut(r, g, b, lutRGB);

            uint8_t R = static_cast<uint8_t>(lutRGB[0]*255.f);
            uint8_t G = static_cast<uint8_t>(lutRGB[1]*255.f);
            uint8_t B = static_cast<uint8_t>(lutRGB[2]*255.f);

            out[j*width + i] = 0xFF000000 | (B<<16) | (G<<8) | R;
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_nmerza_ndk_camera_NativeProcessor_setActiveFilter(JNIEnv *env, jobject thiz,
                                                           jstring filter_name) {
    // TODO: implement setActiveFilter()
}