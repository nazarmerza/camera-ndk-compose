package com.nmerza.ndk.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.nmerza.ndk.utils.FPSCounter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val onGrayscaleBitmap: (Bitmap) -> Unit
) {

    private val renderFpsCounter = FPSCounter("Render")

    private var yuvLayout: YuvLayout? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    private var videoEncoder: VideoEncoder? = null
    private var isRecording = false

    private var renderBitmap: Bitmap? = null

    private val analysisExecutor: Executor = Executors.newSingleThreadExecutor()
    private val processingExecutor: Executor = Executors.newSingleThreadExecutor()

    private var frame = YuvFrame()
    private val frameAnalyzer = FrameAnalyzer(
        frame = frame,
        onFrameAvailable = ::onFrameAvailable
    )

    @RequiresPermission(Manifest.permission.CAMERA)
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cameraSelector: CameraSelector
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor, frameAnalyzer)

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis,
                    imageCapture
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun isRecording(): Boolean = isRecording

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startVideoRecording(onVideoSaved: (Uri) -> Unit) {
        if (isRecording || renderBitmap == null) return

        videoEncoder = VideoEncoder(context, renderBitmap!!.width, renderBitmap!!.height) {
            onVideoSaved(it)
        }
        videoEncoder?.start()
        isRecording = true
    }

    fun stopVideoRecording() {
        videoEncoder?.stop()
        videoEncoder = null
        isRecording = false
    }

    fun takePhoto(onImageCaptured: (Uri) -> Unit) {
        // 1. Grab the current bitmap immediately on the calling thread (usually UI)
        // This makes the capture feel instant.
        val bitmapToSave = renderBitmap ?: return
        if (bitmapToSave.isRecycled) return

        // 2. Prepare the MediaStore values
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // 3. Perform the save operation
        // We do this in a try-block to catch any "recycled bitmap" issues during the save
        try {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { targetUri ->
                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    // The compress operation is the only part that takes time
                    bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }

                Log.d("CameraManager", "Photo saved: $targetUri")

                // 4. Return the result to the UI
                onImageCaptured(targetUri)
            }
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to save photo", e)
        }
    }



    private fun onFrameAvailable(frame: YuvFrame) {
        processingExecutor.execute {
            processFrame(frame)
        }
    }

    private fun processFrame(frame: YuvFrame) {
        // Detect layout once and set to NDK
        if (yuvLayout == null) {
            yuvLayout = when {
                frame.uPixelStride == 1 && frame.vPixelStride == 1 -> YuvLayout.PLANAR
                frame.uPixelStride == 2 && frame.vPixelStride == 2 -> {
                    if (frame.uBuffer.position() < frame.vBuffer.position())
                        YuvLayout.SEMI_PLANAR_NV12
                    else YuvLayout.SEMI_PLANAR_NV21
                }
                else -> YuvLayout.UNKNOWN
            }
            yuvLayout?.let { NativeProcessor.setYuvLayout(it.value) }
        }

        val argbBuffer = ByteBuffer.allocateDirect(frame.width * frame.height * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()

        NativeProcessor.processYuvFrame(
            yBuffer = frame.yBuffer,
            uBuffer = frame.uBuffer,
            vBuffer = frame.vBuffer,
            width = frame.width,
            height = frame.height,
            yRowStride = frame.yRowStride,
            uRowStride = frame.uRowStride,
            vRowStride = frame.vRowStride,
            uPixelStride = frame.uPixelStride,
            vPixelStride = frame.vPixelStride,
            outputArgbBuffer = argbBuffer
        )

        if (renderBitmap == null || renderBitmap?.width != frame.width || renderBitmap?.height != frame.height) {
            renderBitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        }

        renderFpsCounter.tick()

        argbBuffer.rewind()
        renderBitmap?.copyPixelsFromBuffer(argbBuffer)

        // Rotate bitmap if needed
        if (frame.rotationDegrees != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(renderBitmap!!, 0, 0, renderBitmap!!.width, renderBitmap!!.height, matrix, true)
            renderBitmap?.recycle()
            renderBitmap = rotated
        }

        if (isRecording) {
            videoEncoder?.encodeFrame(renderBitmap!!)
        }

        onGrayscaleBitmap(renderBitmap!!)
    }
}
