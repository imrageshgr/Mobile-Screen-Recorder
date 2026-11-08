package com.example.model

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RecordingItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val dateAddedMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val isClip: Boolean = false
) {
    val formattedDuration: String
        get() {
            if (durationMs <= 0) return "00:00"
            val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hours = minutes / 60
            return if (hours > 0) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes % 60, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }

    val formattedSize: String
        get() {
            if (fileSizeBytes <= 0) return "0 B"
            val kb = fileSizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.getDefault(), "%.1f GB", gb)
                mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.getDefault(), "%.0f KB", kb)
                else -> "$fileSizeBytes B"
            }
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
            return sdf.format(Date(dateAddedMs))
        }
}
