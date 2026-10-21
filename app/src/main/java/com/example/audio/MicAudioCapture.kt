package com.example.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Dedicated high-priority reader for microphone voice audio and game voice chat.
 * Implements Android 10+ AudioRecordingCallback to detect system silencing by games,
 * automated silence watchdog detection, and seamless recreation without timeline breaks.
 */
class MicAudioCapture(
    private val context: Context,
    private val ringBuffer: AudioRingBuffer,
    private val healthMonitor: AudioHealthMonitor,
    private val isGameVoiceChatMode: Boolean,
    private val sampleRate: Int = 44_100
) {
    companion object {
        private const val TAG = "MicAudioCapture"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_SHORTS = 1024
        private const val ZERO_SILENCE_THRESHOLD_CHUNKS = 80 // ~1.85 seconds of pure zero samples
        private const val RECOVERY_COOLDOWN_MS = 2500L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var recordingCallback: Any? = null
    private var readerThread: Thread? = null

    @Volatile
    var isRunning = false
        private set
    private val lock = Any()

    private var consecutiveZeroChunks = 0
    private var lastRecoveryTimeMs = 0L

    // Toggle between sources on repeated silence/failure
    private var currentSource = if (isGameVoiceChatMode) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION
    } else {
        MediaRecorder.AudioSource.MIC
    }

    val isPermitted: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!isPermitted) {
            Log.w(TAG, "Cannot start MicAudioCapture: RECORD_AUDIO permission not granted")
            return
        }

        synchronized(lock) {
            if (isRunning) return
            initAudioRecord()
            registerSystemSilencingCallback()
            isRunning = true

            readerThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                readLoop()
            }, "Regonex-MicAudioReader").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "MicAudioCapture started on dedicated audio thread (Source: $currentSource)")
        }
    }

    private fun initAudioRecord(): Boolean {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
            // Use at least 4 times larger buffer size to absorb scheduling spikes
            val bufferSizeBytes = maxOf(minBufferSize * 4, 32_768)

            audioRecord = AudioRecord(
                currentSource,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSizeBytes
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "Primary source $currentSource failed to initialize. Falling back to opposite source.")
                audioRecord?.release()
                currentSource = if (currentSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    MediaRecorder.AudioSource.MIC
                } else {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                }
                audioRecord = AudioRecord(
                    currentSource,
                    sampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSizeBytes
                )
            }

            audioRecord?.let { record ->
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    val sessionId = record.audioSessionId
                    setupAudioEffects(sessionId)
                    record.startRecording()
                    Log.d(TAG, "Mic AudioRecord initialized successfully (Source: $currentSource, buffer: $bufferSizeBytes)")
                    return true
                }
            }
            Log.e(TAG, "Failed to initialize mic AudioRecord with both sources")
            releaseRecord()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AudioRecord for mic: ${e.message}", e)
            releaseRecord()
            return false
        }
    }

    private fun setupAudioEffects(sessionId: Int) {
        releaseEffects()
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach audio effects to session $sessionId: ${e.message}")
        }
    }

    private fun releaseEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { gainControl?.release() } catch (_: Exception) {}
        try { echoCanceler?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        gainControl = null
        echoCanceler = null
    }

    private fun registerSystemSilencingCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                        val activeRecord = audioRecord ?: return
                        for (config in configs) {
                            if (config.clientAudioSessionId == activeRecord.audioSessionId) {
                                val isSilenced = config.isClientSilenced
                                if (isSilenced) {
                                    healthMonitor.logMicSilenced(true)
                                    triggerAutoRecovery("Microphone silenced by concurrent voice-chat game client")
                                } else {
                                    healthMonitor.logMicSilenced(false)
                                }
                            }
                        }
                    }
                }
                audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
                recordingCallback = callback
                Log.d(TAG, "Registered AudioManager.AudioRecordingCallback for silencing detection")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register AudioRecordingCallback: ${e.message}")
            }
        }
    }

    private fun unregisterSystemSilencingCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && recordingCallback != null) {
            try {
                val cb = recordingCallback as AudioManager.AudioRecordingCallback
                audioManager.unregisterAudioRecordingCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering AudioRecordingCallback: ${e.message}")
            }
            recordingCallback = null
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
                // Silence watchdog: check if all samples are pure zero
                var isPureZero = true
                for (i in 0 until readCount) {
                    if (tempBuffer[i] != 0.toShort()) {
                        isPureZero = false
                        break
                    }
                }

                if (isPureZero) {
                    consecutiveZeroChunks++
                    if (consecutiveZeroChunks >= ZERO_SILENCE_THRESHOLD_CHUNKS) {
                        consecutiveZeroChunks = 0
                        triggerAutoRecovery("Continuous zero audio stream (suspected microphone mute/silence)")
                    }
                } else {
                    consecutiveZeroChunks = 0
                }

                // Push to ring buffer (non-blocking write)
                val written = ringBuffer.write(tempBuffer, 0, readCount)
                if (written < readCount) {
                    healthMonitor.logOverrun("MicAudio", readCount - written)
                }
            } else if (readCount == AudioRecord.ERROR_INVALID_OPERATION || readCount == AudioRecord.ERROR_BAD_VALUE) {
                Log.w(TAG, "MicAudio read error code: $readCount")
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun triggerAutoRecovery(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryTimeMs < RECOVERY_COOLDOWN_MS) {
            return
        }
        lastRecoveryTimeMs = now

        Thread({
            restart(reason)
        }, "Regonex-MicRecovery").start()
    }

    fun restart(reason: String) {
        synchronized(lock) {
            if (!isRunning) return
            Log.i(TAG, "Restarting MicAudioCapture: $reason")
            healthMonitor.logRestart("MicAudio", reason)
            releaseRecord()

            // Alternate input source to find an active audio client
            currentSource = if (currentSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                MediaRecorder.AudioSource.MIC
            } else {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }

            initAudioRecord()
        }
    }

    private fun releaseRecord() {
        releaseEffects()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing mic audioRecord: ${e.message}")
        }
        audioRecord = null
    }

    fun stop() {
        synchronized(lock) {
            isRunning = false
            unregisterSystemSilencingCallback()
            readerThread?.interrupt()
            readerThread = null
            releaseRecord()
            Log.d(TAG, "MicAudioCapture stopped")
        }
    }
}
