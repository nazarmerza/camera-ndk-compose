package com.nmerza.ndk.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.nio.ByteBuffer

class VideoEncoder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val onFinished: (Uri) -> Unit
) {

    private val codec: MediaCodec =
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private var started = false
    private var pts = 0L

    fun start() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        )
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 5)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DISPLAY_NAME, "processed_${System.currentTimeMillis()}")
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        )!!

        muxer = MediaMuxer(
            context.contentResolver.openFileDescriptor(uri, "w")!!.fileDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        this.outputUri = uri
        this.inputSurface = surface
    }

    private lateinit var inputSurface: android.view.Surface
    private lateinit var outputUri: Uri

    fun encodeFrame(bitmap: Bitmap) {
        val canvas = inputSurface.lockCanvas(null)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        inputSurface.unlockCanvasAndPost(canvas)

        drain(false)
    }

    fun stop() {
        drain(true)
        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
        onFinished(outputUri)
    }

    private fun drain(end: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
                started = true
            } else if (index >= 0) {
                if (bufferInfo.size > 0 && started) {
                    val buf = codec.getOutputBuffer(index)!!
                    muxer.writeSampleData(trackIndex, buf, bufferInfo)
                }
                codec.releaseOutputBuffer(index, false)
            }
        }
    }
}
