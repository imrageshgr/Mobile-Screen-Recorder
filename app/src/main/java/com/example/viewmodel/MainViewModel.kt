package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ProfileDataStore
import com.example.model.DeviceProfile
import com.example.model.RecordingItem
import com.example.model.RecordingState
import com.example.model.ServiceStatus
import com.example.optimizer.AnalysisProgress
import com.example.optimizer.DeviceAnalyzer
import com.example.repository.RecordingRepository
import com.example.repository.VideoRepository
import com.example.service.ScreenCaptureService
import com.example.service.ScreenRecordService
import com.example.system.DndManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val videoRepository = VideoRepository(application.applicationContext)
    private val legacyRepository = RecordingRepository(application.applicationContext)
    private val dataStore = ProfileDataStore(application.applicationContext)
    private val analyzer = DeviceAnalyzer(application.applicationContext)
    val dndManager = DndManager(application.applicationContext)

    // ScreenRecordService status is the single source of truth for recording and hardware diagnostics
    val serviceStatus: StateFlow<ServiceStatus> = ScreenRecordService.serviceStatus

    val isDarkTheme: StateFlow<Boolean> = dataStore.isDarkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val selectedResolution: StateFlow<String> = dataStore.selectedResolution
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080p")

    val selectedFps: StateFlow<Int> = dataStore.selectedFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    val selectedBitrate: StateFlow<Int> = dataStore.selectedBitrate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 12_000_000)

    val selectedAudioSource: StateFlow<String> = dataStore.selectedAudioSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "INTERNAL")

    val enableOverlayTrigger: StateFlow<Boolean> = dataStore.enableOverlayTrigger
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isAppAudioEnabled: StateFlow<Boolean> = dataStore.isAppAudioEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isMicEnabled: StateFlow<Boolean> = dataStore.isMicEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isAutoDndEnabled: StateFlow<Boolean> = dataStore.isAutoDndEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isFloatingButtonEnabled: StateFlow<Boolean> = dataStore.isFloatingButtonEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isGameVoiceChatEnabled: StateFlow<Boolean> = dataStore.isGameVoiceChatEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val audioBalance: StateFlow<Float> = dataStore.audioBalance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.5f)

    val isBatteryOptPromptShown: StateFlow<Boolean> = dataStore.isBatteryOptPromptShown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val voiceMode: StateFlow<String> = dataStore.voiceMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "TOGGLE")

    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    private val _showOverlayPermissionDialog = MutableStateFlow(false)
    val showOverlayPermissionDialog: StateFlow<Boolean> = _showOverlayPermissionDialog.asStateFlow()

    private val _showBatteryOptDialog = MutableStateFlow(false)
    val showBatteryOptDialog: StateFlow<Boolean> = _showBatteryOptDialog.asStateFlow()

    private val _showGameVoiceChatInfoDialog = MutableStateFlow(false)
    val showGameVoiceChatInfoDialog: StateFlow<Boolean> = _showGameVoiceChatInfoDialog.asStateFlow()

    private val _deviceProfile = MutableStateFlow<DeviceProfile?>(null)
    val deviceProfile: StateFlow<DeviceProfile?> = _deviceProfile.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analysisProgress = MutableStateFlow(
        AnalysisProgress(
            step = 0,
            headline = "Initializing Analyzer",
            detail = "Preparing device test...",
            percentage = 0f
        )
    )
    val analysisProgress: StateFlow<AnalysisProgress> = _analysisProgress.asStateFlow()

    private val _recentRecordings = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recentRecordings: StateFlow<List<RecordingItem>> = _recentRecordings.asStateFlow()

    private val _countdownValue = MutableStateFlow<Int?>(null)
    val countdownValue: StateFlow<Int?> = _countdownValue.asStateFlow()

    private val _newlySavedRecording = MutableStateFlow<RecordingItem?>(null)
    val newlySavedRecording: StateFlow<RecordingItem?> = _newlySavedRecording.asStateFlow()

    private val _activePlayingItem = MutableStateFlow<RecordingItem?>(null)
    val activePlayingItem: StateFlow<RecordingItem?> = _activePlayingItem.asStateFlow()

    private val _selectedItemForAction = MutableStateFlow<RecordingItem?>(null)
    val selectedItemForAction: StateFlow<RecordingItem?> = _selectedItemForAction.asStateFlow()

    private val _showDeleteConfirmation = MutableStateFlow(false)
    val showDeleteConfirmation: StateFlow<Boolean> = _showDeleteConfirmation.asStateFlow()

    private val _showDeleteAllConfirmation = MutableStateFlow(false)
    val showDeleteAllConfirmation: StateFlow<Boolean> = _showDeleteAllConfirmation.asStateFlow()

    private val _showMicPermissionExplanation = MutableStateFlow(false)
    val showMicPermissionExplanation: StateFlow<Boolean> = _showMicPermissionExplanation.asStateFlow()

    private val _showDndPermissionDialog = MutableStateFlow(false)
    val showDndPermissionDialog: StateFlow<Boolean> = _showDndPermissionDialog.asStateFlow()

    private val _showPhoneStateDialog = MutableStateFlow(false)
    val showPhoneStateDialog: StateFlow<Boolean> = _showPhoneStateDialog.asStateFlow()

    private val _showAboutDialog = MutableStateFlow(false)
    val showAboutDialog: StateFlow<Boolean> = _showAboutDialog.asStateFlow()

    private val _uiEvents = MutableSharedFlow<String>()
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

    private var countdownJob: Job? = null
    private var pendingProjectionResultCode: Int? = null
    private var pendingProjectionData: Intent? = null

    init {
        loadProfileAndInitialize()
        refreshRecordings()
        observeServiceSavedRecordings()
    }

    fun setTab(index: Int) {
        _currentTab.value = index
        if (index == 1) {
            refreshRecordings()
        }
    }

    fun toggleTheme() {
        viewModelScope.launch {
            val newTheme = !isDarkTheme.value
            dataStore.setDarkTheme(newTheme)
        }
    }

    fun setResolution(res: String) {
        viewModelScope.launch {
            dataStore.setSelectedResolution(res)
        }
    }

    fun setFps(fps: Int) {
        viewModelScope.launch {
            dataStore.setSelectedFps(fps)
        }
    }

    fun setBitrate(bitrate: Int) {
        viewModelScope.launch {
            dataStore.setSelectedBitrate(bitrate)
        }
    }

    fun setAudioSource(source: String) {
        viewModelScope.launch {
            dataStore.setSelectedAudioSource(source)
        }
    }

    fun setEnableOverlayTrigger(enable: Boolean) {
        viewModelScope.launch {
            dataStore.setEnableOverlayTrigger(enable)
        }
    }

    private fun loadProfileAndInitialize() {
        viewModelScope.launch {
            val isScanned = dataStore.isInitialScanCompleted.first()
            val saved = dataStore.savedProfile.first()
            if (isScanned && saved != null) {
                _deviceProfile.value = saved
            } else {
                runDeviceOptimization()
            }
        }
    }

    fun runDeviceOptimization() {
        viewModelScope.launch {
            _isAnalyzing.value = true
            try {
                val profile = analyzer.analyzeDevice { progress ->
                    _analysisProgress.value = progress
                }
                dataStore.saveProfile(profile)
                _deviceProfile.value = profile

                // Apply optimized profile to active recording settings
                val resLabel = if (profile.height >= 1080 || profile.width >= 1080) "1080p" else "720p"
                dataStore.setSelectedResolution(resLabel)
                dataStore.setSelectedFps(profile.frameRate)
                dataStore.setSelectedBitrate(profile.bitrate)
                dataStore.setInitialScanCompleted(true)

                _uiEvents.emit("Optimal settings configured: $resLabel @ ${profile.frameRate} FPS")
            } catch (e: Exception) {
                val fallback = DeviceProfile.DEFAULT_SAFE_FALLBACK
                dataStore.saveProfile(fallback)
                dataStore.setSelectedResolution("720p")
                dataStore.setSelectedFps(30)
                dataStore.setSelectedBitrate(5_000_000)
                dataStore.setInitialScanCompleted(true)
                _deviceProfile.value = fallback
                _uiEvents.emit("Configured safe fallback configuration")
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun toggleAppAudio(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setAppAudioEnabled(enabled)
            val source = if (enabled) ScreenRecordService.AUDIO_SOURCE_INTERNAL else ScreenRecordService.AUDIO_SOURCE_MUTE
            dataStore.setSelectedAudioSource(source)
        }
    }

    fun toggleMicrophone(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setMicEnabled(enabled)
            val source = if (enabled) ScreenRecordService.AUDIO_SOURCE_MIC else ScreenRecordService.AUDIO_SOURCE_INTERNAL
            dataStore.setSelectedAudioSource(source)
        }
    }

    fun toggleVoiceMode() {
        viewModelScope.launch {
            val next = if (voiceMode.value == "TOGGLE") "HOLD" else "TOGGLE"
            dataStore.setVoiceMode(next)
        }
    }

    fun setVoiceMode(mode: String) {
        viewModelScope.launch {
            dataStore.setVoiceMode(mode)
        }
    }

    fun toggleAutoDnd(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setAutoDndEnabled(enabled)
        }
    }

    fun toggleFloatingButton(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setFloatingButtonEnabled(enabled)
        }
    }

    fun toggleGameVoiceChat(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setGameVoiceChatEnabled(enabled)
        }
    }

    fun setAudioBalance(balance: Float) {
        viewModelScope.launch {
            dataStore.setAudioBalance(balance)
        }
    }

    fun showBatteryOptDialog(show: Boolean) {
        _showBatteryOptDialog.value = show
    }

    fun dismissBatteryOptDialog(neverAskAgain: Boolean = true) {
        _showBatteryOptDialog.value = false
        if (neverAskAgain) {
            viewModelScope.launch {
                dataStore.setBatteryOptPromptShown(true)
            }
        }
    }

    fun showGameVoiceChatInfo(show: Boolean) {
        _showGameVoiceChatInfoDialog.value = show
    }

    fun showMicPermissionDialog(show: Boolean) {
        _showMicPermissionExplanation.value = show
    }

    fun showDndPermissionDialog(show: Boolean) {
        _showDndPermissionDialog.value = show
    }

    fun showPhoneStateDialog(show: Boolean) {
        _showPhoneStateDialog.value = show
    }

    fun showOverlayDialog(show: Boolean) {
        _showOverlayPermissionDialog.value = show
    }

    fun showAboutDialog(show: Boolean) {
        _showAboutDialog.value = show
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            val list = videoRepository.getRecordedVideos()
            _recentRecordings.value = list
        }
    }

    private fun observeServiceSavedRecordings() {
        viewModelScope.launch {
            ScreenRecordService.serviceStatus.collect { status ->
                if (status.lastRecordedUri != null && status.state == RecordingState.IDLE) {
                    onRecordingCompleted(status.lastRecordedUri)
                }
            }
        }
        viewModelScope.launch {
            ScreenCaptureService.serviceStatus.collect { status ->
                if (status.lastRecordedUri != null && status.state == RecordingState.IDLE) {
                    onRecordingCompleted(status.lastRecordedUri)
                }
            }
        }
    }

    private fun onRecordingCompleted(uri: Uri) {
        viewModelScope.launch {
            delay(400)
            val list = videoRepository.getRecordedVideos()
            _recentRecordings.value = list
            val match = list.find { it.uri == uri } ?: list.firstOrNull()
            _newlySavedRecording.value = match
        }
    }

    fun dismissNewlySavedDialog() {
        _newlySavedRecording.value = null
    }

    fun openPlayer(item: RecordingItem) {
        _activePlayingItem.value = item
    }

    fun closePlayer() {
        _activePlayingItem.value = null
    }

    fun selectItemForAction(item: RecordingItem?) {
        _selectedItemForAction.value = item
    }

    fun confirmDelete(show: Boolean) {
        _showDeleteConfirmation.value = show
    }

    fun deleteRecording(item: RecordingItem) {
        viewModelScope.launch {
            val success = videoRepository.deleteVideo(item.uri)
            if (success) {
                if (_activePlayingItem.value?.id == item.id) {
                    _activePlayingItem.value = null
                }
                if (_newlySavedRecording.value?.id == item.id) {
                    _newlySavedRecording.value = null
                }
                _selectedItemForAction.value = null
                _showDeleteConfirmation.value = false
                refreshRecordings()
                _uiEvents.emit("Recording deleted.")
            }
        }
    }

    fun confirmDeleteAll(show: Boolean) {
        _showDeleteAllConfirmation.value = show
    }

    fun deleteAllRecordings() {
        viewModelScope.launch {
            val count = videoRepository.deleteAllVideos()
            _activePlayingItem.value = null
            _newlySavedRecording.value = null
            _selectedItemForAction.value = null
            _showDeleteConfirmation.value = false
            _showDeleteAllConfirmation.value = false
            refreshRecordings()
            if (count > 0) {
                _uiEvents.emit("Deleted $count recordings.")
            } else {
                _uiEvents.emit("No recordings to delete.")
            }
        }
    }

    fun onProjectionPermissionGranted(resultCode: Int, data: Intent) {
        pendingProjectionResultCode = resultCode
        pendingProjectionData = data
        startCountdown()
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            _countdownValue.value = 3
            delay(1000)
            _countdownValue.value = 2
            delay(1000)
            _countdownValue.value = 1
            delay(1000)
            _countdownValue.value = null
            triggerServiceStart()
        }
    }

    fun skipCountdown() {
        countdownJob?.cancel()
        _countdownValue.value = null
        triggerServiceStart()
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        _countdownValue.value = null
        pendingProjectionResultCode = null
        pendingProjectionData = null
    }

    private fun triggerServiceStart() {
        val code = pendingProjectionResultCode ?: return
        val data = pendingProjectionData ?: return
        val context = getApplication<Application>().applicationContext

        // Determine resolution with strict 16-aligned dimensions
        val is1080p = selectedResolution.value == "1080p"
        val isPortrait = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val width = if (is1080p) (if (isPortrait) 1080 else 1920) else (if (isPortrait) 720 else 1280)
        val height = if (is1080p) (if (isPortrait) 1920 else 1080) else (if (isPortrait) 1280 else 720)
        val fps = selectedFps.value
        val bitrate = selectedBitrate.value
        val audioSource = selectedAudioSource.value
        val overlayTrigger = enableOverlayTrigger.value

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val dpi = metrics.densityDpi

        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, code)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordService.EXTRA_WIDTH, width)
            putExtra(ScreenRecordService.EXTRA_HEIGHT, height)
            putExtra(ScreenRecordService.EXTRA_DENSITY_DPI, dpi)
            putExtra(ScreenRecordService.EXTRA_FPS, fps)
            putExtra(ScreenRecordService.EXTRA_BITRATE, bitrate)
            putExtra(ScreenRecordService.EXTRA_AUDIO_SOURCE, audioSource)
            putExtra(ScreenRecordService.EXTRA_ENABLE_OVERLAY, overlayTrigger)
        }

        ContextCompat.startForegroundService(context, intent)

        pendingProjectionResultCode = null
        pendingProjectionData = null
    }

    fun pauseRecording(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeRecording(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun stopRecording(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        context.startService(intent)
    }
}
