package com.example.model

import android.net.Uri

enum class RecordingState {
    IDLE,
    COUNTDOWN,
    RECORDING,
    PAUSED,
    PROCESSING,
    ERROR
}

data class DiagnosticInfo(
    val lastLogMessage: String = "Engine Idle. Ready to record.",
    val step: String = "READY",
    val filePath: String = "None (No active recording)",
    val fileSizeBytes: Long = 0L,
    val videoResolution: String = "1080x1920 (Strict 16-aligned)",
    val fps: Int = 60,
    val bitrateMbps: Int = 12,
    val audioSource: String = "Internal Audio",
    val muxerStarted: Boolean = false,
    val videoTrackRegistered: Boolean = false,
    val audioTrackRegistered: Boolean = false,
    val lastError: String? = null
) {
    val formattedFileSize: String
        get() = when {
            fileSizeBytes <= 0 -> "0 KB"
            fileSizeBytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB (%d B)", fileSizeBytes / 1024.0, fileSizeBytes)
            else -> String.format(java.util.Locale.US, "%.2f MB (%d B)", fileSizeBytes / (1024.0 * 1024.0), fileSizeBytes)
        }
}

data class ServiceStatus(
    val state: RecordingState = RecordingState.IDLE,
    val elapsedSeconds: Long = 0,
    val isMicEnabled: Boolean = false,
    val lastRecordedUri: Uri? = null,
    val errorMessage: String? = null,
    val diagnostic: DiagnosticInfo = DiagnosticInfo()
) {
    val formattedElapsedTime: String
        get() {
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60
            val hours = minutes / 60
            return if (hours > 0) {
                String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes % 60, seconds)
            } else {
                String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }
}
