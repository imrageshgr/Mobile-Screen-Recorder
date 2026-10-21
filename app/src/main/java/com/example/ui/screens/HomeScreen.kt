package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.DeviceProfile
import com.example.model.RecordingItem
import com.example.model.RecordingState
import com.example.model.ServiceStatus
import com.example.ui.components.AudioSwitchesRow
import com.example.ui.components.RecentRecordingsList
import com.example.ui.components.RecordButton
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.RecordRedPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    status: ServiceStatus,
    profile: DeviceProfile?,
    isDarkTheme: Boolean,
    isAppAudioEnabled: Boolean,
    isMicEnabled: Boolean,
    isGameVoiceChatEnabled: Boolean = false,
    audioBalance: Float = 0.5f,
    voiceMode: String = "TOGGLE",
    isAutoDndEnabled: Boolean,
    isFloatingButtonEnabled: Boolean,
    recentRecordings: List<RecordingItem>,
    onRecordButtonClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onAppAudioToggled: (Boolean) -> Unit,
    onMicToggled: (Boolean) -> Unit,
    onGameVoiceChatToggled: (Boolean) -> Unit = {},
    onAudioBalanceChanged: (Float) -> Unit = {},
    onVoiceModeToggled: () -> Unit = {},
    onGameVoiceChatInfoClick: () -> Unit = {},
    onAutoDndToggled: (Boolean) -> Unit,
    onFloatingButtonToggled: (Boolean) -> Unit,
    onReAnalyzeClick: () -> Unit,
    onThemeToggle: () -> Unit,
    onAboutClick: () -> Unit,
    onRecordingClick: (RecordingItem) -> Unit,
    onRecordingLongClick: (RecordingItem) -> Unit,
    onShareRecording: (RecordingItem) -> Unit,
    onDeleteRecording: (RecordingItem) -> Unit,
    onDeleteAllClick: () -> Unit = {},
    snackbarHostState: SnackbarHostState
) {
    var showMenu by remember { mutableStateOf(false) }
    val isRecordingActive = status.state == RecordingState.RECORDING || status.state == RecordingState.PAUSED
    val isSaving = status.state == RecordingState.PROCESSING

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_logo),
                            contentDescription = "Regonex Recorder Logo",
                            modifier = Modifier.size(28.dp)
                        )

                        Text(
                            text = "Regonex Recorder",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("home_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Re-analyze device") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = RecordRedPrimary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onReAnalyzeClick()
                                },
                                modifier = Modifier.testTag("menu_reanalyze")
                            )

                            DropdownMenuItem(
                                text = { Text("Floating button") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = RecordRedPrimary
                                    )
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = isFloatingButtonEnabled,
                                        onCheckedChange = { onFloatingButtonToggled(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = RecordRedPrimary
                                        ),
                                        modifier = Modifier.scale(0.85f)
                                    )
                                },
                                onClick = {
                                    onFloatingButtonToggled(!isFloatingButtonEnabled)
                                },
                                modifier = Modifier.testTag("menu_floating_button")
                            )

                            Text(
                                text = "Note: Floating button can appear in videos when fully visible.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                            )

                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Voice control mode")
                                        Text(
                                            text = if (voiceMode == "HOLD_TO_TALK") "Hold to talk (press & hold)" else "Toggle mode (tap on/off)",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = RecordRedPrimary
                                    )
                                },
                                onClick = {
                                    onVoiceModeToggled()
                                },
                                modifier = Modifier.testTag("menu_voice_mode")
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            DropdownMenuItem(
                                text = { Text("Auto Do Not Disturb") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.DoNotDisturbOn,
                                        contentDescription = null
                                    )
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = isAutoDndEnabled,
                                        onCheckedChange = { onAutoDndToggled(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = RecordRedPrimary
                                        ),
                                        modifier = Modifier.scale(0.85f)
                                    )
                                },
                                onClick = {
                                    onAutoDndToggled(!isAutoDndEnabled)
                                },
                                modifier = Modifier.testTag("menu_auto_dnd")
                            )

                            DropdownMenuItem(
                                text = { Text(if (isDarkTheme) "Light theme" else "Dark theme") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isDarkTheme) Icons.Default.Brightness7 else Icons.Default.Brightness4,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onThemeToggle()
                                },
                                modifier = Modifier.testTag("menu_toggle_theme")
                            )

                            DropdownMenuItem(
                                text = { Text("Game voice chat info") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = AccentGreen
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onGameVoiceChatInfoClick()
                                },
                                modifier = Modifier.testTag("menu_game_voice_chat_info")
                            )

                            DropdownMenuItem(
                                text = { Text("About Regonex Recorder") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onAboutClick()
                                },
                                modifier = Modifier.testTag("menu_about")
                            )

                            if (recentRecordings.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text("Delete all recordings", color = RecordRedPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = RecordRedPrimary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onDeleteAllClick()
                                    },
                                    modifier = Modifier.testTag("menu_delete_all")
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val isWideLayout = maxWidth >= 680.dp

            if (isWideLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))

                        OptimizationBadge(profile != null)

                        if (isSaving) {
                            SavingProgressCard()
                        }

                        RecordButton(
                            status = status,
                            onRecordClick = onRecordButtonClick,
                            onPauseClick = onPauseClick,
                            onResumeClick = onResumeClick
                        )

                        AudioSwitchesRow(
                            isAppAudioEnabled = isAppAudioEnabled,
                            isMicEnabled = isMicEnabled,
                            isGameVoiceChatEnabled = isGameVoiceChatEnabled,
                            audioBalance = audioBalance,
                            voiceMode = voiceMode,
                            isFloatingButtonEnabled = isFloatingButtonEnabled,
                            isEnabled = !isRecordingActive && !isSaving,
                            onAppAudioToggled = onAppAudioToggled,
                            onMicToggled = onMicToggled,
                            onGameVoiceChatToggled = onGameVoiceChatToggled,
                            onAudioBalanceChanged = onAudioBalanceChanged,
                            onVoiceModeToggled = onVoiceModeToggled,
                            onFloatingButtonToggled = onFloatingButtonToggled,
                            onInfoClick = onGameVoiceChatInfoClick
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        RecentRecordingsList(
                            recordings = recentRecordings,
                            onItemClick = onRecordingClick,
                            onItemLongClick = onRecordingLongClick,
                            onShareClick = onShareRecording,
                            onDeleteClick = onDeleteRecording,
                            onDeleteAllClick = onDeleteAllClick
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OptimizationBadge(profile != null)

                    if (isSaving) {
                        SavingProgressCard()
                    }

                    RecordButton(
                        status = status,
                        onRecordClick = onRecordButtonClick,
                        onPauseClick = onPauseClick,
                        onResumeClick = onResumeClick
                    )

                    AudioSwitchesRow(
                        isAppAudioEnabled = isAppAudioEnabled,
                        isMicEnabled = isMicEnabled,
                        isGameVoiceChatEnabled = isGameVoiceChatEnabled,
                        audioBalance = audioBalance,
                        voiceMode = voiceMode,
                        isFloatingButtonEnabled = isFloatingButtonEnabled,
                        isEnabled = !isRecordingActive && !isSaving,
                        onAppAudioToggled = onAppAudioToggled,
                        onMicToggled = onMicToggled,
                        onGameVoiceChatToggled = onGameVoiceChatToggled,
                        onAudioBalanceChanged = onAudioBalanceChanged,
                        onVoiceModeToggled = onVoiceModeToggled,
                        onFloatingButtonToggled = onFloatingButtonToggled,
                        onInfoClick = onGameVoiceChatInfoClick
                    )

                    RecentRecordingsList(
                        recordings = recentRecordings,
                        onItemClick = onRecordingClick,
                        onItemLongClick = onRecordingLongClick,
                        onShareClick = onShareRecording,
                        onDeleteClick = onDeleteRecording,
                        onDeleteAllClick = onDeleteAllClick
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SavingProgressCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, RecordRedPrimary.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("saving_progress_card")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                color = RecordRedPrimary
            )
            Column {
                Text(
                    text = "Saving video...",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Finalizing MP4 and optimizing gallery indexing...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OptimizationBadge(isOptimized: Boolean) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.testTag("optimization_badge")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isOptimized) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )

            Text(
                text = "Optimized for your device",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
