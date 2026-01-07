// In a new file, e.g., CameraApp.kt
package com.nmerza.ndk

import android.app.Application
import androidx.compose.foundation.layout.add
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class CameraApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // Add the VideoFrameDecoder to Coil's component registry.
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
}
