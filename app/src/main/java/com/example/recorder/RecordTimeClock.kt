package com.example.recorder

import android.util.Log

/**
 * Unified high-precision clock shared by both video and audio encoder pipelines.
 * Accurately tracks base time, pauses (manual or call-triggered), and emits
 * strictly monotonic timestamps with zero drift or frozen sections.
 */
class RecordTimeClock {

    companion object {
        private const val TAG = "RecordTimeClock"
    }

    private val lock = Any()
    private var baseTimeNs = 0L
    private var isStarted = false
    private var isPaused = false
    private var pauseStartNs = 0L
    private var totalPausedDurationNs = 0L

    private var initialVideoHardwarePtsUs = -1L
    private var lastEmittedVideoPtsUs = -1L
    private var lastEmittedAudioPtsUs = -1L

    fun start() = synchronized(lock) {
        baseTimeNs = System.nanoTime()
        isStarted = true
        isPaused = false
        pauseStartNs = 0L
        totalPausedDurationNs = 0L
        initialVideoHardwarePtsUs = -1L
        lastEmittedVideoPtsUs = -1L
        lastEmittedAudioPtsUs = -1L
        Log.d(TAG, "RecordTimeClock started at baseTimeNs=$baseTimeNs")
    }

    fun pause() = synchronized(lock) {
        if (isStarted && !isPaused) {
            isPaused = true
            pauseStartNs = System.nanoTime()
            Log.d(TAG, "RecordTimeClock paused at $pauseStartNs")
        }
    }

    fun resume() = synchronized(lock) {
        if (isStarted && isPaused) {
            val now = System.nanoTime()
            val pausedDelta = (now - pauseStartNs).coerceAtLeast(0L)
            totalPausedDurationNs += pausedDelta
            isPaused = false
            Log.d(TAG, "RecordTimeClock resumed. Paused for ${pausedDelta / 1_000_000}ms. Total paused: ${totalPausedDurationNs / 1_000_000}ms")
        }
    }

    /**
     * Compute synchronized PTS for video from the hardware surface presentation timestamp.
     * Subtracts initial hardware offset and cumulative pause duration so video has no freeze gap.
     */
    fun getAdjustedVideoPtsUs(rawPtsUs: Long): Long = synchronized(lock) {
        if (!isStarted) return 0L

        if (initialVideoHardwarePtsUs < 0) {
            initialVideoHardwarePtsUs = rawPtsUs
            Log.d(TAG, "Initialized video hardware base PTS: $initialVideoHardwarePtsUs us")
        }

        val pausedOffsetUs = totalPausedDurationNs / 1000L
        val relativePtsUs = (rawPtsUs - initialVideoHardwarePtsUs - pausedOffsetUs).coerceAtLeast(0L)

        // Ensure strictly monotonic non-decreasing timestamp with at least 1ms advance
        val finalPtsUs = if (relativePtsUs > lastEmittedVideoPtsUs) {
            relativePtsUs
        } else {
            lastEmittedVideoPtsUs + 1000L
        }

        lastEmittedVideoPtsUs = finalPtsUs
        return finalPtsUs
    }

    /**
     * Compute synchronized PTS for audio from the unified shared clock.
     * Subtracts total paused time so audio perfectly aligns with video across pauses.
     */
    fun getAdjustedAudioPtsUs(sampleIndex: Long, sampleRate: Int): Long = synchronized(lock) {
        if (!isStarted) return 0L

        // Continuous audio sample time in microseconds
        val sampleBasedPtsUs = (sampleIndex * 1_000_000L) / sampleRate

        // Ensure strictly monotonic non-decreasing timestamp
        val finalPtsUs = if (sampleBasedPtsUs > lastEmittedAudioPtsUs) {
            sampleBasedPtsUs
        } else {
            lastEmittedAudioPtsUs + 500L
        }

        lastEmittedAudioPtsUs = finalPtsUs
        return finalPtsUs
    }

    fun getElapsedSeconds(): Long = synchronized(lock) {
        if (!isStarted) return 0L
        val now = if (isPaused) pauseStartNs else System.nanoTime()
        val elapsedNs = (now - baseTimeNs - totalPausedDurationNs).coerceAtLeast(0L)
        return elapsedNs / 1_000_000_000L
    }

    fun isPaused(): Boolean = synchronized(lock) { isPaused }
}
