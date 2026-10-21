package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioHealthMonitor
import com.example.audio.AudioMixerEngine
import com.example.audio.MicrophoneController
import com.example.audio.VoiceMode
import com.example.data.ProfileDataStore
import com.example.model.RecordingState
import com.example.model.ServiceStatus
import com.example.overlay.FloatingControlOverlay
import com.example.recorder.MuxerCoordinator
import com.example.recorder.RecordTimeClock
import com.example.repository.RecordingRepository
import com.example.system.CallStateDetector
import com.example.system.DndManager
import com.example.video.ScreenVideoEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "regonex_recording_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_PAUSE = "com.example.service.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.service.ACTION_RESUME"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_APP_AUDIO = "extra_app_audio"
        const val EXTRA_MIC_AUDIO = "extra_mic_audio"
        const val EXTRA_AUTO_DND = "extra_auto_dnd"
        const val EXTRA_FLOATING_BUTTON = "extra_floating_button"
        const val EXTRA_GAME_VOICE_CHAT = "extra_game_voice_chat"
        const val EXTRA_AUDIO_BALANCE = "extra_audio_balance"
        const val EXTRA_VOICE_MODE = "extra_voice_mode"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE = "extra_bitrate"

        private val _serviceStatus = MutableStateFlow(ServiceStatus())
        val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus.asStateFlow()

        fun isRecording(): Boolean {
            return _serviceStatus.value.state == RecordingState.RECORDING ||
                    _serviceStatus.value.state == RecordingState.PAUSED
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var recordClock: RecordTimeClock? = null
    private var videoEncoder: ScreenVideoEncoder? = null
    private var audioMixer: AudioMixerEngine? = null
    private var muxerCoordinator: MuxerCoordinator? = null
    private var floatingOverlay: FloatingControlOverlay? = null
    private var micController: MicrophoneController? = null

    private lateinit var dndManager: DndManager
    private var callStateDetector: CallStateDetector? = null

    private var currentTempFile: File? = null
    private var recordingStartTime = 0L

    private var isUserManuallyPaused = false
    private var isCallPaused = false

    private var videoWidth = 1080
    private var videoHeight = 1920
    private var isMicActive = false
    private var isAppAudioActive = true
    private var isGameVoiceChatActive = false
    private var audioBalance = 0.5f
    private var voiceModeStr = "TOGGLE"
    private var isAutoDndActive = true
    private var isFloatingButtonEnabled = true
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var repository: RecordingRepository

    override fun onCreate() {
        super.onCreate()
        repository = RecordingRepository(applicationContext)
        dndManager = DndManager(applicationContext)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()

        callStateDetector = CallStateDetector(
            context = applicationContext,
            onCallStarted = { handleCallStarted() },
            onCallEnded = { handleCallEnded() }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    Log.e(TAG, "Invalid projection intent or permission denied")
                    _serviceStatus.value = ServiceStatus(
                        state = RecordingState.ERROR,
                        errorMessage = "Screen capture permission was not granted."
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                val appAudio = intent.getBooleanExtra(EXTRA_APP_AUDIO, true)
                val micAudio = intent.getBooleanExtra(EXTRA_MIC_AUDIO, false)
                val autoDnd = intent.getBooleanExtra(EXTRA_AUTO_DND, true)
                val floatingBtn = intent.getBooleanExtra(EXTRA_FLOATING_BUTTON, true)
                val gameVoiceChat = intent.getBooleanExtra(EXTRA_GAME_VOICE_CHAT, false)
                val audioBal = intent.getFloatExtra(EXTRA_AUDIO_BALANCE, 0.5f)
                val voiceMode = intent.getStringExtra(EXTRA_VOICE_MODE) ?: "TOGGLE"
                val width = intent.getIntExtra(EXTRA_WIDTH, 1080)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1920)
                val fps = intent.getIntExtra(EXTRA_FPS, 60)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)

                startRecordingWithFallback(
                    resultCode = resultCode,
                    resultData = resultData,
                    appAudio = appAudio,
                    micAudio = micAudio,
                    autoDnd = autoDnd,
                    floatingBtn = floatingBtn,
                    gameVoiceChat = gameVoiceChat,
                    audioBal = audioBal,
                    voiceMode = voiceMode,
                    reqWidth = width,
                    reqHeight = height,
                    fps = fps,
                    bitrate = bitrate
                )
            }
            ACTION_PAUSE -> {
                isUserManuallyPaused = true
                pauseRecording(isCall = false)
            }
            ACTION_RESUME -> {
                isUserManuallyPaused = false
                if (!isCallPaused) {
                    resumeRecording()
                }
            }
            ACTION_STOP -> stopRecording()
        }

        return START_STICKY
    }

    private fun startRecordingWithFallback(
        resultCode: Int,
        resultData: Intent,
        appAudio: Boolean,
        micAudio: Boolean,
        autoDnd: Boolean,
        floatingBtn: Boolean,
        gameVoiceChat: Boolean,
        audioBal: Float,
        voiceMode: String = "TOGGLE",
        reqWidth: Int,
        reqHeight: Int,
        fps: Int,
        bitrate: Int
    ) {
        val success = attemptStart(
            resultCode, resultData, appAudio, micAudio, autoDnd, floatingBtn, gameVoiceChat, audioBal, voiceMode, reqWidth, reqHeight, fps, bitrate
        )

        if (!success) {
            Log.w(TAG, "Initial profile failed. Stepping down to safe fallback profile.")
            cleanup()
            val safeWidth = 720
            val safeHeight = (reqHeight * 720 / reqWidth.coerceAtLeast(1) / 2) * 2
            val safeSuccess = attemptStart(
                resultCode, resultData, appAudio, micAudio, autoDnd, floatingBtn, gameVoiceChat, audioBal, voiceMode, safeWidth, safeHeight, 30, 4_500_000
            )

            if (safeSuccess) {
                _serviceStatus.value = _serviceStatus.value.copy(
                    errorMessage = "Recording started with safe fallback. Try 'Re-analyze device' in the menu if needed."
                )
            } else {
                _serviceStatus.value = ServiceStatus(
                    state = RecordingState.ERROR,
                    errorMessage = "Device video encoder could not initialize recording."
                )
                cleanup()
                stopSelf()
            }
        }
    }

    private fun attemptStart(
        resultCode: Int,
        resultData: Intent,
        appAudio: Boolean,
        micAudio: Boolean,
        autoDnd: Boolean,
        floatingBtn: Boolean,
        gameVoiceChat: Boolean,
        audioBal: Float,
        voiceMode: String = "TOGGLE",
        reqWidth: Int,
        reqHeight: Int,
        fps: Int,
        bitrate: Int
    ): Boolean {
        return try {
            voiceModeStr = voiceMode
            isAppAudioActive = appAudio
            isMicActive = micAudio
            isAutoDndActive = autoDnd
            isFloatingButtonEnabled = floatingBtn
            isGameVoiceChatActive = gameVoiceChat
            audioBalance = audioBal
            isUserManuallyPaused = false
            isCallPaused = false

            // Hold partial wake lock to prevent CPU sleep during long gameplay recordings
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RegonexRecorder::ScreenRecording").apply {
                    setReferenceCounted(false)
                }
                wakeLock?.acquire(6 * 60 * 60 * 1000L) // 6 hours max safe timeout
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
            }

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val densityDpi = metrics.densityDpi

            // Force even dimensions strictly matching encoder input surface
            videoWidth = (reqWidth / 2) * 2
            videoHeight = (reqHeight / 2) * 2

            // Auto Do Not Disturb
            if (isAutoDndActive) {
                dndManager.applyDndIfEnabled(true)
            }

            val hasMicPermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            // Foreground notification with both media projection and microphone service types declared
            // while visible, so Android 14+ allows microphone access later from background
            val notification = buildRecordingNotification(isPaused = false, isCall = false, elapsedSec = 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (hasMicPermission) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    serviceType
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                ?: return false

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    stopRecording()
                }
            }, Handler(Looper.getMainLooper()))

            val outputDir = getExternalFilesDir("Regonex_Temp") ?: cacheDir
            if (!outputDir.exists()) outputDir.mkdirs()
            currentTempFile = File(outputDir, "regonex_rec_${System.currentTimeMillis()}.mp4")

            // Initialize Unified Clock
            recordClock = RecordTimeClock().apply { start() }

            // Initialize Audio Engine with dedicated reader threads, ring buffers & health monitor
            audioMixer = AudioMixerEngine(
                context = applicationContext,
                mediaProjection = mediaProjection,
                isAppAudioEnabled = isAppAudioActive,
                isMicEnabled = isMicActive,
                isGameVoiceChatMode = isGameVoiceChatActive,
                audioBalance = audioBalance,
                clock = recordClock!!,
                sampleRate = 44_100,
                audioBitrate = 160_000
            )

            // Listen for audio health recovery events to reflect subtle notice in notification
            audioMixer?.healthMonitor?.healthListener = object : AudioHealthMonitor.HealthListener {
                override fun onAudioRecoveryOccurred(event: AudioHealthMonitor.HealthEvent) {
                    val elapsed = recordClock?.getElapsedSeconds() ?: 0L
                    updateNotification(isPaused = isUserManuallyPaused || isCallPaused, isCall = isCallPaused, elapsedSec = elapsed)
                }
                override fun onAppAudioBlockSuspected() {
                    val elapsed = recordClock?.getElapsedSeconds() ?: 0L
                    updateNotification(isPaused = isUserManuallyPaused || isCallPaused, isCall = isCallPaused, elapsedSec = elapsed)
                }
            }

            val hasAudioTrack = audioMixer?.isAudioEnabled == true
            val expectedTracks = if (hasAudioTrack) 2 else 1

            // Muxer Coordinator with strict format tracking & sample queuing
            muxerCoordinator = MuxerCoordinator(currentTempFile!!, expectedTracks)

            // Video rotation orientation calculation
            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation
            val orientationDegrees = when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            muxerCoordinator?.setOrientation(orientationDegrees)

            // Video Encoder Setup
            videoEncoder = ScreenVideoEncoder(
                width = videoWidth,
                height = videoHeight,
                frameRate = fps,
                bitrate = bitrate,
                clock = recordClock!!
            )
            val surface = videoEncoder!!.prepare()

            // VirtualDisplay strictly matching encoder surface size
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "Regonex_Display",
                videoWidth,
                videoHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            // Start draining video
            videoEncoder?.startDraining(muxerCoordinator!!)

            // Start audio if active
            if (hasAudioTrack) {
                audioMixer?.start(muxerCoordinator!!)
            }

            // Start call state detection
            callStateDetector?.startListening()

            // Initialize Floating Control Button if permitted and enabled
            if (isFloatingButtonEnabled && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
                setupFloatingOverlay()
            }

            recordingStartTime = System.currentTimeMillis()

            _serviceStatus.value = ServiceStatus(
                state = RecordingState.RECORDING,
                elapsedSeconds = 0,
                isMicEnabled = isMicActive
            )

            startTimer()
            Log.d(TAG, "Screen recording started successfully (${videoWidth}x${videoHeight} @ ${fps}fps, audio: $hasAudioTrack)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "attemptStart failed: ${e.message}", e)
            false
        }
    }

    private fun setupFloatingOverlay() {
        val initialMode = if (voiceModeStr == "HOLD_TO_TALK") VoiceMode.HOLD_TO_TALK else VoiceMode.TOGGLE
        micController = MicrophoneController(
            context = applicationContext,
            gainControl = audioMixer,
            initialVoiceMode = initialMode,
            initialIsVoiceActive = isMicActive,
            onVoiceModeChanged = { newMode ->
                voiceModeStr = newMode.name
                serviceScope.launch {
                    val profileDataStore = ProfileDataStore(applicationContext)
                    profileDataStore.setVoiceMode(newMode.name)
                }
            }
        )

        audioMixer?.onMicRestartedCallback = {
            micController?.onMicRestarted()
        }

        floatingOverlay = FloatingControlOverlay(
            context = applicationContext,
            micController = micController!!,
            onStopClick = { stopRecording() },
            onPauseResumeClick = {
                if (isUserManuallyPaused) {
                    isUserManuallyPaused = false
                    if (!isCallPaused) resumeRecording()
                } else {
                    isUserManuallyPaused = true
                    pauseRecording(isCall = false)
                }
            }
        ).apply {
            show()
        }
    }

    private fun handleCallStarted() {
        Log.d(TAG, "Phone call detected -> auto-pausing recording and releasing microphone")
        isCallPaused = true
        audioMixer?.setCallMuted(true)
        pauseRecording(isCall = true)
    }

    private fun handleCallEnded() {
        Log.d(TAG, "Phone call ended -> resuming audio capture and auto-resuming recording if not manually paused")
        isCallPaused = false
        audioMixer?.setCallMuted(false)
        if (!isUserManuallyPaused) {
            resumeRecording()
        } else {
            updateNotification(isPaused = true, isCall = false, elapsedSec = recordClock?.getElapsedSeconds() ?: 0)
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val elapsedSec = recordClock?.getElapsedSeconds() ?: 0L
                if (_serviceStatus.value.state == RecordingState.RECORDING) {
                    _serviceStatus.value = _serviceStatus.value.copy(
                        elapsedSeconds = elapsedSec
                    )
                    updateNotification(isPaused = false, isCall = false, elapsedSec = elapsedSec)

                    // Update floating overlay timer
                    val timeString = formatTime(elapsedSec)
                    floatingOverlay?.updateTimer(timeString, isRecordingPaused = false)
                }
            }
        }
    }

    private fun pauseRecording(isCall: Boolean) {
        if (_serviceStatus.value.state != RecordingState.RECORDING) return
        try {
            recordClock?.pause()
            _serviceStatus.value = _serviceStatus.value.copy(state = RecordingState.PAUSED)
            val elapsedSec = recordClock?.getElapsedSeconds() ?: 0L
            updateNotification(isPaused = true, isCall = isCall, elapsedSec = elapsedSec)
            floatingOverlay?.updateTimer(formatTime(elapsedSec), isRecordingPaused = true)
            Log.d(TAG, "Recording paused (callReason: $isCall)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording: ${e.message}", e)
        }
    }

    private fun resumeRecording() {
        if (_serviceStatus.value.state != RecordingState.PAUSED) return
        try {
            recordClock?.resume()
            videoEncoder?.onRecordingResumed()
            _serviceStatus.value = _serviceStatus.value.copy(state = RecordingState.RECORDING)
            val elapsedSec = recordClock?.getElapsedSeconds() ?: 0L
            updateNotification(isPaused = false, isCall = false, elapsedSec = elapsedSec)
            floatingOverlay?.updateTimer(formatTime(elapsedSec), isRecordingPaused = false)
            Log.d(TAG, "Recording resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording: ${e.message}", e)
        }
    }

    private fun stopRecording() {
        val currentState = _serviceStatus.value.state
        if (currentState == RecordingState.PROCESSING || currentState == RecordingState.IDLE) {
            return
        }

        timerJob?.cancel()
        timerJob = null

        // Immediately remove floating overlay so it doesn't linger
        floatingOverlay?.destroy()
        floatingOverlay = null

        // Update state to Saving video
        _serviceStatus.value = _serviceStatus.value.copy(state = RecordingState.PROCESSING)

        // Show ongoing "Saving video" notification to keep service alive
        updateSavingNotification()

        serviceScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Stage 1/5: Signaling End-Of-Stream to encoders")
            videoEncoder?.signalEndOfStream()
            audioMixer?.signalEndOfStream()

            Log.d(TAG, "Stage 2/5: Awaiting EOS drain completion from encoders")
            videoEncoder?.awaitEndOfStream(timeoutMs = 3000L)
            audioMixer?.awaitEndOfStream(timeoutMs = 3000L)

            Log.d(TAG, "Stage 3/5: Stopping and finalizing MediaMuxer")
            muxerCoordinator?.stopAndRelease()

            Log.d(TAG, "Stage 4/5: Releasing hardware capture surfaces & inputs")
            cleanupHardware()

            val fileToSave = currentTempFile
            val totalDurationMs = (recordClock?.getElapsedSeconds() ?: 0L) * 1000L

            Log.d(TAG, "Stage 5/5: Publishing validated MP4 to gallery")
            var savedUri: Uri? = null
            if (fileToSave != null && fileToSave.exists() && fileToSave.length() > 1024L) {
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val fileName = "Regonex_${dateFormat.format(Date())}"
                savedUri = repository.saveVideoToGallery(
                    sourceFile = fileToSave,
                    fileName = fileName,
                    durationMs = totalDurationMs,
                    width = videoWidth,
                    height = videoHeight
                )
            } else {
                Log.e(TAG, "Recording file invalid or zero bytes: ${fileToSave?.absolutePath}")
                if (fileToSave?.exists() == true) fileToSave.delete()
            }

            _serviceStatus.value = ServiceStatus(
                state = RecordingState.IDLE,
                elapsedSeconds = 0,
                lastRecordedUri = savedUri,
                errorMessage = if (savedUri == null) "Recording failed to save. Please try 'Re-analyze device'." else null
            )

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.d(TAG, "Recording pipeline stop complete. Saved URI: $savedUri")
        }
    }

    private fun cleanupHardware() {
        try {
            callStateDetector?.stopListening()
        } catch (_: Exception) {}

        try {
            dndManager.restoreDnd()
        } catch (_: Exception) {}

        try {
            videoEncoder?.release()
        } catch (_: Exception) {}
        videoEncoder = null

        try {
            audioMixer?.release()
        } catch (_: Exception) {}
        audioMixer = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun cleanup() {
        floatingOverlay?.destroy()
        floatingOverlay = null
        micController = null
        cleanupHardware()
        muxerCoordinator?.stopAndRelease()
        muxerCoordinator = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildRecordingNotification(isPaused: Boolean, isCall: Boolean, elapsedSec: Long): Notification {
        val timeFormatted = formatTime(elapsedSec)

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = if (isPaused) {
            val resumeIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_RESUME
            }
            val resumePendingIntent = PendingIntent.getService(
                this, 1, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                getString(R.string.action_resume),
                resumePendingIntent
            ).build()
        } else {
            val pauseIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePendingIntent = PendingIntent.getService(
                this, 2, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_pause),
                pausePendingIntent
            ).build()
        }

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 3, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.action_stop),
            stopPendingIntent
        ).build()

        val title = when {
            isCall -> getString(R.string.recording_paused_call)
            isPaused -> getString(R.string.recording_paused)
            else -> getString(R.string.recording_active)
        }

        val hasRecovered = audioMixer?.healthMonitor?.hasRecoveredEvents == true
        val isAppAudioBlocked = audioMixer?.healthMonitor?.isAppAudioBlockedSuspected == true
        val statusSuffix = when {
            isAppAudioBlocked -> " • Game may block audio"
            hasRecovered -> " • Audio recovered"
            else -> ""
        }

        val contentText = "$timeFormatted • Regonex Recorder$statusSuffix"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(pauseResumeAction)
            .addAction(stopAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateSavingNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Saving video…")
            .setContentText("Finalizing MP4 file to gallery…")
            .setSmallIcon(R.drawable.ic_stat_record)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(isPaused: Boolean, isCall: Boolean, elapsedSec: Long) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildRecordingNotification(isPaused, isCall, elapsedSec)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun formatTime(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }
}
