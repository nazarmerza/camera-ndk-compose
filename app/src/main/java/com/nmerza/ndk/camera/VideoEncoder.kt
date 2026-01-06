package com.nmerza.ndk.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer

class VideoEncoder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val onFinished: (Uri) -> Unit
) {
    private val TAG = "VideoEncoder"

    // Encoders
    private lateinit var videoCodec: MediaCodec
    private lateinit var audioCodec: MediaCodec

    // Muxer & State
    private lateinit var muxer: MediaMuxer
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var isRecording = false

    // Timing
    private var startTimeUs: Long = 0

    // Hardware Resources
    private lateinit var inputSurface: android.view.Surface
    private lateinit var outputUri: Uri
    private var audioRecord: AudioRecord? = null

    fun start() {
        isRecording = true
        startTimeUs = System.nanoTime() / 1000

        setupVideoEncoder()
        setupAudioEncoder()
        setupMuxer()

        videoCodec.start()
        audioCodec.start()

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
        videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoCodec.createInputSurface()
    }

    private fun setupAudioEncoder() {
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun setupMuxer() {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DISPLAY_NAME, "Guardian_${System.currentTimeMillis()}")
        }
        outputUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        val pfd = context.contentResolver.openFileDescriptor(outputUri, "w")!!
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun encodeFrame(bitmap: Bitmap) {
        if (!isRecording) return

        // Push Frame to Surface
        val canvas = inputSurface.lockCanvas(null)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        inputSurface.unlockCanvasAndPost(canvas)

        drainVideo(false)
        drainAudio(false)
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureThread() {
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

        Thread {
            audioRecord?.startRecording()
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    feedAudioEncoder(buffer, read)
                    drainAudio(false)
                }
            }
        }.start()
    }

    private fun feedAudioEncoder(data: ByteArray, length: Int) {
        val index = audioCodec.dequeueInputBuffer(10000)
        if (index >= 0) {
            val buf = audioCodec.getInputBuffer(index)!!
            buf.clear()

            // SAFETY CHECK: Only put what the buffer can actually hold
            val capacity = buf.remaining()
            val bytesToPut = if (length > capacity) {
                Log.w(TAG, "Audio buffer overflow prevented: length $length > capacity $capacity")
                capacity
            } else {
                length
            }

            buf.put(data, 0, bytesToPut)

            val presentationTimeUs = System.nanoTime() / 1000 - startTimeUs
            audioCodec.queueInputBuffer(index, 0, bytesToPut, presentationTimeUs, 0)
        }
    }
    private fun drainVideo(endOfStream: Boolean) {
        if (endOfStream) videoCodec.signalEndOfInputStream()
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = videoCodec.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (videoTrackIndex == -1) { // Only add once
                    videoTrackIndex = muxer.addTrack(videoCodec.outputFormat)
                    Log.d(TAG, "Video track added index: $videoTrackIndex")
                    checkStartMuxer()
                }
            } else if (outIndex >= 0) {
                // CRITICAL: We only write if the muxer is actually started
                if (muxerStarted && info.size > 0) {
                    val buffer = videoCodec.getOutputBuffer(outIndex)!!
                    // Ensure timestamps are correct relative to start
                    muxer.writeSampleData(videoTrackIndex, buffer, info)
                }
                videoCodec.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    private fun drainAudio(endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = audioCodec.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (audioTrackIndex == -1) { // Only add once
                    audioTrackIndex = muxer.addTrack(audioCodec.outputFormat)
                    Log.d(TAG, "Audio track added index: $audioTrackIndex")
                    checkStartMuxer()
                }
            } else if (outIndex >= 0) {
                if (muxerStarted && info.size > 0) {
                    val buffer = audioCodec.getOutputBuffer(outIndex)!!
                    muxer.writeSampleData(audioTrackIndex, buffer, info)
                }
                audioCodec.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    @Synchronized
    private fun checkStartMuxer() {
        if (!muxerStarted && videoTrackIndex != -1 && audioTrackIndex != -1) {
            muxer.start()
            muxerStarted = true
        }
    }

    fun stop() {
        isRecording = false
        drainVideo(true)
        drainAudio(true)

        audioRecord?.stop()
        audioRecord?.release()

        videoCodec.stop()
        videoCodec.release()
        audioCodec.stop()
        audioCodec.release()

        if (muxerStarted) {
            muxer.stop()
            muxer.release()
        }
        onFinished(outputUri)
    }
}