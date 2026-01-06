package com.nmerza.ndk.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
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
        Log.d("CameraManager", "takePhoto() called; renderBitmap is ${if (renderBitmap != null) "available" else "null"}")

        // Copy the bitmap to avoid concurrent mutation/recycle in the processing thread
        val bitmapSnapshot = renderBitmap?.let { bmp ->
            try {
                Bitmap.createBitmap(bmp)
            } catch (e: Exception) {
                Log.e("CameraManager", "Failed to copy renderBitmap", e)
                null
            }
        }

        if (bitmapSnapshot == null) {
            Log.w("CameraManager", "takePhoto: no renderBitmap snapshot available, aborting")
            return
        }

        processingExecutor.execute {
            var savedUri: Uri = Uri.EMPTY
            try {
                val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                    .format(System.currentTimeMillis())

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
                    }
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                if (uri != null) {
                    var wroteOk = false
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        wroteOk = bitmapSnapshot.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        Log.d("CameraManager", "takePhoto: compress result=$wroteOk for uri=$uri")
                    } ?: run {
                        Log.e("CameraManager", "takePhoto: openOutputStream returned null for uri=$uri")
                    }

                    if (wroteOk) {
                        savedUri = uri

                        // On older devices, ensure the gallery picks it up immediately
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            try {
                                val path = uri.path
                                if (path != null) {
                                    MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                                } else {
                                    // fallback broadcast
                                    try {
                                        val scanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                                        scanIntent.data = uri
                                        context.sendBroadcast(scanIntent)
                                    } catch (e: Exception) {
                                        Log.w("CameraManager", "Media scan broadcast failed", e)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("CameraManager", "MediaScannerConnection failed", e)
                            }
                        }

                        Log.d("CameraManager", "takePhoto: saved image to $savedUri")
                    } else {
                        Log.e("CameraManager", "takePhoto: failed to write bitmap to uri=$uri")
                    }
                } else {
                    Log.e("CameraManager", "takePhoto: MediaStore insert returned null")
                    // Fallback: try the legacy insertImage which may work on some devices
                    try {
                        val legacy = MediaStore.Images.Media.insertImage(context.contentResolver, bitmapSnapshot, name, "")
                        if (legacy != null) {
                            savedUri = Uri.parse(legacy)
                            Log.d("CameraManager", "takePhoto: fallback insertImage returned $savedUri")
                        } else {
                            Log.e("CameraManager", "takePhoto: fallback insertImage returned null")
                        }
                    } catch (e: Exception) {
                        Log.e("CameraManager", "takePhoto: fallback insertImage failed", e)
                    }
                }

                // Final fallback: write to app external files directory and scan it so it shows in gallery
                if (savedUri == Uri.EMPTY) {
                    try {
                        val picturesDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                        if (picturesDir != null) {
                            val outFile = java.io.File(picturesDir, "$name.jpg")
                            java.io.FileOutputStream(outFile).use { fos ->
                                val ok = bitmapSnapshot.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                                Log.d("CameraManager", "takePhoto: final fallback wrote file=${outFile.absolutePath} ok=$ok")
                            }

                            // Scan file to add to MediaStore and obtain content Uri in callback
                            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("image/jpeg")) { path, scannedUri ->
                                if (scannedUri != null) {
                                    savedUri = scannedUri
                                    Log.d("CameraManager", "takePhoto: scanned file -> $scannedUri")

                                    // Notify UI on main thread
                                    ContextCompat.getMainExecutor(context).execute {
                                        onImageCaptured(savedUri)
                                    }
                                } else {
                                    Log.w("CameraManager", "takePhoto: scanFile returned null for path=$path")
                                }
                            }

                        } else {
                            Log.w("CameraManager", "takePhoto: external pictures dir is null")
                        }
                    } catch (e: Exception) {
                        Log.e("CameraManager", "takePhoto: final fallback save failed", e)
                    }
                }

            } catch (e: Exception) {
                Log.e("CameraManager", "Photo capture failed", e)
            } finally {
                // Recycle the snapshot copy to free memory
                try { bitmapSnapshot.recycle() } catch (_: Exception) {}
            }

            // Only report success back to UI if saving succeeded
            if (savedUri != Uri.EMPTY) {
                ContextCompat.getMainExecutor(context).execute {
                    onImageCaptured(savedUri)
                }
            } else {
                Log.w("CameraManager", "takePhoto: not calling onImageCaptured because save failed")
            }
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
