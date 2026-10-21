package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.model.RecordingItem
import com.example.model.RecordingState
import com.example.service.ScreenRecordService
import com.example.ui.components.CountdownOverlay
import com.example.ui.components.VideoThumbnail
import com.example.ui.theme.DarkBorderGray
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.LightBorderGray
import com.example.ui.theme.LightSurface
import com.example.ui.theme.PureBlack
import com.example.ui.theme.PureWhite
import com.example.ui.theme.ScreenCapTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.onProjectionPermissionGranted(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission was not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notifications are needed to show recording time and controls.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.setAudioSource(ScreenRecordService.AUDIO_SOURCE_MIC)
            Toast.makeText(this, "Microphone enabled.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission denied. Switched to Internal Audio.", Toast.LENGTH_SHORT).show()
            viewModel.setAudioSource(ScreenRecordService.AUDIO_SOURCE_INTERNAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val isDarkTheme by viewModel.isDarkTheme.collectAsState()
            val currentTab by viewModel.currentTab.collectAsState()
            val serviceStatus by viewModel.serviceStatus.collectAsState()
            val recentRecordings by viewModel.recentRecordings.collectAsState()
            val selectedResolution by viewModel.selectedResolution.collectAsState()
            val selectedFps by viewModel.selectedFps.collectAsState()
            val selectedBitrate by viewModel.selectedBitrate.collectAsState()
            val selectedAudioSource by viewModel.selectedAudioSource.collectAsState()
            val enableOverlayTrigger by viewModel.enableOverlayTrigger.collectAsState()
            val countdownValue by viewModel.countdownValue.collectAsState()
            val activePlayingItem by viewModel.activePlayingItem.collectAsState()
            val selectedItemForAction by viewModel.selectedItemForAction.collectAsState()
            val showDeleteConfirmation by viewModel.showDeleteConfirmation.collectAsState()
            val showDeleteAllConfirmation by viewModel.showDeleteAllConfirmation.collectAsState()

            val isAnalyzing by viewModel.isAnalyzing.collectAsState()
            val analysisProgress by viewModel.analysisProgress.collectAsState()
            val deviceProfile by viewModel.deviceProfile.collectAsState()

            val snackbarHostState = remember { SnackbarHostState() }

            val isRecordingFlowActive by ScreenRecordService.isRecordingFlow.collectAsState()
            val lastSavedVideoTimestamp by ScreenRecordService.lastSavedVideoTimestamp.collectAsState()

            LaunchedEffect(lastSavedVideoTimestamp) {
                if (lastSavedVideoTimestamp > 0L) {
                    viewModel.refreshRecordings()
                    snackbarHostState.showSnackbar("Recording saved")
                    Toast.makeText(this@MainActivity, "Recording saved", Toast.LENGTH_SHORT).show()
                }
            }

            LaunchedEffect(isRecordingFlowActive) {
                if (!isRecordingFlowActive) {
                    viewModel.refreshRecordings()
                }
            }

            LaunchedEffect(serviceStatus.errorMessage) {
                serviceStatus.errorMessage?.let { error ->
                    snackbarHostState.showSnackbar(error)
                }
            }

            LaunchedEffect(Unit) {
                viewModel.uiEvents.collect { message ->
                    snackbarHostState.showSnackbar(message)
                }
            }

            ScreenCapTheme(darkTheme = isDarkTheme) {
                Scaffold(
                    topBar = {
                        GamingTopAppBar(
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = { viewModel.toggleTheme() }
                        )
                    },
                    bottomBar = {
                        GamingBottomNavigation(
                            currentTab = currentTab,
                            recordingsCount = recentRecordings.size,
                            onTabSelected = { viewModel.setTab(it) }
                        )
                    },
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            0 -> RecordTabScreen(
                                serviceStatus = serviceStatus,
                                resolution = selectedResolution,
                                fps = selectedFps,
                                bitrate = selectedBitrate,
                                audioSource = selectedAudioSource,
                                enableOverlay = enableOverlayTrigger,
                                deviceProfile = deviceProfile,
                                onResolutionSelected = { viewModel.setResolution(it) },
                                onFpsSelected = { viewModel.setFps(it) },
                                onBitrateSelected = { viewModel.setBitrate(it) },
                                onAudioSourceSelected = { source ->
                                    if (source != ScreenRecordService.AUDIO_SOURCE_MUTE) {
                                        if (ContextCompat.checkSelfPermission(
                                                this@MainActivity,
                                                Manifest.permission.RECORD_AUDIO
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@RecordTabScreen
                                        }
                                    }
                                    viewModel.setAudioSource(source)
                                },
                                onEnableOverlayToggled = { enable ->
                                    if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Enable 'Display over other apps' to use the invisible corner stop trigger.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                        startActivity(intent)
                                    }
                                    viewModel.setEnableOverlayTrigger(enable)
                                },
                                onStartRecordClick = {
                                    val isRecording = serviceStatus.state == RecordingState.RECORDING ||
                                            serviceStatus.state == RecordingState.PAUSED

                                    if (isRecording) {
                                        viewModel.stopRecording(this@MainActivity)
                                    } else {
                                        if (selectedAudioSource != ScreenRecordService.AUDIO_SOURCE_MUTE &&
                                            ContextCompat.checkSelfPermission(
                                                this@MainActivity,
                                                Manifest.permission.RECORD_AUDIO
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@RecordTabScreen
                                        }
                                        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                    }
                                },
                                onPauseClick = { viewModel.pauseRecording(this@MainActivity) },
                                onResumeClick = { viewModel.resumeRecording(this@MainActivity) },
                                onRetuneDevice = { viewModel.runDeviceOptimization() }
                            )
                            1 -> RecordingsTabScreen(
                                recordings = recentRecordings,
                                onRefreshClick = { viewModel.refreshRecordings() },
                                onPlayClick = { viewModel.openPlayer(it) },
                                onShareClick = { item ->
                                    try {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(Intent.EXTRA_STREAM, item.uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        startActivity(Intent.createChooser(shareIntent, "Share Recording"))
                                    } catch (e: Exception) {
                                        Toast.makeText(this@MainActivity, "Could not share: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDeleteClick = {
                                    viewModel.selectItemForAction(it)
                                    viewModel.confirmDelete(true)
                                },
                                onDeleteAllClick = {
                                    viewModel.confirmDeleteAll(true)
                                }
                            )
                        }

                        // Countdown overlay before recording starts
                        countdownValue?.let { count ->
                            CountdownOverlay(
                                countdownSeconds = count,
                                onSkip = { viewModel.skipCountdown() },
                                onCancel = { viewModel.cancelCountdown() }
                            )
                        }
                    }
                }

                // In-App Video Player Dialog
                activePlayingItem?.let { item ->
                    InAppVideoPlayerDialog(
                        recordingItem = item,
                        onDismiss = { viewModel.closePlayer() }
                    )
                }

                // Delete Single Recording Confirmation Dialog
                if (showDeleteConfirmation && selectedItemForAction != null) {
                    AlertDialog(
                        onDismissRequest = { viewModel.confirmDelete(false) },
                        title = { Text("Delete Recording?", fontWeight = FontWeight.Bold) },
                        text = {
                            Text("Permanently delete '${selectedItemForAction?.title}' from this device? This action cannot be undone.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    selectedItemForAction?.let { viewModel.deleteRecording(it) }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface,
                                    contentColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Text("Delete")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.confirmDelete(false) }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Delete All Recordings Confirmation Dialog
                if (showDeleteAllConfirmation) {
                    AlertDialog(
                        onDismissRequest = { viewModel.confirmDeleteAll(false) },
                        title = { Text("Delete All Recordings?", fontWeight = FontWeight.Bold) },
                        text = {
                            Text("Permanently delete all ${recentRecordings.size} screen recordings from your device? This cannot be undone.")
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.deleteAllRecordings() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface,
                                    contentColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Text("Delete All")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.confirmDeleteAll(false) }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // First-Run / Manual Hardware Calibration Scanner Dialog
                if (isAnalyzing) {
                    HardwareScannerProgressDialog(progress = analysisProgress)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GamingTopAppBar(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = "App Logo",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = "Gaming Screen Recorder",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier.testTag("theme_toggle_button")
            ) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    contentDescription = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.border(width = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    )
}

@Composable
private fun GamingBottomNavigation(
    currentTab: Int,
    recordingsCount: Int,
    onTabSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.border(width = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        NavigationBarItem(
            selected = currentTab == 0,
            onClick = { onTabSelected(0) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.FiberManualRecord,
                    contentDescription = "Record Tab"
                )
            },
            label = { Text("Record", fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.surface,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.onSurface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )

        NavigationBarItem(
            selected = currentTab == 1,
            onClick = { onTabSelected(1) },
            icon = {
                if (recordingsCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            ) {
                                Text(recordingsCount.toString())
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VideoLibrary,
                            contentDescription = "Recordings Tab"
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.VideoLibrary,
                        contentDescription = "Recordings Tab"
                    )
                }
            },
            label = { Text("Recordings", fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.surface,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.onSurface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun RecordTabScreen(
    serviceStatus: com.example.model.ServiceStatus,
    resolution: String,
    fps: Int,
    bitrate: Int,
    audioSource: String,
    enableOverlay: Boolean,
    deviceProfile: com.example.model.DeviceProfile?,
    onResolutionSelected: (String) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onBitrateSelected: (Int) -> Unit,
    onAudioSourceSelected: (String) -> Unit,
    onEnableOverlayToggled: (Boolean) -> Unit,
    onStartRecordClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRetuneDevice: () -> Unit
) {
    val isRecording = serviceStatus.state == RecordingState.RECORDING
    val isPaused = serviceStatus.state == RecordingState.PAUSED
    val isBusy = isRecording || isPaused

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Status & Primary Action Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        )
                        Text(
                            text = when {
                                isPaused -> "Recording Paused"
                                isRecording -> "Recording Active"
                                else -> "Ready to Record"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (isBusy) {
                        Text(
                            text = serviceStatus.formattedElapsedTime,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onStartRecordClick,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .testTag("primary_record_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isBusy) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = if (isBusy) "Stop & Save" else "Start Recording",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }

                        if (isBusy) {
                            OutlinedButton(
                                onClick = if (isPaused) onResumeClick else onPauseClick,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .size(56.dp)
                                    .testTag("pause_resume_button")
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = if (isPaused) "Resume" else "Pause"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Hardware Device Tuning Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Auto-Tuned Hardware Profile",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (deviceProfile != null) {
                                "${deviceProfile.encoderName} • ${resolution} (${deviceProfile.width}x${deviceProfile.height}) @ ${fps} FPS"
                            } else {
                                "Calibrated for your mobile chipset & screen specifications"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onRetuneDevice,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("rescan_device_button")
                    ) {
                        Text("Re-scan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Audio Source Selection Card
        item {
            SettingCard(title = "Audio Source", icon = Icons.Filled.VolumeUp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SegmentButton(
                        text = "Internal Game",
                        icon = Icons.Filled.VolumeUp,
                        selected = audioSource == ScreenRecordService.AUDIO_SOURCE_INTERNAL,
                        onClick = { onAudioSourceSelected(ScreenRecordService.AUDIO_SOURCE_INTERNAL) },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentButton(
                        text = "Microphone",
                        icon = Icons.Filled.Mic,
                        selected = audioSource == ScreenRecordService.AUDIO_SOURCE_MIC,
                        onClick = { onAudioSourceSelected(ScreenRecordService.AUDIO_SOURCE_MIC) },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentButton(
                        text = "Mute",
                        icon = Icons.Filled.VolumeOff,
                        selected = audioSource == ScreenRecordService.AUDIO_SOURCE_MUTE,
                        onClick = { onAudioSourceSelected(ScreenRecordService.AUDIO_SOURCE_MUTE) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = when (audioSource) {
                        ScreenRecordService.AUDIO_SOURCE_INTERNAL -> "Captures game sound directly with zero background noise."
                        ScreenRecordService.AUDIO_SOURCE_MIC -> "Records microphone audio with voice commentary."
                        else -> "Video recorded with no sound."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Resolution Card
        item {
            SettingCard(title = "Resolution", icon = Icons.Filled.Videocam) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SegmentButton(
                        text = "720p (HD)",
                        selected = resolution == "720p",
                        onClick = { onResolutionSelected("720p") },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentButton(
                        text = "1080p (Full HD)",
                        selected = resolution == "1080p",
                        onClick = { onResolutionSelected("1080p") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Frame Rate (FPS) Card
        item {
            SettingCard(title = "Frame Rate", icon = Icons.Filled.PlayArrow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SegmentButton(
                        text = "30 FPS",
                        selected = fps == 30,
                        onClick = { onFpsSelected(30) },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentButton(
                        text = "60 FPS (Zero Lag)",
                        selected = fps == 60,
                        onClick = { onFpsSelected(60) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Bitrate Card
        item {
            SettingCard(title = "Bitrate", icon = Icons.Filled.Settings) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SegmentButton(
                        text = "8 Mbps",
                        selected = bitrate == 8_000_000,
                        onClick = { onBitrateSelected(8_000_000) },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentButton(
                        text = "12 Mbps",
                        selected = bitrate == 12_000_000,
                        onClick = { onBitrateSelected(12_000_000) },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentButton(
                        text = "16 Mbps",
                        selected = bitrate == 16_000_000,
                        onClick = { onBitrateSelected(16_000_000) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Invisible Overlay Stop Trigger Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Invisible Corner Stop Trigger",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Places an invisible 0-alpha tap target in the top-right corner to stop recording without opening notification shade.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = enableOverlay,
                        onCheckedChange = onEnableOverlayToggled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.testTag("overlay_trigger_switch")
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareScannerProgressDialog(
    progress: com.example.optimizer.AnalysisProgress
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { /* Modal: Cannot dismiss while calibration is running */ },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = "Scanning Hardware",
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = "Hardware Auto-Optimization",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = progress.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                LinearProgressIndicator(
                    progress = { progress.percentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.onSurface,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                Text(
                    text = progress.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun SegmentButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 6.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecordingsTabScreen(
    recordings: List<RecordingItem>,
    onRefreshClick: () -> Unit,
    onPlayClick: (RecordingItem) -> Unit,
    onShareClick: (RecordingItem) -> Unit,
    onDeleteClick: (RecordingItem) -> Unit,
    onDeleteAllClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Recordings (${recordings.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onRefreshClick,
                    modifier = Modifier.testTag("refresh_recordings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh Recordings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (recordings.isNotEmpty()) {
                    TextButton(
                        onClick = onDeleteAllClick,
                        modifier = Modifier.testTag("delete_all_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = "Delete All",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Delete All",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "No recordings yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Switch to the Record tab to capture your gameplay with internal audio.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(recordings, key = { it.id }) { item ->
                    RecordingCard(
                        item = item,
                        onPlayClick = { onPlayClick(item) },
                        onShareClick = { onShareClick(item) },
                        onDeleteClick = { onDeleteClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(
    item: RecordingItem,
    onPlayClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with duration badge
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                VideoThumbnail(
                    uri = item.uri,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.formattedDuration,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Information
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.formattedSize} • ${item.formattedDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action Buttons
            IconButton(
                onClick = onPlayClick,
                modifier = Modifier.testTag("play_video_button_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play Video",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = onShareClick,
                modifier = Modifier.testTag("share_video_button_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Share Video",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.testTag("delete_video_button_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete Video",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InAppVideoPlayerDialog(
    recordingItem: RecordingItem,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val mediaController = MediaController(ctx)
                        mediaController.setAnchorView(this)
                        setMediaController(mediaController)
                        setVideoURI(recordingItem.uri)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            )

            // Close button in top-left
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close Player",
                    tint = Color.White
                )
            }

            // Title banner in top-center
            Text(
                text = recordingItem.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
