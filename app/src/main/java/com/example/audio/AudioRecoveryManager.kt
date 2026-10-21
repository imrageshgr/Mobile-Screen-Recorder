package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Monitors hardware audio routing changes (headphone plug/unplug, Bluetooth connect/disconnect)
 * and orchestrates non-blocking hot-restarts of the affected AudioRecord clients
 * without interrupting the master timeline or video recording.
 */
class AudioRecoveryManager(
    private val context: Context,
    private val healthMonitor: AudioHealthMonitor,
    private val onRestartAudioRequested: (reason: String) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecoveryManager"
        private const val DEBOUNCE_MS = 1200L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var deviceCallback: AudioDeviceCallback? = null
    private var headsetReceiver: BroadcastReceiver? = null
    private var lastRestartTimeMs = 0L

    fun start() {
        registerAudioDeviceCallback()
        registerBroadcastReceiver()
        Log.d(TAG, "AudioRecoveryManager started")
    }

    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    handleRoutingChange("Audio device connected (${describeDevices(addedDevices)})")
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    handleRoutingChange("Audio device disconnected (${describeDevices(removedDevices)})")
                }
            }
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            deviceCallback = callback
        }
    }

    private fun describeDevices(devices: Array<out AudioDeviceInfo>?): String {
        if (devices == null || devices.isEmpty()) return "Unknown"
        return devices.joinToString { dev ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dev.productName.toString()
            } else {
                "Type: ${dev.type}"
            }
        }
    }

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        handleRoutingChange("Headphones unplugged (Audio becoming noisy)")
                    }
                    Intent.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", -1)
                        if (state != -1) {
                            handleRoutingChange(if (state == 1) "Wired headset connected" else "Wired headset disconnected")
                        }
                    }
                }
            }
        }
        headsetReceiver = receiver
        try {
            context.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register headset receiver: ${e.message}")
        }
    }

    private fun handleRoutingChange(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRestartTimeMs < DEBOUNCE_MS) {
            return
        }
        lastRestartTimeMs = now

        Log.i(TAG, "Audio routing change detected: $reason")
        healthMonitor.logDeviceChange(reason)
        onRestartAudioRequested(reason)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && deviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering AudioDeviceCallback: ${e.message}")
            }
            deviceCallback = null
        }

        headsetReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering headset receiver: ${e.message}")
            }
            headsetReceiver = null
        }
        Log.d(TAG, "AudioRecoveryManager stopped")
    }
}
