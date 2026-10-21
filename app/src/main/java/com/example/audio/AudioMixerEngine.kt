package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.recorder.MuxerCoordinator
import com.example.recorder.RecordTimeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance, multithreaded audio mixer engine.
 * Coordinates dedicated reader threads for AppAudio and MicAudio, a non-blocking
 * PCM mixing thread with silence-filling, and an asynchronous AAC encoder drain thread.
 * Implements [AudioMixerGainControl] for click-free 30ms fading and instant voice switching.
 */
class AudioMixerEngine(
    private val context: Context,
    private val mediaProjection: MediaProjection?,
    private val isAppAudioEnabled: Boolean,
    private val isMicEnabled: Boolean,
    private val isGameVoiceChatMode: Boolean = false,
    private val audioBalance: Float = 0.5f, // 0.0f (all app) to 1.0f (all mic), default 0.5f
    private val clock: RecordTimeClock,
    private val sampleRate: Int = 44_100,
    private val audioBitrate: Int = 160_000
) : AudioMixerGainControl {
    companion object {
        private const val TAG = "AudioMixerEngine"
        private const val CHUNK_SHORTS = 1024
        private const val TIMEOUT_USEC = 10_000L
    }

    val healthMonitor = AudioHealthMonitor()

    private val appRingBuffer = AudioRingBuffer(65_536)
    private val micRingBuffer = AudioRingBuffer(65_536)

    private var appAudioCapture: AppAudioCapture? = null
    private var micAudioCapture: MicAudioCapture? = null
    private var recoveryManager: AudioRecoveryManager? = null

    var onMicRestartedCallback: (() -> Unit)? = null

    private var audioCodec: MediaCodec? = null
    private var audioTrackIndex = -1

    private var mixerThread: Thread? = null
    private var drainThread: Thread? = null

    @Volatile
    private var isRunning = false
    @Volatile
    private var isCallMuted = false
    @Volatile
    var isAppAudioGainMuted = false
    @Volatile
    var isMicGainMuted = false

    @Volatile
    private var micTargetGainScale: Float = if (isMicEnabled) 1.0f else 0.0f
    @Volatile
    private var currentMicGain: Float = 0.0f

    private val eosDeferred = CompletableDeferred<Unit>()
    private var totalFramesWritten = 0L

    val isAudioEnabled: Boolean
        get() = (isAppAudioEnabled && appAudioCapture?.isSupported == true) ||
                (micAudioCapture?.isPermitted == true)

    init {
        if (isAppAudioEnabled) {
            appAudioCapture = AppAudioCapture(
                mediaProjection = mediaProjection,
                ringBuffer = appRingBuffer,
                healthMonitor = healthMonitor,
                sampleRate = sampleRate
            )
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Initialize MicAudioCapture if mic is enabled OR if permission is granted,
        // so the pipeline and audio track are ready from start even if mic starts muted.
        if (isMicEnabled || hasMicPermission) {
            micAudioCapture = MicAudioCapture(
                context = context,
                ringBuffer = micRingBuffer,
                healthMonitor = healthMonitor,
                isGameVoiceChatMode = isGameVoiceChatMode,
                sampleRate = sampleRate
            )
        }

        recoveryManager = AudioRecoveryManager(
            context = context,
            healthMonitor = healthMonitor,
            onRestartAudioRequested = { reason ->
                appAudioCapture?.restart(reason)
                micAudioCapture?.restart(reason)
                onMicRestartedCallback?.invoke()
            }
        )
    }

    override fun setMicGain(targetGain: Float, fadeDurationMs: Long) {
        micTargetGainScale = targetGain.coerceIn(0.0f, 1.0f)
    }

    override fun getMicGain(): Float = micTargetGainScale

    override fun isMicRunning(): Boolean = micAudioCapture?.isRunning == true

    override fun isMicPermitted(): Boolean = micAudioCapture?.isPermitted == true

    fun start(muxer: MuxerCoordinator) {
        val appSupported = appAudioCapture?.isSupported == true
        val micPermitted = micAudioCapture?.isPermitted == true

        if (!appSupported && !micPermitted) {
            Log.d(TAG, "Audio recording is disabled (no active audio source supported/permitted)")
            return
        }

        try {
            setupAudioCodec()
            isRunning = true

            // Start hardware reader threads
            appAudioCapture?.start()
            micAudioCapture?.start()
            recoveryManager?.start()

            // Start dedicated drain thread
            drainThread = Thread({
                drainCodecLoop(muxer)
            }, "Regonex-AudioEncoderDrain").apply {
                isDaemon = true
                start()
            }

            // Start dedicated non-blocking mixer thread
            mixerThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                mixingLoop()
            }, "Regonex-AudioMixer").apply {
                isDaemon = true
                start()
            }

            Log.d(TAG, "AudioMixerEngine initialized and running across dedicated threads")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioMixerEngine: ${e.message}", e)
            release()
        }
    }

    private fun setupAudioCodec() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun mixingLoop() {
        val appBuffer = ShortArray(CHUNK_SHORTS)
        val micBuffer = ShortArray(CHUNK_SHORTS)
        val mixedBuffer = ShortArray(CHUNK_SHORTS)
        val byteBuffer = ByteBuffer.allocateDirect(CHUNK_SHORTS * 2).order(ByteOrder.LITTLE_ENDIAN)

        // Compute gains from balance setting (0.0 = only app, 0.5 = balanced, 1.0 = only mic)
        // With smooth acoustic level matching for game voice chat
        val balance = audioBalance.coerceIn(0.0f, 1.0f)
        val baseAppGain = if (balance <= 0.5f) 0.85f else 0.85f * (1.0f - (balance - 0.5f) * 1.5f).coerceAtLeast(0.15f)
        val baseMicGain = if (balance >= 0.5f) 0.95f else 0.95f * (balance / 0.5f).coerceAtLeast(0.20f)

        while (isRunning && !Thread.currentThread().isInterrupted) {
            if (clock.isPaused() || isCallMuted) {
                // Keep draining ring buffers during pause to prevent buffer stale overflow
                appRingBuffer.clear()
                micRingBuffer.clear()
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            val appAvailable = appRingBuffer.available()
            val micAvailable = micRingBuffer.available()

            // If neither source has enough data yet, sleep briefly to pace mixing to real-time
            if (appAvailable < CHUNK_SHORTS && micAvailable < CHUNK_SHORTS) {
                try {
                    Thread.sleep(6)
                } catch (_: InterruptedException) {
                    break
                }
                // Check again; if still starved, continue to avoid freezing
                if (appRingBuffer.available() < CHUNK_SHORTS / 2 && micRingBuffer.available() < CHUNK_SHORTS / 2) {
                    continue
                }
            }

            // 1. Non-blocking read from App Audio (fill silence if starved)
            val appRead = appRingBuffer.read(appBuffer, 0, CHUNK_SHORTS)
            if (appRead < CHUNK_SHORTS) {
                for (i in appRead until CHUNK_SHORTS) {
                    appBuffer[i] = 0
                }
                if (isAppAudioEnabled && appRead == 0) {
                    healthMonitor.logUnderrunSilenceFill("AppAudio", CHUNK_SHORTS)
                }
            }

            // 2. Non-blocking read from Mic Audio (fill silence if starved)
            val micRead = micRingBuffer.read(micBuffer, 0, CHUNK_SHORTS)
            if (micRead < CHUNK_SHORTS) {
                for (i in micRead until CHUNK_SHORTS) {
                    micBuffer[i] = 0
                }
                if (micAudioCapture != null && micRead == 0) {
                    healthMonitor.logUnderrunSilenceFill("MicAudio", CHUNK_SHORTS)
                }
            }

            // Apply dynamic floating button mute states & gains
            val effectiveAppGain = if (isAppAudioGainMuted || isCallMuted || !isAppAudioEnabled) 0.0f else baseAppGain
            val isMicPhysicallyPermitted = micAudioCapture?.isPermitted == true
            val targetGain = if (isCallMuted || isMicGainMuted || !isMicPhysicallyPermitted) {
                0.0f
            } else {
                baseMicGain * micTargetGainScale
            }

            // Linear ramp step per stereo frame for smooth ~30ms fade (avoids clicks/pops)
            val framesIn30Ms = (sampleRate * 0.030f).coerceAtLeast(1.0f)
            val gainStepPerFrame = 1.0f / framesIn30Ms

            // Balanced soft-clipping 16-bit PCM mixing with click-free fading
            for (i in 0 until CHUNK_SHORTS step 2) {
                if (currentMicGain != targetGain) {
                    val diff = targetGain - currentMicGain
                    val step = diff.coerceIn(-gainStepPerFrame, gainStepPerFrame)
                    currentMicGain += step
                    if (Math.abs(targetGain - currentMicGain) < 0.0005f) {
                        currentMicGain = targetGain
                    }
                }

                val appValL = appBuffer[i] * effectiveAppGain
                val appValR = appBuffer[i + 1] * effectiveAppGain
                val micValL = micBuffer[i] * currentMicGain
                val micValR = micBuffer[i + 1] * currentMicGain

                mixedBuffer[i] = (appValL + micValL).toInt().coerceIn(-32768, 32767).toShort()
                mixedBuffer[i + 1] = (appValR + micValR).toInt().coerceIn(-32768, 32767).toShort()
            }

            byteBuffer.clear()
            byteBuffer.asShortBuffer().put(mixedBuffer, 0, CHUNK_SHORTS)
            val bytesCount = CHUNK_SHORTS * 2

            // Calculate presentation timestamp from actual written samples with drift correction
            val stereoFramesInChunk = CHUNK_SHORTS / 2 // 2 channels stereo
            val sampleBasedPtsUs = (totalFramesWritten * 1_000_000L) / sampleRate

            // Micro-drift correction against master clock
            val masterClockUs = clock.getAdjustedAudioPtsUs(totalFramesWritten, sampleRate)
            val driftDeltaUs = masterClockUs - sampleBasedPtsUs
            val finalPtsUs = if (Math.abs(driftDeltaUs) > 20_000L) {
                // Correct smoothly without creating sudden step discontinuities
                sampleBasedPtsUs + (driftDeltaUs / 4)
            } else {
                sampleBasedPtsUs
            }

            totalFramesWritten += stereoFramesInChunk

            // Feed raw PCM into AAC encoder with retry loop (never discard)
            queueInputBufferWithRetry(byteBuffer, bytesCount, finalPtsUs)
        }
    }

    private fun queueInputBufferWithRetry(byteBuffer: ByteBuffer, bytesCount: Int, presentationTimeUs: Long) {
        val codec = audioCodec ?: return
        var queued = false

        for (attempt in 0 until 50) {
            if (!isRunning) break
            val inputIndex = try {
                codec.dequeueInputBuffer(TIMEOUT_USEC)
            } catch (e: Exception) {
                Log.w(TAG, "Audio dequeueInputBuffer error: ${e.message}")
                -1
            }

            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(byteBuffer.array(), 0, bytesCount)
                codec.queueInputBuffer(inputIndex, 0, bytesCount, presentationTimeUs, 0)
                queued = true
                break
            } else {
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        if (!queued && isRunning) {
            healthMonitor.logOverrun("EncoderInput", bytesCount / 2)
        }
    }

    private fun drainCodecLoop(muxer: MuxerCoordinator) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val codec = audioCodec ?: break
            val outputIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            } catch (e: Exception) {
                Log.w(TAG, "Audio drain dequeueOutputBuffer error: ${e.message}")
                break
            }

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (audioTrackIndex < 0) {
                        audioTrackIndex = muxer.setAudioFormat(codec.outputFormat)
                        Log.d(TAG, "Audio format registered with muxer: ${codec.outputFormat}")
                    }
                }
                outputIndex >= 0 -> {
                    val encodedBuffer = codec.getOutputBuffer(outputIndex)
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (encodedBuffer != null && bufferInfo.size > 0 && audioTrackIndex >= 0) {
                        if (!clock.isPaused()) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, encodedBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (isEos) {
                        Log.d(TAG, "Audio encoder reached BUFFER_FLAG_END_OF_STREAM")
                        eosDeferred.complete(Unit)
                        break
                    }
                }
            }
        }
    }

    fun signalEndOfStream() {
        Log.d(TAG, "Signaling End Of Stream to audio encoder")
        isRunning = false
        try {
            val codec = audioCodec ?: run {
                eosDeferred.complete(Unit)
                return
            }

            var queued = false
            for (attempt in 0 until 50) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputIndex >= 0) {
                    val presentationTimeUs = (totalFramesWritten * 1_000_000L) / sampleRate
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                    Log.d(TAG, "Audio EOS queued successfully into encoder")
                    break
                }
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (!queued) {
                Log.w(TAG, "Could not dequeue input buffer for audio EOS; completing deferred")
                eosDeferred.complete(Unit)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio signalEndOfStream error: ${e.message}")
            eosDeferred.complete(Unit)
        }
    }

    suspend fun awaitEndOfStream(timeoutMs: Long = 3000L) {
        withTimeoutOrNull(timeoutMs) {
            eosDeferred.await()
        }
    }

    fun setCallMuted(muted: Boolean) {
        isCallMuted = muted
        if (muted) {
            Log.d(TAG, "Call muted: flushing mic and app audio")
            appRingBuffer.clear()
            micRingBuffer.clear()
        }
    }

    fun release() {
        isRunning = false

        try {
            mixerThread?.interrupt()
            mixerThread = null
        } catch (_: Exception) {}

        try {
            drainThread?.interrupt()
            drainThread = null
        } catch (_: Exception) {}

        recoveryManager?.stop()
        recoveryManager = null

        appAudioCapture?.stop()
        appAudioCapture = null

        micAudioCapture?.stop()
        micAudioCapture = null

        try {
            audioCodec?.stop()
            audioCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audioCodec: ${e.message}")
        }
        audioCodec = null

        appRingBuffer.clear()
        micRingBuffer.clear()

        Log.d(TAG, "AudioMixerEngine released")
    }
}
