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
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
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

    private  var yuvLayout: YuvLayout? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var renderBitmap: Bitmap? = null

    private val analysisExecutor: Executor = Executors.newSingleThreadExecutor()

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
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor, frameAnalyzer)

//            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
//                // Manually copy YUV planes into frame
//                frame.width = imageProxy.width
//                frame.height = imageProxy.height
//
//                val yPlane = imageProxy.planes[0]
//                val uPlane = imageProxy.planes[1]
//                val vPlane = imageProxy.planes[2]
//
//                frame.yBuffer = yPlane.buffer
//                frame.uBuffer = uPlane.buffer
//                frame.vBuffer = vPlane.buffer
//
//                frame.yRowStride = yPlane.rowStride
//                frame.uRowStride = uPlane.rowStride
//                frame.vRowStride = vPlane.rowStride
//
//                frame.uPixelStride = uPlane.pixelStride
//                frame.vPixelStride = vPlane.pixelStride
//
//                frame.rotationDegrees = imageProxy.imageInfo.rotationDegrees
//
//                onFrameAvailable(frame)
//
//                imageProxy.close()
//            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis,
                    imageCapture,
                    videoCapture
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startVideoRecording(
        executor: Executor,
        onVideoSaved: (Uri) -> Unit
    ) {
        val videoCapture = this.videoCapture ?: return

        val name = "Video-${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        activeRecording = videoCapture.output
            .prepareRecording(context, mediaStoreOutput)
            .withAudioEnabled()
            .start(executor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (!event.hasError()) {
                        onVideoSaved(event.outputResults.outputUri)
                    } else {
                        activeRecording?.close()
                        activeRecording = null
                    }
                }
            }
    }

    fun stopVideoRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun takePhoto(
        executor: Executor,
        onImageCaptured: (Uri) -> Unit
    ) {
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture?.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraManager", "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: Uri.EMPTY
                    onImageCaptured(savedUri)
                    Log.d("CameraManager", "Photo capture succeeded: $savedUri")
                }
            }
        )
    }

    private fun onFrameAvailable(frame: YuvFrame) {

        // Detect layout once, and set and pass it to NDK
        if (yuvLayout == null ) {
            yuvLayout = when {
                frame.uPixelStride == 1 && frame.vPixelStride == 1 -> YuvLayout.PLANAR
                frame.uPixelStride == 2 && frame.vPixelStride == 2 -> {
                    // distinguish by memory offset or typical device order
                    if (frame.uBuffer.position() < frame.vBuffer.position())
                        YuvLayout.SEMI_PLANAR_NV12
                    else YuvLayout.SEMI_PLANAR_NV21
                }

                else -> YuvLayout.UNKNOWN
            }

            yuvLayout?.let { layout ->
                NativeProcessor.setYuvLayout(layout.value)
            }
        }

        val argbBuffer = ByteBuffer.allocateDirect(frame.width * frame.height * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()

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
            renderBitmap = Bitmap.createBitmap(frame.width, frame.height,
                Bitmap.Config.ARGB_8888)
        }

        renderFpsCounter.tick()

        argbBuffer.rewind()
        renderBitmap?.copyPixelsFromBuffer(argbBuffer)

        // Rotate bitmap if needed
        val rotatedBitmap = if (frame.rotationDegrees != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(frame.rotationDegrees.toFloat())
            Bitmap.createBitmap(renderBitmap!!, 0, 0, renderBitmap!!.width, renderBitmap!!.height, matrix, true)
        } else renderBitmap

        onGrayscaleBitmap(rotatedBitmap!!)
    }
}
