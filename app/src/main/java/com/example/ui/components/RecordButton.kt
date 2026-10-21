package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.RecordingState
import com.example.model.ServiceStatus
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.RecordRedGlow
import com.example.ui.theme.RecordRedPrimary
import com.example.ui.theme.RecordRedVariant

@Composable
fun RecordButton(
    status: ServiceStatus,
    onRecordClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRecording = status.state == RecordingState.RECORDING
    val isPaused = status.state == RecordingState.PAUSED
    val isBusy = isRecording || isPaused

    // Pulsing animation when actively recording
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.22f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = if (isRecording) 0.55f else 0f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Main circular button with outer pulse rings
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(170.dp)
        ) {
            // Animated outer glow ring
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(RecordRedPrimary.copy(alpha = pulseAlpha))
                )
            } else if (isPaused) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(AccentAmber.copy(alpha = 0.25f))
                )
            }

            // Outer decorative ring
            Box(
                modifier = Modifier
                    .size(126.dp)
                    .clip(CircleShape)
                    .border(
                        width = 3.dp,
                        color = when {
                            isRecording -> RecordRedPrimary.copy(alpha = 0.7f)
                            isPaused -> AccentAmber.copy(alpha = 0.7f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        },
                        shape = CircleShape
                    )
            )

            // Central Touch Target Button
            val interactionSource = remember { MutableInteractionSource() }
            val buttonBrush = when {
                isRecording -> Brush.verticalGradient(listOf(RecordRedPrimary, RecordRedVariant))
                isPaused -> Brush.verticalGradient(listOf(AccentAmber, Color(0xFFE65100)))
                else -> Brush.verticalGradient(listOf(RecordRedPrimary, RecordRedVariant))
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(108.dp)
                    .shadow(
                        elevation = if (isBusy) 14.dp else 8.dp,
                        shape = CircleShape,
                        spotColor = if (isBusy) RecordRedPrimary else Color.Black
                    )
                    .clip(CircleShape)
                    .background(buttonBrush)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onRecordClick
                    )
                    .testTag("record_button")
                    .semantics {
                        contentDescription = if (isBusy) "Stop recording" else "Start screen recording"
                    }
            ) {
                if (isBusy) {
                    // Square Stop glyph
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .shadow(2.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                    )
                } else {
                    // Videocam / Record Glyph
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(46.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Timer and Action controls when recording or paused
        if (isBusy) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    // Live status dot
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isPaused) AccentAmber else RecordRedPrimary)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (isPaused) "PAUSED • ${status.formattedElapsedTime}" else status.formattedElapsedTime,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Pause / Resume IconButton
                    IconButton(
                        onClick = {
                            if (isPaused) onResumeClick() else onPauseClick()
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .testTag("pause_resume_button")
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            tint = if (isPaused) AccentAmber else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        } else {
            Text(
                text = "Tap to Record Screen",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
