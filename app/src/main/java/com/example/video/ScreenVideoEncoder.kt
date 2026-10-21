package com.example.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.example.recorder.MuxerCoordinator
import com.example.recorder.RecordTimeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ScreenVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitrate: Int,
    private val clock: RecordTimeClock
) {
    companion object {
        private const val TAG = "ScreenVideoEncoder"
        private const val TIMEOUT_USEC = 10_000L
    }

    private val encoderScope = CoroutineScope(Dispatchers.IO + Job())
    private var drainJob: Job? = null
    private val eosDeferred = CompletableDeferred<Unit>()

    private var videoCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private var videoTrackIndex = -1
    private var hasWrittenFirstKeyFrame = false
    @Volatile
    private var needsSyncFrameAfterResume = false

    @Volatile
    private var isRunning = false

    fun prepare(): Surface {
        // Ensure even dimensions
        val evenWidth = (width / 2) * 2
        val evenHeight = (height / 2) * 2

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, evenWidth, evenHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        Log.d(TAG, "ScreenVideoEncoder prepared (${evenWidth}x${evenHeight} @ ${frameRate}fps, ${bitrate}bps)")
        return inputSurface ?: throw IllegalStateException("Failed to create encoder input surface")
    }

    fun startDraining(muxer: MuxerCoordinator) {
        isRunning = true
        hasWrittenFirstKeyFrame = false
        needsSyncFrameAfterResume = false

        drainJob = encoderScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            val codec = videoCodec ?: return@launch

            // Request immediate initial keyframe
            requestSyncFrame()

            while (isRunning && encoderScope.isActive) {
                val outputIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                } catch (e: Exception) {
                    Log.w(TAG, "dequeueOutputBuffer error: ${e.message}")
                    break
                }

                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = muxer.setVideoFormat(codec.outputFormat)
                            Log.d(TAG, "Video format ready: ${codec.outputFormat}")
                        }
                    }
                    outputIndex >= 0 -> {
                        val encodedBuffer = codec.getOutputBuffer(outputIndex)
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        if (encodedBuffer != null && bufferInfo.size > 0 && videoTrackIndex >= 0) {
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            // 1. Mandatory keyframe requirement on initial recording start
                            if (!hasWrittenFirstKeyFrame) {
                                if (isKeyFrame) {
                                    hasWrittenFirstKeyFrame = true
                                    Log.d(TAG, "First keyframe received! Commencing muxer writes.")
                                } else {
                                    Log.d(TAG, "Dropping leading non-keyframe; requesting sync frame.")
                                    requestSyncFrame()
                                    codec.releaseOutputBuffer(outputIndex, false)
                                    continue
                                }
                            }

                            // 2. Mandatory keyframe requirement after resuming from pause to prevent green/corrupt artifacts
                            if (needsSyncFrameAfterResume) {
                                if (isKeyFrame) {
                                    needsSyncFrameAfterResume = false
                                    Log.d(TAG, "New keyframe received after resume. Resuming muxer writes.")
                                } else {
                                    Log.d(TAG, "Dropping non-keyframe post-resume; waiting for keyframe.")
                                    requestSyncFrame()
                                    codec.releaseOutputBuffer(outputIndex, false)
                                    continue
                                }
                            }

                            // Write sample data only when active (not paused)
                            if (!clock.isPaused()) {
                                bufferInfo.presentationTimeUs = clock.getAdjustedVideoPtsUs(bufferInfo.presentationTimeUs)

                                encodedBuffer.position(bufferInfo.offset)
                                encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encodedBuffer, bufferInfo)
                            }
                        }

                        codec.releaseOutputBuffer(outputIndex, false)

                        if (isEos) {
                            Log.d(TAG, "Video encoder reached BUFFER_FLAG_END_OF_STREAM")
                            eosDeferred.complete(Unit)
                            break
                        }
                    }
                }
            }

            if (!eosDeferred.isCompleted) {
                eosDeferred.complete(Unit)
            }
        }
    }

    fun onRecordingResumed() {
        Log.d(TAG, "Recording resumed: requesting sync frame to prevent post-pause frame corruption")
        needsSyncFrameAfterResume = true
        requestSyncFrame()
    }

    private fun requestSyncFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            videoCodec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request sync frame: ${e.message}")
        }
    }

    fun signalEndOfStream() {
        Log.d(TAG, "Signaling End Of Stream to video encoder input surface")
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream failed: ${e.message}")
            eosDeferred.complete(Unit)
        }
    }

    suspend fun awaitEndOfStream(timeoutMs: Long = 3000L) {
        withTimeoutOrNull(timeoutMs) {
            eosDeferred.await()
        }
    }

    fun release() {
        isRunning = false
        try {
            drainJob?.cancel()
        } catch (_: Exception) {}

        try {
            videoCodec?.stop()
            videoCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing videoCodec: ${e.message}")
        }
        videoCodec = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        Log.d(TAG, "ScreenVideoEncoder released")
    }
}
