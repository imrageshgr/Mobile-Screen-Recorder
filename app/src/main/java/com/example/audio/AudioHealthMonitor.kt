package com.example.audio

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks and logs audio pipeline stability: buffer overruns, underrun silence-fills,
 * system microphone client silencing, device route switches, and audio restarts.
 */
class AudioHealthMonitor {

    companion object {
        private const val TAG = "AudioHealthMonitor"
    }

    data class HealthEvent(
        val timestampMs: Long,
        val tag: String,
        val message: String,
        val isRecovery: Boolean = false
    ) {
        fun formatted(): String {
            val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            return "[${df.format(Date(timestampMs))}] [$tag] $message"
        }
    }

    interface HealthListener {
        fun onAudioRecoveryOccurred(event: HealthEvent)
        fun onAppAudioBlockSuspected()
    }

    private val eventLog = CopyOnWriteArrayList<HealthEvent>()

    @Volatile
    var hasRecoveredEvents: Boolean = false
        private set

    @Volatile
    var isAppAudioBlockedSuspected: Boolean = false
        private set

    var healthListener: HealthListener? = null

    fun logOverrun(source: String, droppedShorts: Int) {
        val event = HealthEvent(
            timestampMs = System.currentTimeMillis(),
            tag = "OVERRUN",
            message = "$source ring buffer overrun ($droppedShorts samples dropped)"
        )
        addEvent(event)
    }

    fun logUnderrunSilenceFill(source: String, countShorts: Int) {
        val event = HealthEvent(
            timestampMs = System.currentTimeMillis(),
            tag = "SILENCE_FILL",
            message = "$source starved; filled $countShorts silence samples to preserve timeline continuity"
        )
        addEvent(event)
    }

    fun logRestart(source: String, reason: String) {
        val event = HealthEvent(
            timestampMs = System.currentTimeMillis(),
            tag = "RESTART",
            message = "$source restarted: $reason",
            isRecovery = true
        )
        hasRecoveredEvents = true
        addEvent(event)
        healthListener?.onAudioRecoveryOccurred(event)
    }

    fun logMicSilenced(silenced: Boolean) {
        val event = HealthEvent(
            timestampMs = System.currentTimeMillis(),
            tag = "MIC_SILENCE_STATUS",
            message = if (silenced) "System reported microphone client SILENCED by concurrent app" else "Microphone un-silenced by system",
            isRecovery = !silenced
        )
        if (!silenced) {
            hasRecoveredEvents = true
            healthListener?.onAudioRecoveryOccurred(event)
        }
        addEvent(event)
    }

    fun logDeviceChange(desc: String) {
        val event = HealthEvent(
            timestampMs = System.currentTimeMillis(),
            tag = "DEVICE_CHANGE",
            message = "Audio route changed: $desc",
            isRecovery = true
        )
        hasRecoveredEvents = true
        addEvent(event)
        healthListener?.onAudioRecoveryOccurred(event)
    }

    fun logAppAudioBlockSuspected() {
        if (!isAppAudioBlockedSuspected) {
            isAppAudioBlockedSuspected = true
            val event = HealthEvent(
                timestampMs = System.currentTimeMillis(),
                tag = "APP_AUDIO_BLOCK",
                message = "Game audio silent for extended period; game may block internal audio capture (ALLOW_CAPTURE_BY_NONE)"
            )
            addEvent(event)
            healthListener?.onAppAudioBlockSuspected()
        }
    }

    private fun addEvent(event: HealthEvent) {
        eventLog.add(event)
        Log.i(TAG, event.formatted())
    }

    fun getEventCount(): Int = eventLog.size

    fun getAllEvents(): List<HealthEvent> = eventLog.toList()

    fun reset() {
        eventLog.clear()
        hasRecoveredEvents = false
        isAppAudioBlockedSuspected = false
    }
}
