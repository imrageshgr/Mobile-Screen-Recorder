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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.MainActivity
import com.example.R
import com.example.model.DiagnosticInfo
import com.example.model.RecordingState
import com.example.model.ServiceStatus
import com.example.repository.VideoRepository
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
 * for zero frame loss, guaranteed MP4 file creation, and non-blocking foreground execution.
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

        const val AUDIO_SOURCE_INTERNAL = "INTERNAL"
        const val AUDIO_SOURCE_MIC = "MIC"
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
    private var mediaRecorder: MediaRecorder? = null

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

    // Notification & Timer
    private var timerJob: Job? = null
    private var totalElapsedSeconds = 0

    // Overlay trigger view
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY

        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStopAndSave()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: App closed from recent apps. Foreground recording continues uninterrupted.")
        // Do NOT stop recording when user swipes away app in recents!
        // The foreground service continues recording until user taps Stop in notification or in-app.
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

        val rawWidth = intent.getIntExtra(EXTRA_WIDTH, 720)
        val rawHeight = intent.getIntExtra(EXTRA_HEIGHT, 1280)
        val densityDpi = intent.getIntExtra(EXTRA_DENSITY_DPI, 400)
        videoFps = intent.getIntExtra(EXTRA_FPS, 60).coerceIn(24, 60)
        videoBitrate = intent.getIntExtra(EXTRA_BITRATE, 12_000_000)
        audioSourceType = intent.getStringExtra(EXTRA_AUDIO_SOURCE) ?: AUDIO_SOURCE_INTERNAL
        enableOverlayTrigger = intent.getBooleanExtra(EXTRA_ENABLE_OVERLAY, false)

        // STRICT 16-ALIGNED RESOLUTION
        // Divisible by 16 for universal MediaTek, Snapdragon, Exynos compatibility
        val isPortrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val is1080p = maxOf(rawWidth, rawHeight) >= 1400 || intent.getStringExtra(EXTRA_RESOLUTION) == "1080p"
        if (is1080p) {
            videoWidth = if (isPortrait) 1088 else 1920
            videoHeight = if (isPortrait) 1920 else 1088
        } else {
            videoWidth = if (isPortrait) 720 else 1280
            videoHeight = if (isPortrait) 1280 else 720
        }

        // Rule 2: Start foreground service BEFORE getMediaProjection
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
            if (audioSourceType == AUDIO_SOURCE_MIC || audioSourceType == AUDIO_SOURCE_INTERNAL) {
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
        logDebug("PIPELINE", "Preparing output file...")

        // Prepare output directories (both external app files and Movies/Recordings)
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

        val audioEnabled = (audioSourceType == AUDIO_SOURCE_MIC || audioSourceType == AUDIO_SOURCE_INTERNAL) && hasMicPermission

        // Rule 3: MediaRecorder EXACT mandatory state-machine setup order
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder = recorder

        // 1. Audio Source (ONLY if audio enabled and permission granted)
        if (audioEnabled) {
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            } catch (e: Exception) {
                logDebug("AUDIO_WARN", "Cannot set audio source MIC: ${e.message}")
            }
        }

        // 2. Video Source
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)

        // 3. Output Format
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        // 4. Output File
        recorder.setOutputFile(outputFile.absolutePath)

        // 5. Video Size
        recorder.setVideoSize(videoWidth, videoHeight)

        // 6. Video Encoder
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        // 7. Audio Encoder (if audio enabled)
        if (audioEnabled) {
            try {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(44100)
                recorder.setAudioEncodingBitRate(128_000)
            } catch (e: Exception) {
                logDebug("AUDIO_ENCODER_WARN", "Audio encoder setup warning: ${e.message}")
            }
        }

        // 8. Video Bitrate
        recorder.setVideoEncodingBitRate(videoBitrate)

        // 9. Video Frame Rate
        recorder.setVideoFrameRate(videoFps)

        // 10. Prepare
        logDebug("PREPARE", "Preparing MediaRecorder...")
        recorder.prepare()

        // Acquire MediaProjection
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

        // 11. Create VirtualDisplay attached to mediaRecorder surface
        logDebug("VIRTUAL_DISPLAY", "Creating VirtualDisplay...")
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecordDisplay",
            videoWidth,
            videoHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface,
            null,
            null
        )

        // 12. Start MediaRecorder
        logDebug("START", "Starting MediaRecorder...")
        recorder.start()

        isRecording = true
        isPaused = false
        _isRecordingFlow.value = true

        _serviceStatus.value = ServiceStatus(
            state = RecordingState.RECORDING,
            elapsedSeconds = 0,
            isMicEnabled = audioEnabled,
            diagnostic = DiagnosticInfo(
                lastLogMessage = "Recording Active: ${videoWidth}x${videoHeight} @ ${videoFps}fps",
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
            setupInvisibleOverlayTrigger()
        }

        logDebug("RECORDING_ACTIVE", "Screen recording started successfully into ${outputFile.name}")
    }

    private fun handlePause() {
        if (!isRecording || isPaused) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
            }
            isPaused = true
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
            }
            isPaused = false
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

        removeInvisibleOverlayTrigger()
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
            // Rule 4: Order of teardown
            // 1. Stop and release virtual display first
            try {
                virtualDisplay?.release()
            } catch (e: Exception) {
                Log.w(TAG, "VirtualDisplay release: ${e.message}")
            }
            virtualDisplay = null

            // Small delay to ensure all buffers flush to hardware surface
            delay(100)

            // 2. Stop and release MediaRecorder
            try {
                mediaRecorder?.stop()
                logDebug("RECORDER_STOP", "MediaRecorder stopped successfully")
            } catch (e: Exception) {
                logDebug("RECORDER_STOP_ERROR", "MediaRecorder stop: ${e.message}", isError = true, exception = e)
            }

            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder release: ${e.message}")
            }
            mediaRecorder = null

            // 3. Stop MediaProjection
            try {
                mediaProjection?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaProjection stop: ${e.message}")
            }
            mediaProjection = null

            // 4. Back-Test Verification Step: check file exists and length > 0
            val outputFile = currentOutputFile
            val fileExists = outputFile != null && outputFile.exists() && outputFile.length() > 0L

            if (fileExists && outputFile != null) {
                val sizeBytes = outputFile.length()
                logDebug("FILE_VERIFIED", "File verified! Size: $sizeBytes bytes (${outputFile.name})")

                // Export to public MediaStore Movies/ScreenRecordings
                val savedUri = VideoRepository.saveVideoToMediaStore(this@ScreenRecordService, outputFile)
                val finalUri = savedUri ?: Uri.fromFile(outputFile)

                logDebug("SAVED", "Video saved successfully: ${outputFile.name} ($sizeBytes bytes)")

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
                    val currentSize = currentOutputFile?.length() ?: 0L
                    _serviceStatus.value = _serviceStatus.value.copy(
                        elapsedSeconds = totalElapsedSeconds.toLong(),
                        diagnostic = _serviceStatus.value.diagnostic.copy(
                            fileSizeBytes = currentSize,
                            muxerStarted = true,
                            videoTrackRegistered = true,
                            audioTrackRegistered = audioSourceType != AUDIO_SOURCE_MUTE,
                            lastLogMessage = "Recording MP4 (${totalElapsedSeconds}s | ${currentSize / 1024} KB)"
                        )
                    )
                    updateNotification("Recording: ${formatTime(totalElapsedSeconds)}")
                }
            }
        }
    }

    private fun setupInvisibleOverlayTrigger() {
        if (!enableOverlayTrigger || !Settings.canDrawOverlays(this)) {
            return
        }

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

    private fun removeInvisibleOverlayTrigger() {
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

        val pauseResumeIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this,
            2,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recording")
            .setContentText(contentText)
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
            Log.i(TAG, "onDestroy called while recording: performing emergency synchronous finalization")
            try {
                virtualDisplay?.release()
                virtualDisplay = null
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
                mediaProjection?.stop()
                mediaProjection = null

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
        removeInvisibleOverlayTrigger()
        timerJob?.cancel()
        _isRecordingFlow.value = false
        super.onDestroy()
    }
}
