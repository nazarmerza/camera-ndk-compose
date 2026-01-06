package com.nmerza.ndk.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.*
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class VideoEncoder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val onFinished: (Uri) -> Unit
) {
    private val TAG = "VideoEncoder"
    private val TIMEOUT_US = 10000L

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null

    private val isRecording = AtomicBoolean(false)
    private val muxerStarted = AtomicBoolean(false)
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val muxerLock = Any()

    private var startTimeUs: Long = 0
    private var lastVideoPtsUs: Long = -1L
    private var audioPresentationTimeUs: Long = 0L

    private lateinit var outputUri: Uri
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    fun start() {
        if (!isRecording.compareAndSet(false, true)) return

        startTimeUs = System.nanoTime() / 1000
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted.set(false)
        lastVideoPtsUs = -1L
        audioPresentationTimeUs = 0L

        setupVideoEncoder()
        setupAudioEncoder()
        setupMuxer()

        videoCodec?.start()
        audioCodec?.start()
        startAudioCaptureThread()
    }

    private fun setupVideoEncoder() {
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 5)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoCodec?.createInputSurface()
    }

    private fun setupAudioEncoder() {
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384) // Increase input buffer stability
        }
        audioCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun setupMuxer() {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DISPLAY_NAME, "Guardian_${System.currentTimeMillis()}.mp4")
        }
        outputUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        val pfd = context.contentResolver.openFileDescriptor(outputUri, "w")!!
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun encodeFrame(bitmap: Bitmap) {
        if (!isRecording.get()) return

        inputSurface?.let { surface ->
            try {
                val canvas: Canvas = surface.lockCanvas(null)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                surface.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "Canvas error: ${e.message}")
            }
        }
        drainEncoder(videoCodec, true, false)
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureThread() {
        val sampleRate = 44100
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 2)

        audioThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            audioRecord?.startRecording()

            val buffer = ByteArray(minBufSize)

            while (isRecording.get()) {
                val read = audioRecord?.read(buffer, 0, minBufSize) ?: 0
                if (read > 0) {
                    var offset = 0
                    var remaining = read

                    // Loop to feed the encoder in case 'read' is larger than encoder capacity
                    while (remaining > 0) {
                        val index = audioCodec?.dequeueInputBuffer(TIMEOUT_US) ?: -1
                        if (index >= 0) {
                            val codecBuffer = audioCodec?.getInputBuffer(index)!!
                            codecBuffer.clear()

                            val capacity = codecBuffer.remaining()
                            val chunk = Math.min(remaining, capacity)

                            codecBuffer.put(buffer, offset, chunk)
                            audioCodec?.queueInputBuffer(index, 0, chunk, audioPresentationTimeUs, 0)

                            // Progress time and counters
                            audioPresentationTimeUs += (chunk.toLong() * 1_000_000L) / (sampleRate * 2)
                            offset += chunk
                            remaining -= chunk
                        }
                        drainEncoder(audioCodec, false, false)
                    }
                }
            }
        }
        audioThread?.start()
    }

    private fun drainEncoder(encoder: MediaCodec?, isVideo: Boolean, endOfStream: Boolean) {
        if (encoder == null) return
        if (isVideo && endOfStream) videoCodec?.signalEndOfInputStream()

        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, 0) // No blocking
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    val track = muxer!!.addTrack(encoder.outputFormat)
                    if (isVideo) videoTrackIndex = track else audioTrackIndex = track

                    if (videoTrackIndex != -1 && audioTrackIndex != -1 && !muxerStarted.get()) {
                        muxer!!.start()
                        muxerStarted.set(true)
                        Log.d(TAG, "Muxer started successfully.")
                    }
                }
            } else if (outIndex >= 0) {
                val buffer = encoder.getOutputBuffer(outIndex)!!
                if (muxerStarted.get() && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {

                    if (isVideo) {
                        val pts = System.nanoTime() / 1000 - startTimeUs
                        info.presentationTimeUs = Math.max(pts, lastVideoPtsUs + 1)
                        lastVideoPtsUs = info.presentationTimeUs
                    }

                    synchronized(muxerLock) {
                        val track = if (isVideo) videoTrackIndex else audioTrackIndex
                        muxer?.writeSampleData(track, buffer, info)
                    }
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    fun stop() {
        isRecording.set(false)
        try { audioThread?.join(1000) } catch (e: Exception) {}

        drainEncoder(videoCodec, true, true)

        try {
            videoCodec?.stop(); videoCodec?.release()
            audioCodec?.stop(); audioCodec?.release()
            audioRecord?.stop(); audioRecord?.release()

            synchronized(muxerLock) {
                if (muxerStarted.get()) {
                    muxer?.stop()
                    muxer?.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}")
        }
        onFinished(outputUri)
    }
}