package com.example.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VideoEncoderWrapper
 * High-performance hardware video capture pipeline built on MediaCodec (H.264/AVC) + MediaMuxer,
 * running all codec dequeue/queue and muxing callbacks on a dedicated HandlerThread ("VideoEncoderThread")
 * so gameplay and the main thread are never blocked.
 */
class VideoEncoderWrapper(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val outputFile: File? = null,
    private val muxerCoordinator: MuxerCoordinator? = null,
    private val onError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "VideoEncoderWrapper"
        private const val THREAD_NAME = "VideoEncoderThread"
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val SMART_BITRATE_CAP_BPS = 12_000_000
        private const val BITRATE_1080P_60FPS_BPS = 12_000_000
        private const val EOS_TIMEOUT_MS = 3000L

        /**
         * Guarantees 12 Mbps (12_000_000 bps) for 1080p @ 60fps and applies a smart thermal cap
         * of 12 Mbps so high-resolution gaming captures do not overheat or throttle the device.
         */
        fun resolveSmartCappedBitrate(width: Int, height: Int, fps: Int, requestedBitrate: Int): Int {
            val is1080p = maxOf(width, height) >= 1920 && minOf(width, height) >= 1080
            return if (is1080p && fps >= 60) {
                BITRATE_1080P_60FPS_BPS
            } else {
                requestedBitrate.coerceAtMost(SMART_BITRATE_CAP_BPS)
            }
        }
    }

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private var mediaCodec: MediaCodec? = null
    private var standaloneMuxer: MediaMuxer? = null
    private var surface: Surface? = null

    private var videoTrackIndex = -1
    private var isStandaloneMuxerStarted = false

    private val isStarted = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
    @Volatile
    private var isPaused = false

    private var pauseStartPtsUs = -1L
    private var pausedTotalUs = 0L
    private var lastWrittenPtsUs = -1L

    private val eosLatch = CountDownLatch(1)
    private val muxerLock = Any()

    /**
     * Configures the H.264 hardware MediaCodec with CBR mode and a 1-second keyframe interval,
     * attaches callbacks onto the dedicated 'VideoEncoderThread', starts the encoder,
     * and returns the input Surface to bind to MediaProjection's VirtualDisplay.
     */
    fun prepareAndStart(): Surface {
        val thread = HandlerThread(THREAD_NAME).apply { start() }
        encoderThread = thread
        val handler = Handler(thread.looper)
        encoderHandler = handler

        if (muxerCoordinator == null) {
            val file = outputFile ?: throw IllegalArgumentException("Either outputFile or muxerCoordinator must be provided")
            standaloneMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec = codec

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Surface input mode handles frame delivery directly into the encoder; no CPU input buffers needed.
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                handleOutputBuffer(codec, index, info)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "MediaCodec error: ${e.diagnosticInfo}", e)
                eosLatch.countDown()
                onError?.invoke(e.message ?: "Hardware video encoder error")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (muxerCoordinator != null) {
                    muxerCoordinator.setVideoFormat(format)
                } else {
                    synchronized(muxerLock) {
                        val muxer = standaloneMuxer ?: return
                        if (!isStandaloneMuxerStarted && videoTrackIndex < 0) {
                            videoTrackIndex = muxer.addTrack(format)
                            muxer.start()
                            isStandaloneMuxerStarted = true
                            Log.d(TAG, "Standalone MediaMuxer started (track=$videoTrackIndex, format=$format)")
                        }
                    }
                }
            }
        }, handler)

        val effectiveBitrate = resolveSmartCappedBitrate(width, height, fps, bitrate)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "CBR configuration unsupported on this chipset, falling back to default bitrate mode: ${e.message}")
            val fallbackFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            codec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val inputSurface = codec.createInputSurface()
        surface = inputSurface

        codec.start()
        isStarted.set(true)
        Log.d(TAG, "VideoEncoderWrapper started: ${width}x${height} @ ${fps}fps, ${effectiveBitrate / 1_000_000}Mbps (CBR, ${I_FRAME_INTERVAL_SECONDS}s I-frame)")
        return inputSurface
    }

    fun getInputSurface(): Surface? = surface

    fun pause() {
        if (!isStarted.get() || isPaused || isStopping.get()) return
        isPaused = true
        pauseStartPtsUs = -1L
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1)
            }
            mediaCodec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Could not suspend MediaCodec input surface: ${e.message}")
        }
    }

    fun resume() {
        if (!isStarted.get() || !isPaused || isStopping.get()) return
        isPaused = false
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0)
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Could not resume MediaCodec input surface: ${e.message}")
        }
    }

    private fun handleOutputBuffer(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo
    ) {
        val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        var bufferReleased = false
        try {
            val outputBuffer = codec.getOutputBuffer(index)
            val isCodecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

            if (outputBuffer != null && info.size > 0 && !isCodecConfig) {
                if (isPaused) {
                    if (pauseStartPtsUs < 0L) {
                        pauseStartPtsUs = info.presentationTimeUs
                    }
                } else {
                    if (pauseStartPtsUs >= 0L) {
                        val delta = (info.presentationTimeUs - pauseStartPtsUs).coerceAtLeast(0L)
                        pausedTotalUs += delta
                        pauseStartPtsUs = -1L
                    }

                    val adjustedPtsUs = (info.presentationTimeUs - pausedTotalUs).let { candidate ->
                        if (lastWrittenPtsUs >= 0L && candidate <= lastWrittenPtsUs) {
                            lastWrittenPtsUs + 1L
                        } else {
                            candidate.coerceAtLeast(0L)
                        }
                    }
                    lastWrittenPtsUs = adjustedPtsUs

                    val writeInfo = MediaCodec.BufferInfo().apply {
                        set(info.offset, info.size, adjustedPtsUs, info.flags)
                    }

                    if (muxerCoordinator != null) {
                        muxerCoordinator.writeVideoSample(outputBuffer, writeInfo)
                    } else {
                        synchronized(muxerLock) {
                            val muxer = standaloneMuxer
                            if (isStandaloneMuxerStarted && muxer != null && videoTrackIndex >= 0) {
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrackIndex, outputBuffer, writeInfo)
                            }
                        }
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // Buffer overflow or codec state contention: safely drop the frame instead of crashing the service
            Log.w(TAG, "MediaCodec IllegalStateException (buffer overflow); safely dropping frame index=$index: ${e.message}")
            try {
                codec.releaseOutputBuffer(index, false)
                bufferReleased = true
            } catch (_: IllegalStateException) {
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "Error processing encoded video frame: ${e.message}")
        } finally {
            if (!bufferReleased) {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                } catch (_: Exception) {}
            }

            if (isEos) {
                eosLatch.countDown()
            }
        }
    }

    /**
     * Signals EOS to the hardware encoder, waits for remaining queued frames to flush,
     * and cleanly releases MediaCodec, Surface, and VideoEncoderThread.
     * Note: When using a shared MuxerCoordinator, caller stops the coordinator after both tracks finish.
     */
    fun stopAndRelease() {
        if (!isStopping.compareAndSet(false, true)) return

        if (isStarted.get()) {
            try {
                if (isPaused) {
                    isPaused = false
                }
                mediaCodec?.signalEndOfInputStream()
                val reachedEos = eosLatch.await(EOS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!reachedEos) {
                    Log.w(TAG, "Timed out waiting for MediaCodec EOS flush")
                }
            } catch (e: Exception) {
                Log.w(TAG, "signalEndOfInputStream warning: ${e.message}")
            }
        }

        isStarted.set(false)

        try {
            encoderThread?.quitSafely()
            encoderThread?.join(500)
        } catch (_: Exception) {}
        encoderThread = null
        encoderHandler = null

        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec stop: ${e.message}")
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec release: ${e.message}")
        }
        mediaCodec = null

        try {
            surface?.release()
        } catch (_: Exception) {}
        surface = null

        if (muxerCoordinator == null) {
            synchronized(muxerLock) {
                try {
                    if (isStandaloneMuxerStarted) {
                        standaloneMuxer?.stop()
                        isStandaloneMuxerStarted = false
                        Log.d(TAG, "Standalone MediaMuxer stopped cleanly")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Standalone MediaMuxer stop error: ${e.message}")
                }
                try {
                    standaloneMuxer?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Standalone MediaMuxer release error: ${e.message}")
                }
                standaloneMuxer = null
                videoTrackIndex = -1
            }
        }
    }
}
