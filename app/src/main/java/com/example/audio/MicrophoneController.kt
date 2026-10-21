package com.example.audio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Operating modes for microphone voice commentary.
 */
enum class VoiceMode {
    TOGGLE,
    HOLD_TO_TALK
}

/**
 * Dedicated controller managing microphone voice recording state, voice modes
 * (Toggle mode vs Hold to talk mode), soft haptics, and gain attenuation in the mixer.
 *
 * Talks directly to [AudioMixerGainControl] via a simple gain interface, keeping
 * audio recording running without pauses, video stops, or timeline gaps.
 */
class MicrophoneController(
    private val context: Context,
    private val gainControl: AudioMixerGainControl?,
    initialVoiceMode: VoiceMode = VoiceMode.TOGGLE,
    initialIsVoiceActive: Boolean = false,
    private val onVoiceModeChanged: ((VoiceMode) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MicrophoneController"
        private const val FADE_DURATION_MS = 30L
    }

    var voiceMode: VoiceMode = initialVoiceMode
        private set

    var isVoiceActive: Boolean = initialIsVoiceActive
        private set

    val isPermitted: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    interface Listener {
        fun onMicStateChanged(isVoiceActive: Boolean, mode: VoiceMode, isPermitted: Boolean)
    }

    private val listeners = mutableListOf<Listener>()

    init {
        // In Hold to talk mode, voice is always muted by default until pressed
        if (voiceMode == VoiceMode.HOLD_TO_TALK) {
            isVoiceActive = false
        }
        applyGainToMixer()
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.onMicStateChanged(isVoiceActive, voiceMode, isPermitted)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val permitted = isPermitted
        listeners.toList().forEach { it.onMicStateChanged(isVoiceActive, voiceMode, permitted) }
    }

    /**
     * Switches between Toggle mode and Hold to talk mode and notifies observers.
     */
    fun toggleVoiceMode(): VoiceMode {
        voiceMode = if (voiceMode == VoiceMode.TOGGLE) {
            VoiceMode.HOLD_TO_TALK
        } else {
            VoiceMode.TOGGLE
        }
        performSoftHaptic()
        if (voiceMode == VoiceMode.HOLD_TO_TALK) {
            // In Hold to talk mode, voice goes silent by default
            isVoiceActive = false
            applyGainToMixer()
        }
        onVoiceModeChanged?.invoke(voiceMode)
        notifyListeners()
        return voiceMode
    }

    fun setVoiceMode(mode: VoiceMode) {
        if (voiceMode != mode) {
            voiceMode = mode
            if (voiceMode == VoiceMode.HOLD_TO_TALK) {
                isVoiceActive = false
                applyGainToMixer()
            }
            onVoiceModeChanged?.invoke(voiceMode)
            notifyListeners()
        }
    }

    /**
     * Handles tap in Toggle mode. Returns true if state toggled, false if denied or ignored.
     */
    fun onMicTapped(): Boolean {
        if (!isPermitted) {
            notifyListeners()
            return false
        }

        if (voiceMode == VoiceMode.TOGGLE) {
            isVoiceActive = !isVoiceActive
            performSoftHaptic()
            applyGainToMixer()
            notifyListeners()
            return true
        }
        return false
    }

    /**
     * Handles finger down in Hold to talk mode.
     */
    fun onHoldPressed(): Boolean {
        if (!isPermitted) {
            notifyListeners()
            return false
        }

        if (voiceMode == VoiceMode.HOLD_TO_TALK) {
            if (!isVoiceActive) {
                isVoiceActive = true
                performSoftHaptic()
                applyGainToMixer()
                notifyListeners()
            }
            return true
        }
        return false
    }

    /**
     * Handles finger release in Hold to talk mode.
     */
    fun onHoldReleased() {
        if (voiceMode == VoiceMode.HOLD_TO_TALK) {
            if (isVoiceActive) {
                isVoiceActive = false
                performSoftHaptic()
                applyGainToMixer()
                notifyListeners()
            }
        }
    }

    /**
     * Called when the system recovery logic restarts the microphone after hardware interruption.
     */
    fun onMicRestarted() {
        Log.d(TAG, "Audio recovery restarted microphone; re-verifying gain state: isVoiceActive=$isVoiceActive")
        applyGainToMixer()
        notifyListeners()
    }

    private fun applyGainToMixer() {
        val targetGain = if (isVoiceActive && isPermitted) 1.0f else 0.0f
        gainControl?.setMicGain(targetGain, FADE_DURATION_MS)
    }

    /**
     * Performs a short, soft haptic feedback pulse, crash-safe on all Android versions (8.0+).
     */
    fun performSoftHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(25)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Soft haptic feedback unavailable: ${e.message}")
        }
    }
}
