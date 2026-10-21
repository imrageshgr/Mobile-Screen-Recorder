package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Dedicated high-priority reader for internal system / game audio via Android 10+
 * AudioPlaybackCaptureConfiguration. Feeds raw PCM into a dedicated ring buffer.
 */
class AppAudioCapture(
    private val mediaProjection: MediaProjection?,
    private val ringBuffer: AudioRingBuffer,
    private val healthMonitor: AudioHealthMonitor,
    private val sampleRate: Int = 44_100
) {
    companion object {
        private const val TAG = "AppAudioCapture"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_SHORTS = 1024
        private const val CONSECUTIVE_SILENT_CHUNKS_FOR_SUSPECTED_BLOCK = 450 // ~10.4s
    }

    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null

    @Volatile
    private var isRunning = false
    private val lock = Any()

    private var consecutiveSilentChunks = 0

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null

    fun start() {
        if (!isSupported) {
            Log.d(TAG, "Internal app audio capture not supported (requires Android 10+ and valid projection)")
            return
        }

        synchronized(lock) {
            if (isRunning) return
            initAudioRecord()
            isRunning = true

            readerThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                readLoop()
            }, "Regonex-AppAudioReader").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "AppAudioCapture started on dedicated audio thread")
        }
    }

    private fun initAudioRecord(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaProjection == null) {
            return false
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
            // Use at least 4 times larger buffer size to absorb game scheduling spikes
            val bufferSizeBytes = maxOf(minBufferSize * 4, 32_768)

            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(sampleRate)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                Log.d(TAG, "Internal audio AudioRecord initialized (buffer: $bufferSizeBytes bytes)")
                return true
            } else {
                Log.w(TAG, "AudioRecord initialization failed (state != INITIALIZED)")
                releaseRecord()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AudioRecord for app audio: ${e.message}", e)
            releaseRecord()
            return false
        }
    }

    private fun readLoop() {
        val tempBuffer = ShortArray(CHUNK_SIZE_SHORTS)

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val record = audioRecord
            if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            val readCount = record.read(tempBuffer, 0, CHUNK_SIZE_SHORTS)
            if (readCount > 0) {
                // Check if audio is completely silent
                var isSilent = true
                for (i in 0 until readCount) {
                    if (tempBuffer[i] != 0.toShort()) {
                        isSilent = false
                        break
                    }
                }

                if (isSilent) {
                    consecutiveSilentChunks++
                    if (consecutiveSilentChunks >= CONSECUTIVE_SILENT_CHUNKS_FOR_SUSPECTED_BLOCK) {
                        healthMonitor.logAppAudioBlockSuspected()
                        consecutiveSilentChunks = 0
                    }
                } else {
                    consecutiveSilentChunks = 0
                }

                // Push to ring buffer (non-blocking write)
                val written = ringBuffer.write(tempBuffer, 0, readCount)
                if (written < readCount) {
                    healthMonitor.logOverrun("AppAudio", readCount - written)
                }
            } else if (readCount == AudioRecord.ERROR_INVALID_OPERATION || readCount == AudioRecord.ERROR_BAD_VALUE) {
                Log.w(TAG, "AppAudio read error code: $readCount")
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun restart(reason: String) {
        synchronized(lock) {
            if (!isRunning) return
            Log.i(TAG, "Restarting AppAudioCapture: $reason")
            healthMonitor.logRestart("AppAudio", reason)
            releaseRecord()
            initAudioRecord()
        }
    }

    private fun releaseRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audioRecord: ${e.message}")
        }
        audioRecord = null
    }

    fun stop() {
        synchronized(lock) {
            isRunning = false
            readerThread?.interrupt()
            readerThread = null
            releaseRecord()
            Log.d(TAG, "AppAudioCapture stopped")
        }
    }
}
