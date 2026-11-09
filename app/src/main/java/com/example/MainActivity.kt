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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
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
import android.os.PowerManager
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.res.painterResource
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
import com.example.ui.screens.LiveTabScreen
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

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* User returned from battery optimization settings */ }

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
            val themeMode by viewModel.themeMode.collectAsState()
            val systemInDarkTheme = isSystemInDarkTheme()
            val isDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> systemInDarkTheme
            }

            DisposableEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                )
                onDispose { }
            }

            val currentTab by viewModel.currentTab.collectAsState()
            val serviceStatus by viewModel.serviceStatus.collectAsState()
            val recentRecordings by viewModel.recentRecordings.collectAsState()
            val selectedResolution by viewModel.selectedResolution.collectAsState()
            val selectedFps by viewModel.selectedFps.collectAsState()
            val selectedBitrate by viewModel.selectedBitrate.collectAsState()
            val selectedAudioSource by viewModel.selectedAudioSource.collectAsState()
            val selectedOrientation by viewModel.selectedOrientation.collectAsState()
            val isFloatingButtonEnabled by viewModel.isFloatingButtonEnabled.collectAsState()
            val shortClipDurationSeconds by viewModel.shortClipDurationSeconds.collectAsState()
            val enableOverlayTrigger by viewModel.enableOverlayTrigger.collectAsState()

            val powerManager = remember { getSystemService(Context.POWER_SERVICE) as PowerManager }
            var isBatteryOptIgnored by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        powerManager.isIgnoringBatteryOptimizations(packageName)
                    } else true
                )
            }
            val countdownValue by viewModel.countdownValue.collectAsState()
            val activePlayingItem by viewModel.activePlayingItem.collectAsState()
            val selectedItemForAction by viewModel.selectedItemForAction.collectAsState()
            val showDeleteConfirmation by viewModel.showDeleteConfirmation.collectAsState()
            val showDeleteAllConfirmation by viewModel.showDeleteAllConfirmation.collectAsState()

            val isAnalyzing by viewModel.isAnalyzing.collectAsState()
            val analysisProgress by viewModel.analysisProgress.collectAsState()
            val deviceProfile by viewModel.deviceProfile.collectAsState()

            val liveStreamConfig by viewModel.liveStreamConfig.collectAsState()
            val liveStreamHistory by viewModel.liveStreamHistory.collectAsState()

            val thermalStatus by viewModel.thermalStatus.collectAsState()
            val showOverheatStopPrompt by viewModel.showOverheatStopPrompt.collectAsState()

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
                            onToggleTheme = { viewModel.toggleTheme(isDarkTheme) }
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
                                audioSource = selectedAudioSource,
                                isBatteryOptIgnored = isBatteryOptIgnored,
                                shortClipDurationSeconds = shortClipDurationSeconds,
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
                                onRequestIgnoreBatteryOpt = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        try {
                                            val intent = Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:$packageName")
                                            )
                                            batteryOptLauncher.launch(intent)
                                        } catch (_: Exception) {
                                            try {
                                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                batteryOptLauncher.launch(intent)
                                            } catch (_: Exception) {}
                                        }
                                    }
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
                                        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                            mediaProjectionManager.createScreenCaptureIntent(
                                                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                                            )
                                        } else {
                                            mediaProjectionManager.createScreenCaptureIntent()
                                        }
                                        projectionLauncher.launch(captureIntent)
                                    }
                                },
                                onPauseClick = { viewModel.pauseRecording(this@MainActivity) },
                                onResumeClick = { viewModel.resumeRecording(this@MainActivity) },
                                onTakeShortClipClick = {
                                    viewModel.takeShortClip(this@MainActivity)
                                    Toast.makeText(this@MainActivity, "Saving ${shortClipDurationSeconds}s short clip to ShortClips folder...", Toast.LENGTH_SHORT).show()
                                },
                                onGoToSettings = { viewModel.setTab(4) }
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
                                onUploadToDriveClick = { item ->
                                    uploadToGoogleDrive(this@MainActivity, item.uri, item.title)
                                },
                                onDeleteClick = {
                                    viewModel.selectItemForAction(it)
                                    viewModel.confirmDelete(true)
                                },
                                onDeleteAllClick = {
                                    viewModel.confirmDeleteAll(true)
                                }
                            )
                            2 -> DriveTabScreen(
                                recordings = recentRecordings,
                                onUploadClick = { item ->
                                    uploadToGoogleDrive(this@MainActivity, item.uri, item.title)
                                },
                                onUploadAllClick = {
                                    val uris = ArrayList(recentRecordings.map { it.uri })
                                    if (uris.isNotEmpty()) {
                                        uploadMultipleToGoogleDrive(this@MainActivity, uris)
                                    } else {
                                        Toast.makeText(this@MainActivity, "No recordings to upload", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onOpenDriveAppClick = { openGoogleDriveApp(this@MainActivity) },
                                onRefreshClick = { viewModel.refreshRecordings() }
                            )
                            3 -> LiveTabScreen(
                                config = liveStreamConfig,
                                resolution = selectedResolution,
                                fps = selectedFps,
                                bitrate = selectedBitrate,
                                onConfigChange = { viewModel.updateLiveStreamConfig(it) },
                                onStartLiveStream = {
                                    val isRecording = serviceStatus.state == RecordingState.RECORDING ||
                                            serviceStatus.state == RecordingState.PAUSED
                                    if (!isRecording) {
                                        // Record broadcast session into permanent history
                                        viewModel.recordLiveStreamSession(
                                            resolution = selectedResolution,
                                            fps = selectedFps,
                                            bitrate = selectedBitrate
                                        )
                                        Toast.makeText(this@MainActivity, "Launching live broadcast to ${liveStreamConfig.platform}...", Toast.LENGTH_SHORT).show()
                                        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                            mediaProjectionManager.createScreenCaptureIntent(
                                                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                                            )
                                        } else {
                                            mediaProjectionManager.createScreenCaptureIntent()
                                        }
                                        projectionLauncher.launch(captureIntent)
                                    } else {
                                        Toast.makeText(this@MainActivity, "Screen recording or broadcast is already active.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            4 -> SettingsTabScreen(
                                deviceProfile = deviceProfile,
                                thermalStatus = thermalStatus,
                                liveStreamHistory = liveStreamHistory,
                                themeMode = themeMode,
                                resolution = selectedResolution,
                                fps = selectedFps,
                                bitrate = selectedBitrate,
                                shortClipDurationSeconds = shortClipDurationSeconds,
                                enableFloatingButton = isFloatingButtonEnabled,
                                isBatteryOptIgnored = isBatteryOptIgnored,
                                onThemeModeSelected = { viewModel.setThemeMode(it) },
                                onResolutionSelected = { viewModel.setResolution(it) },
                                onFpsSelected = { viewModel.setFps(it) },
                                onBitrateSelected = { viewModel.setBitrate(it) },
                                onShortClipDurationSelected = { seconds ->
                                    viewModel.setShortClipDurationSeconds(seconds)
                                },
                                onEnableFloatingButtonToggled = { enable ->
                                    if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Enable 'Display over other apps' to use side slider controls.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                        startActivity(intent)
                                    }
                                    viewModel.setFloatingButtonEnabled(enable)
                                },
                                onRequestIgnoreBatteryOpt = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        try {
                                            val intent = Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:$packageName")
                                            )
                                            batteryOptLauncher.launch(intent)
                                        } catch (_: Exception) {
                                            try {
                                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                batteryOptLauncher.launch(intent)
                                            } catch (_: Exception) {}
                                        }
                                    }
                                },
                                onRetuneDevice = { viewModel.runDeviceOptimization() }
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

                // Interactive Overheating Confirmation Alert
                if (showOverheatStopPrompt) {
                    AlertDialog(
                        onDismissRequest = { viewModel.declineOverheatStop() },
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.BatteryAlert,
                                    contentDescription = null,
                                    tint = Color(0xFFFF3B30),
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = "Device Overheating Alert",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "I can stop because the reason is overheating.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val tempFormatted = String.format(java.util.Locale.US, "%.1f°C", thermalStatus.temperatureCelsius)
                                Text(
                                    text = "Hardware temperature has reached $tempFormatted (${thermalStatus.chargeSource}). Would you like to stop and save the video now?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.acceptOverheatStop(this@MainActivity) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE50914),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Stop & Save Video", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.declineOverheatStop() }) {
                                Text("Keep Recording", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
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

        NavigationBarItem(
            selected = currentTab == 2,
            onClick = { onTabSelected(2) },
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_google_drive),
                    contentDescription = "Drive Tab",
                    modifier = Modifier.size(22.dp)
                )
            },
            label = { Text("Drive", fontWeight = if (currentTab == 2) FontWeight.Bold else FontWeight.Normal) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.surface,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.onSurface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )

        NavigationBarItem(
            selected = currentTab == 3,
            onClick = { onTabSelected(3) },
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_live_broadcast),
                    contentDescription = "Live Tab",
                    modifier = Modifier.size(22.dp)
                )
            },
            label = { Text("Live", fontWeight = if (currentTab == 3) FontWeight.Bold else FontWeight.Normal) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.surface,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.onSurface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )

        NavigationBarItem(
            selected = currentTab == 4,
            onClick = { onTabSelected(4) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings Tab"
                )
            },
            label = { Text("Settings", fontWeight = if (currentTab == 4) FontWeight.Bold else FontWeight.Normal) },
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
    audioSource: String,
    isBatteryOptIgnored: Boolean,
    shortClipDurationSeconds: Int,
    onAudioSourceSelected: (String) -> Unit,
    onRequestIgnoreBatteryOpt: () -> Unit,
    onStartRecordClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onTakeShortClipClick: () -> Unit,
    onGoToSettings: () -> Unit
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

                            Button(
                                onClick = onTakeShortClipClick,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier
                                    .height(56.dp)
                                    .testTag("take_short_clip_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_overlay_clip),
                                        contentDescription = "Take Short Clip",
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "${shortClipDurationSeconds}s Clip",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }


        // Audio Source Selection Card
        item {
            SettingCard(title = "Audio Source", icon = Icons.Filled.VolumeUp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SegmentButton(
                            text = "Game + Mic (Both)",
                            icon = Icons.Filled.VolumeUp,
                            selected = audioSource == ScreenRecordService.AUDIO_SOURCE_BOTH,
                            onClick = { onAudioSourceSelected(ScreenRecordService.AUDIO_SOURCE_BOTH) },
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
                }
                Text(
                    text = when (audioSource) {
                        ScreenRecordService.AUDIO_SOURCE_INTERNAL -> "Captures game sound directly with zero background noise. Microphone is completely muted so your talking voice is NOT recorded."
                        ScreenRecordService.AUDIO_SOURCE_MIC -> "Records microphone audio with voice commentary only. Internal game sound is not captured."
                        ScreenRecordService.AUDIO_SOURCE_BOTH -> "Captures internal game sound AND your microphone voice commentary simultaneously."
                        else -> "Video recorded with no sound."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Battery Optimization Warning (Fix 1:45 sudden stop on Realme / Xiaomi / Oppo)
        if (!isBatteryOptIgnored) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
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
                                    imageVector = Icons.Filled.BatteryAlert,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Prevent Recording Auto-Stop",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Certain phones (Realme, Xiaomi, Oppo) terminate screen recording after ~1.45 minutes. Exclude from battery restrictions for uninterrupted recording.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = onRequestIgnoreBatteryOpt,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.testTag("fix_battery_optimization_button")
                        ) {
                            Text("Fix Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveTabScreen(
    recordings: List<RecordingItem>,
    onUploadClick: (RecordingItem) -> Unit,
    onUploadAllClick: () -> Unit,
    onOpenDriveAppClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Hero Card
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_google_drive),
                                contentDescription = "Google Drive",
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Google Drive",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Save and access your game recordings and short clips directly in your device's Google Drive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenDriveAppClick,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("open_drive_app_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_google_drive),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("Open Drive", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = onUploadAllClick,
                            shape = RoundedCornerShape(10.dp),
                            enabled = recordings.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("upload_all_to_drive_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("Upload All (${recordings.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Section Title & Refresh
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Videos Ready for Backup (${recordings.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = onRefreshClick,
                    modifier = Modifier.testTag("refresh_drive_list_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh Videos",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (recordings.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_drive),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No recordings found to upload",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Record your screen first, then return here to upload your footage directly to Google Drive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(recordings, key = { "drive_${it.id}" }) { item ->
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
                        Box(
                            modifier = Modifier
                                .size(width = 84.dp, height = 56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            VideoThumbnail(
                                uri = item.uri,
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (item.isClip) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    ) {
                                        Text(
                                            text = "CLIP",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            fontSize = 8.sp,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${item.formattedDuration} • ${item.formattedSize}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onUploadClick(item) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.testTag("upload_item_drive_button_${item.id}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_google_drive),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text("Upload", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTabScreen(
    deviceProfile: com.example.model.DeviceProfile?,
    thermalStatus: com.example.system.ThermalStatusInfo = com.example.system.ThermalStatusInfo(),
    liveStreamHistory: List<com.example.model.LiveStreamHistoryItem> = emptyList(),
    themeMode: String = "SYSTEM",
    resolution: String,
    fps: Int,
    bitrate: Int,
    shortClipDurationSeconds: Int,
    enableFloatingButton: Boolean,
    isBatteryOptIgnored: Boolean,
    onThemeModeSelected: (String) -> Unit = {},
    onResolutionSelected: (String) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onBitrateSelected: (Int) -> Unit,
    onShortClipDurationSelected: (Int) -> Unit,
    onEnableFloatingButtonToggled: (Boolean) -> Unit,
    onRequestIgnoreBatteryOpt: () -> Unit,
    onRetuneDevice: () -> Unit
) {
    var showCustomDurationDialog by remember { mutableStateOf(false) }
    var customDurationInput by remember { mutableStateOf(shortClipDurationSeconds.toString()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Theme & Status Bar Visibility Card
        item {
            SettingCard(title = "Theme & Status Bar", icon = Icons.Filled.LightMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SegmentButton(
                        text = "System Default",
                        selected = themeMode == "SYSTEM",
                        onClick = { onThemeModeSelected("SYSTEM") },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentButton(
                        text = "Dark (Black)",
                        selected = themeMode == "DARK",
                        onClick = { onThemeModeSelected("DARK") },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentButton(
                        text = "Light (White)",
                        selected = themeMode == "LIGHT",
                        onClick = { onThemeModeSelected("LIGHT") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "Controls black/white theme. In Dark mode, status bar icons (battery %, signal, clock) are rendered in bright white. In Light mode, they render in crisp dark.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Hardware Thermal & Charging Guard Card
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
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BatteryAlert,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (thermalStatus.isCriticalHeat) Color(0xFFFF3B30) else if (thermalStatus.isOverheating) Color(0xFFFF9500) else Color(0xFF34C759)
                            )
                            Text(
                                text = "Thermal & Charging Guard",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Real-time Temperature pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (thermalStatus.isCriticalHeat) Color(0xFF3B1E1E) else if (thermalStatus.isOverheating) Color(0xFF3B2E1E) else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (thermalStatus.isCriticalHeat) Color(0xFFFF3B30) else if (thermalStatus.isOverheating) Color(0xFFFF9500) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                text = "${String.format(java.util.Locale.US, "%.1f", thermalStatus.temperatureCelsius)}°C",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (thermalStatus.isCriticalHeat) Color(0xFFFF3B30) else if (thermalStatus.isOverheating) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Telemetry Grid
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Power Source", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(thermalStatus.chargeSource, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("OS Thermal Status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(thermalStatus.thermalDescription, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Text(
                        text = "Gaming while charging increases device heat. If overheating occurs, you will be asked: 'I can stop because the reason is overheating.' Recording will NEVER stop automatically unless you confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Live Stream History Card (Permanent, cannot be deleted)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .testTag("live_stream_history_section")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_live_broadcast),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFFE50914)
                            )
                            Text(
                                text = "Live Stream History",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                text = "${liveStreamHistory.size} Broadcast${if (liveStreamHistory.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Permanent audit log of all live broadcasts (Platform, Date, Format, Output Resolution & Frame Rate). This history cannot be deleted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (liveStreamHistory.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No live broadcasts recorded yet. Start streaming from the Live tab to log your history here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            liveStreamHistory.forEach { item ->
                                val platformColor = when (item.platform.uppercase()) {
                                    "YOUTUBE" -> Color(0xFFFF0000)
                                    "TWITCH" -> Color(0xFF9146FF)
                                    "FACEBOOK" -> Color(0xFF1877F2)
                                    "INSTAGRAM" -> Color(0xFFE1306C)
                                    else -> Color(0xFF8E8E93)
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = platformColor.copy(alpha = 0.15f),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    platformColor.copy(alpha = 0.6f)
                                                )
                                            ) {
                                                Text(
                                                    text = item.platform,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = platformColor,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Text(
                                                text = item.dateFormatted,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Text(
                                            text = item.streamTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${item.resolution} @ ${item.fps} FPS (${item.bitrateMbps} Mbps) • ${item.orientation}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        Text(
                                            text = "Endpoint: ${item.streamServer}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
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

        // Short Clip Duration Setting Card
        item {
            val isCustomDuration = shortClipDurationSeconds !in listOf(15, 30, 60, 120)
            SettingCard(title = "Short Clip Duration", icon = Icons.Filled.VideoLibrary) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SegmentButton(
                            text = "15 Sec",
                            selected = shortClipDurationSeconds == 15,
                            onClick = { onShortClipDurationSelected(15) },
                            modifier = Modifier.weight(1f)
                        )
                        SegmentButton(
                            text = "30s (Default)",
                            selected = shortClipDurationSeconds == 30,
                            onClick = { onShortClipDurationSelected(30) },
                            modifier = Modifier.weight(1f)
                        )
                        SegmentButton(
                            text = "60 Sec",
                            selected = shortClipDurationSeconds == 60,
                            onClick = { onShortClipDurationSelected(60) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SegmentButton(
                            text = "2 Min (120s)",
                            selected = shortClipDurationSeconds == 120,
                            onClick = { onShortClipDurationSelected(120) },
                            modifier = Modifier.weight(1f)
                        )
                        SegmentButton(
                            text = if (isCustomDuration) "Custom (${shortClipDurationSeconds}s)" else "Customize...",
                            selected = isCustomDuration,
                            onClick = {
                                customDurationInput = shortClipDurationSeconds.toString()
                                showCustomDurationDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    text = "When you press the Short Clip button on the edge slider during gameplay, the last $shortClipDurationSeconds seconds of footage will be extracted into a separate video and saved into the ShortClips folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Edge Side Slider Controls Card (replaces floating button)
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
                                imageVector = Icons.Filled.Widgets,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Side Slider Edge Controls",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Rests flush on the screen edge as an elegant slider handle. Tapping slides out Pause, Stop & Save, and Short Clip controls over any game or app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = enableFloatingButton,
                        onCheckedChange = onEnableFloatingButtonToggled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.testTag("side_slider_controls_switch")
                    )
                }
            }
        }

        // Battery Optimization Setting
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
                                imageVector = Icons.Filled.BatteryAlert,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Battery Optimization Protection",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isBatteryOptIgnored) {
                                "Active. App is exempt from battery saver restrictions, preventing sudden 1:45 minute terminations."
                            } else {
                                "Not configured. Some phone brands (Realme/Xiaomi/Oppo) terminate recordings after ~1.45 minutes."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    if (!isBatteryOptIgnored) {
                        Button(
                            onClick = onRequestIgnoreBatteryOpt,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.testTag("settings_battery_opt_button")
                        ) {
                            Text("Fix Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = "Protected",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCustomDurationDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDurationDialog = false },
            title = { Text("Customize Short Clip Duration", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter clip duration in seconds (between 5 and 300 seconds):",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = customDurationInput,
                        onValueChange = { customDurationInput = it.filter { ch -> ch.isDigit() } },
                        singleLine = true,
                        label = { Text("Seconds") },
                        placeholder = { Text("e.g. 45") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_clip_duration_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val secs = customDurationInput.toIntOrNull()?.coerceIn(5, 300) ?: 30
                        onShortClipDurationSelected(secs)
                        showCustomDurationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDurationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
    onUploadToDriveClick: (RecordingItem) -> Unit,
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
                        onUploadToDriveClick = { onUploadToDriveClick(item) },
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
    onUploadToDriveClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Column 1 (Left - merged across both rows): Thumbnail with duration badge and play icon
            Box(
                modifier = Modifier
                    .size(width = 100.dp, height = 74.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onPlayClick() }
            ) {
                VideoThumbnail(
                    uri = item.uri,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize()
                )

                // Play icon overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

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

            // Column 2 (Right): Two distinct rows
            // Row 1 (Upper Part): Name of the video, clip badge, size and date
            // Row 2 (Lower Part): Play button, Drive button, Share button, Delete button
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ROW 1: Name of the video & metadata
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (item.isClip) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "CLIP",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 8.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${item.formattedSize} • ${item.formattedDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ROW 2: Action buttons (Play, Drive, Share, Delete)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPlayClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("play_video_button_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play Video",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = onUploadToDriveClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("upload_drive_button_${item.id}")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_drive),
                            contentDescription = "Upload to Google Drive",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onShareClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("share_video_button_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Share Video",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("delete_video_button_${item.id}")
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

private fun uploadToGoogleDrive(context: Context, uri: Uri, title: String) {
    try {
        Toast.makeText(context, "Opening Google Drive...", Toast.LENGTH_SHORT).show()
        val driveIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.google.android.apps.docs")
        }
        val resolveInfos = context.packageManager.queryIntentActivities(driveIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfos.isNotEmpty()) {
            context.startActivity(driveIntent)
        } else {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Upload to Google Drive"
            )
            context.startActivity(chooser)
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open Google Drive: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun uploadMultipleToGoogleDrive(context: Context, uris: ArrayList<Uri>) {
    try {
        Toast.makeText(context, "Uploading ${uris.size} videos to Google Drive...", Toast.LENGTH_SHORT).show()
        val driveIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.google.android.apps.docs")
        }
        val resolveInfos = context.packageManager.queryIntentActivities(driveIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfos.isNotEmpty()) {
            context.startActivity(driveIntent)
        } else {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "video/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Upload All to Google Drive"
            )
            context.startActivity(chooser)
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Could not upload to Google Drive: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun openGoogleDriveApp(context: Context) {
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.docs")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            val driveIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.google.android.apps.docs")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.queryIntentActivities(driveIntent, 0).isNotEmpty()) {
                context.startActivity(driveIntent)
            } else {
                Toast.makeText(context, "Google Drive app is not installed. Opening Google Play Store...", Toast.LENGTH_LONG).show()
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.docs")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
            }
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open Google Drive: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}


