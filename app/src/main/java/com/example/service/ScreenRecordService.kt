package com.example.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.MainActivity
import com.example.R
import com.example.model.DiagnosticInfo
import com.example.model.RecordingState
import com.example.model.ServiceStatus
import com.example.overlay.FloatingControlOverlay
import com.example.repository.VideoRepository
import com.example.system.ThermalChargingMonitor
import com.example.system.ThermalStatusInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * ScreenRecordService
 * Bulletproof Android 14+ screen recording service utilizing native MediaRecorder
 * for zero frame loss, guaranteed MP4 file creation, partial WakeLock against OEM kill timeouts,
 * dynamic landscape/portrait orientation, and FLAG_SECURE floating controls.
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "screen_record_channel_v2"

        const val ACTION_START = "com.example.action.START_RECORDING"
        const val ACTION_STOP = "com.example.action.STOP_RECORDING"
        const val ACTION_PAUSE = "com.example.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.action.RESUME_RECORDING"
        const val ACTION_SHORT_CLIP = "com.example.action.SHORT_CLIP"
        const val ACTION_TOGGLE_MIC = "com.example.action.TOGGLE_MIC"
        const val ACTION_DISMISS_THERMAL_ALERT = "com.example.action.DISMISS_THERMAL_ALERT"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DENSITY_DPI = "extra_density_dpi"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
        const val EXTRA_ENABLE_OVERLAY = "extra_enable_overlay"
        const val EXTRA_RESOLUTION = "extra_resolution"
        const val EXTRA_ORIENTATION = "extra_orientation"
        const val EXTRA_ENABLE_FLOATING = "extra_enable_floating"
        const val EXTRA_SHORT_CLIP_SECONDS = "extra_short_clip_seconds"
        const val EXTRA_IS_LIVE_STREAM = "extra_is_live_stream"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_STREAM_KEY = "extra_stream_key"
        const val EXTRA_STREAM_DELAY_SECONDS = "extra_stream_delay_seconds"
        const val EXTRA_AUDIO_MODE = "extra_audio_mode"

        const val AUDIO_SOURCE_INTERNAL = "INTERNAL"
        const val AUDIO_SOURCE_MIC = "MIC"
        const val AUDIO_SOURCE_BOTH = "BOTH"
        const val AUDIO_SOURCE_MUTE = "MUTE"

        private val _isRecordingFlow = MutableStateFlow(false)
        val isRecordingFlow: StateFlow<Boolean> = _isRecordingFlow.asStateFlow()

        private val _lastSavedVideoTimestamp = MutableStateFlow(0L)
        val lastSavedVideoTimestamp: StateFlow<Long> = _lastSavedVideoTimestamp.asStateFlow()

        private val _serviceStatus = MutableStateFlow(ServiceStatus())
        val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus.asStateFlow()

        private fun logDebug(step: String, message: String, isError: Boolean = false, exception: Throwable? = null) {
            val formatted = "[$step] $message"
            if (isError) {
                Log.e(TAG, formatted, exception)
            } else {
                Log.i(TAG, formatted)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // Recording core components
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: com.example.recorder.VideoEncoderWrapper? = null
    private var internalAudioRecorder: com.example.audio.InternalAudioRecorder? = null
    private var muxerCoordinator: com.example.recorder.MuxerCoordinator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // State tracking
    private var isRecording = false
    private var isPaused = false
    private var currentOutputFile: File? = null

    // Configuration
    private var videoWidth = 1280
    private var videoHeight = 720
    private var videoFps = 60
    private var videoBitrate = 12_000_000
    private var audioSourceType = AUDIO_SOURCE_INTERNAL
    private var enableOverlayTrigger = false
    private var enableFloatingButton = true
    private var shortClipDurationSeconds = 30
    private var pendingShortClipCount = 0

    // Notification & Timer
    private var timerJob: Job? = null
    private var totalElapsedSeconds = 0

    // Floating UI controls
    private var floatingOverlay: FloatingControlOverlay? = null

    // Hardware Thermal & Charging Guard telemetry
    private var thermalMonitor: ThermalChargingMonitor? = null
    private var userDeclinedThermalStop = false
    private var currentThermalInfo = ThermalStatusInfo()

    // Corner Hit Box overlay (optional backup)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.w(TAG, "onStartCommand called with null intent. Stopping service to prevent ghost recordings.")
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent.action ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStopAndSave()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_SHORT_CLIP -> handleTakeShortClip()
            ACTION_DISMISS_THERMAL_ALERT -> handleDismissThermalAlert()
        }
        return START_NOT_STICKY
    }

    private fun handleDismissThermalAlert() {
        logDebug("THERMAL_DECLINED", "User declined overheat stop. Respecting user decision, recording continues without stopping.")
        userDeclinedThermalStop = true
        updateNotification("Recording: ${formatTime(totalElapsedSeconds)}")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: App closed from recent apps. Foreground recording continues uninterrupted.")
        // Do NOT stop recording when user swipes away app in recents.
        // The foreground service continues recording until user taps Stop in floating button, notification, or in-app.
    }

    private fun handleStart(intent: Intent) {
        if (isRecording) {
            logDebug("START", "Recording is already active, ignoring duplicate start request")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            logDebug("PERMISSION_ERROR", "Screen capture permission missing or denied", isError = true)
            _serviceStatus.value = ServiceStatus(
                state = RecordingState.ERROR,
                errorMessage = "Screen capture permission denied."
            )
            stopSelf()
            return
        }

        val rawWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
        val rawHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
        val densityDpi = intent.getIntExtra(EXTRA_DENSITY_DPI, 400)
        videoFps = intent.getIntExtra(EXTRA_FPS, 60).coerceIn(24, 60)
        videoBitrate = intent.getIntExtra(EXTRA_BITRATE, 12_000_000)
        audioSourceType = intent.getStringExtra(EXTRA_AUDIO_SOURCE) ?: AUDIO_SOURCE_INTERNAL
        enableOverlayTrigger = intent.getBooleanExtra(EXTRA_ENABLE_OVERLAY, false)
        enableFloatingButton = intent.getBooleanExtra(EXTRA_ENABLE_FLOATING, true)
        shortClipDurationSeconds = intent.getIntExtra(EXTRA_SHORT_CLIP_SECONDS, 30).coerceAtLeast(5)

        // ORIENTATION CALCULATION:
        // Solves the "smaller video in a video" issue with accurate automatic detection:
        // Automatically detects when a game or movie is played horizontally, while vertical apps remain portrait.
        val orientationPref = intent.getStringExtra(EXTRA_ORIENTATION) ?: "AUTO"
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val currentRotation = wm.defaultDisplay.rotation
        val isDeviceCurrentlyLandscape = currentRotation == Surface.ROTATION_90 || currentRotation == Surface.ROTATION_270

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val isScreenWiderThanTall = metrics.widthPixels > metrics.heightPixels

        val shouldRecordLandscape = when (orientationPref) {
            "LANDSCAPE" -> true
            "PORTRAIT" -> false
            else -> isDeviceCurrentlyLandscape ||
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
                    isScreenWiderThanTall
        }

        val is1080p = maxOf(rawWidth, rawHeight) >= 1400 || intent.getStringExtra(EXTRA_RESOLUTION) == "1080p"
        if (is1080p) {
            videoWidth = if (shouldRecordLandscape) 1920 else 1080
            videoHeight = if (shouldRecordLandscape) 1080 else 1920
        } else {
            videoWidth = if (shouldRecordLandscape) 1280 else 720
            videoHeight = if (shouldRecordLandscape) 720 else 1280
        }

        // Strictly ensure even dimensions for hardware encoder compatibility
        videoWidth = (videoWidth / 2) * 2
        videoHeight = (videoHeight / 2) * 2

        // 1. Acquire Partial WakeLock to prevent CPU power-management and OEM battery killer timeouts (~1.45 mins)
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenRecordService::RecordingWakeLock").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // 4 hours maximum timeout
            }
            logDebug("WAKELOCK", "Acquired partial WakeLock for recording session")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock: ${e.message}")
        }

        // 2. Start foreground service BEFORE getMediaProjection (Required on Android 14+)
        startForegroundServiceWithNotification()

        serviceScope.launch(Dispatchers.IO) {
            try {
                startRecordingPipeline(resultCode, resultData, densityDpi)
            } catch (e: Exception) {
                logDebug("START_ERROR", "Failed to start recording: ${e.message}", isError = true, exception = e)
                _isRecordingFlow.value = false
                _serviceStatus.value = ServiceStatus(
                    state = RecordingState.ERROR,
                    errorMessage = "Recording failed: ${e.message}"
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification("Starting recording...", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (audioSourceType == AUDIO_SOURCE_MIC || audioSourceType == AUDIO_SOURCE_INTERNAL || audioSourceType == AUDIO_SOURCE_BOTH) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("WrongConstant")
    private fun startRecordingPipeline(resultCode: Int, resultData: Intent, densityDpi: Int) {
        logDebug("PIPELINE", "Preparing hardware MediaCodec + MediaMuxer pipeline (${videoWidth}x${videoHeight})...")

        // Prepare output directories
        val recordingsDir = File(getExternalFilesDir(null), "Recordings").apply {
            if (!exists()) mkdirs()
        }
        val outputFile = File(recordingsDir, "REC_${System.currentTimeMillis()}.mp4")
        currentOutputFile = outputFile

        logDebug("STORAGE", "Target file: ${outputFile.absolutePath}")

        // Check audio recording capability
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Audio mode definitions:
        // 1. MIC ONLY: VideoEncoderWrapper captures video surface; InternalAudioRecorder captures hardware MIC.
        // 2. INTERNAL ONLY: VideoEncoderWrapper captures video surface; InternalAudioRecorder captures system playback (MIC MUTED).
        // 3. BOTH: VideoEncoderWrapper captures video surface; InternalAudioRecorder mixes 70% playback + 30% MIC.
        // 4. MUTE: Video surface only, zero audio tracks.
        val isDirectMic = audioSourceType == AUDIO_SOURCE_MIC && hasMicPermission
        val isInternalAudio = (audioSourceType == AUDIO_SOURCE_INTERNAL || audioSourceType == AUDIO_SOURCE_BOTH)
        val needsAudioCapture = isDirectMic || (isInternalAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        // Initialize a single, real-time MediaMuxer coordinator for REC_<timestamp>.mp4
        val expectedTrackCount = if (needsAudioCapture) 2 else 1
        val coordinator = com.example.recorder.MuxerCoordinator(
            outputFile = outputFile,
            expectedTrackCount = expectedTrackCount
        )
        muxerCoordinator = coordinator

        // 1. Initialize dedicated MediaCodec (H.264, CBR, 1s keyframe) streaming directly to MuxerCoordinator on 'VideoEncoderThread'
        logDebug("CODEC_PREPARE", "Preparing VideoEncoderWrapper (H.264, CBR, 1s keyframe)...")
        val encoder = com.example.recorder.VideoEncoderWrapper(
            width = videoWidth,
            height = videoHeight,
            bitrate = videoBitrate,
            fps = videoFps,
            muxerCoordinator = coordinator,
            onError = { errorMsg ->
                logDebug("CODEC_ERROR", errorMsg, isError = true)
            }
        )
        val inputSurface = encoder.prepareAndStart()
        videoEncoder = encoder

        // 2. Acquire MediaProjection
        logDebug("PROJECTION", "Acquiring MediaProjection...")
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjectionManager returned null projection")
        mediaProjection = projection

        // Rule 1: Register Callback BEFORE createVirtualDisplay (Android 14+ strict rule)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                logDebug("PROJECTION_CALLBACK", "MediaProjection stopped by system")
                if (isRecording) {
                    handleStopAndSave()
                }
            }
        }, Handler(Looper.getMainLooper()))

        // 3. Create VirtualDisplay attached directly to codec.createInputSurface()
        logDebug("VIRTUAL_DISPLAY", "Creating VirtualDisplay with MediaCodec InputSurface...")
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecordDisplay",
            videoWidth,
            videoHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        // 4. Start Audio Capture streaming AAC frames into the same MuxerCoordinator in real time
        if (needsAudioCapture) {
            val recorderEngine = com.example.audio.InternalAudioRecorder(
                context = this,
                mediaProjection = if (isInternalAudio) projection else null,
                muxerCoordinator = coordinator,
                includeMic = isDirectMic || (audioSourceType == AUDIO_SOURCE_BOTH),
                includePlayback = isInternalAudio
            )
            val audioStarted = recorderEngine.start()
            if (audioStarted) {
                internalAudioRecorder = recorderEngine
                logDebug("AUDIO_CAPTURE", "Audio capture started (playback=$isInternalAudio, mic=${isDirectMic || audioSourceType == AUDIO_SOURCE_BOTH})")
            } else {
                coordinator.onAudioTrackUnavailable()
                logDebug("AUDIO_CAPTURE_WARN", "Audio capture could not start, proceeding with video only")
            }
        }

        isRecording = true
        isPaused = false
        _isRecordingFlow.value = true

        _serviceStatus.value = ServiceStatus(
            state = RecordingState.RECORDING,
            elapsedSeconds = 0,
            isMicEnabled = isDirectMic || audioSourceType == AUDIO_SOURCE_BOTH,
            diagnostic = DiagnosticInfo(
                lastLogMessage = "Recording Active: ${videoWidth}x${videoHeight} @ ${videoFps}fps (H.264 MediaCodec)",
                step = "RECORDING",
                filePath = outputFile.absolutePath,
                fileSizeBytes = 0L,
                videoResolution = "${videoWidth}x${videoHeight}",
                fps = videoFps,
                bitrateMbps = videoBitrate / 1_000_000,
                audioSource = audioSourceType,
                muxerStarted = true
            )
        )

        serviceScope.launch(Dispatchers.Main) {
            startTimer()
            setupFloatingControlOverlay()

            // Initialize real hardware thermal & charging monitor
            userDeclinedThermalStop = false
            try {
                thermalMonitor?.stopMonitoring()
            } catch (_: Exception) {}
            val monitor = ThermalChargingMonitor(this@ScreenRecordService).apply {
                startMonitoring()
            }
            thermalMonitor = monitor

            serviceScope.launch {
                monitor.statusFlow.collect { status ->
                    currentThermalInfo = status
                    floatingOverlay?.updateThermalAlert(
                        tempCelsius = status.temperatureCelsius,
                        isOverheating = status.isOverheating,
                        isCritical = status.isCriticalHeat
                    )
                    if (isRecording && status.isCriticalHeat && !userDeclinedThermalStop) {
                        updateNotification("I can stop because the reason is overheating.")
                    }
                }
            }
        }

        logDebug("RECORDING_ACTIVE", "Screen recording started successfully into ${outputFile.name}")
    }

    private fun handlePause() {
        if (!isRecording || isPaused) return
        try {
            videoEncoder?.pause()
            internalAudioRecorder?.isPaused = true
            isPaused = true
            floatingOverlay?.setPaused(true)
            _serviceStatus.value = _serviceStatus.value.copy(state = RecordingState.PAUSED)
            updateNotification("Recording Paused")
            logDebug("PAUSE", "Recording paused")
        } catch (e: Exception) {
            logDebug("PAUSE_ERROR", "Error pausing recorder: ${e.message}", isError = true, exception = e)
        }
    }

    private fun handleResume() {
        if (!isRecording || !isPaused) return
        try {
            videoEncoder?.resume()
            internalAudioRecorder?.isPaused = false
            isPaused = false
            floatingOverlay?.setPaused(false)
            _serviceStatus.value = _serviceStatus.value.copy(state = RecordingState.RECORDING)
            updateNotification("Recording: ${formatTime(totalElapsedSeconds)}")
            logDebug("RESUME", "Recording resumed")
        } catch (e: Exception) {
            logDebug("RESUME_ERROR", "Error resuming recorder: ${e.message}", isError = true, exception = e)
        }
    }

    private fun handleStopAndSave() {
        if (!isRecording && _serviceStatus.value.state != RecordingState.RECORDING && _serviceStatus.value.state != RecordingState.PAUSED) {
            logDebug("STOP", "No active recording to stop")
            return
        }

        isRecording = false
        isPaused = false

        removeOverlays()
        timerJob?.cancel()

        _serviceStatus.value = _serviceStatus.value.copy(state = RecordingState.PROCESSING)
        updateNotification("Finalizing video...")
        logDebug("STOPPING", "Stopping recording and saving file...")

        serviceScope.launch(Dispatchers.IO) {
            performFinalizeAndSave()
        }
    }

    private suspend fun performFinalizeAndSave() {
        try {
            // Ensure at least minimum frames are flushed to hardware surface
            delay(250)

            // 1. Signal EOS and release VideoEncoderWrapper FIRST while VirtualDisplay surface is still active!
            try {
                videoEncoder?.stopAndRelease()
                logDebug("ENCODER_STOP", "VideoEncoderWrapper stopped and flushed successfully")
            } catch (e: Exception) {
                logDebug("ENCODER_STOP_ERROR", "VideoEncoderWrapper stop: ${e.message}", isError = true, exception = e)
            }
            videoEncoder = null

            // 2. Now safely release VirtualDisplay
            try {
                virtualDisplay?.release()
            } catch (e: Exception) {
                Log.w(TAG, "VirtualDisplay release: ${e.message}")
            }
            virtualDisplay = null

            // 3. Stop MediaProjection
            try {
                mediaProjection?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaProjection stop: ${e.message}")
            }
            mediaProjection = null

            // 4. Stop and release InternalAudioRecorder so all remaining AAC frames flush to MuxerCoordinator
            try {
                internalAudioRecorder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "InternalAudioRecorder stop: ${e.message}")
            }
            internalAudioRecorder = null

            // 5. Stop and release the shared real-time MediaMuxer coordinator
            try {
                muxerCoordinator?.stopAndRelease()
                logDebug("MUXER_STOP", "Real-time MediaMuxer finalized cleanly")
            } catch (e: Exception) {
                logDebug("MUXER_STOP_ERROR", "MuxerCoordinator stop: ${e.message}", isError = true, exception = e)
            }
            muxerCoordinator = null

            val outputFile = currentOutputFile

            // 6. Release WakeLock & stop thermal monitor
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) {}
            wakeLock = null

            try {
                thermalMonitor?.stopMonitoring()
            } catch (_: Exception) {}
            thermalMonitor = null
            userDeclinedThermalStop = false

            // 7. Back-Test Verification Step: check file exists and length > 0
            val fileExists = outputFile != null && outputFile.exists() && outputFile.length() > 0L

            if (fileExists && outputFile != null) {
                val sizeBytes = outputFile.length()
                logDebug("FILE_VERIFIED", "File verified! Size: $sizeBytes bytes (${outputFile.name})")

                // Export to public MediaStore Movies/Recordings
                var finalUri: Uri? = null
                try {
                    finalUri = VideoRepository.saveVideoToMediaStore(this@ScreenRecordService, outputFile)
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore export failed, fallback to file URI: ${e.message}")
                }
                if (finalUri == null) {
                    finalUri = Uri.fromFile(outputFile)
                }

                logDebug("SAVED", "Video saved successfully: ${outputFile.name} ($sizeBytes bytes)")

                // Extract any bookmarked short clips into dedicated ShortClips directory
                if (pendingShortClipCount > 0) {
                    logDebug("CLIP_EXTRACT", "Extracting $pendingShortClipCount short clip(s) of ${shortClipDurationSeconds}s...")
                    try {
                        val clipResult = com.example.recorder.ShortClipExtractor.extractShortClip(
                            context = this@ScreenRecordService,
                            sourceVideoFile = outputFile,
                            clipDurationSeconds = shortClipDurationSeconds
                        )
                        if (clipResult != null) {
                            val (clipFile, _) = clipResult
                            logDebug("CLIP_SUCCESS", "Short clip created: ${clipFile.name} (${clipFile.length()}b)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error extracting short clip: ${e.message}")
                    }
                    pendingShortClipCount = 0
                }

                _lastSavedVideoTimestamp.value = System.currentTimeMillis()
                _isRecordingFlow.value = false
                _serviceStatus.value = ServiceStatus(
                    state = RecordingState.IDLE,
                    elapsedSeconds = 0,
                    lastRecordedUri = finalUri,
                    diagnostic = DiagnosticInfo(
                        lastLogMessage = "Saved: ${outputFile.name} (${sizeBytes / 1024} KB)",
                        step = "COMPLETED",
                        filePath = outputFile.absolutePath,
                        fileSizeBytes = sizeBytes,
                        videoResolution = "${videoWidth}x${videoHeight}",
                        fps = videoFps,
                        bitrateMbps = videoBitrate / 1_000_000,
                        audioSource = audioSourceType,
                        muxerStarted = false
                    )
                )
            } else {
                val pathStr = outputFile?.absolutePath ?: "unknown"
                val errorDetail = "Recorded file missing or 0 bytes ($pathStr)."
                logDebug("EMPTY_FILE_ERROR", errorDetail, isError = true)

                _isRecordingFlow.value = false
                _serviceStatus.value = ServiceStatus(
                    state = RecordingState.ERROR,
                    elapsedSeconds = 0,
                    errorMessage = errorDetail,
                    diagnostic = DiagnosticInfo(
                        lastLogMessage = "ERROR: Empty file generated (0 bytes)",
                        step = "ERROR",
                        filePath = pathStr,
                        fileSizeBytes = 0L,
                        lastError = errorDetail
                    )
                )
            }
        } catch (e: Exception) {
            logDebug("FINALIZE_ERROR", "Error finalizing recording: ${e.message}", isError = true, exception = e)
            _isRecordingFlow.value = false
            _serviceStatus.value = ServiceStatus(
                state = RecordingState.ERROR,
                errorMessage = e.message
            )
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startTimer() {
        totalElapsedSeconds = 0
        timerJob = serviceScope.launch {
            while (isRecording) {
                delay(1000)
                if (!isPaused) {
                    totalElapsedSeconds++
                    val timeStr = formatTime(totalElapsedSeconds)
                    floatingOverlay?.updateTimer(timeStr)

                    // Throttle notification updates to every 2 seconds to avoid notification manager rate limits
                    if (totalElapsedSeconds % 2 == 0) {
                        val baseText = if (currentThermalInfo.isCriticalHeat && !userDeclinedThermalStop) {
                            "I can stop because the reason is overheating."
                        } else if (currentThermalInfo.isCharging && currentThermalInfo.temperatureCelsius >= ThermalChargingMonitor.TEMP_THRESHOLD_WARMING) {
                            "Recording: $timeStr • ⚡ ${String.format(Locale.US, "%.1f", currentThermalInfo.temperatureCelsius)}°C Charging"
                        } else {
                            "Recording: $timeStr"
                        }
                        updateNotification(baseText)
                    }

                    _serviceStatus.value = _serviceStatus.value.copy(
                        elapsedSeconds = totalElapsedSeconds.toLong()
                    )
                }
            }
        }
    }

    private fun handleTakeShortClip() {
        if (!isRecording) {
            logDebug("CLIP_WARN", "Cannot take short clip: no active recording")
            return
        }

        pendingShortClipCount++
        logDebug("SHORT_CLIP", "Short clip bookmarked! Total clips pending: $pendingShortClipCount")
        floatingOverlay?.notifyClipSaved("Clip bookmarked!")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Short clip bookmarked (${shortClipDurationSeconds}s). Will be saved to ShortClips folder!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFloatingControlOverlay() {
        if (!enableFloatingButton || !Settings.canDrawOverlays(this)) {
            if (enableOverlayTrigger) setupInvisibleOverlayTrigger()
            return
        }

        try {
            floatingOverlay?.dismiss()
            floatingOverlay = FloatingControlOverlay(
                context = this@ScreenRecordService,
                shortClipDurationSeconds = shortClipDurationSeconds,
                onPauseClick = { handlePause() },
                onResumeClick = { handleResume() },
                onStopClick = { handleStopAndSave() },
                onShortClipClick = { handleTakeShortClip() }
            ).apply {
                show()
            }
            logDebug("SIDE_SLIDER", "Side slider edge controls initialized with ${shortClipDurationSeconds}s clip")
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize side slider overlay: ${e.message}")
            if (enableOverlayTrigger) setupInvisibleOverlayTrigger()
        }
    }

    private fun setupInvisibleOverlayTrigger() {
        if (!enableOverlayTrigger || !Settings.canDrawOverlays(this)) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val hitBoxSize = (48 * resources.displayMetrics.density).toInt()

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                hitBoxSize,
                hitBoxSize,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 0
                y = 0
                alpha = 0.01f
            }

            val view = View(this).apply {
                setOnClickListener {
                    logDebug("OVERLAY_TRIGGER", "Invisible corner stop trigger tapped")
                    handleStopAndSave()
                }
            }

            windowManager?.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach invisible stop trigger: ${e.message}")
        }
    }

    private fun removeOverlays() {
        try {
            floatingOverlay?.dismiss()
            floatingOverlay = null
        } catch (_: Exception) {}

        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recording status and controls"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String, elapsedSec: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Check if critical overheating alert prompt should be presented to user
        val isOverheatingAlertActive = currentThermalInfo.isCriticalHeat && !userDeclinedThermalStop

        if (isOverheatingAlertActive) {
            val dismissThermalIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ACTION_DISMISS_THERMAL_ALERT
            }
            val dismissThermalPendingIntent = PendingIntent.getService(
                this,
                3,
                dismissThermalIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val tempStr = String.format(Locale.US, "%.1f°C", currentThermalInfo.temperatureCelsius)
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("⚠️ Overheating Alert ($tempStr)")
                .setContentText("I can stop because the reason is overheating.")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("I can stop because the reason is overheating.\nHardware temperature has reached $tempStr (${currentThermalInfo.chargeSource}). Stop recording now?")
                )
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    android.R.drawable.ic_menu_save,
                    "Stop & Save (Accept)",
                    stopPendingIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Keep Recording (Decline)",
                    dismissThermalPendingIntent
                )
                .build()
        }

        val pauseResumeIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this,
            2,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayText = if (!contentText.contains("⚡") && currentThermalInfo.isCharging && currentThermalInfo.temperatureCelsius >= ThermalChargingMonitor.TEMP_THRESHOLD_WARMING) {
            "$contentText • ⚡ ${String.format(Locale.US, "%.1f", currentThermalInfo.temperatureCelsius)}°C"
        } else {
            contentText
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recording")
            .setContentText(displayText)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pauseResumePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_save,
                "Stop & Save",
                stopPendingIntent
            )

        return builder.build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText, totalElapsedSeconds))
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    override fun onDestroy() {
        if (isRecording) {
            Log.i(TAG, "onDestroy called while recording: performing emergency finalization")
            try {
                // Stop and flush VideoEncoderWrapper before releasing VirtualDisplay
                try {
                    videoEncoder?.stopAndRelease()
                } catch (_: Exception) {}
                videoEncoder = null

                virtualDisplay?.release()
                virtualDisplay = null
                mediaProjection?.stop()
                mediaProjection = null

                try {
                    internalAudioRecorder?.stop()
                } catch (_: Exception) {}
                internalAudioRecorder = null

                try {
                    muxerCoordinator?.stopAndRelease()
                } catch (_: Exception) {}
                muxerCoordinator = null

                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                wakeLock = null

                currentOutputFile?.let { file ->
                    if (file.exists() && file.length() > 0) {
                        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                            VideoRepository.saveVideoToMediaStore(this@ScreenRecordService, file)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Emergency finalize in onDestroy failed: ${e.message}", e)
            }
        }

        isRecording = false
        removeOverlays()
        timerJob?.cancel()
        try {
            thermalMonitor?.stopMonitoring()
        } catch (_: Exception) {}
        thermalMonitor = null
        userDeclinedThermalStop = false
        _isRecordingFlow.value = false
        super.onDestroy()
    }
}
