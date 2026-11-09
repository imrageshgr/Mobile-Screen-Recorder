package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat as AndroidMediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * InternalAudioRecorder
 * Captures clean, direct Internal Game Audio via Android 10+ AudioPlaybackCaptureConfiguration.
 * When includeMic is true (AUDIO_SOURCE_BOTH), it simultaneously captures microphone voice and
 * mixes the PCM audio streams together.
 *
 * Encodes the mixed PCM to standard AAC (MPEG-4) using MediaCodec, saving an audio-only MP4
 * ready for instant hardware muxing with the video track.
 */
class InternalAudioRecorder(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val outputFile: File,
    private val includeMic: Boolean = false,
    private val includeVoiceChat: Boolean = true,
    private val sampleRate: Int = 44100,
    private val audioBitrate: Int = 128000
) {
    companion object {
        private const val TAG = "InternalAudioRecorder"
        private const val CHANNEL_COUNT = 2
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TIMEOUT_USEC = 10_000L
    }

    private var playbackRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var isMuxerStarted = false

    @Volatile
    var isMicMuted: Boolean = false

    @Volatile
    private var isRunning = false
    private var workerThread: Thread? = null
    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10+ (API 29+)")
            return false
        }

        synchronized(lock) {
            if (isRunning) return true

            try {
                // 1. Configure Internal Audio Playback Capture (Media, Game & Teammates' in-game Voice Chat)
                val playbackConfigBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

                if (includeVoiceChat) {
                    // Captures VoIP voice communication so teammates' in-game voice chat is captured
                    playbackConfigBuilder.addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    playbackConfigBuilder.addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                }
                val playbackConfig = playbackConfigBuilder.build()

                val minBufSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufferSize = (minBufSize * 4).coerceAtLeast(16384)

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()

                playbackRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                if (playbackRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Failed to initialize playback AudioRecord")
                    release()
                    return false
                }

                // 2. If Both Audio (Game + Mic) requested, configure Microphone AudioRecord
                if (includeMic) {
                    val hasMicPermission = ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasMicPermission) {
                        try {
                            micRecord = AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                sampleRate,
                                CHANNEL_CONFIG,
                                AUDIO_FORMAT,
                                bufferSize
                            )
                            if (micRecord?.state != AudioRecord.STATE_INITIALIZED) {
                                Log.w(TAG, "Failed to initialize mic AudioRecord, falling back to game-only")
                                micRecord?.release()
                                micRecord = null
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Exception initializing mic: ${e.message}")
                            micRecord = null
                        }
                    }
                }

                // 3. Configure MediaCodec AAC Audio Encoder
                val codecFormat = AndroidMediaFormat.createAudioFormat(
                    AndroidMediaFormat.MIMETYPE_AUDIO_AAC,
                    sampleRate,
                    CHANNEL_COUNT
                ).apply {
                    setInteger(AndroidMediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(AndroidMediaFormat.KEY_BIT_RATE, audioBitrate)
                    setInteger(AndroidMediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
                }

                val codec = MediaCodec.createEncoderByType(AndroidMediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(codecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                audioCodec = codec

                // 4. Configure MediaMuxer
                mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // 5. Start AudioRecord streams
                playbackRecord?.startRecording()
                micRecord?.startRecording()
                isRunning = true

                // 6. Launch high-priority audio processing thread
                workerThread = Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    recordLoop(bufferSize)
                }, "Regonex-InternalAudioWriter").apply {
                    isDaemon = true
                    start()
                }

                Log.d(TAG, "InternalAudioRecorder started (includeMic=$includeMic, sampleRate=$sampleRate)")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start InternalAudioRecorder: ${e.message}", e)
                release()
                return false
            }
        }
    }

    private fun recordLoop(bufferSize: Int) {
        val codec = audioCodec ?: return
        val playback = playbackRecord ?: return
        val mic = micRecord

        val pcmChunkShorts = 1024 * CHANNEL_COUNT
        val playBuf = ShortArray(pcmChunkShorts)
        val micBuf = if (mic != null) ShortArray(pcmChunkShorts) else null
        val mixBuf = ShortArray(pcmChunkShorts)
        val pcmByteBuffer = ByteBuffer.allocateDirect(pcmChunkShorts * 2).order(ByteOrder.LITTLE_ENDIAN)

        val bufferInfo = MediaCodec.BufferInfo()
        var presentationTimeUs = 0L

        while (isRunning) {
            var playRead = try {
                val r = playback.read(playBuf, 0, pcmChunkShorts)
                if (r > 0) r else 0
            } catch (e: Exception) {
                Log.w(TAG, "AudioPlayback read exception: ${e.message}")
                0
            }

            val micRead = if (mic != null && micBuf != null && !isMicMuted) {
                try {
                    val targetLen = if (playRead > 0) playRead else pcmChunkShorts
                    val r = mic.read(micBuf, 0, targetLen)
                    if (r > 0) r else 0
                } catch (e: Exception) {
                    // Game opened in-game voice chat (mic contention), safely ignore and pad with silence
                    0
                }
            } else 0

            // If playback returned nothing or silence (e.g. game in menu or muted), fill with silence to keep audio pacing
            if (playRead <= 0) {
                if (micRead > 0 && micBuf != null) {
                    playRead = micRead
                    playBuf.fill(0, 0, micRead)
                } else {
                    playRead = pcmChunkShorts
                    playBuf.fill(0)
                    try { Thread.sleep(20) } catch (_: Exception) {}
                }
            }

            // Mix or copy PCM samples
            pcmByteBuffer.clear()
            if (micRead > 0 && micBuf != null) {
                for (i in 0 until playRead) {
                    val pSample = playBuf[i].toInt()
                    val mSample = micBuf[i].toInt()
                    val mixed = (pSample + mSample).coerceIn(-32768, 32767).toShort()
                    mixBuf[i] = mixed
                }
                pcmByteBuffer.asShortBuffer().put(mixBuf, 0, playRead)
            } else {
                pcmByteBuffer.asShortBuffer().put(playBuf, 0, playRead)
            }
            pcmByteBuffer.position(0)
            pcmByteBuffer.limit(playRead * 2)

            // Feed into MediaCodec
            try {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(pcmByteBuffer)
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            playRead * 2,
                            presentationTimeUs,
                            0
                        )
                        presentationTimeUs += (playRead * 1_000_000L) / (sampleRate * CHANNEL_COUNT)
                    }
                }

                drainEncoder(codec, bufferInfo, endOfStream = false)
            } catch (e: Exception) {
                Log.w(TAG, "Error encoding audio frame: ${e.message}")
            }
        }

        // Send End-Of-Stream
        try {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainEncoder(codec, bufferInfo, endOfStream = true)
        } catch (e: Exception) {
            Log.w(TAG, "Error sending EOS to audio codec: ${e.message}")
        }
    }

    private fun drainEncoder(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        val muxer = mediaMuxer ?: return

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break else continue
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!isMuxerStarted) {
                        val newFormat = codec.outputFormat
                        audioTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        isMuxerStarted = true
                        Log.d(TAG, "Audio MediaMuxer started with track index: $audioTrackIndex")
                    }
                }
                outputIndex >= 0 -> {
                    val encodedBuffer = codec.getOutputBuffer(outputIndex)
                    if (encodedBuffer != null && bufferInfo.size > 0 && isMuxerStarted) {
                        muxer.writeSampleData(audioTrackIndex, encodedBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!isRunning) return
            isRunning = false
        }

        try { playbackRecord?.stop() } catch (_: Exception) {}
        try { micRecord?.stop() } catch (_: Exception) {}

        try {
            workerThread?.join(1500)
        } catch (_: Exception) {}

        release()
        Log.d(TAG, "InternalAudioRecorder stopped, saved: ${outputFile.length()} bytes")
    }

    private fun release() {
        try {
            playbackRecord?.stop()
        } catch (_: Exception) {}
        try {
            playbackRecord?.release()
        } catch (_: Exception) {}
        playbackRecord = null

        try {
            micRecord?.stop()
        } catch (_: Exception) {}
        try {
            micRecord?.release()
        } catch (_: Exception) {}
        micRecord = null

        try {
            audioCodec?.stop()
        } catch (_: Exception) {}
        try {
            audioCodec?.release()
        } catch (_: Exception) {}
        audioCodec = null

        if (isMuxerStarted) {
            try {
                mediaMuxer?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping audio MediaMuxer: ${e.message}")
            }
        }
        try {
            mediaMuxer?.release()
        } catch (_: Exception) {}
        mediaMuxer = null
        isMuxerStarted = false
    }
}
