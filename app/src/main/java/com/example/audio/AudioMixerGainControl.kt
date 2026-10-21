package com.example.audio

/**
 * Interface for controlling microphone audio gain in the audio mixer without restarting
 * the encoder or interrupting the master timeline.
 */
interface AudioMixerGainControl {
    /**
     * Smoothly sets the microphone gain to [targetGain] (0.0f = muted, 1.0f = active)
     * over [fadeDurationMs] milliseconds.
     */
    fun setMicGain(targetGain: Float, fadeDurationMs: Long = 30L)

    /**
     * Returns the current target microphone gain.
     */
    fun getMicGain(): Float

    /**
     * Checks if the microphone audio capture thread is running and reading PCM data.
     */
    fun isMicRunning(): Boolean

    /**
     * Checks if RECORD_AUDIO permission was granted and mic capture is permitted.
     */
    fun isMicPermitted(): Boolean
}
